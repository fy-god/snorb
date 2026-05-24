package com.macropilot.app.factory

import com.macropilot.app.model.AppUiModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SkillJsonExporter {
    fun exportAppConfig(
        packageName: String,
        appName: String,
        module: AppUiModule?,
        skills: List<JSONObject>,
        dryRunReports: List<JSONObject>
    ): JSONObject {
        val atomic = skills.filter { it.optString("kind") == "atomic_skill" }
        val macro = skills.filter { it.optString("kind") == "macro_skill" }
        val reactive = skills.filter { it.optString("kind") == "reactive_rule" }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "app_config")
            .put("appPackage", packageName)
            .put("appName", appName)
            .put("screens", JSONArray(module?.screens?.map { screen ->
                JSONObject()
                    .put("id", screen.id)
                    .put("name", screen.name)
                    .put("signatures", JSONArray(screen.signatures.map { sig ->
                        JSONObject()
                            .put("packageName", sig.packageName)
                            .put("activityName", sig.activityName)
                            .put("requiredTextsAny", JSONArray(sig.requiredTextsAny))
                            .put("requiredTextsAll", JSONArray(sig.requiredTextsAll))
                            .put("requiredResourceIdsAny", JSONArray(sig.requiredResourceIdsAny))
                            .put("requiredRolesAny", JSONArray(sig.requiredRolesAny.map { it.name }))
                            .put("negativeTexts", JSONArray(sig.negativeTexts))
                            .put("minScore", sig.minScore)
                    }))
            }.orEmpty()))
            .put("elements", JSONArray(module?.elements?.map { element ->
                JSONObject()
                    .put("id", element.id)
                    .put("name", element.name)
                    .put("role", element.role.name)
                    .put("reliability", element.reliability.name)
                    .put("samples", element.samples)
                    .put("successRate", element.successRate)
            }.orEmpty()))
            .put("atomicSkills", JSONArray(atomic))
            .put("macroSkills", JSONArray(macro))
            .put("reactiveRules", JSONArray(reactive))
            .put("runtimePolicy", JSONObject()
                .put("allowCandidateSkill", false)
                .put("allowTrial", false)
                .put("aiCallsDefault", 0)
                .put("screenshotDefault", 0)
                .put("maxTaskDurationMs", 30_000))
            .put("factoryReports", JSONObject()
                .put("dryRunReports", JSONArray(dryRunReports.map { reportSummary(it) }))
                .put("exportedAt", System.currentTimeMillis()))
    }

    private fun reportSummary(report: JSONObject): JSONObject {
        return JSONObject()
            .put("kind", report.optString("kind"))
            .put("skillId", report.optString("skillId").ifBlank { report.optString("templateId") })
            .put("appPackage", report.optString("appPackage"))
            .put("passed", report.optBoolean("passed", false))
            .put("status", report.optString("status"))
            .put("errors", report.optJSONArray("errors") ?: JSONArray())
            .put("warnings", report.optJSONArray("warnings") ?: JSONArray())
            .put("timestamp", report.optLong("timestamp"))
    }

    fun buildAssemblyReport(
        packageName: String,
        appName: String,
        approvedOnly: Boolean,
        sourceFiles: List<File>,
        includedSkills: List<JSONObject>,
        dryRunReportCount: Int,
        testReportCount: Int,
        config: JSONObject,
        previewConfigFile: File?,
        runtimeConfigFile: File?
    ): JSONObject {
        val sourceSummaries = sourceFiles.map { sourceFileSummary(it, approvedOnly) }
        val included = sourceSummaries.filter { it.optBoolean("included") }
        val excluded = sourceSummaries.filterNot { it.optBoolean("included") }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_assembly_report")
            .put("id", "assembly_report_${System.currentTimeMillis()}")
            .put("appPackage", packageName)
            .put("appName", appName)
            .put("mode", if (approvedOnly) "RUNTIME_APPROVED" else "FACTORY_PREVIEW")
            .put("createdAt", System.currentTimeMillis())
            .put("steps", JSONArray(listOf(
                "读取应用下的细粒度 skill JSON 文件",
                "按状态过滤：预览排除 REJECTED，运行配置只允许 APPROVED",
                "按 kind 拆分 atomicSkills / macroSkills / reactiveRules",
                "合并 screens / elements / reports / runtimePolicy",
                "写出 app_config JSON，并记录本次装配报告"
            )))
            .put("source", JSONObject()
                .put("skillFileCount", sourceFiles.size)
                .put("includedSkillJsonCount", includedSkills.size)
                .put("includedSkillCount", included.size)
                .put("excludedSkillCount", excluded.size)
                .put("dryRunReportCount", dryRunReportCount)
                .put("testReportCount", testReportCount))
            .put("counts", JSONObject()
                .put("atomicSkills", config.optJSONArray("atomicSkills")?.length() ?: 0)
                .put("macroSkills", config.optJSONArray("macroSkills")?.length() ?: 0)
                .put("reactiveRules", config.optJSONArray("reactiveRules")?.length() ?: 0)
                .put("screens", config.optJSONArray("screens")?.length() ?: 0)
                .put("elements", config.optJSONArray("elements")?.length() ?: 0))
            .put("outputs", JSONObject()
                .put("previewConfigFile", previewConfigFile?.absolutePath)
                .put("runtimeConfigFile", runtimeConfigFile?.absolutePath)
                .put("runtimeConfigIsExecutable", approvedOnly && runtimeConfigFile != null))
            .put("runtimePolicy", config.optJSONObject("runtimePolicy"))
            .put("includedSkills", JSONArray(included))
            .put("excludedSkills", JSONArray(excluded))
            .put("sourceFiles", JSONArray(sourceSummaries))
    }

    private fun sourceFileSummary(file: File, approvedOnly: Boolean): JSONObject {
        val body = runCatching { JSONObject(file.readText()) }.getOrNull()
        val status = body?.optString("status").orEmpty()
        val included = body != null && if (approvedOnly) status == "APPROVED" else status != "REJECTED"
        val reason = when {
            body == null -> "JSON_PARSE_FAILED"
            approvedOnly && status != "APPROVED" -> "RUNTIME_ONLY_ACCEPTS_APPROVED"
            status == "REJECTED" -> "REJECTED_SKILL_EXCLUDED"
            else -> "INCLUDED"
        }
        return JSONObject()
            .put("id", body?.optString("id")?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension)
            .put("name", body?.optString("name"))
            .put("kind", body?.optString("kind"))
            .put("implements", body?.optString("implements"))
            .put("status", status.ifBlank { "UNKNOWN" })
            .put("included", included)
            .put("reason", reason)
            .put("file", file.absolutePath)
            .put("updatedAt", file.lastModified())
    }
}
