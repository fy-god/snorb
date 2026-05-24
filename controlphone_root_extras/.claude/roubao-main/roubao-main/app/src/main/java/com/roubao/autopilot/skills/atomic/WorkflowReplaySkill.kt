package com.roubao.autopilot.skills.atomic

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.skills.*

/**
 * WorkflowReplaySkill — 在 UI 签名匹配时回放已录制的工作流。
 *
 * 安全检查：
 *  1. 录制时保存 UiSignature
 *  2. 执行前 ReplayGuard.check(recorded, current)
 *  3. EXACT / SIMILAR → 允许回放
 *  4. DIFFERENT → 返回 NeedsAi
 *
 * 这是唯一允许回放预录制步骤的路径，所有回放都经过 UiSignature 校验。
 */
class WorkflowReplaySkill(
    private val controller: DeviceController,
    private val observer: UiObserver
) : AtomicSkill {
    override val id = "workflow_replay"
    override val name = "回放已录制流程"

    /**
     * 已录制的流程及签名
     */
    data class RecordedEntry(
        val plan: SkillWorkflowPlan,
        val signature: UiSignature,
        val goalHint: String // 匹配用：如 "发微信消息给X"
    )

    private val recorded = mutableListOf<RecordedEntry>()

    fun register(plan: SkillWorkflowPlan, signature: UiSignature, goalHint: String) {
        recorded.add(RecordedEntry(plan, signature, goalHint))
    }

    override fun propose(goal: Goal, state: UiState): SkillProposal? {
        if (recorded.isEmpty()) return null

        val currentSig = UiSignature.from(state)
        val best = recorded
            .mapNotNull { entry ->
                val match = ReplayGuard.check(entry.signature, currentSig)
                if (match == SignatureMatchLevel.DIFFERENT) return@mapNotNull null
                val confidence = when (match) {
                    SignatureMatchLevel.EXACT -> 0.92f
                    SignatureMatchLevel.SIMILAR -> 0.75f
                    else -> 0f
                }
                entry to confidence
            }
            .maxByOrNull { it.second }

        if (best == null) return null

        val (entry, confidence) = best
        return SkillProposal(
            skillId = id,
            confidence = confidence,
            executable = confidence >= 0.75f,
            requiresAi = false,
            reason = "已有录制流程可回放: ${entry.plan.name}"
        )
    }

    override suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult {
        if (recorded.isEmpty()) {
            return AtomicSkillResult.NeedsAi("无已录制流程")
        }

        val currentSig = UiSignature.from(state)
        val best = recorded
            .mapNotNull { entry ->
                val match = ReplayGuard.check(entry.signature, currentSig)
                if (match == SignatureMatchLevel.DIFFERENT) return@mapNotNull null
                val score = when (match) {
                    SignatureMatchLevel.EXACT -> 2
                    SignatureMatchLevel.SIMILAR -> 1
                    else -> 0
                }
                entry to score
            }
            .maxByOrNull { it.second }

        if (best == null) {
            return AtomicSkillResult.NeedsAi("UI 签名不匹配任何已录制流程")
        }

        val (entry, _) = best

        // 参数替换
        val params = mutableMapOf<String, String>()
        goal.targetText?.let { params["target"] = it }
        goal.message?.let { params["message"] = it }
        goal.query?.let { params["query"] = it }
        goal.appName?.let { params["appName"] = it }

        val plan = if (params.isNotEmpty()) entry.plan.replaceParams(params) else entry.plan

        val executor = SkillStepExecutor(controller)
        val result = executor.execute(plan)

        return if (result.success) {
            AtomicSkillResult.Success("回放成功: ${plan.name}")
        } else {
            AtomicSkillResult.Failed("回放失败: ${result.message}")
        }
    }
}
