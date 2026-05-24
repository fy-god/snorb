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
import com.macropilot.app.runtime.CapabilityProfiler
import com.macropilot.app.runtime.NodeInspector
import com.macropilot.app.store.AppUiGraphStore
import com.macropilot.app.store.FactorySkillJsonStore
import com.macropilot.app.store.UiStateSnapshotStore
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class AppUiProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded()
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
            ?: "app_ui_probe_${System.currentTimeMillis()}"
        val packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val appName = intent?.getStringExtra(EXTRA_APP_NAME).orEmpty().ifBlank { packageName }
        if (packageName.isBlank()) {
            saveState(requestId, packageName, appName, "FAILED", "packageName is required", null)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val maxScreens = intent?.getIntExtra(EXTRA_MAX_SCREENS, 80)?.coerceIn(1, 160) ?: 80
        val maxClicks = intent?.getIntExtra(EXTRA_MAX_CLICKS, 80)?.coerceIn(0, 220) ?: 80
        val maxDepth = intent?.getIntExtra(EXTRA_MAX_DEPTH, 3)?.coerceIn(0, 8) ?: 3
        val maxSwipeActions = intent?.getIntExtra(EXTRA_MAX_SWIPE_ACTIONS, 20)?.coerceIn(0, 220) ?: 20
        val timeoutMs = intent?.getLongExtra(EXTRA_TIMEOUT_MS, 120_000L)?.coerceIn(5_000L, 600_000L) ?: 120_000L
        val note = intent?.getStringExtra(EXTRA_NOTE).orEmpty().ifBlank { "api_probe_app_ui_graph_service" }
        val primaryNavigationOnly = intent?.getBooleanExtra(EXTRA_PRIMARY_NAV_ONLY, false) ?: false

        val request = ProbeRequest(
            requestId = requestId,
            packageName = packageName,
            appName = appName,
            note = note,
            maxScreens = maxScreens,
            maxClicks = maxClicks,
            maxDepth = maxDepth,
            maxSwipeActions = maxSwipeActions,
            timeoutMs = timeoutMs,
            primaryNavigationOnly = primaryNavigationOnly
        )
        queue.offer(request)
        saveState(
            requestId = requestId,
            packageName = packageName,
            appName = appName,
            status = if (running.get()) "QUEUED" else "QUEUED_STARTING",
            message = "App UI probe queued for phone-side serial execution",
            payload = JSONObject()
                .put("maxScreens", maxScreens)
                .put("maxClicks", maxClicks)
                .put("maxDepth", maxDepth)
                .put("maxSwipeActions", maxSwipeActions)
                .put("timeoutMs", timeoutMs)
                .put("queueSize", queue.size)
        )

        startWorkerIfNeeded()

        return START_NOT_STICKY
    }

    private fun startWorkerIfNeeded() {
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                while (true) {
                    val request = queue.poll()
                    if (request == null) {
                        running.set(false)
                        if (queue.isEmpty() || !running.compareAndSet(false, true)) break
                        continue
                    }
                    runProbeRequest(request)
                }
            } finally {
                running.set(false)
                stopSelf()
            }
        }, "macropilot-app-ui-probe-worker").start()
    }

    private fun runProbeRequest(request: ProbeRequest) {
        saveState(
            requestId = request.requestId,
            packageName = request.packageName,
            appName = request.appName,
            status = "RUNNING",
            message = "App UI probe started from serial queue",
            payload = JSONObject()
                .put("maxScreens", request.maxScreens)
                .put("maxClicks", request.maxClicks)
                .put("maxDepth", request.maxDepth)
                .put("maxSwipeActions", request.maxSwipeActions)
                .put("timeoutMs", request.timeoutMs)
                .put("queueRemaining", queue.size)
        )
        try {
            val actions = DeviceActions(applicationContext)
            val inspector = NodeInspector(applicationContext)
            val graphStore = AppUiGraphStore(applicationContext)
            val explorer = AppUiExplorer(
                context = applicationContext,
                actions = actions,
                inspector = inspector,
                profiler = CapabilityProfiler(),
                snapshotStore = UiStateSnapshotStore(applicationContext),
                graphStore = graphStore,
                reactiveRules = ReactiveUiRuleEngine(inspector, actions)
            )
            val result = explorer.explore(
                packageName = request.packageName,
                appName = request.appName,
                reason = request.note,
                maxScreens = request.maxScreens,
                maxClicks = request.maxClicks,
                maxDepth = request.maxDepth,
                maxSwipeActions = request.maxSwipeActions,
                maxDurationMs = request.timeoutMs,
                forceScreenshots = true,
                primaryNavigationOnly = request.primaryNavigationOnly
            )
            val store = FactorySkillJsonStore(applicationContext).apply { ensureCreated() }
            val reportFile = store.saveFlowReport(request.packageName, result.report)
            saveState(
                requestId = request.requestId,
                packageName = request.packageName,
                appName = request.appName,
                status = result.report.optString("status", if (result.success) "SUCCESS" else "FAILED"),
                message = "App UI probe finished",
                payload = JSONObject()
                    .put("success", result.success)
                    .put("reportFile", reportFile.absolutePath)
                    .put("graphFile", result.graphFile?.absolutePath ?: JSONObject.NULL)
                    .put("screenCount", result.report.optInt("screenCount"))
                    .put("edgeCount", result.report.optInt("edgeCount"))
                    .put("quality", result.report.optJSONObject("quality") ?: JSONObject())
                    .put("queueRemaining", queue.size)
            )
        } catch (error: Throwable) {
            Log.e(TAG, "APP_UI_PROBE_FAILED requestId=${request.requestId} package=${request.packageName}", error)
            saveState(
                requestId = request.requestId,
                packageName = request.packageName,
                appName = request.appName,
                status = "FAILED_EXCEPTION",
                message = error.message ?: error.javaClass.simpleName,
                payload = JSONObject()
                    .put("errorClass", error.javaClass.name)
                    .put("stackTrace", Log.getStackTraceString(error).take(12_000))
                    .put("queueRemaining", queue.size)
            )
        }
    }

    private fun saveState(
        requestId: String,
        packageName: String,
        appName: String,
        status: String,
        message: String,
        payload: JSONObject?
    ) {
        val state = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "app_ui_probe_service_state")
            .put("id", requestId)
            .put("requestId", requestId)
            .put("status", status)
            .put("message", message)
            .put("appPackage", packageName)
            .put("appName", appName)
            .put("payload", payload ?: JSONObject.NULL)
            .put("updatedAt", System.currentTimeMillis())
        FactorySkillJsonStore(applicationContext).apply { ensureCreated() }
            .saveFlowReport(packageName.ifBlank { "__app_ui_probe__" }, state)
    }

    private fun startForegroundIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "MacroPilot App UI probe",
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
            .setContentTitle("MacroPilot is reading App UI")
            .setContentText("Running App UI graph probe in the phone-side service")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_APP_NAME = "appName"
        const val EXTRA_NOTE = "note"
        const val EXTRA_MAX_SCREENS = "maxScreens"
        const val EXTRA_MAX_CLICKS = "maxClicks"
        const val EXTRA_MAX_DEPTH = "maxDepth"
        const val EXTRA_MAX_SWIPE_ACTIONS = "maxSwipeActions"
        const val EXTRA_TIMEOUT_MS = "timeoutMs"
        const val EXTRA_PRIMARY_NAV_ONLY = "primaryNavigationOnly"
        private const val CHANNEL_ID = "macropilot_app_ui_probe"
        private const val NOTIFICATION_ID = 260515
        private const val TAG = "MacroPilotAppUiProbe"
        private val running = AtomicBoolean(false)
        private val queue = ConcurrentLinkedQueue<ProbeRequest>()

        fun start(
            context: Context,
            requestId: String,
            packageName: String,
            appName: String,
            note: String,
            maxScreens: Int,
            maxClicks: Int,
            maxDepth: Int,
            maxSwipeActions: Int,
            timeoutMs: Long,
            primaryNavigationOnly: Boolean
        ) {
            val intent = Intent(context, AppUiProbeService::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_APP_NAME, appName)
                .putExtra(EXTRA_NOTE, note)
                .putExtra(EXTRA_MAX_SCREENS, maxScreens)
                .putExtra(EXTRA_MAX_CLICKS, maxClicks)
                .putExtra(EXTRA_MAX_DEPTH, maxDepth)
                .putExtra(EXTRA_MAX_SWIPE_ACTIONS, maxSwipeActions)
                .putExtra(EXTRA_TIMEOUT_MS, timeoutMs)
                .putExtra(EXTRA_PRIMARY_NAV_ONLY, primaryNavigationOnly)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

private data class ProbeRequest(
    val requestId: String,
    val packageName: String,
    val appName: String,
    val note: String,
    val maxScreens: Int,
    val maxClicks: Int,
    val maxDepth: Int,
    val maxSwipeActions: Int,
    val timeoutMs: Long,
    val primaryNavigationOnly: Boolean
)
