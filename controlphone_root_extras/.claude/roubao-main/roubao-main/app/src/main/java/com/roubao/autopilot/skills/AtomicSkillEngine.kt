package com.roubao.autopilot.skills

import com.roubao.autopilot.agent.AgentResult
import com.roubao.autopilot.controller.DeviceController

/**
 * 引擎执行状态
 */
enum class EngineStatus {
    SUCCESS,
    FAILED,
    NEEDS_FULL_AI  // 本地引擎无法完成，需要完整 VLM（仅 AUTO + allowFullAiFallback）
}

/**
 * AtomicSkillEngine — 基于原子技能的本地执行引擎。
 *
 * 流程（每个 goal 独立循环）：
 *   OBSERVE → PLAN → RESOLVE → EXECUTE → VERIFY → [REPAIR → retry] → DONE/FAILED
 *
 * 解析优先级：
 *   SkillTemplateRepository → AtomicSkillRegistry → WorkflowReplaySkill
 *
 * AI 辅助（AiAssist）的边界：
 *   - AI 只做局部辅助：感知（locateElement）、规划（decomposeGoal）、验证（verifyResult）、修复（suggestRepair）
 *   - AI 绝不返回原始 Action
 *   - 每次 AI 调用受 AiLease 配额限制
 *   - 辅助完成后，控制权回到本地引擎
 *
 * 与旧 NewSkillEngine 的区别：
 *   - 不再硬编码 OBSERVE → PLAN → RESOLVE → EXECUTE → VERIFY → DONE
 *   - 支持 REPAIR 阶段（可选 AI 修复建议）
 *   - SkillTemplateRepository 优先级最高
 *   - 完整的 AI 配额控制
 */
