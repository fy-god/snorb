package com.macropilot.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.macropilot.app.ai.AiAssistClient
import com.macropilot.app.ai.AiAssistConfigStore
import com.macropilot.app.ai.AiAssistRequest
import com.macropilot.app.ai.AiAssistUseCase
import com.macropilot.app.ai.AiProvider
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.factory.AppSideInstructionService
import com.macropilot.app.runtime.CapabilityProfiler
import com.macropilot.app.runtime.NodeInspector
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var instructionInput: EditText
    private lateinit var packageInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareDeviceWindow()
        DeviceActions(this).ensureMacroPilotImeSelected()
        window.statusBarColor = COLOR_BG
        window.navigationBarColor = COLOR_BG
        setContentView(buildUi())
        refreshStatus("MacroPilot ready")
        requestKeyguardDismissIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        DeviceActions(this).ensureMacroPilotImeSelected()
        requestKeyguardDismissIfNeeded()
        refreshStatus("Status refreshed")
    }

    private fun prepareDeviceWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun requestKeyguardDismissIfNeeded() {
        val keyguard = getSystemService(KeyguardManager::class.java) ?: return
        if (!keyguard.isKeyguardLocked) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    refreshStatus("Device prepared")
                }

                override fun onDismissError() {
                    refreshStatus("Unlock required by system")
                }

                override fun onDismissCancelled() {
                    refreshStatus("Unlock cancelled")
                }
            })
        }
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(COLOR_BG) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(root)

        root.addView(text("MacroPilot Console", 24f, true, COLOR_TEXT))
        root.addView(text("App-side AI, Skill JSON, accessibility and IME status. Tasks are executed by the phone-side service.", 13f, false, COLOR_MUTED))

        statusText = text("", 13f, false, COLOR_TEXT).apply {
            background = rounded(COLOR_CARD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(statusText, lp(top = 14))

        root.addView(section("AI"))
        root.addView(button("Test AI connection") { testAiConnection() }, lp(top = 8))
        root.addView(button("Set Qwen3-VL-Flash and test") {
            saveQwenConfig()
            testAiConnection()
        }, lp(top = 8))

        root.addView(section("Phone capability"))
        root.addView(button("Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, lp(top = 8))
        root.addView(button("Select MacroPilot IME") {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }, lp(top = 8))
        root.addView(button("Refresh status") { refreshStatus("Status refreshed") }, lp(top = 8))

        root.addView(section("App-side instruction"))
        packageInput = input("Target package, e.g. com.tencent.mm").apply { setText("com.tencent.mm") }
        instructionInput = input("Task, e.g. 在微信发一条仅自己可见的朋友圈：我是AI")
        root.addView(packageInput, lp(top = 8))
        root.addView(instructionInput, lp(top = 8))
        root.addView(button("Run on phone side") { runAppSideInstruction() }, lp(top = 10))

        logText = text("Idle.", 12f, false, COLOR_MUTED).apply {
            background = rounded(COLOR_FIELD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(logText, lp(top = 14))
        return scroll
    }

    private fun refreshStatus(prefix: String) {
        val state = NodeInspector(this).currentState()
        val profile = CapabilityProfiler().profile(state, MacroPilotAccessibilityService.instance != null)
        val imeSelected = (getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.any { it.packageName == packageName }) == true
        statusText.text = buildString {
            append(prefix).append('\n')
            append("Accessibility: ").append(if (MacroPilotAccessibilityService.instance != null) "CONNECTED" else "DISCONNECTED").append('\n')
            append("IME: ").append(if (imeSelected) "MacroPilot selected" else "not selected").append('\n')
            append("Package: ").append(state?.appPackage ?: "-").append('\n')
            append("Nodes: ").append(state?.nodes?.size ?: 0).append("  Clickable: ").append(state?.nodes?.count { it.clickable } ?: 0).append('\n')
            append("Input: ").append(profile.inputCapability).append("  Verify: ").append(profile.verificationCapability)
        }
    }

    private fun saveQwenConfig() {
        val store = AiAssistConfigStore(this)
        AiAssistConfigStore(this).save(
            enabled = true,
            provider = AiProvider.DASHSCOPE_COMPAT_CHAT,
            endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            model = "qwen3-vl-flash",
            apiKey = store.apiKey()
        )
        log("AI config set to qwen3-vl-flash.")
    }

    private fun testAiConnection() {
        Thread {
            val response = AiAssistClient(this).request(
                AiAssistRequest(
                    useCase = AiAssistUseCase.FAILURE_SUMMARY,
                    inputJson = JSONObject()
                        .put("task", "ping")
                        .put("message", "Return JSON only: {\"ok\":true}"),
                    redacted = true,
                    userVisible = true,
                    runtimePath = false
                )
            )
            runOnUiThread {
                log("AI test: ${response.status}; HTTP=${response.httpStatus ?: "-"}; ${response.message.take(160)}")
            }
        }.start()
    }

    private fun runAppSideInstruction() {
        val pkg = packageInput.text?.toString()?.trim().orEmpty()
        val instruction = instructionInput.text?.toString()?.trim().orEmpty()
        if (pkg.isBlank() || instruction.isBlank()) {
            log("Package and task are required.")
            return
        }
        val requestId = "ui_instruction_${System.currentTimeMillis()}"
        AppSideInstructionService.start(this, requestId, instruction, pkg)
        log("Started phone-side task: $requestId\nReports: files/macro_v2/factory/flow_reports/$pkg")
    }

    private fun log(message: String) {
        logText.text = message
        refreshStatus("Status refreshed")
    }

    private fun section(title: String): TextView = text(title, 16f, true, COLOR_TEXT).apply {
        setPadding(0, dp(18), 0, 0)
    }

    private fun input(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setTextColor(COLOR_TEXT)
        setHintTextColor(COLOR_MUTED)
        textSize = 14f
        background = rounded(COLOR_FIELD)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        minLines = 1
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 14f
        background = rounded(COLOR_ACCENT)
        setOnClickListener { onClick() }
    }

    private fun text(value: String, size: Float, bold: Boolean, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(2f, 1.0f)
        gravity = Gravity.START
    }

    private fun rounded(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(color)
    }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val COLOR_BG = Color.rgb(15, 18, 25)
        private val COLOR_CARD = Color.rgb(30, 35, 48)
        private val COLOR_FIELD = Color.rgb(24, 29, 40)
        private val COLOR_TEXT = Color.rgb(238, 242, 255)
        private val COLOR_MUTED = Color.rgb(154, 164, 185)
        private val COLOR_ACCENT = Color.rgb(64, 116, 255)
    }
}
