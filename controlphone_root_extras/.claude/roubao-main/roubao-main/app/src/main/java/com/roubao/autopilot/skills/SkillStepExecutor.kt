package com.roubao.autopilot.skills

import com.roubao.autopilot.controller.DeviceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class StepStatus { SUCCESS, FAILED, UNKNOWN, NEEDS_AI }

data class StepResult(
    val status: StepStatus,
    val step: SkillStep,
    val message: String,
    val canFallbackToAi: Boolean = false
)

class SkillStepExecutor(
    private val controller: DeviceController,
    private val log: (String) -> Unit = {}
) {
    suspend fun execute(plan: SkillWorkflowPlan): SkillExecutionResult = withContext(Dispatchers.IO) {
        log("SkillWorkflow 开始: ${plan.name}, ${plan.steps.size} 步")
        for ((index, step) in plan.steps.withIndex()) {
            val label = describe(step)
            log("SkillWorkflow 步骤 ${index + 1}/${plan.steps.size}: $label")
            val result = executeStep(step)
            when (result.status) {
                StepStatus.SUCCESS -> {
                    if (step.waitMs > 0) delay(step.waitMs.coerceAtMost(800L))
                }
                StepStatus.FAILED -> {
                    log("SkillWorkflow 步骤失败: $label — ${result.message}")
                    return@withContext SkillExecutionResult(false, "步骤失败: $label — ${result.message}")
                }
                StepStatus.UNKNOWN -> {
                    log("SkillWorkflow 步骤状态未知: $label — ${result.message}")
                    if (step.waitMs > 0) delay(step.waitMs.coerceAtMost(800L))
                }
                StepStatus.NEEDS_AI -> {
                    log("SkillWorkflow 步骤需要 AI: $label — ${result.message}")
                    return@withContext SkillExecutionResult(false, "步骤需要 AI: $label — ${result.message}")
                }
            }
        }
        log("SkillWorkflow 完成: ${plan.name}")
        SkillExecutionResult(true, "已执行: ${plan.name}")
    }

    private suspend fun executeStep(step: SkillStep): StepResult {
        val ok = when (step) {
            is SkillStep.OpenApp -> {
                controller.openApp(step.packageName ?: step.appName)
                waitUntil(step.waitMs.coerceAtLeast(500L), 1500L) {
                    step.packageName == null || currentFocus().contains(step.packageName)
                }
            }
            is SkillStep.Search -> clickTarget(step.target) && run { controller.type(step.query); true }
            is SkillStep.ClickTarget -> clickTarget(step.target)
            is SkillStep.FocusInput -> focusInput(step.target)
            is SkillStep.TypeText -> typeText(step.text)
            is SkillStep.Send -> clickTarget(step.target)
            is SkillStep.FallbackImage -> executeFallback(step)
            is SkillStep.Click -> {
                val (screenW, screenH) = controller.getScreenSize()
                val x = mapCoordinate(step.x, screenW, step.scale)
                val y = mapCoordinate(step.y, screenH, step.scale)
                controller.tap(x, y)
                true
            }
            is SkillStep.ClickText -> clickText(step.text)
            is SkillStep.SystemButton -> {
                when (step.button.lowercase()) {
                    "back" -> controller.back()
                    "home" -> controller.home()
                    "enter" -> controller.enter()
                    else -> return StepResult(StepStatus.FAILED, step, "未知系统键: ${step.button}")
                }
                true
            }
            is SkillStep.Wait -> true
            is SkillStep.Verify -> verify(step.rule)
        }

        if (!ok) {
            val canAi = step.resolvePolicy == ResolvePolicy.HYBRID || step.resolvePolicy == ResolvePolicy.AI_VISION
            return StepResult(
                status = if (canAi) StepStatus.NEEDS_AI else StepStatus.FAILED,
                step = step,
                message = describe(step),
                canFallbackToAi = canAi
            )
        }

        // 步骤本身有 verify 条件时，执行后校验
        val postVerify = step.verify
        return if (postVerify != null && postVerify !is VerifyRule.PackageVisible) {
            // PackageVisible already checked in OpenApp; other rules are post-step checks
            val v = verify(postVerify)
            if (v) StepResult(StepStatus.SUCCESS, step, describe(step))
            else StepResult(StepStatus.UNKNOWN, step, "${describe(step)} — 后置验证未通过", canFallbackToAi = true)
        } else {
            StepResult(StepStatus.SUCCESS, step, describe(step))
        }
    }

    private fun mapCoordinate(value: Int, screenMax: Int, scale: Int): Int {
        return if (value < scale) (value * screenMax / scale) else value.coerceAtMost(screenMax)
    }

    private suspend fun verify(rule: VerifyRule): Boolean {
        return when (rule) {
            is VerifyRule.PackageVisible -> waitUntil(100L, 1200L) { currentFocus().contains(rule.packageName) }
            is VerifyRule.UiTextContains -> waitUntil(100L, 800L) { dumpUi().contains(rule.text) }
            VerifyRule.KeyboardVisible -> waitUntil(100L, 800L) { isKeyboardVisible() }
        }
    }

    private fun clickTarget(target: SkillTarget): Boolean {
        val beforeHash = controller.dumpActiveWindowHash()
        if (controller.clickByTarget(target)) {
            val afterHash = controller.dumpActiveWindowHash()
            if (beforeHash.isBlank() || afterHash.isBlank() || beforeHash != afterHash) return true
            log("Accessibility 点击后 UI 未变化，尝试旧坐标兜底")
        }
        val bounds = findTargetBounds(target)
        if (bounds != null) {
            controller.tap((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2)
            val afterHash = controller.dumpActiveWindowHash()
            return beforeHash.isBlank() || afterHash.isBlank() || beforeHash != afterHash
        }
        if (target.fallbackX != null && target.fallbackY != null) {
            val (screenW, screenH) = controller.getScreenSize()
            controller.tap(mapCoordinate(target.fallbackX, screenW, 1000), mapCoordinate(target.fallbackY, screenH, 1000))
            val afterHash = controller.dumpActiveWindowHash()
            return beforeHash.isBlank() || afterHash.isBlank() || beforeHash != afterHash
        }
        return false
    }

    private fun clickText(text: String): Boolean {
        val bounds = findTextBounds(text, preferLower = true) ?: return false
        controller.tap((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2)
        return true
    }

    private fun focusInput(target: SkillTarget): Boolean {
        if (controller.focusByTarget(target)) return true
        if (controller.focusByTarget(SkillTarget(className = "android.widget.EditText"))) return true
        val bounds = findInputBounds(target)
        if (bounds != null) {
            controller.tap((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2)
            return true
        }
        val fallback = target.takeIf { it.fallbackX != null && it.fallbackY != null }
        if (fallback != null) return clickTarget(fallback)
        log("未找到可靠输入框，取消底部盲点")
        return false
    }

    private fun typeText(text: String): Boolean {
        if (controller.setTextByAccessibility(SkillTarget(className = "android.widget.EditText"), text)) return true
        val beforeHash = controller.dumpActiveWindowHash()
        controller.type(text)
        val afterHash = controller.dumpActiveWindowHash()
        return beforeHash.isBlank() || afterHash.isBlank() || beforeHash != afterHash || dumpUi().contains(text)
    }

    private fun findInputBounds(target: SkillTarget): IntArray? {
        val (_, screenH) = controller.getScreenSize()
        val safeBottom = (screenH * 0.86f).toInt()
        return listOfNotNull(
            findTargetBounds(target),
            findTargetBounds(SkillTarget(className = "android.widget.EditText")),
            findTextBounds("输入", preferLower = false),
            findTextBounds("消息", preferLower = false)
        ).firstOrNull { bounds ->
            bounds[2] > bounds[0] && bounds[3] > bounds[1] && ((bounds[1] + bounds[3]) / 2) < safeBottom
        }
    }

    private fun findTargetBounds(target: SkillTarget): IntArray? {
        target.bounds?.let { return intArrayOf(it.left, it.top, it.right, it.bottom) }
        val ui = dumpUi()
        val candidates = Regex("<node[^>]*bounds=\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\"[^>]*/?>")
            .findAll(ui)
            .mapNotNull { match -> if (nodeMatches(match.value, target)) boundsFromMatch(match) else null }
            .toList()
        if (candidates.isNotEmpty()) {
            return candidates.maxByOrNull { it[1] }
        }
        target.text?.let { findTextBounds(it, preferLower = true)?.let { bounds -> return bounds } }
        target.contentDesc?.let { findTextBounds(it, preferLower = true)?.let { bounds -> return bounds } }
        return null
    }

    private fun nodeMatches(node: String, target: SkillTarget): Boolean {
        var hasSelector = false
        target.text?.takeIf { it.isNotBlank() }?.let {
            hasSelector = true
            if (!node.contains("text=\"$it") && !node.contains(it)) return false
        }
        target.className?.takeIf { it.isNotBlank() }?.let {
            hasSelector = true
            if (!node.contains("class=\"$it\"")) return false
        }
        target.contentDesc?.takeIf { it.isNotBlank() }?.let {
            hasSelector = true
            if (!node.contains("content-desc=\"$it") && !node.contains(it)) return false
        }
        return hasSelector
    }

    private fun executeFallback(step: SkillStep.FallbackImage): Boolean {
        val action = step.action
        return when (action.type) {
            "click", "tap" -> clickTarget(step.target.withFallbackFrom(action))
            "type" -> {
                action.text?.let { controller.type(it) }
                action.text != null
            }
            "open_app" -> {
                action.text?.let { controller.openApp(it) }
                action.text != null
            }
            else -> false
        }
    }

    private fun findTextBounds(text: String, preferLower: Boolean = false): IntArray? {
        val escaped = Regex.escape(text)
        val ui = dumpUi()
        val matches = Regex("(?:text|content-desc)=\"[^\"]*$escaped[^\"]*\"[^>]*bounds=\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\"")
            .findAll(ui)
            .map { boundsFromMatch(it) }
            .toList() + Regex("bounds=\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\"[^>]*(?:text|content-desc)=\"[^\"]*$escaped[^\"]*\"")
            .findAll(ui)
            .map { boundsFromMatch(it) }
            .toList()
        return if (preferLower) matches.maxByOrNull { it[1] } else matches.firstOrNull()
    }

    private fun boundsFromMatch(match: MatchResult): IntArray {
        return intArrayOf(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt()
        )
    }

    private fun isKeyboardVisible(): Boolean {
        val state = inputMethodState()
        return state.contains("mInputShown=true") ||
            state.contains("inputShown=true") ||
            state.contains("mImeWindowVis=0x2") ||
            state.contains("imeInsetsSourceConsumer")
    }

    private suspend fun waitUntil(intervalMs: Long, timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (condition()) return true
            delay(intervalMs.coerceIn(80L, 500L))
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun currentFocus(): String = shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|topResumedActivity'")

    private fun inputMethodState(): String = shell("dumpsys input_method | grep -E 'mInputShown|inputShown|mServedView|mCurrentFocus'")

    private fun dumpUi(): String {
        val path = "/sdcard/roubao_window.xml"
        shell("uiautomator dump $path >/dev/null 2>&1")
        return shell("cat $path 2>/dev/null")
    }

    private fun describe(step: SkillStep): String {
        return when (step) {
            is SkillStep.OpenApp -> "打开应用(${step.packageName ?: step.appName})"
            is SkillStep.Search -> "搜索(${step.query.take(20)})"
            is SkillStep.ClickTarget -> "点击目标(${step.target.text ?: step.target.contentDesc ?: step.target.className ?: "fallback"})"
            is SkillStep.FocusInput -> "聚焦输入栏"
            is SkillStep.TypeText -> "输入(${step.text.take(20)})"
            is SkillStep.Send -> "发送"
            is SkillStep.FallbackImage -> "兜底动作(${step.action.type})"
            is SkillStep.Click -> "点击(${step.x}, ${step.y})"
            is SkillStep.ClickText -> "点击文本(${step.text})"
            is SkillStep.SystemButton -> "系统键(${step.button})"
            is SkillStep.Wait -> "等待(${step.waitMs}ms)"
            is SkillStep.Verify -> when (val rule = step.rule) {
                is VerifyRule.PackageVisible -> "验证应用可见(${rule.packageName})"
                is VerifyRule.UiTextContains -> "验证文本(${rule.text.take(20)})"
                VerifyRule.KeyboardVisible -> "验证键盘"
            }
        }
    }

    private fun shell(command: String): String {
        return try {
            controller.shell(command)
        } catch (e: Exception) {
            ""
        }
    }
}

data class SkillExecutionResult(
    val success: Boolean,
    val message: String
)
