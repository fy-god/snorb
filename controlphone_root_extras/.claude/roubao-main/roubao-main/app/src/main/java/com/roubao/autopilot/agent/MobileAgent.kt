package com.roubao.autopilot.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.roubao.autopilot.App
import com.roubao.autopilot.controller.AppScanner
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.data.ExecutionStep
import com.roubao.autopilot.skills.ExecuteRoute
import com.roubao.autopilot.skills.AtomicSkillEngine
import com.roubao.autopilot.skills.FastCommands
import com.roubao.autopilot.skills.SkillTemplateSaver
import com.roubao.autopilot.skills.FeatureFlags
import com.roubao.autopilot.skills.IntentParser
import com.roubao.autopilot.skills.RecordedSkillStep
import com.roubao.autopilot.skills.SkillManager
import com.roubao.autopilot.skills.SkillRecorder
import com.roubao.autopilot.skills.SkillResult
import com.roubao.autopilot.skills.SkillStepExecutor
import com.roubao.autopilot.skills.SkillWorkflowAssembler
import com.roubao.autopilot.skills.UiObserver
import com.roubao.autopilot.skills.Workflow
import com.roubao.autopilot.macro.ai.NoopAiAssist
import com.roubao.autopilot.macro.core.MacroComposer
import com.roubao.autopilot.macro.core.MacroEngine
import com.roubao.autopilot.macro.core.MacroEventLogger
import com.roubao.autopilot.macro.core.MacroExecutor
import com.roubao.autopilot.macro.core.MacroIntentParser
import com.roubao.autopilot.macro.core.MacroRouter
import com.roubao.autopilot.macro.core.MacroVerifier
import com.roubao.autopilot.macro.core.UiElementResolver
import com.roubao.autopilot.macro.core.UiScreenClassifier
import com.roubao.autopilot.macro.store.AppUiRepository
import com.roubao.autopilot.macro.store.MacroTemplateRepository
import com.roubao.autopilot.ui.OverlayService
import com.roubao.autopilot.vlm.GUIOwlClient
import com.roubao.autopilot.vlm.MAIUIClient
import com.roubao.autopilot.vlm.VLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * 运行模式 — 决定本地 skill 和 AI 之间的路由策略
 */
enum class RunMode {
    AUTO,       // 自动判断：本地可执行 skill 优先，否则 AI
    AI_ONLY,    // 用户明确要求 AI，禁止本地 skill 兜底
    LOCAL_ONLY  // 用户明确要求只用本地 skill，不调 AI
}

/**
 * Mobile Agent 主循环 - 移植自 MobileAgent-v3
 *
 * 新增 Skill 层支持：
 * - 快速路径：高置信度 delegation Skill 直接执行
 * - 增强模式：GUI 自动化 Skill 提供上下文指导
 *
 * 支持三种 VLM 模式 + 三种运行模式：
 * - OpenAI 兼容模式：使用 VLMClient (Manager → Executor → Reflector)
 * - GUI-Owl 模式：使用 GUIOwlClient (直接返回操作指令)
 * - MAI-UI 模式：使用 MAIUIClient (专用 prompt 和对话历史)
 * - RunMode.AUTO / AI_ONLY / LOCAL_ONLY 控制本地 vs AI 路由
 */
