package com.roubao.autopilot.skills.atomic

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.skills.*

/**
 * SendMessageSkill — 点击发送按钮并验证消息已发送。
 */
class SendMessageSkill(
    private val controller: DeviceController,
    private val observer: UiObserver
) : AtomicSkill {
    override val id = "send_message"
    override val name = "发送消息"

    override fun propose(goal: Goal, state: UiState): SkillProposal? {
        if (goal.type != GoalType.SendMessage) return null
        val sendNode = state.nodes.firstOrNull {
            it.clickable && it.visible &&
            ((it.text?.contains("发送") == true) || (it.contentDesc?.contains("发送") == true))
        }
        return SkillProposal(
            skillId = id,
            confidence = if (sendNode != null) 0.95f else 0.5f,
            executable = sendNode != null,
            requiresAi = sendNode == null,
            reason = if (sendNode != null) "找到发送按钮" else "未找到发送按钮，需要 AI"
        )
    }

    override suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult {
        val message = goal.message ?: args["message"] ?: return AtomicSkillResult.Failed("缺少消息内容")

        val sendNode = state.nodes.firstOrNull {
            it.clickable && it.visible &&
            ((it.text?.contains("发送") == true) || (it.contentDesc?.contains("发送") == true))
        }

        if (sendNode == null) {
            if (!controller.clickByTarget(SkillTarget(text = "发送"))) {
                return AtomicSkillResult.NeedsAi("找不到发送按钮")
            }
        } else {
            controller.tap(sendNode.bounds.centerX(), sendNode.bounds.centerY())
        }

        kotlinx.coroutines.delay(600)
        val after = observer.capture()

        // 验证：消息出现在屏幕上，且输入框可能已清空
        val messageVisible = after.hasText(message)
        val inputCleared = after.editableNodes.none { it.text?.contains(message) == true }

        return if (messageVisible) {
            AtomicSkillResult.Success("消息已发送并确认出现在屏幕上")
        } else if (inputCleared) {
            AtomicSkillResult.Success("输入框已清空，推断发送成功")
        } else {
            AtomicSkillResult.Unknown("点击发送已执行，但未能确认消息是否发送")
        }
    }
}
