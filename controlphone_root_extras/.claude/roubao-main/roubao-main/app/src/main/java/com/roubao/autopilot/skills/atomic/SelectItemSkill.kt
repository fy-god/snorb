package com.roubao.autopilot.skills.atomic

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.skills.*

/**
 * SelectItemSkill — 从列表/搜索结果中选择目标项并点击进入。
 */
class SelectItemSkill(
    private val controller: DeviceController,
    private val observer: UiObserver
) : AtomicSkill {
    override val id = "select_item"
    override val name = "选择列表项"

    override fun propose(goal: Goal, state: UiState): SkillProposal? {
        if (goal.type != GoalType.SelectItem && goal.type != GoalType.OpenConversation) return null
        val target = goal.targetText ?: return null
        val matches = state.nodes.filter {
            it.visible && it.clickable &&
            ((it.text?.contains(target) == true) || (it.contentDesc?.contains(target) == true))
        }
        return SkillProposal(
            skillId = id,
            confidence = if (matches.isNotEmpty()) 0.85f else 0.3f,
            executable = matches.isNotEmpty(),
            requiresAi = matches.isEmpty(),
            reason = if (matches.isNotEmpty()) "找到 ${matches.size} 个匹配项" else "未找到 '$target'"
        )
    }

    override suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult {
        val target = goal.targetText ?: args["target"] ?: return AtomicSkillResult.Failed("缺少目标")

        val candidates = state.nodes.filter {
            it.visible && it.clickable &&
            ((it.text?.contains(target) == true) || (it.contentDesc?.contains(target) == true))
        }

        if (candidates.isEmpty()) {
            return AtomicSkillResult.NeedsAi("未在界面上找到可点击的 '$target'")
        }

        // 取最下方的匹配（通常是搜索结果中最佳的一项）
        val best = candidates.maxByOrNull { it.bounds.centerY() } ?: candidates.first()
        controller.tap(best.bounds.centerX(), best.bounds.centerY())
        kotlinx.coroutines.delay(600)

        val after = observer.capture()
        val changed = after.screenText.hashCode() != state.screenText.hashCode()
        return if (changed) AtomicSkillResult.Success("已选择 '$target'，页面已变化")
        else AtomicSkillResult.Unknown("点击 '$target' 已执行，但页面未变化")
    }
}
