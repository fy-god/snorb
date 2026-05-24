package com.roubao.autopilot.skills.atomic

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.skills.*

/**
 * TypeTextSkill — 找到输入框并输入文字，然后验证输入框内容。
 */
class TypeTextSkill(
    private val controller: DeviceController,
    private val observer: UiObserver
) : AtomicSkill {
    override val id = "type_text"
    override val name = "输入文字"

    override fun propose(goal: Goal, state: UiState): SkillProposal? {
        if (goal.type != GoalType.TypeMessage) return null
        val hasEditable = state.editableNodes.isNotEmpty()
        return SkillProposal(
            skillId = id,
            confidence = if (hasEditable) 0.95f else 0.45f,
            executable = hasEditable,
            requiresAi = !hasEditable,
            reason = if (hasEditable) "当前界面有输入框" else "未找到输入框，需要 AI"
        )
    }

    override suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult {
        val text = goal.message ?: args["text"] ?: return AtomicSkillResult.Failed("缺少输入文本")

        val input = state.editableNodes.firstOrNull()
        if (input == null) {
            // 尝试聚焦任意输入框
            if (!controller.focusByTarget(SkillTarget(className = "android.widget.EditText"))) {
                return AtomicSkillResult.NeedsAi("未找到可输入节点")
            }
        } else {
            controller.tap(input.bounds.centerX(), input.bounds.centerY())
        }

        kotlinx.coroutines.delay(300)

        val ok = controller.setTextByAccessibility(
            SkillTarget(className = "android.widget.EditText"), text
        ) || run {
            controller.type(text)
            true
        }

        if (!ok) return AtomicSkillResult.Failed("输入动作失败")

        kotlinx.coroutines.delay(300)
        val after = observer.capture()
        val verified = after.screenText.any { it.contains(text) }

        return if (verified) AtomicSkillResult.Success("输入 '$text' 成功且已验证")
        else AtomicSkillResult.Unknown("输入已执行，但无法确认文字是否进入输入框")
    }
}
