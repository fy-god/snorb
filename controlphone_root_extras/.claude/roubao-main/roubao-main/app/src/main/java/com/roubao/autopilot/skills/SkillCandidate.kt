package com.roubao.autopilot.skills

/**
 * 统一 Skill 候选结果 — 由 SkillResolver 汇总所有本地能力来源后产出。
 * 只有 executable=true 且 confidence >= 0.75 的候选才能走本地执行。
 */
data class SkillCandidate(
    val provider: String,
    val skillId: String,
    val displayName: String,
    val confidence: Float,
    val executable: Boolean,
    val steps: List<SkillStep>?,
    val requiresAi: Boolean,
    val hasVerification: Boolean,
    val reason: String
)
