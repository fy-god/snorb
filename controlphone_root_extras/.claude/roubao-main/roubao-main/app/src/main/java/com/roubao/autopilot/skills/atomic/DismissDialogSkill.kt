package com.roubao.autopilot.skills.atomic

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.skills.*

/**
 * DismissDialogSkill — 尝试关闭弹窗/权限请求/广告。
 */
class DismissDialogSkill(
    private val controller: DeviceController,
    private val observer: UiObserver
) : AtomicSkill {
    override val id = "dismiss_dialog"
    override val name = "关闭弹窗"

    override fun propose(goal: Goal, state: UiState): SkillProposal? {
        if (goal.type != GoalType.DismissDialog) return null
        val hasDialogLike = state.nodes.any {
            val t = it.text ?: ""
            t.contains("允许") || t.contains("取消") || t.contains("关闭") ||
            t.contains("跳过") || t.contains("我知道了") || t.contains("确定")
        }
        return SkillProposal(
            skillId = id,
            confidence = if (hasDialogLike) 0.7f else 0.3f,
            executable = true, // 总是尝试关闭
            requiresAi = false,
            reason = if (hasDialogLike) "检测到可能的弹窗" else "未检测到明显弹窗，仍尝试"
        )
    }

    override suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult {
        val beforeHash = state.screenText.hashCode()

        // 尝试点击常见关闭按钮
        val closeTexts = listOf("关闭", "取消", "跳过", "我知道了", "确定", "同意", "允许")
        for (text in closeTexts) {
            val node = state.nodes.firstOrNull {
                it.clickable && it.visible && (it.text?.contains(text) == true)
            }
            if (node != null) {
                controller.tap(node.bounds.centerX(), node.bounds.centerY())
                kotlinx.coroutines.delay(500)
                val after = observer.capture()
                if (after.screenText.hashCode() != beforeHash) {
                    return AtomicSkillResult.Success("弹窗已关闭 (点击 '$text')")
                }
            }
        }

        // fallback: 按返回键
        controller.back()
        kotlinx.coroutines.delay(400)
        val after = observer.capture()
        return if (after.screenText.hashCode() != beforeHash) {
            AtomicSkillResult.Success("弹窗已关闭 (返回键)")
        } else {
            AtomicSkillResult.Unknown("尝试关闭弹窗，但页面未变化")
        }
    }
}
