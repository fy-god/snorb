package com.macropilot.app.runtime

import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.model.AppUiModule
import com.macropilot.app.model.CapabilityProfile
import com.macropilot.app.model.InputCapability
import com.macropilot.app.model.MacroAtom
import com.macropilot.app.model.MacroPlan
import com.macropilot.app.model.MacroResult
import com.macropilot.app.model.MacroStatus
import com.macropilot.app.model.MacroTemplate
import com.macropilot.app.model.RuntimeTaskRequirements
import com.macropilot.app.model.SkillSource
import com.macropilot.app.model.UiElementSpec
import com.macropilot.app.model.VerificationCapability
import com.macropilot.app.model.VerifyRule
import com.macropilot.app.model.VerifyRuleType

class MacroExecutor(
    private val inspector: NodeInspector,
    private val profiler: CapabilityProfiler,
    private val actions: DeviceActions,
    private val screenClassifier: ScreenClassifier = ScreenClassifier(),
    private val verifier: MacroVerifier = MacroVerifier(),
    private val runtimeGate: RuntimeGate = RuntimeGate(),
    private val timeoutPolicy: MacroTimeoutPolicy = MacroTimeoutPolicy(),
    private val allowCandidateSkillForFactory: Boolean = false
) {
    private val recoveryPolicy by lazy { RecoveryPolicy(inspector, actions, screenClassifier) }

    fun preflight(template: MacroTemplate, profile: CapabilityProfile): MacroResult {
        val startsWithOpenApp = template.atoms.firstOrNull() is MacroAtom.OpenApp
        val needsTextInput = template.atoms.any { atom ->
            atom is MacroAtom.FocusTextInput || atom is MacroAtom.TypeText || atom is MacroAtom.SearchInApp
        }
        val explicitLowConfidence = template.sideEffectLevel == com.macropilot.app.model.SideEffectLevel.LOW &&
            template.finalVerify.minVerification == VerificationCapability.LOW
        val requirements = RuntimeTaskRequirements(
            needsTextInput = needsTextInput,
            requiresVerification = true,
            allowLowConfidence = startsWithOpenApp || explicitLowConfidence,
            sideEffectLevel = template.sideEffectLevel,
            finalVerify = template.finalVerify
        )
        val gate = runtimeGate.evaluate(
            profile = profile,
            requirements = requirements,
            skillSource = if (allowCandidateSkillForFactory && template.source == SkillSource.CANDIDATE) {
                SkillSource.APPROVED
            } else {
                template.source
            },
            staticSafetyText = staticSafetyText(template)
        )
        if (!gate.allowed) {
            return MacroResult(gate.blockedStatus ?: MacroStatus.FAILED, gate.reason)
        }
        val totalAtomBudget = template.atoms.sumOf { timeoutPolicy.timeoutFor(it) }
        if (totalAtomBudget > timeoutPolicy.totalTaskTimeoutMs) {
            return MacroResult(MacroStatus.FAILED_TIMEOUT, "Template atom budget exceeds 30 seconds")
        }
        return MacroResult(gate.confidenceCeiling, "Preflight passed: ${gate.reason}", profile.verificationCapability)
    }

    fun execute(template: MacroTemplate, appUi: AppUiModule, params: Map<String, String>): MacroResult {
        val startedAt = System.currentTimeMillis()
        if (!actions.isInteractive()) {
            return MacroResult(MacroStatus.FAILED_PRECONDITION, "Screen is off; wake and unlock the device before running a skill")
        }
        if (actions.isKeyguardLocked()) {
            return MacroResult(MacroStatus.FAILED_PRECONDITION, "Device is locked; unlock before running a skill")
        }
        val initialState = inspector.currentState()
        val initialProfile = runtimeProfile(initialState)
        val preflight = preflight(template, initialProfile)
        if (preflight.status.name.startsWith("FAILED")) return preflight
        if (template.atoms.firstOrNull() !is MacroAtom.OpenApp && !isTargetPackage(initialState?.appPackage, appUi)) {
            return MacroResult(
                MacroStatus.FAILED_PRECONDITION,
                "Current-screen skill requires foreground package ${appUi.packageName}; current=${initialState?.appPackage}"
            )
        }

        val plan = MacroPlan(
            templateId = template.id,
            packageName = template.packageName,
            params = params,
            atoms = template.atoms,
            finalVerify = template.finalVerify,
            sideEffectLevel = template.sideEffectLevel
        )

        var confidenceCeiling = MacroStatus.SUCCESS
        var verificationCeiling = VerificationCapability.HIGH
        for (atom in plan.atoms) {
            if (System.currentTimeMillis() - startedAt > timeoutPolicy.totalTaskTimeoutMs) {
                return MacroResult(MacroStatus.FAILED_TIMEOUT, "Task exceeded 30 seconds")
            }
            val result = executeAtom(atom, appUi, plan.params)
            if (result.status.name.startsWith("FAILED")) return result
            confidenceCeiling = lowerStatus(confidenceCeiling, result.status)
            verificationCeiling = lowerVerification(verificationCeiling, result.confidence)
        }

        val finalState = inspector.currentState()
        val finalProfile = runtimeProfile(finalState)
        val final = verifier.verify(plan.finalVerify, plan.params, finalState, finalProfile)
        if (final.status.name.startsWith("FAILED")) return final
        return if (confidenceCeiling == MacroStatus.SUCCESS) {
            final
        } else {
            MacroResult(
                status = confidenceCeiling,
                message = "Task completed with confidence ceiling $confidenceCeiling; final verify: ${final.message}",
                confidence = lowerVerification(verificationCeiling, final.confidence)
            )
        }
    }

    private fun executeAtom(atom: MacroAtom, appUi: AppUiModule, params: Map<String, String>): MacroResult {
        return when (atom) {
            is MacroAtom.OpenApp -> {
                dismissSystemOverlayIfPresent()
                val outcome = actions.openApp(atom.packageName)
                if (!outcome.success) {
                    MacroResult(MacroStatus.FAILED_PRECONDITION, outcome.reason)
                } else if (waitUntil(atom.timeoutMs) { isTargetState(inspector.currentState(), appUi) }) {
                    MacroResult(MacroStatus.SUCCESS_MEDIUM_CONFIDENCE, outcome.reason, VerificationCapability.MEDIUM)
                } else {
                    dismissSystemOverlayIfPresent()
                    actions.openApp(atom.packageName)
                    if (waitUntil(1000L) { isTargetState(inspector.currentState(), appUi) }) {
                        MacroResult(MacroStatus.SUCCESS_MEDIUM_CONFIDENCE, "Launch recovered after dismissing system overlay", VerificationCapability.MEDIUM)
                    } else {
                        MacroResult(MacroStatus.FAILED_TIMEOUT, "OpenApp timed out for ${atom.packageName}")
                    }
                }
            }
            is MacroAtom.EnsureScreen -> {
                val matched = waitUntil(atom.timeoutMs) {
                    val state = inspector.currentState() ?: return@waitUntil false
                    screenClassifier.classify(state, appUi)?.screenId == atom.screenId
                }
                val recovered = matched || recoveryPolicy.recoverToScreen(atom.screenId, appUi, atom.timeoutMs)
                if (recovered) {
                    MacroResult(MacroStatus.SUCCESS, "Screen ${atom.screenId} matched", VerificationCapability.HIGH)
                } else {
                    MacroResult(MacroStatus.FAILED_TIMEOUT, "Screen ${atom.screenId} not reached")
                }
            }
            is MacroAtom.ClickElement -> {
                val element = appUi.findElement(atom.elementId)
                    ?: return MacroResult(MacroStatus.FAILED_PRECONDITION, "Unknown element ${atom.elementId}")
                val outcome = actions.clickByMatcher(element.matchers)
                if (outcome.success) {
                    MacroResult(MacroStatus.SUCCESS_MEDIUM_CONFIDENCE, outcome.reason, VerificationCapability.MEDIUM)
                } else {
                    tryCoordinateFallback(element, atom.timeoutMs)
                }
            }
            is MacroAtom.FocusTextInput -> {
                val element = appUi.findElement(atom.targetElementId)
                    ?: return MacroResult(MacroStatus.FAILED_PRECONDITION, "Unknown focus target ${atom.targetElementId}")
                val toggles = atom.toggleElementIds.mapNotNull { appUi.findElement(it) }
                val outcome = ensureTextInputFocused(element, toggles, atom.timeoutMs)
                if (outcome.success) {
                    MacroResult(
                        MacroStatus.SUCCESS_LOW_CONFIDENCE,
                        outcome.reason,
                        VerificationCapability.LOW
                    )
                } else {
                    MacroResult(MacroStatus.FAILED_INPUT_CHANNEL_MISSING, outcome.reason)
                }
            }
            is MacroAtom.TypeText -> {
                val targetId = atom.targetElementId
                    ?: return MacroResult(MacroStatus.FAILED_PRECONDITION, "TypeText requires targetElementId in Runtime v0")
                val element = appUi.findElement(targetId)
                    ?: return MacroResult(MacroStatus.FAILED_PRECONDITION, "Unknown element $targetId")
                val state = inspector.currentState()
                val profile = runtimeProfile(state)
                if (profile.inputCapability == InputCapability.NONE) {
                    if (!actions.isMacroPilotImeSelected()) {
                        return MacroResult(MacroStatus.FAILED_INPUT_CHANNEL_MISSING, "No input channel at TypeText")
                    }
                }
                val text = params[atom.textParam].orEmpty()
                var outcome = actions.setTextByMatcher(element.matchers, text)
                if (!outcome.success) {
                    actions.clickByMatcher(element.matchers)
                    sleepQuietly(250L)
                    outcome = actions.setTextByMatcher(element.matchers, text)
                }
                if (!outcome.success) {
                    val focused = ensureTextInputFocused(element, emptyList(), atom.timeoutMs)
                    if (!focused.success) {
                        return MacroResult(MacroStatus.FAILED, "${outcome.reason}; ${focused.reason}")
                    }
                    val imeOutcome = actions.commitTextWithMacroPilotIme(text)
                    return if (imeOutcome.success) {
                        MacroResult(
                            MacroStatus.SUCCESS_LOW_CONFIDENCE,
                            "${imeOutcome.reason}; text cannot be high-confidence verified through nodes",
                            VerificationCapability.LOW
                        )
                    } else {
                        MacroResult(MacroStatus.FAILED_INPUT_CHANNEL_MISSING, imeOutcome.reason)
                    }
                }
                val typed = waitUntil(atom.timeoutMs) {
                    inspector.currentState()?.nodes?.any { it.editable && it.text?.contains(text) == true } == true
                }
                if (typed) {
                    MacroResult(MacroStatus.SUCCESS, "Text set and verified", VerificationCapability.HIGH)
                } else {
                    MacroResult(MacroStatus.FAILED_TIMEOUT, "Text did not appear in editable node")
                }
            }
            is MacroAtom.SearchInApp -> {
                val click = executeAtom(MacroAtom.ClickElement(atom.searchEntryElementId, atom.timeoutMs), appUi, params)
                if (click.status.name.startsWith("FAILED")) click else {
                    executeAtom(MacroAtom.TypeText(atom.queryParam, atom.searchEntryElementId, atom.timeoutMs), appUi, params)
                }
            }
            is MacroAtom.SelectItem -> {
                val text = params[atom.itemTextParam].orEmpty()
                if (text.isBlank()) {
                    return MacroResult(MacroStatus.FAILED_PRECONDITION, "SelectItem missing ${atom.itemTextParam}")
                }
                val visible = waitUntil(atom.timeoutMs) { textVisible(text) }
                if (!visible) {
                    return MacroResult(MacroStatus.FAILED_TIMEOUT, "Item text not visible: $text")
                }
                val outcome = actions.clickByVisibleText(text)
                if (outcome.success) {
                    MacroResult(MacroStatus.SUCCESS_MEDIUM_CONFIDENCE, outcome.reason, VerificationCapability.MEDIUM)
                } else {
                    MacroResult(MacroStatus.FAILED, outcome.reason)
                }
            }
            is MacroAtom.ScrollUntilVisible -> {
                val text = params[atom.targetTextParam].orEmpty()
                if (text.isBlank()) {
                    return MacroResult(MacroStatus.FAILED_PRECONDITION, "ScrollUntilVisible missing ${atom.targetTextParam}")
                }
                if (textVisible(text)) {
                    return MacroResult(MacroStatus.SUCCESS, "Target already visible", VerificationCapability.HIGH)
                }
                val listSpec = atom.listElementId?.let { id -> appUi.findElement(id) }
                repeat(atom.maxSwipes) {
                    val outcome = if (listSpec != null) {
                        actions.scrollForwardByMatcher(listSpec.matchers)
                    } else {
                        actions.scrollFirstScrollableForward()
                    }
                    if (!outcome.success) {
                        return MacroResult(MacroStatus.FAILED, outcome.reason)
                    }
                    val found = waitUntil((atom.timeoutMs / atom.maxSwipes.coerceAtLeast(1)).coerceAtLeast(250L)) {
                        textVisible(text)
                    }
                    if (found) {
                        return MacroResult(MacroStatus.SUCCESS, "Scrolled until target was visible", VerificationCapability.HIGH)
                    }
                }
                MacroResult(MacroStatus.FAILED_TIMEOUT, "Target not visible after ${atom.maxSwipes} swipes")
            }
            is MacroAtom.WaitFor -> waitForRule(atom.rule, params, atom.timeoutMs)
            is MacroAtom.Verify -> {
                val state = inspector.currentState()
                val profile = runtimeProfile(state)
                verifier.verify(atom.rule, params, state, profile)
            }
        }
    }

    private fun tryCoordinateFallback(element: UiElementSpec, timeoutMs: Long): MacroResult {
        val pack = element.coordinatePack
            ?: return MacroResult(MacroStatus.FAILED_NEEDS_CALIBRATION, "No node match and no coordinate pack")
        val state = inspector.currentState()
            ?: return MacroResult(MacroStatus.FAILED_SERVICE_UNAVAILABLE, "No UiState for coordinate fallback")
        val outcome = actions.tapCoordinate(pack, state.screenWidth, state.screenHeight)
        return if (outcome.success) {
            sleepQuietly(timeoutMs.coerceAtMost(250L))
            MacroResult(MacroStatus.SUCCESS_LOW_CONFIDENCE, outcome.reason, VerificationCapability.LOW)
        } else {
            MacroResult(MacroStatus.FAILED, outcome.reason)
        }
    }

    private fun runtimeProfile(state: com.macropilot.app.model.UiStateSample?): CapabilityProfile {
        val raw = profiler.profile(
            state,
            serviceConnected = MacroPilotAccessibilityService.instance != null
        )
        return if (raw.inputCapability == InputCapability.NONE && actions.isMacroPilotImeSelected()) {
            raw.copy(
                inputCapability = InputCapability.CUSTOM_IME,
                reason = "${raw.reason}, customIme=selected"
            )
        } else {
            raw
        }
    }

    private fun focusElementForIme(element: UiElementSpec): com.macropilot.app.automation.ActionOutcome {
        val nodeClick = actions.clickByMatcher(element.matchers)
        if (nodeClick.success) return nodeClick
        val state = inspector.currentState()
            ?: return com.macropilot.app.automation.ActionOutcome(false, "No UiState for IME coordinate focus")
        val pack = element.coordinatePack
            ?: return com.macropilot.app.automation.ActionOutcome(false, "No coordinate pack for IME focus")
        return actions.tapCoordinate(pack, state.screenWidth, state.screenHeight)
    }

    private fun ensureTextInputFocused(
        target: UiElementSpec,
        toggles: List<UiElementSpec>,
        timeoutMs: Long
    ): com.macropilot.app.automation.ActionOutcome {
        if (!actions.isMacroPilotImeSelected()) {
            return com.macropilot.app.automation.ActionOutcome(false, "MacroPilot IME is not selected")
        }
        if (actions.isMacroPilotImeReady()) {
            return com.macropilot.app.automation.ActionOutcome(true, "MacroPilot IME already has an input connection")
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        fun remaining(): Long = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
        fun waitReady(sliceMs: Long): Boolean {
            return waitUntil(sliceMs.coerceAtMost(remaining()).coerceAtLeast(1L)) {
                actions.isMacroPilotImeReady()
            }
        }

        var lastReason = focusElementForIme(target).reason
        if (waitReady(700L)) {
            return com.macropilot.app.automation.ActionOutcome(true, "Focused ${target.id}; MacroPilot IME connected")
        }

        while (remaining() > 0L) {
            for (toggle in toggles) {
                if (remaining() <= 0L) break
                lastReason = focusElementForIme(toggle).reason
                sleepQuietly(220L)
                if (waitReady(450L)) {
                    return com.macropilot.app.automation.ActionOutcome(
                        true,
                        "Focused through toggle ${toggle.id}; MacroPilot IME connected"
                    )
                }
                lastReason = focusElementForIme(target).reason
                if (waitReady(700L)) {
                    return com.macropilot.app.automation.ActionOutcome(
                        true,
                        "Focused ${target.id} after toggle ${toggle.id}; MacroPilot IME connected"
                    )
                }
            }
            if (toggles.isEmpty()) {
                lastReason = focusElementForIme(target).reason
                if (waitReady(700L)) {
                    return com.macropilot.app.automation.ActionOutcome(
                        true,
                        "Focused ${target.id} after retry; MacroPilot IME connected"
                    )
                }
            } else {
                break
            }
        }
        return com.macropilot.app.automation.ActionOutcome(
            false,
            "MacroPilot IME did not receive an input connection after focus attempts; last=$lastReason"
        )
    }

    private fun waitForRule(rule: VerifyRule, params: Map<String, String>, timeoutMs: Long): MacroResult {
        val ok = waitUntil(timeoutMs) {
            val state = inspector.currentState()
            val profile = runtimeProfile(state)
            !verifier.verify(rule, params, state, profile).status.name.startsWith("FAILED")
        }
        return if (ok) {
            MacroResult(MacroStatus.SUCCESS_MEDIUM_CONFIDENCE, "WaitFor passed", VerificationCapability.MEDIUM)
        } else {
            MacroResult(MacroStatus.FAILED_TIMEOUT, "WaitFor timed out for ${rule.type}")
        }
    }

    private fun textVisible(text: String): Boolean {
        return inspector.currentState()?.nodes?.any { node ->
            node.text?.contains(text) == true || node.contentDesc?.contains(text) == true
        } == true
    }

    private fun lowerStatus(current: MacroStatus, next: MacroStatus): MacroStatus {
        return if (statusRank(next) < statusRank(current)) next else current
    }

    private fun statusRank(status: MacroStatus): Int {
        return when (status) {
            MacroStatus.SUCCESS -> 3
            MacroStatus.SUCCESS_MEDIUM_CONFIDENCE -> 2
            MacroStatus.SUCCESS_LOW_CONFIDENCE -> 1
            else -> 0
        }
    }

    private fun lowerVerification(current: VerificationCapability, next: VerificationCapability): VerificationCapability {
        return if (verificationRank(next) < verificationRank(current)) next else current
    }

    private fun verificationRank(capability: VerificationCapability): Int {
        return when (capability) {
            VerificationCapability.HIGH -> 3
            VerificationCapability.MEDIUM -> 2
            VerificationCapability.LOW -> 1
            VerificationCapability.NONE -> 0
        }
    }

    private fun isTargetPackage(packageName: String?, appUi: AppUiModule): Boolean {
        return packageName == appUi.packageName || packageName in appUi.packageAliases
    }

    private fun isTargetState(state: com.macropilot.app.model.UiStateSample?, appUi: AppUiModule): Boolean {
        if (state == null) return false
        if (!isTargetPackage(state.appPackage, appUi)) return false
        if (hasSystemUiRoot(state)) {
            dismissSystemOverlayIfPresent()
            return false
        }
        return true
    }

    private fun dismissSystemOverlayIfPresent() {
        val state = inspector.currentState() ?: return
        if (state.appPackage == SYSTEM_UI_PACKAGE || hasSystemUiRoot(state)) {
            actions.dismissNotificationShade()
            sleepQuietly(180L)
            if (inspector.currentState()?.let { it.appPackage == SYSTEM_UI_PACKAGE || hasSystemUiRoot(it) } == true) {
                actions.back()
                sleepQuietly(180L)
            }
            if (inspector.currentState()?.let { it.appPackage == SYSTEM_UI_PACKAGE || hasSystemUiRoot(it) } == true) {
                actions.home()
                sleepQuietly(300L)
            }
        }
    }

    private fun hasSystemUiRoot(state: com.macropilot.app.model.UiStateSample): Boolean {
        val firstResource = state.nodes.firstOrNull()?.resourceId.orEmpty()
        if (firstResource.startsWith(SYSTEM_UI_RESOURCE_PREFIX)) return true
        val systemUiNodes = state.nodes.count { it.resourceId?.startsWith(SYSTEM_UI_RESOURCE_PREFIX) == true }
        return state.nodes.isNotEmpty() && systemUiNodes * 2 >= state.nodes.size
    }

    private fun staticSafetyText(template: MacroTemplate): String {
        val atoms = template.atoms.joinToString(" ") { atom ->
            when (atom) {
                is MacroAtom.ClickElement -> atom.elementId
                is MacroAtom.FocusTextInput -> "${atom.targetElementId} ${atom.toggleElementIds.joinToString(" ")}"
                is MacroAtom.TypeText -> "${atom.textParam} ${atom.targetElementId.orEmpty()}"
                is MacroAtom.SearchInApp -> "${atom.queryParam} ${atom.searchEntryElementId}"
                is MacroAtom.SelectItem -> "${atom.itemTextParam} ${atom.listElementId.orEmpty()}"
                is MacroAtom.ScrollUntilVisible -> atom.targetTextParam
                is MacroAtom.EnsureScreen -> atom.screenId
                is MacroAtom.OpenApp -> atom.packageName
                is MacroAtom.WaitFor -> atom.rule.toString()
                is MacroAtom.Verify -> atom.rule.toString()
            }
        }
        return "${template.name} ${template.intentId} $atoms"
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started <= timeoutMs) {
            if (condition()) return true
            sleepQuietly(80L)
        }
        return false
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun AppUiModule.findElement(id: String): UiElementSpec? {
        return elements.firstOrNull { it.id == id }
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val SYSTEM_UI_RESOURCE_PREFIX = "com.android.systemui:"
    }
}