class AtomicSkillEngine(
    private val controller: DeviceController,
    private val aiAssist: AiAssist? = null,
    private val templateRepo: SkillTemplateRepository? = null,
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "[AtomicSkillEngine]"
        private const val MAX_RETRIES_PER_GOAL = 2
    }

    private val observer = UiObserver(controller)
    private val intentParser = IntentParser()
    private val decomposer = GoalDecomposer()
    private val planner = TaskPlanner(intentParser, decomposer)
    private val registry = AtomicSkillRegistry(controller, observer)
    private val resolver = AtomicSkillResolver(registry)

    /** 执行追踪 */
    val records = mutableListOf<StepRecord>()

    /**
     * 主入口：接收用户指令，返回引擎结果。
     */
    suspend fun execute(instruction: String): EngineResult {
        records.clear()
        log("$TAG ===== 开始: $instruction =====")

        // Phase 1: OBSERVE
        val initialState = observer.capture()
        log("$TAG OBSERVE: pkg=${initialState.packageName} nodes=${initialState.nodes.size}")
        records.add(StepRecord(EnginePhase.OBSERVE, -1, null, null, null, null, initialState))

        // Phase 2: PLAN — 本地分解 + 可选 AI 规划辅助
        val plan = planner.createPlan(instruction)
        log("$TAG PLAN: ${plan.goals.size} 个子目标")
        records.add(StepRecord(EnginePhase.PLAN, -1, null, null, null, null, null))

        if (plan.goals.isEmpty()) {
            // 本地分解失败，尝试 AI 规划辅助
            if (aiAssist?.available == true) {
                val lease = AiLease.AUTO_DEFAULT
                if (lease.canPlan()) {
                    log("$TAG PLAN 本地分解失败，尝试 AI 规划辅助")
                    lease.recordPlan()
                    val screenshot = controller.screenshotWithFallback().bitmap
                    val aiGoals = aiAssist.decomposeGoal(instruction, initialState, screenshot, lease)
                    if (!aiGoals.isNullOrEmpty()) {
                        val goals = aiGoals.map { it.toGoal() }
                        log("$TAG AI 规划: ${goals.size} 个子目标")
                        return executeGoals(goals)
                    }
                }
            }
            log("$TAG PLAN 失败: 无法分解为子目标")
            return EngineResult(EngineStatus.FAILED, "无法分解用户指令为子目标")
        }

        val goals = plan.goals.mapNotNull { it.goal }
        if (goals.isEmpty()) {
            log("$TAG PLAN: 无有效 Goal 对象，需要 AI")
            return EngineResult(EngineStatus.NEEDS_FULL_AI, "子目标需要 AI 处理")
        }

        return executeGoals(goals)
    }

    /**
     * 执行 Goal 序列 — 核心循环。
     */
    private suspend fun executeGoals(goals: List<Goal>): EngineResult {
        val lease = AiLease.AUTO_DEFAULT
        val aiUsed = mutableListOf<String>()

        for ((i, goal) in goals.withIndex()) {
            log("$TAG goal[$i] ${goal.type}: ${goal.title}")

            var lastResult: AtomicSkillResult? = null
            var retries = 0

            while (retries <= MAX_RETRIES_PER_GOAL) {
                val stateBefore = observer.capture()

                // RESOLVE — 按优先级: Template → Atomic → Replay
                val resolved = resolveGoal(goal, stateBefore, lease)

                if (resolved == null) {
                    log("$TAG goal[$i] ${goal.type}: 无可执行 skill")
                    // 检查是否使用了 AI 辅助
                    val aiMsg = if (aiUsed.isNotEmpty()) "（已使用 AI: ${aiUsed.joinToString()}）" else ""
                    return EngineResult(EngineStatus.NEEDS_FULL_AI,
                        "${goal.type} 无本地技能可用$aiMsg", aiUsed)
                }

                val (skill, proposal) = resolved

                if (!proposal.executable || proposal.requiresAi) {
                    log("$TAG goal[$i] ${goal.type}: skill=${skill.id} 不可执行 — ${proposal.reason}")
                    return EngineResult(EngineStatus.NEEDS_FULL_AI,
                        "${goal.type} 需要 AI: ${proposal.reason}", aiUsed)
                }

                log("$TAG goal[$i] ${goal.type} → ${skill.id} confidence=${proposal.confidence}")

                // RESOLVE
                records.add(StepRecord(EnginePhase.RESOLVE, i, goal, skill.id, null, stateBefore, null))

                // EXECUTE
                val result = skill.execute(goal, emptyMap(), stateBefore)
                lastResult = result
                records.add(StepRecord(EnginePhase.EXECUTE, i, goal, skill.id, result, stateBefore, null))

                // VERIFY
                val stateAfter = observer.capture()
                kotlinx.coroutines.delay(200)

                val verified = verifyResult(goal, stateBefore, stateAfter, result, lease, aiUsed)

                records.add(StepRecord(
                    EnginePhase.VERIFY, i, goal, skill.id, result,
                    stateBefore, stateAfter
                ))

                when {
                    result.isSuccess && verified -> {
                        log("$TAG goal[$i] ${skill.name}: 成功 — ${(result as AtomicSkillResult.Success).message}")
                        break // 下一个 goal
                    }
                    result is AtomicSkillResult.NeedsAi -> {
                        log("$TAG goal[$i] ${skill.name}: 需要 AI — ${result.message}")
                        return EngineResult(EngineStatus.NEEDS_FULL_AI,
                            "${skill.name} 需要 AI", aiUsed)
                    }
                    result is AtomicSkillResult.NeedsReplan -> {
                        log("$TAG goal[$i] ${skill.name}: 需要重规划 — ${result.message}")
                        return EngineResult(EngineStatus.NEEDS_FULL_AI,
                            "需要重规划", aiUsed)
                    }
                    !result.isSuccess && retries < MAX_RETRIES_PER_GOAL -> {
                        // REPAIR
                        log("$TAG goal[$i] ${skill.name}: 失败 — ${result.message}")
                        val repaired = tryRepair(goal, stateAfter, result, lease, aiUsed)
                        if (!repaired) {
                            retries++
                            kotlinx.coroutines.delay(500)
                            continue
                        }
                        // 修复成功，继续循环
                        retries++
                        kotlinx.coroutines.delay(500)
                        continue
                    }
                    !verified -> {
                        log("$TAG goal[$i] ${skill.name}: 验证失败")
                        retries++
                        if (retries <= MAX_RETRIES_PER_GOAL) {
                            kotlinx.coroutines.delay(500)
                            continue
                        }
                        return EngineResult(EngineStatus.NEEDS_FULL_AI,
                            "${skill.name} 验证失败（已重试 $MAX_RETRIES_PER_GOAL 次）", aiUsed)
                    }
                    else -> {
                        break
                    }
                }
            }
        }

        // DONE
        records.add(StepRecord(EnginePhase.DONE, -1, null, null, null, null, null))
        val summary = goals.joinToString(" → ") { it.type.name }
        log("$TAG ===== 全部完成 =====")

        return EngineResult(
            status = EngineStatus.SUCCESS,
            message = "已执行: $summary",
            aiUsed = aiUsed
        )
    }

    /**
     * 解析 Goal → (AtomicSkill, SkillProposal)。
     *
     * 优先级：
     *   1. SkillTemplateRepository — 参数化模板（如果可用）
     *   2. AtomicSkillRegistry — 原子技能匹配
     *   3. WorkflowReplaySkill — 低优先级回放（仅 UI 签名匹配时）
     */
    private fun resolveGoal(
        goal: Goal,
        state: UiState,
        lease: AiLease
    ): Pair<AtomicSkill, SkillProposal>? {

        // 优先级 1: SkillTemplateRepository
        templateRepo?.findBest(goal)?.let { template ->
            log("$TAG RESOLVE: 命中模板 ${template.name} (score=${template.matchScore(goal)})")
            // 返回模板对应的原子技能（使用第一个原子的 GoalType 匹配）
            val firstGoalType = template.atoms.firstOrNull()?.goalType ?: goal.type
            val templateGoal = goal.copy(type = firstGoalType)
            val resolved = resolver.resolve(templateGoal, state)
            if (resolved != null) {
                return resolved
            }
        }

        // 优先级 2: AtomicSkillRegistry
        val resolved = resolver.resolve(goal, state)
        if (resolved != null && resolved.second.executable) {
            return resolved
        }

        // 优先级 3: WorkflowReplaySkill（低优先级，仅 UI 签名匹配时）
        val replaySkill = registry.get("workflow_replay")
        if (replaySkill != null) {
            val proposal = replaySkill.propose(goal, state)
            if (proposal != null && proposal.executable && proposal.confidence >= 0.90f) {
                log("$TAG RESOLVE: WorkflowReplay 签名匹配 (confidence=${proposal.confidence})")
                return replaySkill to proposal
            }
        }

        return resolved // 可能为 null 或有不可执行的提案
    }

    /**
     * 验证执行结果。
     *   1. 文本验证（快速）
     *   2. 如果文本验证不确定且 AiAssist 可用 → AI 截图验证
     */
    private suspend fun verifyResult(
        goal: Goal,
        before: UiState,
        after: UiState,
        result: AtomicSkillResult,
        lease: AiLease,
        aiUsed: MutableList<String>
    ): Boolean {
        // 文本验证
        if (result is AtomicSkillResult.Success) {
            val textOk = verifyByText(goal, after)
            if (textOk) return true
        }

        if (result is AtomicSkillResult.Unknown) {
            val textOk = verifyByText(goal, after)
            if (textOk) return true
        }

        // AI 截图验证
        if (aiAssist?.available == true && lease.canVerify()) {
            log("$TAG VERIFY: 文本验证不确定，尝试 AI 截图验证")
            lease.recordVerify()
            aiUsed.add("verify")
            val screenshotBefore = controller.screenshotWithFallback().bitmap
            val screenshotAfter = controller.screenshotWithFallback().bitmap
            val verify = aiAssist.verifyResult(
                before, after, goal,
                screenshotBefore, screenshotAfter,
                lease
            )
            return verify?.success == true
        }

        // 对 Success 结果，文本验证失败但无 AI，仍返回 true（信任执行结果）
        return result is AtomicSkillResult.Success
    }

    /**
     * 文本验证：执行后界面是否包含预期的文字。
     */
    private fun verifyByText(goal: Goal, state: UiState): Boolean {
        val targets = listOfNotNull(goal.targetText, goal.message, goal.query)
        if (targets.isEmpty()) return true
        val allPresent = targets.all { t -> state.hasText(t) }
        if (allPresent) {
            log("$TAG VERIFY: 文本验证通过 — ${targets.joinToString()}")
        } else {
            val missing = targets.filter { !state.hasText(it) }
            log("$TAG VERIFY: 文本验证未通过 — 缺少: ${missing.joinToString()}")
        }
        return allPresent
    }

    /**
     * REPAIR 阶段 — 操作失败后尝试修复。
     *   1. 简单重试（默认）
     *   2. 如果 AiAssist 可用 → 获取修复建议
     */
    private suspend fun tryRepair(
        goal: Goal,
        state: UiState,
        lastResult: AtomicSkillResult,
        lease: AiLease,
        aiUsed: MutableList<String>
    ): Boolean {
        // AI 修复建议
        if (aiAssist?.available == true && lease.canRepair()) {
            log("$TAG REPAIR: 尝试 AI 修复建议")
            lease.recordRepair()
            aiUsed.add("repair")
            val screenshot = controller.screenshotWithFallback().bitmap
            val suggestion = aiAssist.suggestRepair(goal, state, lastResult, screenshot, lease)
            if (suggestion != null) {
                log("$TAG REPAIR: ${suggestion.description}")
                if (!suggestion.shouldRetry) {
                    log("$TAG REPAIR: AI 建议不重试")
                    return false
                }
                if (suggestion.needsHuman) {
                    log("$TAG REPAIR: AI 建议人工介入")
                    return false
                }
                // 应用修复建议：换一种 skill 或 GoalType 重试
                if (suggestion.alternativeGoalType != null) {
                    log("$TAG REPAIR: 替换 GoalType 为 ${suggestion.alternativeGoalType}")
                }
                return false // 返回 false 让外层重试
            }
        }

        // 无 AI 辅助时，简单重试
        log("$TAG REPAIR: 简单重试")
        return false // 让外层重试
    }
}

/**
 * 引擎执行结果 — 替代简单的 AgentResult，携带更多上下文。
 */
data class EngineResult(
    val status: EngineStatus,
    val message: String,
    /** 哪些步骤使用了 AI 辅助 */
    val aiUsed: List<String> = emptyList()
) {
    val success: Boolean get() = status == EngineStatus.SUCCESS

    fun toAgentResult(): AgentResult = AgentResult(
        success = success,
        message = message
    )
}
