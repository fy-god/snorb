package com.macropilot.app.factory

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.automation.ScreenMetrics
import com.macropilot.app.model.ElementMatcher
import com.macropilot.app.model.InputCapability
import com.macropilot.app.model.NodeSample
import com.macropilot.app.model.NodeReadability
import com.macropilot.app.model.NormalizedPoint
import com.macropilot.app.model.UiStateSample
import com.macropilot.app.runtime.CapabilityProfiler
import com.macropilot.app.runtime.NodeInspector
import com.macropilot.app.store.FactorySkillJsonStore
import com.macropilot.app.store.UiStateSnapshotStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

class SkillJsonExecutor(
    private val context: Context,
    private val store: FactorySkillJsonStore = FactorySkillJsonStore(context),
    private val inspector: NodeInspector = NodeInspector(context),
    private val profiler: CapabilityProfiler = CapabilityProfiler(),
    private val actions: DeviceActions = DeviceActions(context),
    private val selectorParser: SkillJsonSelectorParser = SkillJsonSelectorParser(),
    private val runtimeApprovedOnly: Boolean = false
) {
    private val reactiveRules = ReactiveUiRuleEngine(inspector, actions)
    private val snapshotStore = UiStateSnapshotStore(context)

    fun execute(
        skill: JSONObject,
        params: JSONObject = JSONObject(),
        allowSideEffect: Boolean = false
    ): SkillJsonRunResult {
        val startedAt = System.currentTimeMillis()
        val result = executeInternal(skill, params, allowSideEffect, mutableSetOf())
        return result.copy(durationMs = System.currentTimeMillis() - startedAt)
    }

    private fun executeInternal(
        skill: JSONObject,
        params: JSONObject,
        allowSideEffect: Boolean,
        visited: MutableSet<String>
    ): SkillJsonRunResult {
        val id = skill.optString("id", "unknown")
        if (!visited.add(id)) {
            return failed("FAILED_CYCLIC_DEPENDENCY", "Cyclic Skill JSON dependency: $id")
        }
        val earlyAction = skill.optJSONObject("action")
        val earlyActionType = earlyAction?.optString("type").orEmpty()
        val isMacroSkill = skill.optString("kind") == "macro_skill"
        val canRunWhileLocked = earlyActionType == "EnsureTextVideoAsset" || isMacroSkill
        if (!actions.isInteractive() && !canRunWhileLocked) {
            return failed("FAILED_PRECONDITION", "Screen is off; wake and unlock the device before Factory test")
        }
        if (runtimeApprovedOnly && skill.optString("status") != "APPROVED") {
            return failed("FAILED_RUNTIME_SKILL_NOT_APPROVED", "Runtime can execute APPROVED Skill JSON only: $id")
        }
        if (actions.isKeyguardLocked() && !canRunWhileLocked) {
            return failed("FAILED_PRECONDITION", "Device is locked; unlock before Factory test")
        }
        if (!allowSideEffect && requiresSideEffectApproval(skill)) {
            return failed(
                "FAILED_SIDE_EFFECT_CONFIRMATION_REQUIRED",
                "Skill may send or submit content; pass allowSideEffect=true in Factory mode to test it"
            )
        }

        val precondition = checkPreconditions(skill)
        if (precondition != null) return precondition
        if (skill.optString("kind") == "macro_skill") {
            return executeMacro(skill, params, allowSideEffect, visited)
        }

        val dependencyResult = executeDependencies(skill, params, allowSideEffect, visited)
        if (dependencyResult != null) return dependencyResult

        val capability = skill.optJSONObject("capabilityRequired")
        val action = skill.optJSONObject("action")
            ?: return failed("FAILED_PRECONDITION", "Skill JSON missing action")
        if (capability?.optBoolean("inputRequired", false) == true && !inputAvailable(capability)) {
            return failed("FAILED_INPUT_CHANNEL_MISSING", "Input skill has no available input channel")
        }
        com.macropilot.app.automation.MacroPilotAccessibilityService.instance?.showStatusOverlay(
            title = id.take(28),
            status = "RUNNING",
            message = "执行动作：${action.optString("type")}；来源=Skill JSON"
        )

        val actionType = action.optString("type")
        saveCurrentSnapshot(
            source = "before_action",
            note = "$id:$actionType",
            relatedSkillId = id,
            relatedActionType = actionType
        )
        val skipReactiveOverlays = action.optBoolean("skipReactiveOverlays", false)
        val beforeReactive = if (shouldRunReactiveForAction(actionType) && !skipReactiveOverlays) {
            runReactiveOverlays(skill.optString("appPackage"), "before_action:$id:$actionType", maxPasses = 2)
        } else {
            ReactiveRuleResult(0, JSONArray())
        }
        if (beforeReactive.dismissedCount > 0) {
            saveCurrentSnapshot(
                source = "after_reactive_overlay",
                note = "$id:$actionType:dismissed=${beforeReactive.dismissedCount}",
                relatedSkillId = id,
                relatedActionType = actionType
            )
        }
        val actionRun = when (actionType) {
            "ResolveElement" -> resolveElement(skill)
            "ClickElement" -> clickElement(skill)
            "ClickVisibleText" -> clickVisibleText(skill, params)
            "ClickSelector" -> clickSelector(skill)
            "ClickCoordinate" -> clickCoordinate(skill)
            "LongPressCoordinate" -> longPressCoordinate(skill)
            "TypeText", "InputText", "PasteText" -> typeText(skill, params)
            "TypeIntoFirstInput" -> typeIntoFirstInput(skill, params)
            "TypeIntoFocusedInput" -> typeIntoFocusedInput(skill, params)
            "FillHupuPostEditor" -> fillHupuPostEditor(skill, params)
            "OpenHupuPostEditor" -> openHupuPostEditor(skill)
            "TypeAtCoordinate" -> typeAtCoordinate(skill, params)
            "VerifyGoal" -> verifyGoal(skill, params, SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", "VerifyGoal started"))
            "Back" -> {
                val outcome = actions.back()
                if (outcome.success) verifyAfterAction(skill, params, SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", outcome.reason)) else {
                    failed("FAILED", outcome.reason)
                }
            }
            "OpenApp" -> {
                val packageName = action.optString("packageName", skill.optString("appPackage"))
                val alreadyForeground = packageName == SkillJsonRuleGenerator.BILIBILI_PACKAGE &&
                    inspector.currentState()?.appPackage == packageName
                val outcome = if (alreadyForeground) {
                    com.macropilot.app.automation.ActionOutcome(
                        true,
                        "Bilibili already foreground; skipped launcher root to preserve current surface"
                    )
                } else if (action.optBoolean("launcherRoot", false)) {
                    actions.openAppLauncherRoot(packageName)
                } else {
                    actions.openApp(packageName)
                }
                val launchResult = if (outcome.success) {
                    SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", outcome.reason)
                } else {
                    val explicit = actions.openAppExplicitLauncher(packageName)
                    if (explicit.success) {
                        SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", "${outcome.reason}; ${explicit.reason}")
                    } else {
                        SkillJsonRunResult("FAILED_PRECONDITION", "${outcome.reason}; ${explicit.reason}")
                    }
                }
                if (launchResult.status.startsWith("SUCCESS")) verifyAfterAction(skill, params, launchResult) else {
                    failed("FAILED_PRECONDITION", outcome.reason)
                }
            }
            "CheckCapability" -> checkCapability(skill)
            "EnsureTextVideoAsset" -> ensureTextVideoAsset(skill, params)
            "HandleSystemPermissionDialog" -> handleSystemPermissionDialog(skill)
            "RecoverDouyinHomeSurface" -> recoverDouyinHomeSurface(skill)
            "RecoverBilibiliHomeSurface" -> recoverBilibiliHomeSurface(skill)
            "OpenBilibiliSearch" -> openBilibiliSearch(skill, params)
            "HandleReactiveOverlays", "DismissBlockingOverlay" -> handleReactiveOverlayAction(skill)
            "EnsureHupuSectionSelected" -> ensureHupuSectionSelected(skill)
            "VerifyHupuPostPublished" -> verifyHupuPostPublished(skill, params)
            "FindEntryByBottomTabSweep" -> findEntryByBottomTabSweep(skill)
            "CaptureWechatBottomTabs" -> captureWechatBottomTabs(skill)
            "ScrollForward" -> {
                val outcome = actions.scrollFirstScrollableForward()
                if (outcome.success) {
                    verifyAfterAction(skill, params, SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", outcome.reason))
                } else {
                    failed("FAILED_NO_SCROLLABLE_CONTAINER", outcome.reason)
                }
            }
            "EnsureXiaohongshuDraftEditor" -> ensureXiaohongshuDraftEditor(skill)
            "Wait" -> {
                val timeoutMs = action.optLong("timeoutMs", 800L).coerceIn(0L, 10_000L)
                val reactive = if (skipReactiveOverlays) {
                    sleep(timeoutMs)
                    ReactiveRuleResult(0, JSONArray())
                } else {
                    waitWithReactive(skill, timeoutMs)
                }
                verifyAfterAction(
                    skill,
                    params,
                    SkillJsonRunResult(
                        "SUCCESS_MEDIUM_CONFIDENCE",
                        "Waited ${timeoutMs}ms; reactiveDismissed=${reactive.dismissedCount}"
                    )
                )
            }
            else -> failed("FAILED_UNSUPPORTED_ACTION", "Unsupported Skill JSON action: $actionType")
        }
        val finalRun = attachReactiveSummary(actionRun, beforeReactive.dismissedCount)
        saveCurrentSnapshot(
            source = if (finalRun.status.startsWith("FAILED")) "action_failed" else "after_action",
            note = "$id:$actionType:${finalRun.status}:${finalRun.message.take(180)}",
            relatedSkillId = id,
            relatedActionType = actionType
        )
        return finalRun
    }

    private fun ensureTextVideoAsset(skill: JSONObject, params: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: return failed("FAILED_PRECONDITION", "Missing action")
        val text = action.optString("text").ifBlank {
            paramValue(action.optString("textParam", "text"), params)
        }.ifBlank { "\u6211\u662fAI" }
        val result = TextVideoAssetWriter(context).createTextVideo(
            text = text,
            durationMs = action.optLong("durationMs", 3_000L),
            displayName = action.optString("displayName")
                .ifBlank { "macropilot_douyin_${System.currentTimeMillis()}.mp4" }
        )
        return if (result.success) {
            SkillJsonRunResult(
                status = "SUCCESS",
                message = "${result.message}; uri=${result.uri}; displayName=${result.displayName}"
            )
        } else {
            failed("FAILED_MEDIA_ASSET", result.message)
        }
    }

    private fun handleReactiveOverlayAction(skill: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: JSONObject()
        val appPackage = action.optString("appPackage").ifBlank { skill.optString("appPackage") }
        val maxPasses = action.optInt("maxPasses", 3).coerceIn(1, 6)
        val result = runReactiveOverlays(appPackage, "explicit_reactive_action:${skill.optString("id")}", maxPasses)
        return SkillJsonRunResult(
            status = "SUCCESS_MEDIUM_CONFIDENCE",
            message = "Reactive overlay pass completed; dismissed=${result.dismissedCount}",
            usedCoordinate = result.dismissedCount > 0
        )
    }

    private fun handleSystemPermissionDialog(skill: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: JSONObject()
        val aliases = jsonArrayStrings(action.optJSONArray("textAliases")).ifEmpty {
            listOf(
                "\u4ec5\u9650\u8fd9\u4e00\u6b21",
                "\u4f7f\u7528\u65f6\u5141\u8bb8",
                "\u5141\u8bb8\u6240\u6709\u7167\u7247\u548c\u89c6\u9891",
                "\u9009\u62e9\u7167\u7247\u548c\u89c6\u9891",
                "\u53bb\u6388\u6743",
                "\u5141\u8bb8",
                "\u540c\u610f",
                "\u7ee7\u7eed",
                "\u786e\u5b9a"
            )
        }
        val state = inspector.currentState()
        val permissionVisible = state?.let { looksLikePermissionDialog(it) } ?: false
        if (state != null && !permissionVisible) {
            return SkillJsonRunResult(
                "SUCCESS_MEDIUM_CONFIDENCE",
                "No system permission dialog visible; skipped permission handler"
            )
        }

        val failures = mutableListOf<String>()
        aliases.forEach { alias ->
            val outcome = actions.clickByVisibleText(alias)
            if (outcome.success) {
                sleep(action.optLong("settleMs", 800L).coerceIn(200L, 2_000L))
                return SkillJsonRunResult(
                    "SUCCESS_MEDIUM_CONFIDENCE",
                    "System permission dialog handled by text '$alias': ${outcome.reason}"
                )
            }
            failures += "$alias=${outcome.reason}"
        }

        val width = displayScreenWidth()
        val height = displayScreenHeight()
        val fallbackPoints = listOf(
            NormalizedPoint(0.50f, 0.695f),
            NormalizedPoint(
                action.optDouble("x", 0.50).toFloat().coerceIn(0f, 1f),
                action.optDouble("y", 0.775).toFloat().coerceIn(0f, 1f)
            ),
            NormalizedPoint(0.50f, 0.735f)
        )
        var tapped = 0
        fallbackPoints.forEachIndexed { index, point ->
            val outcome = actions.tapNormalized(point, width, height)
            if (outcome.success) {
                tapped++
                sleep(action.optLong("settleMs", 800L).coerceIn(200L, 2_000L))
                if (inspector.currentState() != null) {
                    return SkillJsonRunResult(
                        "SUCCESS_LOW_CONFIDENCE",
                        "System permission dialog handled by coordinate fallback #${index + 1}; textAttempts=${failures.take(4).joinToString("; ")}",
                        usedCoordinate = true
                    )
                }
            }
            failures += "fallback#${index + 1}=${outcome.reason}"
        }
        if (tapped > 0) {
            return SkillJsonRunResult(
                "SUCCESS_LOW_CONFIDENCE",
                "System permission dialog fallback gestures sent=$tapped while UiState stayed unreadable; continuing",
                usedCoordinate = true
            )
        }

        return failed(
            "FAILED_PERMISSION_DIALOG_UNHANDLED",
            "System permission dialog looked visible but no allow action worked: ${failures.take(8).joinToString("; ")}"
        )
    }

    private fun recoverDouyinHomeSurface(skill: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: JSONObject()
        val packageName = action.optString("appPackage").ifBlank { skill.optString("appPackage") }
        val timeoutMs = action.optLong("timeoutMs", 4_000L).coerceIn(1_500L, 10_000L)
        val startedAt = System.currentTimeMillis()
        val events = JSONArray()
        var pass = 0
        while (System.currentTimeMillis() - startedAt < timeoutMs && pass < 7) {
            pass++
            val state = inspector.currentState()
            if (state == null) {
                if (packageName.isNotBlank()) actions.openApp(packageName)
                sleep(900L)
                events.put(JSONObject().put("step", pass).put("surface", "no_ui_state"))
                continue
            }
            if (packageName.isNotBlank() && state.appPackage != packageName) {
                actions.openApp(packageName)
                sleep(900L)
                events.put(JSONObject().put("step", pass).put("surface", "wrong_package").put("current", state.appPackage))
                continue
            }
            val labels = state.nodes.map { nodeLabel(it) }
            val context = labels.joinToString(" ")
            val searchLike = listOf(
                "\u641c\u4f60\u60f3\u770b\u7684",
                "\u641c\u7d22",
                "\u731c\u4f60\u60f3\u641c",
                "\u70ed\u70b9",
                "\u76f4\u64ad",
                "\u5546\u57ce"
            ).any { context.contains(it, ignoreCase = true) }
            val commentLike = listOf(
                "\u6709\u4ec0\u4e48\u60f3\u6cd5",
                "\u5c55\u5f00\u8bf4\u8bf4",
                "\u8bc4\u8bba",
                "\u5199\u8bc4\u8bba"
            ).any { context.contains(it, ignoreCase = true) } && state.nodes.any { it.editable }
            val mainLike = listOf(
                "\u9996\u9875",
                "\u670b\u53cb",
                "\u6d88\u606f",
                "\u6211"
            ).count { marker -> labels.any { it.contains(marker, ignoreCase = true) } } >= 2
            val bottomCreatorEntry = state.nodes.any { node ->
                val label = nodeLabel(node)
                val centerX = node.bounds.centerX().toFloat() / state.effectiveScreenWidth().coerceAtLeast(1)
                val centerY = node.bounds.centerY().toFloat() / state.effectiveScreenHeight().coerceAtLeast(1)
                node.clickable &&
                    centerX in 0.38f..0.62f &&
                    centerY > 0.78f &&
                    (
                        label == "+" ||
                            label.contains("\u53d1\u5e03", ignoreCase = true) ||
                            label.contains("\u62cd\u6444", ignoreCase = true) ||
                            label.contains("\u521b\u4f5c", ignoreCase = true)
                        )
            }
            events.put(JSONObject()
                .put("step", pass)
                .put("searchLike", searchLike)
                .put("commentLike", commentLike)
                .put("mainLike", mainLike)
                .put("bottomCreatorEntry", bottomCreatorEntry)
                .put("labels", JSONArray(labels.filter { it.isNotBlank() }.take(10))))
            if (mainLike || bottomCreatorEntry) {
                return SkillJsonRunResult(
                    "SUCCESS_MEDIUM_CONFIDENCE",
                    "Douyin surface ready for creator entry; evidence=${events.length()}"
                )
            }
            val back = actions.back()
            events.put(JSONObject().put("step", pass).put("action", "back").put("success", back.success).put("message", back.reason))
            sleep(900L)
        }
        return SkillJsonRunResult(
            "SUCCESS_LOW_CONFIDENCE",
            "Douyin recover-to-home pass completed; events=$events",
            usedCoordinate = false
        )
    }

    private fun openBilibiliSearch(skill: JSONObject, params: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: JSONObject()
        val packageName = action.optString("appPackage")
            .ifBlank { action.optString("packageName") }
            .ifBlank { SkillJsonRuleGenerator.BILIBILI_PACKAGE }
        val query = paramValue(action.optString("queryParam", "query"), params)
            .ifBlank { action.optString("query") }
            .trim()
        if (query.isBlank()) return failed("FAILED_PARAMS", "OpenBilibiliSearch missing query")
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://search?keyword=$encoded"))
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            sleep(action.optLong("settleMs", 2_800L).coerceIn(800L, 6_000L))
            val state = inspector.currentState()
            val ready = state != null &&
                state.appPackage == packageName &&
                isTrustedBilibiliReadySurface(state)
            SkillJsonRunResult(
                if (ready) "SUCCESS_MEDIUM_CONFIDENCE" else "SUCCESS_LOW_CONFIDENCE",
                "Bilibili search deep link opened for query='$query'; ready=$ready"
            )
        } catch (error: Throwable) {
            failed("FAILED_OPEN_DEEP_LINK", "OpenBilibiliSearch failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun recoverBilibiliHomeSurface(skill: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: JSONObject()
        val packageName = action.optString("appPackage").ifBlank {
            action.optString("packageName").ifBlank { skill.optString("appPackage") }
        }.ifBlank { SkillJsonRuleGenerator.BILIBILI_PACKAGE }
        val timeoutMs = action.optLong("timeoutMs", 6_000L).coerceIn(2_000L, 30_000L)
        val startedAt = System.currentTimeMillis()
        val events = JSONArray()
        var pass = 0
        MacroPilotAccessibilityService.instance?.showStatusOverlay(
            "B 站页面恢复",
            "RUNNING",
            "退出消息/动态/分享页，回到可搜索的主页。"
        )

        while (System.currentTimeMillis() - startedAt < timeoutMs && pass < 9) {
            pass++
            var state = inspector.currentState()
            if (state == null || state.appPackage != packageName) {
                val open = actions.openAppLauncherRoot(packageName)
                events.put(JSONObject()
                    .put("pass", pass)
                    .put("action", "open_launcher_root")
                    .put("success", open.success)
                    .put("message", open.reason))
                sleep(900L)
                state = inspector.currentState()
            }
            if (state != null && isTrustedBilibiliReadySurface(state)) {
                events.put(JSONObject()
                    .put("pass", pass)
                    .put("phase", "ready")
                    .put("surface", bilibiliSurfaceKind(state)))
                return SkillJsonRunResult(
                    "SUCCESS_MEDIUM_CONFIDENCE",
                    "Bilibili home/search surface ready; events=${events.toString().take(1800)}"
                )
            }

            val reactive = runReactiveOverlays(packageName, "recover_bilibili_home:$pass", maxPasses = 2)
            if (reactive.dismissedCount > 0) {
                events.put(JSONObject()
                    .put("pass", pass)
                    .put("action", "reactive_overlays")
                    .put("dismissed", reactive.dismissedCount))
                sleep(500L)
                state = inspector.currentState()
                if (state != null && isTrustedBilibiliReadySurface(state)) {
                    events.put(JSONObject().put("pass", pass).put("phase", "ready_after_reactive"))
                    return SkillJsonRunResult(
                        "SUCCESS_MEDIUM_CONFIDENCE",
                        "Bilibili ready after overlay dismissal; events=${events.toString().take(1800)}",
                        usedCoordinate = reactive.dismissedCount > 0
                    )
                }
            }

            if (state != null && isBilibiliMessageOrDrawerSurface(state)) {
                val explicitBack = findBilibiliExplicitBackNode(state)
                val sheetClose = findBilibiliSheetCloseNode(state)
                val closeOrBack = if (explicitBack != null) {
                    tapNodeCenter(explicitBack, state)
                } else if (sheetClose != null) {
                    tapNodeCenter(sheetClose, state)
                } else {
                    actions.back()
                }
                events.put(JSONObject()
                    .put("pass", pass)
                    .put("surface", bilibiliSurfaceKind(state))
                    .put("action", when {
                        explicitBack != null -> "blocked_surface_tap_explicit_back"
                        sheetClose != null -> "message_surface_tap_close_sheet"
                        else -> "message_surface_global_back"
                    })
                    .put("success", closeOrBack.success)
                    .put("message", closeOrBack.reason))
                sleep(900L)
                val afterBack = waitForStableBilibiliReady(packageName, 1_400L)
                if (afterBack != null) {
                    events.put(JSONObject()
                        .put("pass", pass)
                        .put("phase", "ready_after_message_escape")
                        .put("surface", bilibiliSurfaceKind(afterBack)))
                    return SkillJsonRunResult(
                        "SUCCESS_MEDIUM_CONFIDENCE",
                        "Bilibili ready after leaving message surface; events=${events.toString().take(1800)}",
                        usedCoordinate = true,
                        details = JSONObject().put("events", events)
                    )
                }
                val stillBlocked = inspector.currentState()
                if (stillBlocked != null && isBilibiliMessageOrDrawerSurface(stillBlocked)) {
                    val explicitBackAgain = findBilibiliExplicitBackNode(stillBlocked)
                    val closeSheetAgain = findBilibiliSheetCloseNode(stillBlocked)
                    val tapTopLeft = if (explicitBackAgain != null) {
                        tapNodeCenter(explicitBackAgain, stillBlocked)
                    } else if (closeSheetAgain != null) {
                        tapNodeCenter(closeSheetAgain, stillBlocked)
                    } else {
                        actions.tapNormalized(
                            NormalizedPoint(0.065f, 0.085f),
                            stillBlocked.effectiveScreenWidth(),
                            stillBlocked.effectiveScreenHeight()
                        )
                    }
                    events.put(JSONObject()
                        .put("pass", pass)
                        .put("surface", bilibiliSurfaceKind(stillBlocked))
                        .put("action", when {
                            explicitBackAgain != null -> "blocked_surface_tap_explicit_back_again"
                            closeSheetAgain != null -> "message_surface_tap_close_sheet_again"
                            else -> "message_surface_top_left_back_coordinate"
                        })
                        .put("success", tapTopLeft.success)
                        .put("message", tapTopLeft.reason))
                    sleep(850L)
                }
                continue
            }

            val topBack = state?.nodes
                ?.filter { node ->
                    val effectiveWidth = minOf(state.effectiveScreenWidth(), displayScreenWidth()).coerceAtLeast(1)
                    node.enabled &&
                        node.bounds.centerY() < state.effectiveScreenHeight() * 0.18f &&
                        node.bounds.centerX() < effectiveWidth * 0.18f &&
                        (
                            nodeLabel(node).contains("\u8f6c\u5230\u4e0a\u4e00\u5c42\u7ea7", ignoreCase = true) ||
                                nodeLabel(node).contains("\u8fd4\u56de", ignoreCase = true) ||
                                node.resourceId.orEmpty().contains("back", ignoreCase = true) ||
                                node.contentDesc.orEmpty().contains("\u8fd4\u56de", ignoreCase = true)
                            )
                }
                ?.minByOrNull { it.bounds.centerX() }
            val outcome = if (topBack != null && state != null) {
                tapNodeCenter(topBack, state)
            } else {
                actions.back()
            }
            events.put(JSONObject()
                .put("pass", pass)
                .put("surface", state?.let { bilibiliSurfaceKind(it) } ?: "no_ui_state")
                .put("action", if (topBack != null) "tap_top_back" else "global_back")
                .put("success", outcome.success)
                .put("message", outcome.reason))
            sleep(800L)

            if (pass == 4 || pass == 7) {
                val open = actions.openAppLauncherRoot(packageName)
                events.put(JSONObject()
                    .put("pass", pass)
                    .put("action", "reopen_launcher_root")
                    .put("success", open.success)
                    .put("message", open.reason))
                sleep(900L)
            }
        }

        return SkillJsonRunResult(
            "FAILED_RECOVER_SCREEN",
            "Bilibili recovery exhausted; still not on a trusted home/search/results surface. events=${events.toString().take(2200)}",
            usedCoordinate = true,
            details = JSONObject().put("events", events)
        )
    }

    private fun ensureHupuSectionSelected(skill: JSONObject): SkillJsonRunResult {
        val state = inspector.currentState()
            ?: return failed("FAILED_PRECONDITION", "No UiState for Hupu section selection")
        val labels = state.nodes.map { node -> nodeLabel(node) } +
            state.eventsSinceLastState.mapNotNull { event -> event.text?.takeIf { it.isNotBlank() } }
        val requiresSection = labels.any { label ->
            listOf("添加专区", "选择专区", "请选择专区", "请选择一个专区", "选择板块", "选择版块", "专区才能发帖")
                .any { marker -> label.contains(marker, ignoreCase = true) }
        }
        if (!requiresSection) {
            return SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", "No Hupu section blocker is visible")
        }

        listOf("添加专区", "选择专区", "请选择专区", "选择板块", "选择版块", "专区", "板块", "版块").forEach { label ->
            val click = actions.clickByVisibleText(label)
            if (click.success) {
                sleep(650L)
                val picked = pickFirstHupuSectionCandidate()
                return if (picked.success) {
                    SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", "Hupu section picker handled: ${picked.reason}", usedCoordinate = true)
                } else {
                    failed("FAILED_ELEMENT_NOT_FOUND", "Opened Hupu section selector but no section candidate was selectable: ${picked.reason}")
                }
            }
        }

        val picked = pickFirstHupuSectionCandidate()
        return if (picked.success) {
            SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", "Hupu section selected by visible candidate: ${picked.reason}", usedCoordinate = true)
        } else {
            val fallback = actions.tapNormalized(
                NormalizedPoint(0.50f, 0.34f),
                displayScreenWidth(),
                displayScreenHeight()
            )
            if (fallback.success) {
                SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", "Hupu section selected by fallback coordinate", usedCoordinate = true)
            } else {
                failed("FAILED_ELEMENT_NOT_FOUND", "Hupu requires a section, but no section candidate was selectable: ${fallback.reason}")
            }
        }
    }

    private fun verifyHupuPostPublished(skill: JSONObject, params: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: JSONObject()
        val expectedText = action.optString("text").ifBlank {
            paramValue(action.optString("textParam", "text"), params)
        }.trim()
        val timeoutMs = action.optLong("timeoutMs", 6_000L).coerceIn(1_000L, 15_000L)
        val startedAt = System.currentTimeMillis()
        var lastSummary = "no_ui_state"
        while (System.currentTimeMillis() - startedAt <= timeoutMs) {
            val state = inspector.currentState()
            if (state == null) {
                sleep(450L)
                continue
            }
            val labels = hupuVisibleSignals(state)
            lastSummary = labels.take(10).joinToString(" | ").take(700)
            val successMarker = labels.firstOrNull { label ->
                listOf("发布成功", "发帖成功", "发表成功", "审核中", "发布中", "已发布")
                    .any { marker -> label.contains(marker, ignoreCase = true) }
            }
            if (successMarker != null) {
                return SkillJsonRunResult(
                    "SUCCESS_MEDIUM_CONFIDENCE",
                    "Hupu publish feedback observed: $successMarker"
                )
            }
            val blocker = labels.firstOrNull { label -> isHupuPublishBlocker(label) }
            if (blocker != null) {
                return failed("FAILED_HUPU_PUBLISH_BLOCKED", "Hupu publish blocked: $blocker; labels=$lastSummary")
            }
            if (expectedText.isNotBlank()) {
                val contentVisible = labels.any { it.contains(expectedText, ignoreCase = true) }
                val contentStillInInput = state.nodes.any { node ->
                    node.editable && (node.text?.contains(expectedText, ignoreCase = true) == true ||
                        node.contentDesc?.contains(expectedText, ignoreCase = true) == true)
                }
                val postedSurface = isHupuPostDetailSurface(state) ||
                    labels.any { label ->
                        listOf("刚刚", "分钟前", "评论", "回复", "楼主")
                            .any { marker -> label.contains(marker, ignoreCase = true) }
                    }
                if (contentVisible && !contentStillInInput && postedSurface) {
                    return SkillJsonRunResult(
                        "SUCCESS",
                        "Hupu post content is visible outside the editor"
                    )
                }
            }
            sleep(500L)
        }
        return failed(
            "FAILED_UNVERIFIABLE",
            "Hupu publish evidence not visible within ${timeoutMs}ms; expectedText=${expectedText.take(80)}; labels=$lastSummary"
        )
    }

    private fun isHupuPublishBlocker(label: String): Boolean {
        val hardBlockers = listOf(
            "请选择专区",
            "选择专区",
            "请选择一个专区",
            "专区才能发帖",
            "标题不能为空",
            "内容不能为空",
            "发布失败",
            "发帖失败",
            "网络异常",
            "请登录",
            "登录后",
            "未登录",
            "账号验证",
            "身份验证",
            "完成验证",
            "验证码"
        )
        return hardBlockers.any { marker -> label.contains(marker, ignoreCase = true) }
    }

    private fun hupuVisibleSignals(state: UiStateSample): List<String> {
        val eventSignals = state.eventsSinceLastState.mapNotNull { event ->
            event.text?.takeIf { it.isNotBlank() }?.let { text -> "event:${event.eventType}:$text" }
        }
        val nodeSignals = state.nodes.map { nodeLabel(it) }.filter { it.isNotBlank() }
        return eventSignals + nodeSignals
    }

    private fun pickFirstHupuSectionCandidate(): com.macropilot.app.automation.ActionOutcome {
        val initial = pickVisibleHupuSectionCandidate()
        if (initial.success) return initial
        val search = actions.setTextByMatcher(
            listOf(ElementMatcher(editable = true, enabled = true)),
            "步行街"
        )
        if (search.success) {
            sleep(1_000L)
            val afterSearch = pickVisibleHupuSectionCandidate()
            if (afterSearch.success) {
                return com.macropilot.app.automation.ActionOutcome(
                    true,
                    "Searched default section keyword; ${afterSearch.reason}"
                )
            }
        }
        return com.macropilot.app.automation.ActionOutcome(
            false,
            "${initial.reason}; default section search=${search.reason}"
        )
    }

    private fun pickVisibleHupuSectionCandidate(): com.macropilot.app.automation.ActionOutcome {
        val state = inspector.currentState()
            ?: return com.macropilot.app.automation.ActionOutcome(false, "No UiState after opening section selector")
        val effectiveWidth = state.effectiveScreenWidth()
        val effectiveHeight = state.effectiveScreenHeight()
        val top = effectiveHeight * 0.18f
        val bottom = effectiveHeight * 0.82f
        val forbidden = listOf(
            "取消",
            "关闭",
            "返回",
            "搜索",
            "发布",
            "发表",
            "发帖",
            "选择",
            "请选择",
            "专区才能",
            "登录",
            "抱歉",
            "未找到",
            "未搜索到"
        )
        val candidate = state.nodes
            .asSequence()
            .filter { node ->
                    node.enabled &&
                    node.bounds.width() > effectiveWidth * 0.12f &&
                    node.bounds.height() > 16 &&
                    node.bounds.centerX() > effectiveWidth * 0.30f &&
                    node.bounds.centerY() in top.toInt()..bottom.toInt()
            }
            .map { node ->
                node to listOfNotNull(node.text, node.contentDesc)
                    .joinToString(" ")
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .trim()
            }
            .filter { (_, label) -> label.isNotBlank() && forbidden.none { label.contains(it, ignoreCase = true) } }
            .filter { (node, label) -> node.clickable || label.length in 2..24 }
            .sortedBy { (node, _) -> node.bounds.centerY() }
            .firstOrNull()
            ?: return com.macropilot.app.automation.ActionOutcome(false, "No safe section candidate visible")
        val node = candidate.first
        val x = node.bounds.centerX().toFloat() / effectiveWidth.coerceAtLeast(1)
        val y = node.bounds.centerY().toFloat() / effectiveHeight.coerceAtLeast(1)
        val tap = actions.tapNormalized(NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)), effectiveWidth, effectiveHeight)
        if (!tap.success) return tap
        sleep(800L)
        val after = inspector.currentState()
        return if (after != null && isHupuSectionChooserSurface(after)) {
            com.macropilot.app.automation.ActionOutcome(false, "Tapped section candidate but chooser is still visible")
        } else {
            tap
        }
    }

    private fun captureWechatBottomTabs(skill: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: JSONObject()
        val appPackage = action.optString("appPackage")
            .ifBlank { action.optString("packageName") }
            .ifBlank { skill.optString("appPackage") }
            .ifBlank { SkillJsonRuleGenerator.WECHAT_PACKAGE }
        val settleMs = action.optLong("settleMs", 1_200L).coerceIn(350L, 3_000L)
        val requestedBottomY = action.optDouble("bottomY", 0.955).toFloat()
        val bottomY = requestedBottomY.coerceIn(0.945f, 0.995f)
        val bottomYAttempts = action.optJSONArray("bottomYAttempts")
            .asFloatList()
            .ifEmpty { listOf(bottomY, 0.965f, 0.985f, 0.945f, 0.995f, 0.955f) }
            .map { it.coerceIn(0.945f, 0.995f) }
            .distinct()
        val events = JSONArray()

        MacroPilotAccessibilityService.instance?.showStatusOverlay(
            title = "记录微信底栏",
            status = "RUNNING",
            message = "逐个打开 微信 / 通讯录 / 发现 / 我，并保存节点 JSON + 截图证据"
        )

        if (inspector.currentState()?.appPackage != appPackage) {
            val open = actions.openAppLauncherRoot(appPackage)
            events.put(JSONObject()
                .put("phase", "open_wechat")
                .put("success", open.success)
                .put("message", open.reason))
            sleep(1_500L)
        }
        val reactive = runReactiveOverlays(appPackage, "wechat_bottom_tabs:initial", maxPasses = 3)
        if (reactive.dismissedCount > 0) {
            events.put(JSONObject().put("phase", "initial_reactive").put("dismissed", reactive.dismissedCount))
        }

        val tabs = listOf(
            WechatBottomTabTarget("wechat", "\u5fae\u4fe1", 0.125f),
            WechatBottomTabTarget("contacts", "\u901a\u8baf\u5f55", 0.375f),
            WechatBottomTabTarget("discover", "\u53d1\u73b0", 0.625f),
            WechatBottomTabTarget("me", "\u6211", 0.875f)
        )
        val tabXs = tabs.map { it.x }
        val recoveredToTabShell = recoverToWechatTabShell(appPackage, tabXs, skill.optString("id"), events)
        if (!recoveredToTabShell) {
            saveCurrentSnapshot(
                source = "wechat_bottom_tab_capture_failed",
                note = "bottom_tab_not_visible_after_back_recovery",
                relatedSkillId = skill.optString("id"),
                relatedActionType = "CaptureWechatBottomTabs"
            )
            return failed(
                "FAILED_WECHAT_TAB_SHELL_NOT_VISIBLE",
                "微信底栏不可见，已停止坐标点击，避免继续点到聊天内容；events=${events.toString().take(1800)}"
            )
        }
        var captured = 0
        var usedCoordinate = false
        val acceptedVisualHashes = mutableSetOf<String>()
        tabs.forEachIndexed { index, tab ->
            if (!ensureWechatTabShellStillVisible(appPackage, tabXs, skill.optString("id"), events)) {
                events.put(JSONObject()
                    .put("phase", "tab_shell_lost")
                    .put("tabKey", tab.key)
                    .put("message", "底栏消失，停止后续 tab 点击，避免误点聊天内容"))
                return@forEachIndexed
            }
            var acceptedRecord: UiSnapshotRecord? = null
            val attempts = JSONArray()
            val stateForNode = inspector.currentState()
            val node = findBottomTabNode(stateForNode, tab.label)
            val attemptPoints = buildList {
                if (node != null && stateForNode != null) add(null)
                bottomYAttempts.forEach { y -> add(NormalizedPoint(tab.x, y)) }
            }
            attemptPoints.forEachIndexed attemptLoop@ { attemptIndex, point ->
                if (acceptedRecord != null) return@attemptLoop
                val state = inspector.currentState()
                val outcome = if (point == null && node != null && stateForNode != null) {
                    tapNodeCenter(node, stateForNode)
                } else {
                    usedCoordinate = true
                    val target = point ?: NormalizedPoint(tab.x, bottomY)
                    actions.tapNormalized(
                        target,
                        displayScreenWidth(),
                        displayScreenHeight()
                    )
                }
                sleep(settleMs + (attemptIndex * 180L).coerceAtMost(540L))
                val afterReactive = runReactiveOverlays(appPackage, "wechat_bottom_tabs:${tab.key}:attempt=$attemptIndex", maxPasses = 2)
                if (afterReactive.dismissedCount > 0) sleep(350L)
                val record = saveCurrentSnapshotRecord(
                    source = "wechat_bottom_tab_capture",
                    note = "${tab.key}:${tab.label}; attempt=$attemptIndex; click=${outcome.success}; reactive=${afterReactive.dismissedCount}",
                    relatedSkillId = skill.optString("id"),
                    relatedActionType = "CaptureWechatBottomTabs",
                    forceScreenshot = true,
                    hideOverlayForScreenshot = true
                )
                val selected = wechatSelectedTabEvidence(record?.screenshotPath, tabXs)
                val selectedIndex = selected.optInt("selectedIndex", -1)
                val selectedScore = selected.optInt("selectedScore", 0)
                val visualHash = record?.visualHash.orEmpty()
                val duplicate = visualHash.isNotBlank() && acceptedVisualHashes.contains(visualHash)
                val selectedVerified = selectedIndex == index && selectedScore >= action.optInt("selectedScoreMin", 12)
                val accepted = record != null &&
                    visualHash.isNotBlank() &&
                    !duplicate &&
                    selectedVerified
                attempts.put(JSONObject()
                    .put("attempt", attemptIndex)
                    .put("tapY", point?.y ?: JSONObject.NULL)
                    .put("usedCoordinate", point != null || node == null)
                    .put("tapSuccess", outcome.success)
                    .put("tapMessage", outcome.reason)
                    .put("snapshot", record?.path ?: JSONObject.NULL)
                    .put("screenshotPath", record?.screenshotPath ?: JSONObject.NULL)
                    .put("visualHash", if (visualHash.isBlank()) JSONObject.NULL else visualHash)
                    .put("duplicateVisualHash", duplicate)
                    .put("selectedEvidence", selected)
                    .put("accepted", accepted))
                if (accepted) {
                    acceptedRecord = record
                    acceptedVisualHashes += visualHash
                }
            }
            if (acceptedRecord != null) captured++
            events.put(JSONObject()
                .put("phase", "capture_tab")
                .put("index", index)
                .put("tabKey", tab.key)
                .put("tabLabel", tab.label)
                .put("accepted", acceptedRecord != null)
                .put("snapshot", acceptedRecord?.path ?: JSONObject.NULL)
                .put("screenshotPath", acceptedRecord?.screenshotPath ?: JSONObject.NULL)
                .put("attempts", attempts))
            MacroPilotAccessibilityService.instance?.showStatusOverlay(
                title = "记录微信底栏",
                status = if (acceptedRecord != null) "SUCCESS" else "RUNNING",
                message = "${tab.label}：${if (acceptedRecord != null) "已保存并验证选中" else "未确认切换，已记录失败证据"}"
            )
        }

        return if (captured == tabs.size) {
            SkillJsonRunResult(
                status = "SUCCESS_MEDIUM_CONFIDENCE",
                message = "Captured verified WeChat bottom tab UI snapshots: $captured/4; events=${events.toString().take(2400)}",
                usedCoordinate = usedCoordinate,
                details = JSONObject()
                    .put("captured", captured)
                    .put("expected", tabs.size)
                    .put("events", events)
            )
        } else {
            SkillJsonRunResult(
                status = "FAILED_UI_SNAPSHOT_CAPTURE",
                message = "Captured only $captured/4 verified WeChat bottom tab snapshots. Tap dispatch alone is not success; events=${events.toString().take(2400)}",
                usedCoordinate = usedCoordinate,
                details = JSONObject()
                    .put("captured", captured)
                    .put("expected", tabs.size)
                    .put("events", events)
            )
        }
    }

    private fun recoverToWechatTabShell(
        appPackage: String,
        tabXs: List<Float>,
        skillId: String,
        events: JSONArray
    ): Boolean {
        repeat(6) { index ->
            val probe = saveCurrentSnapshotRecord(
                source = "wechat_tab_shell_probe",
                note = "before_capture:index=$index",
                relatedSkillId = skillId,
                relatedActionType = "CaptureWechatBottomTabs",
                forceScreenshot = true,
                hideOverlayForScreenshot = true
            )
            val evidence = wechatSelectedTabEvidence(probe?.screenshotPath, tabXs)
            val visible = evidence.optInt("selectedScore", 0) >= 8
            events.put(JSONObject()
                .put("phase", "tab_shell_probe")
                .put("index", index)
                .put("visible", visible)
                .put("snapshot", probe?.path ?: JSONObject.NULL)
                .put("screenshotPath", probe?.screenshotPath ?: JSONObject.NULL)
                .put("selectedEvidence", evidence))
            if (visible) return true

            val state = inspector.currentState()
            if (state?.appPackage != appPackage) {
                val open = actions.openAppLauncherRoot(appPackage)
                events.put(JSONObject()
                    .put("phase", "tab_shell_reopen")
                    .put("index", index)
                    .put("success", open.success)
                    .put("message", open.reason))
                sleep(1_200L)
            } else {
                val reason = evidence.optString("reason")
                    .ifBlank { evidence.optString("surfaceWarning") }
                if (reason == "wechat_draft_confirm_dialog_visible") {
                    val discard = actions.tapNormalized(
                        NormalizedPoint(0.30f, 0.565f),
                        displayScreenWidth(),
                        displayScreenHeight()
                    )
                    events.put(JSONObject()
                        .put("phase", "tab_shell_discard_wechat_moment_draft")
                        .put("index", index)
                        .put("success", discard.success)
                        .put("message", discard.reason)
                        .put("reason", reason))
                    sleep(1_000L)
                    return@repeat
                }
                if (reason == "bottom_sheet_dialog_visible") {
                    val dismiss = actions.tapNormalized(
                        NormalizedPoint(0.33f, 0.885f),
                        displayScreenWidth(),
                        displayScreenHeight()
                    )
                    events.put(JSONObject()
                        .put("phase", "tab_shell_dismiss_wechat_bottom_sheet")
                        .put("index", index)
                        .put("success", dismiss.success)
                        .put("message", dismiss.reason)
                        .put("reason", reason))
                    sleep(900L)
                    return@repeat
                }
                if ((reason == "chat_composer_visible" || reason == "keyboard_or_emoji_panel_visible") && index >= 3) {
                    val rootOpen = actions.openAppLauncherRoot(appPackage)
                    events.put(JSONObject()
                        .put("phase", "tab_shell_root_reopen_after_chat_surface")
                        .put("index", index)
                        .put("success", rootOpen.success)
                        .put("message", rootOpen.reason)
                        .put("reason", reason))
                    sleep(1_200L)
                    return@repeat
                }
                if (reason == "chat_composer_visible") {
                    val arrow = actions.tapNormalized(
                        NormalizedPoint(0.055f, 0.088f),
                        displayScreenWidth(),
                        displayScreenHeight()
                    )
                    events.put(JSONObject()
                        .put("phase", "tab_shell_click_wechat_chat_back_arrow")
                        .put("index", index)
                        .put("success", arrow.success)
                        .put("message", arrow.reason)
                        .put("reason", reason))
                    sleep(900L)
                } else {
                    val back = actions.back()
                    events.put(JSONObject()
                        .put("phase", "tab_shell_back")
                        .put("index", index)
                        .put("success", back.success)
                        .put("message", back.reason)
                        .put("reason", reason.ifBlank { JSONObject.NULL }))
                    sleep(650L)
                }
            }
            runReactiveOverlays(appPackage, "wechat_tab_shell_recover:$index", maxPasses = 1)
        }
        return false
    }

    private fun ensureWechatTabShellStillVisible(
        appPackage: String,
        tabXs: List<Float>,
        skillId: String,
        events: JSONArray
    ): Boolean {
        val probe = saveCurrentSnapshotRecord(
            source = "wechat_tab_shell_guard",
            note = "before_tab_tap",
            relatedSkillId = skillId,
            relatedActionType = "CaptureWechatBottomTabs",
            forceScreenshot = true,
            hideOverlayForScreenshot = true
        )
        val evidence = wechatSelectedTabEvidence(probe?.screenshotPath, tabXs)
        if (evidence.optInt("selectedScore", 0) >= 8) return true
        val screenshotMissing = evidence.optString("reason") == "no_screenshot"
        val currentPackage = inspector.currentState()?.appPackage
        if (screenshotMissing && currentPackage == appPackage) {
            events.put(JSONObject()
                .put("phase", "tab_shell_guard_inconclusive")
                .put("message", "截图失败但仍在目标应用，继续执行，不把截图失败当成页面消失")
                .put("snapshot", probe?.path ?: JSONObject.NULL)
                .put("selectedEvidence", evidence))
            return true
        }
        events.put(JSONObject()
            .put("phase", "tab_shell_guard_failed")
            .put("snapshot", probe?.path ?: JSONObject.NULL)
            .put("screenshotPath", probe?.screenshotPath ?: JSONObject.NULL)
            .put("selectedEvidence", evidence))
        return recoverToWechatTabShell(appPackage, tabXs, skillId, events)
    }

    private fun findBottomTabNode(state: UiStateSample?, label: String): NodeSample? {
        if (state == null) return null
        val height = state.effectiveScreenHeight()
        val bottomStart = (height * 0.68f).toInt()
        return state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    node.bounds.centerY() >= bottomStart &&
                    (node.clickable || !node.text.isNullOrBlank() || !node.contentDesc.isNullOrBlank())
            }
            .filter { node -> nodeLabel(node).contains(label, ignoreCase = true) }
            .minByOrNull { node -> kotlin.math.abs(node.bounds.centerY() - (height * 0.92f).toInt()) }
    }

    private fun findEntryByBottomTabSweep(skill: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: JSONObject()
        val appPackage = action.optString("appPackage").ifBlank { skill.optString("appPackage") }
        val aliases = entryAliases(action)
        if (aliases.isEmpty()) {
            return failed("FAILED_PRECONDITION", "FindEntryByBottomTabSweep requires entryAliases")
        }
        val maxTabs = action.optInt("maxTabs", 5).coerceIn(1, 8)
        val homeBackPresses = action.optInt("homeBackPresses", 3).coerceIn(0, 6)
        val timeoutMs = action.optLong("timeoutMs", 12_000L).coerceIn(2_000L, 30_000L)
        val events = JSONArray()
        val hupuPostSweep = isHupuPackage(appPackage) && aliasesWantPostEntry(aliases)
        var hupuHomeOrEntryConfirmed = false
        MacroPilotAccessibilityService.instance?.showStatusOverlay(
            title = "Find entry",
            status = "RUNNING",
            message = "Return home, sweep bottom tabs, find ${aliases.joinToString("/")}"
        )

        val recovered = recoverToAppHomeForEntrySweep(appPackage, homeBackPresses, events)
        if (!recovered && appPackage.isNotBlank()) {
            return failed(
                "FAILED_PRECONDITION",
                "Could not open target app before bottom-tab sweep: $appPackage; events=${events.toString().take(1200)}"
            )
        }

        var state = inspector.currentState()
        runReactiveOverlays(appPackage, "entry_sweep:after_recover", maxPasses = 3).also { reactive ->
            if (reactive.dismissedCount > 0) {
                events.put(JSONObject().put("phase", "reactive_after_recover").put("dismissed", reactive.dismissedCount))
            }
        }
        closeTransientSheetsForEntrySweep(events)
        state = inspector.currentState()
        if (hupuPostSweep) {
            val hupuReady = recoverHupuHomeForEntrySweep(appPackage, events)
            hupuHomeOrEntryConfirmed = hupuReady
            state = inspector.currentState()
            if (!hupuReady || (state != null && isHupuUnsafeEntrySweepSurface(state))) {
                return failed(
                    "FAILED_PRECONDITION",
                    "Hupu entry sweep refused to scan detail/share/editor surface; surface=${state?.let { hupuSurfaceKind(it) }}; events=${events.toString().take(1600)}"
                )
            }
        }
        if (entryDestinationReached(state, aliases)) {
            events.put(JSONObject()
                .put("phase", "entry_destination_reached")
                .put("sourcePhase", "after_recover")
                .put("aliases", JSONArray(aliases)))
            return SkillJsonRunResult(
                status = "SUCCESS_MEDIUM_CONFIDENCE",
                message = "Entry surface already visible after recovery; aliases=${aliases.joinToString("/")}; events=${events.toString().take(1600)}",
                usedCoordinate = true
            )
        }
        findEntryCandidate(state, aliases)?.let { entry ->
            return clickEntryCandidate(entry, state, aliases, events, "initial_page")
        }

        val initialTabs = bottomTabCandidates(state, maxTabs, action, appPackage)
            .ifEmpty {
                if (hupuPostSweep && hupuHomeOrEntryConfirmed) {
                    events.put(JSONObject()
                        .put("phase", "hupu_home_center_fallback_without_tab_nodes")
                        .put("surface", state?.let { hupuSurfaceKind(it) } ?: "no_ui_state")
                        .put("reason", "recoverHupuHomeForEntrySweep confirmed home/entry but tab candidates were empty"))
                    listOf(BottomTabCandidate("\u864e\u6251\u4e2d\u95f4\u521b\u4f5c\u5165\u53e3_\u6062\u590d\u540e\u5750\u6807\u5019\u9009", 0.50f, 0.925f, 110))
                } else {
                    emptyList()
                }
            }
        if (initialTabs.isEmpty()) {
            return failed(
                "FAILED_ELEMENT_NOT_FOUND",
                "No bottom tabs found while looking for ${aliases.joinToString("/")}; events=${events.toString().take(1200)}"
            )
        }

        val visited = mutableSetOf<String>()
        var usedCoordinate = false
        val sweepStartedAt = System.currentTimeMillis()
        for ((index, tab) in initialTabs.withIndex()) {
            if (System.currentTimeMillis() - sweepStartedAt > timeoutMs) {
                break
            }
            val clickedHupuCenterEntry = hupuPostSweep && tab.label.contains("\u4e2d\u95f4\u521b\u4f5c\u5165\u53e3")
            val tabKey = "${(tab.x * 100).toInt()}:${tab.label}"
            if (!visited.add(tabKey)) continue
            MacroPilotAccessibilityService.instance?.showStatusOverlay(
                title = "Sweep tab ${index + 1}/${initialTabs.size}",
                status = "RUNNING",
                message = "Open tab ${tab.label.take(20)}; then find ${aliases.joinToString("/")}"
            )
            val clickState = state
            val clickTab = actions.tapNormalized(
                NormalizedPoint(tab.x.coerceIn(0f, 1f), tab.y.coerceIn(0f, 1f)),
                clickState?.effectiveScreenWidth() ?: displayScreenWidth(),
                clickState?.effectiveScreenHeight() ?: displayScreenHeight()
            )
            usedCoordinate = true
            events.put(JSONObject()
                .put("phase", "click_bottom_tab")
                .put("index", index)
                .put("label", tab.label)
                .put("x", tab.x)
                .put("y", tab.y)
                .put("success", clickTab.success)
                .put("message", clickTab.reason))
            if (!clickTab.success) continue
            sleep(action.optLong("tabSettleMs", 900L).coerceIn(250L, 2_500L))
            val reactive = if (clickedHupuCenterEntry) {
                events.put(JSONObject()
                    .put("phase", "skip_reactive_after_hupu_center_entry")
                    .put("index", index)
                    .put("label", tab.label))
                ReactiveRuleResult(0, JSONArray())
            } else {
                runReactiveOverlays(appPackage, "entry_sweep:after_tab:$index", maxPasses = 2)
            }
            if (reactive.dismissedCount > 0) {
                events.put(JSONObject().put("phase", "reactive_after_tab").put("index", index).put("dismissed", reactive.dismissedCount))
                sleep(350L)
            }
            if (clickedHupuCenterEntry) {
                events.put(JSONObject()
                    .put("phase", "skip_close_transient_after_hupu_center_entry")
                    .put("index", index)
                    .put("label", tab.label))
            } else {
                closeTransientSheetsForEntrySweep(events)
            }
            state = inspector.currentState()
            if (clickedHupuCenterEntry && state != null && isHupuHomeSurface(state)) {
                val retryState = state
                val retry = actions.tapNormalized(
                    NormalizedPoint(tab.x.coerceIn(0f, 1f), 0.900f),
                    retryState.effectiveScreenWidth(),
                    retryState.effectiveScreenHeight()
                )
                events.put(JSONObject()
                    .put("phase", "retry_hupu_center_entry_higher")
                    .put("index", index)
                    .put("label", tab.label)
                    .put("success", retry.success)
                    .put("message", retry.reason))
                sleep(900L)
                state = inspector.currentState()
            }
            if (state != null && isTransientSheetForEntrySweep(state)) {
                events.put(JSONObject().put("phase", "skip_transient_sheet_after_tab").put("index", index).put("label", tab.label))
                continue
            }
            if (entryDestinationReached(state, aliases)) {
                events.put(JSONObject()
                    .put("phase", "entry_destination_reached")
                    .put("tabIndex", index)
                    .put("tabLabel", tab.label)
                    .put("aliases", JSONArray(aliases)))
                return SkillJsonRunResult(
                    status = "SUCCESS_MEDIUM_CONFIDENCE",
                    message = "Bottom tab '${tab.label}' opened the requested entry surface; aliases=${aliases.joinToString("/")}; events=${events.toString().take(1600)}",
                    usedCoordinate = true
                )
            }
            if (hupuPostSweep && state != null && isHupuUnsafeEntrySweepSurface(state)) {
                events.put(JSONObject()
                    .put("phase", "hupu_unsafe_surface_after_tab")
                    .put("tabIndex", index)
                    .put("tabLabel", tab.label)
                    .put("surface", hupuSurfaceKind(state))
                    .put("labels", JSONArray(state.nodes.map { nodeLabel(it) }.filter { it.isNotBlank() }.take(12))))
                recoverHupuHomeForEntrySweep(appPackage, events)
                state = inspector.currentState()
                continue
            }
            findEntryCandidate(state, aliases)?.let { entry ->
                return clickEntryCandidate(entry, state, aliases, events, "tab_${index + 1}")
            }
        }

        return SkillJsonRunResult(
            status = "FAILED_ELEMENT_NOT_FOUND",
            message = "Bottom-tab sweep did not find entry aliases=${aliases.joinToString("/")}; tabsVisited=${visited.size}; events=${events.toString().take(1800)}",
            usedCoordinate = usedCoordinate
        )
    }

    private fun entryDestinationReached(state: UiStateSample?, aliases: List<String>): Boolean {
        if (state == null) return false
        if (isTransientSheetForEntrySweep(state)) return false
        val labels = state.nodes.map { nodeLabel(it) }
        val aliasText = aliases.joinToString(" ").lowercase(Locale.CHINA)
        val wantsPost = listOf("\u53d1\u5e16", "\u53d1\u5e03", "\u521b\u4f5c", "\u5199", "post", "publish", "+")
            .any { aliasText.contains(it.lowercase(Locale.CHINA)) }
        if (wantsPost) {
            if (state.appPackage.orEmpty().contains("hupu", ignoreCase = true)) {
                return isHupuPostEditorSurface(state) ||
                    isHupuCreateEntrySheet(state) ||
                    isHupuSectionRequiredSurface(state)
            }
            val editor = state.nodes.any { it.editable } ||
                labels.any { label ->
                    listOf(
                        "\u9009\u62e9\u4e13\u533a",
                        "\u8bf7\u9009\u62e9\u4e13\u533a",
                        "\u6807\u9898",
                        "\u6b63\u6587",
                        "\u8bf4\u70b9\u4ec0\u4e48",
                        "\u64b0\u5199",
                        "\u53d1\u5e03\u7b14\u8bb0"
                    ).any { marker -> label.contains(marker, ignoreCase = true) }
                }
            if (editor) return true
        }
        val wantsSearch = aliases.any { it.contains("\u641c", ignoreCase = true) || it.contains("search", ignoreCase = true) }
        if (wantsSearch && (state.nodes.any { it.editable } || labels.any { it.contains("\u641c\u7d22", ignoreCase = true) })) return true
        val wantsMessage = aliases.any {
            it.contains("\u6d88\u606f", ignoreCase = true) ||
                it.contains("\u804a\u5929", ignoreCase = true) ||
                it.contains("message", ignoreCase = true)
        }
        if (wantsMessage && labels.any { it.contains("\u6d88\u606f", ignoreCase = true) || it.contains("\u79c1\u4fe1", ignoreCase = true) }) return true
        return false
    }

    private fun entryAliases(action: JSONObject): List<String> {
        val arrays = listOfNotNull(
            action.optJSONArray("entryAliases"),
            action.optJSONArray("aliases"),
            action.optJSONArray("textAliases")
        )
        val out = mutableListOf<String>()
        arrays.forEach { array ->
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) out += value
            }
        }
        listOf("entryText", "text", "targetText").forEach { key ->
            val value = action.optString(key).trim()
            if (value.isNotBlank()) out += value
        }
        return out.distinctBy { it.lowercase(Locale.CHINA) }
    }

    private fun recoverToAppHomeForEntrySweep(appPackage: String, homeBackPresses: Int, events: JSONArray): Boolean {
        if (appPackage.isNotBlank()) {
            val open = actions.openApp(appPackage)
            events.put(JSONObject().put("phase", "open_before_recover").put("packageName", appPackage).put("success", open.success).put("message", open.reason))
            sleep(1_400L)
        }
        runReactiveOverlays(appPackage, "entry_sweep:before_back", maxPasses = 2)
        repeat(homeBackPresses) { index ->
            val before = inspector.currentState()
            val back = actions.back()
            events.put(JSONObject()
                .put("phase", "home_back")
                .put("index", index + 1)
                .put("fromPackage", before?.appPackage ?: JSONObject.NULL)
                .put("window", before?.windowClassName ?: JSONObject.NULL)
                .put("success", back.success)
                .put("message", back.reason))
            sleep(450L)
            runReactiveOverlays(appPackage, "entry_sweep:after_back:${index + 1}", maxPasses = 1)
        }
        if (appPackage.isBlank()) return true
        val open = actions.openApp(appPackage)
        events.put(JSONObject().put("phase", "open_after_recover").put("packageName", appPackage).put("success", open.success).put("message", open.reason))
        sleep(1_700L)
        runReactiveOverlays(appPackage, "entry_sweep:after_open", maxPasses = 3)
        closeTransientSheetsForEntrySweep(events)
        val currentPackage = inspector.currentState()?.appPackage
        events.put(JSONObject().put("phase", "recover_current_package").put("packageName", currentPackage ?: JSONObject.NULL))
        return open.success && (currentPackage == null || currentPackage == appPackage || currentPackage.contains(appPackage.substringBeforeLast('.'), ignoreCase = true))
    }

    private fun recoverHupuHomeForEntrySweep(appPackage: String, events: JSONArray): Boolean {
        repeat(6) { index ->
            closeTransientSheetsForEntrySweep(events)
            val state = inspector.currentState()
            if (state == null) {
                events.put(JSONObject().put("phase", "hupu_home_recover").put("index", index + 1).put("surface", "no_ui_state"))
                val open = actions.openApp(appPackage)
                events.put(JSONObject().put("phase", "hupu_home_recover_open").put("success", open.success).put("message", open.reason))
                sleep(900L)
                return@repeat
            }
            val surface = hupuSurfaceKind(state)
            events.put(JSONObject()
                .put("phase", "hupu_home_probe")
                .put("index", index + 1)
                .put("surface", surface)
                .put("labels", JSONArray(state.nodes.map { nodeLabel(it).take(44) }.filter { it.isNotBlank() }.take(6))))
            if (!state.appPackage.orEmpty().contains("hupu", ignoreCase = true)) {
                val open = actions.openApp(appPackage)
                events.put(JSONObject().put("phase", "hupu_home_recover_open").put("success", open.success).put("message", open.reason))
                sleep(1_000L)
                return@repeat
            }
            if (isHupuHomeSurface(state) ||
                isHupuPostEditorSurface(state) ||
                isHupuCreateEntrySheet(state) ||
                isHupuSectionRequiredSurface(state)
            ) {
                return true
            }
            if (isHupuSearchSurface(state) || isHupuChannelManagerSurface(state)) {
                closeHupuSearchLikeSurface(state, surface, index + 1, appPackage, events)
                return@repeat
            }
            val back = actions.back()
            events.put(JSONObject()
                .put("phase", "hupu_home_recover_back")
                .put("index", index + 1)
                .put("surface", surface)
                .put("success", back.success)
                .put("message", back.reason))
            sleep(750L)
            runReactiveOverlays(appPackage, "hupu_home_recover:$index", maxPasses = 2)
        }
        closeTransientSheetsForEntrySweep(events)
        val finalState = inspector.currentState()
        return finalState != null &&
            finalState.appPackage.orEmpty().contains("hupu", ignoreCase = true) &&
            (isHupuHomeSurface(finalState) ||
                isHupuPostEditorSurface(finalState) ||
                isHupuCreateEntrySheet(finalState) ||
                isHupuSectionRequiredSurface(finalState))
    }

    private fun closeHupuSearchLikeSurface(
        state: UiStateSample,
        surface: String,
        index: Int,
        appPackage: String,
        events: JSONArray
    ): Boolean {
        val closeNode = state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    node.resourceId.orEmpty().contains("iv_close", ignoreCase = true) &&
                    node.bounds.centerY() < state.effectiveScreenHeight() * 0.22f
            }
            .sortedBy { node -> node.bounds.centerX() }
            .firstOrNull()
        val click = actions.clickByMatcher(listOf(ElementMatcher(resourceId = "iv_close", enabled = true)))
        events.put(JSONObject()
            .put("phase", "hupu_home_recover_close")
            .put("index", index)
            .put("surface", surface)
            .put("method", "accessibility_click")
            .put("success", click.success)
            .put("message", click.reason))
        sleep(700L)
        runReactiveOverlays(appPackage, "hupu_home_recover_close:$index", maxPasses = 1)
        if (hupuRecoveredFromSearchLike(surface)) return true

        if (closeNode != null) {
            val tap = tapNodeCenter(closeNode, state)
            events.put(JSONObject()
                .put("phase", "hupu_home_recover_close")
                .put("index", index)
                .put("surface", surface)
                .put("method", "coordinate_close")
                .put("x", closeNode.bounds.centerX().toFloat() / state.effectiveScreenWidth().coerceAtLeast(1))
                .put("y", closeNode.bounds.centerY().toFloat() / state.effectiveScreenHeight().coerceAtLeast(1))
                .put("success", tap.success)
                .put("message", tap.reason))
            sleep(750L)
            if (hupuRecoveredFromSearchLike(surface)) return true
        }

        val back = actions.back()
        events.put(JSONObject()
            .put("phase", "hupu_home_recover_close")
            .put("index", index)
            .put("surface", surface)
            .put("method", "back_after_close_failed")
            .put("success", back.success)
            .put("message", back.reason))
        sleep(800L)
        return hupuRecoveredFromSearchLike(surface)
    }

    private fun hupuRecoveredFromSearchLike(previousSurface: String): Boolean {
        val after = inspector.currentState() ?: return false
        if (!after.appPackage.orEmpty().contains("hupu", ignoreCase = true)) return false
        val now = hupuSurfaceKind(after)
        return now != previousSurface &&
            (isHupuHomeSurface(after) ||
                isHupuPostEditorSurface(after) ||
                isHupuCreateEntrySheet(after) ||
                isHupuSectionRequiredSurface(after))
    }

    private fun closeTransientSheetsForEntrySweep(events: JSONArray) {
        repeat(4) { index ->
            val state = inspector.currentState() ?: return
            if (!isTransientSheetForEntrySweep(state)) return
            val cancel = actions.clickByMatcher(listOf(ElementMatcher(resourceId = "btn_cancel", enabled = true)))
                .takeIf { it.success }
                ?: actions.clickByVisibleText("\u53d6\u6d88")
            events.put(JSONObject()
                .put("phase", "close_transient_sheet")
                .put("index", index + 1)
                .put("window", state.windowClassName ?: JSONObject.NULL)
                .put("method", "cancel_button_or_text")
                .put("success", cancel.success)
                .put("message", cancel.reason)
                .put("labels", JSONArray(state.nodes.map { nodeLabel(it) }.filter { it.isNotBlank() }.take(12))))
            sleep(420L)
            if (!isTransientSheetForEntrySweep(inspector.currentState() ?: return)) return
            val touchOutside = actions.clickByMatcher(listOf(ElementMatcher(resourceId = "touch_outside", enabled = true)))
            events.put(JSONObject()
                .put("phase", "close_transient_sheet")
                .put("index", index + 1)
                .put("method", "touch_outside_node")
                .put("success", touchOutside.success)
                .put("message", touchOutside.reason))
            sleep(420L)
            if (!isTransientSheetForEntrySweep(inspector.currentState() ?: return)) return
            val back = actions.back()
            events.put(JSONObject()
                .put("phase", "close_transient_sheet")
                .put("index", index + 1)
                .put("method", "back")
                .put("success", back.success)
                .put("message", back.reason))
            sleep(520L)
            val afterBack = inspector.currentState() ?: return
            if (!isTransientSheetForEntrySweep(afterBack)) return
            val outside = actions.tapNormalized(
                NormalizedPoint(0.50f, 0.55f),
                displayScreenWidth(),
                displayScreenHeight()
            )
            events.put(JSONObject()
                .put("phase", "close_transient_sheet")
                .put("index", index + 1)
                .put("method", "outside_tap")
                .put("success", outside.success)
                .put("message", outside.reason))
            sleep(520L)
        }
    }

    private fun isTransientSheetForEntrySweep(state: UiStateSample): Boolean {
        val window = state.windowClassName.orEmpty()
        if (isHupuCreateEntrySheet(state) || isHupuSectionRequiredSurface(state)) return false
        if (window.contains("BottomSheet", ignoreCase = true) || window.contains("Dialog", ignoreCase = true)) return true
        if (state.nodes.any { it.resourceId.orEmpty().contains("design_bottom_sheet", ignoreCase = true) }) return true
        if (state.nodes.any { it.resourceId.orEmpty().contains("share_content_container", ignoreCase = true) }) return true
        val labels = state.nodes.map { nodeLabel(it) }
        val shareMarkers = listOf(
            "\u5fae\u4fe1\u597d\u53cb",
            "\u5fae\u4fe1\u670b\u53cb\u5708",
            "QQ\u597d\u53cb",
            "QQ\u7a7a\u95f4",
            "\u65b0\u6d6a\u5fae\u535a",
            "\u590d\u5236\u94fe\u63a5",
            "\u5206\u4eab\u6d77\u62a5",
            "\u4e0d\u63a8\u8350",
            "\u9605\u8bfb\u8bbe\u7f6e",
            "\u53d6\u6d88"
        )
        val hits = labels.count { label -> shareMarkers.any { marker -> label.contains(marker, ignoreCase = true) } }
        return hits >= 2
    }

    private fun isHupuCreateEntrySheet(state: UiStateSample): Boolean {
        if (!state.appPackage.orEmpty().contains("hupu", ignoreCase = true)) return false
        val effectiveHeight = state.effectiveScreenHeight()
        val window = state.windowClassName.orEmpty()
        val labels = state.nodes.map { nodeLabel(it) }
        val sheetLike = window.contains("BottomSheet", ignoreCase = true) ||
            window.contains("Dialog", ignoreCase = true) ||
            state.nodes.any { it.resourceId.orEmpty().contains("design_bottom_sheet", ignoreCase = true) } ||
            state.nodes.any { node ->
                val label = nodeLabel(node)
                node.bounds.centerY() > effectiveHeight * 0.48f &&
                    listOf(
                        "\u53d1\u5e16",
                        "\u53d1\u5e03",
                        "\u5199\u5e16",
                        "\u95ee\u95eeJR",
                        "\u95ee\u7b54",
                        "\u56fe\u6587",
                        "\u56fe\u7247",
                        "\u89c6\u9891",
                        "\u6295\u7968",
                        "\u5e16\u5b50"
                    ).any { marker -> label.contains(marker, ignoreCase = true) }
            }
        if (!sheetLike) return false
        val createHits = listOf(
            "\u53d1\u5e16",
            "\u53d1\u5e03",
            "\u5199\u5e16",
            "\u521b\u4f5c",
            "\u56fe\u6587",
            "\u95ee\u95eeJR",
            "\u95ee\u7b54",
            "\u56fe\u7247",
            "\u89c6\u9891",
            "\u6295\u7968",
            "\u5e16\u5b50"
        ).count { marker -> labels.any { label -> label.contains(marker, ignoreCase = true) } }
        if (createHits <= 0) return false
        val shareHits = listOf(
            "\u5fae\u4fe1\u597d\u53cb",
            "\u5fae\u4fe1\u670b\u53cb\u5708",
            "QQ\u597d\u53cb",
            "QQ\u7a7a\u95f4",
            "\u590d\u5236\u94fe\u63a5",
            "\u5206\u4eab\u6d77\u62a5",
            "\u4e0d\u63a8\u8350",
            "\u4e3e\u62a5"
        ).count { marker -> labels.any { label -> label.contains(marker, ignoreCase = true) } }
        return shareHits < 2
    }

    private fun isHupuSectionRequiredSurface(state: UiStateSample): Boolean {
        if (!state.appPackage.orEmpty().contains("hupu", ignoreCase = true)) return false
        val labels = state.nodes.map { nodeLabel(it) } +
            state.eventsSinceLastState.mapNotNull { event -> event.text?.takeIf { it.isNotBlank() } }
        return listOf(
            "\u9009\u62e9\u4e13\u533a",
            "\u8bf7\u9009\u62e9\u4e13\u533a",
            "\u8bf7\u9009\u62e9\u4e00\u4e2a\u4e13\u533a",
            "\u9009\u62e9\u677f\u5757",
            "\u9009\u62e9\u7248\u5757",
            "\u4e13\u533a\u624d\u80fd\u53d1\u5e16"
        ).any { marker -> labels.any { label -> label.contains(marker, ignoreCase = true) } }
    }

    private fun isHupuSectionChooserSurface(state: UiStateSample): Boolean {
        if (!state.appPackage.orEmpty().contains("hupu", ignoreCase = true)) return false
        val labels = state.nodes.map { nodeLabel(it) }
        return labels.any { it.contains("选择专区", ignoreCase = true) } &&
            state.nodes.any { it.resourceId.orEmpty().contains("design_bottom_sheet", ignoreCase = true) }
    }

    private fun isHupuPackage(appPackage: String): Boolean {
        return appPackage.contains("hupu", ignoreCase = true)
    }

    private fun aliasesWantPostEntry(aliases: List<String>): Boolean {
        val text = aliases.joinToString(" ").lowercase(Locale.CHINA)
        return listOf("\u53d1\u5e16", "\u53d1\u5e03", "\u5199\u5e16", "\u521b\u4f5c", "post", "publish", "+")
            .any { text.contains(it.lowercase(Locale.CHINA)) }
    }

    private fun isHupuUnsafeEntrySweepSurface(state: UiStateSample): Boolean {
        if (isHupuCreateEntrySheet(state) || isHupuSectionRequiredSurface(state)) return false
        if (isHupuHomeSurface(state)) return false
        return isTransientSheetForEntrySweep(state) ||
            isHupuPostDetailSurface(state) ||
            isHupuSearchSurface(state) ||
            isHupuChannelManagerSurface(state)
    }

    private fun hupuSurfaceKind(state: UiStateSample): String {
        return when {
            isHupuSectionRequiredSurface(state) -> "section_required"
            isHupuCreateEntrySheet(state) -> "create_entry_sheet"
            isTransientSheetForEntrySweep(state) -> "share_or_bottom_sheet"
            isHupuPostDetailSurface(state) -> "post_detail"
            isHupuChannelManagerSurface(state) -> "channel_manager"
            isHupuHomeSurface(state) -> "home"
            isHupuSearchSurface(state) -> "search"
            isHupuPostEditorSurface(state) -> "post_editor"
            state.nodes.any { it.editable } -> "editor_or_input"
            else -> "hupu_other"
        }
    }

    private fun isHupuHomeSurface(state: UiStateSample): Boolean {
        if (!state.appPackage.orEmpty().contains("hupu", ignoreCase = true)) return false
        if (isTransientSheetForEntrySweep(state) || isHupuPostDetailSurface(state) || isHupuChannelManagerSurface(state)) return false
        val labels = state.nodes.map { nodeLabel(it) }
        val homeHits = listOf(
            "\u5927\u5bb6\u90fd\u5728\u641c",
            "\u641c\u7d22",
            "\u70ed\u699c",
            "\u64ad\u5ba2",
            "\u63a8\u8350",
            "CS2",
            "NBA",
            "\u56fd\u9645\u8db3\u7403",
            "\u82f1\u96c4\u8054\u76df",
            "\u7efc\u5408\u4f53\u80b2",
            "\u4e2d\u56fd\u7bee\u7403"
        ).count { marker -> labels.any { label -> label.contains(marker, ignoreCase = true) } }
        return homeHits >= 3
    }

    private fun isHupuPostDetailSurface(state: UiStateSample): Boolean {
        val labels = state.nodes.map { nodeLabel(it) }
        val homeChromeHits = listOf("\u9996\u9875", "\u4e13\u533a", "\u8bc4\u5206", "\u6211\u7684").count { marker ->
            labels.any { label -> label.contains(marker, ignoreCase = true) }
        }
        val hasHomeShell = labels.any { label ->
            label.contains("cl_home_root", ignoreCase = true) ||
                label.contains("vp2_main", ignoreCase = true) ||
                label.contains("cl_search", ignoreCase = true) ||
                label.contains("tv_search", ignoreCase = true) ||
                label.contains("\u5927\u5bb6\u90fd\u5728\u641c", ignoreCase = true)
        }
        if (hasHomeShell && homeChromeHits >= 3) return false
        if (labels.any { it.contains("\u5e16\u5b50\u8be6\u60c5\u9875", ignoreCase = true) }) return true
        val hasReplyInput = labels.any { label ->
            label.contains("\u6211\u6765\u8bc4\u8bba", ignoreCase = true) ||
                label.contains("\u8bf4\u70b9\u4ec0\u4e48", ignoreCase = true) ||
                label.contains("\u5199\u8bc4\u8bba", ignoreCase = true)
        }
        val bottomActionHits = listOf("\u8bc4\u8bba", "\u5206\u4eab", "\u56de\u590d", "\u70b9\u8d5e", "\u8d5e", "\u6536\u85cf").count { marker ->
            labels.any { label -> label.contains(marker, ignoreCase = true) }
        }
        if (hasReplyInput && bottomActionHits >= 2) return true
        val detailHits = listOf("\u6211\u6765\u8bc4\u8bba", "\u56de\u590d", "\u8bc4\u8bba", "\u5206\u4eab", "\u697c\u4e3b").count { marker ->
            labels.any { label -> label.contains(marker, ignoreCase = true) }
        }
        val explicitArticleContext = labels.any { label ->
            listOf("\u5e16\u5b50\u8be6\u60c5", "\u53d1\u5e03\u4e8e", "\u53ea\u770b\u697c\u4e3b", "\u697c\u4e3b", "\u6765\u6e90\uff1a\u864e\u6251")
                .any { marker -> label.contains(marker, ignoreCase = true) }
        } || state.nodes.any { node ->
            val resource = node.resourceId.orEmpty()
            resource.contains("post_detail", ignoreCase = true) ||
                resource.contains("detail_page", ignoreCase = true) ||
                resource.contains("reply_list", ignoreCase = true)
        }
        return detailHits >= 3 && explicitArticleContext
    }

    private fun isHupuChannelManagerSurface(state: UiStateSample): Boolean {
        val labels = state.nodes.map { nodeLabel(it) }
        val hits = listOf("\u5168\u90e8\u9891\u9053", "\u6211\u7684\u9891\u9053", "\u70b9\u51fb\u8fdb\u5165\u9891\u9053", "\u63a8\u8350\u9891\u9053").count { marker ->
            labels.any { label -> label.contains(marker, ignoreCase = true) }
        }
        return hits >= 2
    }

    private fun isHupuSearchSurface(state: UiStateSample): Boolean {
        if (!state.appPackage.orEmpty().contains("hupu", ignoreCase = true)) return false
        if (isHupuHomeSurface(state)) return false
        val labels = state.nodes.map { nodeLabel(it) }
        val hasSearchInput = labels.any { label ->
            label.contains("et_search", ignoreCase = true) ||
                label.contains("\u641c\u7d22", ignoreCase = true)
        } && state.nodes.any { it.editable }
        val hasSearchTabs = listOf("\u7efc\u5408", "\u5e16\u5b50", "\u7528\u6237").count { marker ->
            labels.any { label -> label.contains(marker, ignoreCase = true) }
        } >= 2
        return hasSearchInput || hasSearchTabs
    }

    private fun isHupuPostEditorSurface(state: UiStateSample): Boolean {
        if (!state.appPackage.orEmpty().contains("hupu", ignoreCase = true)) return false
        val labels = state.nodes.map { nodeLabel(it) }
        val editorHits = listOf(
            "\u6807\u9898",
            "\u6b63\u6587",
            "\u8bf4\u70b9\u4ec0\u4e48",
            "\u5206\u4eab\u4f60\u7684\u5fc3\u60c5",
            "\u8865\u5145\u8bf4\u660e",
            "\u6dfb\u52a0\u4e13\u533a",
            "\u6dfb\u52a0\u56fe\u7247\u6216\u89c6\u9891",
            "\u8349\u7a3f\u7bb1",
            "\u5199\u5e16",
            "\u64b0\u5199",
            "\u53d1\u5e03",
            "\u53d1\u8868"
        ).count { marker -> labels.any { label -> label.contains(marker, ignoreCase = true) } }
        val hasWebEditorRoot = labels.any { label ->
            label.contains("\u864e\u6251APP\u7f16\u8f91\u5668", ignoreCase = true) ||
                label.contains("editor-root", ignoreCase = true) ||
                label.contains("hupu-app-editor", ignoreCase = true) ||
                label.contains("editor-footer", ignoreCase = true)
        }
        val searchHits = listOf("\u641c\u7d22", "search", "et_search", "iv_search").count { marker ->
            labels.any { label -> label.contains(marker, ignoreCase = true) }
        }
        return ((hasWebEditorRoot && state.nodes.any { it.editable }) || editorHits >= 2) && searchHits < 2
    }

    private fun findEntryCandidate(state: UiStateSample?, aliases: List<String>): EntryCandidate? {
        if (state == null) return null
        if (isTransientSheetForEntrySweep(state) && !isHupuCreateEntrySheet(state)) return null
        if (state.appPackage.orEmpty().contains("hupu", ignoreCase = true) &&
            aliasesWantPostEntry(aliases) &&
            !isHupuCreateEntrySheet(state)
        ) {
            return null
        }
        val editorOpen = state.nodes.any { node -> node.editable } ||
            state.nodes.map { nodeLabel(it) }.any { label ->
                listOf(
                    "\u6807\u9898",
                    "\u6b63\u6587",
                    "\u8bf4\u70b9\u4ec0\u4e48",
                    "\u6dfb\u52a0\u6587\u5b57",
                    "\u53d1\u5e03\u7b14\u8bb0",
                    "\u4fdd\u5b58\u8349\u7a3f"
                ).any { marker -> label.contains(marker, ignoreCase = true) }
            }
        return state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    node.bounds.width() > 8 &&
                    node.bounds.height() > 8 &&
                    (node.clickable || !node.text.isNullOrBlank() || !node.contentDesc.isNullOrBlank() || !node.resourceId.isNullOrBlank())
            }
            .mapNotNull { node ->
                val label = nodeLabel(node)
                val matchedAlias = aliases.firstOrNull { alias -> entryAliasMatches(label, node.resourceId.orEmpty(), alias) }
                    ?: return@mapNotNull null
                if (isForbiddenEntryCandidate(label, matchedAlias, editorOpen, state)) return@mapNotNull null
                EntryCandidate(node, label.ifBlank { node.resourceId.orEmpty() }, matchedAlias, entryCandidateScore(node, label, matchedAlias, state))
            }
            .sortedWith(compareByDescending<EntryCandidate> { it.score }
                .thenBy { it.node.bounds.centerY() }
                .thenBy { it.node.bounds.centerX() })
            .firstOrNull()
    }

    private fun entryAliasMatches(label: String, resourceId: String, alias: String): Boolean {
        val cleanAlias = alias.trim()
        if (cleanAlias.isBlank()) return false
        val haystack = "$label $resourceId".lowercase(Locale.CHINA)
        val lowerAlias = cleanAlias.lowercase(Locale.CHINA)
        if (cleanAlias == "+") {
            return label.trim() == "+" ||
                haystack.contains("plus") ||
                haystack.contains("create") ||
                haystack.contains("compose") ||
                haystack.contains("publish") ||
                haystack.contains("post")
        }
        return haystack.contains(lowerAlias)
    }

    private fun isForbiddenEntryCandidate(
        label: String,
        matchedAlias: String,
        editorOpen: Boolean,
        state: UiStateSample
    ): Boolean {
        val lower = label.lowercase(Locale.CHINA)
        val forbidden = listOf(
            "\u5206\u4eab",
            "\u4e3e\u62a5",
            "\u6536\u85cf",
            "\u590d\u5236\u94fe\u63a5",
            "\u5fae\u4fe1\u597d\u53cb",
            "\u670b\u53cb\u5708",
            "qq",
            "\u53d6\u6d88",
            "\u4e0d\u63a8\u8350",
            "\u9605\u8bfb\u8bbe\u7f6e"
        )
        if (forbidden.any { lower.contains(it.lowercase(Locale.CHINA)) }) return true
        val publishLike = listOf("\u53d1\u5e03", "\u63d0\u4ea4", "\u53d1\u8868", "publish", "submit")
            .any { lower.contains(it.lowercase(Locale.CHINA)) || matchedAlias.contains(it, ignoreCase = true) }
        if (editorOpen && publishLike) return true
        val veryTop = state.screenHeight > 0 && label.isNotBlank() && state.nodes.any { it.editable } &&
            publishLike
        if (veryTop) return true
        return false
    }

    private fun entryCandidateScore(node: NodeSample, label: String, matchedAlias: String, state: UiStateSample): Int {
        val lower = label.lowercase(Locale.CHINA)
        val alias = matchedAlias.lowercase(Locale.CHINA)
        var score = 0
        if (label == matchedAlias || node.text == matchedAlias || node.contentDesc == matchedAlias) score += 90
        if (lower.contains(alias)) score += 65
        if (node.clickable) score += 20
        if (node.bounds.centerY() > state.screenHeight * 0.65f) score += 12
        if (lower.contains("create") || lower.contains("compose") || lower.contains("publish") || lower.contains("post")) score += 14
        if (lower.contains("\u521b\u4f5c") || lower.contains("\u53d1\u5e16") || lower.contains("\u5199")) score += 16
        if (node.bounds.width() > state.screenWidth * 0.52f && label.length <= 2) score -= 18
        return score
    }

    private fun bottomTabCandidates(
        state: UiStateSample?,
        maxTabs: Int,
        action: JSONObject,
        appPackage: String
    ): List<BottomTabCandidate> {
        if (state == null || state.screenWidth <= 0 || state.screenHeight <= 0) return emptyList()
        if (isHupuPackage(appPackage) && isHupuUnsafeEntrySweepSurface(state)) return emptyList()
        val effectiveWidth = state.effectiveScreenWidth()
        val effectiveHeight = state.effectiveScreenHeight()
        val hupuPostSweep = isHupuPackage(appPackage) && aliasesWantPostEntry(entryAliases(action))
        if (hupuPostSweep && !isHupuHomeSurface(state)) return emptyList()
        if (hupuPostSweep && isHupuHomeSurface(state)) {
            val hupuTabs = hupuBottomTabCandidates(state, maxTabs, effectiveWidth, effectiveHeight)
            if (hupuTabs.isNotEmpty()) return hupuTabs
        }
        if (appPackage == SkillJsonRuleGenerator.WECHAT_PACKAGE && action.optBoolean("coordinateFallbackTabs", true)) {
            val y = action.optDouble("bottomTapY", 0.965).toFloat().coerceIn(0.88f, 0.985f)
            return listOf(
                BottomTabCandidate("\u5fae\u4fe1", 0.125f, y, score = 80),
                BottomTabCandidate("\u901a\u8baf\u5f55", 0.375f, y, score = 80),
                BottomTabCandidate("\u53d1\u73b0", 0.625f, y, score = 120),
                BottomTabCandidate("\u6211", 0.875f, y, score = 80)
            ).take(maxTabs.coerceIn(1, 4))
        }
        val top = (effectiveHeight * action.optDouble("bottomBandTop", 0.68).toFloat()).toInt()
        val bottom = (effectiveHeight * 0.995f).toInt()
        val forbidden = listOf(
            "\u53d6\u6d88",
            "\u5206\u4eab",
            "\u4e3e\u62a5",
            "\u6536\u85cf",
            "\u590d\u5236",
            "\u5fae\u4fe1",
            "qq",
            "\u4e0d\u63a8\u8350",
            "\u9605\u8bfb\u8bbe\u7f6e"
        )
        val fromNodes = state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    node.bounds.centerY() in top..bottom &&
                    node.bounds.width() > 8 &&
                    node.bounds.height() > 8 &&
                    (node.clickable || !node.text.isNullOrBlank() || !node.contentDesc.isNullOrBlank())
            }
            .map { node ->
                val label = nodeLabel(node).ifBlank { "tab@${node.bounds.centerX()}" }
                BottomTabCandidate(
                    label = label.take(48),
                    x = node.bounds.centerX().toFloat() / effectiveWidth.coerceAtLeast(1),
                    y = node.bounds.centerY().toFloat() / effectiveHeight.coerceAtLeast(1),
                    score = bottomTabScore(node, label, state)
                )
            }
            .filterNot { tab -> forbidden.any { tab.label.contains(it, ignoreCase = true) } }
            .sortedWith(compareByDescending<BottomTabCandidate> { it.score }.thenBy { it.x })
            .fold(mutableListOf<BottomTabCandidate>()) { acc, tab ->
                if (acc.none { kotlin.math.abs(it.x - tab.x) < 0.085f }) acc += tab
                acc
            }
            .sortedBy { it.x }
            .take(maxTabs)
            .toMutableList()
        if (action.optBoolean("coordinateFallbackTabs", true)) {
            val y = action.optDouble("bottomTapY", 0.925).toFloat().coerceIn(0.78f, 0.98f)
            val count = maxTabs.coerceAtLeast(3)
            for (index in 0 until count) {
                val x = ((index + 1).toFloat() / (count + 1).toFloat()).coerceIn(0.08f, 0.92f)
                if (fromNodes.none { kotlin.math.abs(it.x - x) < 0.075f }) {
                    fromNodes += BottomTabCandidate("fallback_tab_${index + 1}", x, y, score = 1)
                }
            }
        }
        return fromNodes.sortedBy { it.x }.take(maxTabs)
    }

    private fun hupuBottomTabCandidates(
        state: UiStateSample,
        maxTabs: Int,
        effectiveWidth: Int,
        effectiveHeight: Int
    ): List<BottomTabCandidate> {
        val top = (effectiveHeight * 0.86f).toInt()
        val bottom = (effectiveHeight * 0.995f).toInt()
        val out = mutableListOf<BottomTabCandidate>()
        state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    node.clickable &&
                    node.bounds.centerY() in top..bottom &&
                    node.bounds.centerX() in (effectiveWidth * 0.36f).toInt()..(effectiveWidth * 0.64f).toInt() &&
                    node.bounds.width() in (effectiveWidth * 0.08f).toInt()..(effectiveWidth * 0.34f).toInt()
            }
            .sortedBy { node -> kotlin.math.abs(node.bounds.centerX() - effectiveWidth / 2) }
            .firstOrNull()
            ?.let { node ->
                out += BottomTabCandidate(
                    label = "\u864e\u6251\u4e2d\u95f4\u521b\u4f5c\u5165\u53e3",
                    x = node.bounds.centerX().toFloat() / effectiveWidth.coerceAtLeast(1),
                    y = (node.bounds.centerY().toFloat() / effectiveHeight.coerceAtLeast(1)).coerceAtMost(0.925f),
                    score = 120
                )
            }
        if (out.none { kotlin.math.abs(it.x - 0.50f) < 0.12f }) {
            out += BottomTabCandidate(
                label = "\u864e\u6251\u4e2d\u95f4\u521b\u4f5c\u5165\u53e3_\u9996\u9875\u5750\u6807\u5019\u9009",
                x = 0.50f,
                y = 0.925f,
                score = 110
            )
        }

        state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    node.bounds.centerY() in top..bottom &&
                    node.resourceId.orEmpty().endsWith("tv_tab", ignoreCase = true)
            }
            .mapNotNull { node ->
                val label = nodeLabel(node).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                BottomTabCandidate(
                    label = label.take(24),
                    x = node.bounds.centerX().toFloat() / effectiveWidth.coerceAtLeast(1),
                    y = node.bounds.centerY().toFloat() / effectiveHeight.coerceAtLeast(1),
                    score = bottomTabScore(node, label, state)
                )
            }
            .sortedBy { tab -> tab.x }
            .forEach { tab ->
                if (out.none { kotlin.math.abs(it.x - tab.x) < 0.075f }) out += tab
            }

        return out.take(maxTabs.coerceAtLeast(1))
    }

    private fun bottomTabScore(node: NodeSample, label: String, state: UiStateSample): Int {
        val lower = label.lowercase(Locale.CHINA)
        var score = 0
        if (node.clickable) score += 20
        if (!node.text.isNullOrBlank() || !node.contentDesc.isNullOrBlank()) score += 12
        if (node.selected) score += 4
        if (node.bounds.centerY() > state.screenHeight * 0.82f) score += 10
        if (node.bounds.width() < state.screenWidth * 0.34f) score += 8
        if (listOf("\u9996\u9875", "\u63a8\u8350", "\u6d88\u606f", "\u6211", "\u793e\u533a", "\u53d1\u73b0", "\u521b\u4f5c", "\u53d1\u5e03", "+").any { lower.contains(it.lowercase(Locale.CHINA)) }) {
            score += 20
        }
        return score
    }

    private fun clickEntryCandidate(
        entry: EntryCandidate,
        state: UiStateSample?,
        aliases: List<String>,
        events: JSONArray,
        phase: String
    ): SkillJsonRunResult {
        val ui = state ?: inspector.currentState()
            ?: return failed("FAILED_PRECONDITION", "No UiState when clicking entry candidate")
        val effectiveWidth = ui.effectiveScreenWidth()
        val effectiveHeight = ui.effectiveScreenHeight()
        val x = entry.node.bounds.centerX().toFloat() / effectiveWidth.coerceAtLeast(1)
        val y = entry.node.bounds.centerY().toFloat() / effectiveHeight.coerceAtLeast(1)
        val outcome = actions.tapNormalized(NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)), effectiveWidth, effectiveHeight)
        events.put(JSONObject()
            .put("phase", "click_entry")
            .put("sourcePhase", phase)
            .put("label", entry.label)
            .put("matchedAlias", entry.matchedAlias)
            .put("score", entry.score)
            .put("x", x)
            .put("y", y)
            .put("success", outcome.success)
            .put("message", outcome.reason))
        sleep(900L)
        return if (outcome.success) {
            val after = inspector.currentState()
            if (after != null && after.appPackage.orEmpty().contains("hupu", ignoreCase = true) && aliasesWantPostEntry(aliases)) {
                if (!entryDestinationReached(after, aliases)) {
                    return failed(
                        "FAILED_ENTRY_NOT_REACHED",
                        "Clicked '${entry.label}', but Hupu did not reach post entry surface; surface=${hupuSurfaceKind(after)}; events=${events.toString().take(1600)}"
                    )
                }
            }
            SkillJsonRunResult(
                status = "SUCCESS_MEDIUM_CONFIDENCE",
                message = "Found and clicked entry '${entry.label}' by alias '${entry.matchedAlias}' during $phase; aliases=${aliases.joinToString("/")}; events=${events.toString().take(1600)}",
                usedCoordinate = true
            )
        } else {
            failed("FAILED_ELEMENT_NOT_FOUND", "Entry candidate found but click failed: ${outcome.reason}; events=${events.toString().take(1200)}")
        }
    }

    private fun executeMacro(
        skill: JSONObject,
        params: JSONObject,
        allowSideEffect: Boolean,
        visited: MutableSet<String>
    ): SkillJsonRunResult {
        val atoms = skill.optJSONArray("atoms") ?: return failed("FAILED_PRECONDITION", "Macro skill missing atoms")
        var confidence = "SUCCESS"
        var usedCoordinate = false
        var reactiveDismissed = 0
        val macroPackage = skill.optString("appPackage")
        val atomTrace = JSONArray()
        saveCurrentSnapshot(
            source = "before_macro",
            note = skill.optString("id"),
            relatedSkillId = skill.optString("id"),
            relatedActionType = "macro_skill"
        )
        for (i in 0 until atoms.length()) {
            val atom = atoms.optJSONObject(i) ?: continue
            if (atom.optString("type") != "UseSkill") {
                return failed("FAILED_PRECONDITION", "Macro atom $i is not UseSkill")
            }
            val skillId = atom.optString("skillId")
            val child = loadReferencedSkill(skillId)
                ?: return failed("FAILED_DEPENDENCY_MISSING", "Referenced skill not found: $skillId")
            val childParams = bindParams(atom.optJSONObject("params"), params)
            val childSkipReactive = child.optJSONObject("action")?.optBoolean("skipReactiveOverlays", false) == true
            saveCurrentSnapshot(
                source = "before_macro_atom",
                note = "index=$i; skillId=$skillId",
                relatedSkillId = skillId,
                relatedActionType = child.optJSONObject("action")?.optString("type") ?: "UseSkill"
            )
            if (!childSkipReactive) {
                reactiveDismissed += runReactiveOverlays(macroPackage, "before_macro_atom:$i:$skillId", maxPasses = 2).dismissedCount
            }
            var result = executeInternal(child, childParams, allowSideEffect, visited.toMutableSet())
            if (result.status.startsWith("FAILED") && canRetryAfterReactive(result.status)) {
                val recovery = runReactiveOverlays(macroPackage, "retry_after_failure:$i:$skillId", maxPasses = 3)
                reactiveDismissed += recovery.dismissedCount
                if (recovery.dismissedCount > 0) {
                    result = executeInternal(child, childParams, allowSideEffect, visited.toMutableSet())
                }
            }
            if (result.status.startsWith("FAILED")) {
                val failedAtom = JSONObject()
                    .put("index", i)
                    .put("skillId", skillId)
                    .put("actionType", child.optJSONObject("action")?.optString("type") ?: "UseSkill")
                    .put("status", result.status)
                    .put("message", result.message)
                    .put("usedCoordinate", result.usedCoordinate)
                    .put("details", result.details ?: JSONObject.NULL)
                atomTrace.put(failedAtom)
                saveCurrentSnapshot(
                    source = "macro_atom_failed",
                    note = "index=$i; skillId=$skillId; status=${result.status}; ${result.message.take(180)}",
                    relatedSkillId = skillId,
                    relatedActionType = child.optJSONObject("action")?.optString("type") ?: "UseSkill"
                )
                return result.copy(
                    details = JSONObject()
                        .put("macroSkillId", skill.optString("id"))
                        .put("failedAtom", failedAtom)
                        .put("atomTrace", atomTrace)
                        .put("reactiveDismissed", reactiveDismissed)
                )
            }
            confidence = lowerStatus(confidence, result.status)
            usedCoordinate = usedCoordinate || result.usedCoordinate
            if (!childSkipReactive) {
                reactiveDismissed += runReactiveOverlays(macroPackage, "after_macro_atom:$i:$skillId", maxPasses = 2).dismissedCount
            }
            atomTrace.put(JSONObject()
                .put("index", i)
                .put("skillId", skillId)
                .put("actionType", child.optJSONObject("action")?.optString("type") ?: "UseSkill")
                .put("status", result.status)
                .put("message", result.message)
                .put("usedCoordinate", result.usedCoordinate)
                .put("details", result.details ?: JSONObject.NULL))
            saveCurrentSnapshot(
                source = "after_macro_atom",
                note = "index=$i; skillId=$skillId; status=${result.status}",
                relatedSkillId = skillId,
                relatedActionType = child.optJSONObject("action")?.optString("type") ?: "UseSkill"
            )
        }
        saveCurrentSnapshot(
            source = "after_macro",
            note = "${skill.optString("id")}; confidence=$confidence; reactiveDismissed=$reactiveDismissed",
            relatedSkillId = skill.optString("id"),
            relatedActionType = "macro_skill"
        )
        return verifyAfterAction(
            skill,
            params,
            SkillJsonRunResult(
                confidence,
                "Macro atoms completed; reactiveDismissed=$reactiveDismissed",
                usedCoordinate = usedCoordinate,
                details = JSONObject()
                    .put("macroSkillId", skill.optString("id"))
                    .put("atomTrace", atomTrace)
                    .put("reactiveDismissed", reactiveDismissed)
            )
        )
    }

    private fun executeDependencies(
        skill: JSONObject,
        params: JSONObject,
        allowSideEffect: Boolean,
        visited: MutableSet<String>
    ): SkillJsonRunResult? {
        val dependsOn = skill.optJSONArray("dependsOn") ?: return null
        for (i in 0 until dependsOn.length()) {
            val dependencyId = dependsOn.optString(i)
            if (dependencyId.isBlank()) continue
            val dependency = loadReferencedSkill(dependencyId)
                ?: return failed("FAILED_DEPENDENCY_MISSING", "Dependency skill not found: $dependencyId")
            val result = executeInternal(dependency, params, allowSideEffect, visited)
            if (result.status.startsWith("FAILED")) return result
        }
        return null
    }

    private fun loadReferencedSkill(skillId: String): JSONObject? {
        return if (runtimeApprovedOnly) {
            store.loadRuntimeApprovedSkillById(skillId)
                ?.second
                ?.takeIf { it.optString("status") == "APPROVED" }
        } else {
            store.loadSkillById(skillId)?.second
        }
    }

    private fun resolveElement(skill: JSONObject): SkillJsonRunResult {
        val resolved = resolveTarget(skill.optJSONObject("target"))
        return when {
            resolved.nodeResolved -> SkillJsonRunResult("SUCCESS", "Element resolved by accessibility node")
            resolved.coordinateResolved -> SkillJsonRunResult(
                "SUCCESS_LOW_CONFIDENCE",
                "Element resolved by coordinate fallback",
                usedCoordinate = true
            )
            else -> failed("FAILED_ELEMENT_NOT_FOUND", "Element not found and no coordinate fallback")
        }
    }

    private fun clickElement(skill: JSONObject): SkillJsonRunResult {
        val target = skill.optJSONObject("target")
        val resolved = resolveTarget(target)
        val matchers = selectorParser.matchersFromTarget(target)
        if (resolved.nodeResolved && matchers.isNotEmpty()) {
            val outcome = actions.clickByMatcher(matchers)
            if (outcome.success) {
                return verifyAfterAction(skill, JSONObject(), SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", outcome.reason))
            }
        }
        val tapped = tapCoordinate(target)
        return if (tapped.success) {
            verifyAfterAction(
                skill,
                JSONObject(),
                SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", tapped.reason, usedCoordinate = true)
            )
        } else {
            failed("FAILED_ELEMENT_NOT_FOUND", tapped.reason)
        }
    }

    private fun clickVisibleText(skill: JSONObject, params: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: return failed("FAILED_PRECONDITION", "Missing action")
        val text = action.optString("text").ifBlank {
            paramValue(action.optString("textParam", "text"), params)
        }
        val candidates = (listOf(text) + action.optJSONArray("textAliases").asStringList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (candidates.isEmpty()) return failed("FAILED_PRECONDITION", "ClickVisibleText missing text")
        if (action.optBoolean("skipIfHupuPostEditorVisible", false) &&
            inspector.currentState()?.let { isHupuPostEditorSurface(it) } == true
        ) {
            return SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", "Hupu post editor already visible; skipped click '$text'")
        }
        if (action.optBoolean("skipWhenUiUnavailable", false) && inspector.currentState() == null) {
            return SkillJsonRunResult(
                "SUCCESS_LOW_CONFIDENCE",
                "UiState unavailable; skipped optional visible-text click '$text'",
                usedCoordinate = true
            )
        }
        val failures = mutableListOf<String>()
        for (candidate in candidates) {
            val outcome = actions.clickByVisibleText(candidate)
            if (outcome.success) {
                return verifyAfterAction(skill, params, SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", outcome.reason))
            }
            failures += "$candidate=${outcome.reason}"
        }
        val fallback = tapActionCoordinate(action)
        return if (fallback.success) {
            verifyAfterAction(
                skill,
                params,
                SkillJsonRunResult(
                    "SUCCESS_LOW_CONFIDENCE",
                    "${failures.joinToString("; ")}; coordinate fallback used",
                    usedCoordinate = true
                )
            )
        } else {
            failed("FAILED_ELEMENT_NOT_FOUND", "${failures.joinToString("; ")}; ${fallback.reason}")
        }
    }

    private fun clickSelector(skill: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: return failed("FAILED_PRECONDITION", "Missing action")
        val selector = action.optString("selector")
        val matchers = if (selector.isNotBlank()) {
            listOfNotNull(selectorParser.matcherFromSelector(selector))
        } else {
            selectorParser.matchersFromTarget(skill.optJSONObject("target"))
        }
        if (matchers.isEmpty()) return failed("FAILED_PRECONDITION", "ClickSelector has no parsable selector")
        val outcome = actions.clickByMatcher(matchers)
        return if (outcome.success) {
            verifyAfterAction(skill, JSONObject(), SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", outcome.reason))
        } else {
            failed("FAILED_ELEMENT_NOT_FOUND", outcome.reason)
        }
    }

    private fun clickCoordinate(skill: JSONObject): SkillJsonRunResult {
        val state = inspector.currentState()
        val action = skill.optJSONObject("action") ?: return failed("FAILED_PRECONDITION", "Missing action")
        val rawX = action.optDouble("x", action.optDouble("normalizedX", -1.0)).toFloat()
        val rawY = action.optDouble("y", action.optDouble("normalizedY", -1.0)).toFloat()
        if (rawX !in 0f..1f || rawY !in 0f..1f) return failed("FAILED_PRECONDITION", "ClickCoordinate requires normalized x/y")
        val x = rawX.coerceIn(0f, 1f)
        val y = rawY.coerceIn(0f, 1f)
        val width = displayScreenWidth()
        val height = displayScreenHeight()
        val outcome = actions.tapNormalized(NormalizedPoint(x, y), width, height)
        return if (outcome.success) {
            verifyAfterAction(skill, JSONObject(), SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", outcome.reason, usedCoordinate = true))
        } else {
            failed("FAILED", outcome.reason)
        }
    }

    private fun longPressCoordinate(skill: JSONObject): SkillJsonRunResult {
        val state = inspector.currentState()
        val action = skill.optJSONObject("action") ?: return failed("FAILED_PRECONDITION", "Missing action")
        val x = action.optDouble("x", action.optDouble("normalizedX", -1.0)).toFloat()
        val y = action.optDouble("y", action.optDouble("normalizedY", -1.0)).toFloat()
        if (x !in 0f..1f || y !in 0f..1f) {
            return failed("FAILED_PRECONDITION", "LongPressCoordinate requires normalized x/y")
        }
        val width = displayScreenWidth()
        val height = displayScreenHeight()
        val outcome = actions.longPressNormalized(
            NormalizedPoint(x, y),
            width,
            height,
            action.optLong("durationMs", 850L)
        )
        return if (outcome.success) {
            verifyAfterAction(skill, JSONObject(), SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", outcome.reason, usedCoordinate = true))
        } else {
            failed("FAILED", outcome.reason)
        }
    }

    private fun typeText(skill: JSONObject, params: JSONObject): SkillJsonRunResult {
        val target = skill.optJSONObject("target")
        val action = skill.optJSONObject("action") ?: return failed("FAILED_PRECONDITION", "Missing action")
        val text = paramValue(action.optString("textParam", "text"), params)
        if (text.isBlank()) return failed("FAILED_PRECONDITION", "Text param is blank")

        val matchers = selectorParser.matchersFromTarget(target)
        if (matchers.isNotEmpty()) {
            val nodeOutcome = actions.setTextByMatcher(matchers, text)
            if (nodeOutcome.success) {
                return verifyAfterAction(skill, params, SkillJsonRunResult("SUCCESS", nodeOutcome.reason))
            }
            actions.clickByMatcher(matchers)
        }

        val focus = tapCoordinate(target)
        if (!focus.success && matchers.isEmpty()) {
            return failed("FAILED_ELEMENT_NOT_FOUND", focus.reason)
        }
        val imeSelect = actions.ensureMacroPilotImeSelected()
        if (imeSelect.success) {
            waitUntil(800L) { actions.isMacroPilotImeReady() }
        }
        val imeOutcome = if (action.optBoolean("replace", false)) {
            actions.replaceTextWithMacroPilotIme(text)
        } else {
            actions.commitTextWithMacroPilotIme(text)
        }
        return if (imeOutcome.success) {
            verifyAfterAction(
                skill,
                params,
                SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", imeOutcome.reason, usedCoordinate = true)
            )
        } else {
            failed("FAILED_INPUT_CHANNEL_MISSING", imeOutcome.reason)
        }
    }

    private fun typeIntoFirstInput(skill: JSONObject, params: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: return failed("FAILED_PRECONDITION", "Missing action")
        val text = action.optString("text").ifBlank {
            paramValue(action.optString("textParam", "text"), params)
        }
        if (text.isBlank()) return failed("FAILED_PRECONDITION", "Text param is blank")
        val nodeOutcome = actions.setTextByMatcher(
            listOf(ElementMatcher(editable = true, enabled = true)),
            text
        )
        if (nodeOutcome.success) {
            return verifyAfterAction(skill, params, SkillJsonRunResult("SUCCESS", nodeOutcome.reason))
        }
        val imeSelect = actions.ensureMacroPilotImeSelected()
        if (imeSelect.success) {
            waitUntil(800L) { actions.isMacroPilotImeReady() }
        }
        val imeOutcome = if (action.optBoolean("replace", false)) {
            actions.replaceTextWithMacroPilotIme(text)
        } else {
            actions.commitTextWithMacroPilotIme(text)
        }
        return if (imeOutcome.success) {
            verifyAfterAction(skill, params, SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", imeOutcome.reason, usedCoordinate = false))
        } else {
            failed("FAILED_INPUT_CHANNEL_MISSING", "${nodeOutcome.reason}; ${imeSelect.reason}; ${imeOutcome.reason}")
        }
    }

    private fun typeIntoFocusedInput(skill: JSONObject, params: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: return failed("FAILED_PRECONDITION", "Missing action")
        val text = action.optString("text").ifBlank {
            paramValue(action.optString("textParam", "text"), params)
        }
        if (text.isBlank()) return failed("FAILED_PRECONDITION", "Text param is blank")
        val focusedOutcome = actions.setTextByMatcher(
            listOf(ElementMatcher(editable = true, enabled = true, focused = true)),
            text
        )
        if (focusedOutcome.success) {
            return verifyAfterAction(skill, params, SkillJsonRunResult("SUCCESS", focusedOutcome.reason))
        }
        val imeSelect = actions.ensureMacroPilotImeSelected()
        if (imeSelect.success) {
            waitUntil(800L) { actions.isMacroPilotImeReady() }
        }
        val imeOutcome = actions.commitTextWithMacroPilotIme(text)
        return if (imeOutcome.success) {
            verifyAfterAction(skill, params, SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", imeOutcome.reason, usedCoordinate = false))
        } else {
            failed("FAILED_INPUT_CHANNEL_MISSING", "${focusedOutcome.reason}; ${imeSelect.reason}; ${imeOutcome.reason}")
        }
    }

    private fun fillHupuPostEditor(skill: JSONObject, params: JSONObject): SkillJsonRunResult {
        val action = skill.optJSONObject("action") ?: return failed("FAILED_PRECONDITION", "Missing action")
        val title = action.optString("title").ifBlank {
            paramValue(action.optString("titleParam", "title"), params)
        }.trim()
        val body = action.optString("body").ifBlank {
            paramValue(action.optString("bodyParam", "body"), params)
        }.trim()
        if (title.isBlank() && body.isBlank()) return failed("FAILED_PRECONDITION", "Hupu post title/body are blank")
        val state = inspector.currentState() ?: return failed("FAILED_PRECONDITION", "No UiState for Hupu editor fill")
        if (!isHupuPostEditorSurface(state)) {
            return failed("FAILED_PRECONDITION", "Hupu post editor is not visible; surface=${hupuSurfaceKind(state)}")
        }

        val finalTitle = title.ifBlank { body.take(40).ifBlank { "\u004d\u0061\u0063\u0072\u006f\u0050\u0069\u006c\u006f\u0074 \u6d4b\u8bd5" } }
        val titleOutcome = actions.setTextByEditableIndex(0, finalTitle)
        if (!titleOutcome.success) {
            return failed("FAILED_INPUT_CHANNEL_MISSING", "Title input failed: ${titleOutcome.reason}")
        }

        val bodyOutcome = if (body.isNotBlank()) {
            sleep(250L)
            actions.setTextByEditableIndex(1, body)
        } else {
            com.macropilot.app.automation.ActionOutcome(true, "Body empty, skipped")
        }
        if (!bodyOutcome.success) {
            return failed("FAILED_INPUT_CHANNEL_MISSING", "Body input failed: ${bodyOutcome.reason}")
        }

        return verifyAfterAction(
            skill,
            params,
            SkillJsonRunResult("SUCCESS", "Hupu editor filled; title=${finalTitle.take(40)}; body=${body.take(40)}")
        )
    }

    private fun openHupuPostEditor(skill: JSONObject): SkillJsonRunResult {
        val state = inspector.currentState() ?: return failed("FAILED_PRECONDITION", "No UiState for Hupu editor open")
        if (isHupuPostEditorSurface(state)) {
            return SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", "Hupu post editor already visible")
        }
        if (!isHupuCreateEntrySheet(state) && !isHupuSectionRequiredSurface(state)) {
            return failed("FAILED_PRECONDITION", "Hupu create entry sheet is not visible; surface=${hupuSurfaceKind(state)}")
        }

        val width = state.effectiveScreenWidth()
        val height = state.effectiveScreenHeight()
        val candidates = state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    node.bounds.width() > width * 0.18f &&
                    node.bounds.height() > 24 &&
                    node.bounds.centerY() in (height * 0.62f).toInt()..(height * 0.93f).toInt() &&
                    (node.clickable || node.bounds.width() > width * 0.48f)
            }
            .map { node ->
                val label = nodeLabel(node)
                val score = buildList {
                    if (label.contains("\u53d1\u5e16", ignoreCase = true)) add(120)
                    if (label.contains("\u53d1\u5e03", ignoreCase = true)) add(110)
                    if (label.contains("帖子", ignoreCase = true)) add(90)
                    if (label.contains("\u89c6\u9891", ignoreCase = true)) add(30)
                    if (label.contains("\u6295\u7968", ignoreCase = true)) add(20)
                    if (node.clickable) add(15)
                    if (node.bounds.width() > width * 0.6f) add(25)
                }.sum()
                node to label to score
            }
            .sortedWith(compareByDescending<Pair<Pair<NodeSample, String>, Int>> { it.second }
                .thenBy { it.first.first.bounds.centerY() }
                .thenBy { it.first.first.bounds.centerX() })
            .map { it.first.first to it.first.second }
            .firstOrNull()

        val outcome = if (candidates != null) {
            val node = candidates.first
            val x = node.bounds.centerX().toFloat() / width.coerceAtLeast(1)
            val y = node.bounds.centerY().toFloat() / height.coerceAtLeast(1)
            actions.tapNormalized(NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)), width, height)
        } else {
            actions.tapNormalized(NormalizedPoint(0.50f, 0.885f), displayScreenWidth(), displayScreenHeight())
        }

        if (!outcome.success) {
            return failed("FAILED_ELEMENT_NOT_FOUND", outcome.reason)
        }
        val afterReady = waitForHupuPostEditor(5_000L)
        val after = inspector.currentState()
        return if (afterReady) {
            SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", "Hupu post editor opened", usedCoordinate = true)
        } else {
            failed("FAILED_PRECONDITION", "Tapped create sheet but editor is still not visible; surface=${after?.let { hupuSurfaceKind(it) } ?: "no_ui_state"}")
        }
    }

    private fun waitForHupuPostEditor(timeoutMs: Long): Boolean {
        return waitUntil(timeoutMs.coerceIn(1_000L, 8_000L)) {
            inspector.currentState()?.let { isHupuPostEditorSurface(it) } == true
        }
    }

    private fun typeAtCoordinate(skill: JSONObject, params: JSONObject): SkillJsonRunResult {
        val state = inspector.currentState()
        val action = skill.optJSONObject("action") ?: return failed("FAILED_PRECONDITION", "Missing action")
        val text = action.optString("text").ifBlank {
            paramValue(action.optString("textParam", "text"), params)
        }
        if (text.isBlank()) return failed("FAILED_PRECONDITION", "Text param is blank")
        val x = action.optDouble("x", action.optDouble("normalizedX", -1.0)).toFloat()
        val y = action.optDouble("y", action.optDouble("normalizedY", -1.0)).toFloat()
        if (x !in 0f..1f || y !in 0f..1f) {
            return failed("FAILED_PRECONDITION", "TypeAtCoordinate requires normalized x/y")
        }
        val width = displayScreenWidth()
        val height = displayScreenHeight()
        val tap = actions.tapNormalized(NormalizedPoint(x, y), width, height)
        if (!tap.success) return failed("FAILED_ELEMENT_NOT_FOUND", tap.reason)
        sleep(action.optLong("focusDelayMs", 550L).coerceIn(100L, 2_000L))
        val imeSelect = actions.ensureMacroPilotImeSelected()
        if (imeSelect.success) {
            waitUntil(1_200L) { actions.isMacroPilotImeReady() }
        }
        val imeOutcome = if (action.optBoolean("replace", false)) {
            actions.replaceTextWithMacroPilotIme(text)
        } else {
            actions.commitTextWithMacroPilotIme(text)
        }
        return if (imeOutcome.success) {
            verifyAfterAction(
                skill,
                params,
                SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", "${tap.reason}; ${imeOutcome.reason}", usedCoordinate = true)
            )
        } else {
            failed("FAILED_INPUT_CHANNEL_MISSING", "${tap.reason}; ${imeSelect.reason}; ${imeOutcome.reason}")
        }
    }

    private fun ensureXiaohongshuDraftEditor(skill: JSONObject): SkillJsonRunResult {
        fun currentIsEditor(): Boolean {
            val state = inspector.currentState() ?: return false
            if (state.appPackage != SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE) return false
            val visible = state.nodes.mapNotNull { node ->
                listOfNotNull(node.text, node.contentDesc).joinToString(" ").takeIf { it.isNotBlank() }
            }
            val markerHits = listOf("写想法", "说点什么", "标题", "正文", "发布笔记", "下一步", "保存草稿", "谁可以看")
                .count { marker -> visible.any { it.contains(marker) } }
            val editWindow = state.windowClassName?.contains("edit", ignoreCase = true) == true
            return editWindow || markerHits >= 2
        }

        if (currentIsEditor()) {
            return SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", "Xiaohongshu editor already visible")
        }
        val packageName = skill.optJSONObject("action")?.optString("packageName")
            ?.ifBlank { SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE }
            ?: SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE
        val open = actions.openApp(packageName)
        if (!open.success) return failed("FAILED_PRECONDITION", open.reason)
        sleep(2_200L)
        if (currentIsEditor()) {
            return SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", "Xiaohongshu editor resumed after open")
        }

        val state = inspector.currentState() ?: return failed("FAILED_PRECONDITION", "No UiState after opening Xiaohongshu")
        val byText = actions.clickByVisibleText("发布")
        if (!byText.success) {
            actions.tapNormalized(NormalizedPoint(0.50f, 0.935f), displayScreenWidth(), displayScreenHeight())
        }
        sleep(1_800L)
        if (currentIsEditor()) {
            return SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", "Xiaohongshu editor opened via create tab", usedCoordinate = !byText.success)
        }

        val modeClick = actions.clickByVisibleText("文字")
        if (!modeClick.success) {
            actions.tapNormalized(NormalizedPoint(0.50f, 0.86f), displayScreenWidth(), displayScreenHeight())
        }
        sleep(1_200L)
        return if (currentIsEditor()) {
            SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", "Xiaohongshu editor opened via text mode", usedCoordinate = !byText.success || !modeClick.success)
        } else {
            failed("FAILED_ELEMENT_NOT_FOUND", "Could not reach Xiaohongshu draft editor")
        }
    }

    private fun verifyAfterAction(
        skill: JSONObject,
        params: JSONObject,
        actionResult: SkillJsonRunResult
    ): SkillJsonRunResult {
        val verify = skill.optJSONObject("verify") ?: return actionResult
        return when (verify.optString("type")) {
            "Always" -> actionResult
            "ElementResolved", "ElementResolvable" -> actionResult
            "InputContainsOrTextChangedEvent" -> verifyInputChanged(verify, params, actionResult)
            "WindowChangedOrNodeStateChanged", "NodeGoneOrWindowChanged" -> verifyWindowOrNodeChanged(verify, actionResult)
            "MessageSentOrInputCleared" -> verifyGoal(skill, params, actionResult)
            "ForegroundPackage" -> verifyForegroundPackage(verify, actionResult)
            "TextVisible" -> verifyTextVisible(verify, params, actionResult)
            "WechatMomentPublishSubmitted" -> verifyWechatMomentPublishSubmitted(verify, actionResult)
            else -> actionResult
        }
    }

    private fun verifyWechatMomentPublishSubmitted(
        verify: JSONObject,
        actionResult: SkillJsonRunResult
    ): SkillJsonRunResult {
        if (!actionResult.status.startsWith("SUCCESS")) return actionResult
        val timeoutMs = verify.optLong("timeoutMs", 4_500L).coerceIn(1_200L, 8_000L)
        val startedAt = System.currentTimeMillis()
        var sawPublishButton: Boolean? = null
        var sawPrivacySelectionPage = false
        var lastRecord: UiSnapshotRecord? = null
        var lastScore = -1
        var lastPrivacyScore = -1
        var lastTopRightGreyScore = -1
        while (System.currentTimeMillis() - startedAt <= timeoutMs) {
            sleep(520L)
            val record = saveCurrentSnapshotRecord(
                source = "wechat_moment_publish_verify",
                note = "checking top-right publish button after tap",
                relatedSkillId = "wechat_moment_publish",
                relatedActionType = "WechatMomentPublishSubmitted",
                forceScreenshot = true,
                hideOverlayForScreenshot = true
            )
            lastRecord = record ?: lastRecord
            val score = record?.screenshotPath?.let { wechatTopRightGreenButtonScore(it) }
            val privacyScore = record?.screenshotPath?.let { wechatPrivacySelectionPageScore(it) }
            val greyScore = record?.screenshotPath?.let { wechatTopRightGreyButtonScore(it) }
            if (score != null) {
                lastScore = score
                if (greyScore != null) {
                    lastTopRightGreyScore = greyScore
                    if (greyScore >= 80) sawPrivacySelectionPage = true
                }
                if (privacyScore != null) {
                    lastPrivacyScore = privacyScore
                    if (privacyScore >= 80) sawPrivacySelectionPage = true
                }
                val visible = score >= 36
                sawPublishButton = visible
                if (!visible && !sawPrivacySelectionPage) {
                    return SkillJsonRunResult(
                        status = "SUCCESS_MEDIUM_CONFIDENCE",
                        message = "WeChat Moments publish submitted: top-right publish button disappeared",
                        usedCoordinate = actionResult.usedCoordinate,
                        details = JSONObject()
                            .put("verifyType", "WechatMomentPublishSubmitted")
                            .put("publishButtonScore", score)
                            .put("privacySelectionScore", privacyScore ?: JSONObject.NULL)
                            .put("topRightGreyButtonScore", greyScore ?: JSONObject.NULL)
                            .put("screenshotPath", record.screenshotPath ?: JSONObject.NULL)
                            .put("snapshotPath", record.path)
                    )
                }
            }
        }
        return if (sawPublishButton == true) {
            failed(
                "FAILED_ACTION_NOT_EFFECTIVE",
                "WeChat Moments publish button is still visible after tap; publish was not submitted"
            ).copy(
                details = JSONObject()
                    .put("verifyType", "WechatMomentPublishSubmitted")
                    .put("publishButtonScore", lastScore)
                    .put("privacySelectionScore", lastPrivacyScore)
                    .put("topRightGreyButtonScore", lastTopRightGreyScore)
                    .put("screenshotPath", lastRecord?.screenshotPath ?: JSONObject.NULL)
                    .put("snapshotPath", lastRecord?.path ?: JSONObject.NULL)
            )
        } else if (sawPrivacySelectionPage) {
            failed(
                "FAILED_ACTION_NOT_EFFECTIVE",
                "WeChat Moments is still on the privacy selection page; publish was not submitted"
            ).copy(
                details = JSONObject()
                    .put("verifyType", "WechatMomentPublishSubmitted")
                    .put("publishButtonScore", lastScore)
                    .put("privacySelectionScore", lastPrivacyScore)
                    .put("topRightGreyButtonScore", lastTopRightGreyScore)
                    .put("screenshotPath", lastRecord?.screenshotPath ?: JSONObject.NULL)
                    .put("snapshotPath", lastRecord?.path ?: JSONObject.NULL)
            )
        } else {
            failed(
                "FAILED_UNVERIFIABLE",
                "Could not capture a clean screenshot to verify WeChat Moments publish submission"
            ).copy(
                details = JSONObject()
                    .put("verifyType", "WechatMomentPublishSubmitted")
                    .put("publishButtonScore", lastScore)
                    .put("privacySelectionScore", lastPrivacyScore)
                    .put("topRightGreyButtonScore", lastTopRightGreyScore)
                    .put("screenshotPath", lastRecord?.screenshotPath ?: JSONObject.NULL)
                    .put("snapshotPath", lastRecord?.path ?: JSONObject.NULL)
            )
        }
    }

    private fun wechatTopRightGreenButtonScore(screenshotPath: String): Int? {
        return runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeFile(screenshotPath, options) ?: return@runCatching null
            try {
                wechatTopRightGreenButtonScore(bitmap)
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    private fun wechatPrivacySelectionPageScore(screenshotPath: String): Int? {
        return runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeFile(screenshotPath, options) ?: return@runCatching null
            try {
                wechatPrivacySelectionPageScore(bitmap)
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    private fun wechatTopRightGreyButtonScore(screenshotPath: String): Int? {
        return runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeFile(screenshotPath, options) ?: return@runCatching null
            try {
                wechatTopRightGreyButtonScore(bitmap)
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    private fun wechatPrivacySelectionPageScore(bitmap: android.graphics.Bitmap): Int {
        val yStart = (bitmap.height * 0.18f).toInt().coerceIn(0, bitmap.height - 1)
        val yEnd = (bitmap.height * 0.58f).toInt().coerceIn(yStart, bitmap.height - 1)
        val xStart = (bitmap.width * 0.035f).toInt().coerceIn(0, bitmap.width - 1)
        val xEnd = (bitmap.width * 0.125f).toInt().coerceIn(xStart, bitmap.width - 1)
        var score = 0
        var y = yStart
        while (y <= yEnd) {
            var x = xStart
            while (x <= xEnd) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                if (g >= 95 && g > r + 35 && g > b + 30) score++
                x += 2
            }
            y += 2
        }
        return score
    }

    private fun wechatTopRightGreenButtonScore(bitmap: android.graphics.Bitmap): Int {
        val yStart = (bitmap.height * 0.035f).toInt().coerceIn(0, bitmap.height - 1)
        val yEnd = (bitmap.height * 0.115f).toInt().coerceIn(yStart, bitmap.height - 1)
        val xStart = (bitmap.width * 0.800f).toInt().coerceIn(0, bitmap.width - 1)
        val xEnd = (bitmap.width * 0.980f).toInt().coerceIn(xStart, bitmap.width - 1)
        var score = 0
        var y = yStart
        while (y <= yEnd) {
            var x = xStart
            while (x <= xEnd) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                if (g >= 75 && g > r + 30 && g > b + 20) score++
                x += 2
            }
            y += 2
        }
        return score
    }

    private fun wechatTopRightGreyButtonScore(bitmap: android.graphics.Bitmap): Int {
        val yStart = (bitmap.height * 0.035f).toInt().coerceIn(0, bitmap.height - 1)
        val yEnd = (bitmap.height * 0.115f).toInt().coerceIn(yStart, bitmap.height - 1)
        val xStart = (bitmap.width * 0.800f).toInt().coerceIn(0, bitmap.width - 1)
        val xEnd = (bitmap.width * 0.980f).toInt().coerceIn(xStart, bitmap.width - 1)
        var score = 0
        var y = yStart
        while (y <= yEnd) {
            var x = xStart
            while (x <= xEnd) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                if (max in 35..130 && max - min <= 28) score++
                x += 2
            }
            y += 2
        }
        return score
    }

    private fun verifyTextVisible(
        verify: JSONObject,
        params: JSONObject,
        actionResult: SkillJsonRunResult
    ): SkillJsonRunResult {
        val text = verify.optString("text").ifBlank {
            paramValue(verify.optString("textParam", "text"), params)
        }
        if (text.isBlank()) return actionResult
        val timeoutMs = verify.optLong("timeoutMs", 1000L)
        val visible = waitUntil(timeoutMs) {
            inspector.currentState()?.nodes?.any { node ->
                node.text?.contains(text) == true || node.contentDesc?.contains(text) == true
            } == true
        }
        return if (visible) actionResult.copy(status = "SUCCESS", message = "Text visible: $text") else actionResult
    }

    private fun verifyInputChanged(
        verify: JSONObject,
        params: JSONObject,
        actionResult: SkillJsonRunResult
    ): SkillJsonRunResult {
        val text = verify.optString("text").ifBlank {
            paramValue(verify.optString("textParam", "text"), params)
        }
        val timeoutMs = verify.optLong("timeoutMs", 1000L)
        val contains = waitUntil(timeoutMs) {
            inspector.currentState()?.nodes?.any { node ->
                node.editable && node.text?.contains(text) == true
            } == true
        }
        if (contains) return actionResult.copy(status = "SUCCESS", message = "Input node contains text")
        val changed = inspector.currentState()?.eventsSinceLastState?.any {
            it.eventType.contains("TEXT", ignoreCase = true) ||
                it.eventType.contains("CONTENT", ignoreCase = true)
        } == true
        return when {
            changed -> actionResult.copy(status = "SUCCESS_MEDIUM_CONFIDENCE", message = "Text/content change event observed")
            actionResult.usedCoordinate -> actionResult.copy(status = "SUCCESS_LOW_CONFIDENCE", usedCoordinate = true)
            verify.optBoolean("allowLowConfidenceWhenUnverified", false) && actionResult.status.startsWith("SUCCESS") ->
                actionResult.copy(status = "SUCCESS_LOW_CONFIDENCE", message = "${actionResult.message}; input not readable after action")
            else -> failed("FAILED_UNVERIFIABLE", "Input change could not be verified")
        }
    }

    private fun verifyWindowOrNodeChanged(
        verify: JSONObject,
        actionResult: SkillJsonRunResult
    ): SkillJsonRunResult {
        val timeoutMs = verify.optLong("timeoutMs", 800L)
        val changed = waitUntil(timeoutMs) {
            inspector.currentState()?.eventsSinceLastState?.any {
                it.eventType.contains("WINDOW", ignoreCase = true) ||
                    it.eventType.contains("CONTENT", ignoreCase = true)
            } == true
        }
        return when {
            changed -> actionResult.copy(
                status = lowerStatus(actionResult.status, "SUCCESS_MEDIUM_CONFIDENCE"),
                message = "Window/node state changed; ${actionResult.message}"
            )
            actionResult.usedCoordinate -> actionResult.copy(status = "SUCCESS_LOW_CONFIDENCE", usedCoordinate = true)
            else -> actionResult
        }
    }

    private fun verifyGoal(
        skill: JSONObject,
        params: JSONObject,
        actionResult: SkillJsonRunResult
    ): SkillJsonRunResult {
        val verify = skill.optJSONObject("verify") ?: return actionResult
        val message = paramValue(verify.optString("messageParam", "message"), params)
        val timeoutMs = verify.optLong("timeoutMs", 1000L)
        val messageVisible = waitUntil(timeoutMs) {
            inspector.currentState()?.nodes?.any { node ->
                node.text?.contains(message) == true || node.contentDesc?.contains(message) == true
            } == true
        }
        if (messageVisible) return SkillJsonRunResult("SUCCESS", "Message text visible")
        val inputCleared = inspector.currentState()?.nodes?.any { node ->
            node.editable && node.text.isNullOrBlank()
        } == true
        if (inputCleared) return SkillJsonRunResult("SUCCESS_MEDIUM_CONFIDENCE", "Input appears cleared")
        val profile = profiler.profile(inspector.currentState(), com.macropilot.app.automation.MacroPilotAccessibilityService.instance != null)
        return if (verify.optBoolean("allowLowConfidenceWhenNodeUnreadable", false) && profile.nodeReadability == NodeReadability.C) {
            SkillJsonRunResult("SUCCESS_LOW_CONFIDENCE", "Goal is low-confidence because node tree is unreadable", usedCoordinate = actionResult.usedCoordinate)
        } else {
            failed("FAILED_UNVERIFIABLE", "Goal could not be verified")
        }
    }

    private fun verifyForegroundPackage(
        verify: JSONObject,
        actionResult: SkillJsonRunResult
    ): SkillJsonRunResult {
        val expected = verify.optString("packageName")
        val timeoutMs = verify.optLong("timeoutMs", 1000L)
        var sawReadableState = false
        var ok = waitUntil(timeoutMs) {
            val state = inspector.currentState()
            if (state != null) sawReadableState = true
            state?.appPackage == expected
        }
        var retryReason: String? = null
        if (!ok && expected.isNotBlank() && actionResult.status.startsWith("SUCCESS")) {
            val beforePackage = inspector.currentState()?.appPackage
            val explicit = actions.openAppExplicitLauncher(expected)
            sleep(900L)
            ok = waitUntil(verify.optLong("retryTimeoutMs", 4_500L).coerceIn(1_000L, 8_000L)) {
                val state = inspector.currentState()
                if (state != null) sawReadableState = true
                state?.appPackage == expected
            }
            retryReason = "first foreground check stayed at ${beforePackage ?: "unknown"}; explicit launcher retry=${explicit.success}:${explicit.reason}"
        }
        if (!ok && expected.isNotBlank() && actionResult.status.startsWith("SUCCESS")) {
            val beforePackage = inspector.currentState()?.appPackage
            val home = actions.home()
            sleep(700L)
            val root = actions.openAppLauncherRoot(expected)
            sleep(1_000L)
            ok = waitUntil(verify.optLong("retryTimeoutMs", 4_500L).coerceIn(1_000L, 8_000L)) {
                val state = inspector.currentState()
                if (state != null) sawReadableState = true
                state?.appPackage == expected
            }
            retryReason = listOfNotNull(
                retryReason,
                "second foreground check stayed at ${beforePackage ?: "unknown"}; home=${home.success}:${home.reason}; launcherRoot=${root.success}:${root.reason}"
            ).joinToString("; ")
        }
        if (!ok &&
            verify.optBoolean("allowUnverifiedWhenAccessibilityDisconnected", false) &&
            (!sawReadableState || com.macropilot.app.automation.MacroPilotAccessibilityService.instance == null) &&
            actionResult.status.startsWith("SUCCESS")
        ) {
            return SkillJsonRunResult(
                "SUCCESS_LOW_CONFIDENCE",
                "Launch intent sent; foreground package unreadable, continuing to permission/goal verification"
            )
        }
        return if (ok) {
            if (retryReason.isNullOrBlank()) actionResult else actionResult.copy(message = "${actionResult.message}; $retryReason")
        } else {
            failed("FAILED_TIMEOUT", "Foreground package did not become $expected; ${retryReason ?: "no retry"}")
        }
    }

    private fun checkCapability(skill: JSONObject): SkillJsonRunResult {
        val profile = profiler.profile(
            inspector.currentState(),
            com.macropilot.app.automation.MacroPilotAccessibilityService.instance != null
        )
        val policy = skill.optJSONObject("resultPolicy") ?: JSONObject()
        val status = when (profile.nodeReadability) {
            NodeReadability.A -> policy.optString("levelA", "SUCCESS")
            NodeReadability.B -> policy.optString("levelB", "SUCCESS_MEDIUM_CONFIDENCE")
            NodeReadability.C -> policy.optString("levelC", "SUCCESS_LOW_CONFIDENCE")
            NodeReadability.D -> policy.optString("levelD", "FAILED_CAPABILITY_D")
        }
        return if (status.startsWith("FAILED")) {
            failed(status, profile.reason)
        } else {
            SkillJsonRunResult(status, profile.reason)
        }
    }

    private fun resolveTarget(target: JSONObject?): ResolvedTarget {
        val matchers = selectorParser.matchersFromTarget(target)
        val state = inspector.currentState()
        val nodeResolved = state?.nodes?.any { node -> selectorParser.nodeMatchesAny(node, matchers) } == true
        val coordinateResolved = target?.optJSONObject("coordinatePack")?.optJSONObject("fallbackTap") != null
        return ResolvedTarget(nodeResolved = nodeResolved, coordinateResolved = coordinateResolved)
    }

    private fun tapCoordinate(target: JSONObject?): com.macropilot.app.automation.ActionOutcome {
        val state = inspector.currentState()
            ?: return com.macropilot.app.automation.ActionOutcome(false, "No UiState for coordinate fallback")
        val fallback = target?.optJSONObject("coordinatePack")?.optJSONObject("fallbackTap")
            ?: return com.macropilot.app.automation.ActionOutcome(false, "No coordinate fallback")
        return actions.tapNormalized(
            NormalizedPoint(
                x = fallback.optDouble("x", -1.0).toFloat().coerceIn(0f, 1f),
                y = fallback.optDouble("y", -1.0).toFloat().coerceIn(0f, 1f)
            ),
            displayScreenWidth(),
            displayScreenHeight()
        )
    }

    private fun tapActionCoordinate(action: JSONObject): com.macropilot.app.automation.ActionOutcome {
        val state = inspector.currentState()
        if (!action.has("x") && !action.has("normalizedX")) {
            return com.macropilot.app.automation.ActionOutcome(false, "No action coordinate fallback")
        }
        val x = action.optDouble("x", action.optDouble("normalizedX", -1.0)).toFloat()
        val y = action.optDouble("y", action.optDouble("normalizedY", -1.0)).toFloat()
        if (x !in 0f..1f || y !in 0f..1f) {
            return com.macropilot.app.automation.ActionOutcome(false, "Action coordinate fallback must be normalized")
        }
        if (state == null && action.optBoolean("requiresUiStateForCoordinate", true)) {
            return com.macropilot.app.automation.ActionOutcome(false, "No UiState for action coordinate fallback")
        }
        val width = displayScreenWidth()
        val height = displayScreenHeight()
        return actions.tapNormalized(
            NormalizedPoint(x, y),
            width,
            height
        )
    }

    private fun checkPreconditions(skill: JSONObject): SkillJsonRunResult? {
        val preconditions = skill.optJSONArray("preconditions") ?: return null
        for (i in 0 until preconditions.length()) {
            val item = preconditions.optJSONObject(i) ?: continue
            if (item.optString("type") == "ForegroundPackage") {
                val expected = item.optString("packageName")
                val current = inspector.currentState()?.appPackage
                if (current != expected) {
                    return failed("FAILED_PRECONDITION", "Foreground package mismatch: expected=$expected current=$current")
                }
            }
            if (item.optString("type") == "InputChannelAvailable" && !inputAvailable(item)) {
                return failed("FAILED_INPUT_CHANNEL_MISSING", "InputChannelAvailable precondition failed")
            }
        }
        return null
    }

    private fun inputAvailable(requirement: JSONObject? = null): Boolean {
        val requestedChannels = jsonArrayStrings(requirement?.optJSONArray("channels"))
            .ifEmpty { jsonArrayStrings(requirement?.optJSONArray("allowedInputChannels")) }
        val raw = profiler.profile(
            inspector.currentState(),
            com.macropilot.app.automation.MacroPilotAccessibilityService.instance != null
        )
        if (raw.inputCapability != InputCapability.NONE || actions.isMacroPilotImeSelected()) return true
        val accessibilityReady = com.macropilot.app.automation.MacroPilotAccessibilityService.instance != null
        return accessibilityReady && (requestedChannels.isEmpty() || requestedChannels.contains("ACTION_SET_TEXT"))
    }

    private fun jsonArrayStrings(raw: org.json.JSONArray?): List<String> {
        if (raw == null) return emptyList()
        return (0 until raw.length()).mapNotNull { index ->
            raw.optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun requiresSideEffectApproval(skill: JSONObject): Boolean {
        val id = skill.optString("id")
        if (id.contains("click_send_button") || id.contains("send_in_current_chat")) return true
        val action = skill.optJSONObject("action")
        if (action?.optString("type") == "ClickElement" &&
            listOf(action.optString("targetElementId"), action.optString("elementId")).any { it.contains("send_button") }
        ) return true
        val atoms = skill.optJSONArray("atoms") ?: return false
        for (i in 0 until atoms.length()) {
            val atom = atoms.optJSONObject(i) ?: continue
            val skillId = atom.optString("skillId")
            if (skillId.contains("click_send_button") || skillId.contains("send_in_current_chat")) return true
        }
        return false
    }

    private fun bindParams(raw: JSONObject?, params: JSONObject): JSONObject {
        if (raw == null) return params
        val out = JSONObject()
        val keys = raw.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = raw.optString(key)
            if (value.startsWith("$")) {
                out.put(key, params.optString(value.removePrefix("$")))
            } else {
                out.put(key, value)
            }
        }
        return out
    }

    private fun waitForStableBilibiliReady(packageName: String, timeoutMs: Long): UiStateSample? {
        val startedAt = System.currentTimeMillis()
        var firstReadySurface: String? = null
        while (System.currentTimeMillis() - startedAt <= timeoutMs) {
            val state = inspector.currentState()
            if (state != null &&
                state.appPackage == packageName &&
                isTrustedBilibiliReadySurface(state) &&
                !isBilibiliMessageOrDrawerSurface(state)
            ) {
                val surface = bilibiliSurfaceKind(state)
                if (firstReadySurface == surface) return state
                firstReadySurface = surface
            } else {
                firstReadySurface = null
            }
            sleep(350L)
        }
        return null
    }

    private fun isTrustedBilibiliReadySurface(state: UiStateSample): Boolean {
        if (!isBilibiliHomeOrSearchReady(state)) return false
        return when (bilibiliSurfaceKind(state)) {
            "home_tabs", "search_input", "search_results" -> true
            else -> false
        }
    }

    private fun findBilibiliExplicitBackNode(state: UiStateSample): NodeSample? {
        val width = minOf(state.effectiveScreenWidth(), displayScreenWidth()).coerceAtLeast(1)
        return state.nodes
            .filter { it.enabled && it.clickable }
            .filter { node ->
                val label = nodeLabel(node)
                val resourceId = node.resourceId.orEmpty()
                node.bounds.centerX() < width * 0.22f &&
                    node.bounds.centerY() < state.effectiveScreenHeight() * 0.20f &&
                    (
                        resourceId.contains("sidecenter_back", ignoreCase = true) ||
                            resourceId.endsWith(":id/back", ignoreCase = true) ||
                            resourceId.contains("_back", ignoreCase = true) ||
                            label.contains("\u8fd4\u56de", ignoreCase = true) ||
                            label.contains("\u8f6c\u5230\u4e0a\u4e00\u5c42\u7ea7", ignoreCase = true)
                        )
            }
            .minByOrNull { it.bounds.centerX() }
    }

    private fun findBilibiliSheetCloseNode(state: UiStateSample): NodeSample? {
        return state.nodes
            .filter { it.enabled && it.clickable }
            .filter { node ->
                val label = nodeLabel(node)
                label.contains("\u5173\u95ed\u5de5\u4f5c\u8868", ignoreCase = true) ||
                    (
                        node.bounds.left <= 4 &&
                            node.bounds.top <= 4 &&
                            node.bounds.right >= state.effectiveScreenWidth() * 0.80f &&
                            node.bounds.bottom in 800..(state.effectiveScreenHeight() - 300)
                        )
            }
            .minByOrNull { it.bounds.top }
    }

    private fun bilibiliBlockedSurfaceMarkers(): List<String> = listOf(
        "\u5173\u95ed\u5de5\u4f5c\u8868",
        "\u6d88\u606f\u8bbe\u7f6e",
        "UP\u4e3b\u5c0f\u52a9\u624b",
        "\u5e94\u63f4\u56e2\u6d88\u606f\u52a9\u624b",
        "\u901a\u8baf\u5f55",
        "\u9632\u9a9a\u6270\u548c\u4e92\u52a8\u4eba\u7fa4\u8bbe\u7f6e",
        "\u56de\u590d\u4e0e@",
        "\u6536\u5230\u559c\u6b22",
        "\u65b0\u589e\u7c89\u4e1d",
        "\u6211\u7684\u6d88\u606f",
        "\u7cfb\u7edf\u901a\u77e5",
        "\u6211\u7684\u6e38\u620f",
        "\u6e38\u620f\u901a\u77e5",
        "\u6e38\u620f\u4e2d\u5fc3",
        "\u6e38\u620f\u8bc4\u4ef7",
        "WIKI",
        "\u6e38\u620f\u5065\u5eb7\u7cfb\u7edf",
        "\u590d\u5236\u94fe\u63a5",
        "\u751f\u6210\u957f\u56fe",
        "\u4e0d\u63a8\u8350",
        "\u53d1\u4e2a\u6d88\u606f",
        "\u5520\u4f1a\u55d1",
        "\u8f6c\u5230\u52a8\u6001",
        "\u6700\u8fd1\u5728\u770b\u7684\u89c6\u9891",
        "\u6700\u8fd1\u4f7f\u7528\u7684\u5c0f\u7a0b\u5e8f",
        "\u79bb\u7ebf\u7f13\u5b58",
        "\u5386\u53f2\u8bb0\u5f55",
        "\u6211\u7684\u6536\u85cf",
        "\u7a0d\u540e\u518d\u770b"
    )

    private fun isBilibiliHomeOrSearchReady(state: UiStateSample): Boolean {
        if (state.appPackage != SkillJsonRuleGenerator.BILIBILI_PACKAGE) return false
        if (isBilibiliMessageOrDrawerSurface(state)) return false
        val labels = state.nodes.map { nodeLabel(it) }.filter { it.isNotBlank() }
        val context = labels.joinToString(" ")
        if (bilibiliBlockedSurfaceMarkers().any { marker -> context.contains(marker, ignoreCase = true) }) {
            return false
        }
        val hasHomeTabs = listOf("\u9996\u9875", "\u52a8\u6001", "\u4f1a\u5458\u8d2d", "\u6211\u7684")
            .count { marker -> labels.any { it == marker } } >= 2
        val hasSearchEntry = labels.any { label ->
            label.contains("expand_search", ignoreCase = true) ||
                label.contains("搜索栏", ignoreCase = true) ||
                label.contains("搜索", ignoreCase = true) && label.contains("按钮", ignoreCase = true)
        }
        val hasSearchInput = state.nodes.any { node ->
            node.editable ||
                node.resourceId.orEmpty().contains("search_src_text", ignoreCase = true) ||
                node.resourceId.orEmpty().contains("search_keyword", ignoreCase = true) ||
                node.resourceId.orEmpty().contains("search_input", ignoreCase = true)
        }
        val hasResultTabs = listOf("\u7efc\u5408", "\u89c6\u9891", "\u7528\u6237", "\u756a\u5267", "\u76f4\u64ad")
            .count { marker -> labels.any { it == marker || it.contains(marker, ignoreCase = true) } } >= 2
        val messageOrGameSurface = bilibiliBlockedSurfaceMarkers().any { marker -> context.contains(marker, ignoreCase = true) }
        val shareOrPostSurface = messageOrGameSurface
        val unsafeSurface = (messageOrGameSurface || shareOrPostSurface) && !hasSearchInput && !hasResultTabs && !hasHomeTabs
        return !unsafeSurface && (hasHomeTabs || hasSearchEntry || hasSearchInput || hasResultTabs)
    }

    private fun isBilibiliMessageOrDrawerSurface(state: UiStateSample): Boolean {
        if (state.appPackage != SkillJsonRuleGenerator.BILIBILI_PACKAGE) return false
        val labels = state.nodes.map { nodeLabel(it) }.filter { it.isNotBlank() }
        val context = labels.joinToString(" ")
        val homeTabs = listOf("\u9996\u9875", "\u52a8\u6001", "\u4f1a\u5458\u8d2d", "\u6211\u7684")
            .count { marker -> labels.any { it == marker } }
        val titleMessage = labels.any {
            val label = it.trim()
            label == "\u6d88\u606f" || label.endsWith(" \u6d88\u606f")
        }
        val blocked = bilibiliBlockedSurfaceMarkers().any { context.contains(it, ignoreCase = true) }
        return titleMessage || blocked || (homeTabs < 2 && blocked)
    }

    private fun bilibiliSurfaceKind(state: UiStateSample): String {
        val labels = state.nodes.map { nodeLabel(it) }.filter { it.isNotBlank() }
        val context = labels.joinToString(" ")
        return when {
            isBilibiliMessageOrDrawerSurface(state) -> "message_or_drawer_surface"
            context.contains("\u6211\u7684\u6d88\u606f", ignoreCase = true) ||
                context.contains("\u7cfb\u7edf\u901a\u77e5", ignoreCase = true) -> "message_or_system_notice"
            context.contains("\u6e38\u620f", ignoreCase = true) ||
                context.contains("WIKI", ignoreCase = true) -> "game_message_surface"
            context.contains("\u590d\u5236\u94fe\u63a5", ignoreCase = true) ||
                context.contains("\u5206\u4eab", ignoreCase = true) -> "share_or_menu_surface"
            labels.any { it.contains("search_src_text", ignoreCase = true) } -> "search_input"
            listOf("\u7efc\u5408", "\u89c6\u9891", "\u7528\u6237").count { marker -> labels.any { it == marker } } >= 2 -> "search_results"
            listOf("\u9996\u9875", "\u52a8\u6001", "\u4f1a\u5458\u8d2d", "\u6211\u7684").count { marker -> labels.any { it.contains(marker) } } >= 2 -> "home_tabs"
            else -> "unknown"
        }
    }

    private fun nodeLabel(node: com.macropilot.app.model.NodeSample): String {
        return listOfNotNull(node.text, node.contentDesc, node.resourceId)
            .joinToString(" ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
    }

    private fun looksLikePermissionDialog(state: UiStateSample): Boolean {
        val labels = (state.nodes.map { nodeLabel(it) } +
            state.eventsSinceLastState.mapNotNull { it.text?.takeIf { text -> text.isNotBlank() } })
            .filter { it.isNotBlank() }
        if (labels.isEmpty()) return false
        val context = labels.joinToString(" ")
        val promptMarkers = listOf(
            "\u8981\u5141\u8bb8",
            "\u6743\u9650",
            "\u6388\u6743",
            "\u62cd\u6444",
            "\u5f55\u5236",
            "\u76f8\u673a",
            "\u7167\u7247",
            "\u89c6\u9891",
            "\u9ea6\u514b\u98ce"
        )
        val allowMarkers = listOf(
            "\u4ec5\u9650\u8fd9\u4e00\u6b21",
            "\u4f7f\u7528\u65f6\u5141\u8bb8",
            "\u5141\u8bb8\u6240\u6709\u7167\u7247\u548c\u89c6\u9891",
            "\u9009\u62e9\u7167\u7247\u548c\u89c6\u9891",
            "\u53bb\u6388\u6743",
            "\u5141\u8bb8"
        )
        return promptMarkers.any { context.contains(it, ignoreCase = true) } &&
            allowMarkers.any { context.contains(it, ignoreCase = true) }
    }

    private fun saveCurrentSnapshot(
        source: String,
        note: String,
        relatedSkillId: String? = null,
        relatedActionType: String? = null
    ): String? {
        return saveCurrentSnapshotRecord(
            source = source,
            note = note,
            relatedSkillId = relatedSkillId,
            relatedActionType = relatedActionType
        )?.path
    }

    private fun saveCurrentSnapshotRecord(
        source: String,
        note: String,
        relatedSkillId: String? = null,
        relatedActionType: String? = null,
        forceScreenshot: Boolean = false,
        hideOverlayForScreenshot: Boolean = false
    ): UiSnapshotRecord? {
        val state = inspector.currentState() ?: return null
        val profile = profiler.profile(
            state,
            com.macropilot.app.automation.MacroPilotAccessibilityService.instance != null
        )
        return runCatching {
            if (hideOverlayForScreenshot) {
                MacroPilotAccessibilityService.instance?.hideStatusOverlay(0L)
                sleep(220L)
            }
            val file = snapshotStore.save(
                state = state,
                profile = profile,
                source = source,
                note = note,
                relatedSkillId = relatedSkillId,
                relatedActionType = relatedActionType,
                forceScreenshot = forceScreenshot
            )
            val json = JSONObject(file.readText())
            UiSnapshotRecord(
                path = file.absolutePath,
                screenshotPath = json.optString("screenshotPath").takeIf { it.isNotBlank() && it != "null" },
                screenshotSha256 = json.optString("screenshotSha256").takeIf { it.isNotBlank() && it != "null" },
                visualHash = json.optString("visualHash").takeIf { it.isNotBlank() && it != "null" },
                screenKey = json.optString("screenKey"),
                nodeCount = json.optJSONObject("summary")?.optInt("nodeCount", -1) ?: -1
            )
        }.getOrNull()
    }

    private fun wechatSelectedTabEvidence(screenshotPath: String?, tabXs: List<Float>): JSONObject {
        if (screenshotPath.isNullOrBlank()) {
            return JSONObject()
                .put("selectedIndex", -1)
                .put("selectedScore", 0)
                .put("reason", "no_screenshot")
        }
        return runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeFile(screenshotPath, options)
                ?: return@runCatching JSONObject()
                    .put("selectedIndex", -1)
                    .put("selectedScore", 0)
                    .put("reason", "decode_failed")
            try {
                val scores = JSONArray()
                var bestIndex = -1
                var bestScore = 0
                tabXs.forEachIndexed { index, xRatio ->
                    val score = greenScoreInBottomTab(bitmap, xRatio)
                    scores.put(score)
                    if (score > bestScore) {
                        bestScore = score
                        bestIndex = index
                    }
                }
                val blocker = wechatBottomSurfaceBlocker(bitmap)
                if (blocker == "bottom_sheet_dialog_visible" ||
                    blocker == "keyboard_or_emoji_panel_visible" ||
                    (blocker == "chat_composer_visible" && bestScore < 12)
                ) {
                    return@runCatching JSONObject()
                        .put("selectedIndex", -1)
                        .put("selectedScore", 0)
                        .put("scores", scores)
                        .put("reason", blocker)
                }
                JSONObject()
                    .put("selectedIndex", bestIndex)
                    .put("selectedScore", bestScore)
                    .put("scores", scores)
                    .put("surfaceWarning", blocker ?: JSONObject.NULL)
            } finally {
                bitmap.recycle()
            }
        }.getOrElse { error ->
            JSONObject()
                .put("selectedIndex", -1)
                .put("selectedScore", 0)
                .put("reason", error.javaClass.simpleName)
        }
    }

    private fun wechatBottomSurfaceBlocker(bitmap: android.graphics.Bitmap): String? {
        if (wechatDraftConfirmDialogScore(bitmap) >= 2_500 &&
            wechatTopRightGreenButtonScore(bitmap) >= 36
        ) {
            return "wechat_draft_confirm_dialog_visible"
        }

        if (bottomSheetDialogScore(bitmap) >= 900 && bottomSheetGreenButtonScore(bitmap) >= 35) {
            return "bottom_sheet_dialog_visible"
        }

        val composerIconScore =
            brightScore(bitmap, 0.055f, 0.815f, 0.945f, 0.040f) +
                brightScore(bitmap, 0.845f, 0.815f, 0.945f, 0.045f) +
                brightScore(bitmap, 0.940f, 0.815f, 0.945f, 0.045f)
        if (composerIconScore >= 120) return "chat_composer_visible"

        val emojiOrKeyboardScore = saturatedBottomScore(bitmap)
        if (emojiOrKeyboardScore >= 360) return "keyboard_or_emoji_panel_visible"

        return null
    }

    private fun wechatDraftConfirmDialogScore(bitmap: android.graphics.Bitmap): Int {
        val yStart = (bitmap.height * 0.42f).toInt().coerceIn(0, bitmap.height - 1)
        val yEnd = (bitmap.height * 0.61f).toInt().coerceIn(yStart, bitmap.height - 1)
        val xStart = (bitmap.width * 0.10f).toInt().coerceIn(0, bitmap.width - 1)
        val xEnd = (bitmap.width * 0.90f).toInt().coerceIn(xStart, bitmap.width - 1)
        var score = 0
        var y = yStart
        while (y <= yEnd) {
            var x = xStart
            while (x <= xEnd) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                if (max in 32..92 && max - min <= 18) score++
                x += 2
            }
            y += 2
        }
        return score
    }

    private fun bottomSheetDialogScore(bitmap: android.graphics.Bitmap): Int {
        val yStart = (bitmap.height * 0.64f).toInt().coerceIn(0, bitmap.height - 1)
        val yEnd = (bitmap.height * 0.90f).toInt().coerceIn(yStart, bitmap.height - 1)
        val xStart = (bitmap.width * 0.04f).toInt().coerceIn(0, bitmap.width - 1)
        val xEnd = (bitmap.width * 0.96f).toInt().coerceIn(xStart, bitmap.width - 1)
        var score = 0
        var y = yStart
        while (y <= yEnd) {
            var x = xStart
            while (x <= xEnd) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                if (max in 28..105 && max - min <= 20) score++
                x += 5
            }
            y += 5
        }
        return score
    }

    private fun bottomSheetGreenButtonScore(bitmap: android.graphics.Bitmap): Int {
        val yStart = (bitmap.height * 0.78f).toInt().coerceIn(0, bitmap.height - 1)
        val yEnd = (bitmap.height * 0.88f).toInt().coerceIn(yStart, bitmap.height - 1)
        val xStart = (bitmap.width * 0.50f).toInt().coerceIn(0, bitmap.width - 1)
        val xEnd = (bitmap.width * 0.86f).toInt().coerceIn(xStart, bitmap.width - 1)
        var score = 0
        var y = yStart
        while (y <= yEnd) {
            var x = xStart
            while (x <= xEnd) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                if (g >= 130 && g > r + 45 && g > b + 45) score++
                x += 4
            }
            y += 4
        }
        return score
    }

    private fun brightScore(
        bitmap: android.graphics.Bitmap,
        xRatio: Float,
        yStartRatio: Float,
        yEndRatio: Float,
        xRadiusRatio: Float
    ): Int {
        val centerX = (bitmap.width * xRatio).toInt().coerceIn(0, bitmap.width - 1)
        val xRadius = (bitmap.width * xRadiusRatio).toInt().coerceAtLeast(2)
        val yStart = (bitmap.height * yStartRatio).toInt().coerceIn(0, bitmap.height - 1)
        val yEnd = (bitmap.height * yEndRatio).toInt().coerceIn(yStart, bitmap.height - 1)
        var score = 0
        var y = yStart
        while (y <= yEnd) {
            var x = (centerX - xRadius).coerceAtLeast(0)
            val xEnd = (centerX + xRadius).coerceAtMost(bitmap.width - 1)
            while (x <= xEnd) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                if (r >= 170 && g >= 170 && b >= 170) score++
                x += 2
            }
            y += 2
        }
        return score
    }

    private fun saturatedBottomScore(bitmap: android.graphics.Bitmap): Int {
        val yStart = (bitmap.height * 0.64f).toInt().coerceIn(0, bitmap.height - 1)
        val yEnd = (bitmap.height * 0.985f).toInt().coerceIn(yStart, bitmap.height - 1)
        var score = 0
        var y = yStart
        while (y <= yEnd) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                if (max >= 120 && max - min >= 70) score++
                x += 4
            }
            y += 4
        }
        return score
    }

    private fun greenScoreInBottomTab(bitmap: android.graphics.Bitmap, xRatio: Float): Int {
        val centerX = (bitmap.width * xRatio).toInt().coerceIn(0, bitmap.width - 1)
        val xRadius = (bitmap.width * 0.075f).toInt().coerceAtLeast(2)
        val yStart = (bitmap.height * 0.905f).toInt().coerceIn(0, bitmap.height - 1)
        val yEnd = (bitmap.height * 0.985f).toInt().coerceIn(yStart, bitmap.height - 1)
        var score = 0
        var y = yStart
        while (y <= yEnd) {
            var x = (centerX - xRadius).coerceAtLeast(0)
            val xEnd = (centerX + xRadius).coerceAtMost(bitmap.width - 1)
            while (x <= xEnd) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                if (g >= 120 && g > r + 35 && g > b + 35) score++
                x += 3
            }
            y += 3
        }
        return score
    }

    private fun tapNodeCenter(node: NodeSample, state: UiStateSample): com.macropilot.app.automation.ActionOutcome {
        val effectiveWidth = state.effectiveScreenWidth()
        val effectiveHeight = state.effectiveScreenHeight()
        val x = node.bounds.centerX().toFloat() / effectiveWidth.coerceAtLeast(1)
        val y = node.bounds.centerY().toFloat() / effectiveHeight.coerceAtLeast(1)
        return actions.tapNormalized(
            NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)),
            effectiveWidth,
            effectiveHeight
        )
    }

    private fun UiStateSample.effectiveScreenWidth(): Int {
        val nodeRight = nodes.maxOfOrNull { it.bounds.right } ?: 0
        return maxOf(screenWidth, nodeRight, 1)
    }

    private fun UiStateSample.effectiveScreenHeight(): Int {
        val nodeBottom = nodes.maxOfOrNull { it.bounds.bottom } ?: 0
        return maxOf(screenHeight, nodeBottom, 1)
    }

    private fun displayScreenWidth(): Int {
        return ScreenMetrics.touchSize(context).width.coerceAtLeast(1)
    }

    private fun displayScreenHeight(): Int {
        return ScreenMetrics.touchSize(context).height.coerceAtLeast(1)
    }

    private fun paramValue(name: String, params: JSONObject): String {
        val key = name.removePrefix("$")
        return if (params.has(key) && !params.isNull(key)) params.optString(key) else ""
    }

    private fun shouldRunReactiveForAction(actionType: String): Boolean {
        return actionType !in setOf(
            "CheckCapability",
            "EnsureTextVideoAsset",
            "HandleSystemPermissionDialog",
            "HandleReactiveOverlays",
            "DismissBlockingOverlay"
        )
    }

    private fun runReactiveOverlays(
        appPackage: String?,
        reason: String,
        maxPasses: Int = 2
    ): ReactiveRuleResult {
        if (com.macropilot.app.automation.MacroPilotAccessibilityService.instance == null) {
            return ReactiveRuleResult(0, JSONArray())
        }
        if (!actions.isInteractive() || actions.isKeyguardLocked()) {
            return ReactiveRuleResult(0, JSONArray())
        }
        return reactiveRules.dismissBlockingOverlays(appPackage, reason, maxPasses)
    }

    private fun waitWithReactive(skill: JSONObject, timeoutMs: Long): ReactiveRuleResult {
        val startedAt = System.currentTimeMillis()
        var dismissed = 0
        val events = JSONArray()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val reactive = runReactiveOverlays(
                skill.optString("appPackage"),
                "wait:${skill.optString("id")}",
                maxPasses = 1
            )
            dismissed += reactive.dismissedCount
            appendEvents(events, reactive.events)
            val remaining = timeoutMs - (System.currentTimeMillis() - startedAt)
            if (remaining <= 0L) break
            sleep(remaining.coerceAtMost(360L))
        }
        return ReactiveRuleResult(dismissed, events)
    }

    private fun attachReactiveSummary(result: SkillJsonRunResult, dismissedCount: Int): SkillJsonRunResult {
        if (dismissedCount <= 0) return result
        return result.copy(
            message = "${result.message}; reactiveDismissedBeforeAction=$dismissedCount",
            usedCoordinate = true
        )
    }

    private fun appendEvents(target: JSONArray, source: JSONArray) {
        for (index in 0 until source.length()) {
            target.put(source.opt(index))
        }
    }

    private fun canRetryAfterReactive(status: String): Boolean {
        return status in setOf(
            "FAILED_ELEMENT_NOT_FOUND",
            "FAILED_TIMEOUT",
            "FAILED_UNVERIFIABLE",
            "FAILED_NO_SCROLLABLE_CONTAINER"
        )
    }

    private fun lowerStatus(left: String, right: String): String {
        return if (statusRank(right) < statusRank(left)) right else left
    }

    private fun statusRank(status: String): Int {
        return when (status) {
            "SUCCESS" -> 3
            "SUCCESS_MEDIUM_CONFIDENCE" -> 2
            "SUCCESS_LOW_CONFIDENCE" -> 1
            else -> 0
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started <= timeoutMs) {
            if (condition()) return true
            try {
                Thread.sleep(80L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun failed(status: String, message: String): SkillJsonRunResult {
        return SkillJsonRunResult(status = status, message = message)
    }
}

data class SkillJsonRunResult(
    val status: String,
    val message: String,
    val durationMs: Long = 0L,
    val usedCoordinate: Boolean = false,
    val details: JSONObject? = null
)

private data class ResolvedTarget(
    val nodeResolved: Boolean,
    val coordinateResolved: Boolean
)

private data class EntryCandidate(
    val node: NodeSample,
    val label: String,
    val matchedAlias: String,
    val score: Int
)

private data class BottomTabCandidate(
    val label: String,
    val x: Float,
    val y: Float,
    val score: Int
)

private data class WechatBottomTabTarget(
    val key: String,
    val label: String,
    val x: Float
)

private data class UiSnapshotRecord(
    val path: String,
    val screenshotPath: String?,
    val screenshotSha256: String?,
    val visualHash: String?,
    val screenKey: String,
    val nodeCount: Int
)

private fun JSONArray?.asStringList(): List<String> {
    if (this == null) return emptyList()
    val out = mutableListOf<String>()
    for (index in 0 until length()) {
        val value = optString(index)
        if (value.isNotBlank()) out += value
    }
    return out
}

private fun JSONArray?.asFloatList(): List<Float> {
    if (this == null) return emptyList()
    val out = mutableListOf<Float>()
    for (index in 0 until length()) {
        val value = optDouble(index, Double.NaN)
        if (!value.isNaN()) out += value.toFloat()
    }
    return out
}
