package com.macropilot.app.factory

import android.content.Context
import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.model.CapabilityProfile
import com.macropilot.app.model.CoordinateFallback
import com.macropilot.app.model.InputCapability
import com.macropilot.app.model.NodeReadability
import com.macropilot.app.model.NodeSample
import com.macropilot.app.model.NormalizedPoint
import com.macropilot.app.model.ServiceState
import com.macropilot.app.model.UiStateSample
import com.macropilot.app.model.VerificationCapability
import com.macropilot.app.runtime.CapabilityProfiler
import com.macropilot.app.runtime.NodeInspector
import com.macropilot.app.store.AppUiGraphStore
import com.macropilot.app.store.UiStateSnapshotStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

class AppUiExplorer(
    private val context: Context,
    private val actions: DeviceActions = DeviceActions(context),
    private val inspector: NodeInspector = NodeInspector(context),
    private val profiler: CapabilityProfiler = CapabilityProfiler(),
    private val snapshotStore: UiStateSnapshotStore = UiStateSnapshotStore(context),
    private val graphStore: AppUiGraphStore = AppUiGraphStore(context),
    private val reactiveRules: ReactiveUiRuleEngine = ReactiveUiRuleEngine(inspector, actions)
) {
    fun explore(
        packageName: String,
        appName: String = packageName,
        reason: String = "self_training",
        maxScreens: Int = 40,
        maxClicks: Int = 50,
        maxDepth: Int = 4,
        maxSwipeActions: Int = 100,
        maxDurationMs: Long = 180_000L,
        forceScreenshots: Boolean = true,
        primaryNavigationOnly: Boolean = false,
        onEvent: ((String, String, String, String?) -> Unit)? = null
    ): AppUiExploreResult {
        val startedAt = System.currentTimeMillis()
        val runId = "app_ui_graph_${packageName}_${startedAt}".replace(Regex("[^A-Za-z0-9._-]+"), "_")
        val deadlineAt = startedAt + maxDurationMs.coerceAtLeast(30_000L)
        val events = JSONArray()
        val screensByKey = linkedMapOf<String, JSONObject>()
        val edges = JSONArray()

        fun timedOut(): Boolean = System.currentTimeMillis() >= deadlineAt

        fun event(phase: String, status: String, message: String, path: String? = null) {
            events.put(JSONObject()
                .put("phase", phase)
                .put("status", status)
                .put("message", message)
                .put("path", path ?: JSONObject.NULL)
                .put("timestamp", System.currentTimeMillis()))
            MacroPilotAccessibilityService.instance?.showStatusOverlay("AppUI 自训练", status, message)
            onEvent?.invoke(phase, status, message, path)
        }

        if (!waitForAccessibilityService(events, timeoutMs = 8_000L)) {
            val report = JSONObject()
                .put("schemaVersion", 1)
                .put("kind", "app_ui_explore_report")
                .put("status", "FAILED_ACCESSIBILITY_DISCONNECTED")
                .put("appPackage", packageName)
                .put("appName", appName)
                .put("events", events)
                .put("durationMs", System.currentTimeMillis() - startedAt)
            return AppUiExploreResult(report, null, false)
        }
        if (!actions.isInteractive() || actions.isKeyguardLocked()) {
            val report = JSONObject()
                .put("schemaVersion", 1)
                .put("kind", "app_ui_explore_report")
                .put("status", "FAILED_DEVICE_LOCKED_OR_NOT_INTERACTIVE")
                .put("appPackage", packageName)
                .put("appName", appName)
                .put("interactive", actions.isInteractive())
                .put("keyguardLocked", actions.isKeyguardLocked())
                .put("events", events)
                .put("durationMs", System.currentTimeMillis() - startedAt)
            return AppUiExploreResult(report, null, false)
        }

        event("open_app", "RUNNING", "打开 $appName，开始读取首页、底部分屏、入口和输入框")
        val foreground = openAndWaitForTarget(packageName, events)
        events.put(JSONObject()
            .put("phase", "wait_foreground")
            .put("success", foreground.success)
            .put("currentPackage", foreground.currentPackage ?: JSONObject.NULL)
            .put("windowClassName", foreground.windowClassName ?: JSONObject.NULL)
            .put("overlayDismissed", foreground.overlayDismissed)
            .put("overlayEvents", foreground.overlayEvents))
        if (!foreground.success) {
            val state = inspector.currentState()
            val report = JSONObject()
                .put("schemaVersion", 1)
                .put("kind", "app_ui_explore_report")
                .put("status", "FAILED_FOREGROUND_PACKAGE_MISMATCH")
                .put("appPackage", packageName)
                .put("appName", appName)
                .put("currentPackage", state?.appPackage ?: JSONObject.NULL)
                .put("windowClassName", state?.windowClassName ?: JSONObject.NULL)
                .put("events", events)
                .put("durationMs", System.currentTimeMillis() - startedAt)
            return AppUiExploreResult(report, null, false)
        }
        val overlay = reactiveRules.dismissBlockingOverlays(packageName, "app_ui_explorer", maxPasses = 4)
        events.put(JSONObject().put("phase", "reactive_overlays").put("dismissed", overlay.dismissedCount).put("events", overlay.events))
        if (overlay.dismissedCount > 0) sleep(700L)
        if (isPinduoduoPackage(packageName)) {
            val state = inspector.currentState()
            if (state != null) {
                val tapHome = actions.tapNormalized(
                    NormalizedPoint(0.10f, 0.94f),
                    state.screenWidth.coerceAtLeast(1),
                    state.screenHeight.coerceAtLeast(1)
                )
                events.put(JSONObject()
                    .put("phase", "restore_pinduoduo_home_tab")
                    .put("success", tapHome.success)
                    .put("message", tapHome.reason)
                    .put("reason", "PDD can reopen the last video/feed tab; start probing from Home."))
                sleep(700L)
                reactiveRules.dismissBlockingOverlays(packageName, "pinduoduo_home_tab_restore", maxPasses = 2)
            }
        }

        var homeScreen = captureScreenWithRetry(
            packageName = packageName,
            source = "app_ui_explorer_home",
            note = "$reason:$appName:home",
            forceScreenshot = forceScreenshots,
            events = events,
            phase = "capture_home_wait"
        )
        if (homeScreen == null) {
            val state = inspector.currentState()
            val report = JSONObject()
                .put("schemaVersion", 1)
                .put("kind", "app_ui_explore_report")
                .put("status", "FAILED_NO_UI_STATE")
                .put("appPackage", packageName)
                .put("appName", appName)
                .put("currentPackage", state?.appPackage ?: JSONObject.NULL)
                .put("windowClassName", state?.windowClassName ?: JSONObject.NULL)
                .put("nodeCount", state?.nodes?.size ?: 0)
                .put("events", events)
                .put("durationMs", System.currentTimeMillis() - startedAt)
            return AppUiExploreResult(report, null, false)
        }
        if ((homeScreen.optJSONArray("primaryBottomTabs")?.length() ?: 0) < 2) {
            val recovered = recoverToPrimaryTabSurface(
                packageName = packageName,
                appName = appName,
                reason = reason,
                current = homeScreen,
                forceScreenshots = forceScreenshots,
                events = events
            )
            if (recovered != null) {
                events.put(JSONObject()
                    .put("phase", "recover_primary_tab_surface")
                    .put("status", "SUCCESS")
                    .put("message", "Recovered to a primary-tab surface before deep probing.")
                    .put("beforeScreenKey", homeScreen.optString("screenKey"))
                    .put("afterScreenKey", recovered.optString("screenKey"))
                    .put("primaryBottomTabCount", recovered.optJSONArray("primaryBottomTabs")?.length() ?: 0))
                homeScreen = recovered
            } else {
                events.put(JSONObject()
                    .put("phase", "recover_primary_tab_surface")
                    .put("status", "LOW_CONFIDENCE")
                    .put("message", "Primary-tab surface was not found before probing.")
                    .put("primaryBottomTabCount", homeScreen.optJSONArray("primaryBottomTabs")?.length() ?: 0))
            }
        }
        screensByKey[screenGraphKey(homeScreen)] = homeScreen
        event("capture_home", "SUCCESS", "已记录首页：${homeScreen.optInt("nodeCount")} 个节点、${homeScreen.optInt("clickableCount")} 个可点击项", homeScreen.optString("snapshotPath"))

        val explored = linkedSetOf<String>()
        val tappedKeys = linkedSetOf<String>()
        val successfulFingerprints = linkedSetOf<String>()
        val failedFingerprints = mutableMapOf<String, Int>()
        val probeMemory = ExploreProbeMemory(successfulFingerprints, failedFingerprints)
        val queue = ArrayDeque<Pair<JSONObject, Int>>()
        var remainingClicks = maxClicks.coerceAtLeast(0)
        var remainingSwipes = maxSwipeActions.coerceAtLeast(0)

        val primarySweep = explorePrimaryBottomTabs(
            packageName = packageName,
            appName = appName,
            reason = reason,
            homeScreen = homeScreen,
            screensByKey = screensByKey,
            edges = edges,
            events = events,
            maxScreens = maxScreens,
            remainingClicks = remainingClicks,
            deadlineAt = deadlineAt,
            forceScreenshots = forceScreenshots,
            probeMemory = probeMemory,
            event = { phase, status, message, path -> event(phase, status, message, path) }
        )
        remainingClicks = (remainingClicks - primarySweep.tapCount).coerceAtLeast(0)
        val centerSweep = explorePrimaryCenterActions(
            packageName = packageName,
            appName = appName,
            reason = reason,
            homeScreen = homeScreen,
            screensByKey = screensByKey,
            edges = edges,
            events = events,
            maxScreens = maxScreens,
            remainingClicks = remainingClicks,
            deadlineAt = deadlineAt,
            forceScreenshots = forceScreenshots,
            probeMemory = probeMemory,
            event = { phase, status, message, path -> event(phase, status, message, path) }
        )
        remainingClicks = (remainingClicks - centerSweep.tapCount).coerceAtLeast(0)
        val tabSurfaceProbe = probePrimaryTabSurfaces(
            packageName = packageName,
            appName = appName,
            reason = reason,
            tabScreens = primarySweep.screens,
            screensByKey = screensByKey,
            edges = edges,
            events = events,
            maxScreens = maxScreens,
            remainingClicks = remainingClicks,
            deadlineAt = deadlineAt,
            forceScreenshots = forceScreenshots,
            probeMemory = probeMemory,
            event = { phase, status, message, path -> event(phase, status, message, path) }
        )
        remainingClicks = (remainingClicks - tabSurfaceProbe.tapCount).coerceAtLeast(0)
        if (primaryNavigationOnly) {
            events.put(JSONObject()
                .put("phase", "skip_deep_bfs")
                .put("status", "SUCCESS")
                .put("message", "primaryNavigationOnly=true; stopped after bottom tabs and first-level tab surfaces"))
            remainingClicks = 0
        }
        primarySweep.screens.forEach { tabScreen ->
            queue.add(tabScreen to 0)
        }
        queue.add(homeScreen to 0)

        fun restoreSurface(screen: JSONObject, depth: Int, label: String): Boolean {
            return if (depth == 0) {
                val reopen = openAndWaitForTarget(packageName, events)
                events.put(JSONObject()
                    .put("phase", "restore_home_before_entry")
                    .put("label", label)
                    .put("success", reopen.success)
                    .put("message", reopen.message))
                sleep(700L)
                reactiveRules.dismissBlockingOverlays(packageName, "app_ui_explorer_restore_home", maxPasses = 2)
                if (!reopen.success) return false
                val restoreAction = screen.optJSONObject("restoreAction")
                if (restoreAction?.optString("type") == "TapPrimaryBottomTab") {
                    val tap = actions.tapNormalized(
                        NormalizedPoint(
                            restoreAction.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                            restoreAction.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                        ),
                        screen.optInt("screenWidth", 1).coerceAtLeast(1),
                        screen.optInt("screenHeight", 1).coerceAtLeast(1)
                    )
                    events.put(JSONObject()
                        .put("phase", "restore_primary_bottom_tab")
                        .put("label", restoreAction.optString("label"))
                        .put("success", tap.success)
                        .put("message", tap.reason))
                    sleep(650L)
                    reactiveRules.dismissBlockingOverlays(packageName, "app_ui_explorer_restore_tab", maxPasses = 2)
                    tap.success
                } else {
                    true
                }
            } else {
                true
            }
        }

        while (queue.isNotEmpty() && !timedOut() && remainingClicks > 0 && screensByKey.size < maxScreens.coerceAtLeast(1)) {
            val (screen, depth) = queue.removeFirst()
            if (depth > maxDepth.coerceAtLeast(0)) continue
            val screenKey = screenGraphKey(screen)
            if (screenKey.isBlank()) continue
            if (!explored.add(screenKey)) continue

            val categoryFailures = mutableMapOf<String, Int>()
            val includeBottomTabsInBfs = (screen.optJSONArray("primaryBottomTabs")?.length() ?: 0) < 2 &&
                primarySweep.tapCount == 0
            val candidates = safeExploreCandidates(screen, includeBottomTabs = includeBottomTabsInBfs)
                .filter { candidate ->
                    val key = candidateKey(screenKey, candidate)
                    val fingerprint = globalCandidateFingerprint(candidate)
                    val failureCount = failedFingerprints[fingerprint] ?: 0
                    val skipReason = when {
                        key in tappedKeys -> "same_screen_candidate_already_tapped"
                        failureCount >= maxGlobalFailures(candidate) -> "global_candidate_failed_${failureCount}_times"
                        shouldSkipSuccessfulFingerprint(candidate, fingerprint, successfulFingerprints) -> "global_candidate_already_opened"
                        else -> null
                    }
                    if (skipReason != null) {
                        events.put(JSONObject()
                            .put("phase", "skip_repeated_candidate")
                            .put("screenKey", screenKey)
                            .put("candidateCategory", candidate.optString("candidateCategory").ifBlank { "unknown" })
                            .put("label", candidate.optString("label"))
                            .put("fingerprint", fingerprint)
                            .put("reason", skipReason))
                    }
                    skipReason == null
                }
                .take(remainingClicks.coerceAtMost(60))

            for ((index, candidate) in candidates.withIndex()) {
                if (timedOut() || remainingClicks <= 0 || screensByKey.size >= maxScreens.coerceAtLeast(1)) break
                val category = candidate.optString("candidateCategory").ifBlank { "unknown" }
                if (isExternalAuthCandidate(candidate, packageName)) {
                    events.put(JSONObject()
                        .put("phase", "skip_external_auth_candidate")
                        .put("category", category)
                        .put("label", candidate.optString("label"))
                        .put("packageName", packageName)
                        .put("reason", "external login/auth entry is recorded but not opened during app UI graph probing"))
                    continue
                }
                if ((categoryFailures[category] ?: 0) >= 3) {
                    events.put(JSONObject()
                        .put("phase", "skip_category_after_failures")
                        .put("category", category)
                        .put("screenKey", screenKey)
                        .put("reason", "连续 3 次未进入新页面，跳过同类候选避免重复点"))
                    continue
                }
                val tapKey = candidateKey(screenKey, candidate)
                val tapFingerprint = globalCandidateFingerprint(candidate)
                if (!tappedKeys.add(tapKey)) continue
                val label = candidate.optString("label").ifBlank { "${category}_${index + 1}" }
                if (!restoreSurface(screen, depth, label)) {
                    categoryFailures[category] = (categoryFailures[category] ?: 0) + 1
                    failedFingerprints[tapFingerprint] = (failedFingerprints[tapFingerprint] ?: 0) + 1
                    continue
                }
                event("explore_entry", "RUNNING", "BFS/DFS 点击 $category：$label")
                val before = captureScreenWithRetry(
                    packageName = packageName,
                    source = "app_ui_before_tap",
                    note = "$reason:$appName:before:$category:$label:depth=$depth",
                    forceScreenshot = forceScreenshots,
                    events = events,
                    phase = "capture_before_tap"
                )
                val beforeKey = before?.let { screenGraphKey(it) }.orEmpty().ifBlank { screenKey }
                val tap = actions.tapNormalized(
                    NormalizedPoint(
                        candidate.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                        candidate.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                    ),
                    screen.optInt("screenWidth", 1).coerceAtLeast(1),
                    screen.optInt("screenHeight", 1).coerceAtLeast(1)
                )
                remainingClicks -= 1
                events.put(JSONObject()
                    .put("phase", "tap_entry")
                    .put("depth", depth)
                    .put("label", label)
                    .put("candidateCategory", category)
                    .put("success", tap.success)
                    .put("message", tap.reason)
                    .put("beforeScreenKey", beforeKey)
                    .put("beforeSnapshotPath", before?.optString("snapshotPath") ?: JSONObject.NULL)
                    .put("globalFingerprint", tapFingerprint)
                    .put("previousGlobalFailures", failedFingerprints[tapFingerprint] ?: 0)
                    .put("candidate", candidate))
                sleep(900L)
                reactiveRules.dismissBlockingOverlays(packageName, "app_ui_explorer_after_tap", maxPasses = 2)
                val child = captureScreenWithRetry(
                    packageName = packageName,
                    source = "app_ui_after_tap_depth_${depth + 1}",
                    note = "$reason:$appName:after:$category:$label:depth=${depth + 1}",
                    forceScreenshot = forceScreenshots,
                    events = events,
                    phase = "capture_child_wait_depth_${depth + 1}"
                )
                val change = screenChange(before ?: screen, child)
                val childKey = child?.let { screenGraphKey(it) }.orEmpty()
                val changed = change.changed
                if (child != null && childKey.isNotBlank()) {
                    val isNewScreen = screensByKey.putIfAbsent(childKey, child) == null
                    edges.put(JSONObject()
                        .put("fromScreenKey", beforeKey)
                        .put("toScreenKey", childKey)
                        .put("action", JSONObject()
                            .put("type", "TapCandidate")
                            .put("category", category)
                            .put("label", label)
                            .put("x", candidate.optDouble("x"))
                            .put("y", candidate.optDouble("y")))
                        .put("changed", changed)
                        .put("isNewScreen", isNewScreen)
                        .put("beforeSnapshotPath", before?.optString("snapshotPath") ?: JSONObject.NULL)
                        .put("afterSnapshotPath", child.optString("snapshotPath"))
                        .put("beforeNodeCount", before?.optInt("nodeCount") ?: 0)
                        .put("afterNodeCount", child.optInt("nodeCount"))
                        .put("depth", depth + 1)
                        .put("changeScore", change.score)
                        .put("changeConfidence", change.confidence)
                        .put("changeReason", change.reason))
                    event(
                        "capture_child",
                        if (changed) "SUCCESS" else "LOW_CONFIDENCE",
                        "$category $label 后记录页面：before=$beforeKey after=${childKey.take(8)} changed=$changed nodes=${child.optInt("nodeCount")}",
                        child.optString("snapshotPath")
                    )
                    if (changed && depth + 1 <= maxDepth.coerceAtLeast(0)) {
                        successfulFingerprints.add(tapFingerprint)
                        queue.add(child to (depth + 1))
                    } else if (!changed) {
                        categoryFailures[category] = (categoryFailures[category] ?: 0) + 1
                        failedFingerprints[tapFingerprint] = (failedFingerprints[tapFingerprint] ?: 0) + 1
                    } else {
                        successfulFingerprints.add(tapFingerprint)
                    }
                } else {
                    categoryFailures[category] = (categoryFailures[category] ?: 0) + 1
                    failedFingerprints[tapFingerprint] = (failedFingerprints[tapFingerprint] ?: 0) + 1
                    recoverIfOutsideTarget(packageName, "after_bfs_tap:$category:$label", events)
                    events.put(JSONObject()
                        .put("phase", "capture_child")
                        .put("status", "FAILED_NO_AFTER_SCREEN")
                        .put("category", category)
                        .put("label", label))
                }
                actions.back()
                sleep(550L)
            }

            if (!timedOut() && remainingSwipes > 0 && screensByKey.size < maxScreens.coerceAtLeast(1)) {
                val swipeBudget = remainingSwipes.coerceAtMost(if (depth == 0) 8 else 4)
                val swipeAdded = exploreBySwipes(
                    packageName = packageName,
                    appName = appName,
                    reason = reason,
                    baseScreen = screen,
                    screensByKey = screensByKey,
                    edges = edges,
                    events = events,
                    maxScreens = maxScreens,
                    depth = depth,
                    swipeBudget = swipeBudget,
                    deadlineAt = deadlineAt
                )
                remainingSwipes -= swipeBudget
                if (swipeAdded > 0) {
                    event("swipe_explore", "SUCCESS", "点击候选后，再用少量滑动补充 ${swipeAdded} 个新界面")
                }
            }
        }

        val quality = graphQuality(screensByKey.values.toList(), edges)
        val graphStatus = when {
            quality.optBoolean("usable") -> "CANDIDATE_EXPLORED"
            timedOut() && screensByKey.isNotEmpty() -> "CANDIDATE_PARTIAL_TIMEOUT"
            screensByKey.isNotEmpty() -> "CANDIDATE_LOW_SIGNAL"
            else -> "FAILED_NO_SCREEN"
        }
        val reportStatus = when {
            quality.optBoolean("usable") -> "SUCCESS"
            timedOut() && screensByKey.isNotEmpty() -> "PARTIAL_TIMEOUT"
            screensByKey.isNotEmpty() -> "FAILED_LOW_SIGNAL_UI_GRAPH"
            else -> "FAILED_NO_SCREEN"
        }
        val graph = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "app_ui_graph")
            .put("id", runId)
            .put("status", graphStatus)
            .put("appPackage", packageName)
            .put("appName", appName)
            .put("reason", reason)
            .put("quality", quality)
            .put("budgets", JSONObject()
                .put("maxScreens", maxScreens)
                .put("maxClicks", maxClicks)
                .put("maxDepth", maxDepth)
                .put("maxSwipeActions", maxSwipeActions)
                .put("maxDurationMs", maxDurationMs))
            .put("screens", JSONArray(screensByKey.values.toList()))
            .put("edges", edges)
            .put("events", events)
            .put("createdAt", startedAt)
            .put("updatedAt", System.currentTimeMillis())
        val file = graphStore.saveCandidate(packageName, graph)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "app_ui_explore_report")
            .put("id", "app_ui_explore_report_$runId")
            .put("graphId", runId)
            .put("status", reportStatus)
            .put("appPackage", packageName)
            .put("appName", appName)
            .put("screenCount", screensByKey.size)
            .put("edgeCount", edges.length())
            .put("quality", quality)
            .put("graphFile", file.absolutePath)
            .put("events", events)
            .put("timedOut", timedOut())
            .put("maxDurationMs", maxDurationMs)
            .put("durationMs", System.currentTimeMillis() - startedAt)
        return AppUiExploreResult(report, file, reportStatus == "SUCCESS")
    }

    private fun captureScreen(
        packageName: String,
        source: String,
        note: String,
        forceScreenshot: Boolean
    ): JSONObject? {
        val state = inspector.currentState() ?: return null
        if (state.appPackage != packageName) return null
        val profile = profiler.profile(state, MacroPilotAccessibilityService.instance != null)
        val snapshot = runCatching {
            snapshotStore.save(
                state = state,
                profile = profile,
                source = source,
                note = note,
                forceScreenshot = forceScreenshot
            )
        }.getOrNull()
        return screenJson(state, profile, snapshot, packageName)
    }

    private fun captureScreenWithRetry(
        packageName: String,
        source: String,
        note: String,
        forceScreenshot: Boolean,
        events: JSONArray,
        phase: String,
        timeoutMs: Long = 3_500L
    ): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(300L)
        var attempts = 0
        var lastPackage: String? = null
        var lastWindow: String? = null
        var lastNodeCount = 0
        var restoreAttempted = false
        while (System.currentTimeMillis() <= deadline) {
            attempts += 1
            val screen = captureScreen(packageName, source, note, forceScreenshot)
            if (screen != null) {
                if (attempts > 1) {
                    events.put(JSONObject()
                        .put("phase", phase)
                        .put("status", "SUCCESS")
                        .put("attempts", attempts)
                        .put("currentPackage", screen.optString("appPackage"))
                        .put("nodeCount", screen.optInt("nodeCount")))
                }
                return screen
            }
            val state = inspector.currentState()
            lastPackage = state?.appPackage
            lastWindow = state?.windowClassName
            lastNodeCount = state?.nodes?.size ?: 0
            if (state != null && state.appPackage != packageName && shouldDismissDuringLaunch(state, packageName)) {
                reactiveRules.dismissBlockingOverlays(packageName, phase, maxPasses = 1)
            } else if (state != null && state.appPackage != null && state.appPackage != packageName && !restoreAttempted) {
                restoreAttempted = true
                val recovered = quickRestoreTarget(packageName, events, "capture_outside_target:$phase")
                if (recovered.success) {
                    sleep(350L)
                    continue
                }
            }
            sleep(300L)
        }
        events.put(JSONObject()
            .put("phase", phase)
            .put("status", "FAILED")
            .put("attempts", attempts)
            .put("currentPackage", lastPackage ?: JSONObject.NULL)
            .put("windowClassName", lastWindow ?: JSONObject.NULL)
            .put("nodeCount", lastNodeCount))
        return null
    }

    private fun screenJson(
        state: UiStateSample,
        profile: CapabilityProfile,
        snapshot: File?,
        expectedPackage: String
    ): JSONObject {
        val labels = state.nodes.mapNotNull { nodeLabel(it).takeIf { label -> label.isNotBlank() } }
            .distinct()
            .take(80)
        val snapshotMeta = snapshotMeta(snapshot)
        val textSignature = textSignature(state)
        val structureSignature = structureSignature(state)
        val navigationSignature = navigationSignature(state)
        val actionSignature = actionSignature(state)
        return JSONObject()
            .put("screenId", "screen_${state.screenKey().take(12)}")
            .put("screenKey", state.screenKey())
            .put("graphKey", graphKey(state, snapshotMeta?.optString("visualHash").orEmpty()))
            .put("textSignature", textSignature)
            .put("structureSignature", structureSignature)
            .put("navigationSignature", navigationSignature)
            .put("actionSignature", actionSignature)
            .put("visualHash", snapshotMeta?.optString("visualHash")?.takeIf { it.isNotBlank() && it != "null" } ?: JSONObject.NULL)
            .put("title", inferTitle(labels))
            .put("appPackage", state.appPackage ?: expectedPackage)
            .put("windowClassName", state.windowClassName ?: JSONObject.NULL)
            .put("screenWidth", state.screenWidth)
            .put("screenHeight", state.screenHeight)
            .put("nodeCount", state.nodes.size)
            .put("clickableCount", state.nodes.count { it.clickable })
            .put("editableCount", state.nodes.count { it.editable })
            .put("checkableCount", state.nodes.count { it.checkable })
            .put("scrollableCount", state.nodes.count { it.scrollable })
            .put("labels", JSONArray(labels))
            .put("primaryBottomTabs", JSONArray(primaryBottomTabCandidates(state)))
            .put("bottomTabs", JSONArray(bottomCandidates(state)))
            .put("topEntries", JSONArray(topCandidates(state)))
            .put("sideEntries", JSONArray(sideCandidates(state)))
            .put("graphicalCandidates", JSONArray(graphicalCandidates(state)))
            .put("entryCandidates", JSONArray(entryCandidates(state)))
            .put("checkableCandidates", JSONArray(checkableCandidates(state)))
            .put("inputCandidates", JSONArray(inputCandidates(state)))
            .put("clickTargets", JSONArray(clickTargets(state)))
            .put("videoCandidates", JSONArray(videoCandidates(state)))
            .put("capability", JSONObject()
                .put("nodeReadability", profile.nodeReadability.name)
                .put("inputCapability", profile.inputCapability.name)
                .put("verificationCapability", profile.verificationCapability.name)
                .put("coordinateFallback", profile.coordinateFallback.name)
                .put("serviceState", profile.serviceState.name))
            .put("snapshotPath", snapshot?.absolutePath ?: JSONObject.NULL)
            .put("capturedAt", System.currentTimeMillis())
    }

    private fun snapshotMeta(snapshot: File?): JSONObject? {
        if (snapshot == null || !snapshot.isFile) return null
        return runCatching { JSONObject(snapshot.readText()) }.getOrNull()
    }

    private fun graphKey(state: UiStateSample, visualHash: String): String {
        val raw = listOf(
            state.appPackage.orEmpty(),
            state.windowClassName.orEmpty(),
            textSignature(state),
            structureSignature(state),
            navigationSignature(state),
            actionSignature(state),
            visualHash.take(16)
        ).joinToString("|")
        return raw.hashCode().toUInt().toString(16)
    }

    private fun textSignature(state: UiStateSample): String {
        return state.nodes
            .asSequence()
            .mapNotNull { nodeLabel(it).takeIf { label -> label.isNotBlank() } }
            .map { it.replace(Regex("""\s+"""), " ").take(48) }
            .distinct()
            .take(24)
            .joinToString("|")
    }

    private fun structureSignature(state: UiStateSample): String {
        return state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    !isSystemDecorationNode(node) &&
                    !node.bounds.isEmpty &&
                    (node.clickable || node.editable || node.checkable || node.scrollable || looksLikeIconOnlyNode(node, state) || looksLikeGridIconNode(node, state))
            }
            .sortedWith(compareBy<NodeSample> { it.bounds.top }.thenBy { it.bounds.left })
            .take(64)
            .map { node ->
                val cx = ((node.bounds.centerX().toDouble() / state.screenWidth.coerceAtLeast(1)) * 12).toInt().coerceIn(0, 12)
                val cy = ((node.bounds.centerY().toDouble() / state.screenHeight.coerceAtLeast(1)) * 20).toInt().coerceIn(0, 20)
                val w = ((node.bounds.width().toDouble() / state.screenWidth.coerceAtLeast(1)) * 10).toInt().coerceIn(0, 10)
                val h = ((node.bounds.height().toDouble() / state.screenHeight.coerceAtLeast(1)) * 10).toInt().coerceIn(0, 10)
                val flags = buildString {
                    if (node.clickable) append('c')
                    if (node.editable) append('e')
                    if (node.checkable) append('k')
                    if (node.scrollable) append('s')
                    if (node.selected) append('x')
                    if (looksLikeIconOnlyNode(node, state)) append('i')
                    if (looksLikeGridIconNode(node, state)) append('g')
                }.ifBlank { "n" }
                val clazz = node.className.orEmpty().substringAfterLast('.').take(16)
                "$cx,$cy,$w,$h,$flags,$clazz"
            }
            .joinToString("|")
    }

    private fun navigationSignature(state: UiStateSample): String {
        return primaryBottomTabCandidates(state)
            .joinToString("|") { item ->
                val x = (item.optDouble("x", 0.0) * 10).toInt()
                val selected = if (item.optBoolean("selected")) "1" else "0"
                "${item.optString("label").take(16)}@$x:$selected"
            }
    }

    private fun actionSignature(state: UiStateSample): String {
        val candidates = (topCandidates(state) + sideCandidates(state) + graphicalCandidates(state) + inputCandidates(state))
            .sortedWith(compareBy<JSONObject> { it.optDouble("y") }.thenBy { it.optDouble("x") })
            .take(48)
        return candidates.joinToString("|") { candidate ->
            val x = (candidate.optDouble("x", 0.0) * 12).toInt()
            val y = (candidate.optDouble("y", 0.0) * 20).toInt()
            val id = normalizedCandidateIdentity(candidate).take(32)
            "$id@$x,$y"
        }
    }

    private fun safeExploreCandidates(screen: JSONObject, includeBottomTabs: Boolean): List<JSONObject> {
        val merged = mutableListOf<JSONObject>()
        fun append(array: JSONArray?, category: String) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val label = item.optString("label").ifBlank { category }
                if (isDangerousExploreLabel(label)) continue
                val x = item.optDouble("x", item.optJSONObject("center")?.optDouble("x", 0.5) ?: 0.5)
                val y = item.optDouble("y", item.optJSONObject("center")?.optDouble("y", 0.5) ?: 0.5)
                if (x !in 0.0..1.0 || y !in 0.0..1.0) continue
                if (merged.none { existing -> candidatesOverlap(existing, item, x, y) }) {
                    merged += JSONObject(item.toString())
                        .put("label", label)
                        .put("x", x)
                        .put("y", y)
                        .put("candidateCategory", category)
                }
            }
        }
        if (includeBottomTabs) {
            append(screen.optJSONArray("bottomTabs"), "bottom_tab")
        }
        append(screen.optJSONArray("topEntries"), "top_icon")
        append(centerActionCandidates(screen), "center_action")
        append(screen.optJSONArray("sideEntries"), "side_icon")
        append(screen.optJSONArray("graphicalCandidates"), "graphical_ui")
        append(screen.optJSONArray("checkableCandidates"), "checkable")
        append(screen.optJSONArray("entryCandidates"), "text_entry")
        append(screen.optJSONArray("videoCandidates"), "video_card")
        append(screen.optJSONArray("clickTargets"), "clickable_ui")
        append(screen.optJSONArray("inputCandidates"), "input")
        return merged
            .groupBy { it.optString("candidateCategory").ifBlank { "unknown" } }
            .flatMap { (category, items) ->
                val limit = when (category) {
                    "graphical_ui" -> 18
                    "top_icon" -> 12
                    "center_action" -> 4
                    "bottom_tab" -> 8
                    "side_icon" -> 10
                    "checkable" -> 8
                    "video_card" -> 8
                    "text_entry" -> 10
                    "input" -> 4
                    else -> 8
                }
                items.sortedWith(compareByDescending<JSONObject> { scoreExploreCandidateV2(it) }
                    .thenBy { it.optDouble("y") }
                    .thenBy { it.optDouble("x") })
                    .take(limit)
            }
            .sortedWith(compareBy<JSONObject> { categoryPriority(it.optString("candidateCategory")) }
                .thenByDescending { scoreExploreCandidateV2(it) }
                .thenBy { it.optDouble("y") }
                .thenBy { it.optDouble("x") })
    }

    private fun categoryPriority(category: String): Int {
        return when (category) {
            "graphical_ui" -> 0
            "top_icon" -> 1
            "center_action" -> 2
            "checkable" -> 3
            "side_icon" -> 4
            "text_entry" -> 5
            "bottom_tab" -> 6
            "clickable_ui" -> 7
            "video_card" -> 8
            "input" -> 7
            else -> 8
        }
    }

    private fun centerActionCandidates(screen: JSONObject): JSONArray {
        val out = JSONArray()
        val arrays = listOf(
            screen.optJSONArray("bottomTabs"),
            screen.optJSONArray("graphicalCandidates"),
            screen.optJSONArray("clickTargets"),
            screen.optJSONArray("entryCandidates")
        )
        val seen = linkedSetOf<String>()
        arrays.forEach { array ->
            if (array == null) return@forEach
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val x = item.optDouble("x", item.optJSONObject("center")?.optDouble("x", -1.0) ?: -1.0)
                val y = item.optDouble("y", item.optJSONObject("center")?.optDouble("y", -1.0) ?: -1.0)
                if (x !in 0.40..0.60 || y < 0.70) continue
                val haystack = listOf(
                    item.optString("label"),
                    item.optString("text"),
                    item.optString("desc"),
                    item.optString("resourceId")
                ).joinToString(" ").lowercase(Locale.CHINA)
                val looksLikeAction = haystack.contains("home_publish_icon") ||
                    haystack.contains("publish") ||
                    haystack.contains("create") ||
                    haystack.contains("发布") ||
                    haystack.contains("投稿") ||
                    haystack.contains("创作") ||
                    haystack.contains("加号") ||
                    haystack.contains("+") ||
                    item.optString("label").isBlank()
                if (!looksLikeAction) continue
                val key = "${(x * 100).toInt()}|${(y * 100).toInt()}|${haystack.take(40)}"
                if (!seen.add(key)) continue
                out.put(JSONObject(item.toString())
                    .put("label", item.optString("label").ifBlank {
                        item.optString("resourceId").ifBlank { "center_action" }
                    })
                    .put("x", x)
                    .put("y", y)
                    .put("candidateCategory", "center_action"))
            }
        }
        return out
    }

    private fun candidateKey(screenKey: String, candidate: JSONObject): String {
        val category = candidate.optString("candidateCategory")
        val scope = when (category) {
            "bottom_tab",
            "top_icon",
            "side_icon",
            "center_action",
            "checkable" -> "global"
            else -> screenKey.take(12)
        }
        val id = candidate.optString("resourceId")
            .ifBlank { candidate.optString("desc") }
            .ifBlank { candidate.optString("label") }
        val xBucket = (candidate.optDouble("x", 0.0) * 100).toInt()
        val yBucket = (candidate.optDouble("y", 0.0) * 100).toInt()
        return listOf(
            scope,
            category,
            id.take(80),
            xBucket,
            yBucket
        ).joinToString("|")
    }

    private fun globalCandidateFingerprint(candidate: JSONObject): String {
        val category = candidate.optString("candidateCategory").ifBlank { "unknown" }
        val identity = normalizedCandidateIdentity(candidate)
        val region = candidateRegion(candidate)
        val xBucket = (candidate.optDouble("x", 0.5) * 20).toInt().coerceIn(0, 20)
        val yBucket = (candidate.optDouble("y", 0.5) * 20).toInt().coerceIn(0, 20)
        return listOf(category, identity, region, xBucket, yBucket).joinToString("|")
    }

    private fun normalizedCandidateIdentity(candidate: JSONObject): String {
        val raw = candidate.optString("resourceId")
            .ifBlank { candidate.optString("desc") }
            .ifBlank { candidate.optString("text") }
            .ifBlank { candidate.optString("label") }
            .ifBlank { candidate.optString("class") }
            .lowercase(Locale.US)
            .replace(Regex("""\s+"""), " ")
            .trim()
        val compact = raw
            .substringAfterLast('/')
            .substringAfterLast(':')
            .replace(Regex("""[^a-z0-9\u4e00-\u9fa5_+\- ]"""), "")
            .trim()
        return compact.take(72).ifBlank { "icon_only" }
    }

    private fun candidateRegion(candidate: JSONObject): String {
        val x = candidate.optDouble("x", 0.5)
        val y = candidate.optDouble("y", 0.5)
        return when {
            y >= 0.82 -> "bottom"
            y <= 0.18 -> when {
                x <= 0.25 -> "top_left"
                x >= 0.75 -> "top_right"
                else -> "top_center"
            }
            x <= 0.15 -> "left_side"
            x >= 0.85 -> "right_side"
            y in 0.18..0.45 -> "upper_content"
            y in 0.45..0.74 -> "middle_content"
            else -> "lower_content"
        }
    }

    private fun maxGlobalFailures(candidate: JSONObject): Int {
        return when (candidate.optString("candidateCategory")) {
            "top_icon", "side_icon", "center_action", "graphical_ui", "checkable" -> 2
            "bottom_tab" -> 3
            else -> 2
        }
    }

    private fun shouldSkipSuccessfulFingerprint(
        candidate: JSONObject,
        fingerprint: String,
        successfulFingerprints: Set<String>
    ): Boolean {
        if (fingerprint !in successfulFingerprints) return false
        return when (candidate.optString("candidateCategory")) {
            "bottom_tab", "input" -> false
            else -> true
        }
    }

    private fun candidatesOverlap(existing: JSONObject, item: JSONObject, x: Double, y: Double): Boolean {
        val existingX = existing.optDouble("x", existing.optJSONObject("center")?.optDouble("x", -1.0) ?: -1.0)
        val existingY = existing.optDouble("y", existing.optJSONObject("center")?.optDouble("y", -1.0) ?: -1.0)
        if (existingX !in 0.0..1.0 || existingY !in 0.0..1.0) return false
        val sameIdentity = normalizedCandidateIdentity(existing) == normalizedCandidateIdentity(item) &&
            normalizedCandidateIdentity(existing) != "icon_only"
        val close = abs(existingX - x) < 0.026 && abs(existingY - y) < 0.026
        val veryClose = abs(existingX - x) < 0.014 && abs(existingY - y) < 0.014
        return veryClose || (sameIdentity && close)
    }

    private fun screenChange(before: JSONObject?, after: JSONObject?): ScreenChange {
        if (before == null || after == null) {
            return ScreenChange(false, 0, "NONE", "missing_before_or_after_screen")
        }
        var score = 0
        val reasons = mutableListOf<String>()
        fun add(points: Int, reason: String) {
            score += points
            reasons += reason
        }

        val beforeKey = before.optString("screenKey")
        val afterKey = after.optString("screenKey")
        val beforeGraphKey = before.optString("graphKey")
        val afterGraphKey = after.optString("graphKey")
        val beforeText = before.optString("textSignature")
        val afterText = after.optString("textSignature")
        val beforeStructure = before.optString("structureSignature")
        val afterStructure = after.optString("structureSignature")
        val beforeAction = before.optString("actionSignature")
        val afterAction = after.optString("actionSignature")
        val beforeNavigation = before.optString("navigationSignature")
        val afterNavigation = after.optString("navigationSignature")
        val beforeVisual = before.optString("visualHash").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        val afterVisual = after.optString("visualHash").takeIf { it.isNotBlank() && it != "null" }.orEmpty()

        if (afterKey.isNotBlank() && beforeKey.isNotBlank() && afterKey != beforeKey) add(4, "screen_key_changed")
        if (afterGraphKey.isNotBlank() && beforeGraphKey.isNotBlank() && afterGraphKey != beforeGraphKey) add(5, "graph_key_changed")
        if (beforeStructure.isNotBlank() && afterStructure.isNotBlank() && beforeStructure != afterStructure) add(4, "structure_changed")
        if (beforeAction.isNotBlank() && afterAction.isNotBlank() && beforeAction != afterAction) add(3, "action_candidates_changed")
        if (beforeNavigation.isNotBlank() && afterNavigation.isNotBlank() && beforeNavigation != afterNavigation) add(5, "navigation_changed")
        if (beforeVisual.isNotBlank() && afterVisual.isNotBlank() && beforeVisual != afterVisual) add(2, "visual_hash_changed")

        val nodeDelta = abs(after.optInt("nodeCount") - before.optInt("nodeCount"))
        if (nodeDelta >= 12) add(2, "node_count_delta_$nodeDelta")
        val clickableDelta = abs(after.optInt("clickableCount") - before.optInt("clickableCount"))
        if (clickableDelta >= 4) add(1, "clickable_count_delta_$clickableDelta")

        val textSimilarity = signatureSimilarity(beforeText, afterText)
        val structureSimilarity = signatureSimilarity(beforeStructure, afterStructure)
        val actionSimilarity = signatureSimilarity(beforeAction, afterAction)
        if (textSimilarity < 0.55 && beforeText.isNotBlank() && afterText.isNotBlank()) add(2, "text_signature_diverged")
        if (structureSimilarity < 0.68 && beforeStructure.isNotBlank() && afterStructure.isNotBlank()) add(2, "structure_signature_diverged")
        if (actionSimilarity < 0.62 && beforeAction.isNotBlank() && afterAction.isNotBlank()) add(2, "action_signature_diverged")

        val mostlyFeedRefresh = beforeNavigation == afterNavigation &&
            textSimilarity < 0.45 &&
            structureSimilarity >= 0.78 &&
            actionSimilarity >= 0.70 &&
            nodeDelta < 10
        if (mostlyFeedRefresh) {
            score -= 5
            reasons += "looks_like_feed_refresh_not_navigation"
        }

        val changed = score >= 5
        val confidence = when {
            score >= 9 -> "HIGH"
            score >= 5 -> "MEDIUM"
            score >= 3 -> "LOW"
            else -> "NONE"
        }
        return ScreenChange(changed, score, confidence, reasons.joinToString(",").ifBlank { "no_material_change" })
    }

    private fun screenGraphKey(screen: JSONObject): String {
        return screen.optString("graphKey")
            .ifBlank { screen.optString("screenKey") }
            .ifBlank { "screen_${screen.optInt("nodeCount")}_${screen.optInt("clickableCount")}" }
    }

    private fun signatureSimilarity(left: String, right: String): Double {
        if (left.isBlank() && right.isBlank()) return 1.0
        if (left.isBlank() || right.isBlank()) return 0.0
        val leftSet = left.split('|').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val rightSet = right.split('|').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (leftSet.isEmpty() && rightSet.isEmpty()) return 1.0
        if (leftSet.isEmpty() || rightSet.isEmpty()) return 0.0
        val intersection = leftSet.intersect(rightSet).size.toDouble()
        val union = leftSet.union(rightSet).size.toDouble().coerceAtLeast(1.0)
        return intersection / union
    }

    private fun explorePrimaryBottomTabs(
        packageName: String,
        appName: String,
        reason: String,
        homeScreen: JSONObject,
        screensByKey: MutableMap<String, JSONObject>,
        edges: JSONArray,
        events: JSONArray,
        maxScreens: Int,
        remainingClicks: Int,
        deadlineAt: Long,
        forceScreenshots: Boolean,
        probeMemory: ExploreProbeMemory,
        event: (String, String, String, String?) -> Unit
    ): PrimaryTabSweepResult {
        val detectedTabs = primaryBottomTabCandidates(homeScreen)
        val rawTabs = if (detectedTabs.size >= 2) {
            detectedTabs
        } else {
            detectedTabs + bottomGridFallbackTabs(homeScreen, packageName, detectedTabs.size)
        }
        val tabs = normalizePrimaryTabsForPackage(rawTabs, packageName)
        events.put(JSONObject()
            .put("phase", "primary_bottom_tab_candidates")
            .put("count", tabs.size)
            .put("labels", JSONArray(tabs.map { it.optString("label") }))
            .put("detectedCount", detectedTabs.size)
            .put("fallbackCount", (tabs.size - detectedTabs.size).coerceAtLeast(0))
            .put("message", "primary bottom tabs are explored before top icons"))
        if (tabs.isEmpty() || remainingClicks <= 0) {
            event("primary_bottom_tab_sweep", "LOW_CONFIDENCE", "no primary bottom tab candidates", null)
            return PrimaryTabSweepResult(emptyList(), 0)
        }

        val capturedScreens = mutableListOf<JSONObject>()
        var tapCount = 0
        val maxTabs = remainingClicks.coerceAtMost(tabs.size)
        for ((index, tab) in tabs.take(maxTabs).withIndex()) {
            if (System.currentTimeMillis() >= deadlineAt || screensByKey.size >= maxScreens.coerceAtLeast(1)) break
            val label = tab.optString("label").ifBlank { "bottom_tab_${index + 1}" }
            val fingerprint = globalCandidateFingerprint(tab)
            if (probeMemory.shouldSkip(tab, fingerprint)) {
                events.put(JSONObject()
                    .put("phase", "skip_primary_bottom_tab_repeat")
                    .put("label", label)
                    .put("fingerprint", fingerprint)
                    .put("failures", probeMemory.failures[fingerprint] ?: 0))
                continue
            }
            event("primary_bottom_tab_sweep", "RUNNING", "open bottom tab first: $label", null)
            val before = captureScreenWithRetry(
                packageName = packageName,
                source = "app_ui_before_primary_bottom_tab",
                note = "$reason:$appName:before_primary_bottom_tab:$label",
                forceScreenshot = forceScreenshots,
                events = events,
                phase = "capture_before_primary_bottom_tab"
            )
            if (before == null && inspector.currentState()?.appPackage != packageName) {
                probeMemory.record(fingerprint, false)
                events.put(JSONObject()
                    .put("phase", "skip_primary_bottom_tab_outside_target")
                    .put("label", label)
                    .put("reason", "target app was not foreground before tapping; recovered instead of tapping stale coordinates"))
                quickRestoreTarget(packageName, events, "before_primary_bottom_tab:$label")
                continue
            }
            val beforeKey = before?.let { screenGraphKey(it) }.orEmpty().ifBlank { screenGraphKey(homeScreen) }
            val screenForMetrics = before ?: homeScreen
            val tap = actions.tapNormalized(
                NormalizedPoint(
                    tab.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                    tab.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                ),
                screenForMetrics.optInt("screenWidth", 1).coerceAtLeast(1),
                screenForMetrics.optInt("screenHeight", 1).coerceAtLeast(1)
            )
            tapCount += 1
            events.put(JSONObject()
                .put("phase", "tap_primary_bottom_tab")
                .put("index", index + 1)
                .put("label", label)
                .put("success", tap.success)
                .put("message", tap.reason)
                .put("candidate", tab)
                .put("beforeScreenKey", beforeKey)
                .put("beforeSnapshotPath", before?.optString("snapshotPath") ?: JSONObject.NULL))
            sleep(900L)
            reactiveRules.dismissBlockingOverlays(packageName, "app_ui_after_primary_bottom_tab", maxPasses = 2)
            val after = captureScreenWithRetry(
                packageName = packageName,
                source = "app_ui_after_primary_bottom_tab",
                note = "$reason:$appName:after_primary_bottom_tab:$label",
                forceScreenshot = forceScreenshots,
                events = events,
                phase = "capture_after_primary_bottom_tab"
            )
            if (after == null && recoverIfOutsideTarget(packageName, "after_primary_bottom_tab:$label", events)) {
                probeMemory.record(fingerprint, false)
                event("primary_bottom_tab_sweep", "RECOVERED_OUTSIDE_TARGET", "bottom tab $label left target app; recovered and skipped this probe", null)
                continue
            }
            val change = screenChange(before ?: homeScreen, after)
            val afterKey = after?.let { screenGraphKey(it) }.orEmpty()
            val changed = change.changed
            if (after != null && afterKey.isNotBlank()) {
                after.put("parentScreenKey", screenGraphKey(homeScreen))
                after.put(
                    "restoreAction",
                    JSONObject()
                        .put("type", "TapPrimaryBottomTab")
                        .put("label", label)
                        .put("x", tab.optDouble("x"))
                        .put("y", tab.optDouble("y"))
                )
                val isNewScreen = screensByKey.putIfAbsent(afterKey, after) == null
                edges.put(JSONObject()
                    .put("fromScreenKey", beforeKey)
                    .put("toScreenKey", afterKey)
                    .put("action", JSONObject()
                        .put("type", "TapPrimaryBottomTab")
                        .put("category", "primary_bottom_tab")
                        .put("label", label)
                        .put("x", tab.optDouble("x"))
                        .put("y", tab.optDouble("y")))
                    .put("changed", changed)
                    .put("isNewScreen", isNewScreen)
                    .put("beforeSnapshotPath", before?.optString("snapshotPath") ?: JSONObject.NULL)
                    .put("afterSnapshotPath", after.optString("snapshotPath"))
                    .put("beforeNodeCount", before?.optInt("nodeCount") ?: 0)
                    .put("afterNodeCount", after.optInt("nodeCount"))
                    .put("depth", 0)
                    .put("phase", "primary_bottom_tab_sweep")
                    .put("changeScore", change.score)
                    .put("changeConfidence", change.confidence)
                    .put("changeReason", change.reason))
                capturedScreens += after
                event(
                    "primary_bottom_tab_sweep",
                    if (changed) "SUCCESS" else "LOW_CONFIDENCE",
                    "bottom tab $label captured: changed=$changed nodes=${after.optInt("nodeCount")}",
                    after.optString("snapshotPath")
                )
                probeMemory.record(fingerprint, changed)
            } else {
                probeMemory.record(fingerprint, false)
                event("primary_bottom_tab_sweep", "FAILED_NO_AFTER_SCREEN", "bottom tab $label did not produce a readable page", null)
            }
        }
        return PrimaryTabSweepResult(capturedScreens, tapCount)
    }

    private fun explorePrimaryCenterActions(
        packageName: String,
        appName: String,
        reason: String,
        homeScreen: JSONObject,
        screensByKey: MutableMap<String, JSONObject>,
        edges: JSONArray,
        events: JSONArray,
        maxScreens: Int,
        remainingClicks: Int,
        deadlineAt: Long,
        forceScreenshots: Boolean,
        probeMemory: ExploreProbeMemory,
        event: (String, String, String, String?) -> Unit
    ): PrimaryCenterActionSweepResult {
        if (remainingClicks <= 0 || System.currentTimeMillis() >= deadlineAt) {
            return PrimaryCenterActionSweepResult(0)
        }
        val actionsToTry = centerActionCandidates(homeScreen)
            .asJsonObjectList()
            .sortedWith(compareByDescending<JSONObject> { scoreExploreCandidateV2(it) }
                .thenBy { it.optDouble("y") }
                .thenBy { it.optDouble("x") })
            .take(2)
        events.put(JSONObject()
            .put("phase", "primary_center_action_candidates")
            .put("count", actionsToTry.size)
            .put("labels", JSONArray(actionsToTry.map { it.optString("label") }))
            .put("message", "center +/publish action is explored separately from bottom tabs"))
        var tapCount = 0
        for ((index, candidate) in actionsToTry.withIndex()) {
            if (System.currentTimeMillis() >= deadlineAt || tapCount >= remainingClicks || screensByKey.size >= maxScreens.coerceAtLeast(1)) break
            val label = candidate.optString("label").ifBlank { "center_action_${index + 1}" }
            val fingerprint = globalCandidateFingerprint(candidate)
            if (probeMemory.shouldSkip(candidate, fingerprint)) {
                events.put(JSONObject()
                    .put("phase", "skip_primary_center_action_repeat")
                    .put("label", label)
                    .put("fingerprint", fingerprint)
                    .put("failures", probeMemory.failures[fingerprint] ?: 0))
                continue
            }
            if (isExternalAuthCandidate(candidate, packageName)) {
                events.put(JSONObject()
                    .put("phase", "skip_external_auth_candidate")
                    .put("category", "primary_center_action")
                    .put("label", label)
                    .put("reason", "external login/auth entry is recorded but not opened during app UI graph probing"))
                continue
            }
            event("primary_center_action", "RUNNING", "open center action: $label", null)
            val reopen = openAndWaitForTarget(packageName, events)
            events.put(JSONObject()
                .put("phase", "restore_home_before_center_action")
                .put("label", label)
                .put("success", reopen.success)
                .put("message", reopen.message))
            if (!reopen.success) continue
            sleep(700L)
            reactiveRules.dismissBlockingOverlays(packageName, "center_action_restore_home", maxPasses = 2)
            val before = captureScreenWithRetry(
                packageName = packageName,
                source = "app_ui_before_center_action",
                note = "$reason:$appName:before_center_action:$label",
                forceScreenshot = forceScreenshots,
                events = events,
                phase = "capture_before_center_action"
            )
            val beforeKey = before?.let { screenGraphKey(it) }.orEmpty().ifBlank { screenGraphKey(homeScreen) }
            val metrics = before ?: homeScreen
            val tap = actions.tapNormalized(
                NormalizedPoint(
                    candidate.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                    candidate.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                ),
                metrics.optInt("screenWidth", 1).coerceAtLeast(1),
                metrics.optInt("screenHeight", 1).coerceAtLeast(1)
            )
            tapCount += 1
            events.put(JSONObject()
                .put("phase", "tap_primary_center_action")
                .put("index", index + 1)
                .put("label", label)
                .put("success", tap.success)
                .put("message", tap.reason)
                .put("candidate", candidate)
                .put("beforeScreenKey", beforeKey)
                .put("beforeSnapshotPath", before?.optString("snapshotPath") ?: JSONObject.NULL))
            sleep(900L)
            reactiveRules.dismissBlockingOverlays(packageName, "center_action_after_tap", maxPasses = 2)
            val after = captureScreenWithRetry(
                packageName = packageName,
                source = "app_ui_after_center_action",
                note = "$reason:$appName:after_center_action:$label",
                forceScreenshot = forceScreenshots,
                events = events,
                phase = "capture_after_center_action"
            )
            val change = screenChange(before ?: homeScreen, after)
            val afterKey = after?.let { screenGraphKey(it) }.orEmpty()
            val changed = change.changed
            if (after != null && afterKey.isNotBlank()) {
                val isNewScreen = screensByKey.putIfAbsent(afterKey, after) == null
                edges.put(JSONObject()
                    .put("fromScreenKey", beforeKey)
                    .put("toScreenKey", afterKey)
                    .put("action", JSONObject()
                        .put("type", "TapPrimaryCenterAction")
                        .put("category", "primary_center_action")
                        .put("label", label)
                        .put("x", candidate.optDouble("x"))
                        .put("y", candidate.optDouble("y")))
                    .put("changed", changed)
                    .put("isNewScreen", isNewScreen)
                    .put("beforeSnapshotPath", before?.optString("snapshotPath") ?: JSONObject.NULL)
                    .put("afterSnapshotPath", after.optString("snapshotPath"))
                    .put("beforeNodeCount", before?.optInt("nodeCount") ?: 0)
                    .put("afterNodeCount", after.optInt("nodeCount"))
                    .put("depth", 0)
                    .put("phase", "primary_center_action_sweep")
                    .put("changeScore", change.score)
                    .put("changeConfidence", change.confidence)
                    .put("changeReason", change.reason))
                event(
                    "primary_center_action",
                    if (changed) "SUCCESS" else "LOW_CONFIDENCE",
                    "center action $label captured: changed=$changed nodes=${after.optInt("nodeCount")}",
                    after.optString("snapshotPath")
                )
                probeMemory.record(fingerprint, changed)
            } else {
                probeMemory.record(fingerprint, false)
                recoverIfOutsideTarget(packageName, "after_center_action:$label", events)
                event("primary_center_action", "FAILED_NO_AFTER_SCREEN", "center action $label did not produce a readable page", null)
            }
            actions.back()
            sleep(550L)
        }
        return PrimaryCenterActionSweepResult(tapCount)
    }

    private fun probePrimaryTabSurfaces(
        packageName: String,
        appName: String,
        reason: String,
        tabScreens: List<JSONObject>,
        screensByKey: MutableMap<String, JSONObject>,
        edges: JSONArray,
        events: JSONArray,
        maxScreens: Int,
        remainingClicks: Int,
        deadlineAt: Long,
        forceScreenshots: Boolean,
        probeMemory: ExploreProbeMemory,
        event: (String, String, String, String?) -> Unit
    ): PrimaryTabSurfaceProbeResult {
        var tapCount = 0
        if (tabScreens.isEmpty() || remainingClicks <= 0) {
            return PrimaryTabSurfaceProbeResult(0)
        }
        for (tabScreen in tabScreens) {
            if (System.currentTimeMillis() >= deadlineAt || tapCount >= remainingClicks || screensByKey.size >= maxScreens.coerceAtLeast(1)) break
            val restoreAction = tabScreen.optJSONObject("restoreAction")
            val tabLabel = restoreAction?.optString("label").orEmpty().ifBlank { tabScreen.optString("title").ifBlank { "primary_tab" } }
            if (shouldSkipPrimaryTabSurfaceProbe(packageName, tabLabel, tabScreen)) {
                events.put(JSONObject()
                    .put("phase", "skip_primary_tab_surface_probe")
                    .put("tabLabel", tabLabel)
                    .put("reason", "captured this tab surface, but skipped feed/video side controls to keep probing bounded"))
                continue
            }
            val candidates = tabSurfaceCandidates(tabScreen, tabLabel)
                .filterNot { candidate ->
                    val category = candidate.optString("candidateCategory")
                    category == "video_card" || category == "bottom_tab"
                }
                .sortedWith(compareByDescending<JSONObject> { scoreTabSurfaceCandidate(it, tabLabel) }
                    .thenBy { it.optDouble("y") }
                    .thenBy { it.optDouble("x") })
                .take(tabSurfaceProbeLimit(tabLabel))
            events.put(JSONObject()
                .put("phase", "primary_tab_surface_candidates")
                .put("tabLabel", tabLabel)
                .put("count", candidates.size)
                .put("labels", JSONArray(candidates.map { it.optString("label") }))
                .put("message", "scan first-level UI under each bottom tab before deep BFS"))

            for ((index, candidate) in candidates.withIndex()) {
                if (System.currentTimeMillis() >= deadlineAt || tapCount >= remainingClicks || screensByKey.size >= maxScreens.coerceAtLeast(1)) break
                if (restoreAction?.optString("type") == "TapPrimaryBottomTab") {
                    openAndWaitForTarget(packageName, events)
                    sleep(500L)
                    actions.tapNormalized(
                        NormalizedPoint(
                            restoreAction.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                            restoreAction.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                        ),
                        tabScreen.optInt("screenWidth", 1).coerceAtLeast(1),
                        tabScreen.optInt("screenHeight", 1).coerceAtLeast(1)
                    )
                    sleep(650L)
                    reactiveRules.dismissBlockingOverlays(packageName, "primary_tab_surface_restore", maxPasses = 2)
                }
                val category = candidate.optString("candidateCategory").ifBlank { "unknown" }
                val label = candidate.optString("label").ifBlank { "${category}_${index + 1}" }
                val fingerprint = globalCandidateFingerprint(candidate)
                if (probeMemory.shouldSkip(candidate, fingerprint)) {
                    events.put(JSONObject()
                        .put("phase", "skip_primary_tab_surface_repeat")
                        .put("tabLabel", tabLabel)
                        .put("category", category)
                        .put("label", label)
                        .put("fingerprint", fingerprint)
                        .put("failures", probeMemory.failures[fingerprint] ?: 0))
                    continue
                }
                if (isExternalAuthCandidate(candidate, packageName)) {
                    events.put(JSONObject()
                        .put("phase", "skip_external_auth_candidate")
                        .put("category", category)
                        .put("tabLabel", tabLabel)
                        .put("label", label)
                        .put("reason", "external login/auth entry is recorded but not opened during app UI graph probing"))
                    continue
                }
                event("primary_tab_surface_entry", "RUNNING", "tab=$tabLabel click $category: $label", null)
                val before = captureScreenWithRetry(
                    packageName = packageName,
                    source = "app_ui_before_primary_tab_surface_entry",
                    note = "$reason:$appName:tab_surface_before:$tabLabel:$category:$label",
                    forceScreenshot = forceScreenshots,
                    events = events,
                    phase = "capture_before_primary_tab_surface_entry"
                )
                val beforeKey = before?.let { screenGraphKey(it) }.orEmpty().ifBlank { screenGraphKey(tabScreen) }
                val tap = actions.tapNormalized(
                    NormalizedPoint(
                        candidate.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                        candidate.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                    ),
                    tabScreen.optInt("screenWidth", 1).coerceAtLeast(1),
                    tabScreen.optInt("screenHeight", 1).coerceAtLeast(1)
                )
                tapCount += 1
                events.put(JSONObject()
                    .put("phase", "tap_primary_tab_surface_entry")
                    .put("tabLabel", tabLabel)
                    .put("category", category)
                    .put("label", label)
                    .put("success", tap.success)
                    .put("message", tap.reason)
                    .put("fingerprint", fingerprint)
                    .put("beforeScreenKey", beforeKey)
                    .put("candidate", candidate))
                sleep(800L)
                reactiveRules.dismissBlockingOverlays(packageName, "primary_tab_surface_after_tap", maxPasses = 2)
                val after = captureScreenWithRetry(
                    packageName = packageName,
                    source = "app_ui_after_primary_tab_surface_entry",
                    note = "$reason:$appName:tab_surface_after:$tabLabel:$category:$label",
                    forceScreenshot = forceScreenshots,
                    events = events,
                    phase = "capture_after_primary_tab_surface_entry"
                )
                val change = screenChange(before ?: tabScreen, after)
                val afterKey = after?.let { screenGraphKey(it) }.orEmpty()
                val changed = change.changed
                if (after != null && afterKey.isNotBlank()) {
                    val isNewScreen = screensByKey.putIfAbsent(afterKey, after) == null
                    edges.put(JSONObject()
                        .put("fromScreenKey", beforeKey)
                        .put("toScreenKey", afterKey)
                        .put("action", JSONObject()
                            .put("type", "TapPrimaryTabSurfaceEntry")
                            .put("category", "primary_tab_surface_entry")
                            .put("sourceCategory", category)
                            .put("tabLabel", tabLabel)
                            .put("label", label)
                            .put("x", candidate.optDouble("x"))
                            .put("y", candidate.optDouble("y")))
                        .put("changed", changed)
                        .put("isNewScreen", isNewScreen)
                        .put("beforeSnapshotPath", before?.optString("snapshotPath") ?: JSONObject.NULL)
                        .put("afterSnapshotPath", after.optString("snapshotPath"))
                        .put("beforeNodeCount", before?.optInt("nodeCount") ?: 0)
                        .put("afterNodeCount", after.optInt("nodeCount"))
                        .put("depth", 1)
                        .put("phase", "primary_tab_surface_probe")
                        .put("changeScore", change.score)
                        .put("changeConfidence", change.confidence)
                        .put("changeReason", change.reason))
                    event(
                        "primary_tab_surface_entry",
                        if (changed) "SUCCESS" else "LOW_CONFIDENCE",
                        "tab=$tabLabel $category $label changed=$changed nodes=${after.optInt("nodeCount")}",
                        after.optString("snapshotPath")
                    )
                    probeMemory.record(fingerprint, changed)
                }
                if (after == null) {
                    probeMemory.record(fingerprint, false)
                    recoverIfOutsideTarget(packageName, "after_primary_tab_surface:$tabLabel:$label", events)
                }
                actions.back()
                sleep(450L)
            }
        }
        return PrimaryTabSurfaceProbeResult(tapCount)
    }

    private fun scoreTabSurfaceCandidate(candidate: JSONObject, tabLabel: String): Int {
        var score = scoreExploreCandidateV2(candidate)
        val label = candidate.optString("label")
        val haystack = "$tabLabel $label ${candidate.optString("resourceId")} ${candidate.optString("desc")}".lowercase(Locale.CHINA)
        val important = listOf(
            "游戏中心", "我的服务", "服务", "会员购", "购物", "订单", "分类", "全部",
            "关注", "动态", "加号", "发布", "投稿", "创作", "搜索", "设置", "更多",
            "game", "service", "mall", "shop", "order", "category", "follow", "dynamic", "search", "setting", "more"
        )
        important.forEach { keyword ->
            if (haystack.contains(keyword.lowercase(Locale.CHINA))) score += 12
        }
        if (isMineTabLabel(tabLabel) && (haystack.contains("\u6e38\u620f") || haystack.contains("\u670d\u52a1") || haystack.contains("game") || haystack.contains("service"))) {
            score += 30
        }
        if (isMallTabLabel(tabLabel) && (haystack.contains("\u5206\u7c7b") || haystack.contains("\u8ba2\u5355") || haystack.contains("\u5168\u90e8") || haystack.contains("\u8d2d"))) {
            score += 24
        }
        if (isFollowTabLabel(tabLabel) && (haystack.contains("\u52a8\u6001") || haystack.contains("\u5173\u6ce8") || haystack.contains("\u52a0") || haystack.contains("+"))) {
            score += 20
        }
        val y = candidate.optDouble("y", 0.5)
        if (y in 0.12..0.82) score += 4
        if (candidate.optString("candidateCategory") == "graphical_ui") score += 5
        if (candidate.optString("candidateCategory") == "center_action") score += 8
        return score
    }

    private fun tabSurfaceCandidates(screen: JSONObject, tabLabel: String): List<JSONObject> {
        val candidates = safeExploreCandidates(screen, includeBottomTabs = false)
        val synthetic = mutableListOf<JSONObject>()
        val labels = screen.optJSONArray("labels").asStringList()
        val haystack = labels.joinToString(" ")
        if (isMineTabLabel(tabLabel) || haystack.contains("\u6e38\u620f\u4e2d\u5fc3") || haystack.contains("\u6211\u7684\u670d\u52a1")) {
            synthetic += keywordSurfaceCandidate(screen, "\u6e38\u620f\u4e2d\u5fc3", 0.22, 0.56, "mine_service_grid", "游戏中心")
            synthetic += keywordSurfaceCandidate(screen, "\u6211\u7684\u670d\u52a1", 0.50, 0.68, "mine_service_grid", "我的服务")
            synthetic += keywordSurfaceCandidate(screen, "\u8bbe\u7f6e", 0.92, 0.08, "mine_top_icon")
        }
        if (isMallTabLabel(tabLabel) || haystack.contains("\u4f1a\u5458\u8d2d")) {
            synthetic += keywordSurfaceCandidate(screen, "\u4f1a\u5458\u8d2d\u5206\u7c7b", 0.18, 0.24, "mall_graphical_grid", "会员购分类")
            synthetic += keywordSurfaceCandidate(screen, "\u5168\u90e8\u5206\u7c7b", 0.50, 0.24, "mall_graphical_grid", "全部分类")
            synthetic += keywordSurfaceCandidate(screen, "\u8ba2\u5355", 0.86, 0.12, "mall_top_icon")
        }
        if (isFollowTabLabel(tabLabel) || haystack.contains("\u5173\u6ce8")) {
            synthetic += keywordSurfaceCandidate(screen, "\u5173\u6ce8\u52a0\u53f7", 0.90, 0.10, "follow_top_icon", "关注+")
            synthetic += keywordSurfaceCandidate(screen, "\u52a8\u6001", 0.22, 0.18, "follow_surface_entry", "动态")
        }
        return (synthetic + candidates).dedupeCandidatesByPosition()
    }

    private fun tabSurfaceProbeLimit(tabLabel: String): Int {
        return when {
            isMineTabLabel(tabLabel) -> 12
            isMallTabLabel(tabLabel) -> 10
            isFollowTabLabel(tabLabel) -> 10
            else -> 8
        }
    }

    private fun keywordSurfaceCandidate(
        screen: JSONObject,
        label: String,
        fallbackX: Double,
        fallbackY: Double,
        category: String,
        displayLabel: String = label
    ): JSONObject {
        val match = findCandidateByKeyword(screen, label)
        val x = match?.optDouble("x", fallbackX) ?: fallbackX
        val y = match?.optDouble("y", fallbackY) ?: fallbackY
        return JSONObject(match?.toString() ?: "{}")
            .put("label", displayLabel)
            .put("x", x.coerceIn(0.0, 1.0))
            .put("y", y.coerceIn(0.0, 1.0))
            .put("center", JSONObject().put("x", x.coerceIn(0.0, 1.0)).put("y", y.coerceIn(0.0, 1.0)))
            .put("candidateCategory", "graphical_ui")
            .put("surfaceTarget", category)
            .put("coordinateFallback", match == null)
            .put("tags", JSONArray(listOf("graphical_ui", "surface_target", category)))
    }

    private fun findCandidateByKeyword(screen: JSONObject, keyword: String): JSONObject? {
        val arrays = listOf(
            screen.optJSONArray("graphicalCandidates"),
            screen.optJSONArray("entryCandidates"),
            screen.optJSONArray("clickTargets"),
            screen.optJSONArray("topEntries"),
            screen.optJSONArray("sideEntries")
        )
        val normalizedKeyword = keyword.lowercase(Locale.CHINA)
        for (array in arrays) {
            if (array == null) continue
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val haystack = listOf(
                    item.optString("label"),
                    item.optString("text"),
                    item.optString("desc"),
                    item.optString("resourceId")
                ).joinToString(" ").lowercase(Locale.CHINA)
                if (haystack.contains(normalizedKeyword)) {
                    return item
                }
            }
        }
        return null
    }

    private fun isMineTabLabel(label: String): Boolean {
        return label.contains("\u6211\u7684") ||
            label.contains("\u6211,") ||
            label.equals("\u6211", ignoreCase = true) ||
            label.contains("mine", ignoreCase = true) ||
            label.contains("profile", ignoreCase = true)
    }

    private fun isMallTabLabel(label: String): Boolean {
        return label.contains("\u4f1a\u5458\u8d2d") ||
            label.contains("\u5546\u57ce") ||
            label.contains("\u8d2d") ||
            label.contains("mall", ignoreCase = true) ||
            label.contains("shop", ignoreCase = true)
    }

    private fun isFollowTabLabel(label: String): Boolean {
        return label.contains("\u5173\u6ce8") ||
            label.contains("\u52a8\u6001") ||
            label.contains("follow", ignoreCase = true) ||
            label.contains("dynamic", ignoreCase = true)
    }

    private fun isExternalAuthCandidate(candidate: JSONObject, packageName: String): Boolean {
        val haystack = listOf(
            candidate.optString("label"),
            candidate.optString("text"),
            candidate.optString("desc"),
            candidate.optString("resourceId")
        ).joinToString(" ").lowercase(Locale.CHINA)
        if (packageName.contains("qq", ignoreCase = true) || packageName == "com.tencent.mobileqq") {
            return false
        }
        val externalAuthKeywords = listOf(
            "qq", "wechat", "weixin", "wx", "login with", "sign in with",
            "\u5fae\u4fe1\u767b\u5f55", "\u0051\u0051\u767b\u5f55", "\u6388\u6743\u767b\u5f55", "\u7b2c\u4e09\u65b9\u767b\u5f55",
            "\u5feb\u901f\u767b\u5f55", "\u767b\u5f55\u002f\u6ce8\u518c"
        )
        return externalAuthKeywords.any { keyword -> haystack.contains(keyword.lowercase(Locale.CHINA)) }
    }

    private fun recoverIfOutsideTarget(packageName: String, phase: String, events: JSONArray): Boolean {
        val state = inspector.currentState()
        val currentPackage = state?.appPackage
        if (currentPackage == null || currentPackage == packageName) return false
        events.put(JSONObject()
            .put("phase", "left_target_app")
            .put("status", "RECOVERING")
            .put("fromPhase", phase)
            .put("expectedPackage", packageName)
            .put("currentPackage", currentPackage)
            .put("windowClassName", state.windowClassName ?: JSONObject.NULL)
            .put("message", "tap opened another app; returning to target app and not counting it as child UI"))
        val recovered = openAndWaitForTarget(packageName, events)
        events.put(JSONObject()
            .put("phase", "left_target_app_recover")
            .put("status", if (recovered.success) "SUCCESS" else "FAILED")
            .put("fromPhase", phase)
            .put("currentPackage", recovered.currentPackage ?: JSONObject.NULL)
            .put("message", recovered.message))
        return recovered.success
    }

    private fun quickRestoreTarget(packageName: String, events: JSONArray, phase: String): WaitForegroundResult {
        val first = actions.openAppLauncherRoot(packageName)
        events.put(JSONObject()
            .put("phase", "quick_restore_target")
            .put("fromPhase", phase)
            .put("strategy", "launcher_root")
            .put("success", first.success)
            .put("message", first.reason))
        var result = if (first.success) {
            waitForTargetPackage(packageName, 2_500L)
        } else {
            val state = inspector.currentState()
            WaitForegroundResult(false, state?.appPackage, state?.windowClassName, 0, JSONArray(), first.reason)
        }
        if (!result.success) {
            val second = actions.openAppExplicitLauncher(packageName)
            events.put(JSONObject()
                .put("phase", "quick_restore_target")
                .put("fromPhase", phase)
                .put("strategy", "explicit_launcher_activity")
                .put("success", second.success)
                .put("message", second.reason))
            if (second.success) result = waitForTargetPackage(packageName, 3_500L)
        }
        events.put(JSONObject()
            .put("phase", "quick_restore_target_result")
            .put("fromPhase", phase)
            .put("success", result.success)
            .put("currentPackage", result.currentPackage ?: JSONObject.NULL)
            .put("windowClassName", result.windowClassName ?: JSONObject.NULL)
            .put("message", result.message))
        return result
    }

    private fun primaryBottomTabCandidates(screen: JSONObject): List<JSONObject> {
        val array = screen.optJSONArray("primaryBottomTabs")
            ?: screen.optJSONArray("bottomTabs")
            ?: return emptyList()
        val all = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val label = item.optString("label").trim()
            val x = item.optDouble("x", item.optJSONObject("center")?.optDouble("x", -1.0) ?: -1.0)
            val y = item.optDouble("y", item.optJSONObject("center")?.optDouble("y", -1.0) ?: -1.0)
            if (x !in 0.0..1.0 || y !in 0.0..1.0) continue
            if (y < 0.82) continue
            if (isDangerousExploreLabel(label)) continue
            if (isCenterPublishTabCandidate(item)) continue
            if (!isLikelyPrimaryBottomTabItem(item)) continue
            all += JSONObject(item.toString())
                .put("label", label.ifBlank { "bottom_tab_${i + 1}" })
                .put("x", x)
                .put("y", y)
                .put("candidateCategory", "primary_bottom_tab")
        }
        return all
            .groupBy { (it.optDouble("x") * 14).toInt() }
            .values
            .map { group ->
                group.maxWith(compareBy<JSONObject> { scorePrimaryBottomTab(it) }
                    .thenByDescending { it.optString("label").isNotBlank() })
            }
            .sortedBy { it.optDouble("x") }
            .take(8)
    }

    private fun isCenterPublishTabCandidate(candidate: JSONObject): Boolean {
        val haystack = listOf(
            candidate.optString("label"),
            candidate.optString("text"),
            candidate.optString("desc"),
            candidate.optString("resourceId")
        ).joinToString(" ").lowercase(Locale.CHINA)
        if (haystack.contains("home_publish_icon")) return true
        if (haystack.contains("publish") || haystack.contains("create")) return true
        if (haystack.contains("发布") || haystack.contains("投稿") || haystack.contains("创作") || haystack.contains("加号")) return true
        val x = candidate.optDouble("x", candidate.optJSONObject("center")?.optDouble("x", -1.0) ?: -1.0)
        val labelBlank = candidate.optString("label").isBlank() &&
            candidate.optString("text").isBlank() &&
            candidate.optString("desc").isBlank()
        return labelBlank && x in 0.43..0.57
    }

    private fun bottomGridFallbackTabs(screen: JSONObject, packageName: String, existingCount: Int): List<JSONObject> {
        if (existingCount >= 2) return emptyList()
        val width = screen.optInt("screenWidth", 0)
        val height = screen.optInt("screenHeight", 0)
        if (width <= 0 || height <= 0) return emptyList()
        if (isPinduoduoPackage(packageName)) {
            val labels = listOf(
                "\u9996\u9875",
                "\u591a\u591a\u89c6\u9891",
                "\u63d0\u524d\u4f18\u60e0",
                "\u804a\u5929",
                "\u4e2a\u4eba\u4e2d\u5fc3"
            )
            val slots = listOf(0.10, 0.30, 0.50, 0.70, 0.90)
            return slots.mapIndexed { index, x ->
                JSONObject()
                    .put("index", index)
                    .put("label", labels.getOrElse(index) { "bottom_tab_${index + 1}" })
                    .put("text", JSONObject.NULL)
                    .put("desc", JSONObject.NULL)
                    .put("resourceId", JSONObject.NULL)
                    .put("class", "coordinate_fallback_primary_bottom_tab")
                    .put("clickable", false)
                    .put("editable", false)
                    .put("checkable", false)
                    .put("selected", false)
                    .put("bounds", "coordinate_fallback")
                    .put("x", x)
                    .put("y", 0.94)
                    .put("center", JSONObject().put("x", x).put("y", 0.94))
                    .put("candidateCategory", "primary_bottom_tab")
                    .put("coordinateFallback", true)
                    .put("tags", JSONArray(listOf("primary_bottom_tab", "bottom_tab", "coordinate_fallback")))
            }
        }
        val labels = if (packageName == "tv.danmaku.bili" || packageName.contains("bili", ignoreCase = true)) {
            listOf("首页", "动态/关注", "发布", "会员购", "我的")
        } else {
            listOf("tab_1", "tab_2", "tab_3", "tab_4", "tab_5")
        }
        val slots = listOf(0.10, 0.30, 0.50, 0.70, 0.90)
        return slots.mapIndexed { index, x ->
            JSONObject()
                .put("index", index)
                .put("label", labels.getOrElse(index) { "bottom_tab_${index + 1}" })
                .put("text", JSONObject.NULL)
                .put("desc", JSONObject.NULL)
                .put("resourceId", JSONObject.NULL)
                .put("class", "coordinate_fallback_primary_bottom_tab")
                .put("clickable", false)
                .put("editable", false)
                .put("checkable", false)
                .put("selected", false)
                .put("bounds", "coordinate_fallback")
                .put("x", x)
                .put("y", 0.94)
                .put("center", JSONObject().put("x", x).put("y", 0.94))
                .put("candidateCategory", "primary_bottom_tab")
                .put("coordinateFallback", true)
                .put("tags", JSONArray(listOf("primary_bottom_tab", "bottom_tab", "coordinate_fallback")))
        }
    }

    private fun scorePrimaryBottomTab(candidate: JSONObject): Int {
        val label = candidate.optString("label")
        val keywords = listOf(
            "\u9996\u9875", "\u52a8\u6001", "\u5173\u6ce8", "\u4f1a\u5458\u8d2d",
            "\u6211\u7684", "\u6211", "\u53d1\u73b0", "\u6d88\u606f", "\u8d2d\u7269",
            "\u8ba2\u5355", "\u8d26\u53f7", "\u4e2a\u4eba", "home", "follow",
            "dynamic", "mall", "mine", "profile", "me"
        )
        var score = 0
        keywords.forEach { keyword ->
            if (label.contains(keyword, ignoreCase = true)) score += 6
        }
        if (candidate.optBoolean("selected")) score += 2
        val y = candidate.optDouble("y", 0.0)
        if (y >= 0.82) score += 4
        if (label.isNotBlank()) score += 2
        return score
    }

    private fun normalizePrimaryTabsForPackage(tabs: List<JSONObject>, packageName: String): List<JSONObject> {
        if (!isPinduoduoPackage(packageName)) return tabs
        return tabs.sortedBy { it.optDouble("x", 0.5) }.map { tab ->
            val x = tab.optDouble("x", tab.optJSONObject("center")?.optDouble("x", 0.5) ?: 0.5)
            val label = tab.optString("label")
            val normalized = pinduoduoBottomTabLabel(x)
            if (label.isBlank() || label.startsWith("bottom_tab") || label.startsWith("tab_")) {
                JSONObject(tab.toString()).put("label", normalized)
            } else {
                tab
            }
        }
    }

    private fun pinduoduoBottomTabLabel(x: Double): String {
        return when {
            x < 0.20 -> "\u9996\u9875"
            x < 0.40 -> "\u591a\u591a\u89c6\u9891"
            x < 0.62 -> "\u63d0\u524d\u4f18\u60e0"
            x < 0.82 -> "\u804a\u5929"
            else -> "\u4e2a\u4eba\u4e2d\u5fc3"
        }
    }

    private fun shouldSkipPrimaryTabSurfaceProbe(packageName: String, tabLabel: String, tabScreen: JSONObject): Boolean {
        if (!isPinduoduoPackage(packageName)) return false
        val label = tabLabel.lowercase(Locale.CHINA)
        val pddFeedTab = label.contains("\u591a\u591a\u89c6\u9891") ||
            label.contains("\u89c6\u9891") ||
            label.contains("\u76f4\u64ad") ||
            label.contains("\u77ed\u5267")
        val videoLikeSurface = (tabScreen.optJSONArray("videoCandidates")?.length() ?: 0) > 0 &&
            (tabScreen.optJSONArray("sideEntries")?.length() ?: 0) >= 2
        return pddFeedTab || videoLikeSurface
    }

    private fun isPinduoduoPackage(packageName: String?): Boolean {
        return packageName.orEmpty().contains("pinduoduo", ignoreCase = true) ||
            packageName == "com.xunmeng.pinduoduo"
    }

    private fun primaryBottomTabCandidates(state: UiStateSample): List<JSONObject> {
        val minY = state.screenHeight * 0.82f
        val maxHeight = (state.screenHeight * 0.11f).toInt().coerceAtLeast(12)
        val maxWidth = (state.screenWidth * 0.30f).toInt().coerceAtLeast(24)
        return state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    !isSystemDecorationNode(node) &&
                    node.bounds.centerY() >= minY &&
                    node.bounds.width() in 8..maxWidth &&
                    node.bounds.height() in 8..maxHeight &&
                    (node.clickable || node.selected || nodeLabel(node).isNotBlank() || looksLikeBottomNavigationIconNode(node, state))
            }
            .filterNot { node -> isDangerousExploreLabel(nodeLabel(node)) }
            .filter { node -> isLikelyPrimaryBottomTabNode(node, state) }
            .sortedBy { it.bounds.centerX() }
            .mapIndexed { index, node ->
                val semanticLabel = when {
                    looksLikePrimaryTabLabel(nodeLabel(node)) -> nodeLabel(node)
                    isMineTabLabel(nodeLabel(node)) -> "我的"
                    isMallTabLabel(nodeLabel(node)) -> "会员购"
                    isFollowTabLabel(nodeLabel(node)) -> "关注"
                    else -> null
                }
                nodeJson(index, node, state, listOf("primary_bottom_tab", "bottom_tab") + tagsFor(node, state), semanticLabel)
            }
            .groupBy { (it.optDouble("x") * 14).toInt() }
            .values
            .map { group ->
                group.maxWith(compareBy<JSONObject> { scorePrimaryBottomTab(it) }
                    .thenByDescending { it.optString("label").isNotBlank() })
            }
            .sortedBy { it.optDouble("x") }
            .take(8)
    }

    private fun looksLikePrimaryTabLabel(label: String): Boolean {
        if (label.isBlank()) return false
        if (isLikelyContentCardLabel(label)) return false
        val keywords = listOf(
            "\u9996\u9875", "\u52a8\u6001", "\u5173\u6ce8", "\u4f1a\u5458\u8d2d",
            "\u6211\u7684", "\u6211", "\u53d1\u73b0", "\u901a\u8baf\u5f55",
            "\u6d88\u606f", "\u8d2d\u7269", "\u8ba2\u5355", "\u4e2a\u4eba",
            "\u5e7f\u573a", "\u9891\u9053", "\u793e\u533a", "\u5546\u57ce",
            "home", "follow", "dynamic", "mall", "mine", "profile", "me"
        )
        return keywords.any { label.contains(it, ignoreCase = true) }
    }

    private fun isLikelyPrimaryBottomTabItem(item: JSONObject): Boolean {
        val label = item.optString("label").trim()
        val resourceId = item.optString("resourceId").lowercase(Locale.US)
        val y = item.optDouble("y", item.optJSONObject("center")?.optDouble("y", -1.0) ?: -1.0)
        val tags = item.optJSONArray("tags")?.toString().orEmpty()
        if (y < 0.82) return false
        if (isLikelyContentCardLabel(label)) return false
        if (looksLikePrimaryTabLabel(label)) return true
        if (item.optBoolean("selected") && label.length <= 16) return true
        if (tags.contains("primary_bottom_tab") && label.length <= 16) return true
        return resourceId.contains("tab") ||
            resourceId.contains("bottom") ||
            resourceId.contains("navigation") ||
            resourceId.contains("main_") ||
            resourceId.contains("homepage")
    }

    private fun isLikelyPrimaryBottomTabNode(node: NodeSample, state: UiStateSample): Boolean {
        val label = nodeLabel(node).trim()
        val resourceId = node.resourceId.orEmpty().lowercase(Locale.US)
        val centerYRatio = node.bounds.centerY().toDouble() / state.screenHeight.coerceAtLeast(1)
        if (centerYRatio < 0.82) return false
        if (isLikelyContentCardLabel(label)) return false
        if (looksLikePrimaryTabLabel(label)) return true
        if (node.selected && label.length <= 16) return true
        val resourceLooksLikeNav = resourceId.contains("tab") ||
            resourceId.contains("bottom") ||
            resourceId.contains("navigation") ||
            resourceId.contains("main_") ||
            resourceId.contains("homepage")
        return (resourceLooksLikeNav && label.length <= 16) || looksLikeBottomNavigationIconNode(node, state)
    }

    private fun isLikelyContentCardLabel(label: String): Boolean {
        val normalized = label.trim()
        if (normalized.isBlank()) return false
        val lower = normalized.lowercase(Locale.CHINA)
        if (Regex("""\b\d{1,2}:\d{2}\b""").containsMatchIn(normalized)) return true
        if (Regex("""\b\d+(\.\d+)?\s*(万|w|k)?\s*(播放|评论|点赞|弹幕)\b""").containsMatchIn(lower)) return true
        val noisyWords = listOf(
            "\u5c0f\u65f6\u524d", "\u5206\u949f\u524d", "\u6628\u5929", "\u521a\u521a",
            "\u64ad\u653e", "\u5f39\u5e55", "\u8bc4\u8bba", "\u7a3f\u4ef6", "\u89c6\u9891",
            "\u76f4\u64ad", "\u4e13\u680f", "\u7b14\u8bb0", "\u70ed\u8bc4", "\u5df2\u5173\u6ce8",
            "views", "replies", "comments", "followers"
        )
        if (noisyWords.any { lower.contains(it.lowercase(Locale.CHINA)) }) return true
        if (normalized.length > 16 && !looksLikePrimaryTabKeywordOnly(normalized)) return true
        val punctuationCount = normalized.count { it in listOf(' ', ',', '.', '，', '。', '！', '!', '?', '？', '：', ':', '/', '|') }
        return normalized.length > 10 && punctuationCount >= 2
    }

    private fun looksLikePrimaryTabKeywordOnly(label: String): Boolean {
        val compact = label.replace(" ", "").replace("/", "")
        val exact = setOf(
            "\u9996\u9875", "\u52a8\u6001", "\u5173\u6ce8", "\u52a8\u6001\u5173\u6ce8",
            "\u53d1\u5e03", "\u4f1a\u5458\u8d2d", "\u6211\u7684", "\u6211", "\u53d1\u73b0",
            "\u901a\u8baf\u5f55", "\u6d88\u606f", "\u8d2d\u7269", "\u8ba2\u5355", "\u4e2a\u4eba",
            "\u5e7f\u573a", "\u9891\u9053", "\u793e\u533a", "\u5546\u57ce"
        )
        return exact.any { compact.equals(it, ignoreCase = true) } ||
            compact.equals("home", ignoreCase = true) ||
            compact.equals("follow", ignoreCase = true) ||
            compact.equals("dynamic", ignoreCase = true) ||
            compact.equals("mall", ignoreCase = true) ||
            compact.equals("mine", ignoreCase = true) ||
            compact.equals("me", ignoreCase = true) ||
            compact.equals("profile", ignoreCase = true)
    }

    private fun graphQuality(screens: List<JSONObject>, edges: JSONArray): JSONObject {
        val screenCount = screens.size
        val nodeCount = screens.sumOf { it.optInt("nodeCount") }
        val clickableCount = screens.sumOf { it.optInt("clickableCount") }
        val editableCount = screens.sumOf { it.optInt("editableCount") }
        val bottomTabCount = screens.sumOf { it.optJSONArray("bottomTabs")?.length() ?: 0 }
        val topEntryCount = screens.sumOf { it.optJSONArray("topEntries")?.length() ?: 0 }
        val sideEntryCount = screens.sumOf { it.optJSONArray("sideEntries")?.length() ?: 0 }
        val entryCount = screens.sumOf { it.optJSONArray("entryCandidates")?.length() ?: 0 }
        val inputCount = screens.sumOf { it.optJSONArray("inputCandidates")?.length() ?: 0 }
        val videoCount = screens.sumOf { it.optJSONArray("videoCandidates")?.length() ?: 0 }
        val clickTargetCount = screens.sumOf { it.optJSONArray("clickTargets")?.length() ?: 0 }
        val graphicalCount = screens.sumOf { it.optJSONArray("graphicalCandidates")?.length() ?: 0 }
        val checkableCount = screens.sumOf { it.optJSONArray("checkableCandidates")?.length() ?: 0 }
        val labelCount = screens.sumOf { it.optJSONArray("labels")?.length() ?: 0 }
        val candidateCount = bottomTabCount + topEntryCount + sideEntryCount + entryCount + inputCount + videoCount + clickTargetCount + graphicalCount + checkableCount
        val primaryBottomTabEdges = mutableListOf<JSONObject>()
        val changedPrimaryBottomTabEdges = mutableListOf<JSONObject>()
        val primaryTabSurfaceEntriesByTab = linkedMapOf<String, MutableList<String>>()
        var changedPrimaryTabSurfaceEntries = 0
        val categoryCounts = mutableMapOf<String, Int>()
        var changedEdges = 0
        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            val action = edge.optJSONObject("action") ?: JSONObject()
            val category = action.optString("category").ifBlank { action.optString("type").ifBlank { "unknown" } }
            categoryCounts[category] = (categoryCounts[category] ?: 0) + 1
            if (edge.optBoolean("changed")) changedEdges += 1
            if (category == "primary_bottom_tab") {
                primaryBottomTabEdges += edge
                if (edge.optBoolean("changed")) changedPrimaryBottomTabEdges += edge
            }
            if (category == "primary_tab_surface_entry") {
                val tabLabel = action.optString("tabLabel").ifBlank { "unknown_tab" }
                val label = action.optString("label").ifBlank { action.optString("sourceCategory").ifBlank { "entry" } }
                primaryTabSurfaceEntriesByTab.getOrPut(tabLabel) { mutableListOf() }.add(label)
                if (edge.optBoolean("changed")) changedPrimaryTabSurfaceEntries += 1
            }
        }
        val openedPrimaryLabels = primaryBottomTabEdges
            .mapNotNull { it.optJSONObject("action")?.optString("label")?.takeIf { label -> label.isNotBlank() } }
            .distinct()
        val changedPrimaryLabels = changedPrimaryBottomTabEdges
            .mapNotNull { it.optJSONObject("action")?.optString("label")?.takeIf { label -> label.isNotBlank() } }
            .distinct()
        val hasNavigation = edges.length() > 0 || bottomTabCount > 0 || topEntryCount > 0 || sideEntryCount > 0
        val richExploration = screenCount >= 4 && changedEdges >= 3 && candidateCount >= 80 && labelCount >= 40
        val primaryTabsGoodEnough = changedPrimaryBottomTabEdges.size >= 2 ||
            bottomTabCount < 2 ||
            richExploration
        val usable = screenCount >= 1 &&
            nodeCount >= 20 &&
            labelCount >= 3 &&
            (clickableCount >= 3 || candidateCount >= 2 || editableCount > 0) &&
            hasNavigation &&
            primaryTabsGoodEnough
        val reasons = buildList {
            if (screenCount < 1) add("no_screen")
            if (nodeCount < 20) add("too_few_nodes")
            if (labelCount < 3) add("too_few_visible_labels")
            if (clickableCount < 3 && candidateCount < 2 && editableCount <= 0) add("too_few_actionable_candidates")
            if (!hasNavigation) add("no_navigation_or_child_page_signal")
            if (!primaryTabsGoodEnough) add("primary_bottom_tabs_not_opened")
        }
        return JSONObject()
            .put("usable", usable)
            .put("reason", if (usable) "usable_app_ui_graph" else reasons.joinToString(",").ifBlank { "low_signal" })
            .put("qualityGate", JSONObject()
                .put("bottomPrimaryTabsRequired", bottomTabCount >= 2)
                .put("bottomPrimaryTabsAttempted", primaryBottomTabEdges.size)
                .put("bottomPrimaryTabsOpened", changedPrimaryBottomTabEdges.size)
                .put("richExplorationAccepted", richExploration)
                .put("topIconsAreNotEnough", true)
                .put("repeatedMessagePageIsNotProgress", true))
            .put("screenCount", screenCount)
            .put("edgeCount", edges.length())
            .put("nodeCount", nodeCount)
            .put("clickableCount", clickableCount)
            .put("editableCount", editableCount)
            .put("labelCount", labelCount)
            .put("bottomTabCount", bottomTabCount)
            .put("topEntryCount", topEntryCount)
            .put("sideEntryCount", sideEntryCount)
            .put("entryCandidateCount", entryCount)
            .put("inputCandidateCount", inputCount)
            .put("videoCandidateCount", videoCount)
            .put("clickTargetCount", clickTargetCount)
            .put("graphicalCandidateCount", graphicalCount)
            .put("checkableCandidateCount", checkableCount)
            .put("candidateCount", candidateCount)
            .put("changedEdgeCount", changedEdges)
            .put("edgeCategoryCounts", JSONObject(categoryCounts.mapValues { it.value }))
            .put("primaryBottomTabAttemptedCount", primaryBottomTabEdges.size)
            .put("primaryBottomTabAttemptedLabels", JSONArray(openedPrimaryLabels))
            .put("primaryBottomTabOpenedCount", changedPrimaryBottomTabEdges.size)
            .put("primaryBottomTabOpenedLabels", JSONArray(changedPrimaryLabels))
            .put("primaryTabSurfaceEntryChangedCount", changedPrimaryTabSurfaceEntries)
            .put("primaryTabSurfaceEntriesByTab", JSONObject(primaryTabSurfaceEntriesByTab.mapValues { (_, labels) ->
                JSONArray(labels.distinct().take(20))
            }))
    }

    private fun recoverToPrimaryTabSurface(
        packageName: String,
        appName: String,
        reason: String,
        current: JSONObject,
        forceScreenshots: Boolean,
        events: JSONArray
    ): JSONObject? {
        var best: JSONObject? = current.takeIf {
            (it.optJSONArray("primaryBottomTabs")?.length() ?: 0) >= 2
        }
        val currentCount = current.optJSONArray("primaryBottomTabs")?.length() ?: 0
        events.put(JSONObject()
            .put("phase", "recover_primary_tab_surface_start")
            .put("status", if (best != null) "ALREADY_ON_PRIMARY_TAB_SURFACE" else "RUNNING")
            .put("message", "Need bottom primary tabs before opening top/icon entries.")
            .put("screenKey", current.optString("screenKey"))
            .put("primaryBottomTabCount", currentCount)
            .put("snapshotPath", current.optString("snapshotPath", "")))
        if (best != null) return best

        for (attempt in 1..3) {
            val back = actions.back()
            events.put(JSONObject()
                .put("phase", "recover_primary_tab_back")
                .put("attempt", attempt)
                .put("success", back.success)
                .put("message", back.reason))
            sleep(650L)
            reactiveRules.dismissBlockingOverlays(packageName, "recover_primary_tab_back_$attempt", maxPasses = 2)
            val screen = captureScreenWithRetry(
                packageName = packageName,
                source = "app_ui_recover_primary_tab",
                note = "$reason:$appName:recover_primary_tab:attempt=$attempt",
                forceScreenshot = forceScreenshots,
                events = events,
                phase = "capture_recover_primary_tab_$attempt",
                timeoutMs = 2_500L
            )
            val tabCount = screen?.optJSONArray("primaryBottomTabs")?.length() ?: 0
            events.put(JSONObject()
                .put("phase", "recover_primary_tab_check")
                .put("attempt", attempt)
                .put("status", if (tabCount >= 2) "SUCCESS" else "LOW_CONFIDENCE")
                .put("nodeCount", screen?.optInt("nodeCount") ?: 0)
                .put("primaryBottomTabCount", tabCount)
                .put("screenKey", screen?.optString("screenKey") ?: JSONObject.NULL)
                .put("snapshotPath", screen?.optString("snapshotPath") ?: JSONObject.NULL))
            if (screen != null && tabCount >= 2) {
                best = screen
                break
            }
        }
        return best
    }

    private fun scoreExploreCandidate(candidate: JSONObject): Int {
        val label = candidate.optString("label")
        var score = 0
        val important = listOf("搜索", "发现", "消息", "我的", "首页", "发布", "发帖", "创作", "+", "写", "动态", "社区", "登录", "协议", "同意", "设置", "更多")
        important.forEach { if (label.contains(it, ignoreCase = true)) score += 5 }
        if (candidate.optJSONArray("tags")?.toString()?.contains("bottom_tab") == true) score += 4
        if (candidate.optJSONArray("tags")?.toString()?.contains("top_entry") == true) score += 3
        if (candidate.optJSONArray("tags")?.toString()?.contains("side_entry") == true) score += 3
        if (candidate.optJSONArray("tags")?.toString()?.contains("small_icon") == true) score += 2
        if (candidate.optJSONArray("tags")?.toString()?.contains("login_entry") == true) score += 4
        if (candidate.optJSONArray("tags")?.toString()?.contains("agreement") == true) score += 5
        if (candidate.optJSONArray("tags")?.toString()?.contains("checkable") == true) score += 5
        return score
    }

    private fun scoreExploreCandidateV2(candidate: JSONObject): Int {
        val label = candidate.optString("label")
        var score = 0
        val important = listOf(
            "search", "搜索", "发现", "消息", "我的", "首页", "发布", "创作", "+",
            "分类", "频道", "广场", "推荐", "登录", "协议", "同意", "设置", "更多"
        )
        important.forEach { if (label.contains(it, ignoreCase = true)) score += 5 }
        val tags = candidate.optJSONArray("tags")?.toString().orEmpty()
        if (tags.contains("bottom_tab")) score += 4
        if (tags.contains("top_entry")) score += 8
        if (tags.contains("side_entry")) score += 5
        if (tags.contains("graphical_ui")) score += 7
        if (tags.contains("login_entry")) score += 4
        if (tags.contains("agreement")) score += 4
        if (tags.contains("checkable")) score += 5
        if (tags.contains("video_candidate")) score += 4
        if (tags.contains("small_icon")) score += 2
        if (tags.contains("icon_only")) score += 4
        if (tags.contains("grid_icon")) score += 5
        if (tags.contains("coordinate_probe")) score += 1
        if (tags.contains("top_icon")) score += 4
        if (tags.contains("side_icon")) score += 3
        if (tags.contains("agreement")) score += 4
        if (tags.contains("entry_candidate")) score += 2
        if (tags.contains("input_candidate")) score += 2
        return score
    }

    private fun bottomCandidates(state: UiStateSample): List<JSONObject> {
        return primaryBottomTabCandidates(state).mapIndexed { index, tab ->
            JSONObject(tab.toString())
                .put("index", index)
                .put("candidateCategory", "bottom_tab")
        }
    }

    private fun topCandidates(state: UiStateSample): List<JSONObject> {
        val cutoff = state.screenHeight * 0.28f
        return state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    !isSystemDecorationNode(node) &&
                    node.bounds.centerY() <= cutoff &&
                    node.bounds.width() > 8 &&
                    node.bounds.height() > 8 &&
                    (node.clickable || nodeLabel(node).isNotBlank() || looksLikeIconOnlyNode(node, state))
            }
            .sortedBy { it.bounds.centerX() }
            .take(24)
            .mapIndexed { index, node ->
                val semanticLabel = when {
                    nodeLabel(node).contains("search", ignoreCase = true) || nodeLabel(node).contains("鎼滅储") -> "搜索"
                    nodeLabel(node).contains("+") || nodeLabel(node).contains("鍔犲彿") -> "加号"
                    nodeLabel(node).contains("setting", ignoreCase = true) || nodeLabel(node).contains("璁剧疆") -> "设置"
                    else -> null
                }
                nodeJson(index, node, state, listOf("top_entry", "top_entry_candidate"), semanticLabel)
            }
            .toList()
    }

    private fun sideCandidates(state: UiStateSample): List<JSONObject> {
        val leftCutoff = state.screenWidth * 0.14f
        val rightCutoff = state.screenWidth * 0.86f
        return state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    !isSystemDecorationNode(node) &&
                    node.bounds.width() > 8 &&
                    node.bounds.height() > 8 &&
                    (node.clickable || nodeLabel(node).isNotBlank() || looksLikeIconOnlyNode(node, state))
            }
            .filter { node -> node.bounds.centerX() <= leftCutoff || node.bounds.centerX() >= rightCutoff }
            .sortedBy { it.bounds.centerY() }
            .take(24)
            .mapIndexed { index, node -> nodeJson(index, node, state, listOf("side_entry", "side_entry_candidate")) }
            .toList()
    }

    private fun graphicalCandidates(state: UiStateSample): List<JSONObject> {
        val minY = state.screenHeight * 0.04f
        val maxY = state.screenHeight * 0.96f
        return state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    !isSystemDecorationNode(node) &&
                    (node.clickable || looksLikeIconOnlyNode(node, state) || looksLikeGridIconNode(node, state)) &&
                    node.bounds.width() in 10..(state.screenWidth * 0.26f).toInt().coerceAtLeast(10) &&
                    node.bounds.height() in 10..(state.screenHeight * 0.18f).toInt().coerceAtLeast(10) &&
                    node.bounds.centerY().toFloat() in minY..maxY
            }
            .filterNot { node -> isDangerousExploreLabel(nodeLabel(node)) }
            .sortedWith(
                compareBy<NodeSample> {
                    val label = nodeLabel(it)
                    if (label.isBlank()) 0 else 1
                }
                    .thenBy { it.bounds.centerY() }
                    .thenBy { it.bounds.centerX() }
            )
            .take(80)
            .mapIndexed { index, node ->
                val inferredTags = buildList {
                    add("graphical_ui")
                    add("small_icon")
                    if (!node.clickable) add("coordinate_probe")
                    if (nodeLabel(node).isBlank()) add("icon_only")
                    if (looksLikeGridIconNode(node, state)) add("grid_icon")
                }
                nodeJson(index, node, state, inferredTags + tagsFor(node, state))
            }
            .toList()
    }

    private fun checkableCandidates(state: UiStateSample): List<JSONObject> {
        return state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    !isSystemDecorationNode(node) &&
                    node.checkable &&
                    node.bounds.width() > 8 &&
                    node.bounds.height() > 8
            }
            .sortedWith(compareBy<NodeSample> { it.bounds.centerY() }.thenBy { it.bounds.centerX() })
            .take(40)
            .mapIndexed { index, node ->
                nodeJson(index, node, state, listOf("checkable", "agreement", "login_entry") + tagsFor(node, state))
            }
            .toList()
    }

    private fun entryCandidates(state: UiStateSample): List<JSONObject> {
        val aliases = listOf(
            "搜索", "搜", "发现", "发布", "发帖", "发表", "创作", "写", "消息", "+", "加号", "动态", "朋友圈", "社区",
            "登录", "登陆", "账号", "账户", "密码", "验证码", "手机号",
            "post", "publish", "search", "create", "login", "log in", "sign in", "password", "qq", "quick login", "quicklogin", "agree", "agreement", "checkbox", "check box", "勾选", "同意协议"
        )
        return state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    !isSystemDecorationNode(node) &&
                    node.bounds.width() > 8 &&
                    node.bounds.height() > 8 &&
                    (node.clickable || nodeLabel(node).isNotBlank())
            }
            .filter { node ->
                val label = nodeLabel(node)
                label.isNotBlank() && aliases.any { alias -> label.contains(alias, ignoreCase = true) }
            }
            .sortedWith(compareBy<NodeSample> { it.bounds.centerY() }.thenBy { it.bounds.centerX() })
            .take(40)
            .mapIndexed { index, node -> nodeJson(index, node, state, listOf("entry_candidate") + tagsFor(node, state)) }
            .toList()
    }

    private fun inputCandidates(state: UiStateSample): List<JSONObject> {
        return state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    !isSystemDecorationNode(node) &&
                    (node.editable || node.className.orEmpty().contains("EditText", ignoreCase = true)) &&
                    node.bounds.width() > 20 &&
                    node.bounds.height() > 12
            }
            .sortedWith(compareBy<NodeSample> { it.bounds.centerY() }.thenBy { it.bounds.centerX() })
            .take(20)
            .mapIndexed { index, node -> nodeJson(index, node, state, listOf("input_candidate") + tagsFor(node, state)) }
            .toList()
    }

    private fun clickTargets(state: UiStateSample): List<JSONObject> {
        return state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    !isSystemDecorationNode(node) &&
                    node.bounds.width() > 8 &&
                    node.bounds.height() > 8 &&
                    (node.clickable || nodeLabel(node).isNotBlank())
            }
            .filterNot { node -> isDangerousExploreLabel(nodeLabel(node)) }
            .sortedWith(compareBy<NodeSample> { it.bounds.centerY() }.thenBy { it.bounds.centerX() })
            .take(120)
            .mapIndexed { index, node -> nodeJson(index, node, state, tagsFor(node, state)) }
            .toList()
    }

    private fun videoCandidates(state: UiStateSample): List<JSONObject> {
        return state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    !isSystemDecorationNode(node) &&
                    node.bounds.width() > 12 &&
                    node.bounds.height() > 12 &&
                    (node.clickable || nodeLabel(node).isNotBlank())
            }
            .filter { node ->
                val label = nodeLabel(node).lowercase(Locale.CHINA)
                label.contains("video") ||
                    label.contains("视频") ||
                    label.contains("播放") ||
                    label.contains("推荐") ||
                    label.contains("cover") ||
                    label.contains("player") ||
                    label.contains("弹幕")
            }
            .sortedWith(compareBy<NodeSample> { it.bounds.centerY() }.thenBy { it.bounds.centerX() })
            .take(40)
            .mapIndexed { index, node -> nodeJson(index, node, state, listOf("video_candidate") + tagsFor(node, state)) }
            .toList()
    }

    private fun nodeJson(
        index: Int,
        node: NodeSample,
        state: UiStateSample,
        tags: List<String>,
        labelOverride: String? = null
    ): JSONObject {
        val width = state.screenWidth.coerceAtLeast(1)
        val height = state.screenHeight.coerceAtLeast(1)
        val x = (node.bounds.centerX().toDouble() / width).coerceIn(0.0, 1.0)
        val y = (node.bounds.centerY().toDouble() / height).coerceIn(0.0, 1.0)
        return JSONObject()
            .put("index", index)
            .put("label", (labelOverride ?: resolvedNodeLabel(node, index, tags)).take(120))
            .put("text", node.text ?: JSONObject.NULL)
            .put("desc", node.contentDesc ?: JSONObject.NULL)
            .put("resourceId", node.resourceId ?: JSONObject.NULL)
            .put("class", node.className ?: JSONObject.NULL)
            .put("clickable", node.clickable)
            .put("editable", node.editable)
            .put("checkable", node.checkable)
            .put("selected", node.selected)
            .put("bounds", node.bounds.flattenToString())
            .put("x", x)
            .put("y", y)
            .put("center", JSONObject().put("x", x).put("y", y))
            .put("tags", JSONArray(tags.distinct()))
    }

    private fun resolvedNodeLabel(node: NodeSample, index: Int, tags: List<String>): String {
        val textLabel = listOfNotNull(node.text, node.contentDesc)
            .joinToString(" ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
        if (textLabel.isNotBlank()) return textLabel

        val resourceTail = node.resourceId.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast(':')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
        if (resourceTail.isNotBlank() && !isGenericResourceTail(resourceTail)) {
            return resourceTail
        }

        val prefix = when {
            tags.any { it == "primary_bottom_tab" || it == "bottom_tab" } -> "bottom_tab"
            tags.any { it == "top_entry" || it == "top_icon" } -> "top_entry"
            tags.any { it == "side_entry" || it == "side_icon" } -> "side_entry"
            tags.any { it == "graphical_ui" || it == "small_icon" } -> "graphical_ui"
            tags.any { it == "input_candidate" } -> "input"
            tags.any { it == "video_candidate" } -> "video"
            tags.any { it == "checkable" } -> "checkable"
            tags.any { it == "entry_candidate" } -> "entry"
            else -> "node"
        }
        return "${prefix}_${index + 1}"
    }

    private fun isGenericResourceTail(label: String): Boolean {
        val normalized = label.lowercase(Locale.CHINA).trim()
        return normalized in setOf(
            "icon",
            "selected",
            "view",
            "layout",
            "container",
            "button",
            "text",
            "title",
            "item",
            "image",
            "img",
            "frame",
            "root",
            "content",
            "list",
            "cell"
        )
    }

    private fun tagsFor(node: NodeSample, state: UiStateSample): List<String> {
        val label = nodeLabel(node).lowercase(Locale.CHINA)
        return buildList {
            if (node.checkable) add("checkable")
            if (node.checkable || label.contains("agree") || label.contains("agreement") ||
                label.contains("checkbox") || label.contains("check box")
            ) add("agreement")
            if (label.contains("qq") || label.contains("quick login") || label.contains("quicklogin")) add("login_entry")
            if (node.clickable) add("clickable")
            if (node.editable) add("editable")
            if (node.checkable) add("checkable")
            if (node.selected) add("selected")
            if (node.bounds.centerY() > state.screenHeight * 0.68f) add("bottom_area")
            if (label.contains("搜索") || label.contains("search")) add("search_entry")
            if (label.contains("发布") || label.contains("发帖") || label.contains("创作") || label == "+") add("publish_entry")
            if (label.contains("消息")) add("message_entry")
            if (label.contains("我") || label.contains("mine") || label.contains("profile")) add("profile_entry")
            if (label.contains("登录") || label.contains("log in") || label.contains("sign in") ||
                label.contains("password") || label.contains("验证码") || label.contains("账号") || label.contains("账户")
            ) add("login_entry")
            if (label.contains("视频") || label.contains("video") || label.contains("播放") ||
                label.contains("cover") || label.contains("player")
            ) add("video_candidate")
            if (label.contains("协议") || label.contains("同意") || label.contains("agree")) add("agreement")
            if (label.contains("设置") || label.contains("更多") || label.contains("menu") || label.contains("more")) add("small_icon")
            if (label.contains("+") || label.contains("加号")) add("small_icon")
            if (node.clickable &&
                node.bounds.width() <= state.screenWidth * 0.14f &&
                node.bounds.height() <= state.screenHeight * 0.14f
            ) add("small_icon")
            if (looksLikeIconOnlyNode(node, state)) add("icon_only")
            if (looksLikeGridIconNode(node, state)) add("grid_icon")
            val centerXRatio = node.bounds.centerX().toFloat() / state.screenWidth.coerceAtLeast(1)
            val centerYRatio = node.bounds.centerY().toFloat() / state.screenHeight.coerceAtLeast(1)
            if (centerYRatio <= 0.18f) add("top_icon")
            if (centerXRatio <= 0.15f || centerXRatio >= 0.85f) add("side_icon")
        }
    }

    private fun looksLikeIconOnlyNode(node: NodeSample, state: UiStateSample): Boolean {
        val label = listOfNotNull(node.text, node.contentDesc).joinToString(" ").trim()
        val clazz = node.className.orEmpty().lowercase(Locale.US)
        val id = node.resourceId.orEmpty().lowercase(Locale.US)
        val width = node.bounds.width()
        val height = node.bounds.height()
        if (width <= 8 || height <= 8) return false
        val screenWidth = state.screenWidth.coerceAtLeast(1)
        val screenHeight = state.screenHeight.coerceAtLeast(1)
        val compact = width <= screenWidth * 0.18f && height <= screenHeight * 0.16f
        val topOrSide = node.bounds.centerY() <= screenHeight * 0.28f ||
            node.bounds.centerX() <= screenWidth * 0.15f ||
            node.bounds.centerX() >= screenWidth * 0.85f ||
            node.bounds.centerY() >= screenHeight * 0.78f
        val classLooksVisual = clazz.contains("image") ||
            clazz.contains("icon") ||
            clazz.endsWith("view") ||
            clazz.contains("button")
        val idLooksVisual = id.contains("icon") ||
            id.contains("img") ||
            id.contains("image") ||
            id.contains("avatar") ||
            id.contains("menu") ||
            id.contains("more") ||
            id.contains("setting") ||
            id.contains("search") ||
            id.contains("action") ||
            id.contains("tab") ||
            id.contains("button") ||
            id.contains("entrance")
        return compact && topOrSide && (label.isBlank() || classLooksVisual || idLooksVisual)
    }

    private fun looksLikeGridIconNode(node: NodeSample, state: UiStateSample): Boolean {
        val width = node.bounds.width()
        val height = node.bounds.height()
        if (width <= 12 || height <= 12) return false
        val screenWidth = state.screenWidth.coerceAtLeast(1)
        val screenHeight = state.screenHeight.coerceAtLeast(1)
        val centerYRatio = node.bounds.centerY().toDouble() / screenHeight
        val compact = width <= screenWidth * 0.24f && height <= screenHeight * 0.14f
        val inContentGrid = centerYRatio in 0.18..0.86
        val label = nodeLabel(node)
        val id = node.resourceId.orEmpty().lowercase(Locale.US)
        val clazz = node.className.orEmpty().lowercase(Locale.US)
        val gridSignal = id.contains("grid") ||
            id.contains("service") ||
            id.contains("entrance") ||
            id.contains("category") ||
            id.contains("item") ||
            id.contains("channel") ||
            clazz.contains("image") ||
            clazz.contains("button") ||
            clazz.endsWith("view")
        return compact && inContentGrid && (node.clickable || label.isNotBlank() || gridSignal)
    }

    private fun looksLikeBottomNavigationIconNode(node: NodeSample, state: UiStateSample): Boolean {
        val screenWidth = state.screenWidth.coerceAtLeast(1)
        val screenHeight = state.screenHeight.coerceAtLeast(1)
        val centerYRatio = node.bounds.centerY().toDouble() / screenHeight
        if (centerYRatio < 0.82) return false
        val width = node.bounds.width()
        val height = node.bounds.height()
        val compact = width in 8..(screenWidth * 0.24f).toInt().coerceAtLeast(24) &&
            height in 8..(screenHeight * 0.12f).toInt().coerceAtLeast(24)
        if (!compact) return false
        val label = listOfNotNull(node.text, node.contentDesc).joinToString(" ").trim()
        if (isLikelyContentCardLabel(label)) return false
        val id = node.resourceId.orEmpty().lowercase(Locale.US)
        val clazz = node.className.orEmpty().lowercase(Locale.US)
        val navSignal = id.contains("tab") ||
            id.contains("bottom") ||
            id.contains("navigation") ||
            id.contains("main_") ||
            id.contains("homepage") ||
            clazz.contains("image") ||
            clazz.contains("button")
        return node.clickable || node.selected || label.isNotBlank() || navSignal
    }

    private fun isDangerousExploreLabel(label: String): Boolean {
        if (label.isBlank()) return false
        val danger = listOf("支付", "付款", "转账", "删除", "确认删除", "提交订单", "购买", "立即支付", "退出登录", "注销")
        return danger.any { label.contains(it, ignoreCase = true) }
    }

    private fun isSystemDecorationNode(node: NodeSample): Boolean {
        val id = node.resourceId.orEmpty().lowercase(Locale.US)
        val clazz = node.className.orEmpty().lowercase(Locale.US)
        val label = nodeLabel(node).lowercase(Locale.CHINA)
        return id.contains("navigationbarbackground") ||
            id.contains("statusbarbackground") ||
            id.contains("android:id/navigation") ||
            id.contains("android:id/status") ||
            clazz.contains("statusbar") ||
            clazz.contains("navigationbar") ||
            label.contains("navigationbarbackground") ||
            label.contains("statusbarbackground")
    }

    private fun inferTitle(labels: List<String>): String {
        return labels.firstOrNull { label ->
            label.length in 2..24 && !label.contains("/")
        }.orEmpty()
    }

    private fun nodeLabel(node: NodeSample): String {
        return listOfNotNull(node.text, node.contentDesc, node.resourceId)
            .joinToString(" ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
    }

    private fun UiStateSample.screenKey(): String {
        val labels = nodes.mapNotNull { nodeLabel(it).takeIf { label -> label.isNotBlank() } }
            .take(12)
            .joinToString("|")
        return "${appPackage}|${windowClassName}|$labels".hashCode().toUInt().toString(16)
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun exploreBySwipes(
        packageName: String,
        appName: String,
        reason: String,
        baseScreen: JSONObject,
        screensByKey: MutableMap<String, JSONObject>,
        edges: JSONArray,
        events: JSONArray,
        maxScreens: Int,
        swipeBudget: Int,
        depth: Int = 0,
        deadlineAt: Long = Long.MAX_VALUE
    ): Int {
        val width = baseScreen.optInt("screenWidth", 1).coerceAtLeast(1)
        val height = baseScreen.optInt("screenHeight", 1).coerceAtLeast(1)
        val directions = listOf(
            Triple("swipe_left", { actions.swipeLeft(width, height) }, { actions.swipeRight(width, height) }),
            Triple("swipe_right", { actions.swipeRight(width, height) }, { actions.swipeLeft(width, height) }),
            Triple("swipe_up", { actions.swipeUp(width, height) }, { actions.swipeDown(width, height) }),
            Triple("swipe_down", { actions.swipeDown(width, height) }, { actions.swipeUp(width, height) })
        )
        var added = 0
        var remaining = swipeBudget.coerceAtLeast(0)
        while (remaining > 0 && screensByKey.size < maxScreens.coerceAtLeast(1) && System.currentTimeMillis() < deadlineAt) {
            for ((name, gesture, reverseGesture) in directions) {
                if (System.currentTimeMillis() >= deadlineAt) break
                if (remaining <= 0 || screensByKey.size >= maxScreens.coerceAtLeast(1)) break
                val outcome = gesture()
                events.put(JSONObject()
                    .put("phase", "swipe_attempt")
                    .put("direction", name)
                    .put("success", outcome.success)
                    .put("message", outcome.reason))
                remaining -= 1
                if (!outcome.success) continue
                sleep(450L)
                reactiveRules.dismissBlockingOverlays(packageName, "app_ui_swipe_$name", maxPasses = 2)
                val screen = captureScreenWithRetry(
                    packageName = packageName,
                    source = "app_ui_explorer_swipe_$name",
                    note = "$reason:$appName:swipe:$name",
                    forceScreenshot = true,
                    events = events,
                    phase = "capture_swipe_wait"
                )
                if (screen != null) {
                    val key = screenGraphKey(screen)
                    if (key.isNotBlank() && screensByKey.putIfAbsent(key, screen) == null) {
                        added += 1
                        edges.put(JSONObject()
                            .put("fromScreenKey", screenGraphKey(baseScreen))
                            .put("toScreenKey", key)
                            .put("action", JSONObject()
                                .put("type", "Swipe")
                                .put("direction", name))
                            .put("changed", true)
                            .put("depth", 1))
                    }
                }
                if (depth == 0) {
                    openAndWaitForTarget(packageName, events)
                } else {
                    reverseGesture()
                }
                sleep(250L)
            }
        }
        return added
    }

    private fun openAndWaitForTarget(packageName: String, events: JSONArray): WaitForegroundResult {
        val attempts = listOf(
            "launcher_root" to { actions.openAppLauncherRoot(packageName) },
            "home_then_launcher_root" to {
                actions.home()
                sleep(450L)
                actions.openAppLauncherRoot(packageName)
            },
            "explicit_launcher_activity" to { actions.openAppExplicitLauncher(packageName) }
        )
        var last = WaitForegroundResult(false, null, null, 0, JSONArray(), "not started")
        attempts.forEachIndexed { index, attempt ->
            val open = attempt.second()
            events.put(JSONObject()
                .put("phase", "open_app_attempt")
                .put("attempt", index + 1)
                .put("strategy", attempt.first)
                .put("success", open.success)
                .put("message", open.reason))
            if (!open.success) {
                last = WaitForegroundResult(false, last.currentPackage, last.windowClassName, last.overlayDismissed, last.overlayEvents, open.reason)
                return@forEachIndexed
            }
            val foreground = waitForTargetPackage(packageName, if (index == 0) 5_000L else 8_000L)
            events.put(JSONObject()
                .put("phase", "open_app_attempt_wait")
                .put("attempt", index + 1)
                .put("strategy", attempt.first)
                .put("success", foreground.success)
                .put("currentPackage", foreground.currentPackage ?: JSONObject.NULL)
                .put("windowClassName", foreground.windowClassName ?: JSONObject.NULL)
                .put("overlayDismissed", foreground.overlayDismissed)
                .put("overlayEvents", foreground.overlayEvents))
            last = foreground
            if (foreground.success) return foreground
        }
        return last
    }

    private fun waitForTargetPackage(packageName: String, timeoutMs: Long): WaitForegroundResult {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        val overlayEvents = JSONArray()
        var overlayDismissed = 0
        var lastPackage: String? = null
        var lastWindow: String? = null
        while (System.currentTimeMillis() <= deadline) {
            val state = inspector.currentState()
            lastPackage = state?.appPackage
            lastWindow = state?.windowClassName
            if (state?.appPackage == packageName) {
                return WaitForegroundResult(true, lastPackage, lastWindow, overlayDismissed, overlayEvents, "target foreground")
            }
            if (state != null && shouldDismissDuringLaunch(state, packageName)) {
                val result = reactiveRules.dismissBlockingOverlays(packageName, "wait_target_foreground", maxPasses = 1)
                if (result.dismissedCount > 0) {
                    overlayDismissed += result.dismissedCount
                    overlayEvents.put(JSONObject()
                        .put("packageName", state.appPackage ?: JSONObject.NULL)
                        .put("windowClassName", state.windowClassName ?: JSONObject.NULL)
                        .put("dismissed", result.dismissedCount)
                        .put("events", result.events))
                    sleep(650L)
                    continue
                }
            }
            sleep(300L)
        }
        val state = inspector.currentState()
        return WaitForegroundResult(
            success = state?.appPackage == packageName,
            currentPackage = state?.appPackage ?: lastPackage,
            windowClassName = state?.windowClassName ?: lastWindow,
            overlayDismissed = overlayDismissed,
            overlayEvents = overlayEvents,
            message = if (state?.appPackage == packageName) "target foreground" else "foreground package mismatch"
        )
    }

    private fun shouldDismissDuringLaunch(state: UiStateSample, targetPackage: String): Boolean {
        if (state.appPackage == targetPackage) return false
        val window = state.windowClassName.orEmpty().lowercase(Locale.US)
        if (window.contains("appstartconfirmdialogactivity")) return true
        if (state.appPackage.orEmpty().contains("securitypermission", ignoreCase = true)) return true
        val visible = state.nodes.joinToString(" ") { nodeLabel(it) }.lowercase(Locale.CHINA)
        return listOf("想要打开", "30天内允许", "仅本次允许", "允许打开", "继续打开")
            .any { visible.contains(it.lowercase(Locale.CHINA)) }
    }
    private fun waitForAccessibilityService(events: JSONArray, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        var attempt = 0
        while (System.currentTimeMillis() <= deadline) {
            attempt += 1
            if (MacroPilotAccessibilityService.instance != null) {
                events.put(JSONObject()
                    .put("phase", "wait_accessibility")
                    .put("status", "SUCCESS")
                    .put("attempt", attempt)
                    .put("message", "accessibility service is connected"))
                return true
            }
            if (attempt == 1 || attempt % 5 == 0) {
                events.put(JSONObject()
                    .put("phase", "wait_accessibility")
                    .put("status", "WAITING")
                    .put("attempt", attempt)
                    .put("message", "waiting for MacroPilot accessibility service before probing UI"))
            }
            sleep(250L)
        }
        events.put(JSONObject()
            .put("phase", "wait_accessibility")
            .put("status", "FAILED")
            .put("attempt", attempt)
            .put("message", "accessibility service did not reconnect before probe timeout"))
        return MacroPilotAccessibilityService.instance != null
    }
}

data class AppUiExploreResult(
    val report: JSONObject,
    val graphFile: File?,
    val success: Boolean
)

private data class WaitForegroundResult(
    val success: Boolean,
    val currentPackage: String?,
    val windowClassName: String?,
    val overlayDismissed: Int,
    val overlayEvents: JSONArray,
    val message: String
)

private data class PrimaryTabSweepResult(
    val screens: List<JSONObject>,
    val tapCount: Int
)

private data class PrimaryTabSurfaceProbeResult(
    val tapCount: Int
)

private data class PrimaryCenterActionSweepResult(
    val tapCount: Int
)

private data class ExploreProbeMemory(
    val successes: MutableSet<String>,
    val failures: MutableMap<String, Int>
) {
    fun shouldSkip(candidate: JSONObject, fingerprint: String): Boolean {
        if ((failures[fingerprint] ?: 0) >= 2) return true
        if (fingerprint !in successes) return false
        return when (candidate.optString("candidateCategory")) {
            "primary_bottom_tab", "bottom_tab" -> false
            else -> true
        }
    }

    fun record(fingerprint: String, changed: Boolean) {
        if (changed) {
            successes += fingerprint
        } else {
            failures[fingerprint] = (failures[fingerprint] ?: 0) + 1
        }
    }
}

private data class ScreenChange(
    val changed: Boolean,
    val score: Int,
    val confidence: String,
    val reason: String
)

private fun JSONArray?.asJsonObjectList(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = mutableListOf<JSONObject>()
    for (i in 0 until length()) {
        optJSONObject(i)?.let { out += it }
    }
    return out
}

private fun JSONArray?.asStringList(): List<String> {
    if (this == null) return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until length()) {
        val value = optString(i)
        if (value.isNotBlank()) out += value
    }
    return out
}

private fun List<JSONObject>.dedupeCandidatesByPosition(): List<JSONObject> {
    val out = mutableListOf<JSONObject>()
    for (item in this) {
        val x = item.optDouble("x", item.optJSONObject("center")?.optDouble("x", -1.0) ?: -1.0)
        val y = item.optDouble("y", item.optJSONObject("center")?.optDouble("y", -1.0) ?: -1.0)
        if (x !in 0.0..1.0 || y !in 0.0..1.0) continue
        if (out.none { existing -> abs(existing.optDouble("x") - x) < 0.030 && abs(existing.optDouble("y") - y) < 0.030 }) {
            out += item
        }
    }
    return out
}
