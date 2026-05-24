package com.roubao.autopilot.skills

import com.roubao.autopilot.agent.AgentResult
import com.roubao.autopilot.controller.DeviceController

/**
 * PlanExecutor — 执行用户计划中的子目标序列。
 *
 * 对每个 PlanGoal，调用匹配的 AtomicSkill.execute()。
 * 执行失败 / NeedsAi 时返回 null，让上层降级到 VLM。
 */
class PlanExecutor(
    private val controller: DeviceController,
    private val observer: UiObserver,
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "[PlanExecutor]"
    }

    /**
     * 执行单个 goal。
     * 成功返回 AgentResult，失败返回 null 表示需要降级 AI。
     */
    suspend fun executeGoal(
        goal: PlanGoal,
        skill: AtomicSkill,
        proposal: SkillProposal
    ): AgentResult? {
        val g = goal.goal ?: return null
        log("$TAG 执行: ${skill.name} (${skill.id}) goal=${g.type} confidence=${proposal.confidence}")

        val result = skill.execute(g, emptyMap(), observer.capture())

        return when (result) {
            is AtomicSkillResult.Success -> {
                log("$TAG ${skill.name}: ${result.message}")
                AgentResult(success = true, message = result.message)
            }
            is AtomicSkillResult.Failed -> {
                log("$TAG ${skill.name} 失败: ${result.message}")
                null // 降级 AI
            }
            is AtomicSkillResult.Unknown -> {
                log("$TAG ${skill.name} 未知结果: ${result.message}，尝试继续")
                AgentResult(success = true, message = result.message)
            }
            is AtomicSkillResult.NeedsAi -> {
                log("$TAG ${skill.name} 需要 AI: ${result.message}")
                null // 降级 AI
            }
            is AtomicSkillResult.NeedsReplan -> {
                log("$TAG ${skill.name} 需要重规划: ${result.message}")
                null
            }
        }
    }

    /**
     * 执行完整计划 — 遍历所有 goal，逐个执行。
     * 任何一个 goal 失败就停止并返回 null。
     */
    suspend fun executePlan(plan: UserPlan, resolver: AtomicSkillResolver): AgentResult? {
        if (plan.goals.isEmpty()) return null

        val executed = mutableListOf<String>()

        for (goal in plan.goals) {
            val state = observer.capture()
            val resolved = resolver.resolve(goal.goal ?: return null, state)

            if (resolved == null) {
                log("$TAG 无法解析 goal=${goal.title}，降级 AI")
                // 如果已经有部分 goal 执行成功，报告部分成功
                if (executed.isNotEmpty()) {
                    return AgentResult(success = true, message = "部分执行: ${executed.joinToString(" → ")}")
                }
                return null
            }

            val (skill, proposal) = resolved

            if (!proposal.executable || proposal.requiresAi) {
                log("$TAG goal=${goal.title} 需要 AI: ${proposal.reason}")
                if (executed.isNotEmpty()) {
                    return AgentResult(success = true, message = "部分执行: ${executed.joinToString(" → ")}，剩余需 AI")
                }
                return null
            }

            val result = executeGoal(
                goal = goal.copy(selectedSkill = proposal),
                skill = skill,
                proposal = proposal
            )

            if (result != null) {
                executed.add(skill.name)
            } else {
                // goal 执行失败
                if (executed.isNotEmpty()) {
                    return AgentResult(success = true, message = "部分执行: ${executed.joinToString(" → ")}，${skill.name} 失败")
                }
                return null
            }
        }

        return AgentResult(success = true, message = "完整执行: ${executed.joinToString(" → ")}")
    }
}