class MobileAgent(
    private val vlmClient: VLMClient?,
    private val controller: DeviceController,
    private val context: Context,
    private val guiOwlClient: GUIOwlClient? = null,  // GUI-Owl 专用客户端
    private val maiuiClient: MAIUIClient? = null     // MAI-UI 专用客户端
) {
    // 是否使用 GUI-Owl 模式
    private val useGUIOwlMode: Boolean = guiOwlClient != null
    // 是否使用 MAI-UI 模式
    private val useMAIUIMode: Boolean = maiuiClient != null
    // App 扫描器 (使用 App 单例中的实例)
    private val appScanner: AppScanner = App.getInstance().appScanner
    private val manager = Manager()
    private val executor = Executor()
    private val reflector = ActionReflector()
    private val notetaker = Notetaker()

    // Skill 管理器
    private val skillManager: SkillManager? = try {
        SkillManager.getInstance().also {
            println("[肉包] SkillManager 已加载，共 ${it.getAllSkills().size} 个 Skills")
            // 设置 VLM 客户端用于意图匹配（仅在 OpenAI 兼容模式下）
            vlmClient?.let { client -> it.setVLMClient(client) }
        }
    } catch (e: Exception) {
        println("[肉包] SkillManager 加载失败: ${e.message}")
        null
    }

    // 旧版本地路径开关 — 默认关闭，只用新 SkillEngine
    private val enableLegacyLocalFlow = false

    // 状态流
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    /**
     * 执行指令
     */
    suspend fun runInstruction(
        instruction: String,
        maxSteps: Int = 25,
        useNotetaker: Boolean = false
    ): AgentResult {
        log("开始执行: $instruction")

        // === 运行模式检测 ===
        val runMode = detectRunMode(instruction)
        log("[RUN_MODE] $runMode")

        // AI_ONLY: 跳过所有本地 skill，直接走 VLM。VLM 不可用时直接失败，不 fallback
        if (runMode == RunMode.AI_ONLY && !isAiAvailable()) {
            log("错误: 用户要求 AI 模式，但 AI 不可用")
            updateState { copy(isRunning = false, isCompleted = false) }
            return AgentResult(success = false, message = "用户要求使用 AI，但 AI 配置不可用，请检查 API Key / Base URL / Model")
        }

        val skipLocalFlow = runMode == RunMode.AI_ONLY

        // 根据模式选择执行路径
        if (runMode == RunMode.AI_ONLY && useGUIOwlMode && guiOwlClient != null) {
            log("使用 GUI-Owl 模式")
            return runInstructionWithGUIOwl(instruction, maxSteps)
        }

        if (runMode == RunMode.AI_ONLY && useMAIUIMode && maiuiClient != null) {
            log("使用 MAI-UI 模式")
            return runInstructionWithMAIUI(instruction, maxSteps)
        }

        // 重置上次动作序列 (供 Workflow 录制)
        updateState { copy(lastActions = emptyList()) }

        // ================================================================
        // 新架构: FastCommands → AtomicSkillEngine → AI assist (局部) → VLM (仅AI_ONLY)
        // ================================================================
        // 核心原则:
        //   - 本地 AtomicSkillEngine 是执行主体
        //   - AI 只能做局部辅助（规划/感知/修复），不能持续接管
        //   - AUTO 模式禁止直接进入完整 VLM 自动化
        //   - 只有 AI_ONLY 模式才能走完整 VLM
        // ================================================================

        if (runMode == RunMode.AI_ONLY) {
            if (!isAiAvailable()) {
                updateState { copy(isRunning = false, isCompleted = false) }
                return AgentResult(success = false, message = "用户要求 AI 模式，但 AI 不可用")
            }
            log("[AI_ONLY] 直接进入完整 VLM 自动化")
            return runFullVlmAutomation(instruction, maxSteps, useNotetaker)
        }

        // AUTO / LOCAL_ONLY: 本地 AtomicSkillEngine 为主体
        if (!skipLocalFlow) {
            // P0: 本地命令词直通
            val fastMatch = FastCommands.match(instruction)
            if (fastMatch != null) {
                log("⚡ 本地命令词命中: ${fastMatch.type}")
                val infoPool = InfoPool(instruction = instruction)
                controller.getScreenSize().let { (w, h) ->
                    infoPool.screenWidth = w; infoPool.screenHeight = h
                }
                executeAction(fastMatch.action, infoPool)
                updateState { copy(isRunning = false, isCompleted = true) }
                return AgentResult(success = true, message = "本地命令: ${instruction}")
            }

            // AtomicSkillEngine: 拆子目标 → 原子 skill 拼装 → AI 局部辅助
            if (!enableLegacyLocalFlow) {
            val macroLogger = MacroEventLogger { msg -> log(msg) }
            val macroUiObserver = UiObserver(controller)
            val macroVerifier = MacroVerifier()
            val screenClassifier = UiScreenClassifier()
            val elementResolver = UiElementResolver(controller, macroUiObserver, macroLogger)
            val macroExecutor = MacroExecutor(
                controller = controller,
                uiObserver = macroUiObserver,
                elementResolver = elementResolver,
                verifier = macroVerifier,
                screenClassifier = screenClassifier,
                logger = macroLogger
            )
            val macroEngine = MacroEngine(
                intentParser = MacroIntentParser(),
                uiObserver = macroUiObserver,
                appUiRepo = AppUiRepository(context),
                templateRepo = MacroTemplateRepository(context),
                composer = MacroComposer(screenClassifier),
                executor = macroExecutor,
                verifier = macroVerifier,
                aiAssist = NoopAiAssist,
                eventLogger = macroLogger
            )
            val macroRouter = MacroRouter(
                engine = macroEngine,
                fullAiRunner = { command -> runFullVlmAutomation(command, maxSteps, useNotetaker) },
                isAiAvailable = { isAiAvailable() },
                allowFullAiFallback = { FeatureFlags.ALLOW_FULL_AI_FALLBACK },
                eventLogger = macroLogger
            )
            val macroResult = macroRouter.run(instruction, runMode)
            updateState { copy(isRunning = false, isCompleted = macroResult.success) }
            return macroResult
            }

            val engine = AtomicSkillEngine(controller, null, null) { msg -> log(msg) }
            val engineResult = engine.execute(instruction)

            if (engineResult.success) {
                log("[ENGINE] 完成: ${engineResult.message}")
                // 保存 SkillTemplate（替代旧 Workflow 录制）
                try {
                    SkillTemplateSaver.saveFromRecords(instruction, engine.records, context) { msg -> log(msg) }
                } catch (_: Exception) { /* 保存失败不影响主流程 */ }
                updateState { copy(isRunning = false, isCompleted = true) }
                return engineResult.toAgentResult()
            }

            log("[ENGINE] 不可完成: status=${engineResult.status} ${engineResult.message}")

            // AUTO 模式: 引擎失败后，检查是否允许完整 AI 兜底
            if (runMode == RunMode.AUTO) {
                val allowFullAi = FeatureFlags.ALLOW_FULL_AI_FALLBACK
                if (allowFullAi && isAiAvailable()) {
                    log("[AUTO] allowFullAiFallback=true，降级完整 VLM")
                    return runFullVlmAutomation(instruction, maxSteps, useNotetaker)
                }
                log("[AUTO] 禁止完整 AI 接管（allowFullAiFallback=false）")
            }

            // LOCAL_ONLY 或 AUTO 不允许 AI: 到此为止
            updateState { copy(isRunning = false, isCompleted = false) }
            return AgentResult(
                success = false,
                message = "本地原子 skill 无法完成: ${engineResult.message}" +
                    if (runMode == RunMode.AUTO) "（AUTO 模式禁止完整 AI 接管）" else ""
            )
        }

        updateState { copy(isRunning = false, isCompleted = false) }
        return AgentResult(success = false, message = "No execution route available")
    }

    /**
     * 完整 VLM 自动化 — 仅 AI_ONLY 模式或 AUTO 模式下显式允许 AI 兜底时调用。
     * 使用 Manager → Executor → Reflector 三阶段循环。
     */
    private suspend fun runFullVlmAutomation(
        instruction: String,
        maxSteps: Int,
        useNotetaker: Boolean
    ): AgentResult {
        val parsedIntent = IntentParser().parse(instruction)

        // OpenAI 兼容模式需要 VLMClient
        if (vlmClient == null) {
            log("错误: VLMClient 未初始化")
            return AgentResult(success = false, message = "VLMClient 未初始化")
        }

        log("使用 OpenAI 兼容模式")

        // 使用 LLM 匹配 Skill，生成上下文信息给 Agent（不执行任何操作）
        log("正在分析意图...")
        val skillContext = skillManager?.generateAgentContextWithLLM(instruction)

        val infoPool = InfoPool(instruction = instruction)
        val skillRecorder = SkillRecorder()
        val recordedSteps = mutableListOf<RecordedSkillStep>()

        // 初始化 Executor 的对话记忆
        val executorSystemPrompt = buildString {
            append("You are an agent who can operate an Android phone. ")
            append("Decide the next action based on the current state.\n\n")
            append("User Request: $instruction\n")
        }
        infoPool.executorMemory = ConversationMemory.withSystemPrompt(executorSystemPrompt)
        log("已初始化对话记忆")

        // 如果有 Skill 上下文，添加到 InfoPool，让 Manager 知道可用的工具
        if (!skillContext.isNullOrEmpty() && skillContext != "未找到相关技能或可用应用，请使用通用 GUI 自动化完成任务。") {
            infoPool.skillContext = skillContext
            log("已匹配到可用技能:\n$skillContext")
        } else {
            log("未匹配到特定技能，使用通用 GUI 自动化")
        }

        // 获取屏幕尺寸
        val (width, height) = controller.getScreenSize()
        infoPool.screenWidth = width
        infoPool.screenHeight = height
        log("屏幕尺寸: ${width}x${height}")

        // 获取已安装应用列表（只取非系统应用，限制数量避免 prompt 过长）
        val apps = appScanner.getApps()
            .filter { !it.isSystem }
            .take(50)
            .map { it.appName }
        infoPool.installedApps = apps.joinToString(", ")
        log("已加载 ${apps.size} 个应用")

        // 显示悬浮窗 (带停止按钮)
        OverlayService.show(context, "开始执行...") {
            // 停止回调 - 设置状态为停止
            // 注意：协程取消需要在 MainActivity 中处理
            updateState { copy(isRunning = false) }
            // 调用 stop() 方法确保清理
            stop()
        }

        updateState { copy(isRunning = true, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                // 检查协程是否被取消
                coroutineContext.ensureActive()

                // 检查是否被用户停止
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} ==========")
                OverlayService.update("Step ${step + 1}/$maxSteps")

                // 1. 截图 (先隐藏悬浮窗避免被识别)
                log("截图中...")
                OverlayService.setVisible(false)
                delay(50) // 等待悬浮窗隐藏
                val screenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val screenshot = screenshotResult.bitmap

                // 处理敏感页面（截图被系统阻止）
                if (screenshotResult.isSensitive) {
                    log("⚠️ 检测到敏感页面（截图被阻止），请求人工接管")
                    val confirmed = withContext(Dispatchers.Main) {
                        waitForUserConfirm("检测到敏感页面，是否继续执行？")
                    }
                    if (!confirmed) {
                        log("用户取消，任务终止")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "敏感页面，用户取消")
                    }
                    log("用户确认继续（使用黑屏占位图）")
                } else if (screenshotResult.isFallback) {
                    log("⚠️ 截图失败，使用黑屏占位图继续")
                }

                // 再次检查停止状态（截图后）
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                // 2. 检查错误升级
                checkErrorEscalation(infoPool)

                // P1 优化: 第一步用 Manager 做初始规划，后续合并为单次 VLM
                val isFirstStep = step == 0
                val useReflector = infoPool.actionOutcomes.takeLast(2).count { it in listOf("B", "C") } >= 2

                if (isFirstStep) {
                    // 第一步：Manager 做初始规划
                    log("Manager 初始规划中...")
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context); bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }
                    val planResponse = vlmClient.predict(manager.getPrompt(infoPool), listOf(screenshot))
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context); bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }
                    if (planResponse.isSuccess) {
                        val pr = manager.parseResponse(planResponse.getOrThrow())
                        infoPool.completedPlan = pr.completedSubgoal
                        infoPool.plan = pr.plan
                        log("初始计划: ${pr.plan.take(100)}...")
                        if (pr.plan.contains("STOP_SENSITIVE")) {
                            log("敏感页面，已停止")
                            delay(1000); OverlayService.hide(context)
                            updateState { copy(isRunning = false, isCompleted = false) }
                            bringAppToFront()
                            return AgentResult(success = false, message = "敏感页面")
                        }
                    } else {
                        log("Manager 调用失败: ${planResponse.exceptionOrNull()?.message}")
                    }
                } else if (infoPool.errorFlagPlan) {
                    // 连续失败：走完整 Manager 重新规划
                    log("Manager 重新规划 (错误升级)...")
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context); bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }
                    val planResponse = vlmClient.predict(manager.getPrompt(infoPool), listOf(screenshot))
                    if (planResponse.isSuccess) {
                        val pr = manager.parseResponse(planResponse.getOrThrow())
                        infoPool.completedPlan = pr.completedSubgoal
                        infoPool.plan = pr.plan
                        log("新计划: ${pr.plan.take(100)}...")
                    }
                }

                // 3. 决策动作 (合并模式: 单次 VLM = 规划 + 决策 + 自评)
                log(if (isFirstStep || infoPool.errorFlagPlan) "Executor 决策中..." else "合并决策中 (1次VLM)...")
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context); bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                val combinedPrompt = if (isFirstStep || infoPool.errorFlagPlan) {
                    // 完整 Executor prompt (含 Manager 规划结果)
                    executor.getPrompt(infoPool)
                } else {
                    // 合并 prompt (省掉 Manager 调用)
                    getCombinedPrompt(infoPool)
                }

                val memory = infoPool.executorMemory
                val actionResponse = if (memory != null) {
                    memory.addUserMessage(combinedPrompt, screenshot)
                    log("记忆: ${memory.size()} 条, ~${memory.estimateTokens()} tokens")
                    val resp = vlmClient.predictWithContext(memory.toMessagesJson())
                    memory.stripLastUserImage()
                    resp
                } else {
                    vlmClient.predict(combinedPrompt, listOf(screenshot))
                }

                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context); bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                if (actionResponse.isFailure) {
                    log("决策调用失败: ${actionResponse.exceptionOrNull()?.message}")
                    continue
                }

                val responseText = actionResponse.getOrThrow()
                val executorResult = executor.parseResponse(responseText)
                memory?.addAssistantMessage(responseText)
                val action = executorResult.action

                log("动作: ${executorResult.actionStr}")

                infoPool.lastActionThought = executorResult.thought
                infoPool.lastSummary = executorResult.description

                if (action == null) {
                    log("动作解析失败")
                    infoPool.actionHistory.add(Action(type = "invalid"))
                    infoPool.summaryHistory.add(executorResult.description)
                    infoPool.actionOutcomes.add("C")
                    infoPool.errorDescriptions.add("Invalid action format")
                    continue
                }

                // 特殊处理: answer 动作
                if (action.type == "answer") {
                    log("回答: ${action.text}")
                    OverlayService.update("${action.text?.take(20)}...")
                    delay(1500)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = true, answer = action.text) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "回答: ${action.text}")
                }

                // 特殊处理: terminate 动作 (MAI-UI)
                if (action.type == "terminate") {
                    val success = action.status == "success"
                    log("任务${if (success) "完成" else "失败"}")
                    if (success) {
                        recordRecordedSkillIfNeeded(instruction, parsedIntent, recordedSteps, skillRecorder)
                        recordSkillIfNeeded(true, instruction)
                    }
                    OverlayService.update(if (success) "完成!" else "失败")
                    delay(1500)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = success) }
                    bringAppToFront()
                    return AgentResult(success = success, message = if (success) "任务完成" else "任务失败")
                }

                // 6. 敏感操作确认
                if (action.needConfirm || action.message != null && action.type in listOf("click", "double_tap", "long_press")) {
                    val confirmMessage = action.message ?: "确认执行此操作？"
                    log("⚠️ 敏感操作: $confirmMessage")

                    val confirmed = withContext(Dispatchers.Main) {
                        waitForUserConfirm(confirmMessage)
                    }

                    if (!confirmed) {
                        log("❌ 用户取消操作")
                        infoPool.actionHistory.add(action)
                        infoPool.summaryHistory.add("用户取消: ${executorResult.description}")
                        infoPool.actionOutcomes.add("C")
                        infoPool.errorDescriptions.add("User cancelled")
                        continue
                    }
                    log("✅ 用户确认，继续执行")
                }

                // 7. 执行动作
                log("执行动作: ${action.type}")
                skillRecorder.parse(executorResult)?.let { recordedSteps.add(it) }
                OverlayService.update("${action.type}: ${executorResult.description.take(15)}...")
                executeAction(action, infoPool)
                infoPool.lastAction = action

                // 立即记录执行步骤（outcome 暂时为 "?" 表示进行中）
                val currentStepIndex = _state.value.executionSteps.size
                val executionStep = ExecutionStep(
                    stepNumber = step + 1,
                    timestamp = System.currentTimeMillis(),
                    action = action.type,
                    description = executorResult.description,
                    thought = executorResult.thought,
                    outcome = "?" // 进行中
                )
                updateState { copy(executionSteps = executionSteps + executionStep) }

                // 动态等待：按动作类型差异化
                val waitMs = getActionWaitMs(action)
                log("等待 ${waitMs}ms...")
                delay(waitMs)

                // 检查停止状态
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                // 8. 截图 (动作后，隐藏悬浮窗)
                OverlayService.setVisible(false)
                delay(100)
                val afterScreenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val afterScreenshot = afterScreenshotResult.bitmap
                if (afterScreenshotResult.isFallback) {
                    log("动作后截图失败，使用黑屏占位图")
                }

                // 9. 评估结果: 连续失败时用 Reflector 深度分析，否则快速自评
                val outcome: String
                val errorDesc: String

                if (useReflector) {
                    // 连续失败 → 需要 Reflector 深度分析
                    log("Reflector 深度分析 (连续失败)...")
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context); bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }
                    val reflectPrompt = reflector.getPrompt(infoPool)
                    val reflectResponse = vlmClient.predict(reflectPrompt, listOf(screenshot, afterScreenshot))
                    val reflectResult = if (reflectResponse.isSuccess) {
                        reflector.parseResponse(reflectResponse.getOrThrow())
                    } else {
                        ReflectorResult("C", "Reflector call failed")
                    }
                    outcome = reflectResult.outcome
                    errorDesc = reflectResult.errorDescription
                    log("Reflector: $outcome - ${errorDesc.take(50)}")
                } else {
                    // 快速自评: 比较前后截图是否变化（不调 VLM）
                    outcome = quickSelfCheck(screenshot, afterScreenshot, action)
                    errorDesc = if (outcome == "A") "None" else "Screen unchanged or unexpected"
                    log("快速自评: $outcome")
                }

                // 更新历史
                infoPool.actionHistory.add(action)
                infoPool.summaryHistory.add(executorResult.description)
                infoPool.actionOutcomes.add(outcome)
                infoPool.errorDescriptions.add(errorDesc)
                infoPool.progressStatus = infoPool.completedPlan

                // 更新执行步骤的 outcome（之前添加的步骤 outcome 是 "?"）
                updateState {
                    val updatedSteps = executionSteps.toMutableList()
                    if (currentStepIndex < updatedSteps.size) {
                        updatedSteps[currentStepIndex] = updatedSteps[currentStepIndex].copy(
                            outcome = outcome
                        )
                    }
                    copy(executionSteps = updatedSteps)
                }

                // 10. Notetaker (可选, 只在成功时记录)
                if (useNotetaker && outcome == "A" && action.type != "answer") {
                    log("Notetaker 记录中...")

                    // 检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }

                    val notePrompt = notetaker.getPrompt(infoPool)
                    val noteResponse = vlmClient.predict(notePrompt, listOf(afterScreenshot))
                    if (noteResponse.isSuccess) {
                        infoPool.importantNotes = notetaker.parseResponse(noteResponse.getOrThrow())
                    }
                }
            }
        } catch (e: CancellationException) {
            log("任务被取消")
            OverlayService.hide(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("达到最大步数限制")
        OverlayService.update("达到最大步数")
        delay(1500)
        OverlayService.hide(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * GUI-Owl 模式执行指令
     * 简化流程：截图 → GUI-Owl API → 解析操作 → 执行
     */
    private suspend fun runInstructionWithGUIOwl(
        instruction: String,
        maxSteps: Int
    ): AgentResult {
        val client = guiOwlClient ?: return AgentResult(false, "GUIOwlClient 未初始化")

        // 重置会话
        client.resetSession()
        log("GUI-Owl 会话已重置")

        // 获取屏幕尺寸
        val (screenWidth, screenHeight) = controller.getScreenSize()
        log("屏幕尺寸: ${screenWidth}x${screenHeight}")

        // 显示悬浮窗
        OverlayService.show(context, "GUI-Owl 模式") {
            updateState { copy(isRunning = false) }
            stop()
        }

        updateState { copy(isRunning = true, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                coroutineContext.ensureActive()

                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} (GUI-Owl) ==========")
                OverlayService.update("Step ${step + 1}/$maxSteps")

                // 1. 截图
                log("截图中...")
                OverlayService.setVisible(false)
                delay(100)
                val screenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val screenshot = screenshotResult.bitmap

                if (screenshotResult.isSensitive) {
                    log("⚠️ 检测到敏感页面")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "敏感页面，已停止")
                }

                // 2. 调用 GUI-Owl API
                log("调用 GUI-Owl API...")
                val response = client.predict(instruction, screenshot)

                if (response.isFailure) {
                    log("GUI-Owl 调用失败: ${response.exceptionOrNull()?.message}")
                    continue
                }

                val result = response.getOrThrow()
                log("思考: ${result.thought.take(100)}...")
                log("操作: ${result.operation}")
                log("说明: ${result.explanation}")

                // 3. 解析操作指令
                val parsedAction = client.parseOperation(result.operation)
                if (parsedAction == null) {
                    log("无法解析操作: ${result.operation}")
                    continue
                }

                // 记录执行步骤
                val executionStep = ExecutionStep(
                    stepNumber = step + 1,
                    timestamp = System.currentTimeMillis(),
                    action = parsedAction.type,
                    description = result.explanation,
                    thought = result.thought,
                    outcome = "?"
                )
                updateState { copy(executionSteps = executionSteps + executionStep) }

                // 检查是否完成
                if (parsedAction.type == "finish") {
                    log("任务完成!")
                    OverlayService.update("完成!")
                    delay(1500)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = true) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "任务完成")
                }

                // 4. 执行动作
                log("执行动作: ${parsedAction.type}")
                OverlayService.update("${parsedAction.type}: ${result.explanation.take(15)}...")
                executeGUIOwlAction(parsedAction, screenWidth, screenHeight)

                // 更新步骤状态
                updateState {
                    val updatedSteps = executionSteps.toMutableList()
                    if (step < updatedSteps.size) {
                        updatedSteps[step] = updatedSteps[step].copy(outcome = "A")
                    }
                    copy(executionSteps = updatedSteps)
                }

                // 动态等待
                val waitMs = when (parsedAction.type) {
                    "click" -> 700L
                    "swipe" -> 400L
                    "long_press" -> 900L
                    "type" -> 250L
                    "scroll" -> 400L
                    else -> 1000L
                }
                delay(waitMs)
            }
        } catch (e: CancellationException) {
            log("任务被取消")
            OverlayService.hide(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("达到最大步数限制")
        OverlayService.update("达到最大步数")
        delay(1500)
        OverlayService.hide(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * 执行 GUI-Owl 解析的动作
     */
    private suspend fun executeGUIOwlAction(
        action: GUIOwlClient.ParsedAction,
        screenWidth: Int,
        screenHeight: Int
    ) = withContext(Dispatchers.IO) {
        when (action.type) {
            "click" -> {
                val x = action.x ?: return@withContext
                val y = action.y ?: return@withContext
                log("点击: ($x, $y)")
                recordExecutedAction(Action(type = "click", x = x, y = y))
                controller.tap(x, y)
            }
            "swipe" -> {
                val x1 = action.x ?: return@withContext
                val y1 = action.y ?: return@withContext
                val x2 = action.x2 ?: return@withContext
                val y2 = action.y2 ?: return@withContext
                log("滑动: ($x1, $y1) -> ($x2, $y2)")
                controller.swipe(x1, y1, x2, y2)
            }
            "long_press" -> {
                val x = action.x ?: return@withContext
                val y = action.y ?: return@withContext
                log("长按: ($x, $y)")
                controller.longPress(x, y)
            }
            "type" -> {
                val text = action.text ?: return@withContext
                log("输入: $text")
                recordExecutedAction(Action(type = "type", text = text))
                controller.type(text)
            }
            "scroll" -> {
                val direction = action.text ?: "down"
                val centerX = screenWidth / 2
                val centerY = screenHeight / 2
                log("滚动: $direction")
                when (direction) {
                    "up" -> controller.swipe(centerX, centerY + 300, centerX, centerY - 300)
                    "down" -> controller.swipe(centerX, centerY - 300, centerX, centerY + 300)
                    "left" -> controller.swipe(centerX + 300, centerY, centerX - 300, centerY)
                    "right" -> controller.swipe(centerX - 300, centerY, centerX + 300, centerY)
                }
            }
            "system_button" -> {
                when (action.text?.lowercase()) {
                    "back" -> {
                        log("按返回键")
                        controller.back()
                    }
                    "home" -> {
                        log("按 Home 键")
                        controller.home()
                    }
                }
            }
            else -> {
                log("未知动作类型: ${action.type}")
            }
        }
    }

    /**
     * MAI-UI 模式执行指令
     * 使用专用的 MAI-UI prompt 和对话历史管理
     */
    private suspend fun runInstructionWithMAIUI(
        instruction: String,
        maxSteps: Int
    ): AgentResult {
        val client = maiuiClient ?: return AgentResult(false, "MAIUIClient 未初始化")

        // 重置会话
        client.reset()
        log("MAI-UI 会话已重置")

        // 获取屏幕尺寸
        val (screenWidth, screenHeight) = controller.getScreenSize()
        log("屏幕尺寸: ${screenWidth}x${screenHeight}")

        // 设置可用应用列表
        val installedApps = appScanner.getApps().map { it.appName }
        client.setAvailableApps(installedApps)
        log("已加载 ${installedApps.size} 个应用")

        // 显示悬浮窗
        OverlayService.show(context, "MAI-UI 模式") {
            updateState { copy(isRunning = false) }
            stop()
        }

        updateState { copy(isRunning = true, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                coroutineContext.ensureActive()

                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} (MAI-UI) ==========")
                OverlayService.update("Step ${step + 1}/$maxSteps")

                // 1. 截图
                log("截图中...")
                OverlayService.setVisible(false)
                delay(100)
                val screenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val screenshot = screenshotResult.bitmap

                if (screenshotResult.isSensitive) {
                    log("⚠️ 检测到敏感页面")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "敏感页面，已停止")
                }

                // 2. 调用 MAI-UI API
                log("调用 MAI-UI API...")
                val response = client.predict(instruction, screenshot)

                if (response.isFailure) {
                    log("MAI-UI 调用失败: ${response.exceptionOrNull()?.message}")
                    continue
                }

                val result = response.getOrThrow()
                log("思考: ${result.thinking.take(150)}...")

                val action = result.action
                if (action == null) {
                    log("无法解析动作")
                    continue
                }

                log("动作: ${action.type}")

                // 记录执行步骤
                val executionStep = ExecutionStep(
                    stepNumber = step + 1,
                    timestamp = System.currentTimeMillis(),
                    action = action.type,
                    description = result.thinking.take(50),
                    thought = result.thinking,
                    outcome = "?"
                )
                updateState { copy(executionSteps = executionSteps + executionStep) }

                // 检查是否完成
                if (action.type == "terminate") {
                    val success = action.status == "success"
                    log(if (success) "任务完成!" else "任务失败")
                    if (success) recordSkillIfNeeded(true, instruction)
                    OverlayService.update(if (success) "完成!" else "失败")
                    delay(1500)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = success) }
                    bringAppToFront()
                    return AgentResult(success = success, message = if (success) "任务完成" else "任务失败")
                }

                // 检查是否需要人工接管
                if (action.type == "ask_user") {
                    log("请求用户介入: ${action.text}")
                    OverlayService.update("请手动操作: ${action.text?.take(20)}")
                    // 等待用户操作后继续
                    delay(5000)
                    continue
                }

                // 检查是否是回答
                if (action.type == "answer") {
                    log("回答: ${action.text}")
                    OverlayService.update("答案: ${action.text?.take(30)}")
                    delay(3000)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = true) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "回答: ${action.text}")
                }

                // 3. 执行动作
                log("执行动作: ${action.type}")
                OverlayService.update("${action.type}...")
                executeMAIUIAction(action, screenWidth, screenHeight)

                // 更新步骤状态
                updateState {
                    val updatedSteps = executionSteps.toMutableList()
                    if (step < updatedSteps.size) {
                        updatedSteps[step] = updatedSteps[step].copy(outcome = "A")
                    }
                    copy(executionSteps = updatedSteps)
                }

                // 动态等待：MAI-UI 模式动作
                val waitMs = when (action.type) {
                    "click" -> 700L
                    "swipe" -> 400L
                    "long_press" -> 900L
                    "double_click" -> 600L
                    "type" -> 250L
                    "open" -> 2800L
                    "system_button" -> 400L
                    "wait" -> 1500L
                    else -> 1000L
                }
                delay(waitMs)
            }
        } catch (e: CancellationException) {
            log("任务被取消")
            OverlayService.hide(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("达到最大步数限制")
        OverlayService.update("达到最大步数")
        delay(1500)
        OverlayService.hide(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * 执行 MAI-UI 解析的动作
     */
    private suspend fun executeMAIUIAction(
        action: com.roubao.autopilot.vlm.MAIUIAction,
        screenWidth: Int,
        screenHeight: Int
    ) = withContext(Dispatchers.IO) {
        // 转换归一化坐标到屏幕像素
        val screenAction = action.toScreenCoordinates(screenWidth, screenHeight)

        when (action.type) {
            "click" -> {
                val x = screenAction.x?.toInt() ?: return@withContext
                val y = screenAction.y?.toInt() ?: return@withContext
                log("点击: ($x, $y)")
                recordExecutedAction(Action(type = "click", x = x, y = y))
                controller.tap(x, y)
            }
            "long_press" -> {
                val x = screenAction.x?.toInt() ?: return@withContext
                val y = screenAction.y?.toInt() ?: return@withContext
                log("长按: ($x, $y)")
                controller.longPress(x, y)
            }
            "double_click" -> {
                val x = screenAction.x?.toInt() ?: return@withContext
                val y = screenAction.y?.toInt() ?: return@withContext
                log("双击: ($x, $y)")
                controller.tap(x, y)
                delay(100)
                controller.tap(x, y)
            }
            "type" -> {
                val text = action.text ?: return@withContext
                log("输入: $text")
                recordExecutedAction(Action(type = "type", text = text))
                controller.type(text)
            }
            "swipe" -> {
                // 支持方向式滑动和坐标式滑动
                if (action.direction != null) {
                    val centerX = screenWidth / 2
                    val centerY = screenHeight / 2
                    val distance = screenHeight / 3
                    log("滑动: ${action.direction}")
                    when (action.direction.lowercase()) {
                        "up" -> controller.swipe(centerX, centerY + distance, centerX, centerY - distance)
                        "down" -> controller.swipe(centerX, centerY - distance, centerX, centerY + distance)
                        "left" -> controller.swipe(centerX + distance, centerY, centerX - distance, centerY)
                        "right" -> controller.swipe(centerX - distance, centerY, centerX + distance, centerY)
                    }
                } else if (screenAction.x != null && screenAction.y != null) {
                    // 从指定位置滑动
                    val startX = screenAction.x.toInt()
                    val startY = screenAction.y.toInt()
                    val distance = screenHeight / 3
                    val direction = action.direction ?: "up"
                    when (direction.lowercase()) {
                        "up" -> controller.swipe(startX, startY, startX, startY - distance)
                        "down" -> controller.swipe(startX, startY, startX, startY + distance)
                        "left" -> controller.swipe(startX, startY, startX - distance, startY)
                        "right" -> controller.swipe(startX, startY, startX + distance, startY)
                    }
                }
            }
            "drag" -> {
                val x1 = screenAction.startX?.toInt() ?: return@withContext
                val y1 = screenAction.startY?.toInt() ?: return@withContext
                val x2 = screenAction.endX?.toInt() ?: return@withContext
                val y2 = screenAction.endY?.toInt() ?: return@withContext
                log("拖拽: ($x1, $y1) -> ($x2, $y2)")
                controller.swipe(x1, y1, x2, y2, 500)
            }
            "open" -> {
                val appName = action.text ?: return@withContext
                log("打开应用: $appName")
                controller.openApp(appName)
            }
            "system_button" -> {
                when (action.button?.lowercase()) {
                    "back" -> {
                        log("按返回键")
                        controller.back()
                    }
                    "home" -> {
                        log("按 Home 键")
                        controller.home()
                    }
                    "menu" -> {
                        log("按菜单键")
                        // 通过 keyevent 模拟菜单键
                        controller.back()  // 大多数场景用返回代替
                    }
                    "enter" -> {
                        log("按回车键")
                        controller.enter()
                    }
                }
            }
            "wait" -> {
                log("等待...")
                delay(2000)
            }
            else -> {
                log("未知动作类型: ${action.type}")
            }
        }
    }

    /**
     * 执行具体动作 (在 IO 线程执行，避免 ANR)
     */
    private suspend fun executeAction(action: Action, infoPool: InfoPool) = withContext(Dispatchers.IO) {
        recordExecutedAction(action)

        // 动态获取屏幕尺寸（处理横竖屏切换）
        val (screenWidth, screenHeight) = controller.getScreenSize()

        when (action.type) {
            "click", "tap" -> {
                val x = mapCoordinate(action.x ?: 0, screenWidth)
                val y = mapCoordinate(action.y ?: 0, screenHeight)
                controller.tap(x, y)
            }
            "double_tap" -> {
                val x = mapCoordinate(action.x ?: 0, screenWidth)
                val y = mapCoordinate(action.y ?: 0, screenHeight)
                controller.doubleTap(x, y)
            }
            "long_press" -> {
                val x = mapCoordinate(action.x ?: 0, screenWidth)
                val y = mapCoordinate(action.y ?: 0, screenHeight)
                controller.longPress(x, y)
            }
            "swipe" -> {
                // 支持两种 swipe 方式:
                // 1. 坐标方式: coordinate + coordinate2
                // 2. 方向方式: direction (up/down/left/right) + optional coordinate
                if (action.direction != null) {
                    // 方向方式 (MAI-UI 格式)
                    val centerX = action.x?.let { mapCoordinate(it, screenWidth) } ?: (screenWidth / 2)
                    val centerY = action.y?.let { mapCoordinate(it, screenHeight) } ?: (screenHeight / 2)
                    val distance = 400  // 滑动距离
                    when (action.direction.lowercase()) {
                        "up" -> controller.swipe(centerX, centerY + distance, centerX, centerY - distance)
                        "down" -> controller.swipe(centerX, centerY - distance, centerX, centerY + distance)
                        "left" -> controller.swipe(centerX + distance, centerY, centerX - distance, centerY)
                        "right" -> controller.swipe(centerX - distance, centerY, centerX + distance, centerY)
                        else -> log("未知滑动方向: ${action.direction}")
                    }
                } else {
                    // 坐标方式
                    val x1 = mapCoordinate(action.x ?: 0, screenWidth)
                    val y1 = mapCoordinate(action.y ?: 0, screenHeight)
                    val x2 = mapCoordinate(action.x2 ?: 0, screenWidth)
                    val y2 = mapCoordinate(action.y2 ?: 0, screenHeight)
                    controller.swipe(x1, y1, x2, y2)
                }
            }
            "type" -> {
                action.text?.let { controller.type(it) }
            }
            "system_button" -> {
                when (action.button) {
                    "Back", "back" -> controller.back()
                    "Home", "home" -> controller.home()
                    "Enter", "enter" -> controller.enter()
                    else -> log("未知系统按钮: ${action.button}")
                }
            }
            "open_app" -> {
                action.text?.let { appName ->
                    // 智能匹配包名 (客户端模糊搜索，省 token)
                    val packageName = appScanner.findPackage(appName)
                    if (packageName != null) {
                        log("找到应用: $appName -> $packageName")
                        controller.openApp(packageName)
                    } else {
                        log("未找到应用: $appName，尝试直接打开")
                        controller.openApp(appName)
                    }
                }
            }
            "wait" -> {
                // 智能等待：模型决定等待时长
                val duration = (action.duration ?: 3).coerceIn(1, 10)
                log("等待 ${duration} 秒...")
                delay(duration * 1000L)
            }
            "take_over" -> {
                // 人机协作：暂停等待用户手动完成操作
                val message = action.message ?: "请完成操作后点击继续"
                log("🖐 人机协作: $message")
                withContext(Dispatchers.Main) {
                    waitForUserTakeOver(message)
                }
                log("✅ 用户已完成，继续执行")
            }
            else -> {
                log("未知动作类型: ${action.type}")
            }
        }
    }

    private fun recordExecutedAction(action: Action) {
        if (action.type in listOf("wait", "take_over", "ask_user", "answer", "terminate")) return
        updateState { copy(lastActions = lastActions + action) }
    }

    private suspend fun recordSkillIfNeeded(shouldRecord: Boolean, instruction: String) {
        if (!shouldRecord) return
        val actions = _state.value.lastActions
            .filter { it.type !in listOf("wait", "take_over", "ask_user", "answer", "terminate") }
        if (actions.size < 2) return
        val workflow = Workflow.autoGenerate(instruction, actions)
        App.getInstance().workflowLibrary?.saveUserWorkflow(workflow)
        log("已自动录制 SkillWorkflow: ${workflow.name} (${workflow.steps.size}步)")
    }

    private suspend fun recordRecordedSkillIfNeeded(
        instruction: String,
        intent: com.roubao.autopilot.skills.ParsedIntent?,
        steps: List<RecordedSkillStep>,
        recorder: SkillRecorder
    ) {
        val skill = recorder.build(instruction, intent, steps) ?: return
        App.getInstance().recordedSkillLibrary?.save(skill)
        log("已录制语义 Skill: ${skill.name} (${skill.steps.size}步)")
    }

    /**
     * 等待用户完成手动操作（人机协作）
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserTakeOver(message: String) = suspendCancellableCoroutine<Unit> { continuation ->
        com.roubao.autopilot.ui.OverlayService.showTakeOver(message) {
            if (continuation.isActive) {
                continuation.resume(Unit) {}
            }
        }
    }

    /**
     * 等待用户确认敏感操作
     * @return true = 用户确认，false = 用户取消
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserConfirm(message: String) = suspendCancellableCoroutine<Boolean> { continuation ->
        com.roubao.autopilot.ui.OverlayService.showConfirm(message) { confirmed ->
            if (continuation.isActive) {
                continuation.resume(confirmed) {}
            }
        }
    }

    /**
     * P1 优化: 合并 Manager + Executor 为单次 VLM 调用
     * 复杂任务首次规划仍用 Manager，后续每步合并为一次调用。
     * Reflector 仅在连续失败时兜底。
     */
    private fun getCombinedPrompt(infoPool: InfoPool): String = buildString {
        append("You are an agent operating an Android phone. ")
        append("Analyze the screenshot and decide the NEXT action to take.\n\n")

        append("### User Goal ###\n${infoPool.instruction}\n\n")

        if (infoPool.plan.isNotEmpty()) {
            append("### Current Plan ###\n${infoPool.plan}\n\n")
        }
        if (infoPool.completedPlan.isNotEmpty()) {
            append("### Completed ###\n${infoPool.completedPlan}\n\n")
        }
        if (infoPool.skillContext.isNotEmpty()) {
            append("### Available Skills ###\n${infoPool.skillContext}\n\n")
        }

        // 最近动作历史 (精简版，只保留关键信息)
        if (infoPool.actionOutcomes.isNotEmpty()) {
            val n = minOf(3, infoPool.actionHistory.size)
            append("### Recent Actions ###\n")
            for (i in (infoPool.actionHistory.size - n) until infoPool.actionHistory.size) {
                val act = infoPool.actionHistory[i]
                val desc = infoPool.summaryHistory.getOrNull(i) ?: ""
                val outcome = infoPool.actionOutcomes.getOrNull(i) ?: "?"
                val emoji = when (outcome) { "A" -> "OK" else -> "FAIL" }
                append("- $act | $desc | $emoji\n")
            }
            val recentFailures = infoPool.actionOutcomes.takeLast(2).count { it in listOf("B", "C") }
            if (recentFailures >= 2) {
                append("⚠️ Last 2 actions FAILED. MUST try a different approach!\n")
            }
            append("\n")
        }

        append("---\n")
        append("### Action Types ###\n")
        append("- {\"action\":\"click\",\"coordinate\":[x,y]}\n")
        append("- {\"action\":\"long_press\",\"coordinate\":[x,y]}\n")
        append("- {\"action\":\"swipe\",\"coordinate\":[x1,y1],\"coordinate2\":[x2,y2]}\n")
        append("- {\"action\":\"swipe\",\"direction\":\"up|down|left|right\"}\n")
        append("- {\"action\":\"type\",\"text\":\"...\"}\n")
        append("- {\"action\":\"system_button\",\"button\":\"Back|Home|Enter\"}\n")
        append("- {\"action\":\"open_app\",\"text\":\"app name\"}\n")
        append("- {\"action\":\"wait\",\"duration\":seconds}\n")
        append("- {\"action\":\"answer\",\"text\":\"...\"}\n")
        append("- {\"action\":\"terminate\",\"status\":\"success|fail\"}\n\n")

        append("### Rules ###\n")
        append("1. Do NOT repeat previously failed actions.\n")
        append("2. Use open_app to launch apps, NOT home screen.\n")
        append("3. Close popups/dialogs before proceeding.\n")
        append("4. If goal is achieved, use terminate with status=success.\n")
        append("5. For payment/password screens, use terminate with status=fail.\n\n")

        append("Output format:\n")
        append("### Thought ###\nBrief analysis (1-2 sentences)\n\n")
        append("### Action ###\nJSON action object. Include skillType and target when possible: {\"skillType\":\"focus_input|send|click_target|type_text|search|open_app|fallback_image\",\"action\":\"click\",\"target\":{\"text\":\"...\",\"className\":\"...\",\"contentDesc\":\"...\"},\"coordinate\":[x,y]}\n\n")
        append("### Description ###\nBrief description\n")
    }

    /**
     * 动态等待时间：按动作类型
     * open_app 需要等 App 启动，swipe 几乎不用等
     */
    private fun getActionWaitMs(action: Action): Long {
        return when (action.type) {
            "open_app"    -> 2800L  // App 冷启动需要较长时间
            "click", "tap"       -> 700L   // 页面内点击切换
            "long_press"  -> 900L   // 长按触发的菜单
            "double_tap"  -> 600L
            "swipe"       -> 400L   // 滑动后页面几乎即时响应
            "type"        -> 250L   // 输入后几乎不需要等
            "system_button" -> when (action.button) {
                "Back", "back"  -> 400L
                "Home", "home"  -> 500L
                "Enter", "enter" -> 200L
                else            -> 500L
            }
            "wait"        -> ((action.duration ?: 2) * 1000L).coerceIn(500L, 8000L)
            "take_over"   -> 500L  // 人机协作后短暂等待
            else          -> 800L  // 默认
        }
    }

    /**
     * 坐标映射 - 支持相对坐标和绝对坐标
     *
     * 坐标格式判断:
     * - 0-999: Qwen-VL 相对坐标 (0-999 映射到屏幕)
     * - >= 1000: 绝对像素坐标，直接使用
     *
     * @param value 模型输出的坐标值
     * @param screenMax 屏幕实际尺寸
     */
    private fun mapCoordinate(value: Int, screenMax: Int): Int {
        return if (value < 1000) {
            // 相对坐标 (0-999) -> 绝对像素
            (value * screenMax / 999)
        } else {
            // 绝对坐标，限制在屏幕范围内
            value.coerceAtMost(screenMax)
        }
    }

    /**
     * 快速自评: 比较前后截图是否变化 (不调 VLM)
     * 返回 "A" (成功, 屏幕有变化) 或 "C" (无变化, 可能没点到)
     */
    private fun quickSelfCheck(before: Bitmap, after: Bitmap, action: Action): String {
        // 采样检测: 取屏幕中心和四个角的像素
        val w = before.width
        val h = before.height
        val samplePoints = listOf(
            Pair(w / 4, h / 4),
            Pair(w * 3 / 4, h / 4),
            Pair(w / 4, h * 3 / 4),
            Pair(w * 3 / 4, h * 3 / 4),
            Pair(w / 2, h / 2)
        )
        var changedPixels = 0
        for ((x, y) in samplePoints) {
            try {
                if (before.getPixel(x, y) != after.getPixel(x, y)) changedPixels++
            } catch (_: Exception) { }
        }
        return if (changedPixels >= 1) "A" else "C"
    }

    /**
     * 检查错误升级
     */
    private fun checkErrorEscalation(infoPool: InfoPool) {
        infoPool.errorFlagPlan = false
        val thresh = infoPool.errToManagerThresh

        if (infoPool.actionOutcomes.size >= thresh) {
            val recentOutcomes = infoPool.actionOutcomes.takeLast(thresh)
            val failCount = recentOutcomes.count { it in listOf("B", "C") }
            if (failCount == thresh) {
                infoPool.errorFlagPlan = true
            }
        }
    }

    // 停止回调（由 MainActivity 设置，用于取消协程）
    var onStopRequested: (() -> Unit)? = null

    /**
     * 停止执行
     */
    fun stop() {
        OverlayService.hide(context)
        updateState { copy(isRunning = false) }
        // 通知 MainActivity 取消协程
        onStopRequested?.invoke()
    }

    /**
     * 清空日志
     */
    fun clearLogs() {
        _logs.value = emptyList()
        updateState { copy(executionSteps = emptyList()) }
    }

    /**
     * 返回肉包App
     */
    private fun bringAppToFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
        } catch (e: Exception) {
            log("返回App失败: ${e.message}")
        }
    }

    /**
     * 检测运行模式：用户指令中是否明确要求 AI 或本地执行
     */
    private fun detectRunMode(command: String): RunMode {
        val forceAiRegex = Regex(
            "(用\\s*AI|用\\s*ai|走\\s*AI|走\\s*ai|AI\\s*来|ai\\s*来|VLM|vlm|视觉|看屏幕|看截图|智能判断|" +
            "不要走\\s*skill|不要用\\s*skill|跳过\\s*skill|别用本地|不要用本地)",
            RegexOption.IGNORE_CASE
        )
        val forceLocalRegex = Regex(
            "(只用本地|只走本地|只用\\s*skill|不要用\\s*AI|不要用\\s*ai|离线执行|本地执行)",
            RegexOption.IGNORE_CASE
        )
        return when {
            forceLocalRegex.containsMatchIn(command) -> RunMode.LOCAL_ONLY
            forceAiRegex.containsMatchIn(command) -> RunMode.AI_ONLY
            else -> RunMode.AUTO
        }
    }

    /** 检查 AI (VLM 或 GUI Agent) 是否可用 */
    private fun isAiAvailable(): Boolean {
        return vlmClient != null || guiOwlClient != null || maiuiClient != null
    }

    private fun log(message: String) {
        println("[肉包] $message")
        _logs.value = _logs.value + message
    }

    private fun updateState(update: AgentState.() -> AgentState) {
        _state.value = _state.value.update()
    }

}

data class AgentState(
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val currentStep: Int = 0,
    val instruction: String = "",
    val answer: String? = null,
    val executionSteps: List<ExecutionStep> = emptyList(),
    val lastActions: List<Action> = emptyList()  // 最近一次执行的完整动作序列，供录制 Workflow
)

data class AgentResult(
    val success: Boolean,
    val message: String
)
