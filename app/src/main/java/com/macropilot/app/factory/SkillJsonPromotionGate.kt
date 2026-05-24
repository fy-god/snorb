package com.macropilot.app.factory

import com.macropilot.app.safety.SafeActionPolicy
import org.json.JSONArray
import org.json.JSONObject

class SkillJsonPromotionGate(
    private val safetyPolicy: SafeActionPolicy = SafeActionPolicy()
) {
    fun evaluate(
        skill: JSONObject,
        dryRunReport: JSONObject?,
        testReport: JSONObject
    ): JSONObject {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val kind = skill.optString("kind")
        val skillId = skill.optString("id")
        val minRuns = if (kind == "macro_skill") 20 else 10

        if (skill.optString("status") == "APPROVED") {
            errors += "candidate_already_claims_APPROVED"
        }
        if (dryRunReport?.optBoolean("passed", false) != true && skill.optJSONObject("factory")?.optString("dryRunStatus") != "PASSED") {
            errors += "dry_run_not_passed"
        }
        if (skill.optJSONObject("verify") == null) errors += "missing_verify"
        if (skill.optJSONObject("resultPolicy") == null) errors += "missing_resultPolicy"
        if (inputRequired(skill) && allowedInputChannels(skill).isEmpty()) {
            errors += "input_skill_missing_allowed_channels"
        }
        val safetySubject = JSONObject(skill.toString()).apply { remove("safety") }
        val safety = safetyPolicy.evaluateText(safetySubject.toString())
        if (!safety.allowed) errors += "dangerous_action:${safety.matchedKeyword}"
        if (hasInputChannelFailure(testReport)) errors += "input_channel_missing_in_test"

        val runs = testReport.optInt("testRuns", testReport.optInt("totalRuns", 0))
        val success = testReport.optInt("success", 0)
        val medium = testReport.optInt("mediumConfidence", 0)
        val low = testReport.optInt("lowConfidence", 0)
        val successLike = success + medium + low
        val successRate = if (runs == 0) 0.0 else successLike.toDouble() / runs.toDouble()
        val highRate = if (runs == 0) 0.0 else success.toDouble() / runs.toDouble()
        val mediumOrHighRate = if (runs == 0) 0.0 else (success + medium).toDouble() / runs.toDouble()
        val p95 = testReport.optLong("p95DurationMs", Long.MAX_VALUE)

        if (runs < minRuns) {
            errors += "not_enough_test_runs:$runs/$minRuns"
        }
        if (p95 > 30_000L) warnings += "p95_duration_over_30s:$p95"

        val level = when {
            errors.isNotEmpty() -> "REJECTED"
            successRate >= 0.95 && highRate >= 0.90 && p95 <= 30_000L -> "APPROVED_A"
            successRate >= 0.85 && mediumOrHighRate >= 0.80 && p95 <= 30_000L -> "APPROVED_B"
            successRate >= 0.80 && usesCoordinateFallback(skill) && p95 <= 30_000L -> "APPROVED_C"
            else -> "REJECTED"
        }
        val approved = level.startsWith("APPROVED")
        val reason = when (level) {
            "APPROVED_A" -> "A-level promotion passed"
            "APPROVED_B" -> "B-level promotion passed"
            "APPROVED_C" -> "C-level low-confidence coordinate promotion passed"
            else -> (errors.ifEmpty { listOf("promotion_thresholds_not_met") }).joinToString("; ")
        }

        return JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_promotion_decision")
            .put("skillId", skillId)
            .put("appPackage", skill.optString("appPackage"))
            .put("approved", approved)
            .put("status", level)
            .put("reason", reason)
            .put("metrics", JSONObject()
                .put("runs", runs)
                .put("successRate", successRate)
                .put("highConfidenceRate", highRate)
                .put("mediumOrHighConfidenceRate", mediumOrHighRate)
                .put("p95DurationMs", p95)
                .put("minRuns", minRuns))
            .put("errors", JSONArray(errors))
            .put("warnings", JSONArray(warnings))
            .put("timestamp", System.currentTimeMillis())
    }

    private fun inputRequired(skill: JSONObject): Boolean {
        return skill.optJSONObject("capabilityRequired")?.optBoolean("inputRequired", false) == true
    }

    private fun allowedInputChannels(skill: JSONObject): List<String> {
        val raw = skill.optJSONObject("capabilityRequired")?.optJSONArray("allowedInputChannels") ?: return emptyList()
        return (0 until raw.length()).mapNotNull { index -> raw.optString(index).takeIf { it.isNotBlank() } }
    }

    private fun usesCoordinateFallback(skill: JSONObject): Boolean {
        if (skill.optJSONObject("target")?.optJSONObject("coordinatePack") != null) return true
        val text = skill.toString()
        return text.contains("COORDINATE_PACK") || text.contains("coordinate", ignoreCase = true)
    }

    private fun hasInputChannelFailure(report: JSONObject): Boolean {
        val clusters = report.optJSONArray("failureClusters") ?: return false
        for (i in 0 until clusters.length()) {
            val cluster = clusters.optJSONObject(i) ?: continue
            if (cluster.optString("type").contains("INPUT_CHANNEL", ignoreCase = true) && cluster.optInt("count", 0) > 0) {
                return true
            }
        }
        return false
    }
}
