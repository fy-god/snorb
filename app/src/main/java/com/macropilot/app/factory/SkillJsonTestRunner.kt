package com.macropilot.app.factory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SkillJsonTestRunner(
    private val context: Context,
    private val executor: SkillJsonExecutor = SkillJsonExecutor(context)
) {
    fun runRepeated(
        skill: JSONObject,
        params: JSONObject,
        runs: Int,
        allowSideEffect: Boolean
    ): JSONObject {
        val count = runs.coerceIn(1, 50)
        val results = (1..count).map { index ->
            val startedAt = System.currentTimeMillis()
            val result = executor.execute(skill, params, allowSideEffect)
            JSONObject()
                .put("run", index)
                .put("status", result.status)
                .put("message", result.message)
                .put("durationMs", result.durationMs.takeIf { it > 0 } ?: (System.currentTimeMillis() - startedAt))
                .put("usedCoordinate", result.usedCoordinate)
        }
        val durations = results.map { it.optLong("durationMs") }.sorted()
        val failureClusters = results
            .filter { it.optString("status").startsWith("FAILED") }
            .groupBy { failureClusterType(it.optString("status"), it.optString("message")) }
            .map { (type, items) ->
                JSONObject()
                    .put("type", type)
                    .put("count", items.size)
                    .put("sampleMessages", JSONArray(items.take(3).map { it.optString("message") }))
            }
        val success = results.count { it.optString("status") == "SUCCESS" }
        val medium = results.count { it.optString("status") == "SUCCESS_MEDIUM_CONFIDENCE" }
        val low = results.count { it.optString("status") == "SUCCESS_LOW_CONFIDENCE" }
        val failed = results.count { it.optString("status").startsWith("FAILED") }
        val timeout = results.count { it.optString("status") == "FAILED_TIMEOUT" }
        val total = results.size
        val successLike = success + medium + low
        val p95 = if (durations.isEmpty()) 0L else durations[((durations.size - 1) * 0.95f).toInt()]
        return JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_test_report")
            .put("skillId", skill.optString("id"))
            .put("appPackage", skill.optString("appPackage"))
            .put("skillKind", skill.optString("kind"))
            .put("testRuns", total)
            .put("success", success)
            .put("mediumConfidence", medium)
            .put("lowConfidence", low)
            .put("failed", failed)
            .put("timeout", timeout)
            .put("successRate", if (total == 0) 0.0 else successLike.toDouble() / total.toDouble())
            .put("highConfidenceRate", if (total == 0) 0.0 else success.toDouble() / total.toDouble())
            .put("mediumOrHighConfidenceRate", if (total == 0) 0.0 else (success + medium).toDouble() / total.toDouble())
            .put("avgDurationMs", durations.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: 0L)
            .put("p95DurationMs", p95)
            .put("failureClusters", JSONArray(failureClusters))
            .put("runResults", JSONArray(results))
            .put("allowSideEffect", allowSideEffect)
            .put("promotionSuggestion", suggestion(total, successLike, success, medium, low, failed, p95))
            .put("timestamp", System.currentTimeMillis())
    }

    private fun suggestion(
        total: Int,
        successLike: Int,
        high: Int,
        medium: Int,
        low: Int,
        failed: Int,
        p95: Long
    ): String {
        if (total == 0) return "REJECT"
        val successRate = successLike.toDouble() / total.toDouble()
        val highRate = high.toDouble() / total.toDouble()
        val mediumOrHighRate = (high + medium).toDouble() / total.toDouble()
        return when {
            failed > 0 && successRate < 0.80 -> "REJECT"
            successRate >= 0.95 && highRate >= 0.90 && p95 <= 30_000L -> "APPROVE_AS_A_LEVEL"
            successRate >= 0.85 && mediumOrHighRate >= 0.80 && p95 <= 30_000L -> "APPROVE_AS_B_LEVEL"
            successRate >= 0.80 && low > 0 && p95 <= 30_000L -> "APPROVE_AS_C_LEVEL_LOW_CONFIDENCE"
            else -> "KEEP_TESTING"
        }
    }

    private fun failureClusterType(status: String, message: String): String {
        return when {
            status.contains("INPUT_CHANNEL") || message.contains("input", ignoreCase = true) -> "INPUT_CHANNEL_MISSING"
            status.contains("SIDE_EFFECT") -> "SIDE_EFFECT_CONFIRMATION_REQUIRED"
            status.contains("PRECONDITION") -> "PRECONDITION_FAILED"
            status.contains("ELEMENT") -> "ELEMENT_NOT_FOUND"
            status.contains("TIMEOUT") -> "TIMEOUT"
            status.contains("UNVERIFIABLE") -> "UNVERIFIABLE"
            else -> status.ifBlank { "FAILED" }
        }
    }
}
