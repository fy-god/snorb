package com.macropilot.app.store

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

class LocalDataGovernanceStore(private val context: Context) {
    private val trainingDir: File
        get() = File(context.filesDir, "macro_training/raw_samples")

    private val approvedDir: File
        get() = File(context.filesDir, "macro_v2/approved")

    private val factoryDir: File
        get() = File(context.filesDir, "macro_v2/factory")

    private val autonomousTrainingDir: File
        get() = File(factoryDir, "autonomous_training_records")

    private val runLogDir: File
        get() = File(context.filesDir, "macro_v2/run_logs")

    private val exportDir: File
        get() = File(context.filesDir, "macro_exports")

    private val coordinateCalibrationDir: File
        get() = File(context.filesDir, "macro_v2/calibration/coordinates")

    fun inventory(): LocalDataInventory {
        val factoryBreakdown = factoryBreakdown()
        return LocalDataInventory(
            trainingSamples = countJsonFiles(trainingDir),
            approvedFiles = countJsonFiles(approvedDir),
            factoryFiles = countAllFiles(factoryDir),
            factoryBytes = sizeOf(factoryDir),
            uiSnapshotFiles = factoryBreakdown.optJSONObject("ui_snapshots")?.optInt("files") ?: 0,
            uiScreenshotFiles = factoryBreakdown.optJSONObject("ui_screenshots")?.optInt("files") ?: 0,
            appUiGraphFiles = factoryBreakdown.optJSONObject("app_ui_graphs")?.optInt("files") ?: 0,
            assemblyReportFiles = factoryBreakdown.optJSONObject("app_config_exports")?.optInt("files") ?: 0,
            autonomousTrainingRecords = factoryBreakdown.optJSONObject("autonomous_training_records")?.optInt("files") ?: 0,
            runLogs = countJsonFiles(runLogDir),
            coordinateCandidates = countJsonFiles(coordinateCalibrationDir),
            exportFiles = countJsonlFiles(exportDir),
            factoryBreakdown = factoryBreakdown,
            totalBytes = sizeOf(trainingDir) +
                sizeOf(approvedDir) +
                sizeOf(factoryDir) +
                sizeOf(runLogDir) +
                sizeOf(coordinateCalibrationDir) +
                sizeOf(exportDir)
        )
    }

    fun deleteTrainingSamples(): Int {
        return deleteJsonFiles(trainingDir)
    }

    fun deleteFactoryArtifacts(): Int {
        return deleteAllFiles(factoryDir)
    }

    fun compactFactoryArtifacts(): DataCompactionReport {
        val before = inventory()
        var deleted = 0
        deleted += prunePerAppDir(File(factoryDir, "skill_json_candidates"), keepFilesPerApp = 120)
        deleted += prunePerAppDir(File(factoryDir, "skill_json_promoted"), keepFilesPerApp = 80)
        deleted += pruneDir(File(factoryDir, "ui_snapshots"), keepFiles = 420)
        deleted += pruneDir(File(factoryDir, "ui_screenshots"), keepFiles = 160, extension = "png")
        deleted += prunePerAppDir(File(factoryDir, "skill_json_dry_run_reports"), keepFilesPerApp = 30)
        deleted += prunePerAppDir(File(factoryDir, "skill_json_test_reports"), keepFilesPerApp = 30)
        deleted += prunePerAppDir(File(factoryDir, "app_config_exports"), keepFilesPerApp = 12)
        deleted += prunePerAppDir(File(factoryDir, "flow_reports"), keepFilesPerApp = 30)
        deleted += prunePerAppDir(autonomousTrainingDir, keepFilesPerApp = 80)
        deleted += compactOldUiSnapshots(File(factoryDir, "ui_snapshots"), keepFullNewest = 120)
        deleted += compactOldTrainingSamples(trainingDir, keepFullNewest = 80)
        deleted += pruneAiJobs(File(factoryDir, "ai_jobs"), keepDays = 5, keepFilesPerDay = 50)
        val after = inventory()
        return DataCompactionReport(
            deletedFiles = deleted,
            beforeBytes = before.totalBytes,
            afterBytes = after.totalBytes,
            beforeFactoryBytes = before.factoryBytes,
            afterFactoryBytes = after.factoryBytes,
            afterInventory = after
        )
    }

    fun deleteRunLogs(): Int {
        return deleteJsonFiles(runLogDir)
    }

