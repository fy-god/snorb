package com.macropilot.app.factory

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.runtime.CapabilityProfiler
import com.macropilot.app.runtime.NodeInspector
import com.macropilot.app.store.AppUiGraphStore
import com.macropilot.app.store.FactorySkillJsonStore
import com.macropilot.app.store.UiStateSnapshotStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class PopularAppTaskBatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded()
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
            ?: "popular_app_task_batch_${System.currentTimeMillis()}"
        if (intent?.getBooleanExtra(EXTRA_CANCEL, false) == true) {
            cancelled.set(true)
            saveState(requestId, emptyList(), "CANCEL_REQUESTED", "用户已请求停止当前 20 App 任务批次。", null, null)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) {
            saveState(requestId, emptyList(), "FAILED_BUSY", "已有一个 20 App 手机端任务批次正在运行。", null, null)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        cancelled.set(false)
        val packages = intent?.getStringArrayListExtra(EXTRA_PACKAGE_NAMES)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: parsePackages(intent?.getStringExtra(EXTRA_PACKAGE_NAMES).orEmpty())
        val limitApps = intent?.getIntExtra(EXTRA_LIMIT_APPS, 20)?.coerceIn(1, 50) ?: 20
        val tasksPerApp = intent?.getIntExtra(EXTRA_TASKS_PER_APP, 10)?.coerceIn(1, 20) ?: 10
        val maxTaskMs = intent?.getLongExtra(EXTRA_MAX_TASK_MS, 300_000L)?.coerceIn(30_000L, 900_000L) ?: 300_000L
        val retryFailed = intent?.getBooleanExtra(EXTRA_RETRY_FAILED, true) ?: true
        val catalogOnly = intent?.getBooleanExtra(EXTRA_CATALOG_ONLY, false) ?: false
        val taskIds = intent?.getStringArrayListExtra(EXTRA_TASK_IDS)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: parsePackages(intent?.getStringExtra(EXTRA_TASK_IDS).orEmpty())

        Thread({
            try {
                val report = runBatch(
                    requestId = requestId,
                    packageFilter = packages,
                    taskIds = taskIds,
                    limitApps = limitApps,
                    tasksPerApp = tasksPerApp,
                    maxTaskMs = maxTaskMs,
                    retryFailed = retryFailed,
                    catalogOnly = catalogOnly
                )
                saveState(
                    requestId = requestId,
                    packageFilter = packages,
                    status = report.optString("status"),
                    message = "20 App 任务批次完成：${report.optJSONObject("summary")?.toString() ?: report.optString("status")}",
                    payload = report,
                    progress = report.optJSONObject("summary")
                )
            } catch (error: Throwable) {
                Log.e(TAG, "POPULAR_APP_TASK_BATCH_FAILED requestId=$requestId", error)
                saveState(
                    requestId = requestId,
                    packageFilter = packages,
                    status = "FAILED_EXCEPTION",
                    message = error.message ?: error.javaClass.simpleName,
                    payload = JSONObject()
                        .put("errorClass", error.javaClass.name)
                        .put("stackTrace", Log.getStackTraceString(error).take(16_000)),
                    progress = null
                )
            } finally {
                cancelled.set(false)
                running.set(false)
                stopSelf(startId)
            }
        }, "macropilot-popular-app-task-batch-$requestId").start()
        return START_NOT_STICKY
    }

    private fun runBatch(
        requestId: String,
        packageFilter: List<String>,
        taskIds: List<String>,
        limitApps: Int,
        tasksPerApp: Int,
        maxTaskMs: Long,
        retryFailed: Boolean,
        catalogOnly: Boolean
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        val store = FactorySkillJsonStore(applicationContext).apply { ensureCreated() }
        val requestedTaskIds = taskIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val resolvedApps = PopularAppTaskCatalog.resolveProfiles(applicationContext, packageFilter, limitApps, tasksPerApp)
            .map { app ->
                if (requestedTaskIds.isEmpty()) app else app.copy(tasks = app.tasks.filter { it.taskId in requestedTaskIds })
            }
            .filter { requestedTaskIds.isEmpty() || it.tasks.isNotEmpty() }
        val catalog = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "popular_app_task_catalog")
            .put("appCount", resolvedApps.size)
            .put("tasksPerApp", tasksPerApp.coerceIn(1, 20))
            .put("taskIds", JSONArray(taskIds))
            .put("totalTaskCount", resolvedApps.sumOf { it.tasks.size })
            .put("apps", JSONArray(resolvedApps.map { it.toJson(tasksPerApp) }))
            .put("safetyPolicy", JSONObject()
                .put("adbManualTargetUiControl", false)
                .put("paymentPasswordInputAllowed", false)
                .put("submitOrderAllowed", false))
            .put("requestId", requestId)
        val catalogFile = store.saveFlowReport("__popular_app_tasks__", catalog.put("id", "${requestId}_catalog"))
        val totalTasks = resolvedApps.sumOf { it.tasks.size }

        saveState(
            requestId = requestId,
            packageFilter = packageFilter,
            status = if (catalogOnly) "CATALOG_ONLY" else "RUNNING",
            message = "已设计 ${resolvedApps.size} 个 App、${totalTasks} 个任务；目录已保存，准备由手机端逐项执行。",
            payload = JSONObject().put("catalogFile", catalogFile.absolutePath),
            progress = JSONObject()
                .put("phase", "CATALOG_READY")
                .put("appCount", resolvedApps.size)
                .put("taskCount", totalTasks)
                .put("catalogFile", catalogFile.absolutePath)
        )
        if (catalogOnly) {
            return JSONObject()
                .put("schemaVersion", 1)
                .put("kind", "popular_app_task_batch_report")
                .put("id", requestId)
                .put("requestId", requestId)
                .put("status", "CATALOG_ONLY")
                .put("catalogFile", catalogFile.absolutePath)
                .put("summary", JSONObject()
                    .put("appCount", resolvedApps.size)
                    .put("taskCount", totalTasks)
                    .put("executed", 0)
                    .put("message", "只生成任务目录，未执行手机端任务。"))
                .put("apps", JSONArray(resolvedApps.map { it.toJson(tasksPerApp) }))
                .put("durationMs", System.currentTimeMillis() - startedAt)
                .put("createdAt", System.currentTimeMillis())
                .also { report ->
                    val summary = report.optJSONObject("summary") ?: JSONObject()
                    summary
                        .put("executedAttempts", 0)
                        .put("success", 0)
                        .put("failed", 0)
                        .put("blocked", 0)
                        .put("deferred", 0)
                        .put("skipped", totalTasks)
                        .put("retried", 0)
                    BatchReportRates.addPopularTaskRates(summary)
                }
        }

        val readiness = readinessJson()
        if (!readiness.optBoolean("ready")) {
            return JSONObject()
                .put("schemaVersion", 1)
                .put("kind", "popular_app_task_batch_report")
                .put("id", requestId)
                .put("requestId", requestId)
                .put("status", readiness.optString("status", "FAILED_DEVICE_NOT_READY"))
                .put("catalogFile", catalogFile.absolutePath)
                .put("readiness", readiness)
                .put("summary", JSONObject()
                    .put("appCount", resolvedApps.size)
                    .put("taskCount", totalTasks)
                    .put("executed", 0)
                    .put("failed", totalTasks)
                    .put("blocker", readiness.optString("message")))
                .put("apps", JSONArray(resolvedApps.map { it.toJson(tasksPerApp) }))
                .put("durationMs", System.currentTimeMillis() - startedAt)
                .put("createdAt", System.currentTimeMillis())
                .also { report ->
                    val summary = report.optJSONObject("summary") ?: JSONObject()
                    summary
                        .put("executedAttempts", 0)
                        .put("success", 0)
                        .put("failed", 0)
                        .put("blocked", totalTasks)
                        .put("deferred", 0)
                        .put("skipped", 0)
                        .put("retried", 0)
                    BatchReportRates.addPopularTaskRates(summary)
                }
        }

        val appReports = JSONArray()
        var executed = 0
        var success = 0
        var failed = 0
        var blocked = 0
        var deferred = 0
        var skipped = 0
        var retried = 0
        var deviceBlocker: JSONObject? = null

        resolvedApps.forEachIndexed appLoop@{ appIndex, app ->
            if (cancelled.get()) {
                skipped += app.tasks.size
                appReports.put(appSkipped(app, tasksPerApp, "CANCELLED"))
                return@appLoop
            }
            val existingDeviceBlocker = deviceBlocker
            if (existingDeviceBlocker != null) {
                skipped += app.tasks.size
                appReports.put(appSkipped(app, tasksPerApp, existingDeviceBlocker.optString("status", "FAILED_DEVICE_NOT_READY")))
                return@appLoop
            }
            val appReport = JSONObject()
                .put("packageName", app.profile.packageName)
                .put("resolvedPackage", app.resolvedPackage ?: JSONObject.NULL)
                .put("appName", app.profile.appName)
                .put("label", app.label)
                .put("installed", app.installed)
                .put("launchable", app.launchable)
                .put("tasks", JSONArray())
            appReports.put(appReport)
            if (!app.installed || app.resolvedPackage.isNullOrBlank()) {
                blocked += app.tasks.size
                app.tasks.forEach { task ->
                    appReport.optJSONArray("tasks")?.put(taskReport(task, "BLOCKED_APP_NOT_INSTALLED", "目标 App 未安装或包名未匹配。", null, null, false))
                }
                return@appLoop
            }
            if (!app.launchable) {
                blocked += app.tasks.size
                app.tasks.forEach { task ->
                    appReport.optJSONArray("tasks")?.put(taskReport(task, "BLOCKED_APP_NOT_LAUNCHABLE", "目标 App 没有可启动入口。", null, null, false))
                }
                return@appLoop
            }

            app.tasks.forEachIndexed taskLoop@{ taskIndex, task ->
                if (cancelled.get()) {
                    skipped += 1
                    appReport.optJSONArray("tasks")?.put(taskReport(task, "CANCELLED", "批次已取消。", null, null, false))
                    return@taskLoop
                }
                val taskReadiness = readinessJson()
                if (!taskReadiness.optBoolean("ready")) {
                    deviceBlocker = taskReadiness
                    val remainingTasks = app.tasks.drop(taskIndex)
                    blocked += remainingTasks.size
                    remainingTasks.forEach { remainingTask ->
                        appReport.optJSONArray("tasks")?.put(
                            deviceNotReadyTaskReport(
                                task = remainingTask,
                                readiness = taskReadiness,
                                attempt = 0,
                                startedAt = System.currentTimeMillis(),
                                retried = false
                            )
                        )
                    }
                    saveState(
                        requestId = requestId,
                        packageFilter = packageFilter,
                        status = taskReadiness.optString("status", "FAILED_DEVICE_NOT_READY"),
                        message = taskReadiness.optString("message", "手机端能力掉线，批次停止。"),
                        payload = JSONObject().put("readiness", taskReadiness),
                        progress = JSONObject()
                            .put("phase", "DEVICE_NOT_READY")
                            .put("appIndex", appIndex + 1)
                            .put("appTotal", resolvedApps.size)
                            .put("taskIndex", taskIndex + 1)
                            .put("tasksPerApp", app.tasks.size)
                            .put("packageName", app.resolvedPackage)
                            .put("appName", app.profile.appName)
                            .put("taskId", task.taskId)
                            .put("readiness", taskReadiness)
                    )
                    MacroPilotAccessibilityService.instance?.showStatusOverlay(
                        "20 App 任务批次",
                        "FAILED_DEVICE_NOT_READY",
                        taskReadiness.optString("message", "手机端能力掉线，批次停止。")
                    )
                    return@appLoop
                }
                val progress = JSONObject()
                    .put("phase", "TASK_RUNNING")
                    .put("appIndex", appIndex + 1)
                    .put("appTotal", resolvedApps.size)
                    .put("taskIndex", taskIndex + 1)
                    .put("tasksPerApp", app.tasks.size)
                    .put("packageName", app.resolvedPackage)
                    .put("appName", app.profile.appName)
                    .put("taskId", task.taskId)
                    .put("instruction", task.instruction)
                    .put("executed", executed)
                    .put("success", success)
                    .put("failed", failed)
                    .put("blocked", blocked)
                BatchReportRates.addPopularTaskRates(
                    progress
                        .put("taskCount", totalTasks)
                        .put("executedAttempts", executed)
                        .put("deferred", deferred)
                        .put("skipped", skipped)
                        .put("retried", retried)
                )
                saveState(requestId, packageFilter, "RUNNING", "[${appIndex + 1}/${resolvedApps.size}] ${app.profile.appName} ${taskIndex + 1}/${app.tasks.size}: ${task.taskId}", null, progress)
                MacroPilotAccessibilityService.instance?.showStatusOverlay(
                    "20 App 任务批次",
                    "RUNNING",
                    "${app.profile.appName} ${taskIndex + 1}/${app.tasks.size}: ${task.instruction.take(56)}"
                )
                val result = runOneTask(
                    requestId = requestId,
                    app = app,
                    task = task,
                    attempt = 1,
                    maxTaskMs = maxTaskMs
                )
                executed += 1
                var finalResult = result
                if (retryFailed && shouldRetry(result)) {
                    retried += 1
                    val retryInstruction = buildRetryInstruction(task, result)
                    finalResult = runOneTask(
                        requestId = requestId,
                        app = app,
                        task = task.copyForRetry(retryInstruction),
                        attempt = 2,
                        maxTaskMs = maxTaskMs
                    )
                    executed += 1
                    finalResult.put("firstAttempt", result)
                }
                attachTaskEvidenceScreenshot(
                    requestId = requestId,
                    appIndex = appIndex + 1,
                    taskIndex = taskIndex + 1,
                    app = app,
                    task = task,
                    report = finalResult
                )
                when {
                    finalResult.optString("status").startsWith("SUCCESS") -> success += 1
                    finalResult.optString("status").startsWith("BLOCKED") -> blocked += 1
                    finalResult.optString("status").startsWith("PENDING") -> deferred += 1
                    else -> failed += 1
                }
                appReport.optJSONArray("tasks")?.put(finalResult)
            }
        }

        val status = when {
            cancelled.get() -> "CANCELLED"
            deviceBlocker != null -> deviceBlocker?.optString("status", "FAILED_DEVICE_NOT_READY") ?: "FAILED_DEVICE_NOT_READY"
            executed == 0 && blocked > 0 -> "BLOCKED"
            failed == 0 && blocked == 0 && deferred == 0 -> "SUCCESS"
            failed == 0 && deferred > 0 -> "PARTIAL_DEFERRED"
            success > 0 -> "PARTIAL"
            else -> "FAILED"
        }
        val summary = JSONObject()
            .put("appCount", resolvedApps.size)
            .put("taskCount", totalTasks)
            .put("executedAttempts", executed)
            .put("success", success)
            .put("failed", failed)
            .put("blocked", blocked)
            .put("deferred", deferred)
            .put("skipped", skipped)
            .put("retried", retried)
            .put("deviceBlocker", deviceBlocker ?: JSONObject.NULL)
            .put("catalogFile", catalogFile.absolutePath)
        BatchReportRates.addPopularTaskRates(summary)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "popular_app_task_batch_report")
            .put("id", requestId)
            .put("requestId", requestId)
            .put("status", status)
            .put("mode", "PHONE_SIDE_AI_SKILL_JSON_ACCESSIBILITY_TASK_BATCH")
            .put("catalogFile", catalogFile.absolutePath)
            .put("readiness", readiness)
            .put("summary", summary)
            .put("apps", appReports)
            .put("safetyPolicy", JSONObject()
                .put("adbManualTargetUiControl", false)
                .put("paymentPasswordInputAllowed", false)
                .put("submitOrderAllowed", false)
                .put("publishOrCommentSubmitAllowedByDefault", false)
                .put("retryUsesAiFailureReview", true))
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("createdAt", System.currentTimeMillis())
        val reportFile = store.saveFlowReport("__popular_app_tasks__", report)
        report.put("reportFile", reportFile.absolutePath)
        reportFile.writeText(report.toString(2))
        return report
    }

    private fun runOneTask(
        requestId: String,
        app: ResolvedPopularAppProfile,
        task: PopularAppTask,
        attempt: Int,
        maxTaskMs: Long
    ): JSONObject {
        if (task.isHighRiskGuardOnly()) {
            return runGuardOnlyTask(app, task, attempt)
        }
        if (task.category == "dialog_probe" || task.category == "ad_dialog_probe") {
            return runDialogProbeTask(app, task, attempt)
        }
        if (task.shouldUseUiExplorer()) {
            return runUiExplorerTask(app, task, attempt, maxTaskMs)
        }
        val effectiveMaxTaskMs = effectiveMaxTaskMs(app.resolvedPackage ?: app.profile.packageName, task, maxTaskMs)
        val startedAt = System.currentTimeMillis()
        val events = JSONArray()
        val instruction = guardedInstruction(app, task, attempt)
        val worker = Thread.currentThread()
        val resultBox = arrayOfNulls<JSONObject>(1)
        val errorBox = arrayOfNulls<Throwable>(1)
        val runnerThread = Thread({
            try {
                resultBox[0] = AppSideInstructionFlowRunner(applicationContext) { event ->
                    events.put(JSONObject()
                        .put("title", event.title)
                        .put("status", event.status)
                        .put("message", event.message)
                        .put("path", event.path ?: JSONObject.NULL)
                        .put("timestamp", System.currentTimeMillis()))
                }.runInstructionFlow(
                    instruction = instruction,
                    targetPackageHint = app.resolvedPackage
                )
            } catch (error: Throwable) {
                errorBox[0] = error
            } finally {
                worker.interrupt()
            }
        }, "macropilot-task-${task.taskId}-attempt-$attempt")
        runnerThread.start()
        val deadlineAt = startedAt + effectiveMaxTaskMs
        var readinessFailure: JSONObject? = null
        while (runnerThread.isAlive && System.currentTimeMillis() < deadlineAt && readinessFailure == null) {
            try {
                runnerThread.join(1_000L)
            } catch (_: InterruptedException) {
                // The runner woke us up because it finished.
            }
            if (runnerThread.isAlive) {
                val readiness = readinessJson()
                if (!readiness.optBoolean("ready")) {
                    readinessFailure = readiness
                }
            }
        }
        readinessFailure?.let { readiness ->
            runnerThread.interrupt()
            return deviceNotReadyTaskReport(
                task = task,
                readiness = readiness,
                attempt = attempt,
                startedAt = startedAt,
                retried = attempt > 1,
                events = events
            )
        }
        if (runnerThread.isAlive) {
            runnerThread.interrupt()
            return taskReport(
                task = task,
                status = "FAILED_TIMEOUT",
                message = "单任务超过 ${maxTaskMs / 1000}s 未完成，已记录超时；下一轮应缩短计划或先补 UI 图谱。",
                flowReport = null,
                events = events,
                retried = attempt > 1
            )
                .put("attempt", attempt)
                .put("durationMs", System.currentTimeMillis() - startedAt)
        }
        val error = errorBox[0]
        if (error != null) {
            return taskReport(
                task = task,
                status = "FAILED_EXCEPTION",
                message = error.message ?: error.javaClass.simpleName,
                flowReport = JSONObject()
                    .put("errorClass", error.javaClass.name)
                    .put("stackTrace", Log.getStackTraceString(error).take(12_000)),
                events = events,
                retried = attempt > 1
            )
                .put("attempt", attempt)
                .put("durationMs", System.currentTimeMillis() - startedAt)
        }
        val flow = resultBox[0] ?: JSONObject()
        val status = normalizeFlowStatus(flow)
        return taskReport(
            task = task,
            status = status,
            message = flow.optJSONArray("steps")?.let { lastStepMessage(it) }
                ?: flow.optString("status", status),
            flowReport = flow,
            events = events,
            retried = attempt > 1
        )
            .put("attempt", attempt)
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("flowReportFile", flow.optString("reportFile", ""))
            .put("outputs", flow.optJSONObject("outputs") ?: JSONObject())
    }

    private fun effectiveMaxTaskMs(packageName: String?, task: PopularAppTask, requestedMaxTaskMs: Long): Long {
        if (!isPinduoduoPackage(packageName)) return requestedMaxTaskMs
        return when (task.category) {
            "search", "search_open", "detail_probe", "order_probe", "shopping_probe" ->
                requestedMaxTaskMs.coerceAtLeast(120_000L)
            else -> requestedMaxTaskMs
        }
    }

    private fun runUiExplorerTask(
        app: ResolvedPopularAppProfile,
        task: PopularAppTask,
        attempt: Int,
        maxTaskMs: Long
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        val packageName = app.resolvedPackage ?: app.profile.packageName
        val pinduoduo = isPinduoduoPackage(packageName)
        val events = JSONArray()
        val store = FactorySkillJsonStore(applicationContext).apply { ensureCreated() }
        val actions = DeviceActions(applicationContext)
        val inspector = NodeInspector(applicationContext)
        val profiler = CapabilityProfiler()
        val snapshotStore = UiStateSnapshotStore(applicationContext)
        val graphStore = AppUiGraphStore(applicationContext)
        val explorer = AppUiExplorer(
            context = applicationContext,
            actions = actions,
            inspector = inspector,
            profiler = profiler,
            snapshotStore = snapshotStore,
            graphStore = graphStore,
            reactiveRules = ReactiveUiRuleEngine(inspector, actions)
        )
        return runCatching {
            MacroPilotAccessibilityService.instance?.showStatusOverlay(
                "UI 图谱任务",
                "RUNNING",
                "${app.profile.appName}: ${task.taskId}"
            )
            val result = explorer.explore(
                packageName = packageName,
                appName = app.profile.appName,
                reason = "popular_app_task_batch:${task.taskId}",
                maxScreens = when (task.category) {
                    "bottom_tab_probe" -> if (pinduoduo) 20 else 80
                    "graphical_entry_probe", "child_page_probe", "top_icon_probe" -> if (pinduoduo) 18 else 100
                    else -> 60
                },
                maxClicks = when (task.category) {
                    "bottom_tab_probe" -> if (pinduoduo) 12 else 70
                    "graphical_entry_probe", "child_page_probe", "top_icon_probe" -> if (pinduoduo) 14 else 90
                    else -> 50
                },
                maxDepth = if (pinduoduo) 1 else 4,
                maxSwipeActions = if (pinduoduo) 6 else 40,
                maxDurationMs = if (pinduoduo) {
                    maxTaskMs.coerceIn(25_000L, 45_000L)
                } else {
                    maxTaskMs.coerceIn(30_000L, 180_000L)
                },
                forceScreenshots = true,
                primaryNavigationOnly = task.category == "bottom_tab_probe" || (pinduoduo && task.category == "graphical_entry_probe"),
                onEvent = { phase, status, message, path ->
                    events.put(JSONObject()
                        .put("phase", phase)
                        .put("status", status)
                        .put("message", message)
                        .put("path", path ?: JSONObject.NULL)
                        .put("timestamp", System.currentTimeMillis()))
                }
            )
            val graph = if (result.graphFile?.isFile == true) {
                graphStore.compactSummary(packageName, 30)
            } else {
                JSONObject()
                    .put("appPackage", packageName)
                    .put("status", result.report.optString("status", "FAILED_UI_GRAPH"))
                    .put("screenCount", result.report.optInt("screenCount", 0))
                    .put("edgeCount", result.report.optInt("edgeCount", 0))
                    .put("quality", result.report.optJSONObject("quality") ?: JSONObject())
            }
            val report = JSONObject()
                .put("schemaVersion", 1)
                .put("kind", "popular_app_ui_task_report")
                .put("id", "popular_app_ui_task_${task.taskId}_${System.currentTimeMillis()}")
                .put("appPackage", packageName)
                .put("appName", app.profile.appName)
                .put("task", task.toJson())
                .put("attempt", attempt)
                .put("status", if (result.success) "SUCCESS_UI_GRAPH_EXPLORED" else result.report.optString("status", "PARTIAL_UI_GRAPH"))
                .put("message", result.report.optString("message").ifBlank { "UI graph task finished" })
                .put("graphFile", result.graphFile?.absolutePath ?: JSONObject.NULL)
                .put("graphId", result.report.optString("graphId"))
                .put("exploreReport", result.report)
                .put("summaryGraph", graph)
                .put("events", events)
                .put("durationMs", System.currentTimeMillis() - startedAt)
                .put("createdAt", System.currentTimeMillis())
            val reportFile = store.saveFlowReport(packageName, report)
            taskReport(
                task = task,
                status = if (result.success) "SUCCESS_UI_GRAPH_EXPLORED" else result.report.optString("status", "FAILED_UI_GRAPH"),
                message = "UI 图谱读取完成：status=${result.report.optString("status")} screens=${graph.optInt("screenCount")} edges=${graph.optInt("edgeCount")} report=${reportFile.name}",
                flowReport = report.put("reportFile", reportFile.absolutePath),
                events = events,
                retried = attempt > 1
            )
                .put("attempt", attempt)
                .put("durationMs", System.currentTimeMillis() - startedAt)
                .put("flowReportFile", reportFile.absolutePath)
                .put("outputs", JSONObject()
                    .put("graphFile", result.graphFile?.absolutePath ?: JSONObject.NULL)
                    .put("graphId", result.report.optString("graphId"))
                    .put("screenCount", graph.optInt("screenCount"))
                    .put("edgeCount", graph.optInt("edgeCount"))
                    .put("exploreStatus", result.report.optString("status"))
                    .put("timedOut", result.report.optBoolean("timedOut"))
                    .put("quality", graph.optJSONObject("quality") ?: JSONObject()))
        }.getOrElse { error ->
            taskReport(
                task = task,
                status = "FAILED_UI_GRAPH_EXCEPTION",
                message = error.message ?: error.javaClass.simpleName,
                flowReport = JSONObject()
                    .put("errorClass", error.javaClass.name)
                    .put("stackTrace", Log.getStackTraceString(error).take(12_000)),
                events = events,
                retried = attempt > 1
            )
                .put("attempt", attempt)
                .put("durationMs", System.currentTimeMillis() - startedAt)
        }
    }

    private fun isPinduoduoPackage(packageName: String?): Boolean {
        return packageName.orEmpty().contains("pinduoduo", ignoreCase = true) ||
            packageName == "com.xunmeng.pinduoduo"
    }

    private fun runDialogProbeTask(
        app: ResolvedPopularAppProfile,
        task: PopularAppTask,
        attempt: Int
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        val packageName = app.resolvedPackage ?: app.profile.packageName
        val actions = DeviceActions(applicationContext)
        val inspector = NodeInspector(applicationContext)
        val profiler = CapabilityProfiler()
        val snapshotStore = UiStateSnapshotStore(applicationContext)
        val reactive = ReactiveUiRuleEngine(inspector, actions)
        val events = JSONArray()
        return runCatching {
            actions.openAppLauncherRoot(packageName)
            sleepQuietly(900L)
            val before = inspector.currentState()
            val beforeProfile = profiler.profile(before, MacroPilotAccessibilityService.instance != null)
            val beforeSnapshot = before?.let {
                snapshotStore.save(
                    state = it,
                    profile = beforeProfile,
                    source = "popular_app_dialog_probe",
                    note = task.taskId,
                    forceScreenshot = true
                ).absolutePath
            }
            val beforeSummary = dialogStateSummary(before)
            val dismiss = reactive.dismissBlockingOverlays(
                appPackage = packageName,
                reason = "popular_app_dialog_probe:${task.taskId}",
                maxPasses = 2
            )
            events.put(JSONObject()
                .put("phase", "dismiss_overlays")
                .put("dismissed", dismiss.dismissedCount)
                .put("events", dismiss.events))
            sleepQuietly(500L)
            val after = inspector.currentState()
            val afterProfile = profiler.profile(after, MacroPilotAccessibilityService.instance != null)
            val afterSnapshot = after?.let {
                snapshotStore.save(
                    state = it,
                    profile = afterProfile,
                    source = "popular_app_dialog_probe_after",
                    note = task.taskId,
                    forceScreenshot = true
                ).absolutePath
            }
            val afterSummary = dialogStateSummary(after)
            val report = JSONObject()
                .put("schemaVersion", 1)
                .put("kind", "popular_app_dialog_probe_report")
                .put("id", "popular_app_dialog_probe_${task.taskId}_${System.currentTimeMillis()}")
                .put("appPackage", packageName)
                .put("appName", app.profile.appName)
                .put("task", task.toJson())
                .put("attempt", attempt)
                .put("status", "SUCCESS_DIALOG_PROBED")
                .put("message", "轻量弹窗/小字探测完成；不会进入长流程。")
                .put("beforeSnapshot", beforeSnapshot ?: JSONObject.NULL)
                .put("afterSnapshot", afterSnapshot ?: JSONObject.NULL)
                .put("before", beforeSummary)
                .put("after", afterSummary)
                .put("dismissedCount", dismiss.dismissedCount)
                .put("events", events)
                .put("durationMs", System.currentTimeMillis() - startedAt)
                .put("createdAt", System.currentTimeMillis())
            val file = FactorySkillJsonStore(applicationContext).apply { ensureCreated() }
                .saveFlowReport(packageName, report)
            taskReport(
                task = task,
                status = "SUCCESS_DIALOG_PROBED",
                message = "弹窗/小字探测完成：dismissed=${dismiss.dismissedCount} report=${file.name}",
                flowReport = report.put("reportFile", file.absolutePath),
                events = events,
                retried = attempt > 1
            )
                .put("attempt", attempt)
                .put("durationMs", System.currentTimeMillis() - startedAt)
                .put("flowReportFile", file.absolutePath)
                .put("outputs", JSONObject()
                    .put("beforeSnapshot", beforeSnapshot ?: JSONObject.NULL)
                    .put("afterSnapshot", afterSnapshot ?: JSONObject.NULL)
                    .put("dismissedCount", dismiss.dismissedCount)
                    .put("blockers", afterSummary.optJSONArray("blockers") ?: JSONArray()))
        }.getOrElse { error ->
            taskReport(
                task = task,
                status = "FAILED_DIALOG_PROBE_EXCEPTION",
                message = error.message ?: error.javaClass.simpleName,
                flowReport = JSONObject()
                    .put("errorClass", error.javaClass.name)
                    .put("stackTrace", Log.getStackTraceString(error).take(12_000)),
                events = events,
                retried = attempt > 1
            )
                .put("attempt", attempt)
                .put("durationMs", System.currentTimeMillis() - startedAt)
        }
    }

    private fun runGuardOnlyTask(
        app: ResolvedPopularAppProfile,
        task: PopularAppTask,
        attempt: Int
    ): JSONObject {
        val packageName = app.resolvedPackage ?: app.profile.packageName
        val state = NodeInspector(applicationContext).currentState()
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "popular_app_high_risk_guard_report")
            .put("id", "popular_app_guard_${task.taskId}_${System.currentTimeMillis()}")
            .put("appPackage", packageName)
            .put("appName", app.profile.appName)
            .put("task", task.toJson())
            .put("attempt", attempt)
            .put("status", "BLOCKED_HIGH_RISK_GUARD_ONLY")
            .put("message", "支付、金融、交易、密码类任务只生成安全 guard 报告，不执行输入密码、支付、下单、交易。")
            .put("currentPackage", state?.appPackage ?: JSONObject.NULL)
            .put("nodeCount", state?.nodes?.size ?: 0)
            .put("clickableCount", state?.nodes?.count { it.clickable } ?: 0)
            .put("editableCount", state?.nodes?.count { it.editable } ?: 0)
            .put("createdAt", System.currentTimeMillis())
        val file = FactorySkillJsonStore(applicationContext).apply { ensureCreated() }
            .saveFlowReport(packageName, report)
        return taskReport(
            task = task,
            status = "BLOCKED_HIGH_RISK_GUARD_ONLY",
            message = "高风险任务已按安全策略停止：${file.name}",
            flowReport = report.put("reportFile", file.absolutePath),
            events = JSONArray(),
            retried = attempt > 1
        )
            .put("attempt", attempt)
            .put("flowReportFile", file.absolutePath)
            .put("outputs", JSONObject().put("guardReportFile", file.absolutePath))
    }

    private fun normalizeFlowStatus(flow: JSONObject): String {
        val raw = flow.optString("status", "UNKNOWN")
        return when {
            raw.startsWith("SUCCESS") -> "SUCCESS"
            raw.startsWith("FAILED_DEVICE") -> "BLOCKED_DEVICE_NOT_READY"
            raw.startsWith("FAILED_PRECONDITION") -> "BLOCKED_PRECONDITION"
            raw.startsWith("FAILED") -> raw
            raw.isBlank() -> "FAILED_UNKNOWN"
            else -> raw
        }
    }

    private fun dialogStateSummary(state: com.macropilot.app.model.UiStateSample?): JSONObject {
        if (state == null) {
            return JSONObject()
                .put("available", false)
                .put("reason", "no_ui_state")
        }
        val labels = state.nodes.mapNotNull { node ->
            listOfNotNull(node.text, node.contentDesc, node.resourceId)
                .joinToString(" ")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .takeIf { it.isNotBlank() }
        }
        val smallTextNodes = state.nodes
            .filter { node ->
                val label = listOfNotNull(node.text, node.contentDesc)
                    .joinToString(" ")
                    .trim()
                label.isNotBlank() &&
                    label.length <= 80 &&
                    node.bounds.width() in 1..(state.screenWidth * 0.92f).toInt() &&
                    node.bounds.height() in 1..(state.screenHeight * 0.18f).toInt() &&
                    !node.editable
            }
            .map { node ->
                JSONObject()
                    .put("text", listOfNotNull(node.text, node.contentDesc).joinToString(" ").trim())
                    .put("resourceId", node.resourceId ?: JSONObject.NULL)
                    .put("class", node.className ?: JSONObject.NULL)
                    .put("clickable", node.clickable)
                    .put("bounds", node.bounds.flattenToString())
            }
            .take(30)
        val clickCandidates = state.nodes
            .filter { node -> node.clickable && node.enabled && node.bounds.width() > 0 && node.bounds.height() > 0 }
            .map { node ->
                JSONObject()
                    .put("label", listOfNotNull(node.text, node.contentDesc, node.resourceId).joinToString(" ").trim())
                    .put("resourceId", node.resourceId ?: JSONObject.NULL)
                    .put("class", node.className ?: JSONObject.NULL)
                    .put("bounds", node.bounds.flattenToString())
            }
            .take(30)
        return JSONObject()
            .put("available", true)
            .put("appPackage", state.appPackage ?: JSONObject.NULL)
            .put("windowClassName", state.windowClassName ?: JSONObject.NULL)
            .put("nodeCount", state.nodes.size)
            .put("clickableCount", state.nodes.count { it.clickable })
            .put("editableCount", state.nodes.count { it.editable })
            .put("labels", JSONArray(labels.take(40)))
            .put("smallTextNodes", JSONArray(smallTextNodes))
            .put("clickCandidates", JSONArray(clickCandidates))
            .put("events", JSONArray(state.eventsSinceLastState.take(20).map { event ->
                JSONObject()
                    .put("type", event.eventType)
                    .put("text", event.text ?: JSONObject.NULL)
                    .put("class", event.className ?: JSONObject.NULL)
                    .put("package", event.packageName ?: JSONObject.NULL)
            }))
            .put("blockers", ReactiveUiRuleEngine(NodeInspector(applicationContext), DeviceActions(applicationContext))
                .enhancedBlockingDialogSummary(state))
    }

    private fun lastStepMessage(steps: JSONArray): String? {
        if (steps.length() <= 0) return null
        val last = steps.optJSONObject(steps.length() - 1) ?: return null
        return "${last.optString("title")} / ${last.optString("status")}: ${last.optString("message")}"
    }

    private fun shouldRetry(result: JSONObject): Boolean {
        val status = result.optString("status")
        if (status.startsWith("SUCCESS") || status.startsWith("BLOCKED")) return false
        if (status.contains("PARTIAL_TIMEOUT") || status == "PARTIAL_UI_GRAPH") return false
        val capturedScreens = result.optJSONObject("outputs")?.optInt("screenCount", 0) ?: 0
        if (status == "FAILED_LOW_SIGNAL_UI_GRAPH" && capturedScreens > 0) return false
        return true
    }

    private fun buildRetryInstruction(task: PopularAppTask, result: JSONObject): String {
        val flow = result.optJSONObject("flowReport")
        val lastMessage = result.optString("message")
        val reportFile = result.optString("flowReportFile")
        return buildString {
            append(task.instruction)
            append("。这是失败后的第二次自调试重试：先读取当前 UI 和已有 AppUI 图谱，判断卡住位置；")
            append("如果目标入口不在当前页，回到该 App 主页面，按底部导航、顶部小图标、图形化入口、子页面入口的顺序查找；")
            append("不要重复同一个错误点击。上次失败状态=")
            append(result.optString("status"))
            append("；失败信息=")
            append(lastMessage.take(160))
            if (reportFile.isNotBlank()) {
                append("；上次报告=")
                append(reportFile)
            }
            val steps = flow?.optJSONArray("steps")
            if (steps != null && steps.length() > 0) {
                append("；请参考上次最后一步=")
                append(lastStepMessage(steps).orEmpty().take(180))
            }
        }
    }

    private fun guardedInstruction(app: ResolvedPopularAppProfile, task: PopularAppTask, attempt: Int): String {
        val safety = buildString {
            append("目标 App=${app.profile.appName}，包名=${app.resolvedPackage ?: app.profile.packageName}。")
            append("必须由 MacroPilot 手机端自己用无障碍、输入法、AI/Skill JSON 执行，不允许依赖 ADB 手点目标 App。")
            append("执行前先读取当前 UI；如果遇到广告、弹窗、小字提示，先记录文字并安全关闭。")
            append("任务完成必须用当前 UI 证据验证，不能只因为步骤执行完就报成功。")
            append("如果没有现成 skill，先复用已有相似 Skill JSON/archetype，只改关键槽位如入口文字、坐标、目标文本、验证规则，再组装 atomic/macro skill。")
            append("需要探索入口时优先遍历底部标签、顶部小图标、图形化入口、侧边操作和子页面。")
            if (task.stopBeforeSubmit) {
                append("本任务 stopBeforeSubmit=true：写草稿、评论、订单、支付类流程必须停在提交前，不点击最终发布、发送、提交订单、支付、确认授权。")
            }
            if (task.requiresPayment || task.riskLevel == "HIGH") {
                append("支付/金融/交易页面只能读取 UI，绝不输入密码、绝不确认支付、绝不交易。")
            }
            if (attempt > 1) {
                append("这是第 $attempt 次尝试，必须先复盘上一轮失败，再换路径。")
            }
            if (task.expectedEvidence.isNotEmpty()) {
                append("期望证据包括：")
                append(task.expectedEvidence.joinToString("、"))
                append("。")
            }
        }
        return "${task.instruction}\n\n约束：$safety"
    }

    private fun taskReport(
        task: PopularAppTask,
        status: String,
        message: String,
        flowReport: JSONObject?,
        events: JSONArray?,
        retried: Boolean
    ): JSONObject {
        return task.toJson()
            .put("status", status)
            .put("message", message)
            .put("retried", retried)
            .put("flowReport", flowReport ?: JSONObject.NULL)
            .put("outcome", taskOutcomeSummary(status, message, flowReport))
            .put("events", events ?: JSONArray())
            .put("createdAt", System.currentTimeMillis())
    }

    private fun taskOutcomeSummary(status: String, message: String, flowReport: JSONObject?): JSONObject {
        val finalOutcome = flowReport?.optJSONObject("finalOutcome")
        val goalCheck = finalOutcome?.optJSONObject("goalCheck")
        val lastStep = finalOutcome?.optJSONObject("lastStep")
        val readiness = flowReport?.optJSONObject("readiness")
        val blockerType = goalCheck?.optString("blockerType").orEmpty().ifBlank {
            readiness?.optString("status").orEmpty().ifBlank { "NONE" }
        }
        val nextActionHint = goalCheck?.optString("nextActionHint").orEmpty().ifBlank {
            readiness?.optString("message").orEmpty()
        }
        return JSONObject()
            .put("status", status)
            .put("summary", message)
            .put("blockerType", blockerType)
            .put("nextActionHint", nextActionHint)
            .put("goalCheckPassed", goalCheck?.optBoolean("passed") ?: JSONObject.NULL)
            .put("goalCheckMandatory", goalCheck?.optBoolean("mandatory") ?: JSONObject.NULL)
            .put("lastStep", lastStep ?: JSONObject.NULL)
            .put("hasFinalOutcome", finalOutcome != null)
            .put("hasReadiness", readiness != null)
            .put("flowReportKind", flowReport?.optString("kind").orEmpty())
    }

    private fun attachTaskEvidenceScreenshot(
        requestId: String,
        appIndex: Int,
        taskIndex: Int,
        app: ResolvedPopularAppProfile,
        task: PopularAppTask,
        report: JSONObject
    ) {
        val evidenceId = "$appIndex-$taskIndex"
        val status = report.optString("status", "UNKNOWN").safeEvidenceFilePart()
        val packageName = (app.resolvedPackage ?: app.profile.packageName).safeEvidenceFilePart()
        val taskId = task.taskId.safeEvidenceFilePart()
        val evidenceDir = File(
            filesDir,
            "macro_v2/factory/task_evidence/${requestId.safeEvidenceFilePart()}"
        )
        val file = File(evidenceDir, "${evidenceId}_${status}_${packageName}_${taskId}.png")
        val screenshotOk = MacroPilotAccessibilityService.instance
            ?.takeScreenshotPngBlocking(file, timeoutMs = 1_800L) == true
        report
            .put("evidenceId", evidenceId)
            .put("appIndex", appIndex)
            .put("taskIndex", taskIndex)
            .put("evidenceScreenshotOk", screenshotOk)
            .put("evidenceScreenshotPath", if (screenshotOk) file.absolutePath else JSONObject.NULL)
            .put("evidenceScreenshotName", file.name)
    }

    private fun deviceNotReadyTaskReport(
        task: PopularAppTask,
        readiness: JSONObject,
        attempt: Int,
        startedAt: Long,
        retried: Boolean,
        events: JSONArray = JSONArray()
    ): JSONObject {
        return taskReport(
            task = task,
            status = readiness.optString("status", "FAILED_DEVICE_NOT_READY"),
            message = readiness.optString("message", "手机端能力掉线，不能继续执行目标 App 任务。"),
            flowReport = JSONObject()
                .put("readiness", readiness)
                .put("blocker", readiness.optString("message"))
                .put("serviceConnected", readiness.optBoolean("serviceConnected"))
                .put("macroPilotImeSelected", readiness.optBoolean("macroPilotImeSelected"))
                .put("macroPilotImeReady", readiness.optBoolean("macroPilotImeReady"))
                .put("inputCapability", readiness.optString("inputCapability")),
            events = events,
            retried = retried
        )
            .put("attempt", attempt)
            .put("durationMs", System.currentTimeMillis() - startedAt)
    }

    private fun appSkipped(app: ResolvedPopularAppProfile, tasksPerApp: Int, status: String): JSONObject {
        return app.toJson(tasksPerApp)
            .put("tasks", JSONArray(app.tasks.map { task ->
                taskReport(task, status, "批次在进入该 App 前停止。", null, null, false)
            }))
    }

    private fun readinessJson(): JSONObject {
        val actions = DeviceActions(applicationContext)
        val state = NodeInspector(applicationContext).currentState()
        val profile = CapabilityProfiler().profile(state, MacroPilotAccessibilityService.instance != null)
        val serviceConnected = MacroPilotAccessibilityService.instance != null
        val interactive = actions.isInteractive()
        val keyguardLocked = actions.isKeyguardLocked()
        val imeSelected = actions.isMacroPilotImeSelected()
        val imeReady = actions.isMacroPilotImeReady()
        val inputReady = profile.inputCapability.name != "NONE" || imeReady
        val ready = serviceConnected && interactive && !keyguardLocked
        return JSONObject()
            .put("ready", ready)
            .put("status", if (ready) "READY" else "FAILED_DEVICE_NOT_READY")
            .put("message", when {
                !serviceConnected -> "无障碍服务未连接，手机端不能自己读取和点击 App UI。"
                keyguardLocked -> "手机仍在锁屏，不能执行目标 App 任务。"
                !interactive -> "屏幕不可交互，不能执行目标 App 任务。"
                !inputReady -> "输入法/输入通道不可用，手机端不能完成搜索、评论、发帖等输入任务。"
                else -> "手机端可执行。"
            })
            .put("serviceConnected", serviceConnected)
            .put("interactive", interactive)
            .put("keyguardLocked", keyguardLocked)
            .put("macroPilotImeSelected", imeSelected)
            .put("macroPilotImeReady", imeReady)
            .put("inputReady", inputReady)
            .put("inputCheckPolicy", "defer_input_channel_check_until_input_atom")
            .put("currentPackage", state?.appPackage ?: JSONObject.NULL)
            .put("nodeCount", state?.nodes?.size ?: 0)
            .put("clickableCount", state?.nodes?.count { it.clickable } ?: 0)
            .put("editableCount", state?.nodes?.count { it.editable } ?: 0)
            .put("nodeReadability", profile.nodeReadability.name)
            .put("inputCapability", profile.inputCapability.name)
            .put("verificationCapability", profile.verificationCapability.name)
    }

    private fun saveState(
        requestId: String,
        packageFilter: List<String>,
        status: String,
        message: String,
        payload: JSONObject?,
        progress: JSONObject?
    ) {
        val state = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "popular_app_task_batch_service_state")
            .put("id", requestId)
            .put("requestId", requestId)
            .put("status", status)
            .put("message", message)
            .put("requestedPackages", JSONArray(packageFilter))
            .put("progress", progress ?: JSONObject.NULL)
            .put("payload", payload ?: JSONObject.NULL)
            .put("createdAt", System.currentTimeMillis())
        FactorySkillJsonStore(applicationContext).apply { ensureCreated() }
            .saveFlowReport("__popular_app_tasks__", state)
    }

    private fun startForegroundIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "MacroPilot 20 App 任务批次",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MacroPilot 正在执行 20 App 手机端任务")
            .setContentText("手机端按任务目录自主读取 UI、组装 Skill JSON、执行和复盘")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_PACKAGE_NAMES = "packageNames"
        const val EXTRA_LIMIT_APPS = "limitApps"
        const val EXTRA_TASKS_PER_APP = "tasksPerApp"
        const val EXTRA_MAX_TASK_MS = "maxTaskMs"
        const val EXTRA_RETRY_FAILED = "retryFailed"
        const val EXTRA_CATALOG_ONLY = "catalogOnly"
        const val EXTRA_TASK_IDS = "taskIds"
        const val EXTRA_CANCEL = "cancel"
        private const val CHANNEL_ID = "macropilot_popular_app_task_batch"
        private const val NOTIFICATION_ID = 260514
        private const val TAG = "MacroPilotPopularTasks"
        private val running = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)

        fun requestCancel() {
            cancelled.set(true)
        }

        fun start(
            context: Context,
            requestId: String,
            packageFilter: List<String>,
            limitApps: Int,
            tasksPerApp: Int,
            maxTaskMs: Long,
            retryFailed: Boolean,
            catalogOnly: Boolean,
            taskIds: List<String> = emptyList()
        ) {
            val intent = Intent(context, PopularAppTaskBatchService::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putStringArrayListExtra(EXTRA_PACKAGE_NAMES, ArrayList(packageFilter))
                .putStringArrayListExtra(EXTRA_TASK_IDS, ArrayList(taskIds))
                .putExtra(EXTRA_LIMIT_APPS, limitApps)
                .putExtra(EXTRA_TASKS_PER_APP, tasksPerApp)
                .putExtra(EXTRA_MAX_TASK_MS, maxTaskMs)
                .putExtra(EXTRA_RETRY_FAILED, retryFailed)
                .putExtra(EXTRA_CATALOG_ONLY, catalogOnly)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context, requestId: String = "popular_app_task_cancel_${System.currentTimeMillis()}") {
            requestCancel()
            val intent = Intent(context, PopularAppTaskBatchService::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_CANCEL, true)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                Log.w(TAG, "Cancel flag set, but service start for cancel failed: ${error.message}")
            }
        }
    }
}

