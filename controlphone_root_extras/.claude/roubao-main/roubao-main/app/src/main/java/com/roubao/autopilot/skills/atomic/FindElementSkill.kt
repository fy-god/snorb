package com.roubao.autopilot.skills.atomic

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.skills.*

/**
 * FindElementSkill — 在 UI 树中按语义查找元素。
 */
class FindElementSkill(
    private val controller: DeviceController
) : AtomicSkill {
    override val id = "find_element"
    override val name = "查找界面元素"

    override fun propose(goal: Goal, state: UiState): SkillProposal? {
        if (goal.type != GoalType.FindElement && goal.type != GoalType.FindContactOrConversation) return null
        val target = goal.targetText ?: return null
        val found = findNode(target, state)
        return SkillProposal(
            skillId = id,
            confidence = if (found != null) 0.9f else 0.4f,
            executable = found != null,
            requiresAi = found == null,
            reason = if (found != null) "界面上找到 '$target'" else "未找到 '$target'，需要 AI 视觉"
        )
    }

    override suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult {
        val target = goal.targetText ?: args["target"] ?: return AtomicSkillResult.Failed("缺少查找目标")
        val node = findNode(target, state)
        return if (node != null) {
            AtomicSkillResult.Success("找到 '$target' at (${node.bounds.centerX()}, ${node.bounds.centerY()})")
        } else {
            AtomicSkillResult.NeedsAi("未能在无障碍树中找到 '$target'")
        }
    }

    companion object {
        fun findNode(text: String, state: UiState): UiNode? {
            return state.nodes.firstOrNull { node ->
                node.visible && (
                    node.text?.contains(text) == true ||
                    node.contentDesc?.contains(text) == true
                )
            }
        }
    }
}
