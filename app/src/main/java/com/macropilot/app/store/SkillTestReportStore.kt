package com.macropilot.app.store

import android.content.Context
import com.macropilot.app.model.FailureCluster
import com.macropilot.app.model.PromotionDecision
import com.macropilot.app.model.SkillTestReport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SkillTestReportStore(private val context: Context) {
    private val candidateStore = CandidateKnowledgeStore(context)
    private val maxReports = 200

    fun save(report: SkillTestReport, promotionDecision: PromotionDecision? = null): File {
        candidateStore.ensureCreated()
        val file = File(candidateStore.testReportDir, "${report.templateId}_${System.currentTimeMillis()}.json")
        file.writeText(toJson(report, promotionDecision).toString(2))
        pruneOldFiles()
        return file
    }

    private fun toJson(report: SkillTestReport, promotionDecision: PromotionDecision?): JSONObject {
        return JSONObject()
            .put("templateId", report.templateId)
            .put("totalRuns", report.totalRuns)
            .put("success", report.success)
            .put("mediumConfidence", report.mediumConfidence)
            .put("lowConfidence", report.lowConfidence)
            .put("failed", report.failed)
            .put("timeout", report.timeout)
            .put("avgDurationMs", report.avgDurationMs)
            .put("p95DurationMs", report.p95DurationMs)
            .put("failureClusters", JSONArray(report.failureClusters.map { toJson(it) }))
            .put("promotionDecision", promotionDecision?.let { toJson(it) })
            .put("timestamp", System.currentTimeMillis())
    }

    private fun toJson(cluster: FailureCluster): JSONObject {
        return JSONObject()
            .put("reason", cluster.reason)
            .put("count", cluster.count)
            .put("sampleIds", JSONArray(cluster.sampleIds))
    }

    private fun toJson(decision: PromotionDecision): JSONObject {
        return JSONObject()
            .put("status", decision.status.name)
            .put("approved", decision.approved)
            .put("reason", decision.reason)
    }

    private fun pruneOldFiles() {
        val root = candidateStore.testReportDir
        if (!root.isDirectory) return
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedByDescending { it.lastModified() }
            .toList()
        if (files.size <= maxReports) return
        files.drop(maxReports).forEach { file ->
            runCatching { file.delete() }
        }
    }
}
