package com.roubao.autopilot.skills

import com.roubao.autopilot.agent.AgentResult
import com.roubao.autopilot.controller.DeviceController

/**
 * 引擎执行阶段
 */
enum class EnginePhase {
    OBSERVE,
    PLAN,
    RESOLVE,
    EXECUTE,
    VERIFY,
    REPAIR,
    DONE,
    FAILED
}

/**
 * 单步执行记录
 */
data class StepRecord(
    val phase: EnginePhase,
    val goalIndex: Int,
    val goal: Goal?,
    val skillId: String?,
    val result: AtomicSkillResult?,
    val stateBefore: UiState?,
    val stateAfter: UiState?
)

/**
 * NewSkillEngine — 新的本地执行引擎。
 *
 * 流程：
 *   OBSERVE → PLAN → [for each goal: RESOLVE → EXECUTE → VERIFY] → DONE
 *   任何 goal 失败 → AI 降级
 *
 * 与旧路径的关键区别：
 *   - 不走硬编码 workflow，全靠 AtomicSkill 按 Goal + UiState 匹配
 *   - 每步执行后做验证（文本 + 可选的 AI 截图）
 *   - 找不到可执行 skill 时直接降级 AI，不硬拼
 */
class NewSkillEngine(
    private val controller: DeviceController,
    private val vlmScreenshotVerify: (suspend (before: UiState, after: UiState, goal: Goal) -> Boolean)? = null,
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "[SkillEngine]"
        private const val MAX_RETRIES_PER_GOAL = 1
    }

    private val observer = UiObserver(controller)
    private val intentParser = IntentParser()
    private val decomposer = GoalDecomposer()
    private val planner = TaskPlanner(intentParser, decomposer)
    private val registry = AtomicSkillRegistry(controller, observer)
    private val resolver = AtomicSkillResolver(registry)

    /** 执行追踪，用于诊断 */
    val records = mutableListOf<StepRecord>()

    /**
     * 主入口：接收用户指令，返回 AgentResult。
     * 成功 → AgentResult.success
     * 无可执行本地路径 → AgentResult.failure（调用者应降级 VLM）
     */
    suspend fun execute(instruction: String): AgentResult {
        records.clear()
        log("$TAG ===== 开始处理: $instruction =====")

        // Phase 1: OBSERVE
        val initialState = observer.capture()
        log("$TAG OBSERVE: pkg=${initialState.packageName} nodes=${initialState.nodes.size}")
        records.add(StepRecord(EnginePhase.OBSERVE, -1, null, null, null, null, initialState))

        // Phase 2: PLAN
        val plan = planner.createPlan(instruction)
        log("$TAG PLAN: ${plan.goals.size} 个子目标")
        records.add(StepRecord(EnginePhase.PLAN, -1, null, null, null, null, null))

        if (plan.goals.isEmpty()) {
            log("$TAG PLAN 失败: 无法分解为子目标")
            return AgentResult(success = false, message = "无法分解用户指令为子目标")
        }

        // Phase 3-6: 对每个 goal 执行 RESOLVE → EXECUTE → VERIFY
        for ((i, planGoal) in plan.goals.withIndex()) {
            val goal = planGoal.goal
            if (goal == null) {
                log("$TAG goal[$i] 无 Goal 对象，需要 AI")
                return AgentResult(success = false, message = "子目标 ${planGoal.title} 需要 AI 处理")
            }

            val stateBefore = observer.capture()

            // RESOLVE
            val resolved = resolver.resolve(goal, stateBefore)
            records.add(StepRecord(EnginePhase.RESOLVE, i, goal, resolved?.first?.id, null, stateBefore, null))

            if (resolved == null) {
                log("$TAG goal[$i] ${goal.type}: 无 AtomicSkill 匹配，降级 AI")
                return AgentResult(success = false, message = "${goal.type} 无本地技能可用")
            }

            val (skill, proposal) = resolved

            if (!proposal.executable || proposal.requiresAi) {
                log("$TAG goal[$i] ${goal.type}: AtomicSkill=${skill.id} 但需要 AI — ${proposal.reason}")
                return AgentResult(success = false, message = "${goal.type} 需要 AI: ${proposal.reason}")
            }

            log("$TAG goal[$i] ${goal.type} → ${skill.id} confidence=${proposal.confidence}")

            // EXECUTE (with retry)
            var result = skill.execute(goal, emptyMap(), stateBefore)
            var retries = 0

            while (result is AtomicSkillResult.Failed && retries < MAX_RETRIES_PER_GOAL) {
                log("$TAG goal[$i] 重试 ${retries + 1}/${MAX_RETRIES_PER_GOAL}: ${(result as AtomicSkillResult.Failed).message}")
                kotlinx.coroutines.delay(500)
                val retryState = observer.capture()
                result = skill.execute(goal, emptyMap(), retryState)
                retries++
            }

            // VERIFY
            val stateAfter = observer.capture()
            kotlinx.coroutines.delay(200)

            val verified = when (result) {
                is AtomicSkillResult.Success -> {
                    // 文本验证
                    verifyByText(goal, stateAfter)
                }
                is AtomicSkillResult.Unknown -> {
                    // 文本验证 + 可选 VLM 截图验证
                    val textOk = verifyByText(goal, stateAfter)
                    if (!textOk && vlmScreenshotVerify != null) {
                        log("$TAG goal[$i] 文本验证不确定，尝试 VLM 截图验证")
                        vlmScreenshotVerify?.invoke(stateBefore, stateAfter, goal) == true
                    } else textOk
                }
                else -> false
            }

            records.add(StepRecord(
                EnginePhase.VERIFY, i, goal, skill.id, result,
                stateBefore, stateAfter
            ))

            val finalSuccess = result.isSuccess && verified

            if (!finalSuccess) {
                when (result) {
                    is AtomicSkillResult.Failed -> {
                        log("$TAG goal[$i] 执行失败: ${result.message}")
                        return AgentResult(success = false, message = "${skill.name} 失败: ${result.message}")
                    }
                    is AtomicSkillResult.NeedsAi -> {
                        log("$TAG goal[$i] 需要 AI: ${result.message}")
                        return AgentResult(success = false, message = "${skill.name} 需要 AI")
                    }
                    is AtomicSkillResult.NeedsReplan -> {
                        log("$TAG goal[$i] 需要重规划: ${result.message}")
                        return AgentResult(success = false, message = "需要重规划")
                    }
                    is AtomicSkillResult.Unknown -> {
                        if (!verified) {
                            log("$TAG goal[$i] 执行结果未知且验证失败，降级 AI")
                            return AgentResult(success = false, message = "${skill.name} 执行结果无法验证")
                        }
                        log("$TAG goal[$i] 执行结果未知但验证通过，继续")
                    }
                    is AtomicSkillResult.Success -> {
                        // result.success 但 !verified — 不太可能，但处理一下
                        if (!verified) {
                            log("$TAG goal[$i] 执行报告成功但验证失败，降级 AI")
                            return AgentResult(success = false, message = "验证失败")
                        }
                    }
                }
            }

            val resultMsg = when (result) {
                is AtomicSkillResult.Success -> result.message
                is AtomicSkillResult.Failed -> result.message
                is AtomicSkillResult.Unknown -> result.message
                is AtomicSkillResult.NeedsAi -> result.message
                is AtomicSkillResult.NeedsReplan -> result.message
            }
            log("$TAG goal[$i] ${skill.name}: 完成 — $resultMsg")
        }

        // DONE
        records.add(StepRecord(EnginePhase.DONE, -1, null, null, null, null, null))
        log("$TAG ===== 全部完成 =====")

        val summary = plan.goals.joinToString(" → ") { it.title }
        return AgentResult(success = true, message = "已执行: $summary")
    }

    /**
     * 文本验证：执行后界面是否包含预期的文字。
     */
    private fun verifyByText(goal: Goal, state: UiState): Boolean {
        val targets = listOfNotNull(goal.targetText, goal.message, goal.query)
        if (targets.isEmpty()) return true // 无验证目标，默认通过
        val allPresent = targets.all { t -> state.hasText(t) }
        if (allPresent) {
            log("$TAG VERIFY: 文本验证通过 — ${targets.joinToString()}")
        } else {
            val missing = targets.filter { !state.hasText(it) }
            log("$TAG VERIFY: 文本验证未通过 — 缺少: ${missing.joinToString()}")
        }
        return allPresent
    }
}
