package com.macropilot.app.factory

import com.macropilot.app.model.PromotionDecision
import com.macropilot.app.model.PromotionStatus
import com.macropilot.app.model.SkillCandidate
import com.macropilot.app.model.SkillTestReport
import com.macropilot.app.safety.SafeActionPolicy

class PromotionGate(
    private val safetyPolicy: SafeActionPolicy = SafeActionPolicy()
) {
    fun evaluate(candidate: SkillCandidate, report: SkillTestReport): PromotionDecision {
        val safety = safetyPolicy.evaluateTemplate(candidate.template)
        if (!safety.allowed) {
            return PromotionDecision(PromotionStatus.REJECTED, approved = false, reason = safety.reason)
        }

        if (report.totalRuns < 10) {
            return PromotionDecision(
                PromotionStatus.REJECTED,
                approved = false,
                reason = "At least 10 test runs are required before promotion"
            )
        }

        val successLike = report.success + report.mediumConfidence + report.lowConfidence
        val successRate = successLike.toFloat() / report.totalRuns.toFloat()
        val strongRate = report.success.toFloat() / report.totalRuns.toFloat()
        val mediumOrBetterRate = (report.success + report.mediumConfidence).toFloat() / report.totalRuns.toFloat()

        return when {
            successRate >= 0.95f && strongRate >= 0.90f && report.p95DurationMs <= 30_000L -> {
                PromotionDecision(PromotionStatus.APPROVED_A, approved = true, reason = "A-level promotion passed")
            }
            successRate >= 0.85f && mediumOrBetterRate >= 0.80f && report.p95DurationMs <= 30_000L -> {
                PromotionDecision(PromotionStatus.APPROVED_B, approved = true, reason = "B-level promotion passed")
            }
            successRate >= 0.70f && report.p95DurationMs <= 30_000L -> {
                PromotionDecision(
                    PromotionStatus.LOW_CONFIDENCE_ONLY,
                    approved = false,
                    reason = "Can only remain a low-confidence coordinate/candidate skill"
                )
            }
            else -> {
                PromotionDecision(PromotionStatus.REJECTED, approved = false, reason = "Promotion thresholds not met")
            }
        }
    }
}
