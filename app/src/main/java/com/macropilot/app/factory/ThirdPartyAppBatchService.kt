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
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.store.FactorySkillJsonStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ThirdPartyAppBatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded()
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
            ?: "third_party_batch_${System.currentTimeMillis()}"
        val packages = intent?.getStringArrayListExtra(EXTRA_PACKAGE_NAMES).orEmpty()
        if (intent?.getBooleanExtra(EXTRA_CANCEL, false) == true) {
            cancelled.set(true)
            saveState(
                requestId = requestId,
                packages = packages,
                status = "CANCEL_REQUESTED",
                message = "用户已请求停止当前批量 UI 探测任务",
                payload = null,
                progress = JSONObject().put("phase", "CANCEL_REQUESTED")
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val limit = intent?.getIntExtra(EXTRA_LIMIT, packages.size.takeIf { it > 0 } ?: 20) ?: 20
        val probeLimit = intent?.getIntExtra(EXTRA_PROBE_LIMIT, packages.size.takeIf { it > 0 } ?: 20) ?: 20
        val clearExisting = intent?.getBooleanExtra(EXTRA_CLEAR_EXISTING, false) ?: false
        if (!running.compareAndSet(false, true)) {
            saveState(requestId, packages, "FAILED_BUSY", "已有一个第三方 App 批量任务正在运行。", null, null)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        cancelled.set(false)
        Thread({
            try {
                val latestProgress = AtomicReference<JSONObject?>(null)
                saveState(requestId, packages, "RUNNING", "正在批量探索第三方 App，并生成 Skill JSON 子集。", null, null)
                MacroPilotAccessibilityService.instance?.showStatusOverlay(
                    "20 App 批量自训练",
                    "RUNNING",
                    "手机端正在打开 App、读 UI、写 Skill JSON"
                )
                val report = ThirdPartyAppSkillSubsetBatcher(applicationContext).generate(
                    limit = limit,
                    launchableOnly = true,
                    clearExisting = clearExisting,
                    runDryRun = true,
                    exportPreviewConfig = true,
                    probeUi = true,
                    probeLimit = probeLimit,
                    packageFilter = packages,
                    shouldStop = { cancelled.get() },
                    onProgress = { progress ->
                        latestProgress.set(progress)
                        saveState(
                            requestId,
                            packages,
                            "RUNNING",
                            progressMessage(progress),
                            null,
                            progress
                        )
                    }
                )
                saveState(
                    requestId = requestId,
                    packages = packages,
                    status = report.optJSONObject("summary")?.let { summary ->
                        if (summary.optInt("uiProbeSucceeded") >= packages.size.coerceAtMost(probeLimit)) "SUCCESS" else "PARTIAL"
                    } ?: "UNKNOWN",
                    message = "批量任务完成：${report.optJSONObject("summary")?.toString() ?: report.optString("status")}",
                    payload = report,
                    progress = latestProgress.get()
                )
                Log.i(TAG, "THIRD_PARTY_BATCH_FINISHED requestId=$requestId")
            } catch (error: Throwable) {
                Log.e(TAG, "THIRD_PARTY_BATCH_FAILED requestId=$requestId", error)
                saveState(
                    requestId = requestId,
                    packages = packages,
                    status = "FAILED_EXCEPTION",
                    message = error.message ?: error.javaClass.simpleName,
                    payload = JSONObject()
                        .put("errorClass", error.javaClass.name)
                        .put("stackTrace", Log.getStackTraceString(error).take(12_000)),
                    progress = null
                )
            } finally {
                cancelled.set(false)
                running.set(false)
                stopSelf(startId)
            }
        }, "macropilot-third-party-batch-$requestId").start()
        return START_NOT_STICKY
    }

    private fun saveState(
        requestId: String,
        packages: List<String>,
        status: String,
        message: String,
        payload: JSONObject?,
        progress: JSONObject?
    ) {
        val state = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "third_party_app_batch_service_state")
            .put("id", requestId)
            .put("requestId", requestId)
            .put("status", status)
            .put("message", message)
            .put("requestedPackages", JSONArray(packages))
            .put("progress", progress ?: JSONObject.NULL)
            .put("payload", payload ?: JSONObject.NULL)
            .put("createdAt", System.currentTimeMillis())
        FactorySkillJsonStore(applicationContext).apply { ensureCreated() }
            .saveFlowReport("__third_party_apps__", state)
    }

    private fun progressMessage(progress: JSONObject): String {
        val index = progress.optInt("index")
        val total = progress.optInt("total")
        val label = progress.optString("label")
        val pkg = progress.optString("packageName")
        val phase = progress.optString("phase")
        val probe = progress.optString("uiProbeStatus")
        if (phase == "UI_PROBE_EVENT") {
            val uiPhase = progress.optString("uiPhase")
            val uiStatus = progress.optString("uiStatus")
            val elapsed = progress.optLong("elapsedMs")
            return "正在深探 $label($pkg)：$uiPhase/$uiStatus，${progress.optString("uiMessage")}，elapsed=${elapsed}ms"
        }
        return if (phase == "APP_DONE") {
            "[$index/$total] $label($pkg) 完成：$probe，screens=${progress.optInt("screenCount")}，dryRun=${progress.optInt("dryRunPassed")}/${progress.optInt("generatedSkillCount")}"
        } else {
            "[$index/$total] 正在测试 $label($pkg)：生成 Skill JSON、dry-run、读取 AppUI"
        }
    }

    private fun startForegroundIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "MacroPilot 批量自训练",
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
            .setContentTitle("MacroPilot 正在批量测试 App")
            .setContentText("正在读取 App UI、生成 Skill JSON、dry-run")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_PACKAGE_NAMES = "packageNames"
        const val EXTRA_LIMIT = "limit"
        const val EXTRA_PROBE_LIMIT = "probeLimit"
        const val EXTRA_CLEAR_EXISTING = "clearExisting"
        const val EXTRA_CANCEL = "cancel"
        private const val CHANNEL_ID = "macropilot_third_party_batch"
        private const val NOTIFICATION_ID = 260513
        private const val TAG = "MacroPilotBatchSvc"
        private val running = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)

        fun start(
            context: Context,
            requestId: String,
            packageFilter: List<String>,
            limit: Int,
            probeLimit: Int,
            clearExisting: Boolean
        ) {
            val intent = Intent(context, ThirdPartyAppBatchService::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putStringArrayListExtra(EXTRA_PACKAGE_NAMES, ArrayList(packageFilter))
                .putExtra(EXTRA_LIMIT, limit)
                .putExtra(EXTRA_PROBE_LIMIT, probeLimit)
                .putExtra(EXTRA_CLEAR_EXISTING, clearExisting)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context, requestId: String = "third_party_batch_cancel_${System.currentTimeMillis()}") {
            val intent = Intent(context, ThirdPartyAppBatchService::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_CANCEL, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
