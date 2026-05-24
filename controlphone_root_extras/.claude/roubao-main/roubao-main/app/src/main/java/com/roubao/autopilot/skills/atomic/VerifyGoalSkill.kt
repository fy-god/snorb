package com.roubao.autopilot.skills.atomic

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.skills.*

/**
 * VerifyGoalSkill — 验证用户目标是否达成。
 */
class VerifyGoalSkill(
    private val controller: DeviceController,
    private val observer: UiObserver
) : AtomicSkill {
    override val id = "verify_goal"
    override val name = "验证目标达成"

    override fun propose(goal: Goal, state: UiState): SkillProposal? {
        if (goal.type != GoalType.VerifyGoal) return null
        return SkillProposal(
            skillId = id,
            confidence = 0.7f,
            executable = true,
            requiresAi = true, // 最终验证常需要 AI 看截图
            reason = "最终验证需结合截图和 UI 状态"
        )
    }

    override suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult {
        val targetText = goal.targetText ?: args["targetText"]
        val message = goal.message ?: args["message"]

        // 纯文本验证
        if (targetText != null && message != null) {
            val textOk = state.hasText(targetText) && state.hasText(message)
            if (textOk) return AtomicSkillResult.Success("验证成功：界面包含 '$targetText' 和 '$message'")
        }

        if (message != null) {
            val msgOk = state.hasText(message)
            if (msgOk) return AtomicSkillResult.Success("验证成功：界面包含 '$message'")
        }

        if (targetText != null) {
            val targetOk = state.hasText(targetText)
            if (targetOk) return AtomicSkillResult.Success("验证成功：界面包含 '$targetText'")
        }

        // 需要 AI 视觉验证
        return AtomicSkillResult.NeedsAi("纯文本验证不足以确认目标达成，需要 AI 截图判断")
    }
}
