package com.macropilot.app.factory

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.runtime.NodeInspector
import com.macropilot.app.store.AppUiModuleStore
import com.macropilot.app.store.FactorySkillJsonStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppSideFactoryFlowRunner(
    private val context: Context,
    private val onEvent: (AppSideFactoryFlowEvent) -> Unit
) {
    private val store = FactorySkillJsonStore(context).apply { ensureCreated() }
    private val actions = DeviceActions(context)
    private val inspector = NodeInspector(context)

    fun runBilibiliSearchFlow(query: String): JSONObject {
        val appPackage = SkillJsonRuleGenerator.BILIBILI_PACKAGE
        val normalizedQuery = query.trim().ifBlank { "猫和老鼠" }
        val startedAt = System.currentTimeMillis()
        val steps = JSONArray()
        var finalStatus = "SUCCESS"
        var previewConfigFile: File? = null
        var previewReportFile: File? = null
        var runtimeConfigFile: File? = null
        var runtimeRunReportFile: File? = null

        fun step(title: String, status: String, message: String, path: String? = null, extra: JSONObject? = null) {
            if (status.startsWith("FAILED")) finalStatus = "FAILED"
            val item = JSONObject()
                .put("title", title)
                .put("status", status)
                .put("message", message)
                .put("path", path ?: JSONObject.NULL)
                .put("timestamp", System.currentTimeMillis())
            if (extra != null) item.put("extra", extra)
            steps.put(item)
            onEvent(AppSideFactoryFlowEvent(title, status, message, path))
        }

        step(
            "手机端 B 站流水线启动",
            "RUNNING",
            "这次不走 ADB 广播，由 App 内部调用 Factory AI/规则、Skill JSON 测试器、PromotionGate 和 Runtime。"
        )

        try {
            val pipelineResult = FactoryAiPipeline(context, store).analyzeInstructionAndGenerateCandidates(
                instruction = "在哔哩哔哩搜索 $normalizedQuery",
                packageName = appPackage
            )
            step(
                "AI/规则生成候选",
                if (pipelineResult.candidateFiles.isEmpty()) "FAILED_NO_CANDIDATE" else "SUCCESS",
                "AI 状态=${pipelineResult.aiStatus}；候选=${pipelineResult.candidateFiles.size}；Job=${pipelineResult.jobFile.name}",
                pipelineResult.jobFile.absolutePath
            )

            ensureBilibiliRuleSubset(normalizedQuery, ::step)
            val candidateFiles = store.listSkillFiles(appPackage, 1000)
            if (candidateFiles.isEmpty()) {
                step("停止", "FAILED_NO_CANDIDATE", "没有可测试的哔哩哔哩 Skill JSON。")
                return finishReport(appPackage, normalizedQuery, finalStatus, startedAt, steps, null, null, null, null)
            }

            val dryRunReports = dryRunAllCandidates(appPackage, candidateFiles, ::step)
            val preview = exportConfig(appPackage, approvedOnly = false)
            previewConfigFile = preview?.first
            previewReportFile = preview?.second
            step(
                "拼装预览 app_config",
                if (previewConfigFile == null) "FAILED_RUNTIME_CONFIG" else "SUCCESS",
                if (previewConfigFile == null) "没有可拼装的候选 Skill。" else "候选 Skill 已拼成单个 app_config 预览。",
                previewConfigFile?.absolutePath
            )

            val skillIds = listOf(
                "bilibili.open_app",
                "bilibili.home.find_search_entry",
                "bilibili.home.click_search_entry",
                "bilibili.search.find_search_input",
                "bilibili.search.type_search_query",
                "bilibili.search.click_search_submit",
                BILIBILI_MACRO_ID
            )
            val testReports = mutableMapOf<String, JSONObject>()
            skillIds.forEach { skillId ->
                val loaded = store.loadSkillById(skillId)
                if (loaded == null) {
                    step("测试跳过：$skillId", "FAILED_DEPENDENCY_MISSING", "候选库里找不到这个 Skill JSON。")
                    return@forEach
                }
                val skill = loaded.second
                val runs = 1
                val params = paramsForSkill(skillId, normalizedQuery)
                val report = runPreparedTests(skill, params, runs, normalizedQuery, ::step)
                testReports[skillId] = report
                val reportFile = store.saveTestReport(appPackage, skillId, report)
                val updatedSkill = JSONObject(skill.toString())
                val factory = updatedSkill.optJSONObject("factory") ?: JSONObject()
                factory
                    .put("testRuns", report.optInt("testRuns"))
                    .put("successRate", report.optDouble("successRate"))
                    .put("lastTestReport", reportFile.absolutePath)
                    .put("lastTestAt", System.currentTimeMillis())
                updatedSkill.put("factory", factory)
                if (report.optDouble("successRate", 0.0) >= 0.80 && report.optInt("failed", 0) == 0) {
                    updatedSkill.put("status", "TEST_PASSED")
                }
                store.saveSkill(appPackage, updatedSkill)
                step(
                    "测试报告：$skillId",
                    if (report.optInt("failed", 0) == 0) "SUCCESS" else "FAILED_TEST",
                    "运行 ${report.optInt("testRuns")} 次；成功类=${report.optInt("success") + report.optInt("mediumConfidence") + report.optInt("lowConfidence")}；失败=${report.optInt("failed")}；建议=${report.optString("promotionSuggestion")}",
                    reportFile.absolutePath
                )

                step(
                    "PromotionGate：$skillId",
                    "SKIPPED_NEEDS_MORE_TESTS",
                    "本次是手机端快速可视化测试：只跑 1 次给用户看真实结果。要晋级仍需 atomic 10 次、macro 20 次。"
                )
            }

            val runtimeExport = exportConfig(appPackage, approvedOnly = true)
            runtimeConfigFile = runtimeExport?.first
            step(
                "装配 Runtime approved config",
                if (runtimeConfigFile == null) "FAILED_RUNTIME_CONFIG" else "SUCCESS",
                if (runtimeConfigFile == null) "没有 APPROVED Skill 可装配。" else "Runtime 将只从这个 config 加载 APPROVED JSON。",
                runtimeConfigFile?.absolutePath
            )

            val runtimeMacro = store.loadRuntimeApprovedSkillById(BILIBILI_MACRO_ID)
            if (runtimeMacro == null) {
                step(
                    "Runtime 调用 B 站搜索",
                    "SKIPPED_NO_APPROVED_SKILL",
                    "当前还没有已晋级的 $BILIBILI_MACRO_ID。上面的候选实跑报告已经证明 JSON 能否控制手机；正式 Runtime 仍只执行 APPROVED。"
                )
            } else {
                prepareBilibiliHome()
                val runStarted = System.currentTimeMillis()
                val result = SkillJsonExecutor(
                    context = context,
                    runtimeApprovedOnly = true
                ).execute(runtimeMacro.second, JSONObject().put("query", normalizedQuery), allowSideEffect = true)
                val report = JSONObject()
                    .put("schemaVersion", 1)
                    .put("kind", "skill_json_runtime_run_report")
                    .put("id", "runtime_run_${System.currentTimeMillis()}")
                    .put("skillId", BILIBILI_MACRO_ID)
                    .put("appPackage", appPackage)
                    .put("engine", "skill_json_runtime")
                    .put("status", result.status)
                    .put("message", result.message)
                    .put("durationMs", System.currentTimeMillis() - runStarted)
                    .put("usedCoordinate", result.usedCoordinate)
                    .put("allowSideEffect", true)
                    .put("params", JSONObject().put("query", normalizedQuery))
                    .put("runtimeSkillFile", runtimeMacro.first.absolutePath)
                    .put("runtimeConfigFile", store.runtimeApprovedConfigFile(appPackage).absolutePath)
                    .put("createdAt", System.currentTimeMillis())
                runtimeRunReportFile = store.saveRuntimeRunReport(appPackage, report)
                step(
                    "Runtime 调用 B 站搜索",
                    if (result.status.startsWith("FAILED")) result.status else "SUCCESS",
                    "$BILIBILI_MACRO_ID -> ${result.status}；${result.message}",
                    runtimeRunReportFile?.absolutePath
                )
            }
        } catch (error: Throwable) {
            step("手机端流水线异常", "FAILED_EXCEPTION", error.message ?: error.javaClass.simpleName)
        } finally {
            returnToMacroPilot()
            step("返回 MacroPilot", "INFO", "已尝试把 MacroPilot 带回前台。若系统限制后台拉起，用户手动回到 App 后第一屏也会显示这次结果。")
        }

        return finishReport(
            appPackage,
            normalizedQuery,
            finalStatus,
            startedAt,
            steps,
            previewConfigFile,
            previewReportFile,
            runtimeConfigFile,
            runtimeRunReportFile
        )
    }

    private fun ensureBilibiliRuleSubset(
        query: String,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ) {
        val appPackage = SkillJsonRuleGenerator.BILIBILI_PACKAGE
        val required = setOf(
            "bilibili.open_app",
            "bilibili.home.find_search_entry",
            "bilibili.home.click_search_entry",
            "bilibili.search.find_search_input",
            "bilibili.search.type_search_query",
            "bilibili.search.click_search_submit",
            BILIBILI_MACRO_ID
        )
        val existingIds = store.listSkillFiles(appPackage, 1000).mapNotNull { file ->
            runCatching { JSONObject(file.readText()).optString("id").takeIf { it.isNotBlank() } }.getOrNull()
        }.toSet()
        val missing = required - existingIds
        if (missing.isEmpty()) {
            step("规则子集检查", "SUCCESS", "哔哩哔哩 7 个细粒度 Skill 已齐。", null, null)
            return
        }
        val files = SkillJsonRuleGenerator().generateBilibiliSearch(query).map { skill ->
            store.saveSkill(appPackage, skill)
        }
        step(
            "规则泛化补齐 B 站 Skill",
            "SUCCESS",
            "AI 输出不完整或接口不可用时，规则系统补齐 ${files.size} 个 atomic/macro Skill JSON。",
            files.firstOrNull()?.absolutePath,
            JSONObject().put("missingIds", JSONArray(missing.toList()))
        )
    }

    private fun dryRunAllCandidates(
        appPackage: String,
        files: List<File>,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): Map<String, JSONObject> {
        val knownIds = store.listSkillFiles(appPackage, 1000).mapNotNull { file ->
            runCatching { JSONObject(file.readText()).optString("id").takeIf { it.isNotBlank() } }.getOrNull()
        }.toSet()
        val engine = SkillJsonDryRunEngine()
        val reports = mutableMapOf<String, JSONObject>()
        var passed = 0
        files.forEach { file ->
            val skill = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@forEach
            val skillId = skill.optString("id", file.nameWithoutExtension)
            val report = engine.dryRun(skill, knownIds)
            val ok = report.optBoolean("passed")
            if (ok) passed++
            reports[skillId] = report
            val reportFile = store.saveDryRunReport(appPackage, skillId, report)
            val factory = skill.optJSONObject("factory") ?: JSONObject()
            factory
                .put("dryRunStatus", if (ok) "PASSED" else "FAILED")
                .put("lastDryRunReport", reportFile.absolutePath)
                .put("lastDryRunAt", System.currentTimeMillis())
            skill.put("factory", factory)
            skill.put("status", if (ok) "DRY_RUN_PASSED" else "REJECTED")
            store.saveSkill(appPackage, skill)
            step(
                "dry-run：$skillId",
                if (ok) "SUCCESS" else "FAILED_DRY_RUN",
                if (ok) "静态检查通过。" else "失败：${report.optJSONArray("errors")}",
                reportFile.absolutePath,
                null
            )
        }
        step("dry-run 汇总", if (passed == files.size) "SUCCESS" else "FAILED_DRY_RUN", "通过 $passed/${files.size}。", null, null)
        return reports
    }

    private fun runPreparedTests(
        skill: JSONObject,
        params: JSONObject,
        runs: Int,
        query: String,
        step: (String, String, String, String?, JSONObject?) -> Unit
    ): JSONObject {
        val executor = SkillJsonExecutor(context)
        val skillId = skill.optString("id")
        val total = runs.coerceIn(1, 50)
        val results = mutableListOf<JSONObject>()
        for (index in 1..total) {
            step("测试：$skillId", "RUNNING", "第 $index/$total 次，正在准备目标 App 状态。", null, null)
            val prepareError = prepareForSkill(skillId, query)
            val startedAt = System.currentTimeMillis()
            val result = if (prepareError == null) {
                executor.execute(skill, params, allowSideEffect = true)
            } else {
                SkillJsonRunResult("FAILED_PRECONDITION", prepareError)
            }
            val item = JSONObject()
                .put("run", index)
                .put("status", result.status)
                .put("message", result.message)
                .put("durationMs", result.durationMs.takeIf { it > 0 } ?: (System.currentTimeMillis() - startedAt))
                .put("usedCoordinate", result.usedCoordinate)
            results += item
            step(
                "测试：$skillId",
                if (result.status.startsWith("FAILED")) result.status else "SUCCESS",
                "第 $index/$total 次：${result.status}；${result.message}",
                null,
                null
            )
        }
        return buildTestReport(skill, results, allowSideEffect = true)
    }

    private fun prepareForSkill(skillId: String, query: String): String? {
        return when (skillId) {
            "bilibili.open_app" -> null
            "bilibili.home.find_search_entry",
            "bilibili.home.click_search_entry",
            BILIBILI_MACRO_ID -> prepareBilibiliHome()
            "bilibili.search.find_search_input",
            "bilibili.search.type_search_query" -> prepareBilibiliSearchInput()
            "bilibili.search.click_search_submit" -> {
                prepareBilibiliSearchInput()
                    ?: run {
                        val typeSkill = store.loadSkillById("bilibili.search.type_search_query")?.second
                            ?: return "缺少 bilibili.search.type_search_query"
                        val result = SkillJsonExecutor(context).execute(typeSkill, JSONObject().put("query", query), allowSideEffect = true)
                        if (result.status.startsWith("FAILED")) result.message else null
                    }
            }
            else -> null
        }
    }

    private fun prepareBilibiliHome(): String? {
        val open = actions.openApp(SkillJsonRuleGenerator.BILIBILI_PACKAGE)
        if (!open.success) return open.reason
        sleep(1200L)
        repeat(5) {
            val state = inspector.currentState()
            if (state?.appPackage == SkillJsonRuleGenerator.BILIBILI_PACKAGE && hasSearchEntry()) return null
            if (state?.appPackage != SkillJsonRuleGenerator.BILIBILI_PACKAGE) {
                actions.openApp(SkillJsonRuleGenerator.BILIBILI_PACKAGE)
                sleep(900L)
            } else {
                actions.back()
                sleep(650L)
            }
        }
        val state = inspector.currentState()
        return if (state?.appPackage == SkillJsonRuleGenerator.BILIBILI_PACKAGE) null else "哔哩哔哩没有保持在前台"
    }

    private fun prepareBilibiliSearchInput(): String? {
        val home = prepareBilibiliHome()
        if (home != null) return home
        val click = store.loadSkillById("bilibili.home.click_search_entry")?.second
            ?: return "缺少 bilibili.home.click_search_entry"
        val result = SkillJsonExecutor(context).execute(click, JSONObject(), allowSideEffect = true)
        if (result.status.startsWith("FAILED")) return result.message
        val ready = waitUntil(2500L) { hasSearchInput() }
        return if (ready) null else "搜索输入框没有出现"
    }

    private fun hasSearchEntry(): Boolean {
        return inspector.currentState()?.nodes?.any { node ->
            node.resourceId == "tv.danmaku.bili:id/expand_search" ||
                node.contentDesc?.contains("搜索栏") == true ||
                (node.clickable && (node.text?.contains("搜索") == true || node.contentDesc?.contains("搜索") == true))
        } == true
    }

    private fun hasSearchInput(): Boolean {
        return inspector.currentState()?.nodes?.any { node ->
            node.resourceId == "tv.danmaku.bili:id/search_src_text" || node.editable
        } == true
    }

    private fun paramsForSkill(skillId: String, query: String): JSONObject {
        return when (skillId) {
            "bilibili.search.type_search_query",
            BILIBILI_MACRO_ID -> JSONObject().put("query", query)
            else -> JSONObject()
        }
    }

    private fun exportConfig(packageName: String, approvedOnly: Boolean): Pair<File, File>? {
        val sourceFiles = if (approvedOnly) {
            store.listRuntimeApprovedSkills(packageName, 1000)
        } else {
            store.listSkillFiles(packageName, 1000)
        }
        val skills = sourceFiles
            .mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
            .filter { skill -> !approvedOnly || skill.optString("status") == "APPROVED" }
            .filter { skill -> approvedOnly || skill.optString("status") != "REJECTED" }
        if (skills.isEmpty()) return null
        val module = AppUiModuleStore(context).loadApproved(packageName)
            ?: AppUiModuleStore(context).listCandidates().firstOrNull { (_, candidate) -> candidate.packageName == packageName }?.second
        val reports = store.listDryRunReports(packageName, 500)
            .mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() } +
            store.listTestReports(packageName, 500)
                .mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
        val exporter = SkillJsonExporter()
        val config = exporter.exportAppConfig(
            packageName = packageName,
            appName = module?.appName ?: "哔哩哔哩",
            module = module,
            skills = skills,
            dryRunReports = reports
        )
        val configFile = if (approvedOnly) {
            store.saveRuntimeApprovedConfig(packageName, config)
        } else {
            store.saveAppConfig(packageName, config)
        }
        val report = exporter.buildAssemblyReport(
            packageName = packageName,
            appName = module?.appName ?: "哔哩哔哩",
            approvedOnly = approvedOnly,
            sourceFiles = sourceFiles,
            includedSkills = skills,
            dryRunReportCount = store.listDryRunReports(packageName, 500).size,
            testReportCount = store.listTestReports(packageName, 500).size,
            config = config,
            previewConfigFile = if (approvedOnly) null else configFile,
            runtimeConfigFile = if (approvedOnly) configFile else null
        )
        return configFile to store.saveAssemblyReport(packageName, report)
    }

    private fun finishReport(
        appPackage: String,
        query: String,
        status: String,
        startedAt: Long,
        steps: JSONArray,
        previewConfigFile: File?,
        previewReportFile: File?,
        runtimeConfigFile: File?,
        runtimeRunReportFile: File?
    ): JSONObject {
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "app_side_skill_factory_flow_report")
            .put("operation", "APP_SIDE_BILIBILI_SEARCH_FACTORY_FLOW")
            .put("id", "app_side_flow_${System.currentTimeMillis()}")
            .put("appPackage", appPackage)
            .put("query", query)
            .put("status", status)
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("steps", steps)
            .put("outputs", JSONObject()
                .put("previewConfigFile", previewConfigFile?.absolutePath ?: JSONObject.NULL)
                .put("previewReportFile", previewReportFile?.absolutePath ?: JSONObject.NULL)
                .put("runtimeConfigFile", runtimeConfigFile?.absolutePath ?: JSONObject.NULL)
                .put("runtimeRunReportFile", runtimeRunReportFile?.absolutePath ?: JSONObject.NULL))
            .put("createdAt", System.currentTimeMillis())
        val file = store.saveFlowReport(appPackage, report)
        report.put("reportFile", file.absolutePath)
        onEvent(AppSideFactoryFlowEvent(
            title = "手机端 B 站流水线完成",
            status = status,
            message = "报告已写入：${file.name}；步骤=${steps.length()}。",
            path = file.absolutePath
        ))
        return report
    }

    private fun buildTestReport(
        skill: JSONObject,
        results: List<JSONObject>,
        allowSideEffect: Boolean
    ): JSONObject {
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

    private fun latestJsonReportForSkill(files: List<File>, skillId: String): JSONObject? {
        return files.firstNotNullOfOrNull { file ->
            runCatching {
                JSONObject(file.readText()).takeIf { json ->
                    json.optString("skillId") == skillId || file.nameWithoutExtension.startsWith(skillId.replace('.', '_'))
                }
            }.getOrNull()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started <= timeoutMs) {
            if (condition()) return true
            sleep(80L)
        }
        return false
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun returnToMacroPilot() {
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.appTasks
                ?.firstOrNull()
                ?.moveToFront()
        }
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent().setPackage(context.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }
    }

    private companion object {
        const val BILIBILI_MACRO_ID = "bilibili.search.search_in_app"
    }
}

data class AppSideFactoryFlowEvent(
    val title: String,
    val status: String,
    val message: String,
    val path: String? = null
)
