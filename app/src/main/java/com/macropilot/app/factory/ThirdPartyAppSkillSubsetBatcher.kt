package com.macropilot.app.factory

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.model.CapabilityProfile
import com.macropilot.app.model.CoordinateFallback
import com.macropilot.app.model.InputCapability
import com.macropilot.app.model.NodeReadability
import com.macropilot.app.model.ServiceState
import com.macropilot.app.model.VerificationCapability
import com.macropilot.app.runtime.CapabilityProfiler
import com.macropilot.app.runtime.NodeInspector
import com.macropilot.app.store.FactorySkillJsonStore
import com.macropilot.app.store.AppUiGraphStore
import com.macropilot.app.store.UiStateSnapshotStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class ThirdPartyAppSkillSubsetBatcher(
    private val context: Context,
    private val store: FactorySkillJsonStore = FactorySkillJsonStore(context),
    private val dryRunEngine: SkillJsonDryRunEngine = SkillJsonDryRunEngine()
) {
    fun generate(
        limit: Int = 2000,
        launchableOnly: Boolean = false,
        clearExisting: Boolean = false,
        runDryRun: Boolean = true,
        exportPreviewConfig: Boolean = true,
        probeUi: Boolean = false,
        probeLimit: Int = 80,
        packageFilter: List<String> = emptyList(),
        shouldStop: () -> Boolean = { false },
        onProgress: ((JSONObject) -> Unit)? = null
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        val packageManager = context.packageManager
        val actions = if (probeUi) DeviceActions(context) else null
        val inspector = if (probeUi) NodeInspector(context) else null
        val profiler = if (probeUi) CapabilityProfiler() else null
        val snapshotStore = if (probeUi) UiStateSnapshotStore(context) else null
        val graphStore = if (probeUi) AppUiGraphStore(context) else null
        val reactiveRules = if (probeUi && actions != null && inspector != null) {
            ReactiveUiRuleEngine(inspector, actions)
        } else {
            null
        }
        val appUiExplorer = if (probeUi && actions != null && inspector != null && profiler != null && snapshotStore != null && graphStore != null && reactiveRules != null) {
            AppUiExplorer(context, actions, inspector, profiler, snapshotStore, graphStore, reactiveRules)
        } else {
            null
        }
        val probeAllowed = probeUi &&
            MacroPilotAccessibilityService.instance != null &&
            actions?.isInteractive() == true &&
            actions.isKeyguardLocked() == false
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherActivities = packageManager.queryIntentActivities(launcherIntent, 0)
        val launcherByPackage = launcherActivities
            .groupBy { it.activityInfo.packageName }
            .mapValues { (_, activities) -> activities.firstOrNull() }
        val installedByPackage = packageManager.getInstalledPackages(0).associateBy { it.packageName }
        val packageNames = (installedByPackage.keys + launcherByPackage.keys)
            .filter { it != context.packageName }
            .distinct()
        val wantedPackages = packageFilter
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val apps = packageNames
            .asSequence()
            .filter { packageName -> wantedPackages.isEmpty() || packageName in wantedPackages }
            .mapNotNull { packageName ->
                val info = installedByPackage[packageName]
                    ?: runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
                val appInfo = info?.applicationInfo
                    ?: launcherByPackage[packageName]?.activityInfo?.applicationInfo
                    ?: return@mapNotNull null
                if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                val launcherActivity = launcherByPackage[packageName]?.activityInfo?.name.orEmpty()
                val launchable = launchIntent != null || launcherActivity.isNotBlank()
                ThirdPartyAppInfo(
                    packageName = packageName,
                    label = runCatching { appInfo.loadLabel(packageManager).toString() }
                        .getOrDefault(packageName),
                    versionName = info?.versionName.orEmpty(),
                    versionCode = if (info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        info.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        info?.versionCode?.toLong() ?: 0L
                    },
                    launchable = launchable,
                    launchActivity = launchIntent?.component?.className.orEmpty().ifBlank { launcherActivity }
                )
            }
            .filter { app -> !launchableOnly || app.launchable }
            .let { sequence ->
                if (wantedPackages.isEmpty()) {
                    sequence.sortedBy { it.label.lowercase(Locale.getDefault()) }
                } else {
                    sequence.sortedBy { wantedPackages.indexOf(it.packageName).let { index -> if (index >= 0) index else Int.MAX_VALUE } }
                }
            }
            .take(if (wantedPackages.isEmpty()) limit.coerceIn(1, 2000) else wantedPackages.size.coerceIn(1, 2000))
            .toList()

        val appSummaries = mutableListOf<JSONObject>()
        var generatedSkillCount = 0
        var dryRunPassed = 0
        var dryRunFailed = 0
        var exportedConfigCount = 0
        var uiProbeAttempted = 0
        var uiProbeSucceeded = 0
        var uiProbeBlocked = 0
        var uiProbeSkipped = 0

        apps.forEachIndexed { appIndex, app ->
            if (shouldStop()) {
                onProgress?.invoke(JSONObject()
                    .put("phase", "CANCELLED")
                    .put("index", appIndex + 1)
                    .put("total", apps.size)
                    .put("packageName", app.packageName)
                    .put("label", app.label)
                    .put("message", "batch cancelled before app"))
                val status = JSONObject()
                    .put("totalApps", apps.size)
                    .put("processedApps", appSummaries.size)
                    .put("generatedSkillCount", generatedSkillCount)
                    .put("dryRunPassed", dryRunPassed)
                    .put("dryRunFailed", dryRunFailed)
                    .put("uiProbeAttempted", uiProbeAttempted)
                    .put("uiProbeSucceeded", uiProbeSucceeded)
                    .put("uiProbeBlocked", uiProbeBlocked)
                    .put("uiProbeSkipped", uiProbeSkipped)
                    .put("cancelled", true)
                val report = JSONObject()
                    .put("schemaVersion", 1)
                    .put("kind", "third_party_app_skill_subset_batch_report")
                    .put("id", "third_party_app_skill_subset_${System.currentTimeMillis()}")
                    .put("status", "CANCELLED")
                    .put("mode", if (probeUi) "PHONE_SIDE_UI_PROBE_AND_DRY_RUN" else "LOW_RISK_RESOLVE_AND_DRY_RUN")
                    .put("summary", status)
                    .put("requestedPackages", JSONArray(wantedPackages))
                    .put("apps", JSONArray(appSummaries))
                    .put("durationMs", System.currentTimeMillis() - startedAt)
                    .put("createdAt", System.currentTimeMillis())
                val reportFile = store.saveFlowReport("__third_party_apps__", report)
                return JSONObject()
                    .put("ok", true)
                    .put("action", "com.macropilot.app.API_GENERATE_THIRD_PARTY_APP_SKILL_SUBSETS")
                    .put("reportFile", reportFile.absolutePath)
                    .put("summary", status)
                    .put("appsPreview", JSONArray(appSummaries.take(30)))
            }
            onProgress?.invoke(JSONObject()
                .put("phase", "APP_START")
                .put("index", appIndex + 1)
                .put("total", apps.size)
                .put("packageName", app.packageName)
                .put("label", app.label)
                .put("generatedSkillCount", generatedSkillCount)
                .put("dryRunPassed", dryRunPassed)
                .put("dryRunFailed", dryRunFailed)
                .put("uiProbeAttempted", uiProbeAttempted)
                .put("uiProbeSucceeded", uiProbeSucceeded)
                .put("uiProbeBlocked", uiProbeBlocked)
                .also { BatchReportRates.addThirdPartyRates(it.put("totalApps", apps.size).put("launchableApps", apps.count { appInfo -> appInfo.launchable }).put("exportedConfigCount", exportedConfigCount).put("uiProbeSkipped", uiProbeSkipped)) })
            if (clearExisting) store.clearCandidateSkills(app.packageName)
            val skills = buildSubset(app)
            val savedFiles = skills.map { skill ->
                generatedSkillCount += 1
                store.saveSkill(app.packageName, skill)
            }

            val knownIds = skills.map { it.optString("id") }.filter { it.isNotBlank() }.toSet()
            val dryReports = if (runDryRun) {
                savedFiles.mapNotNull { file ->
                    val skill = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@mapNotNull null
                    val report = dryRunEngine.dryRun(skill, knownIds)
                    if (report.optBoolean("passed")) dryRunPassed += 1 else dryRunFailed += 1
                    val reportFile = store.saveDryRunReport(app.packageName, skill.optString("id"), report)
                    val factory = skill.optJSONObject("factory") ?: JSONObject()
                    factory
                        .put("dryRunStatus", if (report.optBoolean("passed")) "PASSED" else "FAILED")
                        .put("lastDryRunReport", reportFile.absolutePath)
                        .put("lastDryRunAt", System.currentTimeMillis())
                    skill.put("factory", factory)
                    skill.put("status", if (report.optBoolean("passed")) "DRY_RUN_PASSED" else "REJECTED")
                    store.saveSkill(app.packageName, skill)
                    report
                }
            } else {
                emptyList()
            }

            var appConfigFile: File? = null
            if (exportPreviewConfig) {
                val currentSkills = store.listSkillFiles(app.packageName, 1000)
                    .mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
                    .filter { it.optString("status") != "REJECTED" }
                val config = SkillJsonExporter().exportAppConfig(
                    packageName = app.packageName,
                    appName = app.label,
                    module = null,
                    skills = currentSkills,
                    dryRunReports = dryReports
                )
                appConfigFile = store.saveAppConfig(app.packageName, config)
                exportedConfigCount += 1
            }

            val uiProbe = when {
                !probeUi -> JSONObject()
                    .put("status", "SKIPPED")
                    .put("reason", "probeUi=false")
                !app.launchable -> {
                    uiProbeSkipped += 1
                    JSONObject()
                        .put("status", "SKIPPED")
                        .put("reason", "app has no launch intent")
                }
                !probeAllowed -> {
                    uiProbeBlocked += 1
                    JSONObject()
                        .put("status", "BLOCKED")
                        .put("reason", "accessibility/input surface is not ready or device is locked")
                        .put("serviceConnected", MacroPilotAccessibilityService.instance != null)
                        .put("interactive", actions?.isInteractive() == true)
                        .put("keyguardLocked", actions?.isKeyguardLocked() == true)
                }
                uiProbeAttempted >= probeLimit.coerceIn(0, 2000) -> {
                    uiProbeSkipped += 1
                    JSONObject()
                        .put("status", "SKIPPED")
                        .put("reason", "probeLimit reached")
                }
                else -> {
                    uiProbeAttempted += 1
                    val report = probeAppUi(
                        app = app,
                        explorer = appUiExplorer!!,
                        graphStore = graphStore!!,
                        progressBase = JSONObject()
                            .put("totalApps", apps.size)
                            .put("launchableApps", apps.count { appInfo -> appInfo.launchable })
                            .put("generatedSkillCount", generatedSkillCount)
                            .put("dryRunPassed", dryRunPassed)
                            .put("dryRunFailed", dryRunFailed)
                            .put("exportedConfigCount", exportedConfigCount)
                            .put("uiProbeAttempted", uiProbeAttempted)
                            .put("uiProbeSucceeded", uiProbeSucceeded)
                            .put("uiProbeBlocked", uiProbeBlocked)
                            .put("uiProbeSkipped", uiProbeSkipped)
                            .also { BatchReportRates.addThirdPartyRates(it) },
                        onProgress = onProgress
                    )
                    if (report.optString("status").startsWith("SUCCESS")) {
                        uiProbeSucceeded += 1
                    } else {
                        uiProbeBlocked += 1
                    }
                    report
                }
            }

            appSummaries += JSONObject()
                .put("packageName", app.packageName)
                .put("label", app.label)
                .put("versionName", app.versionName)
                .put("versionCode", app.versionCode)
                .put("launchable", app.launchable)
                .put("launchActivity", app.launchActivity)
                .put("generatedSkillCount", savedFiles.size)
                .put("dryRunPassed", dryReports.count { it.optBoolean("passed") })
                .put("dryRunFailed", dryReports.count { !it.optBoolean("passed") })
                .put("candidateDir", File(store.skillJsonDir, app.packageName.safePathSegment()).absolutePath)
                .put("previewConfigFile", appConfigFile?.absolutePath)
                .put("uiProbe", uiProbe)
            onProgress?.invoke(JSONObject()
                .put("phase", "APP_DONE")
                .put("index", appIndex + 1)
                .put("total", apps.size)
                .put("packageName", app.packageName)
                .put("label", app.label)
                .put("uiProbeStatus", uiProbe.optString("status"))
                .put("screenCount", uiProbe.optInt("screenCount"))
                .put("edgeCount", uiProbe.optInt("edgeCount"))
                .put("nodeCount", uiProbe.optInt("nodeCount"))
                .put("clickableCount", uiProbe.optInt("clickableCount"))
                .put("editableCount", uiProbe.optInt("editableCount"))
                .put("generatedSkillCount", generatedSkillCount)
                .put("dryRunPassed", dryRunPassed)
                .put("dryRunFailed", dryRunFailed)
                .put("uiProbeAttempted", uiProbeAttempted)
                .put("uiProbeSucceeded", uiProbeSucceeded)
                .put("uiProbeBlocked", uiProbeBlocked)
                .put("uiProbeSkipped", uiProbeSkipped)
                .put("totalApps", apps.size)
                .put("launchableApps", apps.count { appInfo -> appInfo.launchable })
                .put("exportedConfigCount", exportedConfigCount)
                .also { BatchReportRates.addThirdPartyRates(it) })
        }

        val status = JSONObject()
            .put("totalApps", apps.size)
            .put("launchableApps", apps.count { it.launchable })
            .put("nonLaunchableApps", apps.count { !it.launchable })
            .put("generatedSkillCount", generatedSkillCount)
            .put("dryRunPassed", dryRunPassed)
            .put("dryRunFailed", dryRunFailed)
            .put("exportedConfigCount", exportedConfigCount)
            .put("uiProbeEnabled", probeUi)
            .put("uiProbeAllowed", probeAllowed)
            .put("uiProbeAttempted", uiProbeAttempted)
            .put("uiProbeSucceeded", uiProbeSucceeded)
            .put("uiProbeBlocked", uiProbeBlocked)
            .put("uiProbeSkipped", uiProbeSkipped)
        BatchReportRates.addThirdPartyRates(status)

        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "third_party_app_skill_subset_batch_report")
            .put("id", "third_party_app_skill_subset_${System.currentTimeMillis()}")
            .put("status", if (dryRunFailed == 0 && (!probeUi || uiProbeBlocked == 0)) "SUCCESS" else "PARTIAL")
            .put("mode", if (probeUi) "PHONE_SIDE_UI_PROBE_AND_DRY_RUN" else "LOW_RISK_RESOLVE_AND_DRY_RUN")
            .put("summary", status)
            .put("requestedPackages", JSONArray(wantedPackages))
            .put("capabilityBaseline", disconnectedBaseline())
            .put("apps", JSONArray(appSummaries))
            .put("notes", JSONArray(listOf(
                "This batch does not train models.",
                "It writes reusable app-level Skill JSON subsets for future assembly.",
                "Phone-side UI probe now uses AppUiExplorer: it opens launchable apps through App code, dismisses safe overlays, captures home, sweeps safe bottom tabs/entries, records subpages, and stores a compact AppUI graph.",
                "No business submit/pay/delete button is clicked during this batch."
            )))
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("createdAt", System.currentTimeMillis())
        val reportFile = store.saveFlowReport("__third_party_apps__", report)

        return JSONObject()
            .put("ok", true)
            .put("action", "com.macropilot.app.API_GENERATE_THIRD_PARTY_APP_SKILL_SUBSETS")
            .put("reportFile", reportFile.absolutePath)
            .put("summary", status)
            .put("appsPreview", JSONArray(appSummaries.take(30)))
    }

    private fun probeAppUi(
        app: ThirdPartyAppInfo,
        explorer: AppUiExplorer,
        graphStore: AppUiGraphStore,
        progressBase: JSONObject,
        onProgress: ((JSONObject) -> Unit)?
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        MacroPilotAccessibilityService.instance?.showStatusOverlay(
            title = "第三方 App 自训练",
            status = "RUNNING",
            message = "打开 ${app.label.take(18)}，探索首页、底部分屏、入口、输入框和子页面"
        )
        val result = explorer.explore(
            packageName = app.packageName,
            appName = app.label,
            reason = "third_party_app_skill_subset_batch",
            maxScreens = 120,
            maxClicks = 100,
            maxDepth = 4,
            maxSwipeActions = 100,
            maxDurationMs = 180_000L,
            forceScreenshots = true,
            onEvent = { phase, status, message, path ->
                onProgress?.invoke(JSONObject(progressBase.toString())
                    .put("phase", "UI_PROBE_EVENT")
                    .put("packageName", app.packageName)
                    .put("label", app.label)
                    .put("uiPhase", phase)
                    .put("uiStatus", status)
                    .put("uiMessage", message)
                    .put("path", path ?: JSONObject.NULL)
                    .put("elapsedMs", System.currentTimeMillis() - startedAt))
            }
        )
        val graph = graphStore.compactSummary(app.packageName, 20)
        val screens = graph.optJSONArray("screens") ?: JSONArray()
        val firstScreen = screens.optJSONObject(0)
        val quality = graph.optJSONObject("quality") ?: JSONObject()
        val status = when {
            result.success && quality.optBoolean("usable", true) -> "SUCCESS_EXPLORED"
            result.report.optString("status").isNotBlank() -> result.report.optString("status")
            else -> "PARTIAL_OR_BLOCKED"
        }
        return JSONObject()
            .put("status", status)
            .put("appPackage", app.packageName)
            .put("label", app.label)
            .put("graphFile", result.graphFile?.absolutePath ?: JSONObject.NULL)
            .put("screenCount", graph.optInt("screenCount"))
            .put("edgeCount", graph.optInt("edgeCount"))
            .put("quality", quality)
            .put("nodeCount", firstScreen?.optInt("nodeCount") ?: 0)
            .put("clickableCount", firstScreen?.optInt("clickableCount") ?: 0)
            .put("editableCount", firstScreen?.optInt("editableCount") ?: 0)
            .put("checkableCount", firstScreen?.optInt("checkableCount") ?: 0)
            .put("visibleLabels", firstScreen?.optJSONArray("labels") ?: JSONArray())
            .put("bottomTabs", firstScreen?.optJSONArray("bottomTabs") ?: JSONArray())
            .put("topEntries", firstScreen?.optJSONArray("topEntries") ?: JSONArray())
            .put("sideEntries", firstScreen?.optJSONArray("sideEntries") ?: JSONArray())
            .put("graphicalCandidates", firstScreen?.optJSONArray("graphicalCandidates") ?: JSONArray())
            .put("entryCandidates", firstScreen?.optJSONArray("entryCandidates") ?: JSONArray())
            .put("checkableCandidates", firstScreen?.optJSONArray("checkableCandidates") ?: JSONArray())
            .put("inputCandidates", firstScreen?.optJSONArray("inputCandidates") ?: JSONArray())
            .put("videoCandidates", firstScreen?.optJSONArray("videoCandidates") ?: JSONArray())
            .put("exploreReport", result.report)
            .put("durationMs", System.currentTimeMillis() - startedAt)
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun buildSubset(app: ThirdPartyAppInfo): List<JSONObject> {
        val patch = SkillJsonArchetypeFactory.defaultPatch(
            packageName = app.packageName,
            label = app.label,
            launchable = app.launchable,
            launchActivity = app.launchActivity
        )
        return SkillJsonArchetypeFactory().buildForApp(
            patch = patch,
            includeSearch = app.launchable,
            includePostDraft = app.launchable && app.looksLikePostCapableApp()
        )
    }

    private fun ThirdPartyAppInfo.looksLikePostCapableApp(): Boolean {
        val haystack = "${packageName.lowercase(Locale.US)} ${label.lowercase(Locale.getDefault())}"
        return listOf(
            "weibo",
            "hupu",
            "zhihu",
            "reddit",
            "instagram",
            "nga",
            "qq",
            "xiaohongshu",
            "\u5fae\u535a",
            "\u864e\u6251",
            "\u77e5\u4e4e",
            "\u5c0f\u7ea2\u4e66",
            "\u793e\u533a",
            "\u8bba\u575b"
        ).any { token -> haystack.contains(token) }
    }

    private fun atomicBase(
        id: String,
        name: String,
        appPackage: String,
        implementsName: String,
        factory: JSONObject
    ): JSONObject {
        return JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "atomic_skill")
            .put("id", id)
            .put("name", name)
            .put("appPackage", appPackage)
            .put("appVersion", "*")
            .put("status", "CANDIDATE")
            .put("domain", "app_session")
            .put("implements", implementsName)
            .put("params", JSONArray())
            .put("preconditions", JSONArray())
            .put("dependsOn", JSONArray())
            .put("resolverPolicy", JSONObject()
                .put("order", JSONArray(listOf("ACCESSIBILITY", "PACKAGE_MANAGER")))
                .put("allowAiSuggestionInFactory", true)
                .put("allowAiInRuntime", false))
            .put("safety", JSONObject()
                .put("forbiddenTexts", JSONArray(SkillJsonArchetypeFactory.forbiddenTexts())))
            .put("factory", JSONObject(factory.toString()))
    }

    private fun capability(
        minLevel: String,
        inputRequired: Boolean,
        coordinateFallbackAllowed: Boolean
    ): JSONObject {
        return JSONObject()
            .put("minLevel", minLevel)
            .put("inputRequired", inputRequired)
            .put("nodeReadablePreferred", false)
            .put("coordinateFallbackAllowed", coordinateFallbackAllowed)
            .put("allowedInputChannels", JSONArray())
            .put("verificationRequired", "LOW")
    }

    private fun useSkill(skillId: String): JSONObject {
        return JSONObject()
            .put("type", "UseSkill")
            .put("skillId", skillId)
    }

    private fun disconnectedBaseline(): JSONObject {
        val profile = CapabilityProfile(
            nodeReadability = NodeReadability.D,
            inputCapability = InputCapability.NONE,
            verificationCapability = VerificationCapability.NONE,
            coordinateFallback = CoordinateFallback.MISSING,
            serviceState = ServiceState.DISCONNECTED,
            reason = "Baseline only; real per-app node probe requires accessibility service"
        )
        return JSONObject()
            .put("nodeReadability", profile.nodeReadability.name)
            .put("inputCapability", profile.inputCapability.name)
            .put("verificationCapability", profile.verificationCapability.name)
            .put("coordinateFallback", profile.coordinateFallback.name)
            .put("serviceState", profile.serviceState.name)
            .put("reason", profile.reason)
    }

    private data class ThirdPartyAppInfo(
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val launchable: Boolean,
        val launchActivity: String
    )

    private data class SkillIds(
        val packageName: String
    ) {
        private val prefix = packageName.safeSkillPrefix()
        val openApp: String = "$prefix.open_app"
        val capabilityCheck: String = "$prefix.capability_check"
        val recover: String = "$prefix.recover_to_foreground"
        val prepareSession: String = "$prefix.prepare_app_session"
    }
}

private fun String.safeSkillPrefix(): String {
    return lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), ".")
        .trim('.')
        .ifBlank { "app" }
}

private fun String.safePathSegment(): String {
    return lowercase(Locale.US)
        .replace(Regex("[^a-z0-9._-]+"), "_")
        .ifBlank { "unknown" }
}