    fun deleteCoordinateCandidates(): Int {
        return deleteJsonFiles(coordinateCalibrationDir)
    }

    fun deleteExports(): Int {
        if (!exportDir.exists()) return 0
        var count = 0
        exportDir.walkBottomUp().forEach { file ->
            if (file.isFile && file.extension == "jsonl" && file.delete()) count++
        }
        return count
    }

    fun trainingExportPreview(): TrainingExportPreview {
        val files = jsonFiles(trainingDir)
        var redacted = 0
        var personal = 0
        files.forEach { file ->
            val json = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (json?.optString("redactionStatus").orEmpty().contains("redacted", ignoreCase = true)) {
                redacted++
            }
            if (json?.optBoolean("containsPersonalData", false) == true) {
                personal++
            }
        }
        return TrainingExportPreview(
            sampleCount = files.size,
            totalBytes = files.sumOf { it.length() },
            redactedSamples = redacted,
            personalDataSamples = personal,
            exportAllowed = files.isNotEmpty() && redacted == files.size
        )
    }

    fun exportTrainingSamples(): File? {
        val preview = trainingExportPreview()
        if (!preview.exportAllowed) return null
        exportDir.mkdirs()
        val file = File(exportDir, "training_samples_${System.currentTimeMillis()}.jsonl")
        file.bufferedWriter().use { writer ->
            jsonFiles(trainingDir).forEach { sample ->
                writer.appendLine(sample.readText().replace('\n', ' ').replace('\r', ' '))
            }
        }
        return file
    }

    private fun countJsonFiles(dir: File): Int {
        return jsonFiles(dir).size
    }

    private fun countAllFiles(dir: File): Int {
        if (!dir.exists()) return 0
        return dir.walkTopDown().count { it.isFile }
    }

    private fun countJsonlFiles(dir: File): Int {
        if (!dir.exists()) return 0
        return dir.walkTopDown().count { it.isFile && it.extension == "jsonl" }
    }

    private fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun deleteJsonFiles(dir: File): Int {
        if (!dir.exists()) return 0
        var count = 0
        dir.walkBottomUp().forEach { file ->
            if (file.isFile && file.extension == "json" && file.delete()) count++
        }
        return count
    }

    private fun deleteAllFiles(dir: File): Int {
        if (!dir.exists()) return 0
        var count = 0
        dir.walkBottomUp().forEach { file ->
            if (file.isFile && file.delete()) count++
            if (file.isDirectory && file != dir) {
                runCatching { file.delete() }
            }
        }
        return count
    }