private fun parsePackages(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return raw
        .split(",", "\n", ";", "|")
        .map { it.trim() }
        .filter { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) }
        .distinct()
}

private fun PopularAppTask.copyForRetry(instruction: String): PopularAppTask {
    return copy(
        taskId = "${taskId}_retry",
        instruction = instruction
    )
}

private fun PopularAppTask.shouldUseUiExplorer(): Boolean {
    return category in setOf(
        "bottom_tab_probe",
        "graphical_entry_probe",
        "child_page_probe",
        "top_icon_probe",
        "profile_probe",
        "settings_probe",
        "message_probe",
        "detail_probe",
        "feed_probe",
        "ad_dialog_probe",
        "video_action_probe",
        "shopping_probe",
        "promotion_probe",
        "location_probe",
        "transit_probe",
        "login_probe",
        "blocker_report",
        "chat_probe",
        "comment_probe",
        "order_probe",
        "cart_probe",
        "finance_probe"
    )
}

private fun PopularAppTask.isHighRiskGuardOnly(): Boolean {
    return requiresPayment ||
        riskLevel == "HIGH" ||
        category in setOf("safe_payment_probe", "finance_probe")
}

private fun String.safeEvidenceFilePart(): String {
    return trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .take(96)
        .ifBlank { "unknown" }
}
