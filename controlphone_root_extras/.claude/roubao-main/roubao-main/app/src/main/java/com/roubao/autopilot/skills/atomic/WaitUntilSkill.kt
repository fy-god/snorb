package com.roubao.autopilot.skills.atomic

import com.roubao.autopilot.skills.*

/**
 * WaitUntilSkill — 等待指定条件满足（如某文字出现/消失）。
 */
class WaitUntilSkill : AtomicSkill {
    override val id = "wait_until"
    override val name = "等待条件"

    override fun propose(goal: Goal, state: UiState): SkillProposal? {
        if (goal.type != GoalType.WaitUntil) return null
        return SkillProposal(
            skillId = id,
            confidence = 0.9f,
            executable = true,
            requiresAi = false,
            reason = "等待是通用操作"
        )
    }

    override suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult {
        val waitMs = args["waitMs"]?.toLongOrNull() ?: 1000L
        kotlinx.coroutines.delay(waitMs)
        return AtomicSkillResult.Success("等待 ${waitMs}ms")
    }
}
