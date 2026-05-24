package com.roubao.autopilot.macro.core

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.macro.ai.AiAssist
import com.roubao.autopilot.macro.ai.AiAssistPolicy
import com.roubao.autopilot.macro.model.AppUiModule
import com.roubao.autopilot.macro.model.MacroAtom
import com.roubao.autopilot.macro.model.MacroPlan
import com.roubao.autopilot.macro.model.MacroResult
import com.roubao.autopilot.macro.model.RecoverPolicy
import com.roubao.autopilot.macro.model.ResolvedElement
import com.roubao.autopilot.macro.model.VerifyRule
import com.roubao.autopilot.skills.SkillTarget
import com.roubao.autopilot.skills.UiNode
import com.roubao.autopilot.skills.UiObserver
import com.roubao.autopilot.skills.UiState
import kotlinx.coroutines.delay

class MacroExecutor(
    private val controller: DeviceController,
    private val uiObserver: UiObserver,
    private val elementResolver: UiElementResolver,
    private val verifier: MacroVerifier,
    private val screenClassifier: UiScreenClassifier,
    private val logger: MacroEventLogger
) {
    private var directWechatChatMode: Boolean = false

    suspend fun execute(
        plan: MacroPlan,
        appUi: AppUiModule,
        aiPolicy: AiAssistPolicy,
        aiAssist: AiAssist
    ): MacroResult {
        directWechatChatMode = false
        for ((index, atom) in plan.atoms.withIndex()) {
            if (shouldSkipForDirectWechatChat(plan, atom)) {
                logger.emit("[COMPOSE] direct wechat chat mode skips $atom")
                continue
            }

            logger.emit("[ATOM] ${index + 1}/${plan.atoms.size} $atom")
            val result = executeAtom(atom, plan.params, appUi, aiPolicy, aiAssist)
            if (!result.success) {
                return MacroResult.failed("atom failed: ${result.message}")
            }
        }
        return MacroResult.success("all macro atoms completed")
    }

    private suspend fun shouldSkipForDirectWechatChat(plan: MacroPlan, atom: MacroAtom): Boolean {
        if (plan.templateId != "wechat_send_message") return false
        val contact = plan.params["contact"] ?: return false
        if (!contact.contains("\u6587\u4ef6\u4f20\u8f93\u52a9\u624b")) return false

        val navigationAtom = atom is MacroAtom.EnsureScreen ||
            atom is MacroAtom.SearchInApp ||
            atom is MacroAtom.SelectItem ||
            atom is MacroAtom.WaitFor
        if (!navigationAtom) return false

        if (directWechatChatMode) return true

        val state = uiObserver.capture()
        if (state.packageName != plan.appPackage) return false

        if (state.activityName?.contains("SingleChatInfoUI") == true) {
            logger.emit("[RECOVER] filehelper info page -> back to chat")
            controller.back()
            delay(700)
            directWechatChatMode = true
            return true
        }

        if (state.activityName?.contains("LauncherUI") == true && isSoftKeyboardVisible()) {
            logger.emit("[RECOVER] filehelper chat detected by keyboard")
            directWechatChatMode = true
            return true
        }

        return false
    }

    private suspend fun executeAtom(
        atom: MacroAtom,
        params: Map<String, String>,
        appUi: AppUiModule,
        aiPolicy: AiAssistPolicy,
        aiAssist: AiAssist
    ): MacroResult {
        return when (atom) {
            is MacroAtom.OpenApp -> {
                controller.openApp(atom.appPackage)
                waitForPackage(atom.appPackage, timeoutMs = 5000L)
            }
            is MacroAtom.EnsureScreen -> ensureScreen(atom, appUi)
            is MacroAtom.SearchInApp -> {
                val query = params[atom.queryParam]
                    ?: return MacroResult.failed("missing search param ${atom.queryParam}")
                searchInApp(atom.searchEntryElementId, query, appUi, aiPolicy, aiAssist)
            }
            is MacroAtom.SelectItem -> {
                val text = params[atom.itemTextParam]
                    ?: return MacroResult.failed("missing select param ${atom.itemTextParam}")
                selectItem(text, appUi, aiPolicy, aiAssist)
            }
            is MacroAtom.TypeText -> {
                val text = params[atom.textParam]
                    ?: return MacroResult.failed("missing text param ${atom.textParam}")
                typeText(atom.targetElementId, text, appUi, aiPolicy, aiAssist)
            }
            is MacroAtom.ClickElement -> clickElement(atom.elementId, appUi, aiPolicy, aiAssist)
            is MacroAtom.WaitFor -> waitFor(atom.rule, params, atom.timeoutMs)
            is MacroAtom.Verify -> {
                if (atom.rule is VerifyRule.MessageSent) {
                    verifyWechatMessageSentFallback(atom.rule, params)?.let { verify ->
                        return if (verify.success) MacroResult.success("verify success")
                        else MacroResult.failed("verify failed: ${verify.reason}")
                    }
                }

                val verify = verifier.verify(atom.rule, params, uiObserver.capture())
                if (verify.success) MacroResult.success("verify success")
                else MacroResult.failed("verify failed: ${verify.reason}")
            }
            is MacroAtom.ScrollUntilVisible -> scrollUntilVisible(atom, params)
        }
    }

    private suspend fun ensureScreen(atom: MacroAtom.EnsureScreen, appUi: AppUiModule): MacroResult {
        repeat(5) { attempt ->
            val state = uiObserver.capture()
            if (isScreenReady(atom.screenId, appUi, state)) {
                logger.emit("[ENSURE] screen=${atom.screenId}")
                return MacroResult.success("screen ready")
            }

            if (atom.recoverPolicy == RecoverPolicy.BACK_TO_KNOWN_SCREEN && attempt < 4) {
                recoverScreen(atom.screenId, appUi, state, attempt)
                val recovered = uiObserver.capture()
                if (isCoordinateOnlyWechatHomeReady(atom.screenId, appUi, recovered)) {
                    logger.emit("[ENSURE] screen=${atom.screenId} coordinate-only")
                    return MacroResult.success("screen ready by coordinate-only mode")
                }
            }
        }
        return MacroResult.failed("cannot reach screen ${atom.screenId}")
    }

    private fun isScreenReady(
        screenId: String,
        appUi: AppUiModule,
        state: UiState
    ): Boolean {
        val screen = screenClassifier.classify(state, appUi)
        if (screen?.id == screenId) return true

        if (screenId == "wechat_home" && state.packageName == appUi.appPackage) {
            val searchEntry = appUi.findElement("search_entry")
            val searchEntryVisible = searchEntry != null && state.nodes.any { node ->
                node.visible && searchEntry.matchers.any { matcher -> matcher.matches(node) }
            }
            if (searchEntryVisible) {
                logger.emit("[ENSURE] wechat_home accepted by search_entry")
                return true
            }

            val homeTextVisible = listOf("\u5fae\u4fe1", "\u901a\u8baf\u5f55", "\u53d1\u73b0", "\u6211")
                .any { state.hasText(it) }
            if (homeTextVisible) {
                logger.emit("[ENSURE] wechat_home accepted by tab text")
                return true
            }
        }

        return false
    }

    private fun isCoordinateOnlyWechatHomeReady(
        screenId: String,
        appUi: AppUiModule,
        state: UiState
    ): Boolean {
        if (screenId != "wechat_home") return false
        if (state.packageName != appUi.appPackage) return false
        val hasRealNode = state.nodes.any { node ->
            node.visible && !node.bounds.isEmpty && (node.text != null || node.contentDesc != null || node.resourceId != null)
        }
        return !hasRealNode
    }

    private suspend fun recoverScreen(
        screenId: String,
        appUi: AppUiModule,
        state: UiState,
        attempt: Int
    ) {
        if (screenId == "wechat_home" && state.packageName == appUi.appPackage) {
            val wechatTab = state.nodes.firstOrNull { node ->
                node.visible && node.clickable && (node.text == "\u5fae\u4fe1" || node.contentDesc == "\u5fae\u4fe1")
            }
            if (wechatTab != null) {
                logger.emit("[ENSURE] recover wechat_home by WeChat tab")
                controller.tap(wechatTab.bounds.centerX(), wechatTab.bounds.centerY())
                delay(500)
                return
            }

            val (w, h) = controller.getScreenSize()
            logger.emit("[ENSURE] recover wechat_home by bottom-tab coordinate")
            val y = (h * 0.925f).toInt()
            val xCandidates = listOf(0.12f, 0.18f, 0.25f).map { (w * it).toInt() }
            controller.tap(xCandidates[attempt.coerceIn(0, xCandidates.lastIndex)], y)
            delay(700)
            return
        }

        if (screenId == "wechat_home") {
            logger.emit("[ENSURE] recover wechat_home by reopen app")
            controller.openApp(appUi.appPackage)
            delay(1200)
            return
        }

        logger.emit("[ENSURE] recover $screenId by back")
        controller.back()
        delay(500)
    }

    private suspend fun waitForPackage(packageName: String, timeoutMs: Long): MacroResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastPackage: String? = null
        while (System.currentTimeMillis() <= deadline) {
            val state = uiObserver.capture()
            lastPackage = state.packageName
            if (state.packageName == packageName) {
                logger.emit("[OPEN_APP] package ready=$packageName")
                return MacroResult.success("opened app")
            }
            delay(300)
        }
        return MacroResult.failed("open app timeout: expected=$packageName actual=${lastPackage ?: "unknown"}")
    }

    private suspend fun searchInApp(
        searchEntryElementId: String,
        query: String,
        appUi: AppUiModule,
        aiPolicy: AiAssistPolicy,
        aiAssist: AiAssist
    ): MacroResult {
        val entry = elementResolver.resolve(searchEntryElementId, appUi, aiPolicy, aiAssist)
            ?: return MacroResult.failed("search entry not found: $searchEntryElementId")
        tap(entry)
        delay(500)

        val input = elementResolver.resolve("search_input", appUi, aiPolicy, aiAssist)
            ?: return MacroResult.failed("search input not found after tapping $searchEntryElementId")
        tap(input)
        delay(150)

        controller.type(query)
        delay(800)
        return MacroResult.success("searched $query")
    }

    private suspend fun selectItem(
        text: String,
        appUi: AppUiModule,
        aiPolicy: AiAssistPolicy,
        aiAssist: AiAssist
    ): MacroResult {
        val state = uiObserver.capture()
        elementResolver.findText(text, state)?.let {
            tap(it)
            delay(900)
            return MacroResult.success("selected $text")
        }

        if (aiPolicy.canUsePerception()) {
            aiPolicy.recordPerception()
            aiAssist.findElement(text, state, appUi)?.let {
                logger.emit("[AI_ASSIST] FIND_ELEMENT accepted: $text")
                tap(it)
                delay(900)
                return MacroResult.success("selected $text by AI assist")
            }
        }

        return MacroResult.failed("select item not found: $text")
    }

    private suspend fun typeText(
        targetElementId: String?,
        text: String,
        appUi: AppUiModule,
        aiPolicy: AiAssistPolicy,
        aiAssist: AiAssist
    ): MacroResult {
        if (targetElementId == "chat_input") {
            if (isSoftKeyboardVisible()) {
                logger.emit("[TYPE] chat_input focused by visible keyboard")
            } else {
                val target = elementResolver.resolve(targetElementId, appUi, aiPolicy, aiAssist)
                    ?: return MacroResult.failed("text target not found: $targetElementId")
                tap(target)
                delay(200)
            }
            clearFocusedText()
            controller.type(text)
            delay(400)
            return MacroResult.success("typed text")
        }

        if (targetElementId != null) {
            val target = elementResolver.resolve(targetElementId, appUi, aiPolicy, aiAssist)
                ?: return MacroResult.failed("text target not found: $targetElementId")
            val setByAccessibility = (target as? ResolvedElement.Node)
                ?.node
                ?.let { setTextByAccessibility(it, text) } == true
            if (!setByAccessibility) {
                tap(target)
                delay(150)
                controller.type(text)
            }
        } else {
            controller.type(text)
        }
        delay(400)
        return MacroResult.success("typed text")
    }

    private suspend fun clickElement(
        elementId: String,
        appUi: AppUiModule,
        aiPolicy: AiAssistPolicy,
        aiAssist: AiAssist
    ): MacroResult {
        if (elementId == "send_button" && isSoftKeyboardVisible()) {
            val (w, h) = controller.getScreenSize()
            logger.emit("[CLICK] send_button source=keyboard-visible-coordinate")
            controller.tap((w * 0.915f).toInt(), (h * 0.585f).toInt())
            delay(700)
            return MacroResult.success("clicked $elementId")
        }

        val target = elementResolver.resolve(elementId, appUi, aiPolicy, aiAssist)
            ?: return MacroResult.failed("element not found: $elementId")
        tap(target)
        delay(700)
        return MacroResult.success("clicked $elementId")
    }

    private suspend fun waitFor(
        rule: VerifyRule,
        params: Map<String, String>,
        timeoutMs: Long
    ): MacroResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastReason = "timeout"
        while (System.currentTimeMillis() <= deadline) {
            val verify = verifier.verify(rule, params, uiObserver.capture())
            if (verify.success) return MacroResult.success("wait condition met")
            lastReason = verify.reason
            delay(250)
        }
        return MacroResult.failed("wait failed: $lastReason")
    }

    private suspend fun scrollUntilVisible(
        atom: MacroAtom.ScrollUntilVisible,
        params: Map<String, String>
    ): MacroResult {
        val text = params[atom.targetTextParam]
            ?: return MacroResult.failed("missing scroll target ${atom.targetTextParam}")
        repeat(atom.maxSwipes) {
            val state = uiObserver.capture()
            if (state.hasText(text)) return MacroResult.success("scroll target visible")
            val (w, h) = controller.getScreenSize()
            controller.swipe(w / 2, (h * 0.75f).toInt(), w / 2, (h * 0.35f).toInt())
            delay(450)
        }
        return MacroResult.failed("scroll target not visible: $text")
    }

    private fun tap(element: ResolvedElement) {
        val (x, y) = element.center()
        controller.tap(x, y)
    }

    private fun setTextByAccessibility(node: UiNode, text: String): Boolean {
        val target = SkillTarget(
            text = node.text,
            className = node.className,
            contentDesc = node.contentDesc,
            fallbackX = node.bounds.centerX(),
            fallbackY = node.bounds.centerY()
        )
        return controller.setTextByAccessibility(target, text)
    }

    private fun clearFocusedText() {
        runCatching {
            controller.shell("input keyevent 123")
            val deletes = List(80) { "67" }.joinToString(" ")
            controller.shell("input keyevent $deletes")
        }
    }

    private fun isSoftKeyboardVisible(): Boolean {
        return runCatching {
            val dump = controller.shell("dumpsys input_method")
            dump.contains("mInputShown=true") ||
                dump.contains("mWindowVisible=true") ||
                dump.contains("mDecorViewVisible=true")
        }.getOrDefault(false)
    }

    private fun verifyWechatMessageSentFallback(
        rule: VerifyRule.MessageSent,
        params: Map<String, String>
    ): com.roubao.autopilot.macro.model.VerifyResult? {
        val state = uiObserver.capture()
        if (state.packageName != "com.tencent.mm") return null

        val hasRealNode = state.nodes.any { node ->
            node.visible && !node.bounds.isEmpty && (node.text != null || node.contentDesc != null || node.resourceId != null)
        }
        if (hasRealNode) return null

        val message = params[rule.messageParam]
            ?: return com.roubao.autopilot.macro.model.VerifyResult.failed("missing message param ${rule.messageParam}")
        val dump = controller.shell("dumpsys input_method")
        if (!dump.contains("packageName=com.tencent.mm")) {
            logger.emit("[VERIFY] wechat input editor gone after send")
            return com.roubao.autopilot.macro.model.VerifyResult.success()
        }

        val cursor = Regex("mCursorSelStart=(\\d+)\\s+mCursorSelEnd=(\\d+)").find(dump)
        val start = cursor?.groupValues?.getOrNull(1)?.toIntOrNull()
        val end = cursor?.groupValues?.getOrNull(2)?.toIntOrNull()
        return when {
            start == 0 && end == 0 -> {
                logger.emit("[VERIFY] wechat input is empty after sending $message")
                com.roubao.autopilot.macro.model.VerifyResult.success()
            }
            start != null && end != null ->
                com.roubao.autopilot.macro.model.VerifyResult.failed("wechat input still has cursor $start..$end; message may be unsent")
            else ->
                com.roubao.autopilot.macro.model.VerifyResult.failed("wechat verification unavailable without accessibility nodes")
        }
    }
}
