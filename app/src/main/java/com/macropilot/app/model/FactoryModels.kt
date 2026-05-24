package com.macropilot.app.model

data class SkillCandidate(
    val id: String,
    val template: MacroTemplate,
    val generatedFromSampleIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val notes: List<String> = emptyList()
)

data class SkillTestReport(
    val templateId: String,
    val totalRuns: Int,
    val success: Int,
    val mediumConfidence: Int,
    val lowConfidence: Int,
    val failed: Int,
    val timeout: Int,
    val avgDurationMs: Long,
    val p95DurationMs: Long,
    val failureClusters: List<FailureCluster> = emptyList()
)

data class FailureCluster(
    val reason: String,
    val count: Int,
    val sampleIds: List<String> = emptyList()
)

data class PromotionDecision(
    val status: PromotionStatus,
    val approved: Boolean,
    val reason: String
)

enum class PromotionStatus {
    APPROVED_A,
    APPROVED_B,
    LOW_CONFIDENCE_ONLY,
    REJECTED
}
