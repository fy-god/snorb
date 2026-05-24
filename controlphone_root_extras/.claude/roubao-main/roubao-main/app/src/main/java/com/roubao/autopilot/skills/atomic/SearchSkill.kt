package com.roubao.autopilot.skills.atomic

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.skills.*

/**
 * SearchSkill — 在当前 App 内执行搜索。
 * 流程：找搜索入口 → 点击 → 输入 → 提交 → 验证。
 */
class SearchSkill(
    private val controller: DeviceController,
    private val observer: UiObserver
) : AtomicSkill {
    override val id = "search_current_app"
    override val name = "搜索"

    override fun propose(goal: Goal, state: UiState): SkillProposal? {
        if (goal.type != GoalType.SearchCurrentApp) return null
        val hasSearchEntry = state.nodes.any {
            val t = it.text ?: ""
            val d = it.contentDesc ?: ""
            t.contains("搜索") || d.contains("搜索") || t.contains("查找")
        }
        val hasEditable = state.editableNodes.isNotEmpty()
        return SkillProposal(
            skillId = id,
            confidence = if (hasEditable) 0.9f else if (hasSearchEntry) 0.8f else 0.35f,
            executable = hasEditable || hasSearchEntry,
            requiresAi = !hasEditable && !hasSearchEntry,
            reason = when {
                hasEditable -> "当前界面已有输入框"
                hasSearchEntry -> "找到搜索入口"
                else -> "未找到搜索入口，需要 AI"
            }
        )
    }

    override suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult {
        val query = goal.query ?: args["query"] ?: return AtomicSkillResult.Failed("缺少搜索关键词")

        // 如果当前没有输入框，先找搜索入口
        if (state.editableNodes.isEmpty()) {
            val searchNode = state.nodes.firstOrNull {
                (it.text?.contains("搜索") == true || it.contentDesc?.contains("搜索") == true) && it.clickable
            }
            if (searchNode != null) {
                controller.tap(searchNode.bounds.centerX(), searchNode.bounds.centerY())
                kotlinx.coroutines.delay(500)
            } else {
                return AtomicSkillResult.NeedsAi("找不到搜索入口")
            }
        }

        val afterClick = observer.capture()
        val input = afterClick.editableNodes.firstOrNull()
            ?: return AtomicSkillResult.NeedsAi("点击搜索入口后未出现输入框")

        controller.tap(input.bounds.centerX(), input.bounds.centerY())
        controller.type(query)
        kotlinx.coroutines.delay(300)
        controller.enter()
        kotlinx.coroutines.delay(600)

        val after = observer.capture()
        val verified = after.hasText(query)
        return if (verified) AtomicSkillResult.Success("搜索 '$query' 成功")
        else AtomicSkillResult.Unknown("已提交搜索，但结果未确认")
    }
}
