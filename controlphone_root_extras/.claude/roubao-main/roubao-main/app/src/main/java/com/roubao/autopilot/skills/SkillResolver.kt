package com.roubao.autopilot.skills

import com.roubao.autopilot.App

/**
 * SkillResolver — 统一查询所有本地能力来源，返回最优 SkillCandidate。
 *
 * 查询顺序（按稳定度降级）：
 *   RecordedSkillLibrary > WorkflowLibrary > FragmentLibrary > SkillWorkflowAssembler > SkillManager
 *
 * 只有 executable=true 且 confidence >= 0.75 的候选才被采纳；
 * 低置信度 / 需要 AI 的方案会被标记但不会被选为本地执行路线。
 */
class SkillResolver(
    private val appScanner: com.roubao.autopilot.controller.AppScanner
) {
    companion object {
        private const val TAG = "[SkillResolver]"
        private const val MIN_CONFIDENCE = 0.75f
    }

    fun resolve(instruction: String, intent: ParsedIntent?): SkillCandidate? {
        if (intent == null) return null

        val candidates = mutableListOf<SkillCandidate>()

        // 1. RecordedSkillLibrary — 用户录制的 skill，最稳定
        App.getInstance().recordedSkillLibrary?.match(intent)?.let { plan ->
            candidates.add(
                SkillCandidate(
                    provider = "RecordedSkillLibrary",
                    skillId = plan.id,
                    displayName = plan.name,
                    confidence = 0.92f,
                    executable = true,
                    steps = plan.steps,
                    requiresAi = false,
                    hasVerification = false,
                    reason = "命中已录制 skill: ${plan.name}"
                )
            )
        }

        // 2. WorkflowLibrary — 预录制宏
        App.getInstance().workflowLibrary?.match(instruction)?.let { wfMatch ->
            candidates.add(
                SkillCandidate(
                    provider = "WorkflowLibrary",
                    skillId = wfMatch.workflow.id,
                    displayName = wfMatch.workflow.name,
                    confidence = wfMatch.score,
                    executable = wfMatch.score >= MIN_CONFIDENCE,
                    steps = null, // Workflow 用 List<Action>，由 WorkflowLibrary 自己执行
                    requiresAi = false,
                    hasVerification = false,
                    reason = "命中预置 Workflow: ${wfMatch.workflow.name}"
                )
            )
        }

        // 3. SkillWorkflowAssembler（特性门控，默认关闭）
        if (FeatureFlags.ENABLE_SKILL_WORKFLOW_ASSEMBLER_DIRECT_EXEC) {
            SkillWorkflowAssembler(appScanner).assemble(instruction)?.let { planCandidate ->
                candidates.add(
                    SkillCandidate(
                        provider = "SkillWorkflowAssembler",
                        skillId = planCandidate.plan?.id ?: "assembled",
                        displayName = planCandidate.plan?.name ?: "规则拼装",
                        confidence = planCandidate.confidence,
                        executable = planCandidate.isExecutable,
                        steps = planCandidate.plan?.steps,
                        requiresAi = planCandidate.requiresScreenUnderstanding,
                        hasVerification = planCandidate.hasFinalVerification,
                        reason = planCandidate.reason
                    )
                )
            }
        }

        // 4. FragmentLibrary（特性门控，默认关闭）
        if (FeatureFlags.ENABLE_FRAGMENT_LIBRARY) {
            App.getInstance().fragmentLibrary?.tryResolve(instruction)?.let { fragMatch ->
                candidates.add(
                    SkillCandidate(
                        provider = "FragmentLibrary",
                        skillId = fragMatch.template.id,
                        displayName = fragMatch.template.name,
                        confidence = 0.70f,
                        executable = false,
                        steps = null,
                        requiresAi = false,
                        hasVerification = false,
                        reason = "命中碎片模板: ${fragMatch.template.name}，但需运行时验证"
                    )
                )
            }
        }

        // 5. SkillManager — LLM 匹配 Skill
        // SkillManager.matchAvailableAppWithLLM is suspend, handled separately in MobileAgent

        // 过滤：只保留可执行且置信度足够的候选
        val executable = candidates
            .filter { it.executable && it.confidence >= MIN_CONFIDENCE && it.steps != null && it.steps.isNotEmpty() }

        if (executable.isNotEmpty()) {
            val best = executable.maxByOrNull { it.confidence }!!
            println("$TAG 选中: provider=${best.provider} skill=${best.displayName} confidence=${best.confidence}")
            return best
        }

        // 有候选但不可执行
        if (candidates.isNotEmpty()) {
            val best = candidates.maxByOrNull { it.confidence }!!
            println("$TAG 候选存在但不可执行: ${best.reason}")
            return null
        }

        println("$TAG 无本地候选方案")
        return null
    }
}
