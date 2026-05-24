package com.macropilot.app.api

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.factory.PromotionGate
import com.macropilot.app.factory.AppCapabilityProbe
import com.macropilot.app.factory.AppSideInstructionFlowRunner
import com.macropilot.app.factory.AppSideInstructionService
import com.macropilot.app.factory.AppUiProbeService
import com.macropilot.app.factory.CalibrationRequestFactory
import com.macropilot.app.factory.FactoryAiPipeline
import com.macropilot.app.factory.SkillDraftFactory
import com.macropilot.app.factory.SkillFactorySelfTest
import com.macropilot.app.factory.SkillJsonDryRunEngine
import com.macropilot.app.factory.SkillJsonExecutor
import com.macropilot.app.factory.SkillJsonExporter
import com.macropilot.app.factory.SkillJsonPromotionGate
import com.macropilot.app.factory.SkillJsonTestRunner
import com.macropilot.app.factory.SkillTestRunner
import com.macropilot.app.factory.PopularAppTaskBatchService
import com.macropilot.app.factory.PopularAppTaskCatalog
import com.macropilot.app.factory.ThirdPartyAppSkillSubsetBatcher
import com.macropilot.app.factory.ThirdPartyAppBatchService
import com.macropilot.app.factory.WeChatCandidateFactory
import com.macropilot.app.model.MacroTemplate
import com.macropilot.app.model.ModuleStatus
import com.macropilot.app.model.FailureCluster
import com.macropilot.app.model.InputCapability
import com.macropilot.app.model.MacroAtom
import com.macropilot.app.model.SkillCandidate
import com.macropilot.app.model.SkillSource
import com.macropilot.app.model.SkillTestReport
import com.macropilot.app.runtime.CapabilityProfiler
import com.macropilot.app.runtime.ArchitectureSelfCheck
import com.macropilot.app.runtime.MacroExecutor
import com.macropilot.app.runtime.NodeInspector
import com.macropilot.app.store.AppUiModuleStore
import com.macropilot.app.store.AutonomousTrainingRecordStore
import com.macropilot.app.store.BuiltinKnowledgeSeeder
import com.macropilot.app.store.CandidateKnowledgeStore
import com.macropilot.app.store.CoordinateCalibrationStore
import com.macropilot.app.store.FactorySkillJsonStore
import com.macropilot.app.store.MacroTemplateStore
import com.macropilot.app.store.RunLogStore
import com.macropilot.app.store.SkillTestReportStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MacroPilotApiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in API_ACTIONS) return

        val pending = goAsync()
        Thread {
            val response = runCatching {
                handle(context.applicationContext, intent)
            }.getOrElse { error ->
                JSONObject()
                    .put("ok", false)
                    .put("action", action)
                    .put("error", error.message ?: error.javaClass.simpleName)
            }
            respond(context.applicationContext, pending, response)
        }.start()
    }

    private fun handle(context: Context, intent: Intent): JSONObject {
        ensureBuiltins(context)
        return when (intent.action) {
            ACTION_LIST_APPS -> listApps(context, intent)
            ACTION_PROBE_APP_CAPABILITY -> probeAppCapability(context, intent)
            ACTION_CREATE_WECHAT_CALIBRATION_REQUEST -> createWeChatCalibrationRequest(context)
            ACTION_SAVE_COORDINATE_POINT_CANDIDATE -> saveCoordinatePointCandidate(context, intent)
            ACTION_CREATE_WECHAT_TYPE_CANDIDATE -> createWeChatTypeCandidate(context, intent)
            ACTION_LIST_APPROVED_SKILLS -> listApprovedSkills(context, intent)
            ACTION_LIST_CANDIDATES -> listCandidates(context, intent)
            ACTION_CREATE_CANDIDATE_REQUEST -> createCandidateRequest(context, intent)
            ACTION_GENERATE_SKILL_DRAFT -> generateSkillDraft(context, intent)
            ACTION_LIST_SKILL_DRAFTS -> listSkillDrafts(context, intent)
            ACTION_DRY_RUN_SKILL_DRAFT -> dryRunSkillDraft(context, intent)
            ACTION_TEST_SKILL_DRAFT -> testSkillDraft(context, intent)
            ACTION_PROMOTE_SKILL_DRAFT -> promoteSkillDraft(context, intent)
            ACTION_FACTORY_AI_INSTRUCTION -> factoryAiInstruction(context, intent)
            ACTION_RUN_APP_SIDE_INSTRUCTION -> runAppSideInstruction(context, intent)
            ACTION_GENERATE_WECHAT_SKILL_JSON -> generateWeChatSkillJson(context)
            ACTION_LIST_SKILL_JSON -> listSkillJson(context, intent)
            ACTION_DRY_RUN_SKILL_JSON -> dryRunSkillJson(context, intent)
            ACTION_TEST_SKILL_JSON -> testSkillJson(context, intent)
            ACTION_PROMOTE_SKILL_JSON -> promoteSkillJson(context, intent)
            ACTION_LIST_APPROVED_SKILL_JSON -> listApprovedSkillJson(context, intent)
            ACTION_EXPORT_APP_CONFIG_JSON -> exportAppConfigJson(context, intent)
            ACTION_FACTORY_DASHBOARD -> factoryDashboard(context, intent)
            ACTION_FACTORY_SELF_TEST -> factorySelfTest(context)
            ACTION_GENERATE_THIRD_PARTY_APP_SKILL_SUBSETS -> generateThirdPartyAppSkillSubsets(context, intent)
            ACTION_PROBE_APP_UI_GRAPH -> probeAppUiGraph(context, intent)
            ACTION_RUN_THIRD_PARTY_APP_BATCH -> runThirdPartyAppBatch(context, intent)
            ACTION_CANCEL_THIRD_PARTY_APP_BATCH -> cancelThirdPartyAppBatch(context)
            ACTION_LIST_POPULAR_APP_TASK_CATALOG -> listPopularAppTaskCatalog(context, intent)
            ACTION_RUN_POPULAR_APP_TASK_BATCH -> runPopularAppTaskBatch(context, intent)
            ACTION_CANCEL_POPULAR_APP_TASK_BATCH -> cancelPopularAppTaskBatch(context)
            ACTION_DELETE_APPROVED_SKILL -> deleteApprovedSkill(context, intent)
            ACTION_DELETE_CANDIDATE -> deleteCandidate(context, intent)
            ACTION_RUN_SKILL -> runApprovedSkill(context, intent)
            ACTION_GET_STATUS -> status(context)
            else -> JSONObject().put("ok", false).put("error", "Unknown API action")
        }
    }

    private fun listApps(context: Context, intent: Intent): JSONObject {
        val pm = context.packageManager
        val modules = AppUiModuleStore(context).listApproved().associateBy { it.packageName }
        val skillsByPackage = MacroTemplateStore(context).listApproved().groupBy { it.packageName }
        val includeSystem = intent.getBooleanExtra(EXTRA_INCLUDE_SYSTEM, true)
        val limit = intent.getIntExtra(EXTRA_LIMIT, 1000).coerceIn(1, 2000)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherByPackage = pm.queryIntentActivities(launcherIntent, 0)
            .groupBy { it.activityInfo.packageName }
            .mapValues { (_, activities) -> activities.firstOrNull() }
        val installedByPackage = pm.getInstalledPackages(0).associateBy { it.packageName }
        val installed = (installedByPackage.keys + launcherByPackage.keys)
            .distinct()
            .asSequence()
            .mapNotNull { packageName ->
                val info = installedByPackage[packageName]
                    ?: runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
                val appInfo = info?.applicationInfo
                    ?: launcherByPackage[packageName]?.activityInfo?.applicationInfo
                    ?: return@mapNotNull null
                if (!includeSystem && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
                JSONObject()
                    .put("packageName", packageName)
                    .put("label", runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(packageName))
                    .put("versionName", info?.versionName.orEmpty())
                    .put("versionCode", info?.let { versionCode(it) } ?: 0L)
                    .put("system", (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                    .put("launchable", launcherByPackage.containsKey(packageName) || pm.getLaunchIntentForPackage(packageName) != null)
                    .put("approvedModule", modules.containsKey(packageName))
                    .put("approvedSkillCount", skillsByPackage[packageName].orEmpty().size)
            }
            .sortedBy { it.optString("label").lowercase() }
            .take(limit)
            .toList()

        return ok(ACTION_LIST_APPS)
            .put("totalReturned", installed.size)
            .put("limit", limit)
            .put("apps", JSONArray(installed))
    }

    private fun probeAppCapability(context: Context, intent: Intent): JSONObject {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val saveSample = intent.getBooleanExtra(EXTRA_SAVE_SAMPLE, true)
        val timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, 5000L).coerceIn(500L, 15_000L)
        return AppCapabilityProbe(context)
            .probe(packageName = packageName, saveSample = saveSample, timeoutMs = timeoutMs)
            .toJson(ACTION_PROBE_APP_CAPABILITY)
    }

    private fun createWeChatCalibrationRequest(context: Context): JSONObject {
        val probe = AppCapabilityProbe(context)
            .probe(packageName = "com.tencent.mm", saveSample = true, timeoutMs = 8_000L)
        val file = CalibrationRequestFactory(context)
            .saveWeChatSendMessageRequest(probe.state)
        return ok(ACTION_CREATE_WECHAT_CALIBRATION_REQUEST)
            .put("status", "FAILED_NEEDS_CALIBRATION")
            .put("packageName", "com.tencent.mm")
            .put("intentId", "send_message")
            .put("probeOk", probe.ok)
            .put("probeMessage", probe.message)
            .put("nodeReadability", probe.profile?.nodeReadability?.name)
            .put("inputCapability", probe.profile?.inputCapability?.name)
            .put("verificationCapability", probe.profile?.verificationCapability?.name)
            .put("runtimeExecutable", false)
            .put("file", file.absolutePath)
    }

    private fun saveCoordinatePointCandidate(context: Context, intent: Intent): JSONObject {
        val appPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val elementId = intent.getStringExtra(EXTRA_ELEMENT_ID).orEmpty()
        val state = NodeInspector(context).currentState()
        val width = intent.getIntExtra(EXTRA_SCREEN_WIDTH, state?.screenWidth ?: 0).takeIf { it > 0 }
            ?: return failed(ACTION_SAVE_COORDINATE_POINT_CANDIDATE, "Missing screen width")
        val height = intent.getIntExtra(EXTRA_SCREEN_HEIGHT, state?.screenHeight ?: 0).takeIf { it > 0 }
            ?: return failed(ACTION_SAVE_COORDINATE_POINT_CANDIDATE, "Missing screen height")
        val x = intent.getFloatExtra(EXTRA_NORMALIZED_X, -1f)
        val y = intent.getFloatExtra(EXTRA_NORMALIZED_Y, -1f)
        if (appPackage.isBlank() || elementId.isBlank() || x !in 0f..1f || y !in 0f..1f) {
            return failed(ACTION_SAVE_COORDINATE_POINT_CANDIDATE, "Missing packageName/elementId or invalid normalized coordinates")
        }
        val file = CoordinateCalibrationStore(context).saveManualPointCandidate(
            appPackage = appPackage,
            elementId = elementId,
            screenWidth = width,
            screenHeight = height,
            normalizedX = x,
            normalizedY = y,
            note = intent.getStringExtra(EXTRA_NOTE)
        )
        return ok(ACTION_SAVE_COORDINATE_POINT_CANDIDATE)
            .put("packageName", appPackage)
            .put("elementId", elementId)
            .put("runtimeExecutable", false)
            .put("file", file.absolutePath)
    }

    private fun createWeChatTypeCandidate(context: Context, intent: Intent): JSONObject {
        val state = NodeInspector(context).currentState()
        val width = intent.getIntExtra(EXTRA_SCREEN_WIDTH, state?.screenWidth ?: 1440)
        val height = intent.getIntExtra(EXTRA_SCREEN_HEIGHT, state?.screenHeight ?: 2944)
        val x = intent.getFloatExtra(EXTRA_NORMALIZED_X, 0.45f)
        val y = intent.getFloatExtra(EXTRA_NORMALIZED_Y, 0.588f)
        val result = WeChatCandidateFactory(context).createTypeDraftCandidate(
            screenWidth = width,
            screenHeight = height,
            chatInputX = x,
            chatInputY = y
        )
        return ok(ACTION_CREATE_WECHAT_TYPE_CANDIDATE)
            .put("status", "CANDIDATE_ONLY")
            .put("packageName", WeChatCandidateFactory.WECHAT_PACKAGE)
            .put("intentId", "type_message_draft")
            .put("confidence", "LOW")
            .put("result", result.toJson())
    }

    private fun listApprovedSkills(context: Context, intent: Intent): JSONObject {
        val packageFilter = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val skills = MacroTemplateStore(context).listApproved()
            .filter { packageFilter.isNullOrBlank() || it.packageName == packageFilter }
            .map { skillJson(it) }
        return ok(ACTION_LIST_APPROVED_SKILLS)
            .put("count", skills.size)
            .put("skills", JSONArray(skills))
    }

    private fun listCandidates(context: Context, intent: Intent): JSONObject {
        val limit = intent.getIntExtra(EXTRA_LIMIT, 100).coerceIn(1, 500)
        val store = CandidateKnowledgeStore(context)
        store.ensureCreated()
        val files = (
            (store.candidateSkillDir.listFiles { file -> file.isFile && file.extension == "json" } ?: emptyArray()) +
                (store.candidatePatchDir.listFiles { file -> file.isFile && file.extension == "json" } ?: emptyArray())
            )
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .map { candidateFileJson(it) }
        return ok(ACTION_LIST_CANDIDATES)
            .put("count", files.size)
            .put("candidates", JSONArray(files))
    }

    private fun generateSkillDraft(context: Context, intent: Intent): JSONObject {
        val kind = intent.getStringExtra(EXTRA_DRAFT_KIND).orEmpty().ifBlank { "search" }
        if (kind != "search") {
            return failed(ACTION_GENERATE_SKILL_DRAFT, "Only low-risk search drafts are supported in Factory v0")
        }
        val result = SkillDraftFactory(context).generateSearchDraft(
            modulePath = intent.getStringExtra(EXTRA_MODULE_PATH),
            packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME),
            queryParam = intent.getStringExtra(EXTRA_PARAM_NAME).orEmpty().ifBlank { "query" }
        )
        return ok(ACTION_GENERATE_SKILL_DRAFT)
            .put("generated", result.ok)
            .put("reason", result.reason)
            .put("file", result.file?.absolutePath)
            .put("moduleFile", result.moduleFile?.absolutePath)
    }

    private fun listSkillDrafts(context: Context, intent: Intent): JSONObject {
        val drafts = SkillDraftFactory(context).listDrafts(intent.getIntExtra(EXTRA_LIMIT, 100))
        return ok(ACTION_LIST_SKILL_DRAFTS)
            .put("count", drafts.size)
            .put("drafts", JSONArray(drafts))
    }

    private fun dryRunSkillDraft(context: Context, intent: Intent): JSONObject {
        val skillId = intent.getStringExtra(EXTRA_SKILL_ID).orEmpty()
        if (skillId.isBlank()) return failed(ACTION_DRY_RUN_SKILL_DRAFT, "Missing skillId")
        val report = SkillDraftFactory(context).dryRunDraft(
            skillId = skillId,
            modulePath = intent.getStringExtra(EXTRA_MODULE_PATH)
        )
        return ok(ACTION_DRY_RUN_SKILL_DRAFT)
            .put("passed", report.passed)
            .put("reason", report.reason)
            .put("warnings", JSONArray(report.warnings))
            .put("errors", JSONArray(report.errors))
    }

    private fun testSkillDraft(context: Context, intent: Intent): JSONObject {
        val skillId = intent.getStringExtra(EXTRA_SKILL_ID).orEmpty()
        if (skillId.isBlank()) return failed(ACTION_TEST_SKILL_DRAFT, "Missing skillId")
        val templateStore = MacroTemplateStore(context)
        val appStore = AppUiModuleStore(context)
        val template = templateStore.loadCandidate(skillId)
            ?: return failed(ACTION_TEST_SKILL_DRAFT, "Candidate skill draft not found: $skillId")
        val appUi = intent.getStringExtra(EXTRA_MODULE_PATH)
            ?.takeIf { it.isNotBlank() }
            ?.let { appStore.loadCandidateFile(it) }
            ?: appStore.listCandidates().firstOrNull { (_, module) -> module.packageName == template.packageName }?.second
            ?: appStore.loadApproved(template.packageName)
            ?: return failed(ACTION_TEST_SKILL_DRAFT, "AppUiModule not found for ${template.packageName}")

        val dryRun = SkillDraftFactory(context).dryRunDraft(skillId, intent.getStringExtra(EXTRA_MODULE_PATH))
        if (!dryRun.passed) {
            return ok(ACTION_TEST_SKILL_DRAFT)
                .put("tested", false)
                .put("reason", dryRun.reason)
                .put("dryRunErrors", JSONArray(dryRun.errors))
        }

        val paramName = intent.getStringExtra(EXTRA_PARAM_NAME)
            ?: template.params.firstOrNull()?.name
            ?: return failed(ACTION_TEST_SKILL_DRAFT, "Draft has no parameter to vary")
        val count = intent.getIntExtra(EXTRA_COUNT, 10).coerceIn(1, 20)
        val values = parseValues(intent.getStringExtra(EXTRA_VALUES), count)
        val actions = DeviceActions(context)
        val runner = SkillTestRunner(
            MacroExecutor(
                inspector = NodeInspector(context),
                profiler = CapabilityProfiler(),
                actions = actions,
                allowCandidateSkillForFactory = true
            )
        )
        val currentScreenOnly = template.atoms.firstOrNull() !is MacroAtom.OpenApp
        val report = runner.runRepeated(
            template = template,
            appUi = appUi,
            paramName = paramName,
            values = values,
            beforeEach = if (currentScreenOnly) null else {
                {
                    actions.home()
                    Thread.sleep(250L)
                }
            }
        )
        val decision = PromotionGate().evaluate(
            SkillCandidate(
                id = "candidate_${template.id}",
                template = template,
                notes = listOf("api skill draft test")
            ),
            report
        )
        val file = SkillTestReportStore(context).save(report, decision)
        return ok(ACTION_TEST_SKILL_DRAFT)
            .put("tested", true)
            .put("reportFile", file.absolutePath)
            .put("templateId", report.templateId)
            .put("totalRuns", report.totalRuns)
            .put("success", report.success)
            .put("mediumConfidence", report.mediumConfidence)
            .put("lowConfidence", report.lowConfidence)
            .put("failed", report.failed)
            .put("timeout", report.timeout)
            .put("avgDurationMs", report.avgDurationMs)
            .put("p95DurationMs", report.p95DurationMs)
            .put("promotionStatus", decision.status.name)
            .put("promotionApproved", decision.approved)
            .put("promotionReason", decision.reason)
    }

    private fun promoteSkillDraft(context: Context, intent: Intent): JSONObject {
        val skillId = intent.getStringExtra(EXTRA_SKILL_ID).orEmpty()
        if (skillId.isBlank()) return failed(ACTION_PROMOTE_SKILL_DRAFT, "Missing skillId")
        val templateStore = MacroTemplateStore(context)
        val appStore = AppUiModuleStore(context)
        val candidate = templateStore.loadCandidate(skillId)
            ?: return failed(ACTION_PROMOTE_SKILL_DRAFT, "Candidate skill draft not found: $skillId")
        val appUi = intent.getStringExtra(EXTRA_MODULE_PATH)
            ?.takeIf { it.isNotBlank() }
            ?.let { appStore.loadCandidateFile(it) }
            ?: appStore.listCandidates().firstOrNull { (_, module) -> module.packageName == candidate.packageName }?.second
            ?: appStore.loadApproved(candidate.packageName)
            ?: return failed(ACTION_PROMOTE_SKILL_DRAFT, "AppUiModule not found for ${candidate.packageName}")

        val dryRun = SkillDraftFactory(context).dryRunDraft(skillId, intent.getStringExtra(EXTRA_MODULE_PATH))
        if (!dryRun.passed) {
            return ok(ACTION_PROMOTE_SKILL_DRAFT)
                .put("promoted", false)
                .put("reason", dryRun.reason)
                .put("dryRunErrors", JSONArray(dryRun.errors))
        }

        val reportFile = resolveReportFile(context, candidate.id, intent.getStringExtra(EXTRA_REPORT_PATH))
            ?: return failed(ACTION_PROMOTE_SKILL_DRAFT, "No test report found for ${candidate.id}")
        val report = parseReport(reportFile)
            ?: return failed(ACTION_PROMOTE_SKILL_DRAFT, "Test report is invalid: ${reportFile.absolutePath}")
        val decision = PromotionGate().evaluate(
            SkillCandidate(
                id = "candidate_${candidate.id}",
                template = candidate,
                notes = listOf("explicit api promotion")
            ),
            report
        )
        if (!decision.approved) {
            return ok(ACTION_PROMOTE_SKILL_DRAFT)
                .put("promoted", false)
                .put("reason", decision.reason)
                .put("promotionStatus", decision.status.name)
                .put("reportFile", reportFile.absolutePath)
        }

        val approvedModuleExisting = appStore.loadApproved(appUi.packageName) != null
        val moduleFile = if (approvedModuleExisting) {
            null
        } else {
            appStore.saveApproved(appUi.copy(status = ModuleStatus.ACTIVE, updatedAt = System.currentTimeMillis()))
        }
        val skillFile = templateStore.saveApproved(
            candidate.copy(source = SkillSource.APPROVED)
        )
        return ok(ACTION_PROMOTE_SKILL_DRAFT)
            .put("promoted", true)
            .put("skillId", candidate.id)
            .put("skillFile", skillFile.absolutePath)
            .put("moduleFile", moduleFile?.absolutePath)
            .put("usedExistingApprovedModule", approvedModuleExisting)
            .put("promotionStatus", decision.status.name)
            .put("promotionReason", decision.reason)
            .put("reportFile", reportFile.absolutePath)
    }

    private fun factoryAiInstruction(context: Context, intent: Intent): JSONObject {
        val instruction = instructionExtra(intent)
        if (instruction.isBlank()) return failed(ACTION_FACTORY_AI_INSTRUCTION, "Missing instruction")
        val result = FactoryAiPipeline(context).analyzeInstructionAndGenerateCandidates(
            instruction = instruction,
            packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        )
        return ok(ACTION_FACTORY_AI_INSTRUCTION)
            .put("aiStatus", result.aiStatus)
            .put("message", result.message)
            .put("intentDraft", result.intentDraft)
            .put("jobFile", result.jobFile.absolutePath)
            .put("candidateCount", result.candidateFiles.size)
            .put("candidateFiles", JSONArray(result.candidateFiles.map { it.absolutePath }))
    }

    private fun runAppSideInstruction(context: Context, intent: Intent): JSONObject {
        val instruction = instructionExtra(intent)
        if (instruction.isBlank()) return failed(ACTION_RUN_APP_SIDE_INSTRUCTION, "Missing instruction")
        val targetPackageHint = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val requestId = "app_side_instruction_${System.currentTimeMillis()}"
        val appContext = context.applicationContext
        val store = FactorySkillJsonStore(appContext).apply { ensureCreated() }
        val reportPackageSegment = (targetPackageHint ?: "__pending__")
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "__pending__" }
        val expectedCompletionReportFile = File(
            File(store.flowReportDir, reportPackageSegment),
            "${requestId}_completion.json"
        )
        val pendingReport = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "app_side_ai_direct_instruction_request")
            .put("operation", "API_RUN_APP_SIDE_INSTRUCTION")
            .put("id", requestId)
            .put("requestId", requestId)
            .put("instruction", instruction)
            .put("appPackage", targetPackageHint ?: JSONObject.NULL)
            .put("status", "ACCEPTED_PENDING")
            .put("expectedCompletionReportFile", expectedCompletionReportFile.absolutePath)
            .put("reportWatchMode", "PENDING_FILE_MUTATES_TO_FINAL")
            .put("message", "手机端已接收指令，正在准备 AI/Skill JSON 执行流水线。")
            .put("createdAt", System.currentTimeMillis())
        val pendingReportFile = store.saveFlowReport(targetPackageHint ?: "__pending__", pendingReport)
        MacroPilotAccessibilityService.instance?.showStatusOverlay(
            "手机端任务已接收",
            "RUNNING",
            instruction.take(80)
        )

        fun runFlowBlocking(): JSONObject {
            val accessibilityRepair = if (MacroPilotAccessibilityService.instance == null) {
                DeviceActions(appContext).ensureAccessibilityServiceEnabled()
            } else {
                null
            }
            if (accessibilityRepair?.success == true) Thread.sleep(700L)
            val events = JSONArray()
            val report = AppSideInstructionFlowRunner(appContext) { event ->
                events.put(JSONObject()
                    .put("title", event.title)
                    .put("status", event.status)
                    .put("message", event.message)
                    .put("path", event.path))
            }.runInstructionFlow(
                instruction = instruction,
                targetPackageHint = targetPackageHint
            )
            Log.i(
                TAG,
                "APP_SIDE_INSTRUCTION_FINISHED requestId=$requestId status=${report.optString("status")} report=${report.optString("reportFile")}"
            )
            store.saveFlowReport(
                targetPackageHint ?: report.optString("appPackage", "__pending__"),
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("kind", "app_side_ai_direct_instruction_request_completion")
                    .put("operation", "API_RUN_APP_SIDE_INSTRUCTION")
                    .put("id", "${requestId}_completion")
                    .put("requestId", requestId)
                    .put("instruction", instruction)
                    .put("appPackage", targetPackageHint ?: report.optString("appPackage", ""))
                    .put("status", report.optString("status", "UNKNOWN"))
                    .put("flowReportFile", report.optString("reportFile"))
                    .put("stepCount", report.optJSONArray("steps")?.length() ?: 0)
                    .put("events", events)
                    .put("createdAt", System.currentTimeMillis())
            )
            return report
        }

        val requestedWaitForCompletion = intent.getBooleanExtra("waitForCompletion", false)
        if (requestedWaitForCompletion) {
            val report = runCatching { runFlowBlocking() }.getOrElse { error ->
                val failed = appSideInstructionFailureReport(
                    requestId = requestId,
                    instruction = instruction,
                    targetPackageHint = targetPackageHint,
                    error = error
                )
                store.saveFlowReport(targetPackageHint ?: "__pending__", failed)
                Log.e(TAG, "APP_SIDE_INSTRUCTION_FAILED requestId=$requestId", error)
                failed
            }
            return ok(ACTION_RUN_APP_SIDE_INSTRUCTION)
                .put("status", report.optString("status", "UNKNOWN"))
                .put("requestId", requestId)
                .put("appPackage", targetPackageHint)
                .put("pendingReportFile", pendingReportFile.absolutePath)
                .put("expectedCompletionReportFile", expectedCompletionReportFile.absolutePath)
                .put("reportWatchMode", "PENDING_FILE_MUTATES_TO_FINAL")
                .put("reportFile", report.optString("reportFile"))
                .put("message", "手机端同步执行完成，结果已写入 flow/runtime 报告。")
        }

        val serviceStarted = runCatching {
            AppSideInstructionService.start(appContext, requestId, instruction, targetPackageHint)
            true
        }.getOrElse { error ->
            val failed = appSideInstructionFailureReport(
                requestId = requestId,
                instruction = instruction,
                targetPackageHint = targetPackageHint,
                error = error
            )
            store.saveFlowReport(targetPackageHint ?: "__pending__", failed)
            Log.e(TAG, "APP_SIDE_INSTRUCTION_SERVICE_START_FAILED requestId=$requestId", error)
            false
        }
        return ok(ACTION_RUN_APP_SIDE_INSTRUCTION)
            .put("status", if (serviceStarted) "ACCEPTED" else "FAILED_SERVICE_START")
            .put("requestId", requestId)
            .put("appPackage", targetPackageHint)
            .put("pendingReportFile", pendingReportFile.absolutePath)
            .put("expectedCompletionReportFile", expectedCompletionReportFile.absolutePath)
            .put("waitForCompletionRequested", requestedWaitForCompletion)
            .put("waitForCompletionMode", "SERVICE_BACKGROUND_POLL_REPORT")
            .put("reportWatchMode", "PENDING_FILE_MUTATES_TO_FINAL")
            .put("message", if (serviceStarted) {
                "手机端已接收指令，Service 后台执行并写入 flow/runtime 报告；pendingReportFile 会原地更新成最终状态。"
            } else {
                "手机端已接收指令，但启动后台 Service 失败；failure 报告已写入。"
            })
    }

    private fun appSideInstructionFailureReport(
        requestId: String,
        instruction: String,
        targetPackageHint: String?,
        error: Throwable
    ): JSONObject {
        return JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "app_side_ai_direct_instruction_request_failure")
            .put("operation", "API_RUN_APP_SIDE_INSTRUCTION")
            .put("id", "${requestId}_failure")
            .put("requestId", requestId)
            .put("instruction", instruction)
            .put("appPackage", targetPackageHint ?: JSONObject.NULL)
            .put("status", "FAILED_EXCEPTION")
            .put("message", error.message ?: error.javaClass.simpleName)
            .put("errorClass", error.javaClass.name)
            .put("stackTrace", Log.getStackTraceString(error).take(12_000))
            .put("createdAt", System.currentTimeMillis())
    }

    private fun instructionExtra(intent: Intent): String {
        val encoded = intent.getStringExtra(EXTRA_INSTRUCTION_BASE64)
            ?: intent.getStringExtra("instruction_b64")
        if (!encoded.isNullOrBlank()) {
            val decoded = runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        return intent.getStringExtra(EXTRA_INSTRUCTION).orEmpty()
    }

    private fun generateWeChatSkillJson(context: Context): JSONObject {
        val store = FactorySkillJsonStore(context)
        val generated = com.macropilot.app.factory.SkillJsonRuleGenerator().generateWechatCurrentChat()
        val files = generated.map { skill ->
            store.saveSkill(skill.getString("appPackage"), skill)
        }
        return ok(ACTION_GENERATE_WECHAT_SKILL_JSON)
            .put("packageName", com.macropilot.app.factory.SkillJsonRuleGenerator.WECHAT_PACKAGE)
            .put("status", "CANDIDATE")
            .put("skillCount", generated.size)
            .put("skillIds", JSONArray(generated.map { it.optString("id") }))
            .put("files", JSONArray(files.map { it.absolutePath }))
    }

    private fun listSkillJson(context: Context, intent: Intent): JSONObject {
        val store = FactorySkillJsonStore(context)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val limit = intent.getIntExtra(EXTRA_LIMIT, 200).coerceIn(1, 1000)
        val files = store.listSkillFiles(packageName, limit)
        return ok(ACTION_LIST_SKILL_JSON)
            .put("packageName", packageName)
            .put("count", files.size)
            .put("skills", JSONArray(files.map { skillJsonFileSummary(it) }))
    }

    private fun dryRunSkillJson(context: Context, intent: Intent): JSONObject {
        val store = FactorySkillJsonStore(context)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val skillId = intent.getStringExtra(EXTRA_SKILL_ID).orEmpty()
        val all = intent.getBooleanExtra(EXTRA_DRY_RUN_ALL, false) || skillId.isBlank()
        val targets = if (all) {
            store.listSkillFiles(packageName, intent.getIntExtra(EXTRA_LIMIT, 500))
                .filter { file ->
                    runCatching { JSONObject(file.readText()).optString("status") != "APPROVED" }.getOrDefault(false)
                }
        } else {
            listOfNotNull(store.loadSkillById(skillId)?.first)
        }
        if (targets.isEmpty()) {
            return failed(ACTION_DRY_RUN_SKILL_JSON, if (all) "No Skill JSON found" else "Skill JSON not found: $skillId")
        }

        val knownIds = store.listSkillFiles()
            .mapNotNull { file -> runCatching { JSONObject(file.readText()).optString("id").takeIf { it.isNotBlank() } }.getOrNull() }
            .toSet()
        val engine = SkillJsonDryRunEngine()
        val reports = targets.mapNotNull { file ->
            val skill = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@mapNotNull null
            val report = engine.dryRun(skill, knownIds)
            val appPackage = skill.optString("appPackage", packageName.orEmpty()).ifBlank { "unknown" }
            val id = skill.optString("id", file.nameWithoutExtension)
            val reportFile = store.saveDryRunReport(appPackage, id, report)
            val factory = skill.optJSONObject("factory") ?: JSONObject()
            factory
                .put("dryRunStatus", if (report.optBoolean("passed")) "PASSED" else "FAILED")
                .put("lastDryRunReport", reportFile.absolutePath)
                .put("lastDryRunAt", System.currentTimeMillis())
            skill.put("factory", factory)
            if (report.optBoolean("passed")) {
                skill.put("status", "DRY_RUN_PASSED")
            }
            store.saveSkill(appPackage, skill)
            JSONObject()
                .put("skillId", id)
                .put("passed", report.optBoolean("passed"))
                .put("status", report.optString("status"))
                .put("errors", report.optJSONArray("errors") ?: JSONArray())
                .put("warnings", report.optJSONArray("warnings") ?: JSONArray())
                .put("reportFile", reportFile.absolutePath)
        }
        return ok(ACTION_DRY_RUN_SKILL_JSON)
            .put("count", reports.size)
            .put("passed", reports.count { it.optBoolean("passed") })
            .put("failed", reports.count { !it.optBoolean("passed") })
            .put("reports", JSONArray(reports))
    }

    private fun testSkillJson(context: Context, intent: Intent): JSONObject {
        val store = FactorySkillJsonStore(context)
        val skillId = intent.getStringExtra(EXTRA_SKILL_ID).orEmpty()
        if (skillId.isBlank()) return failed(ACTION_TEST_SKILL_JSON, "Missing skillId")
        val (_, skill) = store.loadSkillById(skillId)
            ?: return failed(ACTION_TEST_SKILL_JSON, "Skill JSON not found: $skillId")
        val dryRun = SkillJsonDryRunEngine().dryRun(
            skill,
            store.listSkillFiles().mapNotNull { file ->
                runCatching { JSONObject(file.readText()).optString("id").takeIf { it.isNotBlank() } }.getOrNull()
            }.toSet()
        )
        if (!dryRun.optBoolean("passed")) {
            return ok(ACTION_TEST_SKILL_JSON)
                .put("tested", false)
                .put("reason", "dry-run failed")
                .put("dryRunErrors", dryRun.optJSONArray("errors") ?: JSONArray())
        }

        val defaultRuns = if (skill.optString("kind") == "macro_skill") 20 else 10
        val runs = intent.getIntExtra(EXTRA_COUNT, defaultRuns).coerceIn(1, 50)
        val params = parseSkillJsonParams(intent, skill).apply {
            intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { put("text", it) }
            intent.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.isNotBlank() }?.let {
                put("message", it)
                if (!has("text")) put("text", it)
            }
        }
        val allowSideEffect = intent.getBooleanExtra(EXTRA_ALLOW_SIDE_EFFECT, false)
        val report = SkillJsonTestRunner(context).runRepeated(
            skill = skill,
            params = params,
            runs = runs,
            allowSideEffect = allowSideEffect
        )
        val appPackage = skill.optString("appPackage", "unknown")
        val id = skill.optString("id", skillId)
        val reportFile = store.saveTestReport(appPackage, id, report)
        val factory = skill.optJSONObject("factory") ?: JSONObject()
        factory
            .put("testRuns", report.optInt("testRuns"))
            .put("successRate", report.optDouble("successRate", 0.0))
            .put("lastTestReport", reportFile.absolutePath)
            .put("lastTestAt", System.currentTimeMillis())
        skill.put("factory", factory)
        if (report.optDouble("successRate", 0.0) >= 0.80 && report.optInt("failed", 0) == 0) {
            skill.put("status", "TEST_PASSED")
        }
        store.saveSkill(appPackage, skill)
        return ok(ACTION_TEST_SKILL_JSON)
            .put("tested", true)
            .put("skillId", id)
            .put("reportFile", reportFile.absolutePath)
            .put("report", report)
    }

    private fun promoteSkillJson(context: Context, intent: Intent): JSONObject {
        val store = FactorySkillJsonStore(context)
        val skillId = intent.getStringExtra(EXTRA_SKILL_ID).orEmpty()
        if (skillId.isBlank()) return failed(ACTION_PROMOTE_SKILL_JSON, "Missing skillId")
        val (_, skill) = store.loadSkillById(skillId)
            ?: return failed(ACTION_PROMOTE_SKILL_JSON, "Skill JSON not found: $skillId")
        val appPackage = skill.optString("appPackage")
        val dryRunReport = latestReportForSkill(store.listDryRunReports(appPackage, 500), skill.optString("id"))
        val testReport = latestReportForSkill(store.listTestReports(appPackage, 500), skill.optString("id"))
            ?: return failed(ACTION_PROMOTE_SKILL_JSON, "No Skill JSON test report found for ${skill.optString("id")}")
        val decision = SkillJsonPromotionGate().evaluate(skill, dryRunReport, testReport)
        val factory = skill.optJSONObject("factory") ?: JSONObject()
        factory
            .put("promotionStatus", decision.optString("status"))
            .put("promotionDecision", decision)
            .put("promotionEvaluatedAt", System.currentTimeMillis())
        skill.put("factory", factory)
        if (!decision.optBoolean("approved")) {
            store.saveSkill(appPackage, skill)
            return ok(ACTION_PROMOTE_SKILL_JSON)
                .put("promoted", false)
                .put("decision", decision)
        }

        val approvedSkill = JSONObject(skill.toString())
            .put("status", "APPROVED")
            .put("approvedLevel", decision.optString("status"))
        approvedSkill.put("factory", (approvedSkill.optJSONObject("factory") ?: JSONObject())
            .put("promotedAt", System.currentTimeMillis())
            .put("promotionStatus", decision.optString("status"))
            .put("promotionDecision", decision))
        val candidateFile = store.saveSkill(appPackage, approvedSkill)
        val promotedFile = store.savePromotedSkill(appPackage, approvedSkill)
        val runtimeSkillFile = store.saveRuntimeApprovedSkill(appPackage, approvedSkill)
        val runtimeConfigFile = refreshRuntimeApprovedConfig(context, appPackage)
        return ok(ACTION_PROMOTE_SKILL_JSON)
            .put("promoted", true)
            .put("skillId", approvedSkill.optString("id"))
            .put("candidateFile", candidateFile.absolutePath)
            .put("promotedFile", promotedFile.absolutePath)
            .put("runtimeSkillFile", runtimeSkillFile.absolutePath)
            .put("runtimeConfigFile", runtimeConfigFile?.absolutePath)
            .put("decision", decision)
    }

    private fun listApprovedSkillJson(context: Context, intent: Intent): JSONObject {
        val store = FactorySkillJsonStore(context)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val files = store.listRuntimeApprovedSkills(packageName, intent.getIntExtra(EXTRA_LIMIT, 500))
        return ok(ACTION_LIST_APPROVED_SKILL_JSON)
            .put("packageName", packageName)
            .put("count", files.size)
            .put("skills", JSONArray(files.map { skillJsonFileSummary(it) }))
    }

    private fun exportAppConfigJson(context: Context, intent: Intent): JSONObject {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (packageName.isBlank()) return failed(ACTION_EXPORT_APP_CONFIG_JSON, "Missing packageName")
        val store = FactorySkillJsonStore(context)
        val approvedOnly = intent.getBooleanExtra(EXTRA_APPROVED_ONLY, false)
        val sourceFiles = if (approvedOnly) store.listRuntimeApprovedSkills(packageName, 1000) else store.listSkillFiles(packageName, 1000)
        val skills = sourceFiles
            .mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
            .filter { if (approvedOnly) it.optString("status") == "APPROVED" else it.optString("status") != "REJECTED" }
        val dryRunReports = store.listDryRunReports(packageName, 500)
            .mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
        val testReports = store.listTestReports(packageName, 500)
            .mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
        val moduleStore = AppUiModuleStore(context)
        val module = moduleStore.loadApproved(packageName)
            ?: moduleStore.listCandidates().firstOrNull { (_, candidate) -> candidate.packageName == packageName }?.second
        val appName = module?.appName ?: runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        val config = SkillJsonExporter().exportAppConfig(
            packageName = packageName,
            appName = appName,
            module = module,
            skills = skills,
            dryRunReports = dryRunReports + testReports
        )
        val file = store.saveAppConfig(packageName, config)
        val runtimeConfigFile = if (approvedOnly) store.saveRuntimeApprovedConfig(packageName, config) else null
        val assemblyReport = SkillJsonExporter().buildAssemblyReport(
            packageName = packageName,
            appName = appName,
            approvedOnly = approvedOnly,
            sourceFiles = sourceFiles,
            includedSkills = skills,
            dryRunReportCount = dryRunReports.size,
            testReportCount = testReports.size,
            config = config,
            previewConfigFile = file,
            runtimeConfigFile = runtimeConfigFile
        )
        val assemblyReportFile = store.saveAssemblyReport(packageName, assemblyReport)
        return ok(ACTION_EXPORT_APP_CONFIG_JSON)
            .put("packageName", packageName)
            .put("appName", appName)
            .put("file", file.absolutePath)
            .put("runtimeConfigFile", runtimeConfigFile?.absolutePath)
            .put("assemblyReportFile", assemblyReportFile.absolutePath)
            .put("includedSkillCount", skills.size)
            .put("sourceSkillFileCount", sourceFiles.size)
            .put("dryRunReportCount", dryRunReports.size)
            .put("testReportCount", testReports.size)
            .put("approvedOnly", approvedOnly)
            .put("atomicSkillCount", config.optJSONArray("atomicSkills")?.length() ?: 0)
            .put("macroSkillCount", config.optJSONArray("macroSkills")?.length() ?: 0)
            .put("reactiveRuleCount", config.optJSONArray("reactiveRules")?.length() ?: 0)
            .put("runtimePolicy", config.optJSONObject("runtimePolicy"))
    }

    private fun factoryDashboard(context: Context, intent: Intent): JSONObject {
        val store = FactorySkillJsonStore(context)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val skills = store.listSkillFiles(packageName, 2000)
        val skillSummaries = skills.map { skillJsonFileSummary(it) }
        val statusCounts = JSONObject()
        val kindCounts = JSONObject()
        skillSummaries.forEach { summary ->
            val status = summary.optString("status", "UNKNOWN")
            val kind = summary.optString("kind", "unknown")
            statusCounts.put(status, statusCounts.optInt(status, 0) + 1)
            kindCounts.put(kind, kindCounts.optInt(kind, 0) + 1)
        }
        val oldStore = CandidateKnowledgeStore(context).apply { ensureCreated() }
        val trainingRecordStore = AutonomousTrainingRecordStore(context)
        val trainingRecords = trainingRecordStore.listRecords(packageName, 500)
        val oldCandidates =
            oldStore.candidateSkillDir.safeJsonCount() +
                oldStore.candidatePatchDir.safeJsonCount() +
                oldStore.candidateAppModuleDir.safeJsonCount()
        return ok(ACTION_FACTORY_DASHBOARD)
            .put("packageName", packageName)
            .put("skillJsonCandidates", skills.size)
            .put("skillJsonStatusCounts", statusCounts)
            .put("skillJsonKindCounts", kindCounts)
            .put("dryRunReports", store.listDryRunReports(packageName, 2000).size)
            .put("testReports", store.listTestReports(packageName, 2000).size)
            .put("appConfigExports", store.listAppConfigs(packageName, 500).size)
            .put("assemblyReports", store.listAssemblyReports(packageName, 500).size)
            .put("flowReports", store.listFlowReports(packageName, 500).size)
            .put("autonomousTrainingRecords", trainingRecords.size)
            .put("factoryAiJobs", store.listAiJobs(1000).size)
            .put("runtimeApprovedSkillJson", store.listRuntimeApprovedSkills(packageName, 1000).size)
            .put("runtimeRunReports", store.listRuntimeRunReports(packageName, 500).size)
            .put("runtimeConfigFile", packageName?.takeIf { it.isNotBlank() }?.let { store.runtimeApprovedConfigFile(it).absolutePath })
            .put("latestAssemblyReport", store.listAssemblyReports(packageName, 1).firstOrNull()?.absolutePath)
            .put("latestAutonomousTrainingRecord", trainingRecords.firstOrNull()?.absolutePath)
            .put("legacyFactoryCandidates", oldCandidates)
            .put("latestSkills", JSONArray(skillSummaries.take(30)))
    }

    private fun factorySelfTest(context: Context): JSONObject {
        val factoryReport = SkillFactorySelfTest(context).run()
        val architectureReport = ArchitectureSelfCheck().run().toJson()
        return ok(ACTION_FACTORY_SELF_TEST)
            .put("selfTest", factoryReport)
            .put("architectureSelfCheck", architectureReport)
            .put(
                "passed",
                factoryReport.optBoolean("passed") && architectureReport.optBoolean("passed")
            )
    }

    private fun generateThirdPartyAppSkillSubsets(context: Context, intent: Intent): JSONObject {
        return ThirdPartyAppSkillSubsetBatcher(context).generate(
            limit = intent.getIntExtra(EXTRA_LIMIT, 2000),
            launchableOnly = intent.getBooleanExtra(EXTRA_LAUNCHABLE_ONLY, false),
            clearExisting = intent.getBooleanExtra(EXTRA_CLEAR_EXISTING, false),
            runDryRun = intent.getBooleanExtra(EXTRA_RUN_DRY_RUN, true),
            exportPreviewConfig = intent.getBooleanExtra(EXTRA_EXPORT_PREVIEW_CONFIG, true),
            probeUi = intent.getBooleanExtra(EXTRA_PROBE_UI, false),
            probeLimit = intent.getIntExtra(EXTRA_PROBE_LIMIT, 80),
            packageFilter = parsePackageFilter(intent)
        )
    }

    private fun probeAppUiGraph(context: Context, intent: Intent): JSONObject {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
            .ifBlank { parsePackageFilter(intent).firstOrNull().orEmpty() }
        if (packageName.isBlank()) {
            return ok(ACTION_PROBE_APP_UI_GRAPH)
                .put("ok", false)
                .put("status", "FAILED")
                .put("error", "packageName is required")
        }
        val appName = runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            info.loadLabel(pm).toString()
        }.getOrDefault(packageName)
        val requestId = "app_ui_probe_${packageName}_${System.currentTimeMillis()}".replace(Regex("[^A-Za-z0-9._-]+"), "_")
        val maxScreens = intent.getIntExtra("maxScreens", 80)
        val maxClicks = intent.getIntExtra("maxClicks", 80)
        val maxDepth = intent.getIntExtra("maxDepth", 3)
        val maxSwipeActions = intent.getIntExtra("maxSwipeActions", 20)
        val timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, 120_000L)
        val note = intent.getStringExtra(EXTRA_NOTE).orEmpty().ifBlank { "api_probe_app_ui_graph" }
        val primaryNavigationOnly = intent.getBooleanExtra("primaryNavigationOnly", false)
        AppUiProbeService.start(
            context = context,
            requestId = requestId,
            packageName = packageName,
            appName = appName,
            note = note,
            maxScreens = maxScreens,
            maxClicks = maxClicks,
            maxDepth = maxDepth,
            maxSwipeActions = maxSwipeActions,
            timeoutMs = timeoutMs,
            primaryNavigationOnly = primaryNavigationOnly
        )
        return ok(ACTION_PROBE_APP_UI_GRAPH)
            .put("status", "STARTED")
            .put("success", JSONObject.NULL)
            .put("requestId", requestId)
            .put("appPackage", packageName)
            .put("appName", appName)
            .put("maxScreens", maxScreens)
            .put("maxClicks", maxClicks)
            .put("maxDepth", maxDepth)
            .put("maxSwipeActions", maxSwipeActions)
            .put("timeoutMs", timeoutMs)
            .put("message", "App UI graph probe is running in AppUiProbeService; pull latest flow_reports for final status.")
    }

    private fun runThirdPartyAppBatch(context: Context, intent: Intent): JSONObject {
        val requestId = "third_party_batch_${System.currentTimeMillis()}"
        val packages = parsePackageFilter(intent)
        ThirdPartyAppBatchService.start(
            context = context,
            requestId = requestId,
            packageFilter = packages,
            limit = intent.getIntExtra(EXTRA_LIMIT, packages.size.takeIf { it > 0 } ?: 20),
            probeLimit = intent.getIntExtra(EXTRA_PROBE_LIMIT, packages.size.takeIf { it > 0 } ?: 20),
            clearExisting = intent.getBooleanExtra(EXTRA_CLEAR_EXISTING, false)
        )
        val store = FactorySkillJsonStore(context).apply { ensureCreated() }
        val pending = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "third_party_app_batch_service_state")
            .put("id", requestId)
            .put("requestId", requestId)
            .put("status", "ACCEPTED")
            .put("requestedPackages", JSONArray(packages))
            .put("message", "手机端后台开始批量探索第三方 App、生成 Skill JSON 子集、dry-run 和 app_config。")
            .put("createdAt", System.currentTimeMillis())
        val pendingFile = store.saveFlowReport("__third_party_apps__", pending)
        return ok(ACTION_RUN_THIRD_PARTY_APP_BATCH)
            .put("status", "ACCEPTED")
            .put("requestId", requestId)
            .put("pendingReportFile", pendingFile.absolutePath)
            .put("requestedPackages", JSONArray(packages))
    }

    private fun cancelThirdPartyAppBatch(context: Context): JSONObject {
        ThirdPartyAppBatchService.cancel(context)
        return ok(ACTION_CANCEL_THIRD_PARTY_APP_BATCH)
            .put("status", "CANCEL_REQUESTED")
            .put("message", "已请求停止当前第三方 App UI 批量探测任务")
    }

    private fun listPopularAppTaskCatalog(context: Context, intent: Intent): JSONObject {
        val tasksPerApp = intent.getIntExtra(EXTRA_TASKS_PER_APP, 10).coerceIn(1, 20)
        val limitApps = intent.getIntExtra(EXTRA_LIMIT_APPS, 20).coerceIn(1, 50)
        val catalog = PopularAppTaskCatalog.catalogJson(
            context = context,
            packageFilter = parsePackageFilter(intent),
            limitApps = limitApps,
            tasksPerApp = tasksPerApp
        )
        val file = FactorySkillJsonStore(context).apply { ensureCreated() }
            .saveFlowReport("__popular_app_tasks__", catalog.put("id", "popular_app_task_catalog_${System.currentTimeMillis()}"))
        return ok(ACTION_LIST_POPULAR_APP_TASK_CATALOG)
            .put("status", "SUCCESS")
            .put("catalogFile", file.absolutePath)
            .put("catalog", catalog)
    }

    private fun runPopularAppTaskBatch(context: Context, intent: Intent): JSONObject {
        val requestId = intent.getStringExtra("requestId")
            ?.takeIf { it.isNotBlank() }
            ?: "popular_app_task_batch_${System.currentTimeMillis()}"
        val packages = parsePackageFilter(intent)
        val limitApps = intent.getIntExtra(EXTRA_LIMIT_APPS, intent.getIntExtra(EXTRA_LIMIT, 20)).coerceIn(1, 50)
        val tasksPerApp = intent.getIntExtra(EXTRA_TASKS_PER_APP, 10).coerceIn(1, 20)
        val catalogOnly = intent.getBooleanExtra(EXTRA_CATALOG_ONLY, false)
        val taskIds = parseCsvExtra(intent, EXTRA_TASK_IDS)
        val store = FactorySkillJsonStore(context).apply { ensureCreated() }
        val pending = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "popular_app_task_batch_service_state")
            .put("id", requestId)
            .put("requestId", requestId)
            .put("status", "ACCEPTED_PENDING_SERVICE_START")
            .put("requestedPackages", JSONArray(packages))
            .put("taskIds", JSONArray(taskIds))
            .put("limitApps", limitApps)
            .put("tasksPerApp", tasksPerApp)
            .put("catalogOnly", catalogOnly)
            .put("message", "手机端后台开始执行热门 App 任务批次：先按目录设计任务，再逐项调用 AppSideInstructionFlowRunner。")
            .put("createdAt", System.currentTimeMillis())
        val file = store.saveFlowReport("__popular_app_tasks__", pending)
        val serviceStarted = runCatching {
            PopularAppTaskBatchService.start(
                context = context,
                requestId = requestId,
                packageFilter = packages,
                limitApps = limitApps,
                tasksPerApp = tasksPerApp,
                maxTaskMs = intent.getLongExtra(EXTRA_MAX_TASK_MS, 300_000L).coerceIn(30_000L, 900_000L),
                retryFailed = intent.getBooleanExtra(EXTRA_RETRY_FAILED, true),
                catalogOnly = catalogOnly,
                taskIds = taskIds
            )
            true
        }.getOrElse { error ->
            val failed = JSONObject(pending.toString())
                .put("status", "FAILED_SERVICE_START")
                .put("message", error.message ?: error.javaClass.simpleName)
                .put("errorClass", error.javaClass.name)
                .put("createdAt", System.currentTimeMillis())
            store.saveFlowReport("__popular_app_tasks__", failed)
            Log.e(TAG, "POPULAR_APP_TASK_BATCH_SERVICE_START_FAILED requestId=$requestId", error)
            false
        }
        return ok(ACTION_RUN_POPULAR_APP_TASK_BATCH)
            .put("status", if (serviceStarted) "ACCEPTED" else "FAILED_SERVICE_START")
            .put("requestId", requestId)
            .put("pendingReportFile", file.absolutePath)
            .put("requestedPackages", JSONArray(packages))
            .put("limitApps", limitApps)
            .put("tasksPerApp", tasksPerApp)
    }

    private fun cancelPopularAppTaskBatch(context: Context): JSONObject {
        PopularAppTaskBatchService.cancel(context)
        return ok(ACTION_CANCEL_POPULAR_APP_TASK_BATCH)
            .put("status", "CANCEL_REQUESTED")
            .put("message", "已设置取消标志；如果 Android 不允许后台启动服务，正在运行的批次也会在下一个检查点停止。")
    }

    private fun parsePackageFilter(intent: Intent): List<String> {
        val raw = intent.getStringExtra(EXTRA_PACKAGE_NAMES).orEmpty()
            .ifBlank { intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty() }
        if (raw.isBlank()) return emptyList()
        return raw
            .split(",", "\n", ";", "|")
            .map { it.trim() }
            .filter { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) }
            .distinct()
    }

    private fun parseCsvExtra(intent: Intent, name: String): List<String> {
        val raw = intent.getStringExtra(name).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw
            .split(",", "\n", ";", "|")
            .map { it.trim() }
            .filter { it.matches(Regex("[A-Za-z0-9_.:-]+")) }
            .distinct()
    }

    private fun createCandidateRequest(context: Context, intent: Intent): JSONObject {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (packageName.isBlank()) {
            return failed(ACTION_CREATE_CANDIDATE_REQUEST, "Missing packageName")
        }
        val store = CandidateKnowledgeStore(context)
        store.ensureCreated()
        val id = "candidate_request_${System.currentTimeMillis()}"
        val file = File(store.candidateSkillDir, "$id.json")
        file.writeText(
            JSONObject()
                .put("id", id)
                .put("type", "SkillCandidateRequest")
                .put("packageName", packageName)
                .put("intentId", intent.getStringExtra(EXTRA_INTENT_ID))
                .put("name", intent.getStringExtra(EXTRA_NAME))
                .put("note", intent.getStringExtra(EXTRA_NOTE))
                .put("createdAt", System.currentTimeMillis())
                .toString(2)
        )
        return ok(ACTION_CREATE_CANDIDATE_REQUEST)
            .put("id", id)
            .put("file", file.absolutePath)
    }

    private fun deleteApprovedSkill(context: Context, intent: Intent): JSONObject {
        val id = intent.getStringExtra(EXTRA_SKILL_ID).orEmpty()
        if (id.isBlank()) return failed(ACTION_DELETE_APPROVED_SKILL, "Missing skillId")
        val deleted = MacroTemplateStore(context).deleteApproved(id)
        return ok(ACTION_DELETE_APPROVED_SKILL)
            .put("deleted", deleted)
            .put("skillId", id)
    }

    private fun deleteCandidate(context: Context, intent: Intent): JSONObject {
        val id = intent.getStringExtra(EXTRA_CANDIDATE_ID).orEmpty()
        if (id.isBlank()) return failed(ACTION_DELETE_CANDIDATE, "Missing candidateId")
        val store = CandidateKnowledgeStore(context)
        store.ensureCreated()
        val safeName = id.substringAfterLast('/').substringAfterLast('\\')
        val fileName = if (safeName.endsWith(".json")) safeName else "$safeName.json"
        val file = File(store.candidateSkillDir, fileName)
        val deleted = file.isFile && file.delete()
        return ok(ACTION_DELETE_CANDIDATE)
            .put("deleted", deleted)
            .put("candidateId", id)
    }

    private fun runApprovedSkill(context: Context, intent: Intent): JSONObject {
        val skillId = intent.getStringExtra(EXTRA_SKILL_ID).orEmpty()
        if (skillId.isBlank()) {
            return failed(ACTION_RUN_SKILL, "Missing skillId")
        }
        val skillJsonStore = FactorySkillJsonStore(context)
        val runtimeSkill = skillJsonStore.loadRuntimeApprovedSkillById(skillId)
        if (runtimeSkill != null) {
            val (skillFile, skillJson) = runtimeSkill
            if (skillJson.optString("status") != "APPROVED") {
                return failed(ACTION_RUN_SKILL, "Runtime can execute APPROVED Skill JSON only: $skillId")
            }
            val packageName = skillJson.optString("appPackage")
            val configFile = skillJsonStore.runtimeApprovedConfigFile(packageName)
            if (!configFile.isFile) {
                refreshRuntimeApprovedConfig(context, packageName)
            }
            val startedAt = System.currentTimeMillis()
            val params = parseSkillJsonParams(intent, skillJson)
            val allowSideEffect = intent.getBooleanExtra(EXTRA_ALLOW_SIDE_EFFECT, false)
            val result = SkillJsonExecutor(
                context = context,
                runtimeApprovedOnly = true
            ).execute(skillJson, params, allowSideEffect)
            val durationMs = System.currentTimeMillis() - startedAt
            val runReport = JSONObject()
                .put("schemaVersion", 1)
                .put("kind", "skill_json_runtime_run_report")
                .put("id", "runtime_run_${System.currentTimeMillis()}")
                .put("skillId", skillJson.optString("id"))
                .put("appPackage", packageName)
                .put("engine", "skill_json_runtime")
                .put("status", result.status)
                .put("message", result.message)
                .put("durationMs", durationMs)
                .put("usedCoordinate", result.usedCoordinate)
                .put("allowSideEffect", allowSideEffect)
                .put("params", params)
                .put("runtimeSkillFile", skillFile.absolutePath)
                .put("runtimeConfigFile", skillJsonStore.runtimeApprovedConfigFile(packageName).absolutePath)
                .put("createdAt", System.currentTimeMillis())
            val runReportFile = skillJsonStore.saveRuntimeRunReport(packageName, runReport)
            return ok(ACTION_RUN_SKILL)
                .put("skillId", skillJson.optString("id"))
                .put("engine", "skill_json_runtime")
                .put("status", result.status)
                .put("message", result.message)
                .put("durationMs", durationMs)
                .put("usedCoordinate", result.usedCoordinate)
                .put("runtimeSkillFile", skillFile.absolutePath)
                .put("runtimeConfigFile", skillJsonStore.runtimeApprovedConfigFile(packageName).absolutePath)
                .put("runReportFile", runReportFile.absolutePath)
        }
        val templateStore = MacroTemplateStore(context)
        val appStore = AppUiModuleStore(context)
        val template = templateStore.loadApproved(skillId)
            ?: return failed(ACTION_RUN_SKILL, "Approved skill not found: $skillId")
        val appUi = appStore.loadApproved(template.packageName)
            ?: return failed(ACTION_RUN_SKILL, "Approved AppUiModule not found: ${template.packageName}")
        val params = parseParams(intent, template)
        val startedAt = System.currentTimeMillis()
        val result = MacroExecutor(
            inspector = NodeInspector(context),
            profiler = CapabilityProfiler(),
            actions = DeviceActions(context)
        ).execute(template, appUi, params)
        val durationMs = System.currentTimeMillis() - startedAt
        val logFile = RunLogStore(context).save(
            templateId = template.id,
            result = result,
            durationMs = durationMs,
            note = "api_run_skill"
        )
        return ok(ACTION_RUN_SKILL)
            .put("skillId", skillId)
            .put("status", result.status.name)
            .put("confidence", result.confidence.name)
            .put("message", result.message)
            .put("durationMs", durationMs)
            .put("runLog", logFile.absolutePath)
    }

    private fun status(context: Context): JSONObject {
        val inspector = NodeInspector(context)
        val actions = DeviceActions(context)
        val repair = if (MacroPilotAccessibilityService.instance == null) {
            actions.ensureAccessibilityServiceEnabled()
        } else {
            null
        }
        if (repair?.success == true) {
            Thread.sleep(500L)
        }
        val state = inspector.currentState()
        val serviceConnected = MacroPilotAccessibilityService.instance != null
        val rawProfile = CapabilityProfiler().profile(state, serviceConnected)
        val profile = if (rawProfile.inputCapability == InputCapability.NONE && actions.isMacroPilotImeSelected()) {
            rawProfile.copy(
                inputCapability = InputCapability.CUSTOM_IME,
                reason = "${rawProfile.reason}, customIme=selected"
            )
        } else {
            rawProfile
        }
        val latestRun = RunLogStore(context).latest()
        return ok(ACTION_GET_STATUS)
            .put("serviceConnected", serviceConnected)
            .put("interactive", actions.isInteractive())
            .put("keyguardLocked", actions.isKeyguardLocked())
            .put("currentPackage", state?.appPackage)
            .put("windowClassName", state?.windowClassName)
            .put("nodeCount", state?.nodes?.size ?: 0)
            .put("clickableCount", state?.nodes?.count { it.clickable } ?: 0)
            .put("editableCount", state?.nodes?.count { it.editable } ?: 0)
            .put("nodeReadability", profile.nodeReadability.name)
            .put("inputCapability", profile.inputCapability.name)
            .put("verificationCapability", profile.verificationCapability.name)
            .put("coordinateFallback", profile.coordinateFallback.name)
            .put("serviceState", profile.serviceState.name)
            .put("macroPilotImeSelected", actions.isMacroPilotImeSelected())
            .put("macroPilotImeReady", actions.isMacroPilotImeReady())
            .put("accessibilityRepair", repair?.let { JSONObject().put("success", it.success).put("reason", it.reason) } ?: JSONObject.NULL)
            .put("reason", profile.reason)
            .put("latestRun", latestRun?.let { runCatching { JSONObject(it.readText()) }.getOrNull() })
    }

    private fun parseParams(intent: Intent, template: MacroTemplate): Map<String, String> {
        val json = intent.getStringExtra(EXTRA_PARAMS_JSON)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        return template.params.associate { param ->
            val value = json?.optString(param.name)
                ?.takeIf { it.isNotBlank() }
                ?: intent.getStringExtra(param.name)
                ?: ""
            param.name to value
        }
    }

    private fun parseSkillJsonParams(intent: Intent, skill: JSONObject): JSONObject {
        val out = parseJsonParams(intent)
        val textExtra = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        val messageExtra = intent.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.isNotBlank() }
        if (textExtra != null && !out.has("text")) out.put("text", textExtra)
        if (messageExtra != null && !out.has("message")) out.put("message", messageExtra)
        if (messageExtra == null && textExtra != null && !out.has("message")) out.put("message", textExtra)
        if (textExtra == null && messageExtra != null && !out.has("text")) out.put("text", messageExtra)

        val params = skill.optJSONArray("params") ?: return out
        for (i in 0 until params.length()) {
            val param = params.optJSONObject(i) ?: continue
            val name = param.optString("name")
            if (name.isBlank() || out.has(name)) continue
            val value = intent.getStringExtra(name) ?: when (name) {
                "text" -> textExtra
                "message" -> messageExtra
                else -> null
            }
            if (!value.isNullOrBlank()) out.put(name, value)
        }
        return out
    }

    private fun candidateFileJson(file: File): JSONObject {
        val body = runCatching { JSONObject(file.readText()) }.getOrNull()
        return JSONObject()
            .put("id", body?.optString("id")?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension)
            .put("type", body?.optString("type", "Candidate") ?: "Candidate")
            .put("packageName", body?.optString("packageName"))
            .put("name", body?.optString("name"))
            .put("aiStatus", body?.optString("aiStatus"))
            .put("candidateUsable", body?.optBoolean("candidateUsable"))
            .put("intentId", body?.optString("intentId"))
            .put("source", body?.optString("source"))
            .put("createdAt", body?.optLong("createdAt", file.lastModified()) ?: file.lastModified())
            .put("file", file.absolutePath)
    }

    private fun skillJsonFileSummary(file: File): JSONObject {
        val body = runCatching { JSONObject(file.readText()) }.getOrNull()
        val action = body?.optJSONObject("action")
        val verify = body?.optJSONObject("verify")
        val factory = body?.optJSONObject("factory")
        return JSONObject()
            .put("id", body?.optString("id")?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension)
            .put("name", body?.optString("name"))
            .put("kind", body?.optString("kind"))
            .put("implements", body?.optString("implements"))
            .put("status", body?.optString("status"))
            .put("appPackage", body?.optString("appPackage"))
            .put("intentId", body?.optString("intentId"))
            .put("actionType", action?.optString("type"))
            .put("verifyType", verify?.optString("type"))
            .put("dryRunStatus", factory?.optString("dryRunStatus"))
            .put("testRuns", factory?.optInt("testRuns", 0))
            .put("successRate", factory?.optDouble("successRate", 0.0))
            .put("promotionStatus", factory?.optString("promotionStatus"))
            .put("file", file.absolutePath)
            .put("updatedAt", file.lastModified())
    }

    private fun parseJsonParams(intent: Intent): JSONObject {
        return intent.getStringExtra(EXTRA_PARAMS_JSON)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?: JSONObject()
    }

    private fun latestReportForSkill(files: List<File>, skillId: String): JSONObject? {
        return files
            .sortedByDescending { it.lastModified() }
            .firstNotNullOfOrNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    if (json.optString("skillId") == skillId || json.optString("templateId") == skillId) json else null
                }.getOrNull()
            }
    }

    private fun refreshRuntimeApprovedConfig(context: Context, packageName: String): File? {
        val store = FactorySkillJsonStore(context)
        val sourceFiles = store.listRuntimeApprovedSkills(packageName, 1000)
        val skills = sourceFiles
            .mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
            .filter { it.optString("status") == "APPROVED" }
        if (skills.isEmpty()) return null
        val moduleStore = AppUiModuleStore(context)
        val module = moduleStore.loadApproved(packageName)
            ?: moduleStore.listCandidates().firstOrNull { (_, candidate) -> candidate.packageName == packageName }?.second
        val appName = module?.appName ?: runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        val dryRunReports = store.listDryRunReports(packageName, 500)
            .mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
        val testReports = store.listTestReports(packageName, 500)
            .mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
        val exporter = SkillJsonExporter()
        val config = exporter.exportAppConfig(
            packageName = packageName,
            appName = appName,
            module = module,
            skills = skills,
            dryRunReports = dryRunReports + testReports
        )
        val runtimeConfigFile = store.saveRuntimeApprovedConfig(packageName, config)
        val assemblyReport = exporter.buildAssemblyReport(
            packageName = packageName,
            appName = appName,
            approvedOnly = true,
            sourceFiles = sourceFiles,
            includedSkills = skills,
            dryRunReportCount = dryRunReports.size,
            testReportCount = testReports.size,
            config = config,
            previewConfigFile = null,
            runtimeConfigFile = runtimeConfigFile
        )
        store.saveAssemblyReport(packageName, assemblyReport)
        return runtimeConfigFile
    }

    private fun File.safeJsonCount(): Int {
        if (!isDirectory) return 0
        return walkTopDown().count { file -> file.isFile && file.extension == "json" }
    }

    private fun parseValues(raw: String?, count: Int): List<String> {
        val fromRaw = raw
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return if (fromRaw.isNotEmpty()) {
            fromRaw.take(count)
        } else {
            (1..count).map { "DRAFT_TEST_$it" }
        }
    }

    private fun resolveReportFile(context: Context, templateId: String, explicitPath: String?): File? {
        if (!explicitPath.isNullOrBlank()) {
            val file = File(explicitPath)
            if (file.isFile) return file
        }
        val store = CandidateKnowledgeStore(context)
        store.ensureCreated()
        return store.testReportDir
            .listFiles { file -> file.isFile && file.extension == "json" && file.name.startsWith("${templateId}_") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun parseReport(file: File): SkillTestReport? {
        return runCatching {
            val json = JSONObject(file.readText())
            val clusters = json.optJSONArray("failureClusters").toFailureClusters()
            SkillTestReport(
                templateId = json.getString("templateId"),
                totalRuns = json.getInt("totalRuns"),
                success = json.getInt("success"),
                mediumConfidence = json.getInt("mediumConfidence"),
                lowConfidence = json.getInt("lowConfidence"),
                failed = json.getInt("failed"),
                timeout = json.getInt("timeout"),
                avgDurationMs = json.getLong("avgDurationMs"),
                p95DurationMs = json.getLong("p95DurationMs"),
                failureClusters = clusters
            )
        }.getOrNull()
    }

    private fun JSONArray?.toFailureClusters(): List<FailureCluster> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let { json ->
                FailureCluster(
                    reason = json.optString("reason"),
                    count = json.optInt("count"),
                    sampleIds = json.optJSONArray("sampleIds").toStringList()
                )
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
    }

    private fun skillJson(skill: MacroTemplate): JSONObject {
        return JSONObject()
            .put("id", skill.id)
            .put("name", skill.name)
            .put("packageName", skill.packageName)
            .put("intentId", skill.intentId)
            .put("version", skill.version)
            .put("source", skill.source.name)
            .put("sideEffectLevel", skill.sideEffectLevel.name)
            .put("idempotent", skill.idempotent)
            .put("params", JSONArray(skill.params.map { param ->
                JSONObject()
                    .put("name", param.name)
                    .put("required", param.required)
                    .put("sensitive", param.sensitive)
            }))
            .put("atomCount", skill.atoms.size)
            .put("finalVerify", JSONObject()
                .put("type", skill.finalVerify.type.name)
                .put("minVerification", skill.finalVerify.minVerification.name))
    }

    private fun versionCode(info: android.content.pm.PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun ensureBuiltins(context: Context) {
        val seeder = BuiltinKnowledgeSeeder(context)
        seeder.seedSettingsSearch()
        seeder.seedSettingsScrollVisible()
        seeder.seedSettingsSelectVisible()
    }

    private fun ok(action: String): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("timestamp", System.currentTimeMillis())
    }

    private fun failed(action: String, error: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("action", action)
            .put("error", error)
            .put("timestamp", System.currentTimeMillis())
    }

    private fun respond(context: Context, pending: PendingResult, response: JSONObject) {
        val body = response.toString()
        pending.setResultCode(if (response.optBoolean("ok")) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        pending.setResultData(body)
        pending.setResultExtras(Bundle().apply { putString(EXTRA_RESULT_JSON, body) })
        runCatching {
            val store = FactorySkillJsonStore(context).apply { ensureCreated() }
            val report = JSONObject(response.toString())
                .put("schemaVersion", 1)
                .put("kind", "api_response")
                .put("id", "api_response_${response.optString("action", "unknown")}_${System.currentTimeMillis()}")
                .put("createdAt", System.currentTimeMillis())
            store.saveFlowReport(context.packageName, report)
        }
        Log.i(TAG, "API_RESPONSE $body")
        pending.finish()
    }

    companion object {
        const val ACTION_LIST_APPS = "com.macropilot.app.API_LIST_APPS"
        const val ACTION_PROBE_APP_CAPABILITY = "com.macropilot.app.API_PROBE_APP_CAPABILITY"
        const val ACTION_CREATE_WECHAT_CALIBRATION_REQUEST = "com.macropilot.app.API_CREATE_WECHAT_CALIBRATION_REQUEST"
        const val ACTION_SAVE_COORDINATE_POINT_CANDIDATE = "com.macropilot.app.API_SAVE_COORDINATE_POINT_CANDIDATE"
        const val ACTION_CREATE_WECHAT_TYPE_CANDIDATE = "com.macropilot.app.API_CREATE_WECHAT_TYPE_CANDIDATE"
        const val ACTION_LIST_APPROVED_SKILLS = "com.macropilot.app.API_LIST_APPROVED_SKILLS"
        const val ACTION_LIST_CANDIDATES = "com.macropilot.app.API_LIST_CANDIDATES"
        const val ACTION_CREATE_CANDIDATE_REQUEST = "com.macropilot.app.API_CREATE_CANDIDATE_REQUEST"
        const val ACTION_GENERATE_SKILL_DRAFT = "com.macropilot.app.API_GENERATE_SKILL_DRAFT"
        const val ACTION_LIST_SKILL_DRAFTS = "com.macropilot.app.API_LIST_SKILL_DRAFTS"
        const val ACTION_DRY_RUN_SKILL_DRAFT = "com.macropilot.app.API_DRY_RUN_SKILL_DRAFT"
        const val ACTION_TEST_SKILL_DRAFT = "com.macropilot.app.API_TEST_SKILL_DRAFT"
        const val ACTION_PROMOTE_SKILL_DRAFT = "com.macropilot.app.API_PROMOTE_SKILL_DRAFT"
        const val ACTION_FACTORY_AI_INSTRUCTION = "com.macropilot.app.API_FACTORY_AI_INSTRUCTION"
        const val ACTION_RUN_APP_SIDE_INSTRUCTION = "com.macropilot.app.API_RUN_APP_SIDE_INSTRUCTION"
        const val ACTION_GENERATE_WECHAT_SKILL_JSON = "com.macropilot.app.API_GENERATE_WECHAT_SKILL_JSON"
        const val ACTION_LIST_SKILL_JSON = "com.macropilot.app.API_LIST_SKILL_JSON"
        const val ACTION_DRY_RUN_SKILL_JSON = "com.macropilot.app.API_DRY_RUN_SKILL_JSON"
        const val ACTION_TEST_SKILL_JSON = "com.macropilot.app.API_TEST_SKILL_JSON"
        const val ACTION_PROMOTE_SKILL_JSON = "com.macropilot.app.API_PROMOTE_SKILL_JSON"
        const val ACTION_LIST_APPROVED_SKILL_JSON = "com.macropilot.app.API_LIST_APPROVED_SKILL_JSON"
        const val ACTION_EXPORT_APP_CONFIG_JSON = "com.macropilot.app.API_EXPORT_APP_CONFIG_JSON"
        const val ACTION_FACTORY_DASHBOARD = "com.macropilot.app.API_FACTORY_DASHBOARD"
        const val ACTION_FACTORY_SELF_TEST = "com.macropilot.app.API_FACTORY_SELF_TEST"
        const val ACTION_GENERATE_THIRD_PARTY_APP_SKILL_SUBSETS = "com.macropilot.app.API_GENERATE_THIRD_PARTY_APP_SKILL_SUBSETS"
        const val ACTION_PROBE_APP_UI_GRAPH = "com.macropilot.app.API_PROBE_APP_UI_GRAPH"
        const val ACTION_RUN_THIRD_PARTY_APP_BATCH = "com.macropilot.app.API_RUN_THIRD_PARTY_APP_BATCH"
        const val ACTION_CANCEL_THIRD_PARTY_APP_BATCH = "com.macropilot.app.API_CANCEL_THIRD_PARTY_APP_BATCH"
        const val ACTION_LIST_POPULAR_APP_TASK_CATALOG = "com.macropilot.app.API_LIST_POPULAR_APP_TASK_CATALOG"
        const val ACTION_RUN_POPULAR_APP_TASK_BATCH = "com.macropilot.app.API_RUN_POPULAR_APP_TASK_BATCH"
        const val ACTION_CANCEL_POPULAR_APP_TASK_BATCH = "com.macropilot.app.API_CANCEL_POPULAR_APP_TASK_BATCH"
        const val ACTION_DELETE_APPROVED_SKILL = "com.macropilot.app.API_DELETE_APPROVED_SKILL"
        const val ACTION_DELETE_CANDIDATE = "com.macropilot.app.API_DELETE_CANDIDATE"
        const val ACTION_RUN_SKILL = "com.macropilot.app.API_RUN_SKILL"
        const val ACTION_GET_STATUS = "com.macropilot.app.API_GET_STATUS"

        const val EXTRA_RESULT_JSON = "result_json"
        const val EXTRA_INSTRUCTION_BASE64 = "instructionBase64"
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_PACKAGE_NAMES = "packageNames"
        const val EXTRA_SKILL_ID = "skillId"
        const val EXTRA_CANDIDATE_ID = "candidateId"
        const val EXTRA_PARAMS_JSON = "paramsJson"
        const val EXTRA_INCLUDE_SYSTEM = "includeSystem"
        const val EXTRA_LIMIT = "limit"
        const val EXTRA_COUNT = "count"
        const val EXTRA_MODULE_PATH = "modulePath"
        const val EXTRA_DRAFT_KIND = "draftKind"
        const val EXTRA_PARAM_NAME = "paramName"
        const val EXTRA_VALUES = "values"
        const val EXTRA_REPORT_PATH = "reportPath"
        const val EXTRA_SAVE_SAMPLE = "saveSample"
        const val EXTRA_TIMEOUT_MS = "timeoutMs"
        const val EXTRA_INTENT_ID = "intentId"
        const val EXTRA_ELEMENT_ID = "elementId"
        const val EXTRA_SCREEN_WIDTH = "screenWidth"
        const val EXTRA_SCREEN_HEIGHT = "screenHeight"
        const val EXTRA_NORMALIZED_X = "normalizedX"
        const val EXTRA_NORMALIZED_Y = "normalizedY"
        const val EXTRA_NAME = "name"
        const val EXTRA_NOTE = "note"
        const val EXTRA_INSTRUCTION = "instruction"
        const val EXTRA_DRY_RUN_ALL = "dryRunAll"
        const val EXTRA_ALLOW_SIDE_EFFECT = "allowSideEffect"
        const val EXTRA_APPROVED_ONLY = "approvedOnly"
        const val EXTRA_TEXT = "text"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_LAUNCHABLE_ONLY = "launchableOnly"
        const val EXTRA_CLEAR_EXISTING = "clearExisting"
        const val EXTRA_RUN_DRY_RUN = "runDryRun"
        const val EXTRA_EXPORT_PREVIEW_CONFIG = "exportPreviewConfig"
        const val EXTRA_PROBE_UI = "probeUi"
        const val EXTRA_PROBE_LIMIT = "probeLimit"
        const val EXTRA_LIMIT_APPS = "limitApps"
        const val EXTRA_TASKS_PER_APP = "tasksPerApp"
        const val EXTRA_MAX_TASK_MS = "maxTaskMs"
        const val EXTRA_RETRY_FAILED = "retryFailed"
        const val EXTRA_CATALOG_ONLY = "catalogOnly"
        const val EXTRA_TASK_IDS = "taskIds"

        private const val TAG = "MacroPilotApi"

        private val API_ACTIONS = setOf(
            ACTION_LIST_APPS,
            ACTION_PROBE_APP_CAPABILITY,
            ACTION_CREATE_WECHAT_CALIBRATION_REQUEST,
            ACTION_SAVE_COORDINATE_POINT_CANDIDATE,
            ACTION_CREATE_WECHAT_TYPE_CANDIDATE,
            ACTION_LIST_APPROVED_SKILLS,
            ACTION_LIST_CANDIDATES,
            ACTION_CREATE_CANDIDATE_REQUEST,
            ACTION_GENERATE_SKILL_DRAFT,
            ACTION_LIST_SKILL_DRAFTS,
            ACTION_DRY_RUN_SKILL_DRAFT,
            ACTION_TEST_SKILL_DRAFT,
            ACTION_PROMOTE_SKILL_DRAFT,
            ACTION_FACTORY_AI_INSTRUCTION,
            ACTION_RUN_APP_SIDE_INSTRUCTION,
            ACTION_GENERATE_WECHAT_SKILL_JSON,
            ACTION_LIST_SKILL_JSON,
            ACTION_DRY_RUN_SKILL_JSON,
            ACTION_TEST_SKILL_JSON,
            ACTION_PROMOTE_SKILL_JSON,
            ACTION_LIST_APPROVED_SKILL_JSON,
            ACTION_EXPORT_APP_CONFIG_JSON,
            ACTION_FACTORY_DASHBOARD,
            ACTION_FACTORY_SELF_TEST,
            ACTION_GENERATE_THIRD_PARTY_APP_SKILL_SUBSETS,
            ACTION_PROBE_APP_UI_GRAPH,
            ACTION_RUN_THIRD_PARTY_APP_BATCH,
            ACTION_CANCEL_THIRD_PARTY_APP_BATCH,
            ACTION_LIST_POPULAR_APP_TASK_CATALOG,
            ACTION_RUN_POPULAR_APP_TASK_BATCH,
            ACTION_CANCEL_POPULAR_APP_TASK_BATCH,
            ACTION_DELETE_APPROVED_SKILL,
            ACTION_DELETE_CANDIDATE,
            ACTION_RUN_SKILL,
            ACTION_GET_STATUS
        )
    }
}
