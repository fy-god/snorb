package com.macropilot.app.factory

import com.macropilot.app.model.AppUiModule
import com.macropilot.app.model.FailureCluster
import com.macropilot.app.model.MacroStatus
import com.macropilot.app.model.MacroTemplate
import com.macropilot.app.model.SkillTestReport
import com.macropilot.app.runtime.MacroExecutor

class SkillTestRunner(
    private val executor: MacroExecutor
) {
    fun runRepeated(
        template: MacroTemplate,
        appUi: AppUiModule,
        paramName: String,
        values: List<String>,
        beforeEach: (() -> Unit)? = null
    ): SkillTestReport {
        val runs = values.map { value ->
            beforeEach?.invoke()
            val startedAt = System.currentTimeMillis()
            val result = executor.execute(template, appUi, mapOf(paramName to value))
            SkillRunResult(
                status = result.status,
                message = result.message,
                durationMs = System.currentTimeMillis() - startedAt
            )
        }

        val durations = runs.map { it.durationMs }.sorted()
        val p95 = if (durations.isEmpty()) 0L else durations[((durations.size - 1) * 0.95f).toInt()]
        val failures = runs.filter { it.status.name.startsWith("FAILED") }
            .groupBy { "${it.status}: ${it.message}" }
            .map { (reason, items) -> FailureCluster(reason = reason, count = items.size) }

        return SkillTestReport(
            templateId = template.id,
            totalRuns = runs.size,
            success = runs.count { it.status == MacroStatus.SUCCESS },
            mediumConfidence = runs.count { it.status == MacroStatus.SUCCESS_MEDIUM_CONFIDENCE },
            lowConfidence = runs.count { it.status == MacroStatus.SUCCESS_LOW_CONFIDENCE },
            failed = failures.sumOf { it.count },
            timeout = runs.count { it.status == MacroStatus.FAILED_TIMEOUT },
            avgDurationMs = durations.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: 0L,
            p95DurationMs = p95,
            failureClusters = failures
        )
    }
}

private data class SkillRunResult(
    val status: MacroStatus,
    val message: String,
    val durationMs: Long
)
