package com.macropilot.app.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutonomousTrainingRecordStore(private val context: Context) {
    private val rootDir: File
        get() = File(context.filesDir, "macro_v2/factory/autonomous_training_records")

    private val maxRecordsPerApp = 120

    fun saveFromFlowReport(flowReport: JSONObject, flowReportFile: File): File {
        val packageName = flowReport.optString("appPackage").ifBlank { "__unknown__" }
        val outputs = flowReport.optJSONObject("outputs") ?: JSONObject()
        val aiJobFiles = outputs.optJSONArray("aiJobFiles") ?: JSONArray()
        val assemblyReports = outputs.optJSONArray("latestAssemblyReports") ?: JSONArray()
        val runtimeRunReportFile = outputs.optString("runtimeRunReportFile")
        val appUiGraphFile = outputs.optString("appUiGraph")
        val previewConfigFile = outputs.optString("previewConfigFile")

        val record = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "autonomous_training_record")
            .put("id", "autonomous_training_${System.currentTimeMillis()}")
            .put("appPackage", packageName)
            .put("flowReportFile", flowReportFile.absolutePath)
            .put("input", JSONObject()
                .put("instruction", flowReport.optString("instruction"))
                .put("appPackage", packageName)
                .put("query", flowReport.optString("query"))
                .put("appUiGraphFile", nullIfBlank(appUiGraphFile))
                .put("recentUiSnapshots", outputs.optJSONArray("recentUiSnapshots") ?: JSONArray()))
            .put("teacherOutput", JSONObject()
                .put("aiJobs", summarizeAiJobs(aiJobFiles))
                .put("planReviewOrReplanCount", aiJobFiles.length())
                .put("source", "deepseek_v4pro_or_phone_side_adapter"))
            .put("programOutput", JSONObject()
                .put("status", flowReport.optString("status"))
                .put("durationMs", flowReport.optLong("durationMs"))
                .put("steps", compactSteps(flowReport.optJSONArray("steps") ?: JSONArray(), 40))
                .put("assemblyReports", summarizeAssemblyReports(assemblyReports))
                .put("previewConfigFile", nullIfBlank(previewConfigFile))
                .put("runtimeRunReport", summarizeRuntimeRunReport(runtimeRunReportFile))
                .put("goalTruth", goalTruth(flowReport)))
            .put("datasetTargets", JSONArray(listOf(
                "screen_classifier_sft",
                "element_role_labeler_sft",
                "task_flow_planner_sft",
                "skill_patch_generator_sft",
                "verifier_recovery_sft"
            )))
            .put("qualityGates", JSONObject()
                .put("hasAppUiGraph", appUiGraphFile.isNotBlank())
                .put("hasAssemblyReport", assemblyReports.length() > 0)
                .put("hasRuntimeTrace", runtimeRunReportFile.isNotBlank() && runtimeRunReportFile != "null")
                .put("hasGoalTruth", hasGoalCheck(flowReport))
                .put("eligibleForCleanTraining", eligibleForCleanTraining(flowReport, assemblyReports, runtimeRunReportFile)))
            .put("storagePolicy", JSONObject()
                .put("rawScreenshotsLongTerm", false)
                .put("fullNodeTreesLongTerm", false)
                .put("keepsCompactUiGraph", true)
                .put("keepsPatchReports", true))
            .put("createdAt", System.currentTimeMillis())

        val appDir = File(rootDir, packageName.safeSegment())
        appDir.mkdirs()
        val file = File(appDir, "${record.optString("id")}_${timestamp()}.json")
        file.writeText(record.toString(2))
        pruneNewestFiles(appDir, maxRecordsPerApp)
        return file
    }

    fun listRecords(packageName: String? = null, limit: Int = 100): List<File> {
        val root = packageName
            ?.takeIf { it.isNotBlank() }
            ?.let { File(rootDir, it.safeSegment()) }
            ?: rootDir
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.absolutePath })
            .take(limit.coerceIn(1, 1000))
            .toList()
    }

    private fun summarizeAiJobs(paths: JSONArray): JSONArray {
        val out = JSONArray()
        for (index in 0 until paths.length()) {
            val path = paths.optString(index)
            val job = readJson(path) ?: continue
            out.put(JSONObject()
                .put("path", path)
                .put("taskType", job.optString("taskType"))
                .put("aiStatus", job.optString("aiStatus"))
                .put("httpStatus", job.opt("httpStatus") ?: JSONObject.NULL)
                .put("model", job.optString("model"))
                .put("baseUrlHost", job.optString("baseUrlHost"))
                .put("parseSuccess", job.optBoolean("parseSuccess"))
                .put("parsedJson", compactJson(job.optJSONObject("parsedJson") ?: JSONObject(), 16)))
        }
        return out
    }

    private fun summarizeAssemblyReports(paths: JSONArray): JSONArray {
        val out = JSONArray()
        for (index in 0 until paths.length()) {
            val path = paths.optString(index)
            val report = readJson(path) ?: continue
            val patches = report.optJSONArray("patches") ?: JSONArray()
            val compactPatches = JSONArray()
            for (patchIndex in 0 until minOf(patches.length(), 20)) {
                val patch = patches.optJSONObject(patchIndex) ?: continue
                compactPatches.put(JSONObject()
                    .put("skillId", patch.optString("skillId"))
                    .put("actionType", patch.optString("actionType"))
                    .put("sourcePath", patch.optString("sourcePath"))
                    .put("sourceOrigin", patch.optString("sourceOrigin"))
                    .put("changedFields", patch.optJSONArray("changedFields") ?: JSONArray()))
            }
            out.put(JSONObject()
                .put("path", path)
                .put("planStepCount", report.optInt("planStepCount"))
                .put("generatedSkillCount", report.optInt("generatedSkillCount"))
                .put("macroSkillId", report.optString("macroSkillId"))
                .put("rule", report.optString("rule"))
                .put("patches", compactPatches))
        }
        return out
    }

    private fun summarizeRuntimeRunReport(path: String): JSONObject {
        val report = readJson(path) ?: return JSONObject()
            .put("path", nullIfBlank(path))
            .put("status", "MISSING")
        return JSONObject()
            .put("path", path)
            .put("kind", report.optString("kind"))
            .put("skillId", report.optString("skillId"))
            .put("status", report.optString("status"))
            .put("message", report.optString("message").take(500))
            .put("durationMs", report.optLong("durationMs"))
            .put("usedCoordinate", report.optBoolean("usedCoordinate"))
            .put("goalCheck", report.optJSONObject("goalCheck") ?: JSONObject())
    }

    private fun compactSteps(steps: JSONArray, limit: Int): JSONArray {
        val out = JSONArray()
        val start = (steps.length() - limit.coerceAtLeast(1)).coerceAtLeast(0)
        for (index in start until steps.length()) {
            val step = steps.optJSONObject(index) ?: continue
            out.put(JSONObject()
                .put("title", step.optString("title"))
                .put("status", step.optString("status"))
                .put("message", step.optString("message").take(360))
                .put("path", nullIfBlank(step.optString("path")))
                .put("extra", compactJson(step.optJSONObject("extra") ?: JSONObject(), 10)))
        }
        return out
    }

    private fun goalTruth(flowReport: JSONObject): JSONObject {
        val steps = flowReport.optJSONArray("steps") ?: JSONArray()
        var latestGoal: JSONObject? = null
        for (index in 0 until steps.length()) {
            val item = steps.optJSONObject(index) ?: continue
            if (item.optString("title").contains("目标完成校验")) latestGoal = item
        }
        return JSONObject()
            .put("status", flowReport.optString("status"))
            .put("hasExplicitGoalCheck", latestGoal != null)
            .put("goalStepStatus", latestGoal?.optString("status") ?: JSONObject.NULL)
            .put("goalStepMessage", latestGoal?.optString("message") ?: JSONObject.NULL)
            .put("goalEvidence", latestGoal?.optJSONObject("extra") ?: JSONObject())
    }

    private fun hasGoalCheck(flowReport: JSONObject): Boolean {
        val steps = flowReport.optJSONArray("steps") ?: return false
        for (index in 0 until steps.length()) {
            val item = steps.optJSONObject(index) ?: continue
            if (item.optString("title").contains("目标完成校验")) return true
        }
        return false
    }

    private fun eligibleForCleanTraining(flowReport: JSONObject, assemblyReports: JSONArray, runtimeRunReportFile: String): Boolean {
        val status = flowReport.optString("status")
        return assemblyReports.length() > 0 &&
            runtimeRunReportFile.isNotBlank() &&
            runtimeRunReportFile != "null" &&
            hasGoalCheck(flowReport) &&
            !status.equals("RUNNING", ignoreCase = true)
    }

    private fun compactJson(json: JSONObject, maxKeys: Int): JSONObject {
        val out = JSONObject()
        val keys = json.keys()
        var count = 0
        while (keys.hasNext() && count < maxKeys.coerceAtLeast(1)) {
            val key = keys.next()
            val value = json.opt(key)
            out.put(key, when (value) {
                is String -> value.take(500)
                is JSONObject -> compactJson(value, 8)
                is JSONArray -> compactArray(value, 8)
                else -> value ?: JSONObject.NULL
            })
            count++
        }
        return out
    }

    private fun compactArray(array: JSONArray, maxItems: Int): JSONArray {
        val out = JSONArray()
        for (index in 0 until minOf(array.length(), maxItems.coerceAtLeast(1))) {
            val value = array.opt(index)
            out.put(when (value) {
                is String -> value.take(300)
                is JSONObject -> compactJson(value, 8)
                is JSONArray -> compactArray(value, 6)
                else -> value ?: JSONObject.NULL
            })
        }
        return out
    }

    private fun readJson(path: String): JSONObject? {
        if (path.isBlank() || path == "null") return null
        val file = File(path)
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun nullIfBlank(value: String): Any {
        return value.takeIf { it.isNotBlank() && it != "null" } ?: JSONObject.NULL
    }

    private fun pruneNewestFiles(dir: File, keep: Int) {
        if (!dir.isDirectory) return
        val files = dir.walkTopDown()
            .filter { it.isFile }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.absolutePath })
            .toList()
        if (files.size <= keep) return
        files.drop(keep).forEach { file -> runCatching { file.delete() } }
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    }

    private fun String.safeSegment(): String {
        return lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "item" }
    }
}
