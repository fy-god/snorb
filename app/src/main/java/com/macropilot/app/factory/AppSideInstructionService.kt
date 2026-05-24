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
import com.macropilot.app.store.FactorySkillJsonStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class AppSideInstructionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded()
        val instruction = intent?.getStringExtra(EXTRA_INSTRUCTION).orEmpty()
        val targetPackage = intent?.getStringExtra(EXTRA_PACKAGE_NAME)
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
            ?: "app_side_service_${System.currentTimeMillis()}"

        if (instruction.isBlank()) {
            saveFailure(requestId, instruction, targetPackage, IllegalArgumentException("Missing instruction"))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        queue.offer(
            InstructionRequest(
                requestId = requestId,
                instruction = instruction,
                targetPackage = targetPackage
            )
        )
        saveQueued(requestId, instruction, targetPackage)
        MacroPilotAccessibilityService.instance?.showStatusOverlay(
            "手机端任务已排队",
            if (running.get()) "QUEUED" else "QUEUED_STARTING",
            "等待前一个 app-side 任务完成后自动执行；queue=${queue.size}"
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
                    runQueuedInstruction(request)
                }
            } finally {
                running.set(false)
                stopSelf()
            }
        }, "macropilot-service-worker").start()
    }

    private fun runQueuedInstruction(request: InstructionRequest) {
        val completed = AtomicBoolean(false)
        val worker = Thread({
            try {
                runCatching {
                    runInstruction(
                        requestId = request.requestId,
                        instruction = request.instruction,
                        targetPackage = request.targetPackage,
                        completed = completed
                    )
                }.getOrElse { error ->
                    if (completed.compareAndSet(false, true)) {
                        saveFailure(request.requestId, request.instruction, request.targetPackage, error)
                    }
                }
            } finally {
                completed.compareAndSet(false, true)
            }
        }, "macropilot-service-${request.requestId}")

        worker.start()
        try {
            worker.join(MAX_SERVICE_DURATION_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (worker.isAlive && completed.compareAndSet(false, true)) {
            saveTimeout(request.requestId, request.instruction, request.targetPackage)
            MacroPilotAccessibilityService.instance?.showStatusOverlay(
                "手机端任务超时",
                "FAILED_TIMEOUT",
                "已写入失败报告，继续执行队列中的下一个任务。"
            )
            worker.interrupt()
        }
    }

    private fun runInstruction(
        requestId: String,
        instruction: String,
        targetPackage: String?,
        completed: AtomicBoolean
    ) {
        val store = FactorySkillJsonStore(applicationContext).apply { ensureCreated() }
        Log.i(TAG, "SERVICE_INSTRUCTION_STARTED requestId=$requestId package=$targetPackage")
        MacroPilotAccessibilityService.instance?.showStatusOverlay(
            "手机端任务执行中",
            "RUNNING",
            instruction.take(80)
        )
        store.saveFlowReport(
            targetPackage ?: "__pending__",
            JSONObject()
                .put("schemaVersion", 1)
                .put("kind", "app_side_ai_direct_instruction_service_state")
                .put("operation", "API_RUN_APP_SIDE_INSTRUCTION")
                .put("id", "${requestId}_running")
                .put("requestId", requestId)
                .put("instruction", instruction)
                .put("appPackage", targetPackage ?: JSONObject.NULL)
                .put("status", "RUNNING")
                .put("message", "Phone-side service accepted the task and is running AI/Skill JSON flow.")
                .put("queueRemaining", queue.size)
                .put("createdAt", System.currentTimeMillis())
        )

        val accessibilityRepair = if (MacroPilotAccessibilityService.instance == null) {
            DeviceActions(applicationContext).ensureAccessibilityServiceEnabled()
        } else {
            null
        }
        if (accessibilityRepair?.success == true) Thread.sleep(700L)

        val events = JSONArray()
        val report = AppSideInstructionFlowRunner(applicationContext) { event ->
            events.put(
                JSONObject()
                    .put("title", event.title)
                    .put("status", event.status)
                    .put("message", event.message)
                    .put("path", event.path)
            )
        }.runInstructionFlow(
            instruction = instruction,
            targetPackageHint = targetPackage
        )

        val completion = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "app_side_ai_direct_instruction_service_completion")
            .put("operation", "API_RUN_APP_SIDE_INSTRUCTION")
            .put("id", "${requestId}_completion")
            .put("requestId", requestId)
            .put("instruction", instruction)
            .put("appPackage", targetPackage ?: report.optString("appPackage", ""))
            .put("status", report.optString("status", "UNKNOWN"))
            .put("flowReportFile", report.optString("reportFile"))
            .put("stepCount", report.optJSONArray("steps")?.length() ?: 0)
            .put("events", events)
            .put("queueRemaining", queue.size)
            .put("createdAt", System.currentTimeMillis())

        if (!completed.compareAndSet(false, true)) {
            Log.w(TAG, "SERVICE_INSTRUCTION_FINISHED_AFTER_TIMEOUT requestId=$requestId ignored")
            return
        }

        val reportPackage = targetPackage ?: report.optString("appPackage", "__pending__")
        val completionFile = store.saveFlowReport(reportPackage, completion)
        store.saveFlowReport(
            reportPackage,
            JSONObject(completion.toString())
                .put("kind", "app_side_ai_direct_instruction_request_final")
                .put("id", requestId)
                .put("message", "Phone-side task finished; flow/runtime/skill JSON reports were written.")
                .put("completionReportFile", completionFile.absolutePath)
                .put("reportWatchMode", "FINAL")
        )
        Log.i(
            TAG,
            "SERVICE_INSTRUCTION_FINISHED requestId=$requestId status=${completion.optString("status")} report=${completion.optString("flowReportFile")}"
        )
    }

    private fun saveQueued(requestId: String, instruction: String, targetPackage: String?) {
        Log.i(TAG, "SERVICE_INSTRUCTION_QUEUED requestId=$requestId package=$targetPackage queue=${queue.size}")
        FactorySkillJsonStore(applicationContext).apply { ensureCreated() }
            .saveFlowReport(
                targetPackage ?: "__pending__",
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("kind", "app_side_ai_direct_instruction_service_queued")
                    .put("operation", "API_RUN_APP_SIDE_INSTRUCTION")
                    .put("id", "${requestId}_queued")
                    .put("requestId", requestId)
                    .put("instruction", instruction)
                    .put("appPackage", targetPackage ?: JSONObject.NULL)
                    .put("status", if (running.get()) "QUEUED" else "QUEUED_STARTING")
                    .put("message", "Phone-side app-side task is queued for serial execution.")
                    .put("queueSize", queue.size)
                    .put("createdAt", System.currentTimeMillis())
            )
    }

    private fun saveFailure(requestId: String, instruction: String, targetPackage: String?, error: Throwable) {
        Log.e(TAG, "SERVICE_INSTRUCTION_FAILED requestId=$requestId", error)
        val failure = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "app_side_ai_direct_instruction_service_failure")
            .put("operation", "API_RUN_APP_SIDE_INSTRUCTION")
            .put("id", "${requestId}_failure")
            .put("requestId", requestId)
            .put("instruction", instruction)
            .put("appPackage", targetPackage ?: JSONObject.NULL)
            .put("status", "FAILED_EXCEPTION")
            .put("message", error.message ?: error.javaClass.simpleName)
            .put("errorClass", error.javaClass.name)
            .put("stackTrace", Log.getStackTraceString(error).take(12_000))
            .put("createdAt", System.currentTimeMillis())
        val reportPackage = targetPackage ?: "__pending__"
        FactorySkillJsonStore(applicationContext).apply { ensureCreated() }.also { store ->
            val failureFile = store.saveFlowReport(reportPackage, failure)
            store.saveFlowReport(
                reportPackage,
                JSONObject(failure.toString())
                    .put("kind", "app_side_ai_direct_instruction_request_final")
                    .put("id", requestId)
                    .put("failureReportFile", failureFile.absolutePath)
                    .put("reportWatchMode", "FINAL")
            )
        }
    }

    private fun saveTimeout(requestId: String, instruction: String, targetPackage: String?) {
        Log.e(TAG, "SERVICE_INSTRUCTION_TIMEOUT requestId=$requestId maxMs=$MAX_SERVICE_DURATION_MS")
        val root = MacroPilotAccessibilityService.instance?.rootInActiveWindow
        val currentPackage = root?.packageName?.toString()
        val timeout = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "app_side_ai_direct_instruction_service_timeout")
            .put("operation", "API_RUN_APP_SIDE_INSTRUCTION")
            .put("id", "${requestId}_timeout")
            .put("requestId", requestId)
            .put("instruction", instruction)
            .put("appPackage", targetPackage ?: JSONObject.NULL)
            .put("status", "FAILED_TIMEOUT")
            .put("message", "Phone-side task exceeded ${MAX_SERVICE_DURATION_MS / 1000}s; a visible failure report was written.")
            .put("currentPackage", currentPackage ?: JSONObject.NULL)
            .put("serviceConnected", MacroPilotAccessibilityService.instance != null)
            .put("queueRemaining", queue.size)
            .put("createdAt", System.currentTimeMillis())

        val reportPackage = targetPackage ?: currentPackage ?: "__pending__"
        FactorySkillJsonStore(applicationContext).apply { ensureCreated() }.also { store ->
            store.saveFlowReport(reportPackage, timeout)
            store.saveFlowReport(
                reportPackage,
                JSONObject(timeout.toString())
                    .put("kind", "app_side_ai_direct_instruction_request_final")
                    .put("id", requestId)
                    .put("reportWatchMode", "FINAL")
            )
        }
    }

    private fun startForegroundIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "MacroPilot phone-side tasks",
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
            .setContentTitle("MacroPilot is running phone-side tasks")
            .setContentText("AI is planning, writing Skill JSON, executing, and reporting.")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_INSTRUCTION = "instruction"
        const val EXTRA_PACKAGE_NAME = "packageName"
        private val running = AtomicBoolean(false)
        private val queue = ConcurrentLinkedQueue<InstructionRequest>()
        private const val CHANNEL_ID = "macropilot_app_side_tasks"
        private const val NOTIFICATION_ID = 260512
        private const val TAG = "MacroPilotAppSideSvc"
        private const val MAX_SERVICE_DURATION_MS = 300_000L

        fun start(context: Context, requestId: String, instruction: String, targetPackage: String?) {
            val intent = Intent(context, AppSideInstructionService::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_INSTRUCTION, instruction)
                .putExtra(EXTRA_PACKAGE_NAME, targetPackage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

private data class InstructionRequest(
    val requestId: String,
    val instruction: String,
    val targetPackage: String?
)
