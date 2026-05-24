package com.roubao.autopilot.skills.atomic

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.skills.*

/**
 * ClickElementSkill — 点击语义目标（非固定坐标）。
 */
class ClickElementSkill(
    private val controller: DeviceController,
    private val observer: UiObserver
) : AtomicSkill {
    override val id = "click_element"
    override val name = "点击界面元素"

    override fun propose(goal: Goal, state: UiState): SkillProposal? {
        if (goal.type != GoalType.ClickSemanticTarget && goal.type != GoalType.OpenConversation && goal.type != GoalType.SendMessage) return null
        val target = goal.targetText ?: return null
        val node = FindElementSkill.findNode(target, state)
        val hasFallback = goal.targetText == "发送" && state.clickableNodes.isNotEmpty()
        return SkillProposal(
            skillId = id,
            confidence = if (node != null) 0.9f else if (hasFallback) 0.6f else 0.3f,
            executable = node != null || hasFallback,
            requiresAi = node == null,
            reason = if (node != null) "找到可点击元素 '$target'" else "未找到 '$target'，需要 AI"
        )
    }

    override suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult {
        val target = goal.targetText ?: args["target"] ?: return AtomicSkillResult.Failed("缺少点击目标")
        val node = FindElementSkill.findNode(target, state)

        if (node != null) {
            controller.tap(node.bounds.centerX(), node.bounds.centerY())
            kotlinx.coroutines.delay(400)
            val after = observer.capture()
            val changed = after.screenText != state.screenText
            return if (changed) AtomicSkillResult.Success("点击 '$target' 成功，页面已变化")
            else AtomicSkillResult.Unknown("点击 '$target' 已执行，但页面未变化")
        }

        // fallback: accessibility click
        if (controller.clickByTarget(SkillTarget(text = target))) {
            kotlinx.coroutines.delay(400)
            return AtomicSkillResult.Success("Accessibility 点击 '$target' 成功")
        }

        return AtomicSkillResult.NeedsAi("无法通过无障碍服务点击 '$target'")
    }
}
