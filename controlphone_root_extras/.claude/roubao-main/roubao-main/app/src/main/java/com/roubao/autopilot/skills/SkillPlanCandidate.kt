package com.roubao.autopilot.skills

/**
 * Skill 候选方案 — 由 SkillWorkflowAssembler 等模块生成，
 * 不直接执行，而是交给 Router 评分后决定走本地还是 AI。
 */
data class SkillPlanCandidate(
    val source: String,
    val plan: SkillWorkflowPlan?,
    val confidence: Float,
    val requiresScreenUnderstanding: Boolean,
    val hasFinalVerification: Boolean,
    val reason: String
) {
    /** 本地可执行？需同时满足高置信度 + 不需要屏幕理解 + 有最终验证 */
    val isExecutable: Boolean
        get() = confidence >= 0.75f && !requiresScreenUnderstanding && hasFinalVerification
            && plan != null && plan.steps.isNotEmpty()

    companion object {
        /** 所有条件完美满足的快捷构造 */
        fun perfect(source: String, plan: SkillWorkflowPlan, reason: String = "规则拼装") = SkillPlanCandidate(
            source = source, plan = plan,
            confidence = 0.95f,
            requiresScreenUnderstanding = false,
            hasFinalVerification = true,
            reason = reason
        )

        /** 低置信度 / 需要屏幕判断的方案 — 不能本地执行 */
        fun weak(source: String, plan: SkillWorkflowPlan?, confidence: Float, reason: String) = SkillPlanCandidate(
            source = source, plan = plan,
            confidence = confidence,
            requiresScreenUnderstanding = true,
            hasFinalVerification = false,
            reason = reason
        )
    }
}
