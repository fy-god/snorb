package com.macropilot.app.store

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FactorySkillJsonStore(private val context: Context) {
    private val rootDir: File
        get() = File(context.filesDir, "macro_v2/factory")

    val skillJsonDir: File
        get() = File(rootDir, "skill_json_candidates")

    val dryRunReportDir: File
        get() = File(rootDir, "skill_json_dry_run_reports")

    val testReportDir: File
        get() = File(rootDir, "skill_json_test_reports")

    val appConfigExportDir: File
        get() = File(rootDir, "app_config_exports")

    val promotedDir: File
        get() = File(rootDir, "skill_json_promoted")

    val aiJobDir: File
        get() = File(rootDir, "ai_jobs")

    val flowReportDir: File
        get() = File(rootDir, "flow_reports")

    val runtimeApprovedAppsDir: File
        get() = File(context.filesDir, "macro_v2/runtime/approved/apps")

    val runtimeRunReportDir: File
        get() = File(context.filesDir, "macro_v2/runtime/run_reports")

    private val maxFilesPerPackage = 80
    private val maxAiJobsPerDay = 60

    fun ensureCreated() {
        skillJsonDir.mkdirs()
        dryRunReportDir.mkdirs()
        testReportDir.mkdirs()
        appConfigExportDir.mkdirs()
        promotedDir.mkdirs()
        aiJobDir.mkdirs()
        flowReportDir.mkdirs()
        runtimeApprovedAppsDir.mkdirs()
        runtimeRunReportDir.mkdirs()
    }

    fun saveSkill(packageName: String, json: JSONObject): File {
        ensureCreated()
        val appDir = File(skillJsonDir, packageName.safeSegment())
        appDir.mkdirs()
        val id = json.optString("id").ifBlank { "skill_${System.currentTimeMillis()}" }
        val file = File(appDir, "${id.safeSegment()}.json")
        file.writeText(json.toString(2))
        pruneNewestFiles(appDir, maxFilesPerPackage)
        return file
    }

    fun clearCandidateSkills(packageName: String): Int {
        ensureCreated()
        val appDir = File(skillJsonDir, packageName.safeSegment())
        if (!appDir.isDirectory) return 0
        return appDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.count { it.delete() }
            ?: 0
    }

    fun loadSkillById(idOrPath: String): Pair<File, JSONObject>? {
        ensureCreated()
        val direct = File(idOrPath)
        if (direct.isFile) {
            return runCatching { direct to JSONObject(direct.readText()) }.getOrNull()
        }
        val id = idOrPath.removeSuffix(".json")
        return listSkillFiles(limit = 10_000)
            .firstOrNull { it.nameWithoutExtension == id.safeSegment() || runCatching { JSONObject(it.readText()).optString("id") == id }.getOrDefault(false) }
            ?.let { file -> runCatching { file to JSONObject(file.readText()) }.getOrNull() }
    }

    fun loadRuntimeApprovedSkillById(idOrPath: String): Pair<File, JSONObject>? {
        ensureCreated()
        val direct = File(idOrPath)
        val runtimeRoot = runtimeApprovedAppsDir.absolutePath.lowercase(Locale.US)
        if (direct.isFile) {
            val directPath = direct.absolutePath.lowercase(Locale.US)
            if (directPath.startsWith(runtimeRoot)) {
                return runCatching { direct to JSONObject(direct.readText()) }.getOrNull()
            }
        }
        val id = idOrPath.removeSuffix(".json")
        return listRuntimeApprovedSkills(limit = 2000)
            .firstOrNull { file ->
                file.nameWithoutExtension == id.safeSegment() ||
                    runCatching { JSONObject(file.readText()).optString("id") == id }.getOrDefault(false)
            }
            ?.let { file -> runCatching { file to JSONObject(file.readText()) }.getOrNull() }
    }

    fun listSkillFiles(packageName: String? = null, limit: Int = 5000): List<File> {
        ensureCreated()
        val root = packageName
            ?.takeIf { it.isNotBlank() }
            ?.let { File(skillJsonDir, it.safeSegment()) }
            ?: skillJsonDir
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .newestFiles(limit.coerceIn(1, 10_000))
    }

    fun saveDryRunReport(packageName: String, skillId: String, report: JSONObject): File {
        ensureCreated()
        val appDir = File(dryRunReportDir, packageName.safeSegment())
        appDir.mkdirs()
        val file = File(appDir, "${skillId.safeSegment()}_${System.currentTimeMillis()}.json")
        file.writeText(report.toString(2))
        pruneNewestFiles(appDir, maxFilesPerPackage)
        return file
    }

    fun listDryRunReports(packageName: String? = null, limit: Int = 200): List<File> {
        ensureCreated()
        val root = packageName
            ?.takeIf { it.isNotBlank() }
            ?.let { File(dryRunReportDir, it.safeSegment()) }
            ?: dryRunReportDir
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .newestFiles(limit.coerceIn(1, 1000))
    }

    fun saveTestReport(packageName: String, skillId: String, report: JSONObject): File {
        ensureCreated()
        val appDir = File(testReportDir, packageName.safeSegment())
        appDir.mkdirs()
        val file = File(appDir, "${skillId.safeSegment()}_${System.currentTimeMillis()}.json")
        file.writeText(report.toString(2))
        pruneNewestFiles(appDir, maxFilesPerPackage)
        return file
    }

    fun listTestReports(packageName: String? = null, limit: Int = 200): List<File> {
        ensureCreated()
        val root = packageName
            ?.takeIf { it.isNotBlank() }
            ?.let { File(testReportDir, it.safeSegment()) }
            ?: testReportDir
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .newestFiles(limit.coerceIn(1, 1000))
    }

    fun saveAppConfig(packageName: String, config: JSONObject): File {
        ensureCreated()
        val appDir = File(appConfigExportDir, packageName.safeSegment())
        appDir.mkdirs()
        val file = File(appDir, "app_config_${timestamp()}.json")
        file.writeText(config.toString(2))
        pruneNewestFiles(appDir, maxFilesPerPackage)
        return file
    }

    fun saveAssemblyReport(packageName: String, report: JSONObject): File {
        ensureCreated()
        val appDir = File(appConfigExportDir, packageName.safeSegment())
        appDir.mkdirs()
        val id = report.optString("id").ifBlank { "assembly_report_${timestamp()}" }
        val file = File(appDir, "${id.safeSegment()}.json")
        file.writeText(report.toString(2))
        pruneNewestFiles(appDir, maxFilesPerPackage)
        return file
    }

    fun listAppConfigs(packageName: String? = null, limit: Int = 100): List<File> {
        ensureCreated()
        val root = packageName
            ?.takeIf { it.isNotBlank() }
            ?.let { File(appConfigExportDir, it.safeSegment()) }
            ?: appConfigExportDir
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "json" && it.name.startsWith("app_config_") }
            .newestFiles(limit.coerceIn(1, 500))
    }

    fun listAssemblyReports(packageName: String? = null, limit: Int = 100): List<File> {
        ensureCreated()
        val root = packageName
            ?.takeIf { it.isNotBlank() }
            ?.let { File(appConfigExportDir, it.safeSegment()) }
            ?: appConfigExportDir
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "json" && it.name.startsWith("assembly_report_") }
            .newestFiles(limit.coerceIn(1, 500))
    }

    fun savePromotedSkill(packageName: String, json: JSONObject): File {
        ensureCreated()
        val appDir = File(promotedDir, packageName.safeSegment())
        appDir.mkdirs()
        val id = json.optString("id").ifBlank { "skill_${System.currentTimeMillis()}" }
        val file = File(appDir, "${id.safeSegment()}.json")
        file.writeText(json.toString(2))
        pruneNewestFiles(appDir, maxFilesPerPackage)
        return file
    }

    fun saveRuntimeApprovedSkill(packageName: String, json: JSONObject): File {
        ensureCreated()
        val skillDir = File(File(runtimeApprovedAppsDir, packageName.safeSegment()), "skills")
        skillDir.mkdirs()
        val id = json.optString("id").ifBlank { "skill_${System.currentTimeMillis()}" }
        val file = File(skillDir, "${id.safeSegment()}.json")
        file.writeText(json.toString(2))
        pruneNewestFiles(skillDir, maxFilesPerPackage)
        return file
    }

    fun listRuntimeApprovedSkills(packageName: String? = null, limit: Int = 500): List<File> {
        ensureCreated()
        val root = packageName
            ?.takeIf { it.isNotBlank() }
            ?.let { File(File(runtimeApprovedAppsDir, it.safeSegment()), "skills") }
            ?: runtimeApprovedAppsDir
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "json" && it.parentFile?.name == "skills" }
            .newestFiles(limit.coerceIn(1, 2000))
    }

    fun saveRuntimeApprovedConfig(packageName: String, config: JSONObject): File {
        ensureCreated()
        val appDir = File(runtimeApprovedAppsDir, packageName.safeSegment())
        appDir.mkdirs()
        val file = File(appDir, "config.json")
        file.writeText(config.toString(2))
        return file
    }

    fun runtimeApprovedConfigFile(packageName: String): File {
        ensureCreated()
        return File(File(runtimeApprovedAppsDir, packageName.safeSegment()), "config.json")
    }

    fun loadRuntimeApprovedConfig(packageName: String): JSONObject? {
        val file = runtimeApprovedConfigFile(packageName)
        return if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrNull() else null
    }

    fun saveRuntimeRunReport(packageName: String, report: JSONObject): File {
        ensureCreated()
        val appDir = File(runtimeRunReportDir, packageName.safeSegment())
        appDir.mkdirs()
        val id = report.optString("id").ifBlank { "runtime_run_${timestamp()}" }
        val file = File(appDir, "${id.safeSegment()}.json")
        file.writeText(report.toString(2))
        pruneNewestFiles(appDir, maxFilesPerPackage)
        return file
    }

    fun listRuntimeRunReports(packageName: String? = null, limit: Int = 100): List<File> {
        ensureCreated()
        val root = packageName
            ?.takeIf { it.isNotBlank() }
            ?.let { File(runtimeRunReportDir, it.safeSegment()) }
            ?: runtimeRunReportDir
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .newestFiles(limit.coerceIn(1, 500))
    }

    fun saveAiJob(job: JSONObject): File {
        ensureCreated()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dir = File(aiJobDir, date)
        dir.mkdirs()
        val id = job.optString("id").ifBlank { "job_${System.currentTimeMillis()}" }
        val file = File(dir, "${id.safeSegment()}.json")
        file.writeText(job.toString(2))
        pruneNewestFiles(dir, maxAiJobsPerDay)
        return file
    }

    fun listAiJobs(limit: Int = 200): List<File> {
        ensureCreated()
        if (!aiJobDir.isDirectory) return emptyList()
        return aiJobDir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .newestFiles(limit.coerceIn(1, 1000))
    }

    fun saveFlowReport(packageName: String, report: JSONObject): File {
        ensureCreated()
        val appDir = File(flowReportDir, packageName.safeSegment())
        appDir.mkdirs()
        val id = report.optString("id").ifBlank { "flow_report_${timestamp()}" }
        val file = File(appDir, "${id.safeSegment()}.json")
        file.writeText(report.toString(2))
        pruneNewestFiles(appDir, maxFilesPerPackage)
        return file
    }

    fun listFlowReports(packageName: String? = null, limit: Int = 100): List<File> {
        ensureCreated()
        val root = packageName
            ?.takeIf { it.isNotBlank() }
            ?.let { File(flowReportDir, it.safeSegment()) }
            ?: flowReportDir
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .newestFiles(limit.coerceIn(1, 500))
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    }

    private data class FileSnapshot(
        val file: File,
        val modifiedAt: Long,
        val path: String
    )

    private fun Sequence<File>.newestFiles(limit: Int): List<File> {
        return map { file ->
            FileSnapshot(
                file = file,
                modifiedAt = runCatching { file.lastModified() }.getOrDefault(0L),
                path = runCatching { file.absolutePath }.getOrDefault(file.path)
            )
        }
            .toList()
            .sortedWith(
                compareByDescending<FileSnapshot> { it.modifiedAt }
                    .thenBy { it.path }
            )
            .take(limit)
            .map { it.file }
    }

    private fun pruneNewestFiles(dir: File, keep: Int) {
        if (!dir.isDirectory) return
        val files = dir.walkTopDown()
            .filter { it.isFile }
            .map {
                FileSnapshot(
                    file = it,
                    modifiedAt = runCatching { it.lastModified() }.getOrDefault(0L),
                    path = runCatching { it.absolutePath }.getOrDefault(it.path)
                )
            }
            .toList()
            .sortedWith(
                compareByDescending<FileSnapshot> { it.modifiedAt }
                    .thenBy { it.path }
            )
        if (files.size <= keep) return
        files.drop(keep).forEach { snapshot ->
            runCatching { snapshot.file.delete() }
        }
    }

    private fun String.safeSegment(): String {
        return lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "item" }
    }
}
