package com.roubao.autopilot

import android.app.Application
import android.content.pm.PackageManager
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.roubao.autopilot.ble.BleCommandReceiver
import com.roubao.autopilot.controller.AppScanner
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.data.SettingsManager
import com.roubao.autopilot.skills.FragmentLibrary
import com.roubao.autopilot.skills.RecordedSkillLibrary
import com.roubao.autopilot.skills.SkillManager
import com.roubao.autopilot.skills.WorkflowLibrary
import com.roubao.autopilot.tools.ToolManager
import com.roubao.autopilot.utils.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class App : Application() {

    lateinit var deviceController: DeviceController
        private set
    lateinit var appScanner: AppScanner
        private set

    // BLE 命令接收器 (ESP32 → 手机)
    var bleCommandReceiver: BleCommandReceiver? = null
        private set

    // BLE 来的 AI 复杂指令回调 (由 MainActivity 设置)
    var onBleAICommand: ((String) -> Unit)? = null

    // Workflow 宏库 (预录制操作序列)
    var workflowLibrary: WorkflowLibrary? = null
        private set

    // Fragment 碎片库 (可复用原子动作，零截图组装)
    var fragmentLibrary: FragmentLibrary? = null
        private set

    // Recorded Skill 库 (语义步骤录制/回放)
    var recordedSkillLibrary: RecordedSkillLibrary? = null
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化崩溃捕获（本地日志）
        CrashHandler.getInstance().init(this)

        // 初始化 Firebase Crashlytics（根据用户设置决定是否启用）
        val settingsManager = SettingsManager(this)
        val cloudCrashReportEnabled = settingsManager.settings.value.cloudCrashReportEnabled
        FirebaseCrashlytics.getInstance().apply {
            setCrashlyticsCollectionEnabled(cloudCrashReportEnabled)
            setCustomKey("app_version", BuildConfig.VERSION_NAME)
            setCustomKey("device_model", android.os.Build.MODEL)
            setCustomKey("android_version", android.os.Build.VERSION.SDK_INT.toString())
            // 发送待上传的崩溃报告
            sendUnsentReports()
        }
        println("[App] 云端崩溃上报: ${if (cloudCrashReportEnabled) "已开启" else "已关闭"}")

        // 初始化 Shizuku
        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)

        // 初始化核心组件
        initializeComponents()
    }

    private fun initializeComponents() {
        // 初始化设备控制器
        deviceController = DeviceController(this)
        deviceController.setCacheDir(cacheDir)

        // 初始化应用扫描器
        appScanner = AppScanner(this)

        // 初始化 Tools 层
        val toolManager = ToolManager.init(this, deviceController, appScanner)

        // 异步预扫描应用列表（避免 ANR）
        println("[App] 开始异步扫描已安装应用...")
        Thread {
            appScanner.refreshApps()
            println("[App] 已扫描 ${appScanner.getApps().size} 个应用")
        }.start()

        // 初始化 Skills 层（传入 appScanner 用于检测已安装应用）
        val skillManager = SkillManager.init(this, toolManager, appScanner)
        println("[App] SkillManager 已加载 ${skillManager.getAllSkills().size} 个 Skills")

        // 初始化 BLE 命令接收器 (ESP32 蓝牙触发)
        bleCommandReceiver = BleCommandReceiver(
            context = this,
            onAction = { action ->
                println("[App] BLE 直接执行: ${action.type}")
                BleCommandReceiver.executeAction(action, deviceController)
            },
            onAICommand = { query ->
                println("[App] BLE AI 指令: $query")
                onBleAICommand?.invoke(query)
            }
        )
        // 尝试启动 BLE Server (蓝牙未开启则静默失败)
        bleCommandReceiver?.start()

        // 初始化 Fragment 碎片库 (可复用原子动作，零截图组装)
        fragmentLibrary = FragmentLibrary(this, deviceController)
        appScope.launch {
            fragmentLibrary?.load()
        }

        // 初始化 Workflow 宏库 (预录制操作序列，零 VLM 回放)
        workflowLibrary = WorkflowLibrary(this, deviceController)
        appScope.launch {
            workflowLibrary?.load()
        }

        // 初始化 Recorded Skill 库 (语义步骤录制/回放)
        recordedSkillLibrary = RecordedSkillLibrary(this)
        appScope.launch {
            recordedSkillLibrary?.load()
        }

        println("[App] 组件初始化完成")
    }

    override fun onTerminate() {
        super.onTerminate()
        bleCommandReceiver?.stop()
        Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
    }

    /**
     * 动态更新云端崩溃上报开关
     */
    fun updateCloudCrashReportEnabled(enabled: Boolean) {
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
        println("[App] 云端崩溃上报已${if (enabled) "开启" else "关闭"}")
    }

    companion object {
        @Volatile
        private var instance: App? = null

        fun getInstance(): App {
            return instance ?: throw IllegalStateException("App 未初始化")
        }

        private val REQUEST_PERMISSION_RESULT_LISTENER =
            Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                println("[Shizuku] Permission result: $granted")
            }
    }
}
