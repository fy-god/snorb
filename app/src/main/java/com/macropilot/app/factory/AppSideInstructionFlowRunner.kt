package com.macropilot.app.factory

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import com.macropilot.app.ai.AiAssistClient
import com.macropilot.app.ai.AiAssistConfigStore
import com.macropilot.app.ai.AiAssistRequest
import com.macropilot.app.ai.AiAssistStatus
import com.macropilot.app.ai.AiAssistUseCase
import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.model.NormalizedPoint
import com.macropilot.app.runtime.CapabilityProfiler
import com.macropilot.app.runtime.NodeInspector
import com.macropilot.app.store.AppUiGraphStore
import com.macropilot.app.store.AppUiModuleStore
import com.macropilot.app.store.AutonomousTrainingRecordStore
import com.macropilot.app.store.FactorySkillJsonStore
import com.macropilot.app.store.UiStateSnapshotStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale

class AppSideInstructionFlowRunner(
    private val context: Context,
    private val onEvent: (AppSideFactoryFlowEvent) -> Unit
) {
    private val store = FactorySkillJsonStore(context).apply { ensureCreated() }
    private val inspector = NodeInspector(context)
    private val profiler = CapabilityProfiler()
    private val actions = DeviceActions(context)
    private val reactiveRules = ReactiveUiRuleEngine(inspector, actions)
    private val adaptivePlanner = SimilarSkillAdaptivePlanner(context, store, inspector)
    private val snapshotStore = UiStateSnapshotStore(context)
    private val appUiGraphStore = AppUiGraphStore(context)
    private val trainingRecordStore = AutonomousTrainingRecordStore(context)
    private val appUiExplorer = AppUiExplorer(context, actions, inspector, profiler, snapshotStore, appUiGraphStore, reactiveRules)
    private val patchAssembler = SkillJsonPatchAssembler(context, store)

    fun runInstructionFlow(
        instruction: String,
        targetPackageHint: String? = null
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        val steps = JSONArray()
        var finalStatus = "SUCCESS"
        val explicitTargetPackage = targetPackageHint
            ?.trim()
            ?.takeIf { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) }
        var appPackage = normalizeTargetPackage(instruction, targetPackageHint)
        val taskInstruction = primaryInstructionText(instruction)
        var aiJobFile: File? = null
        var previewConfigFile: File? = null
        var runtimeRunReportFile: File? = null
        var flowReportFile: File? = null
        var executedSkillId = ""
        val aiJobFiles = JSONArray()
        var currentFlowSkillFiles: List<File> = emptyList()
        var runtimeVerifiedHupuPublish = false
        var runtimeVerifiedWechatMomentSubmit = false
        var finalGoalCheck: GoalCheck? = null

        fun step(title: String, status: String, message: String, path: String? = null, extra: JSONObject? = null) {
            if (status.startsWith("FAILED")) finalStatus = "FAILED"
            val item = JSONObject()
                .put("title", title)
                .put("status", status)
                .put("message", message)
                .put("path", path ?: JSONObject.NULL)
                .put("timestamp", System.currentTimeMillis())
            if (extra != null) item.put("extra", extra)
            steps.put(item)
            MacroPilotAccessibilityService.instance?.showStatusOverlay(title, status, message)
            onEvent(AppSideFactoryFlowEvent(title, status, message, path))
        }

        step(
            "用户指令进入手机端执行器",
            "RUNNING",
            "主输入不再只生成文件：App 将调用 AI，生成可审计 JSON，再由 SkillJsonExecutor 直接执行候选能力。"
        )

        try {
            if (appPackage.isNotBlank()) {
                val graphSummary = ensureAppUiGraphForInstruction(
                    instruction = instruction,
                    appPackage = appPackage,
                    step = ::step
                )
                if (graphSummary != null) {
                    step(
                        "AppUI 图谱已进入决策上下文",
                        "INFO",
                        "已知页面=${graphSummary.optInt("screenCount")}，边=${graphSummary.optInt("edgeCount")}；AI/模板改写会优先参考入口、底部分屏、输入框和可点击候选。",
                        graphSummary.optString("path").takeIf { it.isNotBlank() },
                        graphSummary
                    )
                }
            }
            if (requiresAccessibilityControl(taskInstruction)) {
                val readiness = executionReadiness(appPackage, taskInstruction)
                if (!readiness.optBoolean("ready")) {
                    step(
                        "手机控制前置检查不通过",
                        readiness.optString("status").ifBlank { "FAILED_DEVICE_NOT_READY" },
                        readiness.optString("message"),
                        null,
                        readiness
                    )
                    return finishReport(
                        instruction,
                        appPackage,
                        finalStatus,
                        startedAt,
                        steps,
                        aiJobFile,
                        previewConfigFile,
                        runtimeRunReportFile,
                        aiJobFiles
                    )
                }
            }
            var adaptivePlan = adaptivePlanner.plan(taskInstruction, explicitTargetPackage ?: appPackage)
            var ai: AiPlanResult = localAdaptiveAiPlanPlaceholder(taskInstruction, appPackage)
            if (adaptivePlan != null) {
                step(
                    "手机端相似 Skill 自适应规划",
                    "SUCCESS",
                    "本机先复用抖音/小红书成功模式：识别 intent=${adaptivePlan.intentId}，改写出 ${adaptivePlan.planSteps.size} 个细粒度步骤；AI v4pro 只在失败或二次判断时接管。",
                    null,
                    adaptivePlan.evidence
                )
                if (shouldAiReviewBeforeExecution(taskInstruction, appPackage) &&
                    !isWechatMomentPrivateInstruction(taskInstruction, explicitTargetPackage ?: appPackage)
                ) {
                    val planForReview = adaptivePlan
                    val review = requestAiPlanReview(
                        instruction = instruction,
                        appPackage = explicitTargetPackage ?: appPackage,
                        intentId = planForReview.intentId,
                        proposedPlanSteps = planForReview.planSteps
                    )
                    aiJobFiles.put(review.jobFile.absolutePath)
                    step(
                        "v4pro 执行前复核",
                        review.status,
                        "模型=${review.model.ifBlank { "未返回" }}；HTTP=${review.httpStatus ?: "-"}；patchedSteps=${review.planSteps.size}；usePatched=${review.usePatchedSteps}；${review.decision.optString("nextActionReason").take(90)}",
                        review.jobFile.absolutePath,
                        JSONObject().put("decision", review.decision)
                    )
                    if (review.status == AiAssistStatus.SUCCESS.name && review.usePatchedSteps && review.planSteps.isNotEmpty()) {
                        val reviewedEvidence = JSONObject(planForReview.evidence.toString())
                            .put("aiPlanReviewJob", review.jobFile.absolutePath)
                            .put("aiPlanReviewDecision", review.decision)
                            .put("aiPatchedStepCount", review.planSteps.size)
                        adaptivePlan = planForReview.copy(
                            planSteps = review.planSteps,
                            evidence = reviewedEvidence
                        )
                        step(
                            "v4pro 复核已改写计划",
                            "SUCCESS",
                            "执行前 AI 认为本地相似计划需要改写，已用 ${review.planSteps.size} 个复核步骤重新生成 Skill JSON。",
                            review.jobFile.absolutePath,
                            reviewedEvidence
                        )
                    }
                }
            } else if (shouldRunRuleFirst(taskInstruction, explicitTargetPackage ?: appPackage)) {
                step(
                    "规则优先执行",
                    "INFO",
                    "已识别为本地有 Skill 子集/成功案例的任务：先直接生成候选 JSON 并执行；失败或目标校验不过再调用 v4pro 二次判断，避免一上来卡在 AI plan。",
                    null,
                    JSONObject()
                        .put("appPackage", explicitTargetPackage ?: appPackage)
                        .put("instruction", taskInstruction)
                )
            } else {
                ai = requestAiPlan(taskInstruction, appPackage, explicitTargetPackage)
                aiJobFile = ai.jobFile
                aiJobFiles.put(ai.jobFile.absolutePath)
                val aiRoot = ai.root
                step(
                    "AI 决策返回",
                    ai.status,
                    "HTTP=${ai.httpStatus ?: "-"}；模型=${ai.model.ifBlank { "未返回" }}；计划步骤=${ai.planSteps.size}；AI候选=${ai.candidates.size}",
                    ai.jobFile.absolutePath,
                    JSONObject().put("intentDraft", ai.intentDraft)
                )
            }

            appPackage = explicitTargetPackage ?: ai?.intentDraft?.optString("appPackage")?.ifBlank { appPackage } ?: appPackage
            if (appPackage.isBlank()) {
                appPackage = inspector.currentState()?.appPackage.orEmpty()
            }
            if (appPackage.isBlank()) {
                step("目标应用判断失败", "FAILED_PRECONDITION", "AI 和规则都没有判断出目标包名。请在目标包名输入框写包名。", ai.jobFile.absolutePath)
                return finishReport(instruction, appPackage, finalStatus, startedAt, steps, aiJobFile, previewConfigFile, runtimeRunReportFile, aiJobFiles)
            }

            val savedAiCandidates = ai.candidates.map { candidate ->
                store.saveSkill(candidate.optString("appPackage", appPackage).ifBlank { appPackage }, candidate)
            }
            if (savedAiCandidates.isNotEmpty()) {
                step("AI 候选 Skill JSON 已保存", "SUCCESS", "保存 ${savedAiCandidates.size} 个 AI 产出的 candidate skill。", savedAiCandidates.first().absolutePath)
            }

            val macroAndParams = when {
                adaptivePlan != null -> {
                    val built = buildDirectPlanSkills(instruction, appPackage, adaptivePlan.planSteps)
                    currentFlowSkillFiles = built.skillFiles
                    step(
                        "相似成功案例改写为 Skill JSON",
                        "SUCCESS",
                        "不靠 ADB 拼流程；App 底层把 intent=${adaptivePlan.intentId} 拆成 ${built.skillFiles.size} 个 candidate JSON，之后统一 dry-run、执行、失败再让 v4pro 二次判断。",
                        built.macroFile.absolutePath,
                        adaptivePlan.evidence
                    )
                    built.macroFile to JSONObject()
                }
                isDouyinVideoPublishInstruction(taskInstruction, appPackage) -> {
                    val built = buildDirectPlanSkills(instruction, appPackage, douyinVideoPublishSteps(instruction, appPackage))
                    currentFlowSkillFiles = built.skillFiles
                    step(
                        "\u89c4\u5219\u6cdb\u5316\u6296\u97f3\u53d1\u89c6\u9891\u4efb\u52a1",
                        "SUCCESS",
                        "\u4e13\u7528\u7ec6\u7c92\u5ea6 JSON\uff1a\u751f\u6210\u201c\u6211\u662fAI\u201d\u77ed\u89c6\u9891\u7d20\u6750 -> \u6253\u5f00\u6296\u97f3 -> \u8fdb\u5165\u53d1\u5e03 -> \u9009\u6700\u65b0\u89c6\u9891 -> \u586b\u5199\u6587\u6848 -> \u70b9\u51fb\u53d1\u5e03\u3002",
                        built.macroFile.absolutePath
                    )
                    built.macroFile to JSONObject()
                }
                isBilibiliCommentInstruction(instruction, appPackage) -> {
                    val built = buildDirectPlanSkills(instruction, appPackage, bilibiliCommentDraftSteps(instruction))
                    currentFlowSkillFiles = built.skillFiles
                    step(
                        "规则泛化 B 站评论草稿",
                        "SUCCESS",
                        "AI 已返回决策；评论任务改用专用细粒度 JSON：搜索 -> 打开结果 -> 评论框 -> 输入草稿；默认不点发布。",
                        built.macroFile.absolutePath
                    )
                    built.macroFile to JSONObject()
                }
                isBilibiliSearchInstruction(instruction, appPackage) -> {
                    val query = extractSearchQuery(instruction).ifBlank { "猫和老鼠" }
                    val files = SkillJsonRuleGenerator().generateBilibiliSearch(query).map { skill ->
                        store.saveSkill(skill.getString("appPackage"), skill)
                    }
                    currentFlowSkillFiles = files
                    step("规则泛化 B 站搜索 Skill", "SUCCESS", "补齐 ${files.size} 个细粒度 Skill JSON，搜索词=$query；复杂任务会在搜索结果页再让 AI 判断下一步。", files.firstOrNull()?.absolutePath)
                    loadMacroWithParams("bilibili.search.search_in_app", JSONObject().put("query", query))
                }
                isXiaohongshuPostInstruction(instruction, appPackage) -> {
                    val publishNow = isXiaohongshuExplicitPublishInstruction(instruction, appPackage)
                    val built = buildDirectPlanSkills(instruction, appPackage, xiaohongshuPostDraftSteps(instruction))
                    currentFlowSkillFiles = built.skillFiles
                    step(
                        if (publishNow) "规则泛化小红书发布任务" else "规则泛化小红书笔记草稿",
                        "SUCCESS",
                        if (publishNow) {
                            "专用细粒度 JSON：进入创作页 -> 填写内容 -> 下一步 -> 点击最终发布；发布按钮只因本次用户明确要求才放行。"
                        } else {
                            "专用细粒度 JSON：打开小红书 -> 进入创作入口 -> 选择文字/图文入口 -> 填写标题和正文；默认停在草稿页，不点最终发布。"
                        },
                        built.macroFile.absolutePath
                    )
                    built.macroFile to JSONObject()
                }
                isHupuExplicitPublishInstruction(instruction, appPackage) -> {
                    val built = buildDirectPlanSkills(instruction, appPackage, hupuPublishPostSteps(instruction, appPackage))
                    currentFlowSkillFiles = built.skillFiles
                    step(
                        "\u89c4\u5219\u6cdb\u5316\u864e\u6251\u53d1\u5e16\u4efb\u52a1",
                        "SUCCESS",
                        "\u4e13\u7528\u7ec6\u7c92\u5ea6 JSON\uff1a\u56de\u5230\u864e\u6251\u4e3b\u9875 -> \u626b\u5e95\u90e8\u5206\u9875\u627e\u53d1\u5e16\u5165\u53e3 -> \u9009\u4e13\u533a -> \u8f93\u5165\u5185\u5bb9 -> \u6309\u7528\u6237\u660e\u786e\u8981\u6c42\u53d1\u5e03\u3002",
                        built.macroFile.absolutePath
                    )
                    built.macroFile to JSONObject()
                }
                ai.planSteps.isNotEmpty() -> {
                    val built = buildDirectPlanSkills(instruction, appPackage, ai.planSteps)
                    currentFlowSkillFiles = built.skillFiles
                    step(
                        "AI 计划转 Skill JSON",
                        "SUCCESS",
                        "AI 返回 ${ai.planSteps.size} 个动作步骤，已转换成 ${built.skillFiles.size} 个候选 Skill JSON。",
                        built.macroFile.absolutePath
                    )
                    built.macroFile to JSONObject()
                }
                isWechatCurrentChatInstruction(instruction, appPackage) -> {
                    val message = extractMessage(instruction).ifBlank { "111" }
                    val files = SkillJsonRuleGenerator().generateWechatCurrentChat(message).map { skill ->
                        store.saveSkill(skill.getString("appPackage"), skill)
                    }
                    currentFlowSkillFiles = files
                    step("规则泛化微信当前聊天 Skill", "SUCCESS", "补齐 ${files.size} 个细粒度 Skill JSON，准备直接执行候选 macro。", files.firstOrNull()?.absolutePath)
                    loadMacroWithParams("wechat.chat.send_in_current_chat", JSONObject().put("message", message))
                }
                else -> {
                    val planSteps = heuristicSteps(instruction, appPackage)
                    if (planSteps.isEmpty()) {
                        val bootstrap = requestAiBootstrapPlan(
                            instruction = instruction,
                            appPackage = appPackage,
                            reason = "AI_INITIAL_EMPTY_AND_NO_RULE_SKILL"
                        )
                        aiJobFiles.put(bootstrap.jobFile.absolutePath)
                        step(
                            "无 Skill 启动器：AI 拆小步骤",
                            bootstrap.status,
                            "首次 AI 没有 steps，已启动 DeepSeek/OpenAI-compatible 二阶段拆解：HTTP=${bootstrap.httpStatus ?: "-"}；模型=${bootstrap.model.ifBlank { "未返回" }}；步骤=${bootstrap.planSteps.size}；候选=${bootstrap.candidates.size}",
                            bootstrap.jobFile.absolutePath,
                            JSONObject().put("intentDraft", bootstrap.intentDraft)
                        )
                        val bootstrapCandidates = bootstrap.candidates.map { candidate ->
                            store.saveSkill(candidate.optString("appPackage", appPackage).ifBlank { appPackage }, candidate)
                        }
                        if (bootstrapCandidates.isNotEmpty()) {
                            step(
                                "无 Skill 候选 JSON 已保存",
                                "SUCCESS",
                                "AI bootstrap 保存 ${bootstrapCandidates.size} 个细粒度 candidate skill。",
                                bootstrapCandidates.first().absolutePath
                            )
                        }
                        if (bootstrap.planSteps.isEmpty()) {
                            step(
                                "动作计划为空",
                                "FAILED_NO_CANDIDATE",
                                "两次 AI 都没有给出可执行 steps，规则也没有兜底动作。本次不会假装成功。",
                                bootstrap.jobFile.absolutePath
                            )
                            null
                        } else {
                            val built = buildDirectPlanSkills(instruction, appPackage, bootstrap.planSteps)
                            currentFlowSkillFiles = bootstrapCandidates + built.skillFiles
                            step(
                                "AI 拆解转 Skill JSON",
                                "SUCCESS",
                                "AI bootstrap 返回 ${bootstrap.planSteps.size} 个小动作，已转换成 ${built.skillFiles.size} 个候选 Skill JSON。",
                                built.macroFile.absolutePath
                            )
                            built.macroFile to JSONObject()
                        }
                    } else {
                        val built = buildDirectPlanSkills(instruction, appPackage, planSteps)
                        currentFlowSkillFiles = built.skillFiles
                        built.skillFiles.forEach { file ->
                            step("写入直接执行 Skill", "SUCCESS", file.name, file.absolutePath)
                        }
                        built.macroFile to JSONObject()
                    }
                }
            }

            if (macroAndParams == null) {
                return finishReport(instruction, appPackage, finalStatus, startedAt, steps, aiJobFile, previewConfigFile, runtimeRunReportFile, aiJobFiles)
            }

            if (requiresAccessibilityControl(instruction) && MacroPilotAccessibilityService.instance == null) {
                step(
                    "无障碍未连接，停止执行",
                    "FAILED_ACCESSIBILITY_DISCONNECTED",
                    "当前手机没有启用 MacroPilot 无障碍服务。搜索、点击、点赞、评论都不能控制手机；本次不会再把 OpenApp/Wait 误报为成功。",
                    null,
                    JSONObject()
                        .put("serviceConnected", false)
                        .put("inputCapability", "NONE")
                        .put("canOnlyOpenApp", true)
                )
                return finishReport(instruction, appPackage, finalStatus, startedAt, steps, aiJobFile, previewConfigFile, runtimeRunReportFile, aiJobFiles)
            }

            val (macroFile, params) = macroAndParams
            val macro = JSONObject(macroFile.readText())
            appPackage = macro.optString("appPackage").ifBlank { appPackage }
            executedSkillId = macro.optString("id", macroFile.nameWithoutExtension)

            val dry = if (currentFlowSkillFiles.isNotEmpty()) {
                dryRunFiles(appPackage, currentFlowSkillFiles)
            } else {
                dryRunPackage(appPackage)
            }
            step("dry-run 静态检查", if (dry.passed == dry.total) "SUCCESS" else "FAILED_DRY_RUN", "通过 ${dry.passed}/${dry.total}。", dry.lastReportPath)

            previewConfigFile = exportPreview(appPackage)
            step(
                "拼装 app_config 预览",
                if (previewConfigFile == null) "FAILED_RUNTIME_CONFIG" else "SUCCESS",
                if (previewConfigFile == null) "没有可拼装的 Skill JSON。" else "候选/测试产物已拼为单个 app_config 预览。",
                previewConfigFile?.absolutePath
            )

            val runStarted = System.currentTimeMillis()
            step("执行候选 JSON", "RUNNING", "不等待 APPROVED：按用户要求，直接由 SkillJsonExecutor 执行候选 macro=$executedSkillId。", macroFile.absolutePath)
            val result = SkillJsonExecutor(context, runtimeApprovedOnly = false)
                .execute(macro, params, allowSideEffect = true)
            val runReport = JSONObject()
                .put("schemaVersion", 1)
                .put("kind", "skill_json_ai_direct_run_report")
                .put("id", "ai_direct_run_${System.currentTimeMillis()}")
                .put("skillId", executedSkillId)
                .put("appPackage", appPackage)
                .put("instruction", instruction)
                .put("engine", "ai_direct_skill_json")
                .put("approvedRequired", false)
                .put("status", result.status)
                .put("message", result.message)
                .put("durationMs", System.currentTimeMillis() - runStarted)
                .put("usedCoordinate", result.usedCoordinate)
                .put("details", result.details ?: JSONObject.NULL)
                .put("allowSideEffect", true)
                .put("params", params)
                .put("skillFile", macroFile.absolutePath)
                .put("previewConfigFile", previewConfigFile?.absolutePath ?: JSONObject.NULL)
                .put("createdAt", System.currentTimeMillis())
            runtimeRunReportFile = store.saveRuntimeRunReport(appPackage, runReport)
            step(
                "候选 JSON 执行结果",
                if (result.status.startsWith("FAILED")) result.status else "SUCCESS",
                "$executedSkillId -> ${result.status}；${result.message}",
                runtimeRunReportFile?.absolutePath
            )
            runtimeVerifiedHupuPublish =
                isHupuExplicitPublishInstruction(instruction, appPackage) &&
                    !result.status.startsWith("FAILED")
            runtimeVerifiedWechatMomentSubmit =
                isWechatMomentPrivateInstruction(instruction, appPackage) &&
                    !result.status.startsWith("FAILED") &&
                    atomTraceContains(result.details, "WechatMomentPublishSubmitted")
            if (!result.status.startsWith("FAILED")) {
                if (finalStatus != "FAILED") finalStatus = "SUCCESS"
            }

            var handledByAiFollowUp = false
            if (!result.status.startsWith("FAILED") && shouldRunBilibiliOpenFirstFallback(instruction, appPackage)) {
                val fallback = runBilibiliOpenFirstVideoFallback(instruction, appPackage, step = ::step)
                if (fallback != null) {
                    runtimeRunReportFile = fallback
                    handledByAiFollowUp = true
                    finalStatus = "SUCCESS_WITH_LOCAL_VIDEO_OPEN"
                }
            }
            if (!handledByAiFollowUp && result.status.startsWith("FAILED") && isBilibiliCommentInstruction(taskInstruction, appPackage)) {
                val fallback = runBilibiliCommentDraftFallback(taskInstruction, appPackage, step = ::step)
                if (fallback != null) {
                    runtimeRunReportFile = fallback
                    handledByAiFollowUp = true
                    finalStatus = "SUCCESS_WITH_FALLBACK"
                }
            } else if (!handledByAiFollowUp && result.status.startsWith("FAILED") && shouldRunXiaohongshuDraftFallback(taskInstruction, appPackage)) {
                val fallback = if (isXiaohongshuExplicitPublishInstruction(taskInstruction, appPackage)) {
                    runXiaohongshuPublishFallback(taskInstruction, appPackage, step = ::step)
                } else {
                    runXiaohongshuDraftFallback(taskInstruction, appPackage, step = ::step)
                }
                if (fallback != null) {
                    runtimeRunReportFile = fallback
                    handledByAiFollowUp = true
                    finalStatus = "SUCCESS_WITH_FALLBACK"
                }
            } else if (!handledByAiFollowUp && result.status.startsWith("FAILED") && isBilibiliSearchInstruction(taskInstruction, appPackage)) {
                val fallback = runBilibiliRuleFallback(taskInstruction, appPackage, step = ::step)
                if (fallback != null) {
                    runtimeRunReportFile = fallback
                    handledByAiFollowUp = true
                    finalStatus = "SUCCESS_WITH_FALLBACK"
                }
            } else if (!handledByAiFollowUp && result.status.startsWith("FAILED") && isGenericSearchInstruction(taskInstruction)) {
                val fallback = runGenericSearchFallback(taskInstruction, appPackage, step = ::step)
                if (fallback != null) {
                    runtimeRunReportFile = fallback
                    handledByAiFollowUp = true
                    finalStatus = "SUCCESS_WITH_FALLBACK"
                }
            }
            if (!handledByAiFollowUp && result.status.startsWith("FAILED")) {
                tryDismissBlockersBeforeReplan(
                    instruction = instruction,
                    appPackage = appPackage,
                    trigger = "EXECUTION_FAILED",
                    previousReportFile = runtimeRunReportFile,
                    step = ::step
                )
                val recovery = runAiFollowUp(
                    instruction = instruction,
                    appPackage = appPackage,
                    failedSkillId = executedSkillId,
                    trigger = "EXECUTION_FAILED",
                    phase = "REPLAN",
                    previousResult = result,
                    previousReportFile = runtimeRunReportFile,
                    flowSteps = steps,
                    step = ::step
                )
                recovery?.aiJobFile?.absolutePath?.let { aiJobFiles.put(it) }
                if (recovery?.runtimeReportFile != null) {
                    runtimeRunReportFile = recovery.runtimeReportFile
                    handledByAiFollowUp = recovery.success
                    if (recovery.success) finalStatus = "SUCCESS_WITH_AI_REPLAN"
                }
            } else if (!handledByAiFollowUp &&
                isPinduoduoPackage(appPackage) &&
                isGenericSearchInstruction(taskInstruction)
            ) {
                val earlyGoal = verifyInstructionGoal(taskInstruction, appPackage)
                if (!earlyGoal.passed) {
                    val fallback = runPinduoduoTextSearchFallback(taskInstruction, appPackage, step = ::step)
                    if (fallback != null) {
                        runtimeRunReportFile = fallback
                        handledByAiFollowUp = true
                        finalStatus = "SUCCESS_WITH_FALLBACK"
                    }
                }
            } else if (!handledByAiFollowUp && needsRuntimeCheckpoint(instruction, result)) {
                tryDismissBlockersBeforeReplan(
                    instruction = instruction,
                    appPackage = appPackage,
                    trigger = "COMPLEX_TASK_CHECKPOINT",
                    previousReportFile = runtimeRunReportFile,
                    step = ::step
                )
                val checkpoint = runAiFollowUp(
                    instruction = instruction,
                    appPackage = appPackage,
                    failedSkillId = executedSkillId,
                    trigger = "COMPLEX_TASK_CHECKPOINT",
                    phase = "CHECKPOINT",
                    previousResult = result,
                    previousReportFile = runtimeRunReportFile,
                    flowSteps = steps,
                    step = ::step
                )
                checkpoint?.aiJobFile?.absolutePath?.let { aiJobFiles.put(it) }
                if (checkpoint?.runtimeReportFile != null) {
                    runtimeRunReportFile = checkpoint.runtimeReportFile
                    handledByAiFollowUp = checkpoint.success
                    if (checkpoint.success) finalStatus = "SUCCESS_WITH_AI_CHECKPOINT"
                } else if (checkpoint?.success == true) {
                    handledByAiFollowUp = true
                }
            }

            if (!handledByAiFollowUp && result.status.startsWith("FAILED") && isBilibiliCommentInstruction(instruction, appPackage)) {
                val fallback = runBilibiliCommentDraftFallback(instruction, appPackage, step = ::step)
                if (fallback != null) {
                    runtimeRunReportFile = fallback
                    finalStatus = "SUCCESS_WITH_FALLBACK"
                }
            } else if (!handledByAiFollowUp && result.status.startsWith("FAILED") && shouldRunXiaohongshuDraftFallback(instruction, appPackage)) {
                val fallback = if (isXiaohongshuExplicitPublishInstruction(instruction, appPackage)) {
                    runXiaohongshuPublishFallback(instruction, appPackage, step = ::step)
                } else {
                    runXiaohongshuDraftFallback(instruction, appPackage, step = ::step)
                }
                if (fallback != null) {
                    runtimeRunReportFile = fallback
                    finalStatus = "SUCCESS_WITH_FALLBACK"
                }
            } else if (!handledByAiFollowUp && result.status.startsWith("FAILED") && isBilibiliSearchInstruction(instruction, appPackage)) {
                val fallback = runBilibiliRuleFallback(instruction, appPackage, step = ::step)
                if (fallback != null) {
                    runtimeRunReportFile = fallback
                    finalStatus = "SUCCESS_WITH_FALLBACK"
                }
            } else if (!handledByAiFollowUp && result.status.startsWith("FAILED") && isGenericSearchInstruction(instruction)) {
                val fallback = runGenericSearchFallback(instruction, appPackage, step = ::step)
                if (fallback != null) {
                    runtimeRunReportFile = fallback
                    finalStatus = "SUCCESS_WITH_FALLBACK"
                }
            }

            var goalCheck = if (runtimeVerifiedHupuPublish) {
                GoalCheck(
                    mandatory = true,
                    passed = true,
                    message = "候选 JSON 内置 VerifyHupuPostPublished 已通过，外层流程沿用该验证结果。",
                    evidence = JSONObject()
                        .put("verifiedBy", "SkillJsonExecutor.VerifyHupuPostPublished")
                        .put("runtimeRunReportFile", runtimeRunReportFile?.absolutePath ?: JSONObject.NULL)
                        .put("skillId", executedSkillId)
                )
            } else if (runtimeVerifiedWechatMomentSubmit) {
                GoalCheck(
                    mandatory = true,
                    passed = true,
                    message = "\u5019\u9009 JSON \u5185\u7f6e WechatMomentPublishSubmitted \u5df2\u901a\u8fc7\uff1a\u70b9\u51fb\u53d1\u8868\u540e\u53f3\u4e0a\u89d2\u53d1\u8868\u6309\u94ae\u5df2\u6d88\u5931\uff0c\u89c6\u4e3a\u5fae\u4fe1\u670b\u53cb\u5708\u63d0\u4ea4\u6210\u529f\u7684\u4e2d\u7f6e\u4fe1\u8bc1\u636e\u3002",
                    evidence = JSONObject()
                        .put("verifiedBy", "SkillJsonExecutor.WechatMomentPublishSubmitted")
                        .put("runtimeRunReportFile", runtimeRunReportFile?.absolutePath ?: JSONObject.NULL)
                        .put("skillId", executedSkillId)
                        .put("confidence", "MEDIUM")
                )
            } else {
                verifyInstructionGoal(instruction, appPackage)
            }
            var goalRetry = 0
            var localOpenFirstFallbackRuns = 0
            var localXiaohongshuDraftFallbackUsed = false
            var localBottomTabEntrySweepUsed = false
            var localPinduoduoTextSearchFallbackUsed = false
            var localDouyinPublishFallbackUsed = false
            while (goalCheck.mandatory && !goalCheck.passed && goalRetry < 4 && MacroPilotAccessibilityService.instance != null) {
                step(
                    "目标未完成，继续 AI 判断",
                    "RUNNING",
                    "${goalCheck.message}；第 ${goalRetry + 1}/4 次继续重判当前屏幕。",
                    runtimeRunReportFile?.absolutePath,
                    goalCheck.evidence
                )
                if (tryDismissBlockersBeforeReplan(
                        instruction = instruction,
                        appPackage = appPackage,
                        trigger = "GOAL_UNVERIFIED_LOOP_${goalRetry + 1}",
                        previousReportFile = runtimeRunReportFile,
                        step = ::step
                    )
                ) {
                    goalCheck = verifyInstructionGoal(instruction, appPackage)
                    if (goalCheck.passed) break
                }
                if (localOpenFirstFallbackRuns < 3 &&
                    shouldRunBilibiliOpenFirstFallback(instruction, appPackage)
                ) {
                    localOpenFirstFallbackRuns++
                    val fallback = runBilibiliOpenFirstVideoFallback(instruction, appPackage, step = ::step)
                    if (fallback != null) {
                        runtimeRunReportFile = fallback
                    }
                    goalCheck = verifyInstructionGoal(instruction, appPackage)
                    if (goalCheck.passed) break
                }
                if (!localPinduoduoTextSearchFallbackUsed &&
                    isPinduoduoPackage(appPackage) &&
                    isGenericSearchInstruction(taskInstruction)
                ) {
                    localPinduoduoTextSearchFallbackUsed = true
                    val fallback = runPinduoduoTextSearchFallback(taskInstruction, appPackage, step = ::step)
                    if (fallback != null) {
                        runtimeRunReportFile = fallback
                    }
                    goalCheck = verifyInstructionGoal(instruction, appPackage)
                    if (goalCheck.passed) break
                }
                if (!localBottomTabEntrySweepUsed && shouldRunBottomTabEntrySweep(instruction, appPackage)) {
                    localBottomTabEntrySweepUsed = true
                    val sweep = runBottomTabEntrySweepFallback(instruction, appPackage, step = ::step)
                    if (sweep != null) {
                        runtimeRunReportFile = sweep
                    }
                    goalCheck = verifyInstructionGoal(instruction, appPackage)
                    if (goalCheck.passed) break
                }
                if (!localDouyinPublishFallbackUsed && shouldRunDouyinPublishButtonFallback(instruction, appPackage)) {
                    localDouyinPublishFallbackUsed = true
                    val fallback = runDouyinPublishButtonFallback(instruction, appPackage, step = ::step)
                    if (fallback != null) {
                        runtimeRunReportFile = fallback
                    }
                    goalCheck = verifyInstructionGoal(instruction, appPackage)
                    if (goalCheck.passed) break
                }
                val retry = runAiFollowUp(
                    instruction = instruction,
                    appPackage = appPackage,
                    failedSkillId = executedSkillId,
                    trigger = "GOAL_UNVERIFIED_LOOP_${goalRetry + 1}",
                    phase = "REPLAN",
                    previousResult = SkillJsonRunResult(
                        status = "FAILED_GOAL_UNVERIFIED",
                        message = goalCheck.message
                    ),
                    previousReportFile = runtimeRunReportFile,
                    flowSteps = steps,
                    step = ::step
                )
                retry?.aiJobFile?.absolutePath?.let { aiJobFiles.put(it) }
                if (retry?.runtimeReportFile != null) {
                    runtimeRunReportFile = retry.runtimeReportFile
                }
                if (retry == null || (!retry.executed && !retry.success)) {
                    step(
                        "AI 未返回补救动作，停止重复等待",
                        "FAILED_NO_REPLAN",
                        "失败后 AI 判断没有产出可执行 JSON，通常是 API 超时/网络失败/解析失败；本次停止循环，避免一直卡在 RUNNING。",
                        retry?.aiJobFile?.absolutePath,
                        JSONObject().put("goalCheck", goalCheck.evidence)
                    )
                    break
                }
                goalRetry++
                goalCheck = verifyInstructionGoal(instruction, appPackage)
                if (!goalCheck.passed &&
                    localOpenFirstFallbackRuns < 3 &&
                    shouldRunBilibiliOpenFirstFallback(instruction, appPackage)
                ) {
                    localOpenFirstFallbackRuns++
                    val fallback = runBilibiliOpenFirstVideoFallback(instruction, appPackage, step = ::step)
                    if (fallback != null) {
                        runtimeRunReportFile = fallback
                    }
                    goalCheck = verifyInstructionGoal(instruction, appPackage)
                }
                if (!goalCheck.passed &&
                    !localXiaohongshuDraftFallbackUsed &&
                    shouldRunXiaohongshuDraftFallback(instruction, appPackage)
                ) {
                    localXiaohongshuDraftFallbackUsed = true
                    val fallback = if (isXiaohongshuExplicitPublishInstruction(instruction, appPackage)) {
                        runXiaohongshuPublishFallback(instruction, appPackage, step = ::step)
                    } else {
                        runXiaohongshuDraftFallback(instruction, appPackage, step = ::step)
                    }
                    if (fallback != null) {
                        runtimeRunReportFile = fallback
                    }
                    goalCheck = verifyInstructionGoal(instruction, appPackage)
                }
            }
            if (goalCheck.mandatory) {
                step(
                    "目标完成校验",
                    if (goalCheck.passed) "SUCCESS" else "FAILED_GOAL_UNVERIFIED",
                    goalCheck.message,
                    runtimeRunReportFile?.absolutePath,
                    goalCheck.evidence
                )
                finalGoalCheck = goalCheck
                if (goalCheck.passed) {
                    finalStatus = "SUCCESS"
                } else {
                    finalStatus = "FAILED_GOAL_UNVERIFIED"
                }
            }
        } catch (error: Throwable) {
            step("手机端指令流水线异常", "FAILED_EXCEPTION", error.message ?: error.javaClass.simpleName)
        }

        sleep(1200L)
        val finalUiSummary = currentUiSummary()
        val keepTargetForegroundForProof =
            finalStatus.startsWith("FAILED") ||
                finalStatus == "SUCCESS_WITH_AI_REPLAN" ||
                finalStatus == "SUCCESS_WITH_AI_CHECKPOINT" ||
                (finalStatus == "SUCCESS" && (
                    isHupuExplicitPublishInstruction(instruction, appPackage) ||
                        isOpenFirstContentInstruction(instruction) ||
                        isDouyinVideoPublishInstruction(taskInstruction, appPackage) ||
                        isWechatMomentPrivateInstruction(instruction, appPackage)
                    ))
        if (keepTargetForegroundForProof) {
            step(
                "保留目标应用前台用于证据",
                "INFO",
                "本次保留目标应用当前页面，不把 MacroPilot 拉回前台；报告里会记录当前 UI 快照和截图，方便继续让 AI 判断卡点。",
                finalUiSummary.optString("snapshotPath").takeIf { it.isNotBlank() }
            )
        } else {
            returnToMacroPilot()
            step("返回 MacroPilot 显示报告", "INFO", "已把 MacroPilot 拉回前台，用户可以直接看 AI、JSON、dry-run、app_config 和执行报告。")
        }
        MacroPilotAccessibilityService.instance?.hideStatusOverlay(2500L)

        val report = finishReport(
            instruction = instruction,
            appPackage = appPackage,
            status = finalStatus,
            startedAt = startedAt,
            steps = steps,
            aiJobFile = aiJobFile,
            previewConfigFile = previewConfigFile,
            runtimeRunReportFile = runtimeRunReportFile,
            aiJobFiles = aiJobFiles,
            goalCheck = finalGoalCheck,
            finalUiSummary = finalUiSummary
        )
        flowReportFile = File(report.optString("reportFile"))
        step("报告已写入", "INFO", flowReportFile?.absolutePath.orEmpty(), flowReportFile?.absolutePath)
        return report
    }

    private fun localAdaptiveAiPlanPlaceholder(instruction: String, appPackage: String): AiPlanResult {
        return AiPlanResult(
            jobFile = File(context.filesDir, "macro_v2/factory/ai_jobs/local_adaptive_placeholder.json"),
            root = JSONObject().put("source", "PHONE_SIDE_SIMILAR_SKILL_ADAPTER_V1"),
            intentDraft = JSONObject()
                .put("appPackage", appPackage)
                .put("intentId", "local_adaptive")
                .put("params", JSONObject())
                .put("source", "LOCAL_FIRST_NO_AI"),
            planSteps = emptyList(),
            candidates = emptyList(),
            status = "LOCAL_ADAPTIVE",
            httpStatus = null,
            model = "phone-side-similar-skill-adapter"
        )
    }

    private fun shouldRunRuleFirst(instruction: String, appPackage: String): Boolean {
        return isDouyinVideoPublishInstruction(instruction, appPackage) ||
            isBilibiliCommentInstruction(instruction, appPackage) ||
            isBilibiliSearchInstruction(instruction, appPackage) ||
            (appPackage == SkillJsonRuleGenerator.BILIBILI_PACKAGE && isOpenFirstContentInstruction(instruction)) ||
            isXiaohongshuPostInstruction(instruction, appPackage) ||
            isHupuExplicitPublishInstruction(instruction, appPackage) ||
            isWechatCurrentChatInstruction(instruction, appPackage) ||
            isWechatMomentPrivateInstruction(instruction, appPackage)
    }

    private fun requestAiPlan(
        instruction: String,
        targetPackageHint: String,
        explicitTargetPackage: String? = null
    ): AiPlanResult {
        val config = AiAssistConfigStore(context).load()
        val task = JSONObject()
            .put("taskType", "RUN_USER_INSTRUCTION_DIRECTLY_ON_PHONE")
            .put("instruction", instruction)
            .put("targetPackageHint", targetPackageHint)
            .put("currentUi", currentUiSummary())
            .put("appUiGraph", appUiGraphStore.compactSummary((explicitTargetPackage ?: targetPackageHint).takeIf { it.isNotBlank() } ?: targetPackageHint))
            .put("availableSkillTemplates", availableSkillTemplateSummary((explicitTargetPackage ?: targetPackageHint).takeIf { it.isNotBlank() } ?: targetPackageHint, 24))
            .put("recentUiSnapshots", snapshotStore.recentSummary(targetPackageHint.takeIf { it.isNotBlank() }, 8))
            .put("installedAppHints", JSONArray(installedAppHints(instruction).take(20)))
            .put("outputContract", JSONObject()
                .put("intentDraft", JSONObject()
                    .put("appPackage", "target package")
                    .put("intentId", "open_app | search_in_app | click | type_text | back | custom")
                    .put("params", "object"))
                .put("steps", JSONArray(listOf(
                    JSONObject().put("type", "EnsureTextVideoAsset").put("text", "\u6211\u662fAI").put("displayName", "macropilot_douyin_ai.mp4"),
                    JSONObject().put("type", "OpenApp").put("packageName", "com.example"),
                    JSONObject().put("type", "HandleReactiveOverlays").put("maxPasses", 3),
                    JSONObject().put("type", "CaptureWechatBottomTabs").put("packageName", SkillJsonRuleGenerator.WECHAT_PACKAGE),
                    JSONObject().put("type", "RecoverBilibiliHomeSurface").put("packageName", SkillJsonRuleGenerator.BILIBILI_PACKAGE),
                    JSONObject().put("type", "FindEntryByBottomTabSweep")
                        .put("entryAliases", JSONArray(listOf("\u53d1\u5e16", "\u53d1\u5e03", "\u521b\u4f5c", "\u641c\u7d22", "\u6d88\u606f", "+")))
                        .put("maxTabs", 5),
                    JSONObject().put("type", "ClickVisibleText").put("text", "搜索"),
                    JSONObject().put("type", "TypeIntoFirstInput").put("text", "keyword"),
                    JSONObject().put("type", "TypeIntoFocusedInput").put("text", "draft text"),
                    JSONObject().put("type", "OpenHupuPostEditor"),
                    JSONObject().put("type", "FillHupuPostEditor").put("title", "title").put("body", "body"),
                    JSONObject().put("type", "ClickSelector").put("selector", "[text='搜索'][clickable=true]"),
                    JSONObject().put("type", "Back"),
                    JSONObject().put("type", "Wait").put("timeoutMs", 800)
                )))
                .put("candidates", "optional fine-grained SkillCandidate JSON array"))
            .put("rules", JSONArray(listOf(
                "You may return executable steps because the user explicitly requested direct phone-side control.",
                "Prefer text/selector actions. Coordinates must be normalized 0..1 and only when no node/text is available.",
                "For Douyin video publishing, first create a short local text video with EnsureTextVideoAsset, then open Douyin, enter the publish flow, choose the latest album video, type the caption, click publish only when the user explicitly asked to publish, and verify with platform feedback such as 发布成功/发布中/审核中.",
                "Never report a Douyin publish task as completed just because the app opened, a draft page is visible, or a coordinate was tapped.",
                "Use currentUi.blockingDialogs as hard evidence. If it contains ad_or_splash, first return HandleReactiveOverlays or a close/skip action. If it contains hupu_section_required, do not keep waiting: choose/select a forum/section before retrying publish.",
                "For WeChat Moments, first use CaptureWechatBottomTabs to record 微信/通讯录/发现/我 pages, then use the discovered 朋友圈 evidence instead of guessing.",
                "For Bilibili, if current UI is 我的消息/系统通知/游戏通知/动态/分享面板, use RecoverBilibiliHomeSurface before trying search or selecting the first video.",
                "If the needed entry is not visible, use FindEntryByBottomTabSweep to return to app home, click bottom tabs one by one, and search for entries such as 发帖/发布/创作/搜索/消息 before replanning.",
                "Use appUiGraph first. If it contains a known screen, bottom tab, entry candidate, or input candidate, adapt that candidate instead of inventing coordinates.",
                "Your plan will be converted by the app into Skill JSON by rewriting key fields of existing skill/archetype templates. Therefore return small steps with clear slot values: packageName/appPackage, text/textAliases, selector, x/y, timeoutMs, and verification intent.",
                "Every generated skill must be status=CANDIDATE, never APPROVED."
            )))
        val response = AiAssistClient(context).request(
            AiAssistRequest(
                useCase = AiAssistUseCase.RUNTIME_PLAN,
                inputJson = task,
                redacted = true,
                userVisible = true,
                runtimePath = true
            )
        )
        val root = response.outputJson?.optJSONObject("output") ?: response.outputJson ?: JSONObject()
        val intentDraft = extractIntentDraft(instruction, root, explicitTargetPackage ?: targetPackageHint)
        if (!explicitTargetPackage.isNullOrBlank()) {
            intentDraft.put("appPackage", explicitTargetPackage)
            intentDraft.put("source", "EXPLICIT_TARGET_PACKAGE")
        }
        val planSteps = extractPlanSteps(root)
        val candidates = extractCandidates(root, intentDraft, response.status.name)
        val job = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "factory_ai_job")
            .put("id", "factory_ai_direct_${System.currentTimeMillis()}")
            .put("taskType", "RUN_USER_INSTRUCTION_DIRECT")
            .put("appPackage", intentDraft.optString("appPackage").ifBlank { targetPackageHint })
            .put("createdAt", System.currentTimeMillis())
            .put("inputSummary", instruction.take(500))
            .put("promptSnapshot", task)
            .put("aiStatus", response.status.name)
            .put("aiMessage", response.message)
            .put("provider", response.provider?.name)
            .put("httpStatus", response.httpStatus ?: JSONObject.NULL)
            .put("model", response.outputJson?.optString("model").orEmpty().ifBlank { config.model })
            .put("baseUrlHost", endpointHost(config.endpoint))
            .put("rawResponse", response.outputJson ?: JSONObject.NULL)
            .put("parsedJson", JSONObject()
                .put("intentDraft", intentDraft)
                .put("planStepCount", planSteps.size)
                .put("aiCandidateCount", candidates.size)
                .put("acceptedAiCandidateCount", candidates.size)
                .put("ruleCandidateCount", 0))
            .put("parseSuccess", response.status == AiAssistStatus.SUCCESS && (planSteps.isNotEmpty() || candidates.isNotEmpty()))
            .put("errors", JSONArray(if (response.status == AiAssistStatus.SUCCESS) emptyList<String>() else listOf(response.message)))
            .put("accepted", planSteps.isNotEmpty() || candidates.isNotEmpty())
            .put("acceptedBy", "AI_DIRECT_OR_RULE_FALLBACK")
            .put("acceptedAt", System.currentTimeMillis())
        val jobFile = store.saveAiJob(job)
        return AiPlanResult(
            jobFile = jobFile,
            root = root,
            intentDraft = intentDraft,
            planSteps = planSteps,
            candidates = candidates,
            status = response.status.name,
            httpStatus = response.httpStatus,
            model = job.optString("model")
        )
    }

    private fun requestAiPlanReview(
        instruction: String,
        appPackage: String,
        intentId: String,
        proposedPlanSteps: List<JSONObject>
    ): AiPlanReviewResult {
        val config = AiAssistConfigStore(context).load()
        val task = JSONObject()
            .put("taskType", "RUNTIME_PLAN_REVIEW_BEFORE_EXECUTION")
            .put("instruction", instruction)
            .put("appPackage", appPackage)
            .put("intentId", intentId)
            .put("currentUi", currentUiSummary())
            .put("appUiGraph", appUiGraphStore.compactSummary(appPackage))
            .put("availableSkillTemplates", availableSkillTemplateSummary(appPackage, 24))
            .put("recentUiSnapshots", snapshotStore.recentSummary(appPackage.takeIf { it.isNotBlank() }, 10))
            .put("proposedPlanSteps", JSONArray(proposedPlanSteps.map { JSONObject(it.toString()) }))
            .put("outputContract", JSONObject()
                .put("decision", JSONObject()
                    .put("usePatchedSteps", false)
                    .put("screenAssessment", "当前页面是什么，哪些入口/弹窗/输入框可见")
                    .put("planRisk", "本地计划哪里可能点错、漏验证或误报成功")
                    .put("confidence", "HIGH | MEDIUM | LOW")
                    .put("nextActionReason", "为什么保留或改写计划"))
                .put("steps", JSONArray(listOf(
                    JSONObject().put("type", "OpenApp").put("packageName", appPackage.ifBlank { "com.example" }),
                    JSONObject().put("type", "HandleReactiveOverlays").put("maxPasses", 3),
                    JSONObject().put("type", "RecoverBilibiliHomeSurface").put("packageName", SkillJsonRuleGenerator.BILIBILI_PACKAGE),
                    JSONObject().put("type", "FindEntryByBottomTabSweep")
                        .put("entryAliases", JSONArray(listOf("\u53d1\u5e16", "\u53d1\u5e03", "\u521b\u4f5c", "\u641c\u7d22", "\u6d88\u606f", "+"))),
                    JSONObject().put("type", "ClickVisibleText").put("text", "\u641c\u7d22"),
                    JSONObject().put("type", "ClickCoordinate").put("x", 0.50).put("y", 0.36),
                    JSONObject().put("type", "TypeIntoFirstInput").put("text", "\u8f93\u5165\u5185\u5bb9"),
                    JSONObject().put("type", "Back"),
                    JSONObject().put("type", "Wait").put("timeoutMs", 800)
                ))))
            .put("rules", JSONArray(listOf(
                "你是在执行前复核手机端本地相似 Skill 计划。先判断当前 UI，再判断 proposedPlanSteps 是否够用。",
                "如果本地计划已经安全、细粒度、可验证，decision.usePatchedSteps=false，steps=[]。",
                "如果计划缺少关广告/选专区/打开入口/二次验证，decision.usePatchedSteps=true，并只返回完整替换计划中必要的细粒度 steps。",
                "优先使用 appUiGraph 里已经发现的入口、底部分屏、输入框、子页面证据；不要忽略已记录的 UI 图谱。",
                "返回的 steps 会被手机端用已有 Skill JSON/archetype 做关键字段补丁拼装，所以每一步要写清楚 text/textAliases/x/y/selector/verifyType。",
                "不要删除安全前置动作：OpenApp、HandleReactiveOverlays、CaptureWechatBottomTabs、FindEntryByBottomTabSweep 这类动作通常应该保留。",
                "B 站如果卡在消息/系统通知/游戏通知/分享面板，复核计划必须插入 RecoverBilibiliHomeSurface，然后再搜索/选择视频。",
                "用户明确要求发布/发帖/发表时才允许保留最终发布动作；否则只能写草稿。",
                "不能把 OpenApp、Wait、进入搜索页当作复杂任务完成；需要后续 checkpoint 或 verify 证据。",
                "坐标必须来自 currentUi.clickTargets/currentUi.entryCandidates/currentUi.videoCandidates 的 center，或者标明低置信兜底。"
            )))
        val response = AiAssistClient(context).request(
            AiAssistRequest(
                useCase = AiAssistUseCase.RUNTIME_PLAN,
                inputJson = task,
                redacted = true,
                userVisible = true,
                runtimePath = true
            )
        )
        val root = response.outputJson?.optJSONObject("output") ?: response.outputJson ?: JSONObject()
        val decision = extractDecision(root)
        val planSteps = extractPlanSteps(root)
        val usePatchedSteps = decision.optBoolean("usePatchedSteps", false) && planSteps.isNotEmpty()
        val job = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "factory_ai_job")
            .put("id", "factory_ai_plan_review_${System.currentTimeMillis()}")
            .put("taskType", "RUNTIME_PLAN_REVIEW_BEFORE_EXECUTION")
            .put("appPackage", appPackage)
            .put("createdAt", System.currentTimeMillis())
            .put("inputSummary", instruction.take(500))
            .put("promptSnapshot", task)
            .put("aiStatus", response.status.name)
            .put("aiMessage", response.message)
            .put("provider", response.provider?.name)
            .put("httpStatus", response.httpStatus ?: JSONObject.NULL)
            .put("model", response.outputJson?.optString("model").orEmpty().ifBlank { config.model })
            .put("baseUrlHost", endpointHost(config.endpoint))
            .put("rawResponse", response.outputJson ?: JSONObject.NULL)
            .put("parsedJson", JSONObject()
                .put("decision", decision)
                .put("usePatchedSteps", usePatchedSteps)
                .put("patchedStepCount", planSteps.size))
            .put("parseSuccess", response.status == AiAssistStatus.SUCCESS)
            .put("errors", JSONArray(if (response.status == AiAssistStatus.SUCCESS) emptyList<String>() else listOf(response.message)))
            .put("accepted", true)
            .put("acceptedBy", "APP_SIDE_PRE_EXECUTION_REVIEW")
            .put("acceptedAt", System.currentTimeMillis())
        val jobFile = store.saveAiJob(job)
        return AiPlanReviewResult(
            jobFile = jobFile,
            decision = decision,
            planSteps = planSteps,
            usePatchedSteps = usePatchedSteps,
            status = response.status.name,
            httpStatus = response.httpStatus,
            model = job.optString("model")
        )
    }

    private fun requestAiBootstrapPlan(
        instruction: String,
        appPackage: String,
        reason: String
    ): AiPlanResult {
        val config = AiAssistConfigStore(context).load()
        val task = JSONObject()
            .put("taskType", "AI_SKILL_BOOTSTRAP_WHEN_NO_SKILL")
            .put("reason", reason)
            .put("instruction", instruction)
            .put("appPackage", appPackage)
            .put("currentUi", currentUiSummary())
            .put("appUiGraph", appUiGraphStore.compactSummary(appPackage))
            .put("availableSkillTemplates", availableSkillTemplateSummary(appPackage, 24))
            .put("recentUiSnapshots", snapshotStore.recentSummary(appPackage.takeIf { it.isNotBlank() }, 8))
            .put("installedAppHints", JSONArray(installedAppHints(instruction).take(30)))
            .put("successCasePattern", JSONObject()
                .put("name", "xhs_post_draft_success_case")
                .put("flow", JSONArray(listOf(
                    "recognize appPackage",
                    "split user goal into atomic actions",
                    "generate candidate Skill JSON per action",
                    "dry-run only current generated skill graph",
                    "execute one small macro",
                    "verify by UI evidence or return low confidence",
                    "reuse existing skill/archetype templates by patching key slots instead of writing a whole app-specific hardcoded workflow",
                    "do not click final publish/pay/delete unless explicitly confirmed"
                )))
                .put("exampleSteps", JSONArray(listOf(
                    JSONObject().put("type", "OpenApp").put("packageName", appPackage.ifBlank { "com.example" }),
                    JSONObject().put("type", "ClickVisibleText").put("text", "搜索"),
                    JSONObject().put("type", "TypeIntoFirstInput").put("text", "keyword"),
                    JSONObject().put("type", "ClickCoordinate").put("x", 0.50).put("y", 0.30),
                    JSONObject().put("type", "TypeIntoFocusedInput").put("text", "draft text"),
                    JSONObject().put("type", "Wait").put("timeoutMs", 800)
                ))))
            .put("outputContract", JSONObject()
                .put("intentDraft", JSONObject()
                    .put("appPackage", appPackage)
                    .put("intentId", "open_app | search_in_app | create_draft | click | type_text | custom")
                    .put("params", JSONObject()))
                .put("taskBreakdown", JSONArray(listOf(
                    "atomic goal 1",
                    "atomic goal 2",
                    "atomic goal 3"
                )))
                .put("steps", JSONArray(listOf(
                    JSONObject().put("type", "OpenApp").put("packageName", appPackage.ifBlank { "com.example" }),
                    JSONObject().put("type", "ClickVisibleText").put("text", "visible text"),
                    JSONObject().put("type", "ClickSelector").put("selector", "[text*='搜索'][clickable=true]"),
                    JSONObject().put("type", "ClickCoordinate").put("x", 0.50).put("y", 0.50),
                    JSONObject().put("type", "HandleReactiveOverlays").put("maxPasses", 3),
                    JSONObject().put("type", "CaptureWechatBottomTabs").put("packageName", SkillJsonRuleGenerator.WECHAT_PACKAGE),
                    JSONObject().put("type", "RecoverBilibiliHomeSurface").put("packageName", SkillJsonRuleGenerator.BILIBILI_PACKAGE),
                    JSONObject().put("type", "FindEntryByBottomTabSweep")
                        .put("entryAliases", JSONArray(listOf("\u53d1\u5e16", "\u53d1\u5e03", "\u521b\u4f5c", "\u641c\u7d22", "\u6d88\u606f", "+")))
                        .put("maxTabs", 5),
                    JSONObject().put("type", "EnsureHupuSectionSelected"),
                    JSONObject().put("type", "TypeIntoFirstInput").put("text", "text"),
                    JSONObject().put("type", "TypeIntoFocusedInput").put("text", "text"),
                    JSONObject().put("type", "ScrollForward"),
                    JSONObject().put("type", "Back"),
                    JSONObject().put("type", "Wait").put("timeoutMs", 800)
                )))
                .put("candidates", "optional fine-grained atomic_skill/macro_skill JSON array"))
            .put("rules", JSONArray(listOf(
                "You are not training a model. You are bootstrapping candidate Skill JSON for an unknown or untrained app.",
                "Return one JSON object only. Do not return prose.",
                "Never return an empty steps array unless the task is impossible or unsafe. If the app is not already open, the first step must be OpenApp.",
                "Split the task into small atomic actions. Each step must map to one allowed type: OpenApp, ClickVisibleText, ClickSelector, ClickCoordinate, LongPressCoordinate, TypeIntoFirstInput, TypeIntoFocusedInput, OpenHupuPostEditor, FillHupuPostEditor, FindEntryByBottomTabSweep, CaptureWechatBottomTabs, RecoverDouyinHomeSurface, RecoverBilibiliHomeSurface, HandleReactiveOverlays, EnsureHupuSectionSelected, VerifyHupuPostPublished, ScrollForward, Back, Wait.",
                "Use currentUi.clickTargets when choosing a visible target. If no reliable target exists, use Wait or ScrollForward, or a low-confidence ClickCoordinate with normalized x/y.",
                "For WeChat Moments, use CaptureWechatBottomTabs first so recentUiSnapshots contains page maps for WeChat, Contacts, Discover, and Me before looking for Moments.",
                "For Bilibili, if currentUi is 我的消息、系统通知、游戏通知、动态或分享面板, output RecoverBilibiliHomeSurface before search/video selection.",
                "If the task needs a post/search/message entry and it is not visible, output FindEntryByBottomTabSweep with entryAliases. This action returns to app home, sweeps bottom tabs, and clicks the matching entry.",
                "Use currentUi.blockingDialogs as hard evidence. If it contains ad_or_splash, close/skip it first. If it contains hupu_section_required, select a forum/section before trying to publish again.",
                "ClickSelector supports only simple bracket selectors: [text='...'], [text*='...'], [desc='...'], [desc*='...'], [resourceId='...'], [class='...'][clickable=true].",
                "Do not click dangerous final actions such as 发布、支付、删除、授权 unless the instruction explicitly confirms that final action.",
                "Candidate skills must be status=CANDIDATE, never APPROVED.",
                "Coordinates are fallback only and must be marked low confidence through resultPolicy or action reasoning."
            )))
        val response = AiAssistClient(context).request(
            AiAssistRequest(
                useCase = AiAssistUseCase.RUNTIME_PLAN,
                inputJson = task,
                redacted = true,
                userVisible = true,
                runtimePath = true
            )
        )
        val root = response.outputJson?.optJSONObject("output") ?: response.outputJson ?: JSONObject()
        val intentDraft = extractIntentDraft(instruction, root, appPackage)
        if (appPackage.isNotBlank()) {
            intentDraft.put("appPackage", appPackage)
            intentDraft.put("source", "BOOTSTRAP_TARGET_PACKAGE")
        }
        val planSteps = extractPlanSteps(root)
        val candidates = extractCandidates(root, intentDraft, response.status.name)
        val job = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "factory_ai_job")
            .put("id", "factory_ai_bootstrap_${System.currentTimeMillis()}")
            .put("taskType", "AI_SKILL_BOOTSTRAP_WHEN_NO_SKILL")
            .put("reason", reason)
            .put("appPackage", intentDraft.optString("appPackage").ifBlank { appPackage })
            .put("createdAt", System.currentTimeMillis())
            .put("inputSummary", instruction.take(500))
            .put("promptSnapshot", task)
            .put("aiStatus", response.status.name)
            .put("aiMessage", response.message)
            .put("provider", response.provider?.name)
            .put("httpStatus", response.httpStatus ?: JSONObject.NULL)
            .put("model", response.outputJson?.optString("model").orEmpty().ifBlank { config.model })
            .put("baseUrlHost", endpointHost(config.endpoint))
            .put("rawResponse", response.outputJson ?: JSONObject.NULL)
            .put("parsedJson", JSONObject()
                .put("intentDraft", intentDraft)
                .put("taskBreakdown", root.optJSONArray("taskBreakdown") ?: JSONArray())
                .put("planStepCount", planSteps.size)
                .put("aiCandidateCount", candidates.size))
            .put("parseSuccess", response.status == AiAssistStatus.SUCCESS && (planSteps.isNotEmpty() || candidates.isNotEmpty()))
            .put("errors", JSONArray(if (response.status == AiAssistStatus.SUCCESS) emptyList<String>() else listOf(response.message)))
            .put("accepted", planSteps.isNotEmpty() || candidates.isNotEmpty())
            .put("acceptedBy", "AI_SKILL_BOOTSTRAP")
            .put("acceptedAt", System.currentTimeMillis())
        val jobFile = store.saveAiJob(job)
        return AiPlanResult(
            jobFile = jobFile,
            root = root,
            intentDraft = intentDraft,
            planSteps = planSteps,
            candidates = candidates,
            status = response.status.name,
            httpStatus = response.httpStatus,
            model = job.optString("model")
        )
    }

    private fun buildDirectPlanSkills(
        instruction: String,
        appPackage: String,
        planSteps: List<JSONObject>
    ): DirectPlanBuild {
        val patchedAssembly = patchAssembler.assembleFromPlan(
            instruction = instruction,
            appPackage = appPackage,
            planSteps = planSteps,
            source = "APP_SIDE_PLAN_PATCH_ASSEMBLER"
        )
        if (patchedAssembly.skillFiles.isNotEmpty()) {
            return DirectPlanBuild(
                skillFiles = patchedAssembly.skillFiles,
                macroFile = patchedAssembly.macroFile
            )
        }
        val stamp = System.currentTimeMillis()
        val skillIds = mutableListOf<String>()
        val files = mutableListOf<File>()
        planSteps.forEachIndexed { index, rawStep ->
            val action = normalizeAction(rawStep, appPackage)
            if (action.optString("type") == "FindEntryByBottomTabSweep" && shouldAllowEntrySweepForInstruction(instruction, appPackage)) {
                action
                    .put("userConfirmedSubmitAction", true)
                    .put("submitReason", "\u8fd9\u662f\u56de\u5230 App \u9996\u9875\u540e\u626b\u63cf\u53d1\u5e16/\u53d1\u5e03/\u521b\u4f5c\u5165\u53e3\uff0c\u4e0d\u662f\u70b9\u51fb\u6700\u7ec8\u63d0\u4ea4\u6309\u94ae")
            }
            if (allowsExplicitPublishStep(instruction, appPackage, action)) {
                action
                    .put("userConfirmedSubmitAction", true)
                    .put("submitReason", "\u7528\u6237\u660e\u786e\u8981\u6c42\u672c\u6b21\u53d1\u5e03\uff0c\u5141\u8bb8\u70b9\u51fb\u53d1\u5e03\u6309\u94ae")
            }
            val id = "direct.${stamp}.${index + 1}.${action.optString("type").lowercase(Locale.US)}"
            skillIds += id
            val skill = directAtomicSkill(id, appPackage, "直接执行步骤 ${index + 1}", action)
            files += store.saveSkill(appPackage, skill)
        }
        val macro = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "macro_skill")
            .put("id", "direct.command.$stamp")
            .put("name", "用户指令直接执行")
            .put("appPackage", appPackage)
            .put("status", "CANDIDATE")
            .put("implements", "AiDirectInstructionSkill")
            .put("intentId", "ai_direct_instruction")
            .put("params", JSONArray())
            .put("preconditions", JSONArray())
            .put("atoms", JSONArray(skillIds.map { JSONObject().put("type", "UseSkill").put("skillId", it) }))
            .put("verify", JSONObject().put("type", "Always").put("timeoutMs", 1))
            .put("resultPolicy", JSONObject()
                .put("allAtomicSuccess", "SUCCESS")
                .put("partialAtomicSuccess", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("lowConfidence", "SUCCESS_LOW_CONFIDENCE")
                .put("failed", "FAILED"))
            .put("timeoutMs", 30_000)
            .put("factory", factoryMeta("AI_DIRECT_PLAN", instruction))
        val macroFile = store.saveSkill(appPackage, macro)
        return DirectPlanBuild(files + macroFile, macroFile)
    }

    private fun directAtomicSkill(id: String, appPackage: String, name: String, action: JSONObject): JSONObject {
        val actionType = action.optString("type")
        val input = actionType in setOf("TypeIntoFirstInput", "TypeIntoFocusedInput", "TypeAtCoordinate", "FillHupuPostEditor")
        val hasCoordinateFallback = actionType == "ClickCoordinate" ||
            actionType == "LongPressCoordinate" ||
            actionType == "TypeAtCoordinate" ||
            actionType == "FindEntryByBottomTabSweep" ||
            actionType == "CaptureWechatBottomTabs" ||
            actionType == "OpenHupuPostEditor" ||
            actionType == "HandleSystemPermissionDialog" ||
            actionType == "RecoverDouyinHomeSurface" ||
            actionType == "RecoverBilibiliHomeSurface" ||
            (actionType == "ClickVisibleText" && (action.has("x") || action.has("normalizedX")))
        return JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "atomic_skill")
            .put("id", id)
            .put("name", name)
            .put("appPackage", action.optString("packageName").takeIf { actionType == "OpenApp" } ?: appPackage)
            .put("status", "CANDIDATE")
            .put("implements", when (actionType) {
                "OpenApp" -> "OpenAppSkill"
                "ClickVisibleText", "ClickSelector", "ClickCoordinate", "LongPressCoordinate" -> "ClickElementSkill"
                "OpenHupuPostEditor" -> "ClickElementSkill"
                "FindEntryByBottomTabSweep" -> "FindEntrySkill"
                "CaptureWechatBottomTabs" -> "ObserveScreenSkill"
                "TypeIntoFirstInput", "TypeIntoFocusedInput", "TypeAtCoordinate", "FillHupuPostEditor" -> "TypeTextSkill"
                "EnsureXiaohongshuDraftEditor" -> "RecoverToScreenSkill"
                "EnsureTextVideoAsset" -> "CreateMediaAssetSkill"
                "HandleSystemPermissionDialog" -> "PermissionDialogSkill"
                "RecoverDouyinHomeSurface" -> "RecoverToScreenSkill"
                "RecoverBilibiliHomeSurface" -> "RecoverToScreenSkill"
                "HandleReactiveOverlays" -> "DismissDialogSkill"
                "EnsureHupuSectionSelected" -> "SelectItemSkill"
                "VerifyHupuPostPublished" -> "VerifyGoalSkill"
                "Back" -> "BackSkill"
                "Wait" -> "WaitUntilSkill"
                "ScrollForward" -> "ScrollUntilVisibleSkill"
                else -> "AiDirectActionSkill"
            })
            .put("params", JSONArray())
            .put("preconditions", JSONArray())
            .put("dependsOn", JSONArray())
            .put("capabilityRequired", JSONObject()
                .put("minLevel", "D")
                .put("inputRequired", input)
                .put("coordinateFallbackAllowed", hasCoordinateFallback)
                .put("allowedInputChannels", JSONArray(if (input) listOf("ACTION_SET_TEXT", "PASTE", "CUSTOM_IME", "SHIZUKU_ADB") else emptyList<String>()))
                .put("verificationRequired", "LOW"))
            .put("resolverPolicy", JSONObject()
                .put("order", JSONArray(listOf("ACCESSIBILITY", "COORDINATE_PACK", "AI_DIRECT_PLAN")))
                .put("allowAiSuggestionInFactory", true)
                .put("allowAiInRuntime", true))
            .put("safety", JSONObject()
                .put("userConfirmedSubmitAction", action.optBoolean("userConfirmedSubmitAction", false))
                .put("submitReason", action.optString("submitReason")))
            .put("action", action)
            .put("verify", verifyForAction(action))
            .put("resultPolicy", JSONObject()
                .put("verified", "SUCCESS")
                .put("partialVerified", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("coordinateOnly", "SUCCESS_LOW_CONFIDENCE")
                .put("unverifiable", "FAILED_UNVERIFIABLE"))
            .put("factory", factoryMeta("AI_DIRECT_PLAN", name))
    }

    private fun verifyForAction(action: JSONObject): JSONObject {
        if (action.optString("verifyType") == "WechatMomentPublishSubmitted") {
            return JSONObject()
                .put("type", "WechatMomentPublishSubmitted")
                .put("timeoutMs", action.optLong("timeoutMs", 4_500L))
        }
        return when (action.optString("type")) {
            "OpenApp" -> JSONObject()
                .put("type", "ForegroundPackage")
                .put("packageName", action.optString("packageName"))
                .put("timeoutMs", action.optLong("timeoutMs", 4_500L))
                .put("retryTimeoutMs", action.optLong("retryTimeoutMs", 4_500L))
                .put("allowUnverifiedWhenAccessibilityDisconnected", true)
            "TypeIntoFirstInput" -> JSONObject()
                .put("type", "InputContainsOrTextChangedEvent")
                .put("text", action.optString("text"))
                .put("timeoutMs", 1500)
                .put("allowMediumConfidenceWhenEventOnly", true)
                .put("allowLowConfidenceWhenUnverified", true)
            "TypeIntoFocusedInput", "TypeAtCoordinate", "FillHupuPostEditor" -> JSONObject()
                .put("type", "InputContainsOrTextChangedEvent")
                .put("text", action.optString("text").ifBlank { action.optString("title") })
                .put("timeoutMs", 1500)
                .put("allowMediumConfidenceWhenEventOnly", true)
                .put("allowLowConfidenceWhenUnverified", true)
            "EnsureTextVideoAsset" -> JSONObject()
                .put("type", "Always")
                .put("timeoutMs", 1)
            "HandleSystemPermissionDialog", "RecoverDouyinHomeSurface", "RecoverBilibiliHomeSurface", "HandleReactiveOverlays", "EnsureHupuSectionSelected", "VerifyHupuPostPublished", "FindEntryByBottomTabSweep", "CaptureWechatBottomTabs", "OpenHupuPostEditor" -> JSONObject()
                .put("type", "Always")
                .put("timeoutMs", 1)
            "Wait" -> JSONObject().put("type", "Always").put("timeoutMs", 1)
            else -> JSONObject()
                .put("type", "WindowChangedOrNodeStateChanged")
                .put("timeoutMs", 1000)
                .put("allowLowConfidenceWhenCoordinateOnly", true)
        }
    }

    private fun normalizeAction(raw: JSONObject, fallbackPackage: String): JSONObject {
        val action = raw.optJSONObject("action") ?: raw
        val rawType = action.optString("type").ifBlank { action.optString("action") }
        val type = when (rawType.lowercase(Locale.US)) {
            "open_app", "openapp", "launch_app", "launchapp" -> "OpenApp"
            "click_text", "clickvisibletext", "tap_text", "tapvisibletext" -> "ClickVisibleText"
            "click_selector", "clickselector", "tap_selector" -> "ClickSelector"
            "type_text", "typetext", "input_text", "inputtext", "typeintofirstinput" -> "TypeIntoFirstInput"
            "fill_hupu_post_editor", "fillhupuposteditor", "hupu_fill_post_editor" -> "FillHupuPostEditor"
            "open_hupu_post_editor", "openhupuposteditor", "hupu_open_post_editor" -> "OpenHupuPostEditor"
            "type_focused_input", "typefocusedinput", "typeintofocusedinput", "input_focused_text" -> "TypeIntoFocusedInput"
            "type_at_coordinate", "typeatcoordinate", "input_at_coordinate", "tap_and_type" -> "TypeAtCoordinate"
            "press_back", "back" -> "Back"
            "wait", "sleep" -> "Wait"
            "click_coordinate", "clickcoordinate", "tap_coordinate" -> "ClickCoordinate"
            "long_press_coordinate", "longpresscoordinate", "long_press", "longpress" -> "LongPressCoordinate"
            "scroll_forward", "scrollforward", "scroll", "swipe_up", "swipeup" -> "ScrollForward"
            "ensure_xhs_editor", "ensurexiaohongshudrafteditor", "ensure_xiaohongshu_draft_editor" -> "EnsureXiaohongshuDraftEditor"
            "ensure_text_video_asset", "ensuretextvideoasset", "create_text_video", "createtextvideo", "create_video_asset" -> "EnsureTextVideoAsset"
            "handle_system_permission_dialog", "handlesystempermissiondialog", "grant_permission", "grantpermission", "allow_permission", "allowpermission" -> "HandleSystemPermissionDialog"
            "recover_douyin_home_surface", "recoverdouyinhomesurface", "recover_douyin_home", "douyin_recover_home" -> "RecoverDouyinHomeSurface"
            "recover_bilibili_home_surface", "recoverbilibilihomesurface", "recover_bilibili_home", "bilibili_recover_home", "bili_recover_home" -> "RecoverBilibiliHomeSurface"
            "handle_reactive_overlays", "handlereactiveoverlays", "dismiss_overlay", "dismissblockingoverlay", "dismiss_blocking_overlay" -> "HandleReactiveOverlays"
            "ensure_hupu_section_selected", "ensurehupusectionselected", "select_hupu_section" -> "EnsureHupuSectionSelected"
            "verify_hupu_post_published", "verifyhupupostpublished", "verify_hupu_publish", "verify_hupu_post" -> "VerifyHupuPostPublished"
            "find_entry_by_bottom_tab_sweep", "findentrybybottomtabsweep", "bottom_tab_sweep", "find_entry", "sweep_bottom_tabs" -> "FindEntryByBottomTabSweep"
            "capture_wechat_bottom_tabs", "capturewechatbottomtabs", "wechat_bottom_tabs", "record_wechat_tabs" -> "CaptureWechatBottomTabs"
            else -> rawType.ifBlank { "Wait" }
        }
        val out = JSONObject().put("type", type)
        when (type) {
            "OpenApp" -> out.put("packageName", action.optString("packageName").ifBlank {
                action.optString("appPackage").ifBlank { fallbackPackage }
            }).put("timeoutMs", action.optLong("timeoutMs", 3000L)).also {
                if (action.optBoolean("launcherRoot", false)) out.put("launcherRoot", true)
                if (action.has("retryTimeoutMs")) out.put("retryTimeoutMs", action.optLong("retryTimeoutMs", 4_500L))
            }
            "ClickVisibleText" -> out.put("text", action.optString("text").ifBlank {
                action.optString("label").ifBlank { action.optString("targetText") }
            }).put("timeoutMs", action.optLong("timeoutMs", 1500L)).also {
                val aliases = action.optJSONArray("textAliases") ?: action.optJSONArray("aliases")
                if (aliases != null) out.put("textAliases", aliases)
                if (action.optBoolean("skipIfHupuPostEditorVisible", false)) {
                    out.put("skipIfHupuPostEditorVisible", true)
                }
                if (action.optBoolean("skipWhenUiUnavailable", false)) {
                    out.put("skipWhenUiUnavailable", true)
                }
                if (action.has("requiresUiStateForCoordinate")) {
                    out.put("requiresUiStateForCoordinate", action.optBoolean("requiresUiStateForCoordinate", true))
                }
                copyNormalizedPoint(action, out)
            }
            "ClickSelector" -> out.put("selector", action.optString("selector")).put("timeoutMs", action.optLong("timeoutMs", 1500L))
            "TypeIntoFirstInput" -> out.put("text", action.optString("text").ifBlank {
                action.optString("value").ifBlank { action.optString("input") }
            }).put("timeoutMs", action.optLong("timeoutMs", 2000L))
            "TypeIntoFocusedInput" -> out.put("text", action.optString("text").ifBlank {
                action.optString("value").ifBlank { action.optString("input") }
            }).put("timeoutMs", action.optLong("timeoutMs", 2000L))
            "FillHupuPostEditor" -> out
                .put("title", action.optString("title"))
                .put("body", action.optString("body"))
                .put("titleParam", action.optString("titleParam"))
                .put("bodyParam", action.optString("bodyParam"))
                .put("timeoutMs", action.optLong("timeoutMs", 2600L))
            "OpenHupuPostEditor" -> out
                .put("timeoutMs", action.optLong("timeoutMs", 2_000L))
            "TypeAtCoordinate" -> out.put("text", action.optString("text").ifBlank {
                action.optString("value").ifBlank { action.optString("input") }
            })
                .put("x", action.optDouble("x", action.optDouble("normalizedX", 0.5)))
                .put("y", action.optDouble("y", action.optDouble("normalizedY", 0.5)))
                .put("replace", action.optBoolean("replace", false))
                .put("focusDelayMs", action.optLong("focusDelayMs", 550L))
                .put("timeoutMs", action.optLong("timeoutMs", 2600L))
            "Back" -> out.put("timeoutMs", action.optLong("timeoutMs", 1000L))
            "Wait" -> out.put("timeoutMs", action.optLong("timeoutMs", 800L))
            "ScrollForward" -> out.put("timeoutMs", action.optLong("timeoutMs", 1200L))
            "EnsureXiaohongshuDraftEditor" -> out
                .put("packageName", action.optString("packageName").ifBlank {
                    action.optString("appPackage").ifBlank { fallbackPackage }
                })
                .put("timeoutMs", action.optLong("timeoutMs", 6_000L))
            "EnsureTextVideoAsset" -> out
                .put("text", action.optString("text").ifBlank {
                    action.optString("value").ifBlank { action.optString("input").ifBlank { "\u6211\u662fAI" } }
                })
                .put("textParam", action.optString("textParam"))
                .put("displayName", action.optString("displayName").ifBlank { "macropilot_douyin_ai.mp4" })
                .put("durationMs", action.optLong("durationMs", 3_000L))
            "HandleReactiveOverlays" -> out
                .put("appPackage", action.optString("appPackage").ifBlank { fallbackPackage })
                .put("maxPasses", action.optInt("maxPasses", 3))
            "HandleSystemPermissionDialog" -> out
                .put("appPackage", action.optString("appPackage").ifBlank { fallbackPackage })
                .put("textAliases", normalizeStringArray(
                    action.optJSONArray("textAliases") ?: action.optJSONArray("aliases"),
                    listOf(
                        "\u4ec5\u9650\u8fd9\u4e00\u6b21",
                        "\u4f7f\u7528\u65f6\u5141\u8bb8",
                        "\u5141\u8bb8\u6240\u6709\u7167\u7247\u548c\u89c6\u9891",
                        "\u9009\u62e9\u7167\u7247\u548c\u89c6\u9891",
                        "\u53bb\u6388\u6743",
                        "\u5141\u8bb8"
                    )
                ))
                .put("x", action.optDouble("x", action.optDouble("normalizedX", 0.50)))
                .put("y", action.optDouble("y", action.optDouble("normalizedY", 0.775)))
                .put("settleMs", action.optLong("settleMs", 900L))
                .put("userConfirmedSubmitAction", true)
                .put("submitReason", action.optString("submitReason").ifBlank {
                    action.optString("safetyReason").ifBlank {
                    "\u7528\u6237\u660e\u786e\u8981\u6c42\u7684\u624b\u673a\u7aef\u64cd\u4f5c\u9700\u8981\u5904\u7406\u7cfb\u7edf\u6743\u9650\u5f39\u7a97"
                    }
                })
            "RecoverDouyinHomeSurface" -> out
                .put("appPackage", action.optString("appPackage").ifBlank {
                    action.optString("packageName").ifBlank { fallbackPackage }
                })
                .put("timeoutMs", action.optLong("timeoutMs", 4_000L))
            "RecoverBilibiliHomeSurface" -> out
                .put("appPackage", action.optString("appPackage").ifBlank {
                    action.optString("packageName").ifBlank { fallbackPackage.ifBlank { SkillJsonRuleGenerator.BILIBILI_PACKAGE } }
                })
                .put("timeoutMs", action.optLong("timeoutMs", 6_000L))
            "EnsureHupuSectionSelected" -> out
                .put("appPackage", action.optString("appPackage").ifBlank { fallbackPackage })
                .put("timeoutMs", action.optLong("timeoutMs", 3_000L))
            "VerifyHupuPostPublished" -> out
                .put("text", action.optString("text").ifBlank {
                    action.optString("value").ifBlank { action.optString("input") }
                })
                .put("textParam", action.optString("textParam"))
                .put("timeoutMs", action.optLong("timeoutMs", 6_000L))
                .put("userConfirmedSubmitAction", true)
                .put("submitReason", "这是用户明确要求发布后的结果验证，不是新的发布动作")
            "FindEntryByBottomTabSweep" -> out
                .put("appPackage", action.optString("appPackage").ifBlank { fallbackPackage })
                .put("entryAliases", normalizeStringArray(
                    action.optJSONArray("entryAliases")
                        ?: action.optJSONArray("aliases")
                        ?: action.optJSONArray("textAliases"),
                    listOfNotNull(
                        action.optString("entryText").takeIf { it.isNotBlank() },
                        action.optString("text").takeIf { it.isNotBlank() },
                        action.optString("targetText").takeIf { it.isNotBlank() }
                    )
                ))
                .put("maxTabs", action.optInt("maxTabs", 5))
                .put("homeBackPresses", action.optInt("homeBackPresses", 3))
                .put("timeoutMs", action.optLong("timeoutMs", 12_000L))
                .put("bottomTapY", action.optDouble("bottomTapY", 0.925))
            "CaptureWechatBottomTabs" -> out
                .put("appPackage", action.optString("appPackage").ifBlank {
                    action.optString("packageName").ifBlank { fallbackPackage.ifBlank { SkillJsonRuleGenerator.WECHAT_PACKAGE } }
                })
                .put("settleMs", action.optLong("settleMs", 1_000L))
                .put("bottomY", action.optDouble("bottomY", 0.955))
            "ClickCoordinate" -> out
                .put("x", action.optDouble("x", action.optDouble("normalizedX", 0.5)))
                .put("y", action.optDouble("y", action.optDouble("normalizedY", 0.5)))
                .put("timeoutMs", action.optLong("timeoutMs", 1000L))
            "LongPressCoordinate" -> out
                .put("x", action.optDouble("x", action.optDouble("normalizedX", 0.5)))
                .put("y", action.optDouble("y", action.optDouble("normalizedY", 0.5)))
                .put("durationMs", action.optLong("durationMs", 850L))
                .put("timeoutMs", action.optLong("timeoutMs", 1500L))
        }
        if (action.optBoolean("userConfirmedSubmitAction", false)) {
            out.put("userConfirmedSubmitAction", true)
            out.put("submitReason", action.optString("submitReason"))
        }
        if (action.optBoolean("skipReactiveOverlays", false)) {
            out.put("skipReactiveOverlays", true)
        }
        if (action.has("verifyType")) {
            out.put("verifyType", action.optString("verifyType"))
        }
        return out
    }

    private fun copyNormalizedPoint(from: JSONObject, to: JSONObject) {
        if (from.has("x") || from.has("normalizedX")) {
            to.put("x", from.optDouble("x", from.optDouble("normalizedX", 0.5)))
        }
        if (from.has("y") || from.has("normalizedY")) {
            to.put("y", from.optDouble("y", from.optDouble("normalizedY", 0.5)))
        }
    }

    private fun normalizeStringArray(array: JSONArray?, extras: List<String> = emptyList()): JSONArray {
        val values = mutableListOf<String>()
        if (array != null) {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) values += value
            }
        }
        extras.map { it.trim() }.filter { it.isNotBlank() }.forEach { values += it }
        return JSONArray(values.distinctBy { it.lowercase(Locale.CHINA) })
    }

    private fun dryRunPackage(packageName: String): DryRunSummary {
        val files = store.listSkillFiles(packageName, 1000).filter { file ->
            runCatching { JSONObject(file.readText()).optString("status") != "APPROVED" }.getOrDefault(false)
        }
        return dryRunFiles(packageName, files)
    }

    private fun dryRunFiles(packageName: String, files: List<File>): DryRunSummary {
        val targetFiles = files
            .distinctBy { it.absolutePath }
            .filter { file ->
                file.isFile && runCatching { JSONObject(file.readText()).optString("status") != "APPROVED" }.getOrDefault(false)
            }
        val knownIds = store.listSkillFiles(packageName, 1000).mapNotNull { file ->
            runCatching { JSONObject(file.readText()).optString("id").takeIf { it.isNotBlank() } }.getOrNull()
        }.toSet()
        var passed = 0
        var lastPath: String? = null
        val engine = SkillJsonDryRunEngine()
        targetFiles.forEach { file ->
            val skill = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@forEach
            val report = engine.dryRun(skill, knownIds)
            val ok = report.optBoolean("passed")
            if (ok) passed++
            val skillId = skill.optString("id", file.nameWithoutExtension)
            val reportFile = store.saveDryRunReport(skill.optString("appPackage", packageName), skillId, report)
            lastPath = reportFile.absolutePath
            val factory = skill.optJSONObject("factory") ?: JSONObject()
            factory
                .put("dryRunStatus", if (ok) "PASSED" else "FAILED")
                .put("lastDryRunReport", reportFile.absolutePath)
                .put("lastDryRunAt", System.currentTimeMillis())
            skill.put("factory", factory)
            skill.put("status", if (ok) "DRY_RUN_PASSED" else "REJECTED")
            store.saveSkill(skill.optString("appPackage", packageName), skill)
        }
        return DryRunSummary(passed, targetFiles.size, lastPath)
    }

    private fun exportPreview(packageName: String): File? {
        val sourceFiles = store.listSkillFiles(packageName, 1000)
        val skills = sourceFiles.mapNotNull { file ->
            runCatching { JSONObject(file.readText()) }.getOrNull()
        }.filter { it.optString("status") != "REJECTED" }
        if (skills.isEmpty()) return null
        val reports = store.listDryRunReports(packageName, 500).mapNotNull { file ->
            runCatching { JSONObject(file.readText()) }.getOrNull()
        } + store.listTestReports(packageName, 500).mapNotNull { file ->
            runCatching { JSONObject(file.readText()) }.getOrNull()
        }
        val module = AppUiModuleStore(context).loadApproved(packageName)
            ?: AppUiModuleStore(context).listCandidates().firstOrNull { (_, candidate) -> candidate.packageName == packageName }?.second
        val exporter = SkillJsonExporter()
        val config = exporter.exportAppConfig(
            packageName = packageName,
            appName = appNameForPackage(packageName),
            module = module,
            skills = skills,
            dryRunReports = reports
        )
        val configFile = store.saveAppConfig(packageName, config)
        val assembly = exporter.buildAssemblyReport(
            packageName = packageName,
            appName = appNameForPackage(packageName),
            approvedOnly = false,
            sourceFiles = sourceFiles,
            includedSkills = skills,
            dryRunReportCount = store.listDryRunReports(packageName, 500).size,
            testReportCount = store.listTestReports(packageName, 500).size,
            config = config,
            previewConfigFile = configFile,
            runtimeConfigFile = null
        )
        store.saveAssemblyReport(packageName, assembly)
        return configFile
    }

    private fun tryDismissBlockersBeforeReplan(
        instruction: String,
        appPackage: String,
        trigger: String,
        previousReportFile: File?,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): Boolean {
        if (MacroPilotAccessibilityService.instance == null) return false
        if (!actions.isInteractive() || actions.isKeyguardLocked()) return false
        val beforeState = inspector.currentState()
        val blockers = reactiveRules.enhancedBlockingDialogSummary(beforeState)
        val hasConcreteBlocker = hasConcreteBlockingDialog(blockers)
        val result = reactiveRules.dismissBlockingOverlays(
            appPackage = appPackage,
            reason = "flow_replan:$trigger",
            maxPasses = 3
        )
        val visualFallback = if (result.dismissedCount == 0 &&
            shouldTryWechatUnreadableNoticeFallback(instruction, appPackage, beforeState)
        ) {
            val width = beforeState?.screenWidth?.coerceAtLeast(1) ?: 1440
            val height = beforeState?.screenHeight?.coerceAtLeast(1) ?: 3168
            actions.tapNormalized(NormalizedPoint(0.33f, 0.885f), width, height)
        } else {
            null
        }
        if (hasConcreteBlocker || result.dismissedCount > 0 || visualFallback != null) {
            step(
                "本地弹窗规则处理",
                if (result.dismissedCount > 0 || visualFallback?.success == true) "SUCCESS" else "INFO",
                if (result.dismissedCount > 0 || visualFallback?.success == true) {
                    "AI 重判前先由手机端 GKD-like 规则处理阻断弹窗/广告：节点规则处理 ${result.dismissedCount} 个，视觉兜底=${visualFallback?.success == true}。"
                } else {
                    "检测到疑似阻断弹窗，但没有安全可点的关闭项；保留给 AI 根据当前 UI 再判断。"
                },
                previousReportFile?.absolutePath,
                JSONObject()
                    .put("trigger", trigger)
                    .put("blockingDialogsBefore", blockers)
                    .put("dismissEvents", result.events)
                    .put("visualFallback", visualFallback?.let { outcome ->
                        JSONObject()
                            .put("type", "wechat_unreadable_personalization_notice")
                            .put("tap", "0.33,0.885")
                            .put("success", outcome.success)
                            .put("message", outcome.reason)
                    } ?: JSONObject.NULL)
            )
            if (result.dismissedCount > 0 || visualFallback?.success == true) sleep(900L)
        }
        return result.dismissedCount > 0 || visualFallback?.success == true
    }

    private fun hasConcreteBlockingDialog(blockers: JSONArray): Boolean {
        for (index in 0 until blockers.length()) {
            val type = blockers.optJSONObject(index)?.optString("type").orEmpty()
            if (type.isNotBlank() && type != "popup_observed") return true
        }
        return false
    }

    private fun shouldTryWechatUnreadableNoticeFallback(
        instruction: String,
        appPackage: String,
        state: com.macropilot.app.model.UiStateSample?
    ): Boolean {
        if (!isWechatMomentPrivateInstruction(instruction, appPackage)) return false
        if (appPackage != SkillJsonRuleGenerator.WECHAT_PACKAGE) return false
        if (state?.appPackage != SkillJsonRuleGenerator.WECHAT_PACKAGE) return false
        if ((state.nodes.size) > 3) return false
        val hasReadableLabel = state.nodes.any { node ->
            !node.text.isNullOrBlank() || !node.contentDesc.isNullOrBlank() || !node.resourceId.isNullOrBlank()
        }
        return !hasReadableLabel && currentScreenshotLooksLikeWechatBottomSheet()
    }

    private fun currentScreenshotLooksLikeWechatBottomSheet(): Boolean {
        val service = MacroPilotAccessibilityService.instance ?: return false
        return runCatching {
            service.hideStatusOverlay(0L)
            sleep(220L)
            val file = File(context.cacheDir, "macropilot_wechat_bottom_sheet_probe.png")
            if (!service.takeScreenshotPngBlocking(file, timeoutMs = 1_800L)) return@runCatching false
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching false
            try {
                wechatBottomSheetDarkScore(bitmap) >= 3_000 &&
                    wechatBottomSheetGreenButtonScore(bitmap) >= 80
            } finally {
                bitmap.recycle()
            }
        }.getOrDefault(false)
    }

    private fun wechatBottomSheetDarkScore(bitmap: android.graphics.Bitmap): Int {
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
                if (max in 24..112 && max - min <= 24) score++
                x += 2
            }
            y += 2
        }
        return score
    }

    private fun wechatBottomSheetGreenButtonScore(bitmap: android.graphics.Bitmap): Int {
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
                if (g >= 125 && g > r + 42 && g > b + 42) score++
                x += 2
            }
            y += 2
        }
        return score
    }

    private fun runAiFollowUp(
        instruction: String,
        appPackage: String,
        failedSkillId: String,
        trigger: String,
        phase: String,
        previousResult: SkillJsonRunResult,
        previousReportFile: File?,
        flowSteps: JSONArray,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): AiFollowUpExecution? {
        val label = if (phase == "REPLAN") "失败后 AI 判断" else "中途 AI 检查点"
        step(
            label,
            "RUNNING",
            "当前不是直接停下：App 把失败原因、当前屏幕节点和已跑步骤发给手机端 AI，让 AI 判断下一段补救 JSON。",
            previousReportFile?.absolutePath,
            JSONObject()
                .put("phase", phase)
                .put("trigger", trigger)
                .put("previousStatus", previousResult.status)
                .put("previousMessage", previousResult.message)
        )

        val ai = requestAiFollowUpDecision(
            instruction = instruction,
            appPackage = appPackage,
            failedSkillId = failedSkillId,
            trigger = trigger,
            phase = phase,
            previousResult = previousResult,
            previousReportFile = previousReportFile,
            flowSteps = flowSteps
        )
        step(
            "$label 返回",
            ai.status,
            "HTTP=${ai.httpStatus ?: "-"}；模型=${ai.model.ifBlank { "未返回" }}；goalCompleted=${ai.goalCompleted}；补救步骤=${ai.planSteps.size}；诊断=${ai.diagnosis.take(80)}",
            ai.jobFile.absolutePath,
            JSONObject().put("decision", ai.decision)
        )
        if (ai.status != AiAssistStatus.SUCCESS.name) {
            return AiFollowUpExecution(ai.jobFile, runtimeReportFile = null, success = false, executed = false)
        }

        if (requiresAccessibilityControl(instruction) && MacroPilotAccessibilityService.instance == null) {
            step(
                "$label 已判断但不执行",
                "FAILED_ACCESSIBILITY_DISCONNECTED",
                "AI 已给出判断，但无障碍服务断开，不能继续点击/搜索/点赞；停止补救，避免再次把 OpenApp/Wait 误报成功。",
                ai.jobFile.absolutePath,
                JSONObject().put("decision", ai.decision)
            )
            return AiFollowUpExecution(ai.jobFile, runtimeReportFile = null, success = false, executed = false)
        }

        val savedAiCandidates = ai.candidates.map { candidate ->
            store.saveSkill(candidate.optString("appPackage", appPackage).ifBlank { appPackage }, candidate)
        }
        if (savedAiCandidates.isNotEmpty()) {
            step("AI 补救 Candidate 已保存", "SUCCESS", "保存 ${savedAiCandidates.size} 个补救候选 Skill JSON。", savedAiCandidates.first().absolutePath, null)
        }

        if (ai.goalCompleted && ai.planSteps.isEmpty()) {
            step("$label 确认目标已完成", "SUCCESS", "AI 根据当前屏幕判断目标已经完成，不再追加动作。", ai.jobFile.absolutePath, null)
            return AiFollowUpExecution(ai.jobFile, runtimeReportFile = null, success = true, executed = false)
        }
        if (ai.planSteps.isEmpty()) {
            step("$label 没有可执行补救动作", "FAILED_NO_REPLAN", "AI 没有返回 steps，也没有确认目标完成。", ai.jobFile.absolutePath, null)
            return AiFollowUpExecution(ai.jobFile, runtimeReportFile = null, success = false, executed = false)
        }

        val built = buildDirectPlanSkills(instruction, appPackage, ai.planSteps)
        step(
            "AI 补救计划转 Skill JSON",
            "SUCCESS",
            "AI 返回 ${ai.planSteps.size} 个下一步动作，已转换成 ${built.skillFiles.size} 个候选 Skill JSON。",
            built.macroFile.absolutePath,
            JSONObject().put("phase", phase).put("trigger", trigger)
        )
        val dry = dryRunFiles(appPackage, savedAiCandidates + built.skillFiles)
        step("AI 补救 dry-run", if (dry.passed == dry.total) "SUCCESS" else "FAILED_DRY_RUN", "通过 ${dry.passed}/${dry.total}。", dry.lastReportPath, null)

        val macro = JSONObject(built.macroFile.readText())
        val startedAt = System.currentTimeMillis()
        val result = SkillJsonExecutor(context, runtimeApprovedOnly = false)
            .execute(macro, JSONObject(), allowSideEffect = true)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_ai_follow_up_run_report")
            .put("id", "ai_follow_up_run_${System.currentTimeMillis()}")
            .put("skillId", macro.optString("id"))
            .put("failedSkillId", failedSkillId)
            .put("appPackage", appPackage)
            .put("instruction", instruction)
            .put("engine", "ai_runtime_${phase.lowercase(Locale.US)}")
            .put("phase", phase)
            .put("trigger", trigger)
            .put("previousStatus", previousResult.status)
            .put("previousMessage", previousResult.message)
            .put("previousRuntimeReportFile", previousReportFile?.absolutePath ?: JSONObject.NULL)
            .put("aiJobFile", ai.jobFile.absolutePath)
            .put("decision", ai.decision)
            .put("approvedRequired", false)
            .put("status", result.status)
            .put("message", result.message)
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("usedCoordinate", result.usedCoordinate)
            .put("details", result.details ?: JSONObject.NULL)
            .put("allowSideEffect", true)
            .put("skillFile", built.macroFile.absolutePath)
            .put("createdAt", System.currentTimeMillis())
        val reportFile = store.saveRuntimeRunReport(appPackage, report)
        step(
            "AI 补救 JSON 执行结果",
            if (result.status.startsWith("FAILED")) result.status else "SUCCESS",
            "${macro.optString("id")} -> ${result.status}；${result.message}",
            reportFile.absolutePath,
            JSONObject().put("phase", phase).put("trigger", trigger)
        )
        return AiFollowUpExecution(
            aiJobFile = ai.jobFile,
            runtimeReportFile = reportFile,
            success = !result.status.startsWith("FAILED"),
            executed = true
        )
    }

    private fun requestAiFollowUpDecision(
        instruction: String,
        appPackage: String,
        failedSkillId: String,
        trigger: String,
        phase: String,
        previousResult: SkillJsonRunResult,
        previousReportFile: File?,
        flowSteps: JSONArray
    ): AiFollowUpResult {
        val config = AiAssistConfigStore(context).load()
        val taskType = if (phase == "REPLAN") "RUNTIME_REPLAN_AFTER_FAILURE" else "RUNTIME_CHECKPOINT"
        val task = JSONObject()
            .put("taskType", taskType)
            .put("phase", phase)
            .put("trigger", trigger)
            .put("instruction", instruction)
            .put("appPackage", appPackage)
            .put("failedSkillId", failedSkillId)
            .put("previousResult", JSONObject()
                .put("status", previousResult.status)
                .put("message", previousResult.message)
                .put("durationMs", previousResult.durationMs)
                .put("usedCoordinate", previousResult.usedCoordinate))
            .put("previousRuntimeReportFile", previousReportFile?.absolutePath ?: JSONObject.NULL)
            .put("currentUi", currentUiSummary())
            .put("appUiGraph", appUiGraphStore.compactSummary(appPackage))
            .put("availableSkillTemplates", availableSkillTemplateSummary(appPackage, 24))
            .put("recentUiSnapshots", snapshotStore.recentSummary(appPackage.takeIf { it.isNotBlank() }, 10))
            .put("recentFlowSteps", compactFlowSteps(flowSteps, 18))
            .put("outputContract", JSONObject()
                .put("decision", JSONObject()
                    .put("goalCompleted", false)
                    .put("screenAssessment", "当前页面是什么，看到哪些关键节点")
                    .put("failureDiagnosis", "失败/歧义原因，例如搜索结果页、详情页、节点缺失、输入失败、需要返回")
                    .put("confidence", "HIGH | MEDIUM | LOW")
                    .put("nextActionReason", "为什么选择下一步动作"))
                .put("steps", JSONArray(listOf(
                    JSONObject().put("type", "ClickVisibleText").put("text", "可见文字"),
                    JSONObject().put("type", "ClickSelector").put("selector", "[text='搜索'][clickable=true]"),
                    JSONObject().put("type", "ClickCoordinate").put("x", 0.50).put("y", 0.30),
                    JSONObject().put("type", "TypeIntoFirstInput").put("text", "输入内容"),
                    JSONObject().put("type", "TypeIntoFocusedInput").put("text", "输入到当前聚焦框"),
                    JSONObject().put("type", "ScrollForward"),
                    JSONObject().put("type", "Back"),
                    JSONObject().put("type", "HandleReactiveOverlays").put("maxPasses", 3),
                    JSONObject().put("type", "RecoverBilibiliHomeSurface")
                        .put("timeoutMs", 6000),
                    JSONObject().put("type", "FindEntryByBottomTabSweep")
                        .put("entryAliases", JSONArray(listOf("\u53d1\u5e16", "\u53d1\u5e03", "\u521b\u4f5c", "\u641c\u7d22", "\u6d88\u606f", "+")))
                        .put("maxTabs", 5),
                    JSONObject().put("type", "EnsureHupuSectionSelected"),
                    JSONObject().put("type", "Wait").put("timeoutMs", 800)
                )))
                .put("candidates", "optional fine-grained SkillCandidate JSON array"))
            .put("rules", JSONArray(listOf(
                "先判断当前屏幕，再给下一小段动作。",
                "失败后不要重复整条旧计划，只输出能够把当前页面推进一步的 1-5 个 steps。",
                "优先参考 appUiGraph 和 availableSkillTemplates：先判断卡在哪个 screen/entry/input，再返回可被模板补丁拼装的关键 slot。",
                "如果当前在搜索结果页并且用户要第一个视频，就优先打开第一个视频结果。",
                "如果当前在视频详情页并且用户要点赞，就优先找“点赞/赞/like”节点；节点不可读时才用低置信坐标。",
                "你会收到 currentUi.clickTargets 和 currentUi.videoCandidates。点击哪个视频必须基于这些候选：在 decision.nextActionReason 写 targetCandidateIndex、候选文本/描述/bounds 和选择理由。",
                "如果用户说“第一个视频”，选择 videoCandidates 中第一个位于内容区且不是搜索栏/返回/清除按钮的候选；如果没有候选或还在 loading，只能 Wait 或 ScrollForward，不能乱点。",
                "如果目标是打开第一个视频，不能只因为搜索结果页出现“视频/播放/王老菊”就说 goalCompleted=true；必须看到视频详情页/播放页证据，并且搜索输入框和结果页 tabs 已经不在当前页面。",
                "B 站任务里 ComposeActivity、opus_detail、dy_opus、动态、分享面板、评论区底栏、我的消息/系统通知/游戏通知都不是“第一个视频已打开”；如果看到这些页面，优先输出 RecoverBilibiliHomeSurface 或 Back 回到主页后重新搜索，不能点底部分享。",
                "如果目标是发小红书/写小红书草稿，不能只因为打开了小红书或点过底部加号就说 goalCompleted=true；必须看到草稿编辑页证据，并且看到标题或正文文本已经写入。",
                "小红书草稿任务优先找 currentUi.clickTargets 中 tags 包含 create_control、draft_editor、title_input、body_input、next_step 的节点；没有可靠节点时才使用低置信坐标：底部创作入口一般 x=0.50 y=0.935，标题区域 x=0.50 y=0.34，正文区域 x=0.50 y=0.49。",
                "小红书任务默认只写草稿，除非用户明确说“现在发布/确认发布”，否则不要点击最终发布按钮。",
                "currentUi.blockingDialogs 是硬证据：如果包含 ad_or_splash，先关闭/跳过广告；如果包含 hupu_section_required，说明虎扑发帖被专区选择拦住，下一步必须选择专区/板块，不能继续 Wait 或重复原计划。",
                "如果你选择候选，steps 应使用 ClickCoordinate，并使用该候选 center.x/center.y；不要自己编造 selector。",
                "ClickSelector 只能使用简单选择器：[text='文字']、[text*='文字']、[desc='描述']、[desc*='描述']、[resourceId='完整id']、[class='完整类名'][clickable=true]。不要输出 >、:first-child、空格层级选择器或 CSS/XPath。",
                "如果只能判断大概位置，直接输出 ClickCoordinate，搜索结果第一个视频一般可先尝试 x=0.50 y=0.36；视频详情点赞区域一般可先尝试 x=0.26 y=0.86，但必须低置信并等待验证。",
                "如果目标已经完成，decision.goalCompleted=true 且 steps=[]。",
                "所有坐标必须是 0..1 归一化坐标。"
            )))
        val response = AiAssistClient(context).request(
            AiAssistRequest(
                useCase = if (phase == "REPLAN") AiAssistUseCase.RUNTIME_REPLAN else AiAssistUseCase.RUNTIME_CHECKPOINT,
                inputJson = task,
                redacted = true,
                userVisible = true,
                runtimePath = true
            )
        )
        val root = response.outputJson?.optJSONObject("output") ?: response.outputJson ?: JSONObject()
        val decision = extractDecision(root)
        val planSteps = extractPlanSteps(root)
        val intentDraft = JSONObject()
            .put("appPackage", appPackage)
            .put("intentId", inferIntentId(instruction))
            .put("params", JSONObject()
                .put("query", extractSearchQuery(instruction))
                .put("message", extractMessage(instruction)))
            .put("source", phase)
        val candidates = extractCandidates(root, intentDraft, response.status.name)
        val goalCompleted = decision.optBoolean("goalCompleted", false)
        val job = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "factory_ai_job")
            .put("id", "factory_ai_${phase.lowercase(Locale.US)}_${System.currentTimeMillis()}")
            .put("taskType", taskType)
            .put("phase", phase)
            .put("trigger", trigger)
            .put("appPackage", appPackage)
            .put("relatedCandidateIds", JSONArray(listOf(failedSkillId)))
            .put("createdAt", System.currentTimeMillis())
            .put("inputSummary", "$trigger: ${previousResult.status} ${previousResult.message}".take(500))
            .put("promptSnapshot", task)
            .put("aiStatus", response.status.name)
            .put("aiMessage", response.message)
            .put("provider", response.provider?.name)
            .put("httpStatus", response.httpStatus ?: JSONObject.NULL)
            .put("model", response.outputJson?.optString("model").orEmpty().ifBlank { config.model })
            .put("baseUrlHost", endpointHost(config.endpoint))
            .put("rawResponse", response.outputJson ?: JSONObject.NULL)
            .put("parsedJson", JSONObject()
                .put("decision", decision)
                .put("planStepCount", planSteps.size)
                .put("aiCandidateCount", candidates.size)
                .put("goalCompleted", goalCompleted))
            .put("parseSuccess", response.status == AiAssistStatus.SUCCESS && (goalCompleted || planSteps.isNotEmpty() || candidates.isNotEmpty()))
            .put("errors", JSONArray(if (response.status == AiAssistStatus.SUCCESS) emptyList<String>() else listOf(response.message)))
            .put("accepted", goalCompleted || planSteps.isNotEmpty() || candidates.isNotEmpty())
            .put("acceptedBy", "APP_SIDE_RUNTIME_$phase")
            .put("acceptedAt", System.currentTimeMillis())
        val jobFile = store.saveAiJob(job)
        return AiFollowUpResult(
            jobFile = jobFile,
            decision = decision,
            planSteps = planSteps,
            candidates = candidates,
            goalCompleted = goalCompleted,
            diagnosis = decision.optString("failureDiagnosis")
                .ifBlank { decision.optString("screenAssessment") },
            status = response.status.name,
            httpStatus = response.httpStatus,
            model = job.optString("model")
        )
    }

    private fun compactFlowSteps(flowSteps: JSONArray, limit: Int): JSONArray {
        val out = JSONArray()
        val start = (flowSteps.length() - limit).coerceAtLeast(0)
        for (i in start until flowSteps.length()) {
            val item = flowSteps.optJSONObject(i) ?: continue
            out.put(JSONObject()
                .put("title", item.optString("title"))
                .put("status", item.optString("status"))
                .put("message", item.optString("message").take(260))
                .put("path", item.optString("path")))
        }
        return out
    }

    private fun atomTraceContains(details: JSONObject?, needle: String): Boolean {
        if (details == null || needle.isBlank()) return false
        val trace = details.optJSONArray("atomTrace") ?: return false
        for (index in 0 until trace.length()) {
            val atom = trace.optJSONObject(index) ?: continue
            if (atom.optString("message").contains(needle, ignoreCase = true)) return true
            if (atom.optJSONObject("details")?.toString()?.contains(needle, ignoreCase = true) == true) return true
        }
        return false
    }

    private fun extractDecision(root: JSONObject): JSONObject {
        return root.optJSONObject("decision")
            ?: root.optJSONObject("checkpoint")
            ?: root.optJSONObject("replan")
            ?: JSONObject()
    }

    private fun needsRuntimeCheckpoint(instruction: String, result: SkillJsonRunResult): Boolean {
        val text = instruction.lowercase(Locale.CHINA)
        if (isHupuExplicitPublishInstruction(instruction, "com.hupu.games")) return false
        if (result.usedCoordinate) return true
        if (isDouyinVideoPublishInstruction(instruction, SkillJsonRuleGenerator.DOUYIN_PACKAGE)) return true
        return listOf("第一个", "视频", "点赞", "评论", "详情", "打开结果", "收藏", "关注", "小红书", "发布", "发帖", "帖子", "笔记", "草稿")
            .any { marker -> text.contains(marker) }
    }

    private fun shouldAiReviewBeforeExecution(instruction: String, appPackage: String): Boolean {
        val text = instruction.lowercase(Locale.CHINA)
        val app = appPackage.lowercase(Locale.US)
        return listOf(
            "第一个",
            "视频",
            "点赞",
            "评论",
            "发布",
            "发表",
            "发帖",
            "帖子",
            "笔记",
            "朋友圈",
            "仅自己",
            "草稿"
        ).any { marker -> text.contains(marker) } ||
            app.contains("hupu") ||
            app.contains("aweme") ||
            app.contains("xiaohongshu") ||
            app == SkillJsonRuleGenerator.BILIBILI_PACKAGE ||
            app == SkillJsonRuleGenerator.WECHAT_PACKAGE
    }

    private fun primaryInstructionText(instruction: String): String {
        val firstBlock = instruction.substringBefore("\n\n").trim()
        return firstBlock.ifBlank { instruction.trim() }
    }

    private fun verifyInstructionGoal(instruction: String, appPackage: String): GoalCheck {
        val goalInstruction = primaryInstructionText(instruction)
        val text = goalInstruction.lowercase(Locale.CHINA)
        val mustVerifyLike = text.contains("点赞") || text.contains("点个赞")
        val mustVerifyComment = text.contains("评论")
        val mustVerifyXhsDraft = isXiaohongshuPostInstruction(goalInstruction, appPackage)
        val mustVerifyGenericDraft = isGenericPostDraftInstruction(goalInstruction, appPackage)
        val mustVerifyDouyinVideoPublish = isDouyinVideoPublishInstruction(goalInstruction, appPackage)
        val mustVerifyHupuPublish = isHupuExplicitPublishInstruction(goalInstruction, appPackage)
        val mustVerifyWechatMomentPrivate = isWechatMomentPrivateInstruction(goalInstruction, appPackage)
        val mustVerifyOpenFirst = isOpenFirstContentInstruction(goalInstruction)
        val mustVerifySearchEntry = isSearchEntryOnlyInstruction(goalInstruction)
        val mustVerifySearch = !mustVerifySearchEntry &&
            (isGenericSearchInstruction(goalInstruction) || isBilibiliSearchInstruction(goalInstruction, appPackage))
        val mandatory = mustVerifyLike ||
            mustVerifyComment ||
            mustVerifyXhsDraft ||
            mustVerifyGenericDraft ||
            mustVerifyDouyinVideoPublish ||
            mustVerifyHupuPublish ||
            mustVerifyWechatMomentPrivate ||
            mustVerifySearchEntry ||
            mustVerifySearch
        if (!mandatory) {
            return GoalCheck(mandatory = false, passed = true, message = "该指令不需要额外目标校验。", evidence = JSONObject())
        }
        if (MacroPilotAccessibilityService.instance == null) {
            return GoalCheck(
                mandatory = true,
                passed = false,
                message = "无法校验目标：MacroPilot 无障碍服务未连接，不能读取当前页面节点，所以不能报告成功。",
                evidence = JSONObject().put("serviceConnected", false)
            )
        }
        val state = inspector.currentState()
            ?: return GoalCheck(
                mandatory = true,
                passed = false,
                message = "无法校验目标：当前没有 UiState。",
                evidence = JSONObject().put("serviceConnected", true).put("uiState", "null")
            )
        val eventVisible = state.eventsSinceLastState.mapNotNull { event ->
            event.text
                ?.takeIf { it.isNotBlank() }
                ?.let { text -> "event:${event.eventType}:$text" }
        }
        val nodeVisible = state.nodes.mapNotNull { node ->
            listOf(node.text, node.contentDesc)
                .filterNotNull()
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        }
        val visible = eventVisible + nodeVisible
        val goalProfile = profiler.profile(state, MacroPilotAccessibilityService.instance != null)
        val goalSnapshotFile = runCatching {
            snapshotStore.save(
                state = state,
                profile = goalProfile,
                source = "goal_verification",
                note = goalInstruction.take(160),
                forceScreenshot = true
            )
        }.getOrNull()
        val goalSnapshotJson = goalSnapshotFile?.let { file ->
            runCatching { JSONObject(file.readText()) }.getOrNull()
        }
        val evidence = JSONObject()
            .put("serviceConnected", true)
            .put("appPackage", state.appPackage)
            .put("nodeCount", state.nodes.size)
            .put("goalVerificationSnapshotPath", goalSnapshotFile?.absolutePath ?: JSONObject.NULL)
            .put("goalVerificationScreenshotPath", goalSnapshotJson?.optString("screenshotPath")?.takeIf { it.isNotBlank() && it != "null" } ?: JSONObject.NULL)
            .put("goalVerificationScreenshotSha256", goalSnapshotJson?.optString("screenshotSha256")?.takeIf { it.isNotBlank() && it != "null" } ?: JSONObject.NULL)
            .put("goalVerificationVisualHash", goalSnapshotJson?.optString("visualHash")?.takeIf { it.isNotBlank() && it != "null" } ?: JSONObject.NULL)
            .put("sampleTexts", JSONArray(visible.take(30)))

        if (mustVerifyDouyinVideoPublish) {
            return verifyDouyinVideoPublished(goalInstruction, state, visible, evidence)
        }

        if (mustVerifyWechatMomentPrivate) {
            return verifyWechatMomentPrivatePosted(goalInstruction, state, visible, evidence)
        }

        if (mustVerifyLike) {
            val liked = visible.any { item ->
                item.contains("已点赞") ||
                    item.contains("取消点赞") ||
                    item.contains("已赞") ||
                    item.contains("liked", ignoreCase = true) ||
                    item.contains("unlike", ignoreCase = true)
            }
            return GoalCheck(
                mandatory = true,
                passed = liked,
                message = if (liked) {
                    "已看到点赞后的节点证据。"
                } else {
                    "没有看到“已点赞/取消点赞”等节点证据，不能把点赞任务报告为成功。"
                },
                evidence = evidence.put("expected", "liked_state")
            )
        }

        if (mustVerifyComment) {
            val comment = extractCommentText(goalInstruction)
            val visibleComment = comment.isNotBlank() && visible.any { it.contains(comment) }
            return GoalCheck(
                mandatory = true,
                passed = visibleComment,
                message = if (visibleComment) {
                    "已看到评论草稿文本。"
                } else {
                    "没有看到评论草稿文本，不能报告评论任务成功。"
                },
                evidence = evidence.put("expectedComment", comment)
            )
        }

        if (mustVerifyXhsDraft) {
            if (isXiaohongshuExplicitPublishInstruction(goalInstruction, appPackage)) {
                return verifyXiaohongshuPostPublished(goalInstruction, state, visible, evidence)
            }
            return verifyXiaohongshuDraftWritten(goalInstruction, state, visible, evidence)
        }

        if (mustVerifyHupuPublish) {
            return verifyHupuPostPublished(goalInstruction, state, visible, evidence)
        }

        if (mustVerifyGenericDraft) {
            val content = extractGenericPostDraftContent(goalInstruction)
            val contentVisible = content.isNotBlank() && visible.any { it.contains(content) }
            val editorVisible = state.nodes.any { node -> node.editable } ||
                visible.any { item ->
                    item.contains("\u8349\u7a3f") ||
                        item.contains("\u53d1\u5e16") ||
                        item.contains("\u5199\u5fae\u535a") ||
                        item.contains("\u53d1\u5fae\u535a") ||
                        item.contains("\u53d1\u9001") ||
                        item.contains("\u53d1\u5e03")
                }
            val passed = contentVisible && editorVisible
            return GoalCheck(
                mandatory = true,
                passed = passed,
                message = if (passed) {
                    "\u5df2\u770b\u5230\u53d1\u5e16/\u5fae\u535a\u8349\u7a3f\u5185\u5bb9\u548c\u7f16\u8f91\u9875\u8bc1\u636e\u3002"
                } else {
                    "\u672a\u540c\u65f6\u770b\u5230\u8349\u7a3f\u5185\u5bb9\u548c\u7f16\u8f91\u9875\u8bc1\u636e\uff0c\u4e0d\u80fd\u628a\u53d1\u5e16/\u5fae\u535a\u4efb\u52a1\u62a5\u544a\u4e3a\u6210\u529f\u3002"
                },
                evidence = evidence
                    .put("expected", "generic_post_draft_written")
                    .put("contentNeedle", content)
                    .put("contentVisible", contentVisible)
                    .put("editorVisible", editorVisible)
            )
        }

        if (mustVerifyOpenFirst) {
            if (appPackage == SkillJsonRuleGenerator.BILIBILI_PACKAGE) {
                return verifyBilibiliFirstVideoOpened(state, visible, evidence)
            }
            val detailMarkers = listOf("关注", "评论", "收藏", "点赞", "赞", "分享", "回答", "发布于", "笔记", "弹幕")
            val detailMarkerCount = detailMarkers.count { marker ->
                visible.any { item -> item.contains(marker) }
            }
            val searchInputPresent = state.nodes.any { node ->
                node.editable ||
                    node.resourceId?.contains("search", ignoreCase = true) == true
            }
            val resultTabsVisible = visible.any { item ->
                item == "综合" || item == "视频" || item == "用户" || item.contains("搜索结果")
            }
            val detailMarkerVisible = detailMarkerCount >= 2
            val searchOnly = searchInputPresent || resultTabsVisible
            return GoalCheck(
                mandatory = true,
                passed = detailMarkerVisible && !searchOnly,
                message = if (detailMarkerVisible && !searchOnly) {
                    "已看到详情页/内容页证据，第一条内容已打开。"
                } else {
                    "没有看到详情页/内容页证据，不能报告第一条内容已打开。"
                },
                evidence = evidence
                    .put("expected", "first_result_detail_opened")
                    .put("detailMarkerVisible", detailMarkerVisible)
                    .put("detailMarkerCount", detailMarkerCount)
                    .put("searchInputPresent", searchInputPresent)
                    .put("resultTabsVisible", resultTabsVisible)
                    .put("searchOnly", searchOnly)
            )
        }

        if (mustVerifySearchEntry) {
            return verifySearchEntryOpened(appPackage, state, visible, evidence)
        }

        if (mustVerifySearch) {
            val query = extractSearchQuery(goalInstruction)
            val queryVisible = query.isNotBlank() && visible.any { it.contains(query, ignoreCase = true) }
            val pinduoduoCameraSearchSurface = isPinduoduoCameraSearchSurface(state, visible)
            val ecommerceResultMarkerVisible = isPinduoduoPackage(appPackage) && visible.any { item ->
                item.contains("商品") ||
                    item.contains("店铺") ||
                    item.contains("销量") ||
                    item.contains("筛选") ||
                    item.contains("价格") ||
                    item.contains("券") ||
                    item.contains("评价") ||
                    item.contains("拼单") ||
                    item.contains("百亿补贴")
            }
            val resultMarkerVisible = visible.any { item ->
                item.contains("综合") ||
                    item.contains("视频") ||
                    item.contains("用户") ||
                    item.contains("搜索结果") ||
                    item.contains("播放") ||
                    item.contains("粉丝")
            } || ecommerceResultMarkerVisible
            val passed = queryVisible && resultMarkerVisible && !pinduoduoCameraSearchSurface
            return GoalCheck(
                mandatory = true,
                passed = passed,
                message = if (passed) {
                    "已看到搜索词和结果页标记。"
                } else if (pinduoduoCameraSearchSurface) {
                    "当前是拼多多拍照搜/相机权限页，不是文字商品搜索结果页，必须返回后重走文字搜索。"
                } else {
                    "没有同时看到搜索词和结果页标记，不能把搜索任务报告为成功。"
                },
                evidence = evidence
                    .put("query", query)
                    .put("queryVisible", queryVisible)
                    .put("resultMarkerVisible", resultMarkerVisible)
                    .put("ecommerceResultMarkerVisible", ecommerceResultMarkerVisible)
                    .put("pinduoduoCameraSearchSurface", pinduoduoCameraSearchSurface)
            )
        }

        return GoalCheck(mandatory = false, passed = true, message = "无额外校验。", evidence = evidence)
    }

    private fun verifyHupuPostPublished(
        instruction: String,
        state: com.macropilot.app.model.UiStateSample,
        visible: List<String>,
        evidence: JSONObject
    ): GoalCheck {
        val title = extractGenericPostTitle(instruction)
        val body = extractGenericPostBody(instruction)
        val content = extractGenericPostDraftContent(instruction)
        val contentNeedles = listOf(title, body, content)
            .map { it.trim().take(80) }
            .filter { it.length >= 2 }
            .distinct()
        val blockingDialogs = reactiveRules.enhancedBlockingDialogSummary(state)
        val eventTexts = visible.filter { it.startsWith("event:") }
        val hardBlockerMarkers = listOf(
            "选择专区",
            "请选择专区",
            "选择板块",
            "选择版块",
            "内容不能为空",
            "标题不能为空",
            "发布失败",
            "发帖失败",
            "网络异常"
        ).filter { marker -> visible.any { item -> item.contains(marker, ignoreCase = true) } }
        val authBlockerMarkers = listOf(
            "请登录",
            "登录后",
            "未登录",
            "账号验证",
            "身份验证",
            "完成验证",
            "验证码"
        ).filter { marker -> eventTexts.any { item -> item.contains(marker, ignoreCase = true) } }
        val blockerMarkers = hardBlockerMarkers + authBlockerMarkers
        val successMarkerTerms = listOf(
            "发布成功",
            "发帖成功",
            "发表成功",
            "已发布",
            "审核中",
            "审核通过",
            "已审核通过",
            "已经审核通过",
            "发布的主帖",
            "发布中",
            "帖子已发布"
        )
        val contentVisible = contentNeedles.any { needle ->
            visible.any { item -> item.contains(needle, ignoreCase = true) }
        }
        val successEvidence = visible.filter { item ->
            successMarkerTerms.any { marker -> item.contains(marker, ignoreCase = true) } &&
                (title.isBlank() || item.contains(title, ignoreCase = true) || contentVisible)
        }
        val successMarkers = successMarkerTerms.filter { marker ->
            successEvidence.any { item -> item.contains(marker, ignoreCase = true) }
        }
        val editorVisible = state.nodes.any { node -> node.editable } ||
            visible.any { item -> item.contains("发帖") || item.contains("发布") || item.contains("标题") || item.contains("正文") }
        val detailMarkers = listOf("回复", "评论", "分享", "收藏", "推荐", "楼主").count { marker ->
            visible.any { item -> item.contains(marker, ignoreCase = true) }
        }
        val passed = state.appPackage.orEmpty().contains("hupu", ignoreCase = true) &&
            blockerMarkers.isEmpty() &&
            (successEvidence.isNotEmpty() || (contentVisible && !editorVisible && detailMarkers >= 2))
        val message = when {
            passed -> "已看到虎扑发帖成功/审核中/帖子详情证据。"
            blockerMarkers.isNotEmpty() -> "虎扑发帖被阻塞：${blockerMarkers.joinToString("、")}。不能继续原计划或报告成功，必须先处理这些提示。"
            else -> "没有看到虎扑发帖成功、审核中或帖子详情证据，不能报告已发出去。"
        }
        return GoalCheck(
            mandatory = true,
            passed = passed,
            message = message,
            blockerType = when {
                blockerMarkers.isNotEmpty() && blockerMarkers.any { it.contains("专区") || it.contains("板块") || it.contains("版块") } -> "HUPU_SECTION_REQUIRED"
                blockerMarkers.isNotEmpty() && authBlockerMarkers.isNotEmpty() -> "HUPU_AUTH_REQUIRED"
                blockerMarkers.isNotEmpty() -> "HUPU_PUBLISH_BLOCKED"
                !successEvidence.isNotEmpty() -> "HUPU_EVIDENCE_MISSING"
                else -> "NONE"
            },
            nextActionHint = when {
                blockerMarkers.isNotEmpty() && blockerMarkers.any { it.contains("专区") || it.contains("板块") || it.contains("版块") } ->
                    "先选择专区/板块，再回到发帖编辑页继续，不要重复 Wait。"
                blockerMarkers.isNotEmpty() && authBlockerMarkers.isNotEmpty() ->
                    "先完成登录或验证码，再重新读取当前页的小字提示。"
                blockerMarkers.isNotEmpty() ->
                    "先处理当前阻断提示，再回到发帖流程确认最终发布证据。"
                !successEvidence.isNotEmpty() ->
                    "回到帖子详情页或结果页，读取发布成功/审核中/帖子已发布证据。"
                else -> ""
            },
            evidence = evidence
                .put("expected", "hupu_post_published")
                .put("expectedTitle", title)
                .put("expectedBody", body)
                .put("contentNeedle", content)
                .put("contentNeedles", JSONArray(contentNeedles))
                .put("contentVisible", contentVisible)
                .put("editorVisible", editorVisible)
                .put("detailMarkerCount", detailMarkers)
                .put("successEvidence", JSONArray(successEvidence.take(8)))
                .put("successMarkers", JSONArray(successMarkers))
                .put("blockerMarkers", JSONArray(blockerMarkers))
                .put("blockingDialogs", blockingDialogs)
        )
    }

    private fun verifyBilibiliFirstVideoOpened(
        state: com.macropilot.app.model.UiStateSample,
        visible: List<String>,
        evidence: JSONObject
    ): GoalCheck {
        val searchInputPresent = state.nodes.any { node ->
            node.editable ||
                node.resourceId?.contains("search_src_text", ignoreCase = true) == true ||
                node.resourceId?.contains("search_keyword", ignoreCase = true) == true ||
                node.resourceId?.contains("search_input", ignoreCase = true) == true
        }
        val resultTabsVisible = visible.any { item ->
            item == "综合" ||
                item == "视频" ||
                item == "用户" ||
                item == "番剧" ||
                item == "直播" ||
                item == "专栏" ||
                item.contains("搜索结果")
        }
        val detailMarkers = listOf("点赞", "投币", "收藏", "转发", "分享", "评论", "弹幕", "简介", "相关推荐", "缓存", "关注")
        val markerHits = detailMarkers.filter { marker ->
            visible.any { item -> item.contains(marker) }
        }
        val playerMarkers = listOf("全屏", "倍速", "清晰度", "暂停", "播放中", "小窗")
        val playerMarkerHits = playerMarkers.filter { marker ->
            visible.any { item -> item.contains(marker) }
        }
        val compactResourceIds = state.nodes.mapNotNull { node ->
            node.resourceId
                ?.substringAfter(":id/", node.resourceId)
                ?.takeIf { it.isNotBlank() }
        }
        val compactClasses = state.nodes.mapNotNull { node ->
            node.className?.takeIf { it.isNotBlank() }
        }
        val windowClass = state.windowClassName.orEmpty()
        val nonVideoResourceHits = compactResourceIds.filter { id ->
            id.contains("opus", ignoreCase = true) ||
                id.startsWith("dy_", ignoreCase = true) ||
                id.contains("dynamic", ignoreCase = true) ||
                id.contains("following", ignoreCase = true) ||
                id.contains("supermenu", ignoreCase = true) ||
                id.contains("share_sheet", ignoreCase = true)
        }
        val nonVideoTextHits = listOf(
            "点我发评论",
            "热门评论",
            "赞和转发",
            "转发",
            "生成长图",
            "复制链接",
            "动态",
            "专栏",
            "最近在看的视频",
            "最近使用的小程序",
            "离线缓存",
            "历史记录",
            "我的收藏",
            "稍后再看",
            "看视频",
            "听视频"
        ).filter { marker ->
            visible.any { item -> item.contains(marker, ignoreCase = true) }
        }
        val videoResourceHits = compactResourceIds.filter { id ->
            id.contains("player", ignoreCase = true) ||
                id.contains("video", ignoreCase = true) ||
                id.contains("videodetail", ignoreCase = true) ||
                id.contains("ugc", ignoreCase = true) ||
                id.contains("danmaku", ignoreCase = true) ||
                id.contains("play", ignoreCase = true)
        } + compactClasses.filter { klass ->
            klass.contains("video", ignoreCase = true) ||
                klass.contains("player", ignoreCase = true)
        } + listOfNotNull(windowClass.takeIf {
            it.contains("video", ignoreCase = true) || it.contains("player", ignoreCase = true)
        })
        val blockerMarkers = listOf(
            "分享",
            "QQ",
            "微信",
            "朋友圈",
            "复制链接",
            "举报",
            "不推荐",
            "消息设置",
            "转到动态",
            "发布",
            "发个消息",
            "唠会嗑",
            "最近在看的视频",
            "最近使用的小程序",
            "离线缓存",
            "历史记录",
            "我的收藏",
            "稍后再看"
        )
        val blockerHits = blockerMarkers.filter { marker ->
            visible.any { item -> item.contains(marker, ignoreCase = true) }
        }
        val shareSheetVisible = state.windowClassName.orEmpty().contains("supermenu", ignoreCase = true) ||
            reactiveRules.enhancedBlockingDialogSummary(state).toString().contains("share_sheet", ignoreCase = true) ||
            blockerHits.count { it in listOf("分享", "QQ", "微信", "朋友圈", "复制链接", "举报", "不推荐") } >= 2
        val unsafePostOrMessageSurface = blockerHits.any { marker ->
            marker in listOf(
                "消息设置",
                "转到动态",
                "发布",
                "发个消息",
                "唠会嗑",
                "最近在看的视频",
                "最近使用的小程序",
                "离线缓存",
                "历史记录",
                "我的收藏",
                "稍后再看"
            )
        }
        val searchSurfaceVisible = searchInputPresent || resultTabsVisible
        val nonVideoDetailSurface =
            windowClass.contains("ComposeActivity", ignoreCase = true) ||
                nonVideoResourceHits.isNotEmpty() ||
                nonVideoTextHits.isNotEmpty()
        val videoSpecificEvidence = playerMarkerHits.isNotEmpty() || videoResourceHits.isNotEmpty()
        val detailEvidenceStrong = (markerHits.size >= 2 || (markerHits.isNotEmpty() && playerMarkerHits.isNotEmpty())) &&
            videoSpecificEvidence &&
            !nonVideoDetailSurface
        val passed = state.appPackage == SkillJsonRuleGenerator.BILIBILI_PACKAGE &&
            detailEvidenceStrong &&
            !searchSurfaceVisible &&
            !shareSheetVisible &&
            !unsafePostOrMessageSurface
        return GoalCheck(
            mandatory = true,
            passed = passed,
            message = when {
                passed -> "已看到 B 站视频详情页/播放页证据，且搜索输入框和结果页 tabs 已消失。"
                shareSheetVisible -> "当前是 B 站分享面板/弹出菜单，不是视频详情页，不能报告第一个视频已打开。"
                nonVideoDetailSurface -> "当前是 B 站动态/图文/评论/分享相关页面，不是视频播放详情页，不能报告第一个视频已打开。"
                unsafePostOrMessageSurface -> "当前像 B 站消息/动态发布相关页面，不是视频详情页，不能报告第一个视频已打开。"
                searchSurfaceVisible -> "当前仍像搜索结果页：搜索输入框或“综合/视频/用户”等结果页 tabs 还在，不能报告第一个视频已打开。"
                !videoSpecificEvidence -> "没有看到播放器/视频详情资源或播放控件证据，不能把普通内容详情页当成第一个视频。"
                else -> "没有看到 B 站视频详情页/播放页的强证据，不能报告第一个视频已打开。"
            },
            blockerType = when {
                shareSheetVisible -> "BILIBILI_SHARE_PANEL_VISIBLE"
                nonVideoDetailSurface -> "BILIBILI_NON_VIDEO_SURFACE"
                unsafePostOrMessageSurface -> "BILIBILI_MESSAGE_OR_DYNAMIC_SURFACE"
                searchSurfaceVisible -> "BILIBILI_SEARCH_SURFACE"
                !videoSpecificEvidence -> "BILIBILI_VIDEO_EVIDENCE_MISSING"
                else -> "NONE"
            },
            nextActionHint = when {
                shareSheetVisible -> "返回视频列表或详情页，不要点分享面板里的项。"
                nonVideoDetailSurface -> "回到视频结果页，重新点开第一个有播放/弹幕/时长证据的视频。"
                unsafePostOrMessageSurface -> "先退回到搜索结果页，再重新选择视频候选。"
                searchSurfaceVisible -> "先退出搜索结果页的输入框和 tabs，再点第一个视频候选。"
                !videoSpecificEvidence -> "继续从视频列表里找带播放/弹幕/时长证据的卡片，而不是作者卡或普通内容页。"
                else -> ""
            },
            evidence = evidence
                .put("expected", "bilibili_first_video_detail_opened")
                .put("searchInputPresent", searchInputPresent)
                .put("resultTabsVisible", resultTabsVisible)
                .put("searchSurfaceVisible", searchSurfaceVisible)
                .put("detailMarkerHits", JSONArray(markerHits))
                .put("playerMarkerHits", JSONArray(playerMarkerHits))
                .put("videoResourceHits", JSONArray(videoResourceHits.take(20)))
                .put("nonVideoResourceHits", JSONArray(nonVideoResourceHits.take(20)))
                .put("nonVideoTextHits", JSONArray(nonVideoTextHits))
                .put("nonVideoDetailSurface", nonVideoDetailSurface)
                .put("videoSpecificEvidence", videoSpecificEvidence)
                .put("blockerHits", JSONArray(blockerHits))
                .put("shareSheetVisible", shareSheetVisible)
                .put("unsafePostOrMessageSurface", unsafePostOrMessageSurface)
                .put("detailEvidenceStrong", detailEvidenceStrong)
        )
    }

    private fun verifyDouyinVideoPublished(
        instruction: String,
        state: com.macropilot.app.model.UiStateSample,
        visible: List<String>,
        evidence: JSONObject
    ): GoalCheck {
        val textNeedle = extractDouyinVideoText(instruction).take(24)
        val inDouyin = isDouyinPackage(state.appPackage)
        val publishMarkerHits = listOf(
            "\u4f5c\u54c1\u53d1\u5e03\u6210\u529f",
            "\u5df2\u53d1\u5e03",
            "\u5ba1\u6838\u4e2d",
            "\u4e0a\u4f20\u6210\u529f",
            "\u53d1\u5e03\u4e2d",
            "\u6b63\u5728\u53d1\u5e03",
            "\u6295\u7a3f\u6210\u529f"
        ).filter { marker -> visible.any { item -> item.contains(marker) } }
        val blockerHits = listOf(
            "\u767b\u5f55",
            "\u624b\u673a\u53f7",
            "\u5b9e\u540d\u8ba4\u8bc1",
            "\u6743\u9650",
            "\u76f8\u518c\u6743\u9650",
            "\u53d1\u5e03\u5931\u8d25",
            "\u4e0a\u4f20\u5931\u8d25",
            "\u7f51\u7edc\u5f02\u5e38",
            "\u98ce\u9669",
            "\u8bbe\u5907\u5f02\u5e38",
            "\u64cd\u4f5c\u9891\u7e41",
            "\u6682\u65f6\u65e0\u6cd5"
        ).filter { marker -> visible.any { item -> item.contains(marker) } }
        val editorMarkerHits = listOf(
            "\u4e0b\u4e00\u6b65",
            "\u6dfb\u52a0\u4f5c\u54c1\u63cf\u8ff0",
            "\u4f5c\u54c1\u63cf\u8ff0",
            "\u8bf4\u70b9\u4ec0\u4e48",
            "\u8c01\u53ef\u4ee5\u770b",
            "\u4fdd\u5b58\u8349\u7a3f",
            "\u53d1\u4f5c\u54c1",
            "\u6dfb\u52a0\u6807\u7b7e",
            "\u4f60\u5728\u54ea\u91cc",
            "\u516c\u5f00",
            "\u6240\u6709\u4eba\u53ef\u89c1",
            "\u53d1\u5e03\u6210\u529f\u540e",
            "\u4fdd\u5b58\u5185\u5bb9\u81f3\u672c\u5730",
            "\u9650\u65f6\u65e5\u5e38"
        ).filter { marker -> visible.any { item -> item.contains(marker) } }
        val publishMarkerEvidence = visible.filter { item ->
            val editorHint = item.contains("\u53d1\u5e03\u6210\u529f\u540e") ||
                item.contains("\u4fdd\u5b58\u5185\u5bb9\u81f3\u672c\u5730")
            !editorHint && publishMarkerHits.any { marker -> item.contains(marker) }
        }
        val stillPublishEditorVisible = editorMarkerHits.isNotEmpty()
        val contentVisible = textNeedle.isNotBlank() && visible.any { item -> item.contains(textNeedle) }
        val published = inDouyin &&
            blockerHits.isEmpty() &&
            publishMarkerEvidence.isNotEmpty() &&
            !stillPublishEditorVisible
        val blockerType = when {
            blockerHits.isNotEmpty() -> "DOUYIN_PUBLISH_BLOCKED"
            stillPublishEditorVisible -> "DOUYIN_EDITOR_VISIBLE"
            publishMarkerEvidence.isEmpty() -> "DOUYIN_PUBLISH_EVIDENCE_MISSING"
            else -> "NONE"
        }
        val nextActionHint = when {
            blockerHits.isNotEmpty() -> "先处理登录/权限/实名/网络提示，再重新读取发布结果页。"
            stillPublishEditorVisible -> "继续推进发布流程到平台反馈页，不要把编辑页当成功。"
            publishMarkerEvidence.isEmpty() -> "找到发布完成/发布中/审核中证据后再判定成功。"
            else -> ""
        }
        return GoalCheck(
            mandatory = true,
            passed = published,
            message = when {
                blockerHits.isNotEmpty() ->
                    "抖音返回登录/权限/实名/发布失败等阻断证据，不能报告成功。"
                published ->
                    "已看到抖音发布完成或发布中/审核中的平台反馈。"
                stillPublishEditorVisible ->
                    "当前仍像抖音发布编辑页，没看到发布完成/发布中/审核中反馈，不能报告成功。"
                else ->
                    "没有看到抖音发布成功、发布中、审核中等明确证据，不能把发视频任务报告为成功。"
            },
            blockerType = blockerType,
            nextActionHint = nextActionHint,
            evidence = evidence
                .put("expected", "douyin_video_published")
                .put("textNeedle", textNeedle)
                .put("contentVisible", contentVisible)
                .put("publishMarkerHits", JSONArray(publishMarkerHits))
                .put("publishMarkerEvidence", JSONArray(publishMarkerEvidence.take(8)))
                .put("blockerHits", JSONArray(blockerHits))
                .put("editorMarkerHits", JSONArray(editorMarkerHits))
                .put("stillPublishEditorVisible", stillPublishEditorVisible)
                .put("inDouyin", inDouyin)
        )
    }

    private fun verifyWechatMomentPrivatePosted(
        instruction: String,
        state: com.macropilot.app.model.UiStateSample,
        visible: List<String>,
        evidence: JSONObject
    ): GoalCheck {
        val content = extractWechatMomentContent(instruction).take(40)
        val inWechat = state.appPackage == SkillJsonRuleGenerator.WECHAT_PACKAGE
        val contentVisible = content.isNotBlank() && visible.any { item -> item.contains(content) }
        val privateMarkerHits = listOf(
            "\u79c1\u5bc6",
            "\u4ec5\u81ea\u5df1\u53ef\u89c1",
            "\u4ec5\u81ea\u5df1",
            "\u53ea\u6709\u81ea\u5df1\u53ef\u89c1"
        ).filter { marker -> visible.any { item -> item.contains(marker) } }
        val editorMarkerHits = listOf(
            "\u53d1\u8868",
            "\u8c01\u53ef\u4ee5\u770b",
            "\u63d0\u9192\u8c01\u770b",
            "\u6240\u5728\u4f4d\u7f6e",
            "\u8fd9\u4e00\u523b\u7684\u60f3\u6cd5"
        ).filter { marker -> visible.any { item -> item.contains(marker) } } +
            if (state.nodes.any { it.editable }) listOf("editable_node") else emptyList()
        val timelineMarkers = listOf(
            "\u670b\u53cb\u5708",
            "\u8bc4\u8bba",
            "\u70b9\u8d5e",
            "\u53d1\u73b0"
        ).filter { marker -> visible.any { item -> item.contains(marker) } }
        val posted = inWechat &&
            contentVisible &&
            editorMarkerHits.isEmpty() &&
            (privateMarkerHits.isNotEmpty() || timelineMarkers.isNotEmpty())
        val blockerType = when {
            !contentVisible -> "WECHAT_MOMENT_CONTENT_MISSING"
            editorMarkerHits.isNotEmpty() -> "WECHAT_MOMENT_EDITOR_VISIBLE"
            privateMarkerHits.isEmpty() && timelineMarkers.isEmpty() -> "WECHAT_MOMENT_POST_FEEDBACK_MISSING"
            else -> "NONE"
        }
        val nextActionHint = when {
            !contentVisible -> "回到朋友圈发表页或时间线，确认文案是否已经进入页面。"
            editorMarkerHits.isNotEmpty() -> "继续推进到发表后时间线/内容页反馈，不要把编辑页当成功。"
            privateMarkerHits.isEmpty() && timelineMarkers.isEmpty() -> "读取发表后的朋友圈/时间线反馈，确认可见范围和内容是否出现。"
            else -> ""
        }
        return GoalCheck(
            mandatory = true,
            passed = posted,
            message = when {
                posted -> "\u5df2\u770b\u5230\u5fae\u4fe1\u670b\u53cb\u5708\u53d1\u5e03\u540e\u7684\u5185\u5bb9\u8bc1\u636e\uff0c\u4e14\u5df2\u79bb\u5f00\u53d1\u8868\u7f16\u8f91\u9875\u3002"
                !contentVisible -> "\u6ca1\u6709\u5728\u5fae\u4fe1\u5f53\u524d\u9875\u770b\u5230\u670b\u53cb\u5708\u5185\u5bb9\uff0c\u4e0d\u80fd\u62a5\u544a\u53d1\u5e03\u6210\u529f\u3002"
                editorMarkerHits.isNotEmpty() -> "\u5f53\u524d\u4ecd\u50cf\u670b\u53cb\u5708\u53d1\u8868\u7f16\u8f91\u9875\uff0c\u4e0d\u80fd\u62a5\u544a\u5df2\u53d1\u5e03\u3002"
                else -> "\u672a\u770b\u5230\u670b\u53cb\u5708\u53d1\u5e03\u540e\u9875\u9762\u8bc1\u636e\uff0c\u4e0d\u80fd\u8bef\u62a5\u6210\u529f\u3002"
            },
            blockerType = blockerType,
            nextActionHint = nextActionHint,
            evidence = evidence
                .put("expected", "wechat_moment_private_posted")
                .put("contentNeedle", content)
                .put("contentVisible", contentVisible)
                .put("privateMarkerHits", JSONArray(privateMarkerHits))
                .put("editorMarkerHits", JSONArray(editorMarkerHits))
                .put("timelineMarkers", JSONArray(timelineMarkers))
                .put("inWechat", inWechat)
        )
    }

    private fun verifyXiaohongshuDraftWritten(
        instruction: String,
        state: com.macropilot.app.model.UiStateSample,
        visible: List<String>,
        evidence: JSONObject
    ): GoalCheck {
        val body = extractXiaohongshuBody(instruction)
        val title = extractXiaohongshuTitle(instruction, body)
        val bodyNeedle = body.take(24)
        val titleNeedle = title.take(18)
        val bodyVisible = bodyNeedle.isNotBlank() && visible.any { item ->
            item.contains(bodyNeedle) || bodyNeedle.contains(item.take(12))
        }
        val titleVisible = titleNeedle.isNotBlank() && visible.any { item ->
            item.contains(titleNeedle) || titleNeedle.contains(item.take(10))
        }
        val editorMarkers = listOf("标题", "正文", "添加正文", "写想法", "说点什么", "发布笔记", "下一步", "保存草稿", "谁可以看")
        val markerHits = editorMarkers.filter { marker ->
            visible.any { item -> item.contains(marker) }
        }
        val editableCount = state.nodes.count { it.editable }
        val inXhs = state.appPackage == SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE
        val draftEvidenceStrong = inXhs && (bodyVisible || titleVisible) && (markerHits.isNotEmpty() || editableCount > 0)
        val blockerType = when {
            !inXhs -> "XHS_WRONG_APP"
            !bodyVisible && !titleVisible -> "XHS_DRAFT_TEXT_MISSING"
            markerHits.isEmpty() && editableCount <= 0 -> "XHS_EDITOR_EVIDENCE_MISSING"
            else -> "NONE"
        }
        val nextActionHint = when {
            !inXhs -> "先回到小红书应用，再检查发布草稿页。"
            !bodyVisible && !titleVisible -> "继续在草稿编辑页写入标题或正文，再重新取证。"
            markerHits.isEmpty() && editableCount <= 0 -> "先找到发布/草稿编辑页的小字和输入框证据。"
            else -> ""
        }
        return GoalCheck(
            mandatory = true,
            passed = draftEvidenceStrong,
            message = if (draftEvidenceStrong) {
                "已看到小红书草稿页和标题/正文文本证据，草稿已写入；未点击最终发布。"
            } else {
                "没有看到小红书草稿文本或编辑页证据，不能把发小红书任务报告为成功。"
            },
            blockerType = blockerType,
            nextActionHint = nextActionHint,
            evidence = evidence
                .put("expected", "xiaohongshu_draft_written")
                .put("titleNeedle", titleNeedle)
                .put("bodyNeedle", bodyNeedle)
                .put("titleVisible", titleVisible)
                .put("bodyVisible", bodyVisible)
                .put("editorMarkerHits", JSONArray(markerHits))
                .put("editableCount", editableCount)
                .put("inXiaohongshu", inXhs)
                .put("finalPublishClicked", false)
        )
    }

    private fun verifyXiaohongshuPostPublished(
        instruction: String,
        state: com.macropilot.app.model.UiStateSample,
        visible: List<String>,
        evidence: JSONObject
    ): GoalCheck {
        val body = extractXiaohongshuBody(instruction)
        val title = extractXiaohongshuTitle(instruction, body)
        val bodyNeedle = body.take(32)
        val titleNeedle = title.take(24)
        fun normalized(value: String): String = value.trim().lowercase().replace("\\s+".toRegex(), "")
        fun textVisibleStrict(needle: String): Boolean {
            val normalizedNeedle = normalized(needle)
            if (normalizedNeedle.isBlank()) return false
            return visible.any { item ->
                val normalizedItem = normalized(item)
                when {
                    normalizedItem.isBlank() -> false
                    normalizedNeedle.length <= 5 -> normalizedItem == normalizedNeedle
                    else -> normalizedItem.contains(normalizedNeedle) ||
                        (normalizedItem.length >= 8 && normalizedNeedle.contains(normalizedItem))
                }
            }
        }
        val bodyVisible = textVisibleStrict(bodyNeedle)
        val titleVisible = textVisibleStrict(titleNeedle)
        val securityBlockerHits = listOf(
            "\u5b89\u5168\u9650\u5236",
            "\u8bbe\u5907\u5f02\u5e38",
            "\u98ce\u9669\u63d2\u4ef6",
            "\u5173\u95ed/\u5378\u8f7d",
            "\u5c1d\u8bd5\u5173\u95ed",
            "\u91cd\u542f\u8bd5\u8bd5"
        ).filter { marker -> visible.any { item -> item.contains(marker) } }
        val publishMarkerHits = listOf(
            "\u53d1\u5e03\u6210\u529f",
            "\u5df2\u53d1\u5e03",
            "\u5ba1\u6838\u4e2d",
            "\u4e0a\u4f20\u6210\u529f",
            "\u4e0a\u4f20\u4e2d",
            "\u53d1\u5e03\u4e2d"
        ).filter { marker -> visible.any { item -> item.contains(marker) } }
        val detailMarkerHits = listOf(
            "\u70b9\u8d5e",
            "\u8d5e",
            "\u8bc4\u8bba",
            "\u6536\u85cf",
            "\u5206\u4eab",
            "\u5173\u6ce8"
        ).filter { marker -> visible.any { item -> item.contains(marker) } }
        val editorMarkers = listOf(
            "\u5199\u60f3\u6cd5",
            "\u8bf4\u70b9\u4ec0\u4e48",
            "\u4e0b\u4e00\u6b65",
            "\u5b58\u8349\u7a3f",
            "\u4e0d\u4fdd\u5b58\u8fd4\u56de",
            "\u53d1\u5e03\u7b14\u8bb0",
            "\u6dfb\u52a0\u6807\u9898",
            "\u5c55\u5f00\u8bf4\u8bf4"
        )
        val editorMarkerHits = editorMarkers.filter { marker ->
            visible.any { item -> item.contains(marker) }
        }
        val feedSurfaceMarkers = listOf(
            "\u5df2\u9009\u5b9a\u63a8\u8350",
            "\u63a8\u8350",
            "\u89c6\u9891",
            "\u76f4\u64ad",
            "\u77ed\u5267",
            "\u7a7f\u642d"
        ).filter { marker -> visible.any { item -> item == marker || item.contains(marker) } }
        val inXhs = state.appPackage == SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE
        val contentVisible = bodyVisible || titleVisible
        val shortContent = listOf(bodyNeedle, titleNeedle)
            .map(::normalized)
            .any { it.isNotBlank() && it.length <= 5 }
        val editorStillVisible = editorMarkerHits.isNotEmpty() || state.nodes.any { it.editable }
        val feedSurfaceVisible = feedSurfaceMarkers.isNotEmpty()
        val detailEvidenceStrong = detailMarkerHits.size >= 3 && !feedSurfaceVisible && !editorStillVisible
        val publishedEvidenceStrong = inXhs &&
            securityBlockerHits.isEmpty() &&
            (
                publishMarkerHits.isNotEmpty() ||
                    (!shortContent && contentVisible && detailEvidenceStrong)
            )
        return GoalCheck(
            mandatory = true,
            passed = publishedEvidenceStrong,
            message = when {
                securityBlockerHits.isNotEmpty() ->
                    "\u5c0f\u7ea2\u4e66\u8fd4\u56de\u5b89\u5168\u9650\u5236/\u8bbe\u5907\u5f02\u5e38\uff0c\u53d1\u5e03\u88ab\u5e73\u53f0\u963b\u6b62\uff0c\u4e0d\u80fd\u62a5\u544a\u6210\u529f\u3002"
                publishedEvidenceStrong ->
                    "\u5df2\u770b\u5230\u5c0f\u7ea2\u4e66\u53d1\u5e03\u5b8c\u6210\u8bc1\u636e\uff1a\u53d1\u5e03/\u5ba1\u6838\u6807\u8bb0\u6216\u5df2\u53d1\u5e03\u5185\u5bb9\u9875\u5f3a\u8bc1\u636e\u3002"
                shortContent ->
                    "\u672a\u770b\u5230\u5c0f\u7ea2\u4e66\u53d1\u5e03\u6210\u529f/\u5ba1\u6838\u4e2d\u7b49\u660e\u786e\u5e73\u53f0\u53cd\u9988\uff0c\u77ed\u6587\u672c\u4e0d\u5141\u8bb8\u7528\u9996\u9875\u4fe1\u606f\u6d41\u8bef\u5224\u6210\u5df2\u53d1\u5e03\u3002"
                else ->
                    "\u6ca1\u6709\u770b\u5230\u5c0f\u7ea2\u4e66\u53d1\u5e03\u5b8c\u6210\u8bc1\u636e\uff0c\u4e0d\u80fd\u628a\u53d1\u5e03\u4efb\u52a1\u62a5\u544a\u4e3a\u6210\u529f\u3002"
            },
            evidence = evidence
                .put("expected", "xiaohongshu_post_published")
                .put("titleNeedle", titleNeedle)
                .put("bodyNeedle", bodyNeedle)
                .put("titleVisible", titleVisible)
                .put("bodyVisible", bodyVisible)
                .put("shortContentRequiresPublishMarker", shortContent)
                .put("securityBlockerHits", JSONArray(securityBlockerHits))
                .put("publishMarkerHits", JSONArray(publishMarkerHits))
                .put("detailMarkerHits", JSONArray(detailMarkerHits))
                .put("detailEvidenceStrong", detailEvidenceStrong)
                .put("editorMarkerHits", JSONArray(editorMarkerHits))
                .put("editorStillVisible", editorStillVisible)
                .put("feedSurfaceMarkers", JSONArray(feedSurfaceMarkers))
                .put("feedSurfaceVisible", feedSurfaceVisible)
                .put("inXiaohongshu", inXhs)
                .put("finalPublishRequested", true)
        )
    }

    private fun requiresAccessibilityControl(instruction: String): Boolean {
        val text = instruction.trim()
        val pureOpen = (text.contains("打开") || text.contains("启动")) &&
            listOf("搜索", "搜", "点击", "点", "输入", "发", "发送", "评论", "点赞", "收藏", "关注", "第一条", "第一个")
                .none { marker -> text.contains(marker) }
        return !pureOpen
    }

    private fun executionReadiness(appPackage: String, instruction: String): JSONObject {
        val serviceConnected = MacroPilotAccessibilityService.instance != null
        val interactive = actions.isInteractive()
        val keyguardLocked = actions.isKeyguardLocked()
        val state = inspector.currentState()
        val profile = profiler.profile(state, serviceConnected)
        val needsInput = instructionNeedsInput(instruction)
        val editableVisibleNow = state?.nodes?.any { node -> node.enabled && node.editable } == true
        val inputMissingAtCurrentScreen = needsInput &&
            editableVisibleNow &&
            profile.inputCapability == com.macropilot.app.model.InputCapability.NONE &&
            !actions.isMacroPilotImeSelected()
        val reasons = mutableListOf<String>()
        if (!serviceConnected) reasons += "accessibility_disconnected"
        if (!interactive) reasons += "device_not_interactive"
        if (keyguardLocked) reasons += "keyguard_locked"
        if (inputMissingAtCurrentScreen) {
            reasons += "input_capability_none_on_current_editable_screen"
        }
        val ready = reasons.isEmpty()
        return JSONObject()
            .put("ready", ready)
            .put("status", if (ready) "READY" else "FAILED_DEVICE_NOT_READY")
            .put(
                "message",
                if (ready) {
                    "设备可执行：无障碍、屏幕交互和输入通道均可用。"
                } else {
                    "设备当前不能执行复杂任务：${reasons.joinToString(",")}。本次不会继续空转或假报成功。"
                }
            )
            .put("serviceConnected", serviceConnected)
            .put("interactive", interactive)
            .put("keyguardLocked", keyguardLocked)
            .put("currentPackage", state?.appPackage ?: JSONObject.NULL)
            .put("expectedPackage", appPackage.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("nodeCount", state?.nodes?.size ?: 0)
            .put("clickableCount", state?.nodes?.count { it.clickable } ?: 0)
            .put("editableCount", state?.nodes?.count { it.editable } ?: 0)
            .put("nodeReadability", profile.nodeReadability.name)
            .put("inputCapability", profile.inputCapability.name)
            .put("inputRequiredForInstruction", needsInput)
            .put("editableVisibleNow", editableVisibleNow)
            .put("inputCheckPolicy", "defer_input_channel_check_until_input_atom")
            .put("verificationCapability", profile.verificationCapability.name)
            .put("reasons", JSONArray(reasons))
    }

    private fun instructionNeedsInput(instruction: String): Boolean {
        val text = instruction.lowercase(Locale.getDefault())
        val inputMarkers = listOf(
            "搜索",
            "输入",
            "写",
            "草稿",
            "评论框",
            "评论",
            "发帖",
            "发布",
            "发送",
            "回复",
            "填写",
            "设置目的地",
            "query",
            "search",
            "type",
            "draft",
            "comment",
            "post"
        )
        val nonInputProbeMarkers = listOf(
            "读取",
            "识别",
            "记录",
            "打开",
            "进入",
            "依次打开",
            "底部",
            "顶部",
            "图形化入口",
            "子页面",
            "设置页",
            "不发表评论",
            "不发送",
            "不点击",
            "只读取"
        )
        val hasInput = inputMarkers.any { text.contains(it) }
        val explicitlyProbeOnly = nonInputProbeMarkers.any { text.contains(it) } &&
            listOf("不要发送", "不发送", "不要点击最终", "不发表评论", "不下单", "不支付", "只读取").any { text.contains(it) }
        return hasInput && !explicitlyProbeOnly
    }

    private fun shouldRunDouyinPublishButtonFallback(instruction: String, appPackage: String): Boolean {
        if (!isDouyinVideoPublishInstruction(instruction, appPackage)) return false
        val state = inspector.currentState() ?: return false
        if (!isDouyinPackage(state.appPackage)) return false
        val visible = visibleLabels(state)
        val editorMarkers = listOf(
            "\u53d1\u4f5c\u54c1",
            "\u53d1\u5e03\u4f5c\u54c1",
            "\u6dfb\u52a0\u4f5c\u54c1\u63cf\u8ff0",
            "\u4f5c\u54c1\u63cf\u8ff0",
            "\u53d1\u5e03\u6210\u529f\u540e",
            "\u4fdd\u5b58\u5185\u5bb9\u81f3\u672c\u5730",
            "\u6240\u6709\u4eba\u53ef\u89c1",
            "\u6dfb\u52a0\u6807\u7b7e",
            "\u4f60\u5728\u54ea\u91cc",
            "\u9650\u65f6\u65e5\u5e38"
        )
        return editorMarkers.any { marker -> visible.any { item -> item.contains(marker) } }
    }

    private fun runDouyinPublishButtonFallback(
        instruction: String,
        appPackage: String,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): File? {
        val state = inspector.currentState()
        val visible = visibleLabels(state)
        val text = extractDouyinVideoText(instruction).ifBlank { "\u6211\u662fAI" }
        val contentVisible = text.isNotBlank() && visible.any { item -> item.contains(text.take(24)) }
        val editorEvidence = JSONObject()
            .put("text", text)
            .put("contentVisible", contentVisible)
            .put("currentUi", currentUiSummary())

        step(
            "\u68c0\u6d4b\u5230\u6296\u97f3\u53d1\u5e03\u9875\uff0c\u542f\u52a8\u53d1\u4f5c\u54c1\u8865\u6551",
            "RUNNING",
            "\u5f53\u524d\u8fd8\u5728\u6296\u97f3\u53d1\u5e03\u7f16\u8f91\u9875\uff0c\u4e0d\u5141\u8bb8\u8bef\u62a5\u6210\u529f\uff1b\u73b0\u5728\u628a\u70b9\u51fb\u201c\u53d1\u4f5c\u54c1\u201d\u5199\u6210\u7ec6\u7c92\u5ea6 JSON \u5e76\u91cd\u8dd1\u3002",
            null,
            editorEvidence
        )

        val steps = mutableListOf<JSONObject>()
        steps += JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", appPackage).put("maxPasses", 2)
        if (!contentVisible) {
            steps += JSONObject()
                .put("type", "TypeAtCoordinate")
                .put("x", 0.32)
                .put("y", 0.43)
                .put("text", text)
                .put("replace", false)
                .put("focusDelayMs", 700L)
                .put("timeoutMs", 2_600L)
            steps += JSONObject().put("type", "Wait").put("timeoutMs", 700L)
        }
        steps += JSONObject()
            .put("type", "ClickVisibleText")
            .put("text", "\u53d1\u4f5c\u54c1")
            .put("textAliases", JSONArray(listOf(
                "\u53d1\u4f5c\u54c1",
                "\u53d1\u5e03\u4f5c\u54c1",
                "\u53d1\u5e03",
                "\u53d1\u65e5\u5e38",
                "\u53d1\u5e03\u65e5\u5e38"
            )))
            .put("x", 0.78)
            .put("y", 0.93)
            .put("requiresUiStateForCoordinate", false)
            .put("timeoutMs", 1_800L)
            .put("userConfirmedSubmitAction", true)
            .put("submitReason", "\u7528\u6237\u660e\u786e\u8981\u6c42\u5728\u6296\u97f3\u53d1\u89c6\u9891")
        steps += JSONObject().put("type", "Wait").put("timeoutMs", 12_000L)

        val built = buildDirectPlanSkills(instruction, appPackage, steps)
        step(
            "\u6296\u97f3\u53d1\u4f5c\u54c1\u8865\u6551 Skill JSON \u5df2\u5199\u5165",
            "SUCCESS",
            "\u5df2\u751f\u6210 ${built.skillFiles.size} \u4e2a\u7ec6\u7c92\u5ea6 candidate JSON\uff0c\u5305\u542b\u53ef\u89c1\u6587\u5b57\u70b9\u51fb\u548c\u5750\u6807\u4f4e\u7f6e\u4fe1\u8865\u6551\u3002",
            built.macroFile.absolutePath,
            editorEvidence
        )
        val dry = dryRunFiles(appPackage, built.skillFiles)
        step(
            "\u6296\u97f3\u53d1\u4f5c\u54c1\u8865\u6551 dry-run",
            if (dry.passed == dry.total) "SUCCESS" else "FAILED_DRY_RUN",
            "\u901a\u8fc7 ${dry.passed}/${dry.total}\u3002",
            dry.lastReportPath,
            null
        )

        val macro = JSONObject(built.macroFile.readText())
        val startedAt = System.currentTimeMillis()
        val result = SkillJsonExecutor(context, runtimeApprovedOnly = false)
            .execute(macro, JSONObject(), allowSideEffect = true)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_douyin_publish_button_fallback_report")
            .put("id", "douyin_publish_button_fallback_${System.currentTimeMillis()}")
            .put("skillId", macro.optString("id"))
            .put("appPackage", appPackage)
            .put("instruction", instruction)
            .put("engine", "douyin_publish_editor_local_fallback")
            .put("approvedRequired", false)
            .put("status", result.status)
            .put("message", result.message)
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("usedCoordinate", result.usedCoordinate)
            .put("allowSideEffect", true)
            .put("skillFile", built.macroFile.absolutePath)
            .put("createdAt", System.currentTimeMillis())
        val reportFile = store.saveRuntimeRunReport(appPackage, report)
        step(
            "\u6296\u97f3\u53d1\u4f5c\u54c1\u8865\u6551\u6267\u884c\u7ed3\u679c",
            if (result.status.startsWith("FAILED")) result.status else "SUCCESS",
            "${macro.optString("id")} -> ${result.status}; ${result.message.take(180)}\uff1b\u968f\u540e\u4ecd\u4f1a\u7528\u5e73\u53f0\u53d1\u5e03\u53cd\u9988\u91cd\u65b0\u6821\u9a8c\uff0c\u4e0d\u8fbe\u6807\u5c31\u4e0d\u7b97\u6210\u529f\u3002",
            reportFile.absolutePath,
            editorEvidence
        )
        return reportFile
    }

    private fun visibleLabels(state: com.macropilot.app.model.UiStateSample?): List<String> {
        if (state == null) return emptyList()
        return state.nodes.mapNotNull { node ->
            listOfNotNull(node.text, node.contentDesc, node.resourceId)
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        }
    }

    private fun shouldRunBilibiliOpenFirstFallback(instruction: String, appPackage: String): Boolean {
        return appPackage == SkillJsonRuleGenerator.BILIBILI_PACKAGE &&
            isOpenFirstContentInstruction(instruction) &&
            instruction.contains("视频")
    }

    private fun isOpenFirstContentInstruction(instruction: String): Boolean {
        return listOf("打开第一条", "打开第一个", "第一条笔记", "第一个结果", "第一条视频", "第一个视频")
            .any { marker -> instruction.contains(marker) }
    }

    private fun runBilibiliOpenFirstVideoFallback(
        instruction: String,
        appPackage: String,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): File? {
        val query = extractSearchQuery(instruction)
        val initialState = inspector.currentState()
        if (initialState == null || !isBilibiliSearchResultSurface(initialState)) {
            step(
                "B 站不在搜索结果页，先恢复搜索",
                "RUNNING",
                "当前页面不是可信搜索结果页；先执行 B 站搜索 Skill 子集（含 RecoverBilibiliHomeSurface），再选择第一个视频候选。",
                null,
                JSONObject()
                    .put("query", query)
                    .put("currentUi", currentUiSummary())
            )
            runBilibiliRuleFallback(instruction, appPackage, step)
            sleep(900L)
        }
        clickBilibiliVideoTabIfPresent(step)
        val target = chooseFirstBilibiliVideoTarget(query)
        if (target == null) {
            step(
                "本地候选补救停止",
                "FAILED_ELEMENT_NOT_FOUND",
                "当前屏幕没有找到可信的第一个视频候选。不会乱点，也不会把搜索页误报为成功。",
                null,
                JSONObject()
                    .put("query", query)
                    .put("currentUi", currentUiSummary())
            )
            return null
        }

        val steps = listOf(
            JSONObject()
                .put("type", "ClickCoordinate")
                .put("x", target.x)
                .put("y", target.y)
                .put("skipReactiveOverlays", true)
                .put("timeoutMs", 1200L),
            JSONObject().put("type", "Wait").put("timeoutMs", 3200L).put("skipReactiveOverlays", true)
        )
        val built = buildDirectPlanSkills(instruction, appPackage, steps)
        step(
            "本地候选补救转 Skill JSON",
            "SUCCESS",
            "选择第 ${target.index + 1} 个视频候选并写成 ClickCoordinate 原子 Skill；坐标=(${String.format(Locale.US, "%.3f", target.x)}, ${String.format(Locale.US, "%.3f", target.y)})。",
            built.macroFile.absolutePath,
            target.evidence
        )
        val dry = dryRunFiles(appPackage, built.skillFiles)
        step("本地候选补救 dry-run", if (dry.passed == dry.total) "SUCCESS" else "FAILED_DRY_RUN", "通过 ${dry.passed}/${dry.total}。", dry.lastReportPath, null)

        val macro = JSONObject(built.macroFile.readText())
        val startedAt = System.currentTimeMillis()
        val result = SkillJsonExecutor(context, runtimeApprovedOnly = false)
            .execute(macro, JSONObject(), allowSideEffect = true)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_bilibili_open_first_video_fallback_report")
            .put("id", "bilibili_open_first_video_${System.currentTimeMillis()}")
            .put("skillId", macro.optString("id"))
            .put("appPackage", appPackage)
            .put("instruction", instruction)
            .put("engine", "local_video_candidate_resolver")
            .put("status", result.status)
            .put("message", result.message)
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("usedCoordinate", result.usedCoordinate)
            .put("allowSideEffect", true)
            .put("selectedCandidate", target.evidence)
            .put("skillFile", built.macroFile.absolutePath)
            .put("createdAt", System.currentTimeMillis())
        val reportFile = store.saveRuntimeRunReport(appPackage, report)
        step(
            "本地打开第一个视频结果",
            if (result.status.startsWith("FAILED")) result.status else "SUCCESS",
            "${macro.optString("id")} -> ${result.status}；${result.message}；随后仍会用详情页证据重新校验，校验不过就失败。",
            reportFile.absolutePath,
            target.evidence
        )
        return reportFile
    }

    private fun clickBilibiliVideoTabIfPresent(
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): Boolean {
        val state = inspector.currentState() ?: return false
        if (state.appPackage != SkillJsonRuleGenerator.BILIBILI_PACKAGE) return false
        if (!isBilibiliSearchResultSurface(state)) return false
        val tab = state.nodes
            .filter { node ->
                node.enabled &&
                    (node.text == "视频" || node.contentDesc == "视频") &&
                    node.bounds.centerY() in 0..(state.screenHeight * 0.28f).toInt()
            }
            .minByOrNull { it.bounds.centerY() }
            ?: return false
        val point = NormalizedPoint(
            x = (tab.bounds.centerX().toFloat() / state.screenWidth.coerceAtLeast(1)).coerceIn(0f, 1f),
            y = (tab.bounds.centerY().toFloat() / state.screenHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
        )
        val outcome = actions.tapNormalized(point, state.screenWidth, state.screenHeight)
        sleep(1200L)
        step(
            "B 站切到视频结果分栏",
            if (outcome.success) "SUCCESS" else "FAILED_ELEMENT_NOT_FOUND",
            "用户要求打开第一个视频，先强制进入“视频”结果分栏，避免综合页误点 UP 主/动态/专栏。",
            null,
            JSONObject()
                .put("tabBounds", tab.bounds.flattenToString())
                .put("tap", JSONObject().put("x", point.x).put("y", point.y))
                .put("reason", outcome.reason)
        )
        return outcome.success
    }

    private fun chooseFirstBilibiliVideoTarget(query: String): BilibiliVideoTarget? {
        val state = inspector.currentState() ?: return null
        if (state.appPackage != SkillJsonRuleGenerator.BILIBILI_PACKAGE) return null
        if (!isBilibiliSearchResultSurface(state)) return null
        val screenWidth = state.screenWidth.coerceAtLeast(1)
        val screenHeight = state.screenHeight.coerceAtLeast(1)
        val visibleLabels = state.nodes.map { node ->
            listOfNotNull(node.text, node.contentDesc, node.resourceId)
                .joinToString(" ")
                .trim()
        }.filter { it.isNotBlank() }
        val queryTokens = query
            .split(" ", "　", "和", ",", "，")
            .map { it.trim() }
            .filter { it.length >= 2 }
        val pageHasQueryEvidence = query.isNotBlank() && visibleLabels.any { label ->
            label.contains(query, ignoreCase = true) ||
                queryTokens.any { token -> label.contains(token, ignoreCase = true) }
        }
        val pageHasSearchResultVideoSet = visibleLabels.any { label ->
            label.contains("video_layout", ignoreCase = true) ||
                label.contains("more_video", ignoreCase = true) ||
                label.contains("个视频")
        }
        val durationRegex = Regex("\\b\\d{1,2}:\\d{2}\\b")
        val rows = state.nodes
            .filter { node ->
                node.enabled &&
                    node.bounds.centerY().toDouble() / screenHeight in 0.20..0.86 &&
                    node.bounds.height() > 24
            }
            .mapNotNull { row ->
                val rowLabel = listOfNotNull(row.text, row.contentDesc, row.resourceId)
                    .joinToString(" ")
                    .trim()
                val rowLower = rowLabel.lowercase(Locale.CHINA)
                if (rowLower.contains("tag_name") ||
                    rowLower.contains("搜索历史") ||
                    rowLower.contains("bilibili热搜") ||
                    rowLower.contains("完整榜单") ||
                    rowLower.contains("搜索发现")
                ) {
                    return@mapNotNull null
                }
                val rowCenterY = row.bounds.centerY()
                val rowCenterX = row.bounds.centerX()
                val nearby = state.nodes.filter { other ->
                    val dy = kotlin.math.abs(other.bounds.centerY() - rowCenterY)
                    val verticallyInside = other.bounds.centerY() in row.bounds.top..row.bounds.bottom
                    (dy < screenHeight * 0.095f || verticallyInside) &&
                        kotlin.math.abs(other.bounds.centerX() - rowCenterX) < screenWidth * 0.55f
                }
                val combined = nearby.joinToString(" ") { node ->
                    listOfNotNull(node.text, node.contentDesc, node.resourceId)
                        .joinToString(" ")
                }.trim()
                val combinedLower = combined.lowercase(Locale.CHINA)
                val rowVideoEvidence = combinedLower.contains("play_num") ||
                    combinedLower.contains("danmakus_num") ||
                    combinedLower.contains(":id/cover") ||
                    combinedLower.contains(":id/title") ||
                    combined.contains("播放") ||
                    combined.contains("弹幕") ||
                    durationRegex.containsMatchIn(combined)
                val rowQueryEvidence = queryTokens.any { token -> combined.contains(token, ignoreCase = true) } ||
                    (query.isNotBlank() && combined.contains(query, ignoreCase = true))
                val titleLike = nearby.any { node ->
                    val text = node.text.orEmpty().ifBlank { node.contentDesc.orEmpty() }
                    val id = node.resourceId.orEmpty()
                    text.length >= 8 &&
                        !id.contains("tag_name", ignoreCase = true) &&
                        !isBilibiliNonVideoSearchControl(text)
                }
                if (!rowVideoEvidence || !rowQueryEvidence || !titleLike) return@mapNotNull null
                val clickNode = nearby
                    .filter { it.clickable && it.bounds.width() > screenWidth * 0.25f && it.bounds.height() > 40 }
                    .maxByOrNull { it.bounds.width() * it.bounds.height() }
                    ?: row
                val x = (clickNode.bounds.centerX().toDouble() / screenWidth).coerceIn(0.08, 0.92)
                val y = (clickNode.bounds.centerY().toDouble() / screenHeight).coerceIn(0.20, 0.84)
                val score = 55 +
                    (if (row.clickable || clickNode.clickable) 10 else 0) +
                    (if (combinedLower.contains(":id/title")) 8 else 0) +
                    (if (durationRegex.containsMatchIn(combined)) 6 else 0)
                BilibiliVideoTarget(
                    index = 0,
                    x = x,
                    y = y,
                    sortY = clickNode.bounds.top,
                    score = score,
                    evidence = JSONObject()
                        .put("resolver", "row_cluster")
                        .put("text", row.text ?: JSONObject.NULL)
                        .put("desc", row.contentDesc ?: JSONObject.NULL)
                        .put("resourceId", row.resourceId ?: JSONObject.NULL)
                        .put("class", row.className ?: JSONObject.NULL)
                        .put("clickable", row.clickable)
                        .put("bounds", row.bounds.flattenToString())
                        .put("clickBounds", clickNode.bounds.flattenToString())
                        .put("center", JSONObject().put("x", x).put("y", y))
                        .put("score", score)
                        .put("hasVideoEvidence", true)
                        .put("hasQueryEvidence", true)
                        .put("pageHasQueryEvidence", pageHasQueryEvidence)
                        .put("pageHasSearchResultVideoSet", pageHasSearchResultVideoSet)
                        .put("combinedEvidence", combined.take(500))
                )
            }
        val candidates = state.nodes.mapNotNull { node ->
            val label = listOfNotNull(node.text, node.contentDesc, node.resourceId)
                .joinToString(" ")
                .trim()
            val normalizedLabel = label.lowercase(Locale.CHINA)
            val rect = node.bounds
            val centerYRatio = rect.centerY().toDouble() / screenHeight
            val centerXRatio = rect.centerX().toDouble() / screenWidth
            if (!node.enabled ||
                node.editable ||
                centerYRatio < 0.20 ||
                centerYRatio > 0.86 ||
                rect.width() < screenWidth * 0.18f ||
                rect.height() < 28 ||
                rect.height() > screenHeight * 0.32f ||
                isBilibiliNonVideoSearchControl(label)
            ) {
                return@mapNotNull null
            }
            val hasVideoEvidence = normalizedLabel.contains("播放") ||
                normalizedLabel.contains("弹幕") ||
                normalizedLabel.contains("观看") ||
                normalizedLabel.contains("video_layout") ||
                normalizedLabel.contains("play_num") ||
                normalizedLabel.contains("duration") ||
                normalizedLabel.contains(":id/cover") ||
                durationRegex.containsMatchIn(label)
            val hasQueryEvidence = queryTokens.any { token -> label.contains(token, ignoreCase = true) } ||
                (query.isNotBlank() && label.contains(query, ignoreCase = true))
            val looksLikeAuthorCard = (label.contains("粉丝") || label.contains("关注")) && !hasVideoEvidence
            if (looksLikeAuthorCard) return@mapNotNull null
            if (normalizedLabel.contains("recycler_view")) return@mapNotNull null
            if (normalizedLabel.contains("video_layout")) return@mapNotNull null
            if (queryTokens.isNotEmpty() &&
                !hasQueryEvidence &&
                !(pageHasQueryEvidence && hasVideoEvidence) &&
                !(pageHasSearchResultVideoSet && hasVideoEvidence)
            ) {
                return@mapNotNull null
            }
            if (normalizedLabel.contains("tag_name")) return@mapNotNull null
            if (!hasVideoEvidence && !hasQueryEvidence) return@mapNotNull null
            if (queryTokens.isNotEmpty() && !hasVideoEvidence) return@mapNotNull null
            if (!hasVideoEvidence && label.length < 10) return@mapNotNull null
            var score = 0
            if (hasVideoEvidence) score += 45
            if (hasQueryEvidence) score += 18
            if (pageHasQueryEvidence && hasVideoEvidence && !hasQueryEvidence) score += 6
            if (pageHasSearchResultVideoSet && hasVideoEvidence && !hasQueryEvidence) score += 5
            if (normalizedLabel.contains(":id/cover")) score += 7
            if (normalizedLabel.contains("video_layout")) score += 6
            if (label.length >= 10) score += 10
            if (node.clickable) score += 8
            if (rect.width() > screenWidth * 0.45f) score += 5
            if (centerYRatio < 0.55) score += 5
            if (score < 18) return@mapNotNull null
            BilibiliVideoTarget(
                index = 0,
                x = centerXRatio.coerceIn(0.04, 0.96),
                y = centerYRatio.coerceIn(0.04, 0.96),
                sortY = rect.top,
                score = score,
                evidence = JSONObject()
                    .put("text", node.text ?: JSONObject.NULL)
                    .put("desc", node.contentDesc ?: JSONObject.NULL)
                    .put("resourceId", node.resourceId ?: JSONObject.NULL)
                    .put("class", node.className ?: JSONObject.NULL)
                    .put("clickable", node.clickable)
                    .put("bounds", rect.flattenToString())
                    .put("center", JSONObject().put("x", centerXRatio).put("y", centerYRatio))
                    .put("score", score)
                    .put("hasVideoEvidence", hasVideoEvidence)
                    .put("hasQueryEvidence", hasQueryEvidence)
                    .put("pageHasQueryEvidence", pageHasQueryEvidence)
                    .put("pageHasSearchResultVideoSet", pageHasSearchResultVideoSet)
            )
        }
            .sortedWith(compareByDescending<BilibiliVideoTarget> { it.score >= 45 }
                .thenBy { it.sortY })
            .mapIndexed { index, target -> target.copy(index = index) }
        return (rows + candidates)
            .sortedWith(compareByDescending<BilibiliVideoTarget> { it.score }
                .thenBy { it.sortY })
            .mapIndexed { index, target -> target.copy(index = index) }
            .firstOrNull()
    }

    private fun isBilibiliSearchResultSurface(state: com.macropilot.app.model.UiStateSample): Boolean {
        val visible = state.nodes.mapNotNull { node ->
            listOfNotNull(node.text, node.contentDesc, node.resourceId)
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        }
        val hasSearchInput = state.nodes.any { node ->
            node.editable ||
                node.resourceId?.contains("search_src_text", ignoreCase = true) == true ||
                node.resourceId?.contains("search_keyword", ignoreCase = true) == true ||
                node.resourceId?.contains("search_input", ignoreCase = true) == true
        }
        val hasResultTabs = visible.any { item ->
            item == "综合" ||
                item == "视频" ||
                item == "用户" ||
                item == "番剧" ||
                item == "直播" ||
                item == "专栏" ||
                item.contains("搜索结果") ||
                item.contains("action_search", ignoreCase = true)
        }
        val unsafeSurface = visible.any { item ->
            listOf("发个消息", "唠会嗑", "转到动态", "发布", "消息设置", "分享", "QQ", "微信", "朋友圈")
                .any { marker -> item.contains(marker, ignoreCase = true) }
        }
        return (hasSearchInput || hasResultTabs) && !unsafeSurface
    }

    private fun isBilibiliNonVideoSearchControl(label: String): Boolean {
        val trimmed = label.trim()
        if (trimmed.isBlank()) return true
        val exactControls = setOf(
            "搜索",
            "返回",
            "取消",
            "清除查询",
            "综合",
            "视频",
            "用户",
            "番剧",
            "直播",
            "专栏",
            "首页",
            "动态",
            "会员购",
            "我的",
            "分享",
            "消息",
            "发布",
            "转到动态",
            "发个消息聊聊呗"
        )
        if (exactControls.contains(trimmed)) return true
        return trimmed.contains("search_src_text", ignoreCase = true) ||
            trimmed.contains("action_search", ignoreCase = true) ||
            trimmed.contains("expand_search", ignoreCase = true) ||
            trimmed.contains("search_keyword", ignoreCase = true) ||
            trimmed.contains(":id/opus", ignoreCase = true) ||
            trimmed.contains(":id/dy_", ignoreCase = true) ||
            trimmed.contains(":id/favor_repost", ignoreCase = true) ||
            trimmed.contains(":id/comment_main", ignoreCase = true) ||
            trimmed.contains(":id/cmt", ignoreCase = true) ||
            trimmed.contains("dynamic", ignoreCase = true) ||
            trimmed.contains("supermenu", ignoreCase = true) ||
            trimmed.contains("返回", ignoreCase = true) ||
            trimmed.contains("清除查询", ignoreCase = true) ||
            trimmed.contains("点我发评论", ignoreCase = true) ||
            trimmed.contains("热门评论", ignoreCase = true) ||
            trimmed.contains("赞和转发", ignoreCase = true) ||
            trimmed.contains("生成长图", ignoreCase = true) ||
            trimmed.contains("复制链接", ignoreCase = true) ||
            trimmed.contains("投币", ignoreCase = true) ||
            trimmed.contains("分享", ignoreCase = true) ||
            trimmed.contains("消息设置", ignoreCase = true) ||
            trimmed.contains("转到动态", ignoreCase = true) ||
            trimmed.contains("发个消息", ignoreCase = true) ||
            trimmed.contains("唠会嗑", ignoreCase = true) ||
            trimmed.contains("发布", ignoreCase = true)
    }

    private fun runBilibiliRuleFallback(
        instruction: String,
        appPackage: String,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): File? {
        val query = extractSearchQuery(instruction).ifBlank { "猫和老鼠" }
        step("AI 计划失败，启动规则兜底", "RUNNING", "AI 返回的动作计划没有点到目标节点；现在改用 B 站细粒度 Skill JSON 子集继续执行。", null, null)
        val files = SkillJsonRuleGenerator().generateBilibiliSearch(query).map { skill ->
            store.saveSkill(skill.getString("appPackage"), skill)
        }
        step("规则兜底 Skill 已写入", "SUCCESS", "写入 ${files.size} 个 atomic/macro Skill JSON。", files.firstOrNull()?.absolutePath, null)
        val dry = dryRunPackage(appPackage)
        step("规则兜底 dry-run", if (dry.passed == dry.total) "SUCCESS" else "FAILED_DRY_RUN", "通过 ${dry.passed}/${dry.total}。", dry.lastReportPath, null)
        val macroPair = store.loadSkillById("bilibili.search.search_in_app")
        if (macroPair == null) {
            step("规则兜底停止", "FAILED_DEPENDENCY_MISSING", "找不到 bilibili.search.search_in_app。", null, null)
            return null
        }
        val (macroFile, macro) = macroPair
        val startedAt = System.currentTimeMillis()
        val result = SkillJsonExecutor(context, runtimeApprovedOnly = false)
            .execute(macro, JSONObject().put("query", query), allowSideEffect = true)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_ai_direct_fallback_run_report")
            .put("id", "ai_direct_fallback_run_${System.currentTimeMillis()}")
            .put("skillId", "bilibili.search.search_in_app")
            .put("appPackage", appPackage)
            .put("instruction", instruction)
            .put("engine", "rule_fallback_skill_json")
            .put("approvedRequired", false)
            .put("status", result.status)
            .put("message", result.message)
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("usedCoordinate", result.usedCoordinate)
            .put("allowSideEffect", true)
            .put("params", JSONObject().put("query", query))
            .put("skillFile", macroFile.absolutePath)
            .put("createdAt", System.currentTimeMillis())
        val reportFile = store.saveRuntimeRunReport(appPackage, report)
        step(
            "规则兜底执行结果",
            if (result.status.startsWith("FAILED")) result.status else "SUCCESS",
            "bilibili.search.search_in_app -> ${result.status}；${result.message}",
            reportFile.absolutePath,
            null
        )
        return if (result.status.startsWith("FAILED")) null else reportFile
    }

    private fun runGenericSearchFallback(
        instruction: String,
        appPackage: String,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): File? {
        val query = extractSearchQuery(instruction).ifBlank { "猫和老鼠" }
        step(
            "通用第三方搜索兜底启动",
            "RUNNING",
            "AI 计划没有命中可点击节点；现在生成通用搜索 Skill JSON：打开应用 -> 点搜索入口 -> 输入关键词 -> 点搜索。坐标只作为低置信兜底。",
            null,
            JSONObject().put("query", query).put("appPackage", appPackage)
        )
        val built = buildDirectPlanSkills(instruction, appPackage, genericThirdPartySearchSteps(instruction, appPackage))
        step(
            "通用搜索 Skill JSON 已写入",
            "SUCCESS",
            "已写入 ${built.skillFiles.size} 个候选 JSON，包含文字优先和坐标兜底。",
            built.macroFile.absolutePath,
            null
        )
        val dry = dryRunPackage(appPackage)
        step(
            "通用搜索 dry-run",
            if (dry.passed == dry.total) "SUCCESS" else "FAILED_DRY_RUN",
            "通过 ${dry.passed}/${dry.total}。",
            dry.lastReportPath,
            null
        )
        val macro = JSONObject(built.macroFile.readText())
        val startedAt = System.currentTimeMillis()
        val result = SkillJsonExecutor(context, runtimeApprovedOnly = false)
            .execute(macro, JSONObject(), allowSideEffect = true)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_generic_search_fallback_run_report")
            .put("id", "generic_search_fallback_${System.currentTimeMillis()}")
            .put("skillId", macro.optString("id"))
            .put("appPackage", appPackage)
            .put("instruction", instruction)
            .put("query", query)
            .put("engine", "generic_search_fallback_skill_json")
            .put("approvedRequired", false)
            .put("status", result.status)
            .put("message", result.message)
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("usedCoordinate", result.usedCoordinate)
            .put("allowSideEffect", true)
            .put("skillFile", built.macroFile.absolutePath)
            .put("createdAt", System.currentTimeMillis())
        val reportFile = store.saveRuntimeRunReport(appPackage, report)
        step(
            "通用搜索兜底执行结果",
            if (result.status.startsWith("FAILED")) result.status else "SUCCESS",
            "${macro.optString("id")} -> ${result.status}；${result.message}",
            reportFile.absolutePath,
            null
        )
        return if (result.status.startsWith("FAILED")) null else reportFile
    }

    private fun runPinduoduoTextSearchFallback(
        instruction: String,
        appPackage: String,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): File? {
        val query = extractSearchQuery(instruction).ifBlank { "纸巾" }
        step(
            "拼多多文字搜索恢复启动",
            "RUNNING",
            "目标校验发现当前没有到商品搜索结果页；手机端将从拍照搜/权限页返回，并重新生成只走文字搜索框的细粒度 JSON。",
            null,
            JSONObject()
                .put("query", query)
                .put("currentUi", currentUiSummary())
        )
        val built = buildDirectPlanSkills(instruction, appPackage, pinduoduoTextSearchRecoverySteps(instruction, appPackage))
        step(
            "拼多多文字搜索 Skill JSON 已写入",
            "SUCCESS",
            "写入 ${built.skillFiles.size} 个候选 JSON：Back/弹窗处理/OpenApp/点文字搜索框/输入关键词/提交搜索。",
            built.macroFile.absolutePath,
            null
        )
        val dry = dryRunFiles(appPackage, built.skillFiles)
        step(
            "拼多多文字搜索 dry-run",
            if (dry.passed == dry.total) "SUCCESS" else "FAILED_DRY_RUN",
            "通过 ${dry.passed}/${dry.total}。",
            dry.lastReportPath,
            null
        )

        val macro = JSONObject(built.macroFile.readText())
        val startedAt = System.currentTimeMillis()
        val result = SkillJsonExecutor(context, runtimeApprovedOnly = false)
            .execute(macro, JSONObject(), allowSideEffect = true)
        val goalCheck = verifyInstructionGoal(instruction, appPackage)
        val status = when {
            result.status.startsWith("FAILED") -> result.status
            goalCheck.passed -> "SUCCESS"
            else -> "FAILED_GOAL_UNVERIFIED"
        }
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_pinduoduo_text_search_recovery_report")
            .put("id", "pinduoduo_text_search_recovery_${System.currentTimeMillis()}")
            .put("skillId", macro.optString("id"))
            .put("appPackage", appPackage)
            .put("instruction", instruction)
            .put("query", query)
            .put("engine", "pinduoduo_text_search_recovery")
            .put("approvedRequired", false)
            .put("status", status)
            .put("executorStatus", result.status)
            .put("message", result.message)
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("usedCoordinate", result.usedCoordinate)
            .put("allowSideEffect", true)
            .put("goalCheck", JSONObject()
                .put("passed", goalCheck.passed)
                .put("message", goalCheck.message)
                .put("evidence", goalCheck.evidence))
            .put("skillFile", built.macroFile.absolutePath)
            .put("createdAt", System.currentTimeMillis())
        val reportFile = store.saveRuntimeRunReport(appPackage, report)
        step(
            "拼多多文字搜索恢复结果",
            status,
            "${macro.optString("id")} -> ${result.status}；${goalCheck.message}",
            reportFile.absolutePath,
            goalCheck.evidence
        )
        return if (status.startsWith("FAILED")) null else reportFile
    }

    private fun runBilibiliCommentDraftFallback(
        instruction: String,
        appPackage: String,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): File? {
        step("AI 评论计划失败，启动规则草稿兜底", "RUNNING", "改用固定细粒度 JSON：搜索内容 -> 打开结果 -> 点评论 -> 输入草稿；不点击发布。", null, null)
        val built = buildDirectPlanSkills(instruction, appPackage, bilibiliCommentDraftSteps(instruction))
        val dry = dryRunPackage(appPackage)
        step("评论草稿 dry-run", if (dry.passed == dry.total) "SUCCESS" else "FAILED_DRY_RUN", "通过 ${dry.passed}/${dry.total}。", dry.lastReportPath, null)
        val macro = JSONObject(built.macroFile.readText())
        val startedAt = System.currentTimeMillis()
        val result = SkillJsonExecutor(context, runtimeApprovedOnly = false)
            .execute(macro, JSONObject(), allowSideEffect = true)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_ai_direct_comment_draft_fallback_report")
            .put("id", "ai_comment_draft_fallback_${System.currentTimeMillis()}")
            .put("skillId", macro.optString("id"))
            .put("appPackage", appPackage)
            .put("instruction", instruction)
            .put("engine", "comment_draft_rule_fallback_skill_json")
            .put("approvedRequired", false)
            .put("status", result.status)
            .put("message", result.message)
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("usedCoordinate", result.usedCoordinate)
            .put("allowSideEffect", true)
            .put("note", "评论任务默认只写草稿，不点击发布按钮。")
            .put("skillFile", built.macroFile.absolutePath)
            .put("createdAt", System.currentTimeMillis())
        val reportFile = store.saveRuntimeRunReport(appPackage, report)
        step(
            "评论草稿兜底执行结果",
            if (result.status.startsWith("FAILED")) result.status else "SUCCESS",
            "${macro.optString("id")} -> ${result.status}；${result.message}",
            reportFile.absolutePath,
            null
        )
        return if (result.status.startsWith("FAILED")) null else reportFile
    }

    private fun shouldRunXiaohongshuDraftFallback(instruction: String, appPackage: String): Boolean {
        return isXiaohongshuPostInstruction(instruction, appPackage)
    }

    private fun shouldRunBottomTabEntrySweep(instruction: String, appPackage: String): Boolean {
        if (appPackage.isBlank()) return false
        if (isWechatMomentPrivateInstruction(instruction, appPackage)) return false
        if (appPackage == SkillJsonRuleGenerator.BILIBILI_PACKAGE &&
            (isOpenFirstContentInstruction(instruction) || isBilibiliSearchInstruction(instruction, appPackage))
        ) {
            return false
        }
        val text = instruction.lowercase(Locale.CHINA)
        return listOf(
            "\u53d1\u5e16",
            "\u53d1\u5e03",
            "\u5e16\u5b50",
            "\u521b\u4f5c",
            "\u5199\u5fae\u535a",
            "\u641c\u7d22",
            "\u6d88\u606f",
            "\u804a\u5929",
            "\u79c1\u4fe1",
            "\u8bc4\u8bba",
            "post",
            "publish",
            "search",
            "message",
            "comment"
        ).any { marker -> text.contains(marker.lowercase(Locale.CHINA)) } ||
            isGenericPostDraftInstruction(instruction, appPackage) ||
            isHupuExplicitPublishInstruction(instruction, appPackage)
    }

    private fun entryAliasesForInstruction(instruction: String, appPackage: String): List<String> {
        val text = instruction.lowercase(Locale.CHINA)
        return when {
            isGenericPostDraftInstruction(instruction, appPackage) ||
                isHupuExplicitPublishInstruction(instruction, appPackage) ||
                text.contains("\u53d1\u5e16") ||
                text.contains("\u53d1\u5e03") ||
                text.contains("post") ||
                text.contains("publish") -> postEntryAliasesForPackage(appPackage)
            text.contains("\u641c\u7d22") || text.contains("search") -> listOf("\u641c\u7d22", "\u641c", "\u67e5\u627e")
            text.contains("\u6d88\u606f") || text.contains("\u804a\u5929") || text.contains("\u79c1\u4fe1") || text.contains("message") -> listOf("\u6d88\u606f", "\u804a\u5929", "\u79c1\u4fe1")
            text.contains("\u8bc4\u8bba") || text.contains("comment") -> listOf("\u8bc4\u8bba", "\u5199\u8bc4\u8bba", "\u56de\u590d")
            else -> listOf("\u53d1\u5e16", "\u53d1\u5e03", "\u521b\u4f5c", "\u641c\u7d22", "\u6d88\u606f", "+")
        }
    }

    private fun postEntryAliasesForPackage(appPackage: String): List<String> {
        return when {
            appPackage.contains("hupu", ignoreCase = true) -> listOf("\u53d1\u5e16", "\u53d1\u5e03", "\u5199\u5e16\u5b50", "\u5199\u5e16", "\u521b\u4f5c", "+")
            appPackage.contains("weibo", ignoreCase = true) -> listOf("\u5199\u5fae\u535a", "\u53d1\u5fae\u535a", "\u53d1\u5e03", "\u521b\u4f5c", "+")
            appPackage.contains("zhihu", ignoreCase = true) -> listOf("\u5199\u56de\u7b54", "\u53d1\u5e03", "\u521b\u4f5c", "+")
            else -> listOf("\u53d1\u5e16", "\u53d1\u5e03", "\u521b\u4f5c", "\u5199", "\u52a0\u53f7", "+")
        }
    }

    private fun runBottomTabEntrySweepFallback(
        instruction: String,
        appPackage: String,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): File? {
        val aliases = entryAliasesForInstruction(instruction, appPackage)
        step(
            "\u5165\u53e3\u672a\u627e\u5230\uff0c\u542f\u52a8\u5e95\u90e8\u5206\u9875\u626b\u63cf",
            "RUNNING",
            "\u56de\u5230\u76ee\u6807 App \u4e3b\u9875\uff0c\u9010\u4e2a\u70b9\u5e95\u90e8 tab\uff0c\u67e5\u627e\u5165\u53e3\uff1a${aliases.joinToString("/")}\u3002",
            null,
            JSONObject().put("entryAliases", JSONArray(aliases)).put("currentUi", currentUiSummary())
        )
        val steps = listOf(
            JSONObject().put("type", "OpenApp").put("packageName", appPackage).put("timeoutMs", 3000),
            JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", appPackage).put("maxPasses", 4),
            JSONObject()
                .put("type", "FindEntryByBottomTabSweep")
                .put("appPackage", appPackage)
                .put("entryAliases", JSONArray(aliases))
                .put("maxTabs", 5)
                .put("homeBackPresses", 3)
                .put("timeoutMs", 12_000),
            JSONObject().put("type", "Wait").put("timeoutMs", 900)
        )
        val built = buildDirectPlanSkills(instruction, appPackage, steps)
        val dry = dryRunFiles(appPackage, built.skillFiles)
        step(
            "\u5e95\u90e8\u5206\u9875\u626b\u63cf dry-run",
            if (dry.passed == dry.total) "SUCCESS" else "FAILED_DRY_RUN",
            "\u901a\u8fc7 ${dry.passed}/${dry.total}\u3002",
            dry.lastReportPath,
            null
        )
        val macro = JSONObject(built.macroFile.readText())
        val startedAt = System.currentTimeMillis()
        val result = SkillJsonExecutor(context, runtimeApprovedOnly = false)
            .execute(macro, JSONObject(), allowSideEffect = true)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_bottom_tab_entry_sweep_report")
            .put("id", "bottom_tab_entry_sweep_${System.currentTimeMillis()}")
            .put("skillId", macro.optString("id"))
            .put("appPackage", appPackage)
            .put("instruction", instruction)
            .put("engine", "bottom_tab_entry_sweep")
            .put("approvedRequired", false)
            .put("status", result.status)
            .put("message", result.message)
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("usedCoordinate", result.usedCoordinate)
            .put("entryAliases", JSONArray(aliases))
            .put("skillFile", built.macroFile.absolutePath)
            .put("createdAt", System.currentTimeMillis())
        val reportFile = store.saveRuntimeRunReport(appPackage, report)
        step(
            "\u5e95\u90e8\u5206\u9875\u626b\u63cf\u7ed3\u679c",
            if (result.status.startsWith("FAILED")) result.status else "SUCCESS",
            "${macro.optString("id")} -> ${result.status}; ${result.message.take(180)}",
            reportFile.absolutePath,
            JSONObject().put("entryAliases", JSONArray(aliases)).put("executorStatus", result.status)
        )
        return reportFile
    }

    private fun allowsExplicitPublishStep(instruction: String, appPackage: String, action: JSONObject): Boolean {
        if (!isXiaohongshuExplicitPublishInstruction(instruction, appPackage) &&
            !isDouyinVideoPublishInstruction(instruction, appPackage) &&
            !isHupuExplicitPublishInstruction(instruction, appPackage) &&
            !isWechatMomentPrivateInstruction(instruction, appPackage)
        ) return false
        if (action.optString("type") !in setOf("ClickVisibleText", "ClickSelector", "ClickCoordinate", "LongPressCoordinate")) return false
        val aliases = action.optJSONArray("textAliases")?.let { array ->
            (0 until array.length()).joinToString(" ") { index -> array.optString(index) }
        }.orEmpty()
        val text = listOf(action.optString("text"), action.optString("selector"), action.optString("targetText"), aliases)
            .joinToString(" ")
        return text.contains("\u53d1\u5e03") ||
            text.contains("\u53d1\u8868") ||
            text.contains("\u4e0a\u4f20") ||
            text.contains("publish", ignoreCase = true) ||
            text.contains("upload", ignoreCase = true)
    }

    private fun shouldAllowEntrySweepForInstruction(instruction: String, appPackage: String): Boolean {
        return shouldRunBottomTabEntrySweep(instruction, appPackage) ||
            isGenericPostDraftInstruction(instruction, appPackage) ||
            isXiaohongshuPostInstruction(instruction, appPackage) ||
            isHupuExplicitPublishInstruction(instruction, appPackage)
    }

    private fun runXiaohongshuDraftFallback(
        instruction: String,
        appPackage: String,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): File? {
        val body = extractXiaohongshuBody(instruction).ifBlank { "MacroPilot 自动化测试草稿，请勿发布" }
        val title = extractXiaohongshuTitle(instruction, body)
        step(
            "小红书草稿本地兜底启动",
            "RUNNING",
            "改用和 B 站相同的办法：生成一组确定性细粒度 Skill JSON，先回到小红书首页/创作入口，再写标题和正文；不点击最终发布。",
            null,
            JSONObject()
                .put("title", title)
                .put("body", body)
                .put("currentUi", currentUiSummary())
        )
        val built = buildDirectPlanSkills(instruction, appPackage, xiaohongshuDraftRecoverySteps(instruction))
        step(
            "小红书兜底 Skill JSON 已写入",
            "SUCCESS",
            "已写入 ${built.skillFiles.size} 个候选 JSON：OpenApp、点击首页/创作入口、选择文字模式、聚焦标题/正文、输入草稿。",
            built.macroFile.absolutePath,
            null
        )
        val dry = dryRunFiles(appPackage, built.skillFiles)
        step(
            "小红书兜底 dry-run",
            if (dry.passed == dry.total) "SUCCESS" else "FAILED_DRY_RUN",
            "通过 ${dry.passed}/${dry.total}。",
            dry.lastReportPath,
            null
        )

        val macro = JSONObject(built.macroFile.readText())
        val startedAt = System.currentTimeMillis()
        val result = SkillJsonExecutor(context, runtimeApprovedOnly = false)
            .execute(macro, JSONObject(), allowSideEffect = true)
        val goalCheck = verifyInstructionGoal(instruction, appPackage)
        val status = when {
            result.status.startsWith("FAILED") -> result.status
            goalCheck.passed -> "SUCCESS"
            else -> "FAILED_GOAL_UNVERIFIED"
        }
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_xiaohongshu_draft_fallback_report")
            .put("id", "xhs_draft_fallback_${System.currentTimeMillis()}")
            .put("skillId", macro.optString("id"))
            .put("appPackage", appPackage)
            .put("instruction", instruction)
            .put("engine", "xhs_draft_local_resolver")
            .put("approvedRequired", false)
            .put("status", status)
            .put("executorStatus", result.status)
            .put("message", result.message)
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("usedCoordinate", result.usedCoordinate)
            .put("allowSideEffect", true)
            .put("title", title)
            .put("body", body)
            .put("goalCheck", JSONObject()
                .put("passed", goalCheck.passed)
                .put("message", goalCheck.message)
                .put("evidence", goalCheck.evidence))
            .put("note", "小红书任务默认只写草稿，不点击最终发布按钮。")
            .put("skillFile", built.macroFile.absolutePath)
            .put("createdAt", System.currentTimeMillis())
        val reportFile = store.saveRuntimeRunReport(appPackage, report)
        step(
            "小红书草稿兜底执行结果",
            status,
            "${macro.optString("id")} -> ${result.status}；${goalCheck.message}",
            reportFile.absolutePath,
            goalCheck.evidence
        )
        return reportFile
    }

    private fun runXiaohongshuPublishFallback(
        instruction: String,
        appPackage: String,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): File? {
        val body = extractXiaohongshuBody(instruction).ifBlank { "我是ai" }
        val title = extractXiaohongshuTitle(instruction, body)
        step(
            "小红书发布本地兜底启动",
            "RUNNING",
            "用户明确要求发布：生成发布专用细粒度 Skill JSON，允许点击最终发布按钮，并用发布后页面证据校验。",
            null,
            JSONObject()
                .put("title", title)
                .put("body", body)
                .put("currentUi", currentUiSummary())
        )
        val built = buildDirectPlanSkills(instruction, appPackage, xiaohongshuPublishPostSteps(instruction))
        step(
            "小红书发布 Skill JSON 已写入",
            "SUCCESS",
            "已写入 ${built.skillFiles.size} 个候选 JSON：进入编辑器、替换输入内容、下一步、发布。",
            built.macroFile.absolutePath,
            null
        )
        val dry = dryRunFiles(appPackage, built.skillFiles)
        step(
            "小红书发布 dry-run",
            if (dry.passed == dry.total) "SUCCESS" else "FAILED_DRY_RUN",
            "通过 ${dry.passed}/${dry.total}。",
            dry.lastReportPath,
            null
        )

        val macro = JSONObject(built.macroFile.readText())
        val startedAt = System.currentTimeMillis()
        val result = SkillJsonExecutor(context, runtimeApprovedOnly = false)
            .execute(macro, JSONObject(), allowSideEffect = true)
        val goalCheck = verifyInstructionGoal(instruction, appPackage)
        val status = when {
            result.status.startsWith("FAILED") -> result.status
            goalCheck.passed -> "SUCCESS"
            else -> "FAILED_GOAL_UNVERIFIED"
        }
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_xiaohongshu_publish_fallback_report")
            .put("id", "xhs_publish_fallback_${System.currentTimeMillis()}")
            .put("skillId", macro.optString("id"))
            .put("appPackage", appPackage)
            .put("instruction", instruction)
            .put("engine", "xhs_publish_local_resolver")
            .put("approvedRequired", false)
            .put("status", status)
            .put("executorStatus", result.status)
            .put("message", result.message)
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("usedCoordinate", result.usedCoordinate)
            .put("allowSideEffect", true)
            .put("title", title)
            .put("body", body)
            .put("goalCheck", JSONObject()
                .put("passed", goalCheck.passed)
                .put("message", goalCheck.message)
                .put("evidence", goalCheck.evidence))
            .put("skillFile", built.macroFile.absolutePath)
            .put("createdAt", System.currentTimeMillis())
        val reportFile = store.saveRuntimeRunReport(appPackage, report)
        step(
            "小红书发布兜底执行结果",
            status,
            "${macro.optString("id")} -> ${result.status}；${goalCheck.message}",
            reportFile.absolutePath,
            goalCheck.evidence
        )
        return reportFile
    }

    private fun finishReport(
        instruction: String,
        appPackage: String,
        status: String,
        startedAt: Long,
        steps: JSONArray,
        aiJobFile: File?,
        previewConfigFile: File?,
        runtimeRunReportFile: File?,
        aiJobFiles: JSONArray = JSONArray(),
        goalCheck: GoalCheck? = null,
        finalUiSummary: JSONObject? = null
    ): JSONObject {
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "app_side_ai_direct_instruction_flow_report")
            .put("operation", "APP_SIDE_AI_DIRECT_INSTRUCTION")
            .put("id", "ai_direct_flow_${System.currentTimeMillis()}")
            .put("instruction", instruction)
            .put("appPackage", appPackage)
            .put("query", extractSearchQuery(instruction))
            .put("status", status)
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("steps", steps)
            .put("finalOutcome", JSONObject()
                .put("status", status)
                .put("summary", buildFinalOutcomeSummary(status, goalCheck))
                .put("goalCheck", goalCheck?.let { goalCheckSummary(it) } ?: JSONObject.NULL)
                .put("lastStep", lastStepSummary(steps))
                .put("finalUiSummary", finalUiSummary ?: JSONObject.NULL))
            .put("outputs", JSONObject()
                .put("aiJobFile", aiJobFile?.absolutePath ?: JSONObject.NULL)
                .put("aiJobFiles", aiJobFiles)
                .put("previewConfigFile", previewConfigFile?.absolutePath ?: JSONObject.NULL)
                .put("runtimeRunReportFile", runtimeRunReportFile?.absolutePath ?: JSONObject.NULL)
                .put("latestAssemblyReports", JSONArray(store.listAssemblyReports(appPackage.takeIf { it.isNotBlank() }, 5).map { it.absolutePath }))
                .put("appUiGraph", appUiGraphStore.candidateFile(appPackage.ifBlank { "__unknown__" }).absolutePath)
                .put("recentUiSnapshots", snapshotStore.recentSummary(appPackage.takeIf { it.isNotBlank() }, 12)))
            .put("createdAt", System.currentTimeMillis())
        val file = store.saveFlowReport(appPackage.ifBlank { "__unknown__" }, report)
        report.put("reportFile", file.absolutePath)
        val trainingRecordFile = trainingRecordStore.saveFromFlowReport(report, file)
        report.optJSONObject("outputs")
            ?.put("autonomousTrainingRecordFile", trainingRecordFile.absolutePath)
        file.writeText(report.toString(2))
        onEvent(AppSideFactoryFlowEvent(
            title = "用户指令流水线完成",
            status = status,
            message = "报告=${file.name}；训练记录=${trainingRecordFile.name}；步骤=${steps.length()}。",
            path = file.absolutePath
        ))
        return report
    }

    private fun buildFinalOutcomeSummary(status: String, goalCheck: GoalCheck?): String {
        val base = when {
            status.startsWith("SUCCESS") -> "任务已完成"
            status == "FAILED_GOAL_UNVERIFIED" -> "任务已执行，但最终目标校验未通过"
            status.startsWith("FAILED") -> "任务执行失败"
            status.startsWith("BLOCKED") -> "任务被前置条件阻断"
            else -> "任务结束"
        }
        val blocker = goalCheck?.blockerType?.takeIf { it.isNotBlank() && it != "NONE" }
        val hint = goalCheck?.nextActionHint?.takeIf { it.isNotBlank() }
        return buildString {
            append(base)
            if (blocker != null) {
                append("；blockerType=")
                append(blocker)
            }
            if (goalCheck != null) {
                append("；goal=")
                append(if (goalCheck.passed) "PASSED" else "FAILED")
            }
            if (!hint.isNullOrBlank()) {
                append("；nextAction=")
                append(hint.take(180))
            }
            if (!goalCheck?.message.isNullOrBlank()) {
                append("；evidence=")
                append(goalCheck?.message?.take(180))
            }
        }
    }

    private fun goalCheckSummary(goalCheck: GoalCheck): JSONObject {
        return JSONObject()
            .put("mandatory", goalCheck.mandatory)
            .put("passed", goalCheck.passed)
            .put("message", goalCheck.message)
            .put("blockerType", goalCheck.blockerType)
            .put("nextActionHint", goalCheck.nextActionHint)
            .put("evidence", goalCheck.evidence)
    }

    private fun lastStepSummary(steps: JSONArray): JSONObject {
        if (steps.length() <= 0) {
            return JSONObject()
                .put("available", false)
        }
        val last = steps.optJSONObject(steps.length() - 1) ?: JSONObject()
        return JSONObject()
            .put("available", true)
            .put("title", last.optString("title"))
            .put("status", last.optString("status"))
            .put("message", last.optString("message"))
            .put("path", last.optString("path"))
            .put("extra", last.optJSONObject("extra") ?: JSONObject.NULL)
    }

    private fun currentUiSummary(): JSONObject {
        val state = inspector.currentState()
        val profile = profiler.profile(state, MacroPilotAccessibilityService.instance != null)
        val snapshotPath = state?.let { ui ->
            runCatching {
                snapshotStore.save(
                    state = ui,
                    profile = profile,
                    source = "current_ui_summary",
                    note = "factory_ai_context",
                    forceScreenshot = true
                ).absolutePath
            }.getOrNull()
        }
        val nodes = JSONArray()
        state?.nodes?.take(90)?.forEach { node ->
            nodes.put(JSONObject()
                .put("text", node.text)
                .put("desc", node.contentDesc)
                .put("resourceId", node.resourceId)
                .put("class", node.className)
                .put("clickable", node.clickable)
                .put("editable", node.editable)
                .put("scrollable", node.scrollable)
                .put("bounds", node.bounds.flattenToString()))
        }
        return JSONObject()
            .put("serviceConnected", MacroPilotAccessibilityService.instance != null)
            .put("appPackage", state?.appPackage)
            .put("windowClassName", state?.windowClassName)
            .put("screenWidth", state?.screenWidth ?: 0)
            .put("screenHeight", state?.screenHeight ?: 0)
            .put("nodeCount", state?.nodes?.size ?: 0)
            .put("snapshotPath", snapshotPath ?: JSONObject.NULL)
            .put("recentUiSnapshots", snapshotStore.recentSummary(state?.appPackage, 4))
            .put("capability", JSONObject()
                .put("nodeReadability", profile.nodeReadability.name)
                .put("inputCapability", profile.inputCapability.name)
                .put("verificationCapability", profile.verificationCapability.name))
            .put("nodes", nodes)
            .put("blockingDialogs", reactiveRules.enhancedBlockingDialogSummary(state))
            .put("clickTargets", clickTargetsSummary(state))
            .put("bottomTabs", bottomTabsSummary(state))
            .put("entryCandidates", entryCandidatesSummary(state))
            .put("videoCandidates", videoCandidatesSummary(state))
    }

    private fun ensureAppUiGraphForInstruction(
        instruction: String,
        appPackage: String,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): JSONObject? {
        if (appPackage.isBlank()) return null
        val existing = appUiGraphStore.compactSummary(appPackage, 16)
        if (existing.optString("status") != "MISSING" && existing.optInt("screenCount", 0) >= 2) {
            return existing.put("path", appUiGraphStore.candidateFile(appPackage).absolutePath)
        }
        if (!shouldExploreAppUiForInstruction(instruction)) {
            return existing.takeIf { it.optString("status") != "MISSING" }
        }
        step(
            "AppUI 自训练开始",
            "RUNNING",
            "没有足够 AppUI 图谱：先由手机端自己打开 App、关弹窗、记录首页/底部分屏/入口/输入框/子页面。",
            null,
            JSONObject().put("appPackage", appPackage)
        )
        val result = appUiExplorer.explore(
            packageName = appPackage,
            appName = appNameForPackage(appPackage),
            reason = "instruction:${instruction.take(80)}",
            maxScreens = 24,
            maxClicks = 24,
            maxDepth = 3,
            maxSwipeActions = 60,
            forceScreenshots = true
        ) { title, status, message, path ->
            step("AppUI 探索：$title", status, message, path, null)
        }
        val summary = appUiGraphStore.compactSummary(appPackage, 16)
            .put("path", result.graphFile?.absolutePath ?: appUiGraphStore.candidateFile(appPackage).absolutePath)
            .put("exploreStatus", result.report.optString("status"))
            .put("exploreReport", result.report)
        step(
            "AppUI 自训练完成",
            if (result.success) "SUCCESS" else result.report.optString("status").ifBlank { "FAILED" },
            "页面=${summary.optInt("screenCount")}，边=${summary.optInt("edgeCount")}；图谱会参与本次 AI/Skill 改写。",
            result.graphFile?.absolutePath,
            summary
        )
        return summary
    }

    private fun shouldExploreAppUiForInstruction(instruction: String): Boolean {
        val lower = instruction.lowercase(Locale.CHINA)
        return listOf(
            "搜索",
            "搜",
            "打开",
            "点击",
            "发",
            "发布",
            "发帖",
            "发表",
            "评论",
            "点赞",
            "朋友圈",
            "视频",
            "入口",
            "页面",
            "ui",
            "UI"
        ).any { lower.contains(it.lowercase(Locale.CHINA)) }
    }

    private fun availableSkillTemplateSummary(appPackage: String, limit: Int): JSONArray {
        val out = JSONArray()
        val files = (store.listRuntimeApprovedSkills(appPackage.takeIf { it.isNotBlank() }, limit) +
            store.listSkillFiles(appPackage.takeIf { it.isNotBlank() }, limit))
            .distinctBy { it.absolutePath }
            .take(limit.coerceIn(1, 80))
        files.forEach { file ->
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@forEach
            out.put(JSONObject()
                .put("id", json.optString("id"))
                .put("kind", json.optString("kind"))
                .put("implements", json.optString("implements"))
                .put("status", json.optString("status"))
                .put("actionType", json.optJSONObject("action")?.optString("type"))
                .put("intentId", json.optString("intentId"))
                .put("file", file.absolutePath))
        }
        return out
    }

    private fun clickTargetsSummary(state: com.macropilot.app.model.UiStateSample?): JSONArray {
        if (state == null) return JSONArray()
        val out = JSONArray()
        state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    node.bounds.width() > 8 &&
                    node.bounds.height() > 8 &&
                    (node.clickable || !node.text.isNullOrBlank() || !node.contentDesc.isNullOrBlank())
            }
            .filterNot { node ->
                val label = listOfNotNull(node.text, node.contentDesc, node.resourceId).joinToString(" ")
                label.isBlank() && !node.clickable
            }
            .take(60)
            .forEachIndexed { index, node ->
                out.put(nodeChoiceJson(index, node, state.screenWidth, state.screenHeight, semanticTagsForNode(node, state)))
            }
        return out
    }

    private fun bottomTabsSummary(state: com.macropilot.app.model.UiStateSample?): JSONArray {
        if (state == null) return JSONArray()
        val out = JSONArray()
        val topCutoff = (state.screenHeight * 0.68f).toInt()
        state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    node.bounds.centerY() >= topCutoff &&
                    node.bounds.width() > 8 &&
                    node.bounds.height() > 8 &&
                    (node.clickable || !node.text.isNullOrBlank() || !node.contentDesc.isNullOrBlank())
            }
            .sortedBy { it.bounds.centerX() }
            .take(12)
            .forEachIndexed { index, node ->
                out.put(nodeChoiceJson(index, node, state.screenWidth, state.screenHeight, semanticTagsForNode(node, state) + "bottom_tab_candidate"))
            }
        return out
    }

    private fun videoCandidatesSummary(state: com.macropilot.app.model.UiStateSample?): JSONArray {
        if (state == null) return JSONArray()
        val out = JSONArray()
        val topCutoff = (state.screenHeight * 0.20f).toInt()
        val bottomCutoff = (state.screenHeight * 0.88f).toInt()
        state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    node.bounds.centerY() in topCutoff..bottomCutoff &&
                    node.bounds.width() > state.screenWidth * 0.18f &&
                    node.bounds.height() > 24 &&
                    (node.clickable || !node.text.isNullOrBlank() || !node.contentDesc.isNullOrBlank())
            }
            .filterNot { node ->
                val label = listOfNotNull(node.text, node.contentDesc, node.resourceId).joinToString(" ")
                if (state.appPackage == SkillJsonRuleGenerator.BILIBILI_PACKAGE) {
                    isBilibiliNonVideoSearchControl(label)
                } else {
                    listOf("搜索", "返回", "清除查询", "首页", "动态", "会员购", "我的").any { label == it || label.endsWith("/$it") }
                }
            }
            .take(20)
            .forEachIndexed { index, node ->
                out.put(nodeChoiceJson(index, node, state.screenWidth, state.screenHeight, semanticTagsForNode(node, state)))
            }
        return out
    }

    private fun entryCandidatesSummary(state: com.macropilot.app.model.UiStateSample?): JSONArray {
        if (state == null) return JSONArray()
        val out = JSONArray()
        val aliases = listOf(
            "\u641c\u7d22",
            "\u53d1\u5e16",
            "\u53d1\u5e03",
            "\u53d1\u8868",
            "\u521b\u4f5c",
            "\u5199",
            "\u52a0\u53f7",
            "+",
            "\u6d88\u606f",
            "\u8bc4\u8bba",
            "\u670b\u53cb\u5708",
            "\u53d1\u73b0",
            "post",
            "publish",
            "search",
            "create"
        )
        state.nodes
            .asSequence()
            .filter { node ->
                node.enabled &&
                    node.bounds.width() > 8 &&
                    node.bounds.height() > 8 &&
                    (node.clickable || !node.text.isNullOrBlank() || !node.contentDesc.isNullOrBlank())
            }
            .map { node ->
                val label = listOfNotNull(node.text, node.contentDesc, node.resourceId, node.className)
                    .joinToString(" ")
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .trim()
                node to label
            }
            .filter { (_, label) ->
                label.isNotBlank() && aliases.any { alias -> label.contains(alias, ignoreCase = true) }
            }
            .sortedWith(compareBy<Pair<com.macropilot.app.model.NodeSample, String>> { it.first.bounds.centerY() }
                .thenBy { it.first.bounds.centerX() })
            .take(24)
            .forEachIndexed { index, (node, _) ->
                out.put(nodeChoiceJson(index, node, state.screenWidth, state.screenHeight, semanticTagsForNode(node, state) + "entry_candidate"))
            }
        return out
    }

    private fun nodeChoiceJson(
        index: Int,
        node: com.macropilot.app.model.NodeSample,
        screenWidth: Int,
        screenHeight: Int,
        tags: List<String>
    ): JSONObject {
        val centerX = if (screenWidth > 0) node.bounds.centerX().toDouble() / screenWidth else 0.5
        val centerY = if (screenHeight > 0) node.bounds.centerY().toDouble() / screenHeight else 0.5
        return JSONObject()
            .put("index", index)
            .put("text", node.text ?: JSONObject.NULL)
            .put("desc", node.contentDesc ?: JSONObject.NULL)
            .put("resourceId", node.resourceId ?: JSONObject.NULL)
            .put("class", node.className ?: JSONObject.NULL)
            .put("clickable", node.clickable)
            .put("selected", node.selected)
            .put("bounds", node.bounds.flattenToString())
            .put("center", JSONObject()
                .put("x", centerX.coerceIn(0.0, 1.0))
                .put("y", centerY.coerceIn(0.0, 1.0)))
            .put("tags", JSONArray(tags))
    }

    private fun semanticTagsForNode(
        node: com.macropilot.app.model.NodeSample,
        state: com.macropilot.app.model.UiStateSample
    ): List<String> {
        val label = listOfNotNull(node.text, node.contentDesc, node.resourceId, node.className)
            .joinToString(" ")
            .lowercase(Locale.CHINA)
        return buildList {
            if (node.clickable) add("clickable")
            if (node.selected) add("selected")
            if (node.bounds.centerY() > state.screenHeight * 0.16f) add("content_area")
            if (label.contains("video") || label.contains("视频") || label.contains("播放") || label.contains("view") || label.contains("duration")) add("video_like")
            if (label.contains("up") || label.contains("作者") || label.contains("王老菊")) add("author_or_query")
            if (label.contains("点赞") || label.contains("赞") || label.contains("like")) add("like_control")
            if (label.contains("评论") || label.contains("comment")) add("comment_control")
            if (state.appPackage == SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE) {
                if (label.contains("发布") ||
                    label.contains("创作") ||
                    label.contains("发笔记") ||
                    label.contains("加号") ||
                    label.contains("plus") ||
                    (node.clickable && kotlin.math.abs(node.bounds.centerX() - state.screenWidth / 2) < state.screenWidth * 0.12f &&
                        node.bounds.centerY() > state.screenHeight * 0.84f)
                ) {
                    add("create_control")
                }
                if (label.contains("标题") || label.contains("添加标题")) add("title_input")
                if (label.contains("正文") || label.contains("添加正文") || label.contains("说点什么")) add("body_input")
                if (node.editable || label.contains("发布笔记") || label.contains("保存草稿") || label.contains("谁可以看")) add("draft_editor")
                if (label.contains("下一步") || label.contains("完成")) add("next_step")
                if (label.contains("相册") || label.contains("图片") || label.contains("图文") || label.contains("文字")) add("media_or_text_mode")
                if (label in setOf("首页", "购物", "消息", "我") || label.contains("home")) add("tab_control")
            }
        }
    }

    private fun extractIntentDraft(instruction: String, root: JSONObject, packageHint: String): JSONObject {
        val aiIntent = root.optJSONObject("intentDraft") ?: root.optJSONObject("intent") ?: JSONObject()
        val appPackage = aiIntent.optString("appPackage").ifBlank {
            aiIntent.optString("packageName").ifBlank { normalizeTargetPackage(instruction, packageHint) }
        }
        return JSONObject()
            .put("appPackage", appPackage)
            .put("intentId", aiIntent.optString("intentId").ifBlank { inferIntentId(instruction) })
            .put("params", aiIntent.optJSONObject("params") ?: JSONObject()
                .put("query", extractSearchQuery(instruction))
                .put("message", extractMessage(instruction)))
            .put("source", "AI_DIRECT")
    }

    private fun extractPlanSteps(root: JSONObject): List<JSONObject> {
        val plan = root.optJSONObject("plan")
        val arrays = listOfNotNull(
            root.optJSONArray("steps"),
            root.optJSONArray("actions"),
            root.optJSONArray("plan"),
            plan?.optJSONArray("steps"),
            plan?.optJSONArray("actions")
        )
        return arrays.firstOrNull { it.length() > 0 }?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
        }.orEmpty()
    }

    private fun extractCandidates(root: JSONObject, intentDraft: JSONObject, aiStatus: String): List<JSONObject> {
        val arrays = listOfNotNull(
            root.optJSONArray("candidates"),
            root.optJSONArray("skillCandidates"),
            root.optJSONArray("skills")
        )
        val packageName = intentDraft.optString("appPackage")
        return arrays.flatMap { array ->
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
        }.mapIndexed { index, raw ->
            JSONObject(raw.toString())
                .put("schemaVersion", raw.optInt("schemaVersion", 1))
                .put("status", "CANDIDATE")
                .put("appPackage", raw.optString("appPackage").ifBlank { packageName })
                .put("id", raw.optString("id").ifBlank { "ai.direct.candidate.${System.currentTimeMillis()}.$index" })
                .put("factory", (raw.optJSONObject("factory") ?: JSONObject())
                    .put("source", "AI_DIRECT_CANDIDATE")
                    .put("aiStatus", aiStatus)
                    .put("dryRunStatus", "PENDING")
                    .put("promotionStatus", "NOT_PROMOTED")
                    .put("createdAt", System.currentTimeMillis()))
        }.filter { it.optString("kind") in setOf("atomic_skill", "macro_skill", "reactive_rule") }
    }

    private fun heuristicSteps(instruction: String, appPackage: String): List<JSONObject> {
        val text = instruction.trim()
        graphBasedHeuristicSteps(text, appPackage).takeIf { it.isNotEmpty() }?.let { return it }
        val out = mutableListOf<JSONObject>()
        val query = extractSearchQuery(text)
        if (isXiaohongshuPostInstruction(text, appPackage)) {
            return xiaohongshuPostDraftSteps(text)
        }
        if (isGenericPostDraftInstruction(text, appPackage)) {
            return genericThirdPartyPostDraftSteps(text, appPackage)
        }
        if (query.isNotBlank() && text.contains("搜") && appPackage.isNotBlank()) {
            return genericThirdPartySearchSteps(instruction, appPackage)
        }
        if (appPackage.isNotBlank() && (text.contains("打开") || text.contains("启动"))) {
            out += JSONObject().put("type", "OpenApp").put("packageName", appPackage)
            return out
        }
        if (text.contains("返回")) {
            out += JSONObject().put("type", "Back")
        }
        val clickText = Regex("(?:点击|点|打开)\\s*([^，。\\s]+)").find(text)?.groupValues?.getOrNull(1)
        if (!clickText.isNullOrBlank() && clickText !in listOf("打开", "启动")) {
            out += JSONObject().put("type", "ClickVisibleText").put("text", clickText)
        }
        val inputText = Regex("(?:输入|填入|写入)\\s*(.+)$").find(text)?.groupValues?.getOrNull(1)
        if (!inputText.isNullOrBlank()) {
            out += JSONObject().put("type", "TypeIntoFirstInput").put("text", inputText.trim())
        }
        if (out.isEmpty() && query.isNotBlank() && text.contains("搜")) {
            if (appPackage.isNotBlank()) out += JSONObject().put("type", "OpenApp").put("packageName", appPackage)
            out += JSONObject().put("type", "ClickVisibleText").put("text", "搜索")
            out += JSONObject().put("type", "TypeIntoFirstInput").put("text", query)
            out += JSONObject().put("type", "ClickVisibleText").put("text", "搜索")
        }
        return out
    }

    private fun graphBasedHeuristicSteps(instruction: String, appPackage: String): List<JSONObject> {
        if (appPackage.isBlank()) return emptyList()
        val graph = appUiGraphStore.compactSummary(appPackage, 16)
        if (graph.optString("status") == "MISSING") return emptyList()
        val wantsSearch = instruction.contains("搜索") || instruction.contains("搜")
        val wantsPublish = instruction.contains("发布") || instruction.contains("发帖") || instruction.contains("发表") || instruction.contains("发一条") || instruction.contains("发个")
        val wantsComment = instruction.contains("评论")
        val wantsOpenOnly = instruction.contains("打开") && !wantsSearch && !wantsPublish && !wantsComment
        val steps = mutableListOf<JSONObject>()
        steps += JSONObject().put("type", "OpenApp").put("packageName", appPackage).put("timeoutMs", 3000)
        steps += JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", appPackage).put("maxPasses", 3)
        when {
            wantsSearch -> {
                val entry = findGraphCandidate(graph, listOf("搜索", "search"))
                if (entry != null) {
                    steps += clickCandidateStep(entry, "搜索", 1200)
                    val query = extractSearchQuery(instruction)
                    if (query.isNotBlank()) {
                        steps += JSONObject().put("type", "TypeIntoFirstInput").put("text", query).put("timeoutMs", 2200)
                        steps += JSONObject().put("type", "ClickVisibleText")
                            .put("text", "搜索")
                            .put("textAliases", JSONArray(listOf("搜索", "确定", "Search")))
                            .put("timeoutMs", 1500)
                    }
                }
            }
            wantsPublish -> {
                val entry = findGraphCandidate(graph, listOf("发布", "发帖", "创作", "+", "publish", "post", "create"))
                if (entry != null) {
                    steps += clickCandidateStep(entry, "发布", 1500)
                    steps += JSONObject().put("type", "Wait").put("timeoutMs", 1000)
                } else {
                    steps += JSONObject()
                        .put("type", "FindEntryByBottomTabSweep")
                        .put("appPackage", appPackage)
                        .put("entryAliases", JSONArray(listOf("发布", "发帖", "发表", "创作", "+")))
                        .put("maxTabs", 5)
                        .put("timeoutMs", 10_000)
                }
                val content = extractPostContentForGraphFallback(instruction)
                if (content.isNotBlank()) {
                    steps += JSONObject().put("type", "TypeIntoFirstInput").put("text", content as Any).put("timeoutMs", 2600)
                }
            }
            wantsComment -> {
                val entry = findGraphCandidate(graph, listOf("评论", "说点什么", "留下你的想法", "comment"))
                if (entry != null) {
                    steps += clickCandidateStep(entry, "评论", 1200)
                }
                val comment = extractCommentText(instruction)
                if (comment.isNotBlank()) {
                    steps += JSONObject().put("type", "TypeIntoFirstInput").put("text", comment).put("timeoutMs", 2200)
                }
            }
            wantsOpenOnly -> {
                val clickText = Regex("(?:打开|进入|点开)\\s*([^，。\\s]+)").find(instruction)?.groupValues?.getOrNull(1).orEmpty()
                val entry = if (clickText.isNotBlank()) findGraphCandidate(graph, listOf(clickText)) else null
                if (entry != null) {
                    steps += clickCandidateStep(entry, clickText, 1200)
                }
            }
        }
        return if (steps.size > 2 || wantsOpenOnly) steps else emptyList()
    }

    private fun extractPostContentForGraphFallback(instruction: String): String {
        return extractGenericPostDraftContent(instruction)
            .ifBlank { extractXiaohongshuBody(instruction) }
            .ifBlank { extractMessage(instruction) }
            .take(160)
    }

    private fun findGraphCandidate(graph: JSONObject, aliases: List<String>): JSONObject? {
        val screens = graph.optJSONArray("screens") ?: return null
        val all = mutableListOf<JSONObject>()
        for (i in 0 until screens.length()) {
            val screen = screens.optJSONObject(i) ?: continue
            listOf("entryCandidates", "inputCandidates", "bottomTabs", "clickTargets").forEach { key ->
                val candidates = screen.optJSONArray(key) ?: return@forEach
                for (j in 0 until candidates.length()) {
                    candidates.optJSONObject(j)?.let { candidate ->
                        all += JSONObject(candidate.toString()).put("screenKey", screen.optString("screenKey")).put("sourceList", key)
                    }
                }
            }
        }
        return all
            .map { candidate -> candidate to graphCandidateScore(candidate, aliases) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<JSONObject, Int>> { it.second }
                .thenBy { it.first.optDouble("y", it.first.optJSONObject("center")?.optDouble("y", 0.5) ?: 0.5) })
            .firstOrNull()
            ?.first
    }

    private fun graphCandidateScore(candidate: JSONObject, aliases: List<String>): Int {
        val label = listOf(
            candidate.optString("label"),
            candidate.optString("text"),
            candidate.optString("desc"),
            candidate.optString("resourceId")
        ).joinToString(" ").lowercase(Locale.CHINA)
        val tags = candidate.optJSONArray("tags")?.toString().orEmpty().lowercase(Locale.CHINA)
        var score = 0
        aliases.forEach { alias ->
            val key = alias.lowercase(Locale.CHINA)
            if (label.contains(key)) score += 20
            if (tags.contains(key)) score += 10
        }
        if (candidate.optBoolean("clickable", false)) score += 4
        if (candidate.optString("sourceList") == "entryCandidates") score += 4
        if (candidate.optString("sourceList") == "bottomTabs") score += 2
        return score
    }

    private fun clickCandidateStep(candidate: JSONObject, textFallback: String, timeoutMs: Long): JSONObject {
        val center = candidate.optJSONObject("center")
        val x = candidate.optDouble("x", center?.optDouble("x", 0.5) ?: 0.5)
        val y = candidate.optDouble("y", center?.optDouble("y", 0.5) ?: 0.5)
        val aliases = JSONArray(listOf(
            textFallback,
            candidate.optString("text"),
            candidate.optString("desc"),
            candidate.optString("label")
        ).filter { it.isNotBlank() && it != "null" }.distinct())
        return JSONObject()
            .put("type", "ClickVisibleText")
            .put("text", textFallback)
            .put("textAliases", aliases)
            .put("x", x)
            .put("y", y)
            .put("requiresUiStateForCoordinate", false)
            .put("timeoutMs", timeoutMs)
            .put("graphCandidate", candidate)
    }

    private fun normalizeTargetPackage(instruction: String, targetPackageHint: String?): String {
        val hint = targetPackageHint?.trim().orEmpty()
        if (hint.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+"))) return hint
        Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").find(instruction)?.value?.let { return it }
        val lowered = instruction.lowercase(Locale.CHINA)
        if (instruction.contains("\u6296\u97f3") || lowered.contains("douyin") || lowered.contains("aweme")) {
            return SkillJsonRuleGenerator.DOUYIN_PACKAGE
        }
        if (instruction.contains("\u54d4\u54e9\u54d4\u54e9") || instruction.contains("B\u7ad9", ignoreCase = true)) {
            return SkillJsonRuleGenerator.BILIBILI_PACKAGE
        }
        if (instruction.contains("\u5c0f\u7ea2\u4e66")) {
            return SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE
        }
        if (instruction.contains("\u5fae\u4fe1")) {
            return SkillJsonRuleGenerator.WECHAT_PACKAGE
        }
        if (instruction.contains("\u864e\u6251")) {
            return "com.hupu.games"
        }
        if (instruction.contains("哔哩") || instruction.contains("B站", ignoreCase = true) || instruction.contains("bilibili", ignoreCase = true)) {
            return SkillJsonRuleGenerator.BILIBILI_PACKAGE
        }
        if (instruction.contains("小红书") || instruction.contains("xiaohongshu", ignoreCase = true) || instruction.contains("xhs", ignoreCase = true)) {
            return SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE
        }
        if (instruction.contains("微信") || instruction.contains("wechat", ignoreCase = true)) {
            return SkillJsonRuleGenerator.WECHAT_PACKAGE
        }
        if (instruction.contains("虎扑") || lowered.contains("hupu")) {
            return "com.hupu.games"
        }
        return findInstalledPackageByText(instruction) ?: hint
    }

    private fun findInstalledPackageByText(text: String): String? {
        val normalized = text.lowercase(Locale.CHINA)
        return runCatching {
            context.packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { app -> app.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { app -> app to context.packageManager.getApplicationLabel(app).toString() }
                .firstOrNull { (app, label) ->
                    appLabelMatchesInstruction(normalized, label, app.packageName)
                }
                ?.first
                ?.packageName
        }.getOrNull()
    }

    private fun installedAppHints(text: String): List<JSONObject> {
        val normalized = text.lowercase(Locale.CHINA)
        return runCatching {
            context.packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { app -> app.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { app -> app to context.packageManager.getApplicationLabel(app).toString() }
                .filter { (app, label) ->
                    appLabelMatchesInstruction(normalized, label, app.packageName)
                }
                .map { (app, label) -> JSONObject().put("label", label).put("packageName", app.packageName) }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun appLabelMatchesInstruction(normalizedInstruction: String, label: String, packageName: String): Boolean {
        val compact = label.trim().lowercase(Locale.CHINA)
        if (compact.isBlank()) return false
        if (normalizedInstruction.contains(packageName.lowercase(Locale.US))) return true
        if (compact.length >= 2 && normalizedInstruction.contains(compact)) return true
        val latinTokens = Regex("[a-z0-9][a-z0-9._-]{2,}")
            .findAll(compact)
            .map { it.value }
            .toList()
        return latinTokens.any { token -> normalizedInstruction.contains(token) }
    }

    private fun loadMacroWithParams(skillId: String, params: JSONObject): Pair<File, JSONObject>? {
        return store.loadSkillById(skillId)?.let { it.first to params }
    }

    private fun isDouyinPackage(appPackage: String?): Boolean {
        val value = appPackage.orEmpty().lowercase(Locale.US)
        return value == SkillJsonRuleGenerator.DOUYIN_PACKAGE ||
            value == "com.ss.android.ugc.aweme.lite" ||
            value.contains("aweme") ||
            value.contains("douyin")
    }

    private fun isDouyinVideoPublishInstruction(instruction: String, appPackage: String): Boolean {
        val lower = instruction.lowercase(Locale.CHINA)
        val targetsDouyin = isDouyinPackage(appPackage) ||
            instruction.contains("\u6296\u97f3") ||
            lower.contains("douyin") ||
            lower.contains("aweme")
        val publishIntent = listOf(
            "\u53d1\u89c6\u9891",
            "\u53d1\u4e00\u6761\u89c6\u9891",
            "\u53d1\u4e2a\u89c6\u9891",
            "\u53d1\u5e03\u89c6\u9891",
            "\u4e0a\u4f20\u89c6\u9891",
            "publish video",
            "upload video"
        ).any { marker -> lower.contains(marker.lowercase(Locale.CHINA)) } ||
            (instruction.contains("\u89c6\u9891") &&
                listOf("\u53d1", "\u53d1\u5e03", "\u4e0a\u4f20", "publish", "upload", "post")
                    .any { marker -> lower.contains(marker.lowercase(Locale.CHINA)) })
        return targetsDouyin && publishIntent
    }

    private fun douyinVideoPublishSteps(instruction: String, appPackage: String): List<JSONObject> {
        val packageName = if (isDouyinPackage(appPackage)) appPackage else SkillJsonRuleGenerator.DOUYIN_PACKAGE
        val text = extractDouyinVideoText(instruction).ifBlank { "\u6211\u662fAI" }
        return listOf(
            JSONObject()
                .put("type", "EnsureTextVideoAsset")
                .put("text", text)
                .put("displayName", "macropilot_douyin_ai.mp4")
                .put("durationMs", 3_000L),
            JSONObject().put("type", "OpenApp").put("packageName", packageName).put("timeoutMs", 3_000L),
            JSONObject().put("type", "Wait").put("timeoutMs", 3_000L),
            JSONObject()
                .put("type", "RecoverDouyinHomeSurface")
                .put("appPackage", packageName)
                .put("timeoutMs", 4_000L),
            JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", packageName).put("maxPasses", 3),
            JSONObject()
                .put("type", "HandleSystemPermissionDialog")
                .put("textAliases", JSONArray(listOf(
                    "\u4ec5\u9650\u8fd9\u4e00\u6b21",
                    "\u4f7f\u7528\u65f6\u5141\u8bb8",
                    "\u53bb\u6388\u6743",
                    "\u5141\u8bb8",
                    "\u540c\u610f"
                )))
                .put("x", 0.50)
                .put("y", 0.775)
                .put("settleMs", 900L)
                .put("userConfirmedSubmitAction", true)
                .put("submitReason", "\u7528\u6237\u660e\u786e\u8981\u6c42\u5728\u6296\u97f3\u53d1\u89c6\u9891\uff0c\u9700\u5141\u8bb8\u76f8\u673a/\u76f8\u518c\u6743\u9650"),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "+")
                .put("textAliases", JSONArray(listOf("+", "\u62cd\u6444", "\u521b\u4f5c", "\u53d1\u5e03")))
                .put("x", 0.50)
                .put("y", 0.935)
                .put("skipWhenUiUnavailable", true)
                .put("timeoutMs", 1_500L),
            JSONObject().put("type", "Wait").put("timeoutMs", 1_000L),
            JSONObject()
                .put("type", "HandleSystemPermissionDialog")
                .put("textAliases", JSONArray(listOf(
                    "\u4ec5\u9650\u8fd9\u4e00\u6b21",
                    "\u4f7f\u7528\u65f6\u5141\u8bb8",
                    "\u53bb\u6388\u6743",
                    "\u5141\u8bb8",
                    "\u540c\u610f"
                )))
                .put("x", 0.50)
                .put("y", 0.775)
                .put("settleMs", 900L)
                .put("userConfirmedSubmitAction", true)
                .put("submitReason", "\u7528\u6237\u660e\u786e\u8981\u6c42\u5728\u6296\u97f3\u53d1\u89c6\u9891\uff0c\u9700\u5141\u8bb8\u76f8\u673a/\u76f8\u518c\u6743\u9650"),
            JSONObject().put("type", "Wait").put("timeoutMs", 1_200L),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "\u76f8\u518c")
                .put("textAliases", JSONArray(listOf("\u76f8\u518c", "\u4e0a\u4f20", "\u624b\u673a\u76f8\u518c", "\u4ece\u76f8\u518c\u9009\u62e9")))
                .put("x", 0.18)
                .put("y", 0.86)
                .put("requiresUiStateForCoordinate", false)
                .put("timeoutMs", 1_500L),
            JSONObject().put("type", "Wait").put("timeoutMs", 1_200L),
            JSONObject()
                .put("type", "HandleSystemPermissionDialog")
                .put("textAliases", JSONArray(listOf(
                    "\u5141\u8bb8\u6240\u6709\u7167\u7247\u548c\u89c6\u9891",
                    "\u9009\u62e9\u7167\u7247\u548c\u89c6\u9891",
                    "\u4ec5\u9650\u8fd9\u4e00\u6b21",
                    "\u4f7f\u7528\u65f6\u5141\u8bb8",
                    "\u5141\u8bb8",
                    "\u540c\u610f"
                )))
                .put("x", 0.50)
                .put("y", 0.775)
                .put("settleMs", 900L)
                .put("userConfirmedSubmitAction", true)
                .put("submitReason", "\u7528\u6237\u660e\u786e\u8981\u6c42\u5728\u6296\u97f3\u53d1\u89c6\u9891\uff0c\u9700\u5141\u8bb8\u76f8\u673a/\u76f8\u518c\u6743\u9650"),
            JSONObject().put("type", "Wait").put("timeoutMs", 1_200L),
            JSONObject()
                .put("type", "ClickCoordinate")
                .put("x", 0.61)
                .put("y", 0.25)
                .put("timeoutMs", 1_500L),
            JSONObject().put("type", "Wait").put("timeoutMs", 1_000L),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "\u4e0b\u4e00\u6b65")
                .put("textAliases", JSONArray(listOf("\u4e0b\u4e00\u6b65", "\u5b8c\u6210", "\u9009\u597d\u4e86")))
                .put("x", 0.86)
                .put("y", 0.93)
                .put("requiresUiStateForCoordinate", false)
                .put("timeoutMs", 1_800L),
            JSONObject().put("type", "Wait").put("timeoutMs", 3_000L),
            JSONObject()
                .put("type", "TypeAtCoordinate")
                .put("x", 0.32)
                .put("y", 0.43)
                .put("text", text)
                .put("replace", false)
                .put("focusDelayMs", 700L)
                .put("timeoutMs", 2_600L),
            JSONObject().put("type", "Wait").put("timeoutMs", 800L),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "\u53d1\u5e03")
                .put("textAliases", JSONArray(listOf("\u53d1\u5e03", "\u53d1\u4f5c\u54c1", "\u53d1\u65e5\u5e38", "\u53d1\u5e03\u65e5\u5e38", "\u53d1\u5e03\u4f5c\u54c1")))
                .put("x", 0.78)
                .put("y", 0.93)
                .put("requiresUiStateForCoordinate", false)
                .put("timeoutMs", 1_800L)
                .put("userConfirmedSubmitAction", true)
                .put("submitReason", "\u7528\u6237\u660e\u786e\u8981\u6c42\u5728\u6296\u97f3\u53d1\u89c6\u9891"),
            JSONObject().put("type", "Wait").put("timeoutMs", 7_000L)
        )
    }

    private fun extractDouyinVideoText(instruction: String): String {
        val markers = listOf(
            "\u8bf4",
            "\u5185\u5bb9\u662f",
            "\u6587\u6848\u662f",
            "\u5199",
            "\u914d\u6587",
            "caption:"
        )
        val explicit = markers.firstNotNullOfOrNull { marker ->
            instruction.substringAfter(marker, missingDelimiterValue = "")
                .trim(' ', '\uff1a', ':', '\uff0c', ',', '\u3002')
                .takeIf { it.isNotBlank() && it != instruction }
        }.orEmpty()
        val fallback = instruction
            .replace("\u6296\u97f3", "")
            .replace("\u53d1\u4e00\u6761\u89c6\u9891", "")
            .replace("\u53d1\u4e2a\u89c6\u9891", "")
            .replace("\u53d1\u5e03\u89c6\u9891", "")
            .replace("\u53d1\u89c6\u9891", "")
            .replace("\u89c6\u9891", "")
            .trim(' ', '\uff1a', ':', '\uff0c', ',', '\u3002')
        val text = explicit.ifBlank { fallback }.ifBlank { "\u6211\u662fAI" }
        return text
            .replace("ai", "AI", ignoreCase = true)
            .take(60)
    }

    private fun isBilibiliSearchInstruction(instruction: String, appPackage: String): Boolean {
        return appPackage == SkillJsonRuleGenerator.BILIBILI_PACKAGE &&
            (instruction.contains("搜") ||
                instruction.contains("搜索") ||
                instruction.contains("第一条") ||
                instruction.contains("第一个") ||
                instruction.contains("视频") ||
                instruction.contains("点赞") ||
                instruction.contains("评论"))
    }

    private fun isGenericSearchInstruction(instruction: String): Boolean {
        return instruction.contains("搜") || instruction.contains("搜索")
    }

    private fun isPinduoduoPackage(appPackage: String?): Boolean {
        return appPackage.orEmpty().contains("pinduoduo", ignoreCase = true) ||
            appPackage == "com.xunmeng.pinduoduo"
    }

    private fun isPinduoduoCameraSearchSurface(
        state: com.macropilot.app.model.UiStateSample,
        visible: List<String>
    ): Boolean {
        if (!isPinduoduoPackage(state.appPackage)) return false
        val text = visible.joinToString(" ")
        val markers = listOf(
            "一键找同款",
            "找同款",
            "开启相机权限",
            "相机权限",
            "允许摄像头权限",
            "我的相册",
            "最近搜索",
            "历史浏览",
            "自动识别"
        )
        return markers.count { marker -> text.contains(marker) } >= 2
    }

    private fun isSearchEntryOnlyInstruction(instruction: String): Boolean {
        val text = instruction.lowercase(Locale.CHINA)
        val wantsSearch = text.contains("搜索") || text.contains("搜") || text.contains("search")
        if (!wantsSearch) return false
        val wantsEntry = text.contains("入口") ||
            text.contains("搜索页") ||
            text.contains("搜索框") ||
            text.contains("打开搜索") ||
            text.contains("进入搜索")
        val wantsResult = text.contains("结果") ||
            text.contains("第一个") ||
            text.contains("第一条") ||
            text.contains("打开第") ||
            text.contains("视频") ||
            text.contains("点赞") ||
            text.contains("评论") ||
            text.contains("发")
        return wantsEntry && !wantsResult
    }

    private fun verifySearchEntryOpened(
        appPackage: String,
        state: com.macropilot.app.model.UiStateSample,
        visible: List<String>,
        evidence: JSONObject
    ): GoalCheck {
        val editablePresent = state.nodes.any { it.editable }
        val searchTextVisible = visible.any { item ->
            item.contains("搜索") ||
                item.contains("搜一搜") ||
                item.contains("猜你想搜") ||
                item.contains("历史记录") ||
                item.contains("Search", ignoreCase = true)
        }
        val searchResourceVisible = state.nodes.any { node ->
            node.resourceId?.contains("search", ignoreCase = true) == true ||
                node.contentDesc?.contains("搜索") == true ||
                node.text?.contains("搜索") == true
        }
        val inTargetApp = appPackage.isBlank() || state.appPackage == appPackage
        val passed = inTargetApp && (editablePresent || searchTextVisible || searchResourceVisible)
        val blockerType = when {
            !inTargetApp -> "SEARCH_WRONG_APP"
            editablePresent || searchTextVisible || searchResourceVisible -> "NONE"
            else -> "SEARCH_ENTRY_MISSING"
        }
        val nextActionHint = when {
            !inTargetApp -> "先回到目标应用，再重找搜索入口。"
            editablePresent || searchTextVisible || searchResourceVisible -> ""
            else -> "回到首页或底部主分屏，点击搜索入口后再继续。"
        }
        return GoalCheck(
            mandatory = true,
            passed = passed,
            message = if (passed) {
                "已看到搜索入口/搜索框证据。"
            } else {
                "没有看到搜索入口、搜索框或搜索页提示，不能把打开搜索入口报告为成功。"
            },
            blockerType = blockerType,
            nextActionHint = nextActionHint,
            evidence = evidence
                .put("expected", "search_entry_opened")
                .put("editablePresent", editablePresent)
                .put("searchTextVisible", searchTextVisible)
                .put("searchResourceVisible", searchResourceVisible)
                .put("inTargetApp", inTargetApp)
        )
    }

    private fun isBilibiliCommentInstruction(instruction: String, appPackage: String): Boolean {
        return appPackage == SkillJsonRuleGenerator.BILIBILI_PACKAGE &&
            instruction.contains("评论")
    }

    private fun isGenericPostDraftInstruction(instruction: String, appPackage: String): Boolean {
        if (appPackage.isBlank()) return false
        if (isXiaohongshuPostInstruction(instruction, appPackage)) return false
        if (instruction.contains("\u641c") || instruction.contains("\u641c\u7d22")) return false
        val text = "${instruction.lowercase(Locale.CHINA)} ${appPackage.lowercase(Locale.US)}"
        val socialApp = listOf(
            "weibo",
            "hupu",
            "zhihu",
            "reddit",
            "instagram",
            "nga",
            "\u5fae\u535a",
            "\u864e\u6251",
            "\u77e5\u4e4e",
            "\u793e\u533a",
            "\u8bba\u575b"
        ).any { token -> text.contains(token) }
        val postIntent = listOf(
            "\u53d1\u4e00\u6761",
            "\u53d1\u4e2a",
            "\u53d1\u5e16",
            "\u5199\u5fae\u535a",
            "\u53d1\u5fae\u535a",
            "\u8349\u7a3f",
            "\u5e16\u5b50",
            "\u52a8\u6001",
            "post",
            "draft"
        ).any { token -> instruction.contains(token, ignoreCase = true) }
        return socialApp && postIntent
    }

    private fun isHupuExplicitPublishInstruction(instruction: String, appPackage: String): Boolean {
        val isHupu = appPackage.contains("hupu", ignoreCase = true) ||
            instruction.contains("\u864e\u6251")
        if (!isHupu) return false
        val looksLikeSearchOrOpenExistingPost = listOf(
            "\u641c\u7d22",
            "\u67e5\u627e",
            "\u627e\u5230",
            "\u6253\u5f00\u7b2c\u4e00\u4e2a",
            "\u6253\u5f00\u7b2c1\u4e2a",
            "\u6253\u5f00\u9996\u4e2a"
        ).any { marker -> instruction.contains(marker, ignoreCase = true) }
        val hasExplicitPublishVerb = listOf(
            "\u53d1\u51fa\u53bb",
            "\u53d1\u5e03",
            "\u63d0\u4ea4",
            "\u53d1\u4e00\u6761",
            "\u53d1\u4e2a\u5e16",
            "\u53d1\u4e00\u4e2a\u5e16",
            "\u53d1\u5e16\u5b50",
            "\u5e2e\u6211\u53d1",
            "\u6211\u8981\u53d1",
            "publish",
            "post a"
        ).any { marker -> instruction.contains(marker, ignoreCase = true) }
        if (looksLikeSearchOrOpenExistingPost && !hasExplicitPublishVerb) return false
        val noPublish = listOf(
            "\u4e0d\u8981\u70b9\u6700\u7ec8\u53d1\u5e03",
            "\u4e0d\u8981\u53d1\u5e03",
            "\u4e0d\u53d1\u5e03",
            "\u522b\u53d1\u5e03",
            "\u8349\u7a3f",
            "\u53ea\u5199",
            "\u5148\u5199"
        ).any { marker -> instruction.contains(marker, ignoreCase = true) }
        if (noPublish) return false
        return listOf(
            "\u53d1\u51fa\u53bb",
            "\u53d1\u5e03",
            "\u63d0\u4ea4",
            "\u53d1\u5e16",
            "\u53d1\u4e2a\u5e16\u5b50",
            "\u53d1\u4e00\u4e2a\u5e16\u5b50",
            "publish",
            "post"
        ).any { marker -> instruction.contains(marker, ignoreCase = true) }
    }

    private fun hupuPublishPostSteps(instruction: String, appPackage: String): List<JSONObject> {
        val content = extractGenericPostDraftContent(instruction).ifBlank { "\u6211\u662fAI" }
        val title = extractGenericPostTitle(instruction)
            .ifBlank { content.lineSequence().firstOrNull().orEmpty().take(40) }
            .ifBlank { "\u004d\u0061\u0063\u0072\u006f\u0050\u0069\u006c\u006f\u0074 \u6d4b\u8bd5" }
        val body = extractGenericPostBody(instruction)
            .ifBlank { content.lineSequence().drop(1).joinToString("\n").trim() }
            .ifBlank { content.takeIf { it != title }.orEmpty() }
        val packageName = appPackage.ifBlank { "com.hupu.games" }
        return listOf(
            JSONObject().put("type", "OpenApp").put("packageName", packageName).put("timeoutMs", 3000),
            JSONObject().put("type", "Wait").put("timeoutMs", 1800),
            JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", packageName).put("maxPasses", 4),
            JSONObject()
                .put("type", "FindEntryByBottomTabSweep")
                .put("appPackage", packageName)
                .put("entryAliases", JSONArray(listOf("\u53d1\u5e16", "\u53d1\u5e03", "\u5199\u5e16\u5b50", "\u5199\u5e16", "\u521b\u4f5c", "+")))
                .put("maxTabs", 5)
                .put("homeBackPresses", 3)
                .put("timeoutMs", 12_000),
            JSONObject().put("type", "Wait").put("timeoutMs", 1200),
            JSONObject()
                .put("type", "OpenHupuPostEditor")
                .put("timeoutMs", 2000),
            JSONObject().put("type", "Wait").put("timeoutMs", 1500),
            JSONObject().put("type", "EnsureHupuSectionSelected").put("appPackage", packageName).put("timeoutMs", 3000),
            JSONObject().put("type", "Wait").put("timeoutMs", 900),
            JSONObject()
                .put("type", "FillHupuPostEditor")
                .put("title", title)
                .put("body", body)
                .put("timeoutMs", 2600),
            JSONObject().put("type", "Wait").put("timeoutMs", 800),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "\u53d1\u5e03")
                .put("textAliases", JSONArray(listOf("\u53d1\u5e03", "\u53d1\u5e16", "\u63d0\u4ea4", "\u53d1\u8868")))
                .put("x", 0.86)
                .put("y", 0.92)
                .put("timeoutMs", 1600)
                .put("userConfirmedSubmitAction", true)
                .put("submitReason", "\u7528\u6237\u660e\u786e\u8981\u6c42\u5728\u864e\u6251\u53d1\u5e16"),
            JSONObject().put("type", "Wait").put("timeoutMs", 3000),
            JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", packageName).put("maxPasses", 2),
            JSONObject()
                .put("type", "VerifyHupuPostPublished")
                .put("text", title)
                .put("timeoutMs", 6_000)
        )
    }

    private fun genericThirdPartyPostDraftSteps(instruction: String, appPackage: String): List<JSONObject> {
        if (isHupuExplicitPublishInstruction(instruction, appPackage)) {
            return hupuPublishPostSteps(instruction, appPackage)
        }
        val content = extractGenericPostDraftContent(instruction)
            .ifBlank { "\u004d\u0061\u0063\u0072\u006f\u0050\u0069\u006c\u006f\u0074 \u81ea\u52a8\u5316\u6d4b\u8bd5\u8349\u7a3f\uff0c\u8bf7\u52ff\u53d1\u5e03" }
        return listOf(
            JSONObject().put("type", "OpenApp").put("packageName", appPackage).put("timeoutMs", 3000),
            JSONObject().put("type", "Wait").put("timeoutMs", 2200),
            JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", appPackage).put("maxPasses", 4),
            JSONObject()
                .put("type", "FindEntryByBottomTabSweep")
                .put("appPackage", appPackage)
                .put("entryAliases", JSONArray(postEntryAliasesForPackage(appPackage)))
                .put("maxTabs", 5)
                .put("homeBackPresses", 3)
                .put("timeoutMs", 12_000),
            JSONObject().put("type", "Wait").put("timeoutMs", 1600),
            JSONObject()
                .put("type", "TypeIntoFirstInput")
                .put("text", content)
                .put("timeoutMs", 2600),
            JSONObject().put("type", "Wait").put("timeoutMs", 900)
        )
    }

    private fun extractGenericPostDraftContent(instruction: String): String {
        val title = extractGenericPostTitle(instruction)
        val body = extractGenericPostBody(instruction)
        val content = when {
            title.isNotBlank() && body.isNotBlank() -> "$title\n$body"
            body.isNotBlank() -> body
            title.isNotBlank() -> title
            else -> instruction
                .replace("\u4e0d\u8981\u70b9\u6700\u7ec8\u53d1\u5e03", "")
                .replace("\u4e0d\u8981\u53d1\u5e03", "")
                .replace("\u522b\u53d1\u5e03", "")
                .trim()
        }
        return content.take(300)
    }

    private fun extractGenericPostTitle(instruction: String): String {
        return listOf("\u6807\u9898\u662f", "\u6807\u9898\u4e3a", "\u6807\u9898\uff1a", "\u6807\u9898:", "title:")
            .firstNotNullOfOrNull { marker ->
                instruction.substringAfter(marker, missingDelimiterValue = "")
                    .substringBefore("\uff0c\u6b63\u6587\u662f")
                    .substringBefore(",\u6b63\u6587\u662f")
                    .substringBefore("\u6b63\u6587\u662f")
                    .substringBefore("\uff0c\u5185\u5bb9\u662f")
                    .substringBefore(",\u5185\u5bb9\u662f")
                    .substringBefore("\u5185\u5bb9\u662f")
                    .substringBefore("\uff0c\u5185\u5bb9")
                    .substringBefore(",\u5185\u5bb9")
                    .substringBefore("\uff0c\u6b63\u6587")
                    .substringBefore(",\u6b63\u6587")
                    .substringBefore("\u5185\u5bb9\uff1a")
                    .substringBefore("\u5185\u5bb9:")
                    .trim()
                    .takeIf { it.isNotBlank() && it != instruction }
            }
            .orEmpty()
            .take(60)
    }

    private fun extractGenericPostBody(instruction: String): String {
        return listOf("\u6b63\u6587\u662f", "\u5185\u5bb9\u662f", "\u6587\u6848\u662f", "\u5185\u5bb9\uff1a", "\u5185\u5bb9:", "\u6b63\u6587\uff1a", "\u6b63\u6587:", "content:")
            .firstNotNullOfOrNull { marker ->
                instruction.substringAfter(marker, missingDelimiterValue = "")
                    .trim()
                    .takeIf { it.isNotBlank() && it != instruction }
            }
            ?.substringBefore("\uff0c\u8bf7\u53d1\u51fa\u53bb")
            ?.substringBefore(",\u8bf7\u53d1\u51fa\u53bb")
            ?.substringBefore("\u8bf7\u53d1\u51fa\u53bb")
            ?.substringBefore("\uff0c\u53d1\u51fa\u53bb")
            ?.substringBefore(",\u53d1\u51fa\u53bb")
            ?.substringBefore("\u53d1\u51fa\u53bb")
            ?.substringBefore("\uff0c\u5e76\u53d1\u5e03")
            ?.substringBefore(",\u5e76\u53d1\u5e03")
            ?.substringBefore("\uff0c\u7136\u540e\u53d1\u5e03")
            ?.substringBefore(",\u7136\u540e\u53d1\u5e03")
            ?.substringBefore("\uff0c\u4e0d\u8981")
            ?.substringBefore(",\u4e0d\u8981")
            ?.substringBefore("\uff0c\u4e0d\u53d1")
            ?.substringBefore(",\u4e0d\u53d1")
            ?.substringBefore("\uff0c\u522b")
            ?.substringBefore(",\u522b")
            ?.trim()
            .orEmpty()
            .take(240)
    }

    private fun isXiaohongshuPostInstruction(instruction: String, appPackage: String): Boolean {
        if (appPackage != SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE) return false
        if (instruction.contains("搜") || instruction.contains("搜索")) return false
        return instruction.contains("小红书") &&
            (instruction.contains("发") ||
                instruction.contains("发布") ||
                instruction.contains("笔记") ||
                instruction.contains("帖子") ||
                instruction.contains("文案") ||
                instruction.contains("草稿") ||
                instruction.contains("写"))
    }

    private fun isXiaohongshuExplicitPublishInstruction(instruction: String, appPackage: String): Boolean {
        if (!isXiaohongshuPostInstruction(instruction, appPackage)) return false
        val noPublish = listOf("不要发布", "不发布", "别发布", "不要发出去", "不发出去", "草稿", "只写", "先保存")
            .any { marker -> instruction.contains(marker) }
        if (noPublish) return false
        return listOf("发布", "发出去", "确认发布", "现在发", "发一条", "发个")
            .any { marker -> instruction.contains(marker) }
    }

    private fun xiaohongshuPostDraftSteps(instruction: String): List<JSONObject> {
        if (isXiaohongshuExplicitPublishInstruction(instruction, SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE)) {
            return xiaohongshuPublishPostSteps(instruction)
        }
        val body = extractXiaohongshuBody(instruction).ifBlank { "MacroPilot 自动化测试草稿，请勿发布" }
        val title = extractXiaohongshuTitle(instruction, body)
        if (isXiaohongshuDraftEditorState(inspector.currentState())) {
            return listOf(
                JSONObject()
                    .put("type", "TypeAtCoordinate")
                    .put("x", 0.50)
                    .put("y", 0.435)
                    .put("text", "$title\n$body")
                    .put("focusDelayMs", 700)
                    .put("timeoutMs", 3200),
                JSONObject().put("type", "Wait").put("timeoutMs", 900)
            )
        }
        return listOf(
            JSONObject().put("type", "OpenApp").put("packageName", SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE),
            JSONObject().put("type", "Wait").put("timeoutMs", 2400),
            JSONObject().put("type", "ClickCoordinate").put("x", 0.50).put("y", 0.935).put("timeoutMs", 1000),
            JSONObject().put("type", "Wait").put("timeoutMs", 1800),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "文字")
                .put("x", 0.50)
                .put("y", 0.86)
                .put("timeoutMs", 1200),
            JSONObject().put("type", "Wait").put("timeoutMs", 1200),
            JSONObject()
                .put("type", "TypeAtCoordinate")
                .put("x", 0.50)
                .put("y", 0.435)
                .put("text", "$title\n$body")
                .put("focusDelayMs", 700)
                .put("timeoutMs", 3200),
            JSONObject().put("type", "Wait").put("timeoutMs", 800)
        )
    }

    private fun xiaohongshuPublishPostSteps(instruction: String): List<JSONObject> {
        val body = extractXiaohongshuBody(instruction).ifBlank { "我是ai" }
        val title = extractXiaohongshuTitle(instruction, body)
        val text = if (title == body) body else "$title\n$body"
        return listOf(
            JSONObject()
                .put("type", "EnsureXiaohongshuDraftEditor")
                .put("packageName", SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE)
                .put("timeoutMs", 6_000),
            JSONObject()
                .put("type", "TypeAtCoordinate")
                .put("x", 0.50)
                .put("y", 0.435)
                .put("text", text)
                .put("replace", true)
                .put("focusDelayMs", 700)
                .put("timeoutMs", 3200),
            JSONObject().put("type", "Wait").put("timeoutMs", 900),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "下一步")
                .put("x", 0.86)
                .put("y", 0.08)
                .put("timeoutMs", 1500),
            JSONObject().put("type", "Wait").put("timeoutMs", 2_200),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "发布")
                .put("x", 0.82)
                .put("y", 0.92)
                .put("timeoutMs", 1500)
                .put("userConfirmedSubmitAction", true)
                .put("submitReason", "用户明确要求发布小红书内容"),
            JSONObject().put("type", "Wait").put("timeoutMs", 4_000)
        )
    }

    private fun xiaohongshuDraftRecoverySteps(instruction: String): List<JSONObject> {
        val body = extractXiaohongshuBody(instruction).ifBlank { "MacroPilot 自动化测试草稿，请勿发布" }
        val title = extractXiaohongshuTitle(instruction, body)
        if (isXiaohongshuDraftEditorState(inspector.currentState())) {
            return listOf(
                JSONObject()
                    .put("type", "TypeAtCoordinate")
                    .put("x", 0.50)
                    .put("y", 0.435)
                    .put("text", "$title\n$body")
                    .put("focusDelayMs", 700)
                    .put("timeoutMs", 3200),
                JSONObject().put("type", "Wait").put("timeoutMs", 900)
            )
        }
        return listOf(
            JSONObject().put("type", "OpenApp").put("packageName", SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE),
            JSONObject().put("type", "Wait").put("timeoutMs", 2200),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "首页")
                .put("x", 0.10)
                .put("y", 0.935)
                .put("timeoutMs", 1000),
            JSONObject().put("type", "Wait").put("timeoutMs", 900),
            JSONObject()
                .put("type", "ClickCoordinate")
                .put("x", 0.50)
                .put("y", 0.935)
                .put("timeoutMs", 1000),
            JSONObject().put("type", "Wait").put("timeoutMs", 1800),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "文字")
                .put("x", 0.50)
                .put("y", 0.86)
                .put("timeoutMs", 1200),
            JSONObject().put("type", "Wait").put("timeoutMs", 1100),
            JSONObject()
                .put("type", "TypeAtCoordinate")
                .put("x", 0.50)
                .put("y", 0.435)
                .put("text", "$title\n$body")
                .put("focusDelayMs", 700)
                .put("timeoutMs", 3200),
            JSONObject().put("type", "Wait").put("timeoutMs", 900)
        )
    }

    private fun isXiaohongshuDraftEditorState(state: com.macropilot.app.model.UiStateSample?): Boolean {
        if (state?.appPackage != SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE) return false
        val visible = state.nodes.mapNotNull { node ->
            listOfNotNull(node.text, node.contentDesc).joinToString(" ").takeIf { it.isNotBlank() }
        }
        val markerHits = listOf("写想法", "说点什么", "标题", "正文", "发布笔记", "下一步", "保存草稿", "谁可以看")
            .count { marker -> visible.any { it.contains(marker) } }
        val editWindow = state.windowClassName?.contains("edit", ignoreCase = true) == true
        return editWindow || markerHits >= 2
    }

    private fun bilibiliCommentDraftSteps(instruction: String): List<JSONObject> {
        val query = extractSearchQuery(instruction).ifBlank { "猫和老鼠" }
        val comment = extractCommentText(instruction).ifBlank { "MacroPilot 测试评论草稿" }
        return listOf(
            JSONObject().put("type", "OpenApp").put("packageName", SkillJsonRuleGenerator.BILIBILI_PACKAGE),
            JSONObject().put("type", "ClickVisibleText").put("text", "搜索"),
            JSONObject().put("type", "TypeIntoFirstInput").put("text", query),
            JSONObject().put("type", "ClickSelector").put("selector", "[text='搜索'][clickable=true]"),
            JSONObject().put("type", "Wait").put("timeoutMs", 1600),
            JSONObject().put("type", "ClickCoordinate").put("x", 0.50).put("y", 0.30),
            JSONObject().put("type", "Wait").put("timeoutMs", 3200),
            JSONObject().put("type", "ClickCoordinate").put("x", 0.58).put("y", 0.92),
            JSONObject().put("type", "Wait").put("timeoutMs", 900),
            JSONObject().put("type", "ClickCoordinate").put("x", 0.44).put("y", 0.93),
            JSONObject().put("type", "Wait").put("timeoutMs", 700),
            JSONObject().put("type", "TypeIntoFirstInput").put("text", comment)
        )
    }

    private fun genericThirdPartySearchSteps(instruction: String, appPackage: String): List<JSONObject> {
        val query = extractSearchQuery(instruction).ifBlank { "猫和老鼠" }
        val points = genericSearchProfile(appPackage)
        return listOf(
            JSONObject().put("type", "OpenApp").put("packageName", appPackage),
            JSONObject().put("type", "Wait").put("timeoutMs", points.openWaitMs),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "搜索")
                .put("x", points.searchEntryX)
                .put("y", points.searchEntryY)
                .put("timeoutMs", 1200),
            JSONObject().put("type", "Wait").put("timeoutMs", 700),
            JSONObject().put("type", "TypeIntoFirstInput").put("text", query).put("timeoutMs", 2200),
            JSONObject().put("type", "Wait").put("timeoutMs", 500),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "搜索")
                .put("x", points.submitX)
                .put("y", points.submitY)
                .put("timeoutMs", 1200),
            JSONObject().put("type", "Wait").put("timeoutMs", 1600)
        )
    }

    private fun pinduoduoTextSearchRecoverySteps(instruction: String, appPackage: String): List<JSONObject> {
        val query = extractSearchQuery(instruction).ifBlank { "纸巾" }
        return listOf(
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "取消")
                .put("textAliases", JSONArray(listOf("取消", "关闭")))
                .put("x", 0.34)
                .put("y", 0.61)
                .put("timeoutMs", 900),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "关闭")
                .put("textAliases", JSONArray(listOf("关闭", "取消")))
                .put("x", 0.90)
                .put("y", 0.47)
                .put("timeoutMs", 900),
            JSONObject().put("type", "Back").put("timeoutMs", 900),
            JSONObject().put("type", "Wait").put("timeoutMs", 800),
            JSONObject().put("type", "Back").put("timeoutMs", 900),
            JSONObject().put("type", "Wait").put("timeoutMs", 700),
            JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", appPackage).put("maxPasses", 5),
            JSONObject()
                .put("type", "OpenApp")
                .put("packageName", appPackage)
                .put("launcherRoot", true)
                .put("timeoutMs", 5_000)
                .put("retryTimeoutMs", 5_000),
            JSONObject().put("type", "Wait").put("timeoutMs", 2200),
            JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", appPackage).put("maxPasses", 5),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "搜索")
                .put("textAliases", JSONArray(listOf("搜索", "搜一搜", "搜索商品", "搜索商品或店铺")))
                .put("x", 0.46)
                .put("y", 0.075)
                .put("timeoutMs", 1300),
            JSONObject().put("type", "Wait").put("timeoutMs", 900),
            JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", appPackage).put("maxPasses", 4),
            JSONObject()
                .put("type", "TypeAtCoordinate")
                .put("x", 0.42)
                .put("y", 0.075)
                .put("text", query)
                .put("replace", true)
                .put("focusDelayMs", 650)
                .put("timeoutMs", 2600),
            JSONObject().put("type", "Wait").put("timeoutMs", 600),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "搜索")
                .put("textAliases", JSONArray(listOf("搜索", "搜 索")))
                .put("x", 0.90)
                .put("y", 0.075)
                .put("timeoutMs", 1400),
            JSONObject().put("type", "Wait").put("timeoutMs", 2600),
            JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", appPackage).put("maxPasses", 3)
        )
    }

    private fun genericSearchProfile(appPackage: String): GenericSearchProfile {
        return when {
            isPinduoduoPackage(appPackage) ->
                GenericSearchProfile(searchEntryX = 0.46, searchEntryY = 0.075, submitX = 0.90, submitY = 0.075, openWaitMs = 2200)
            appPackage.contains("xingin", ignoreCase = true) ->
                GenericSearchProfile(searchEntryX = 0.52, searchEntryY = 0.075, submitX = 0.90, submitY = 0.075, openWaitMs = 2200)
            appPackage.contains("aweme", ignoreCase = true) || appPackage.contains("douyin", ignoreCase = true) ->
                GenericSearchProfile(searchEntryX = 0.92, searchEntryY = 0.075, submitX = 0.90, submitY = 0.075, openWaitMs = 2200)
            appPackage.contains("zhihu", ignoreCase = true) ->
                GenericSearchProfile(searchEntryX = 0.50, searchEntryY = 0.080, submitX = 0.90, submitY = 0.080, openWaitMs = 2000)
            appPackage.contains("weibo", ignoreCase = true) ->
                GenericSearchProfile(searchEntryX = 0.50, searchEntryY = 0.085, submitX = 0.90, submitY = 0.085, openWaitMs = 2000)
            appPackage.contains("qqmusic", ignoreCase = true) || appPackage.contains("netease", ignoreCase = true) ->
                GenericSearchProfile(searchEntryX = 0.50, searchEntryY = 0.085, submitX = 0.90, submitY = 0.085, openWaitMs = 1800)
            else ->
                GenericSearchProfile(searchEntryX = 0.50, searchEntryY = 0.080, submitX = 0.90, submitY = 0.080, openWaitMs = 1800)
        }
    }

    private fun isWechatCurrentChatInstruction(instruction: String, appPackage: String): Boolean {
        return appPackage == SkillJsonRuleGenerator.WECHAT_PACKAGE &&
            (instruction.contains("发") || instruction.contains("发送") || instruction.contains("输入"))
    }

    private fun isWechatMomentPrivateInstruction(instruction: String, appPackage: String): Boolean {
        if (appPackage != SkillJsonRuleGenerator.WECHAT_PACKAGE && !instruction.contains("\u5fae\u4fe1")) return false
        val wantsMoment = instruction.contains("\u670b\u53cb\u5708")
        val privateOnly = instruction.contains("\u4ec5\u81ea\u5df1") ||
            instruction.contains("\u81ea\u5df1\u53ef\u89c1") ||
            instruction.contains("\u79c1\u5bc6")
        val publish = instruction.contains("\u53d1") ||
            instruction.contains("\u53d1\u8868") ||
            instruction.contains("\u53d1\u5e03") ||
            instruction.contains("post", ignoreCase = true)
        return wantsMoment && privateOnly && publish
    }

    private fun extractWechatMomentContent(raw: String): String {
        val afterMomentColon = listOf(
            "\u670b\u53cb\u5708\uff1a",
            "\u670b\u53cb\u5708:",
            "\u670b\u53cb\u5708 \uff1a",
            "\u670b\u53cb\u5708 :"
        ).firstNotNullOfOrNull { marker ->
            raw.substringAfter(marker, missingDelimiterValue = "")
                .takeIf { it.isNotBlank() && it != raw }
        }
        val markers = listOf(
            "\u5185\u5bb9\u662f",
            "\u6587\u6848\u662f",
            "\u5199",
            "\u8bf4",
            "\u53d1"
        )
        val explicit = afterMomentColon ?: markers.firstNotNullOfOrNull { marker ->
            raw.substringAfter(marker, missingDelimiterValue = "")
                .trim(' ', '\uff1a', ':', '\uff0c', ',', '\u3002')
                .takeIf { it.isNotBlank() && it != raw }
        }.orEmpty()
        return explicit
            .substringBefore("\uff0c\u5fc5\u987b")
            .substringBefore(",\u5fc5\u987b")
            .substringBefore("\uff1b\u5fc5\u987b")
            .substringBefore(";\u5fc5\u987b")
            .substringBefore("\uff0c\u5e76\u9a8c\u8bc1")
            .substringBefore(",\u5e76\u9a8c\u8bc1")
            .substringBefore("\u5e76\u9a8c\u8bc1")
            .substringBefore("\uff0c\u4e0d\u80fd")
            .substringBefore(",\u4e0d\u80fd")
            .substringBefore("\u4e0d\u80fd")
            .substringBefore("\uff0c\u8bbe\u7f6e")
            .substringBefore(",\u8bbe\u7f6e")
            .substringBefore("\u8bbe\u7f6e\u4e3a")
            .substringBefore("\uff0c\u7136\u540e")
            .substringBefore(",\u7136\u540e")
            .substringBefore("\u7136\u540e")
            .substringBefore("\uff0c\u5e76")
            .substringBefore(",\u5e76")
            .substringBefore("\u5e76")
            .replace("\u4e00\u6761\u670b\u53cb\u5708", "")
            .replace("\u670b\u53cb\u5708", "")
            .replace("\u4ec5\u81ea\u5df1\u53ef\u89c1", "")
            .replace("\u4ec5\u81ea\u5df1", "")
            .replace("\u81ea\u5df1\u53ef\u89c1", "")
            .replace("\u8bd5\u4e00\u4e0b", "")
            .replace("\u5fc5\u987b\u771f\u6b63\u70b9\u51fb\u53d1\u8868", "")
            .replace("\u5e76\u9a8c\u8bc1\u5df2\u7ecf\u63d0\u4ea4", "")
            .replace("\u4e0d\u80fd\u53ea\u505c\u5728\u8349\u7a3f\u9875", "")
            .trim(' ', '\uff1a', ':', '\uff0c', ',', '\u3002')
            .ifBlank { "\u6211\u662fAI" }
            .take(80)
    }

    private fun extractSearchQuery(raw: String): String {
        val trimmed = raw.trim()
        val explicit = listOf("搜索", "搜").firstNotNullOfOrNull { marker ->
            trimmed.substringAfter(marker, missingDelimiterValue = "").trim().takeIf { it.isNotBlank() && it != trimmed }
        }
        return (explicit ?: trimmed)
            .substringBefore("给")
            .substringBefore("并打开")
            .substringBefore("然后打开")
            .substringBefore("打开第")
            .substringBefore("打开第一")
            .substringBefore("第一条")
            .substringBefore("第一个")
            .substringBefore("第1个")
            .substringBefore("第1条")
            .substringBefore("点赞")
            .substringBefore("点个赞")
            .substringBefore("视频点赞")
            .substringBefore("，点")
            .substringBefore("，打开")
            .substringBefore("，在")
            .substringBefore("，写")
            .substringBefore(" 评论")
            .substringBefore("评论框")
            .replace("哔哩哔哩", "")
            .replace("哔哩", "")
            .replace("B站", "")
            .replace("bilibili", "", ignoreCase = true)
            .replace("搜索", "")
            .trim()
    }

    private fun extractMessage(raw: String): String {
        return Regex("(?:发|发送|输入|写入)\\s*(.+)$")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: Regex("([0-9a-zA-Z_\\-]{1,64})").findAll(raw).lastOrNull()?.value.orEmpty()
    }

    private fun extractXiaohongshuBody(raw: String): String {
        val markers = listOf(
            "正文是",
            "内容是",
            "文案是",
            "正文：",
            "内容：",
            "文案：",
            "写成",
            "写一条",
            "写",
            "发布"
        )
        val explicit = markers.firstNotNullOfOrNull { marker ->
            raw.substringAfter(marker, missingDelimiterValue = "")
                .trim(' ', '：', ':', '，', ',', '。')
                .takeIf { it.isNotBlank() && it != raw }
        }
        val afterAppName = raw.substringAfter("小红书", missingDelimiterValue = "")
            .trim(' ', '：', ':', '，', ',', '。')
            .takeIf { it.isNotBlank() && it != raw }
        return (explicit ?: afterAppName.orEmpty())
            .substringBefore("，不要发布")
            .substringBefore("不要发布")
            .substringBefore("，先保存")
            .substringBefore("先保存")
            .replace("发一条", "")
            .replace("发个", "")
            .replace("发布", "")
            .replace("里", "")
            .replace("笔记", "")
            .replace("帖子", "")
            .replace("草稿", "")
            .trim(' ', '：', ':', '，', ',', '。')
    }

    private fun extractXiaohongshuTitle(raw: String, body: String): String {
        val markers = listOf("标题是", "标题：", "标题:")
        val explicit = markers.firstNotNullOfOrNull { marker ->
            raw.substringAfter(marker, missingDelimiterValue = "")
                .substringBefore("正文")
                .substringBefore("内容")
                .substringBefore("文案")
                .trim(' ', '：', ':', '，', ',', '。')
                .takeIf { it.isNotBlank() && it != raw }
        }
        return explicit
            ?: body.replace("\n", " ").trim().take(18).ifBlank { "MacroPilot 测试草稿" }
    }

    private fun extractCommentText(raw: String): String {
        val markers = listOf("评论草稿", "评论内容", "写评论", "写")
        val found = markers.firstNotNullOfOrNull { marker ->
            raw.substringAfter(marker, missingDelimiterValue = "")
                .trim(' ', '：', ':', '，', ',', '。')
                .takeIf { it.isNotBlank() && it != raw }
        }
        return found
            ?.substringBefore("，不要发布")
            ?.substringBefore("不要发布")
            ?.replace("草稿", "")
            ?.trim()
            .orEmpty()
    }

    private fun inferIntentId(instruction: String): String {
        return when {
            instruction.contains("搜索") || instruction.contains("搜") -> "search_in_app"
            instruction.contains("打开") || instruction.contains("启动") -> "open_app"
            instruction.contains("点击") || instruction.contains("点") -> "click"
            instruction.contains("输入") -> "type_text"
            instruction.contains("返回") -> "back"
            else -> "custom"
        }
    }

    private fun appNameForPackage(packageName: String): String {
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun factoryMeta(source: String, instruction: String): JSONObject {
        return JSONObject()
            .put("source", source)
            .put("instruction", instruction)
            .put("traceIds", JSONArray())
            .put("sampleCount", 0)
            .put("dryRunStatus", "PENDING")
            .put("testRuns", 0)
            .put("successRate", 0.0)
            .put("promotionStatus", "NOT_PROMOTED")
            .put("createdAt", System.currentTimeMillis())
    }

    private fun endpointHost(endpoint: String): String {
        return runCatching { URI(endpoint).host }.getOrNull() ?: endpoint
    }

    private fun returnToMacroPilot() {
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent().setPackage(context.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

private data class AiPlanResult(
    val jobFile: File,
    val root: JSONObject,
    val intentDraft: JSONObject,
    val planSteps: List<JSONObject>,
    val candidates: List<JSONObject>,
    val status: String,
    val httpStatus: Int?,
    val model: String
)

private data class AiFollowUpResult(
    val jobFile: File,
    val decision: JSONObject,
    val planSteps: List<JSONObject>,
    val candidates: List<JSONObject>,
    val goalCompleted: Boolean,
    val diagnosis: String,
    val status: String,
    val httpStatus: Int?,
    val model: String
)

private data class AiPlanReviewResult(
    val jobFile: File,
    val decision: JSONObject,
    val planSteps: List<JSONObject>,
    val usePatchedSteps: Boolean,
    val status: String,
    val httpStatus: Int?,
    val model: String
)

private data class AiFollowUpExecution(
    val aiJobFile: File,
    val runtimeReportFile: File?,
    val success: Boolean,
    val executed: Boolean
)

private data class GoalCheck(
    val mandatory: Boolean,
    val passed: Boolean,
    val message: String,
    val evidence: JSONObject,
    val blockerType: String = "NONE",
    val nextActionHint: String = ""
)

private data class BilibiliVideoTarget(
    val index: Int,
    val x: Double,
    val y: Double,
    val sortY: Int,
    val score: Int,
    val evidence: JSONObject
)

private data class DirectPlanBuild(
    val skillFiles: List<File>,
    val macroFile: File
)

private data class DryRunSummary(
    val passed: Int,
    val total: Int,
    val lastReportPath: String?
)

private data class GenericSearchProfile(
    val searchEntryX: Double,
    val searchEntryY: Double,
    val submitX: Double,
    val submitY: Double,
    val openWaitMs: Long
)


