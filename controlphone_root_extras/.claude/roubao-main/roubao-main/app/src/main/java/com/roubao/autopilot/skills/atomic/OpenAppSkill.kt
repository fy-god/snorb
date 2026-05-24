package com.roubao.autopilot.skills.atomic

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.skills.*

/**
 * OpenAppSkill — 打开指定 App 并验证前台包名。
 */
class OpenAppSkill(
    private val controller: DeviceController,
    private val observer: UiObserver
) : AtomicSkill {
    override val id = "open_app"
    override val name = "打开应用"

    override fun propose(goal: Goal, state: UiState): SkillProposal? {
        if (goal.type != GoalType.EnsureAppReady) return null
        val pkg = goal.appPackage ?: return null
        val alreadyOpen = state.packageName == pkg
        return SkillProposal(
            skillId = id,
            confidence = if (alreadyOpen) 1.0f else 0.95f,
            executable = true,
            requiresAi = false,
            reason = if (alreadyOpen) "应用已在前台" else "shell 打开应用稳定"
        )
    }

    override suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult {
        val pkg = goal.appPackage ?: args["packageName"] ?: return AtomicSkillResult.Failed("缺少包名")
        controller.openApp(pkg)
        kotlinx.coroutines.delay(800)
        val after = observer.capture()
        return if (after.packageName == pkg) {
            AtomicSkillResult.Success("已打开 ${goal.appName ?: pkg}")
        } else {
            AtomicSkillResult.Unknown("openApp 已执行，但前台包名不匹配: 期望=$pkg 实际=${after.packageName}")
        }
    }
}