    private fun jsonFiles(dir: File): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
    }

    private fun prunePerAppDir(root: File, keepFilesPerApp: Int): Int {
        if (!root.isDirectory) return 0
        var deleted = 0
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { appDir -> deleted += pruneDir(appDir, keepFilesPerApp) }
        return deleted
    }

    private fun pruneAiJobs(root: File, keepDays: Int, keepFilesPerDay: Int): Int {
        if (!root.isDirectory) return 0
        val dayDirs = root.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedWith(compareByDescending<File> { it.name }.thenBy { it.absolutePath })
            .orEmpty()
        var deleted = 0
        dayDirs.drop(keepDays).forEach { dir ->
            deleted += deleteAllFiles(dir)
            runCatching { dir.delete() }
        }
        dayDirs.take(keepDays).forEach { dir -> deleted += pruneDir(dir, keepFilesPerDay) }
        return deleted
    }

    private fun pruneDir(dir: File, keepFiles: Int, extension: String? = null): Int {
        if (!dir.isDirectory) return 0
        val files = dir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    (extension == null || file.extension.lowercase(Locale.US) == extension.lowercase(Locale.US))
            }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.absolutePath })
            .toList()
        if (files.size <= keepFiles) return 0
        var deleted = 0
        files.drop(keepFiles).forEach { file ->
            if (file.delete()) deleted++
        }
        return deleted
    }

    private fun compactOldUiSnapshots(root: File, keepFullNewest: Int): Int {
        if (!root.isDirectory) return 0
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.absolutePath })
            .toList()
        var compacted = 0
        files.drop(keepFullNewest.coerceAtLeast(0)).forEach { file ->
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@forEach
            val state = json.optJSONObject("state") ?: return@forEach
            val nodes = state.optJSONArray("nodes")
            val events = state.optJSONArray("eventsSinceLastState")
            if ((nodes?.length() ?: 0) == 0 && (events?.length() ?: 0) == 0) return@forEach
            state
                .put("nodeCount", state.optInt("nodeCount", nodes?.length() ?: 0))
                .put("nodesStored", 0)
                .put("nodesStorage", "compacted_after_retention_window")
                .put("nodes", org.json.JSONArray())
                .put("eventsSinceLastState", org.json.JSONArray())
            json.put("state", state)
            json.put("compactedAt", System.currentTimeMillis())
            if (runCatching { file.writeText(json.toString(2)) }.isSuccess) compacted++
        }
        return compacted
    }

    private fun compactOldTrainingSamples(root: File, keepFullNewest: Int): Int {
        if (!root.isDirectory) return 0
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.absolutePath })
            .toList()
        var compacted = 0
        files.drop(keepFullNewest.coerceAtLeast(0)).forEach { file ->
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@forEach
            val state = json.optJSONObject("beforeState") ?: return@forEach
            val nodes = state.optJSONArray("nodes")
            val events = state.optJSONArray("eventsSinceLastState")
            if ((nodes?.length() ?: 0) == 0 && (events?.length() ?: 0) == 0) return@forEach
            state
                .put("nodeCount", state.optInt("nodeCount", nodes?.length() ?: 0))
                .put("nodesStored", 0)
                .put("storagePolicy", "compacted_after_retention_window")
                .put("nodes", org.json.JSONArray())
                .put("eventsSinceLastState", org.json.JSONArray())
            json.put("beforeState", state)
            json.put("compactedAt", System.currentTimeMillis())
            if (runCatching { file.writeText(json.toString(2)) }.isSuccess) compacted++
        }
        return compacted
    }

    private fun factoryBreakdown(): JSONObject {
        val out = JSONObject()
        if (!factoryDir.exists()) return out
        val names = listOf(
            "skill_json_candidates",
            "skill_json_dry_run_reports",
            "skill_json_test_reports",
            "app_config_exports",
            "skill_json_promoted",
            "ai_jobs",
            "flow_reports",
            "ui_snapshots",
            "ui_screenshots",
            "app_ui_graphs",
            "autonomous_training_records"
        )
        names.forEach { name ->
            val dir = File(factoryDir, name)
            out.put(name, JSONObject()
                .put("files", countAllFiles(dir))
                .put("bytes", sizeOf(dir)))
        }
        return out
    }
}

data class DataCompactionReport(
    val deletedFiles: Int,
    val beforeBytes: Long,
    val afterBytes: Long,
    val beforeFactoryBytes: Long,
    val afterFactoryBytes: Long,
    val afterInventory: LocalDataInventory
) {
    fun compact(): String {
        return "deleted=$deletedFiles factory=${beforeFactoryBytes}B->${afterFactoryBytes}B total=${beforeBytes}B->${afterBytes}B after=${afterInventory.compact()}"
    }
}

data class LocalDataInventory(
    val trainingSamples: Int,
    val approvedFiles: Int,
    val factoryFiles: Int,
    val factoryBytes: Long,
    val uiSnapshotFiles: Int,
    val uiScreenshotFiles: Int,
    val appUiGraphFiles: Int,
    val assemblyReportFiles: Int,
    val autonomousTrainingRecords: Int,
    val runLogs: Int,
    val coordinateCandidates: Int,
    val exportFiles: Int,
    val factoryBreakdown: JSONObject,
    val totalBytes: Long
) {
    fun compact(): String {
        return "samples=$trainingSamples approved=$approvedFiles factory=$factoryFiles(${factoryBytes}B) uiSnapshots=$uiSnapshotFiles screenshots=$uiScreenshotFiles graphs=$appUiGraphFiles assembly=$assemblyReportFiles trainRecords=$autonomousTrainingRecords runLogs=$runLogs coords=$coordinateCandidates exports=$exportFiles bytes=$totalBytes"
    }
}

data class TrainingExportPreview(
    val sampleCount: Int,
    val totalBytes: Long,
    val redactedSamples: Int,
    val personalDataSamples: Int,
    val exportAllowed: Boolean
) {
    fun compact(): String {
        return "samples=$sampleCount redacted=$redactedSamples personal=$personalDataSamples bytes=$totalBytes allowed=$exportAllowed"
    }
}
