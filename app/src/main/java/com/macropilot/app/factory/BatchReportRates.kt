package com.macropilot.app.factory

import org.json.JSONObject
import java.util.Locale
import kotlin.math.round

internal object BatchReportRates {
    fun addPopularTaskRates(summary: JSONObject): JSONObject {
        val taskCount = summary.optInt("taskCount", 0)
        val success = summary.optInt("success", 0)
        val failed = summary.optInt("failed", 0)
        val blocked = summary.optInt("blocked", 0)
        val deferred = summary.optInt("deferred", 0)
        val skipped = summary.optInt("skipped", 0)
        val executedAttempts = summary.optInt("executedAttempts", summary.optInt("executed", 0))
        val decidedTasks = success + failed + blocked + deferred + skipped
        val executableTasks = success + failed + deferred

        summary
            .put("decidedTasks", decidedTasks)
            .put("attemptedLogicalTasks", executableTasks)
            .putRate("pass", success, taskCount)
            .putRate("success", success, taskCount)
            .putRate("failure", failed, taskCount)
            .putRate("blocked", blocked, taskCount)
            .putRate("deferred", deferred, taskCount)
            .putRate("skipped", skipped, taskCount)
            .putRate("completion", decidedTasks, taskCount)
            .putRate("executablePass", success, executableTasks)
            .putRate("executedAttemptPass", success, executedAttempts)
            .putRate("retry", summary.optInt("retried", 0), executedAttempts)
        return summary
    }

    fun addThirdPartyRates(summary: JSONObject): JSONObject {
        val totalApps = summary.optInt("totalApps", 0)
        val launchableApps = summary.optInt("launchableApps", totalApps)
        val dryRunTotal = summary.optInt("dryRunPassed", 0) + summary.optInt("dryRunFailed", 0)
        val uiProbeAttempted = summary.optInt("uiProbeAttempted", 0)
        val uiProbeSucceeded = summary.optInt("uiProbeSucceeded", 0)
        val uiProbeBlocked = summary.optInt("uiProbeBlocked", 0)
        val uiProbeSkipped = summary.optInt("uiProbeSkipped", 0)
        val uiProbeFinished = uiProbeSucceeded + uiProbeBlocked + uiProbeSkipped

        summary
            .putRate("dryRunPass", summary.optInt("dryRunPassed", 0), dryRunTotal)
            .putRate("uiProbeSuccess", uiProbeSucceeded, uiProbeAttempted)
            .putRate("uiProbeBlocked", uiProbeBlocked, uiProbeAttempted)
            .putRate("uiProbeCoverage", uiProbeAttempted, launchableApps)
            .putRate("uiProbeCompletion", uiProbeFinished, launchableApps)
            .putRate("appConfigExport", summary.optInt("exportedConfigCount", 0), totalApps)
        return summary
    }

    fun JSONObject.putRate(prefix: String, numerator: Int, denominator: Int): JSONObject {
        val ratio = if (denominator <= 0) 0.0 else numerator.toDouble() / denominator.toDouble()
        val pct = roundTo1(ratio * 100.0)
        put("${prefix}Rate", roundTo4(ratio))
        put("${prefix}RatePct", pct)
        put("${prefix}RateText", String.format(Locale.US, "%.1f%%", pct))
        put("${prefix}RateNumerator", numerator)
        put("${prefix}RateDenominator", denominator)
        return this
    }

    private fun roundTo1(value: Double): Double = round(value * 10.0) / 10.0

    private fun roundTo4(value: Double): Double = round(value * 10_000.0) / 10_000.0
}
