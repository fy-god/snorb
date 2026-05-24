package com.roubao.autopilot.skills.atomic

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.skills.*

/**
 * ScrollUntilSkill — 滑动直到看到目标元素，或达到最大次数。
 */
class ScrollUntilSkill(
    private val controller: DeviceController,
    private val observer: UiObserver
) : AtomicSkill {
    override val id = "scroll_until"
    override val name = "滑动直到可见"

    override fun propose(goal: Goal, state: UiState): SkillProposal? {
        if (goal.type != GoalType.ScrollUntilVisible) return null
        val target = goal.targetText ?: return null
        val alreadyVisible = FindElementSkill.findNode(target, state) != null
        val hasScrollable = state.scrollableNodes.isNotEmpty()
        return SkillProposal(
            skillId = id,
            confidence = if (alreadyVisible) 1.0f else if (hasScrollable) 0.8f else 0.5f,
            executable = true,
            requiresAi = false,
            reason = if (alreadyVisible) "目标已可见" else "需要滑动查找 '$target'"
        )
    }

    override suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult {
        val target = goal.targetText ?: args["target"] ?: return AtomicSkillResult.Failed("缺少目标")
        val maxSwipes = args["maxSwipes"]?.toIntOrNull() ?: 6

        var prevHash = state.screenText.hashCode()

        for (i in 0 until maxSwipes) {
            val current = observer.capture()
            if (FindElementSkill.findNode(target, current) != null) {
                return AtomicSkillResult.Success("滑动 ${i + 1} 次后找到 '$target'")
            }

            val scrollable = current.scrollableNodes.firstOrNull()
            if (scrollable != null) {
                controller.swipe(scrollable.bounds.centerX(), scrollable.bounds.bottom, scrollable.bounds.centerX(), scrollable.bounds.top)
            } else {
                val (w, h) = controller.getScreenSize()
                controller.swipe(w / 2, h * 3 / 4, w / 2, h / 4)
            }

            kotlinx.coroutines.delay(600)
            val after = observer.capture()
            val newHash = after.screenText.hashCode()
            if (newHash == prevHash) {
                return AtomicSkillResult.Failed("滑动后页面未变化，可能已到底")
            }
            prevHash = newHash
        }

        return AtomicSkillResult.NeedsAi("滑动 $maxSwipes 次仍未找到 '$target'")
    }
}
