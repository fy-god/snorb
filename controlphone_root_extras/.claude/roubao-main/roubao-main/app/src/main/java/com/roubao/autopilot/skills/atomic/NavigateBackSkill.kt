package com.roubao.autopilot.skills.atomic

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.skills.*

/**
 * NavigateBackSkill — 返回上一页，验证页面已变化。
 */
class NavigateBackSkill(
    private val controller: DeviceController,
    private val observer: UiObserver
) : AtomicSkill {
    override val id = "navigate_back"
    override val name = "返回"

    override fun propose(goal: Goal, state: UiState): SkillProposal? {
        if (goal.type != GoalType.NavigateBack) return null
        return SkillProposal(
            skillId = id,
            confidence = 0.95f,
            executable = true,
            requiresAi = false,
            reason = "系统返回键稳定"
        )
    }

    override suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult {
        val beforeHash = state.screenText.hashCode()
        controller.back()
        kotlinx.coroutines.delay(400)
        val after = observer.capture()
        return if (after.screenText.hashCode() != beforeHash) {
            AtomicSkillResult.Success("返回成功，页面已变化")
        } else {
            AtomicSkillResult.Unknown("返回已执行，但页面未变化")
        }
    }
}
