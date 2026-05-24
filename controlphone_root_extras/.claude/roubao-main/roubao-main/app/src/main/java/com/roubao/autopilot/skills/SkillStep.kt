package com.roubao.autopilot.skills

import com.roubao.autopilot.agent.Action
import org.json.JSONArray
import org.json.JSONObject

/** 步骤定位策略 */
enum class ResolvePolicy {
    FIXED,                 // 固定坐标，只适合非常稳定的按钮
    ACCESSIBILITY_TEXT,    // 通过无障碍节点找文字
    DEEPLINK,              // DeepLink 或 Intent，最稳定
    SHELL,                 // shell 命令
    AI_VISION,             // 必须截图给 AI 判断
    HYBRID                 // 先本地找，找不到再 AI
}

sealed class SkillStep(
    open val waitMs: Long = 500L,
    open val resolvePolicy: ResolvePolicy = ResolvePolicy.FIXED,
    open val verify: VerifyRule? = null
) {
    data class OpenApp(val appName: String, val packageName: String? = null, override val waitMs: Long = 500L, override val resolvePolicy: ResolvePolicy = ResolvePolicy.SHELL, override val verify: VerifyRule? = VerifyRule.PackageVisible(packageName ?: "")) : SkillStep(waitMs, resolvePolicy, verify)
    data class Search(val target: SkillTarget, val query: String, override val waitMs: Long = 300L, override val resolvePolicy: ResolvePolicy = ResolvePolicy.HYBRID, override val verify: VerifyRule? = null) : SkillStep(waitMs, resolvePolicy, verify)
    data class ClickTarget(val target: SkillTarget, override val waitMs: Long = 300L, override val resolvePolicy: ResolvePolicy = ResolvePolicy.ACCESSIBILITY_TEXT, override val verify: VerifyRule? = null) : SkillStep(waitMs, resolvePolicy, verify)
    data class FocusInput(val target: SkillTarget = SkillTarget(className = "android.widget.EditText"), override val waitMs: Long = 300L, override val resolvePolicy: ResolvePolicy = ResolvePolicy.ACCESSIBILITY_TEXT, override val verify: VerifyRule? = null) : SkillStep(waitMs, resolvePolicy, verify)
    data class TypeText(val text: String, override val waitMs: Long = 300L, override val resolvePolicy: ResolvePolicy = ResolvePolicy.ACCESSIBILITY_TEXT, override val verify: VerifyRule? = null) : SkillStep(waitMs, resolvePolicy, verify)
    data class Send(val target: SkillTarget = SkillTarget(text = "发送"), override val waitMs: Long = 300L, override val resolvePolicy: ResolvePolicy = ResolvePolicy.HYBRID, override val verify: VerifyRule? = null) : SkillStep(waitMs, resolvePolicy, verify)
    data class FallbackImage(val action: Action, val target: SkillTarget = SkillTarget(), override val waitMs: Long = 300L, override val resolvePolicy: ResolvePolicy = ResolvePolicy.AI_VISION, override val verify: VerifyRule? = null) : SkillStep(waitMs, resolvePolicy, verify)
    data class Click(val x: Int, val y: Int, val scale: Int = 1000, override val waitMs: Long = 300L, override val resolvePolicy: ResolvePolicy = ResolvePolicy.FIXED, override val verify: VerifyRule? = null) : SkillStep(waitMs, resolvePolicy, verify)
    data class ClickText(val text: String, override val waitMs: Long = 300L, override val resolvePolicy: ResolvePolicy = ResolvePolicy.ACCESSIBILITY_TEXT, override val verify: VerifyRule? = null) : SkillStep(waitMs, resolvePolicy, verify)
    data class SystemButton(val button: String, override val waitMs: Long = 300L, override val resolvePolicy: ResolvePolicy = ResolvePolicy.SHELL, override val verify: VerifyRule? = null) : SkillStep(waitMs, resolvePolicy, verify)
    data class Wait(override val waitMs: Long = 500L) : SkillStep(waitMs)
    data class Verify(val rule: VerifyRule, override val waitMs: Long = 0L) : SkillStep(waitMs)

    fun toAction(): Action? = when (this) {
        is OpenApp -> Action(type = "open_app", text = packageName ?: appName)
        is Search -> null
        is ClickTarget -> null
        is FocusInput -> null
        is TypeText -> Action(type = "type", text = text)
        is Send -> null
        is FallbackImage -> action
        is Click -> Action(type = "click", x = x, y = y)
        is ClickText -> null
        is SystemButton -> Action(type = "system_button", button = button)
        is Wait -> Action(type = "wait", duration = (waitMs / 1000L).toInt().coerceAtLeast(1))
        is Verify -> null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", when (this@SkillStep) {
            is OpenApp -> "open_app"
            is Search -> "search"
            is ClickTarget -> "click_target"
            is FocusInput -> "focus_input"
            is TypeText -> "type_text"
            is Send -> "send"
            is FallbackImage -> "fallback_image"
            is Click -> "click"
            is ClickText -> "click_text"
            is SystemButton -> "system_button"
            is Wait -> "wait"
            is Verify -> "verify"
        })
        put("waitMs", waitMs)
        when (val step = this@SkillStep) {
            is OpenApp -> {
                put("appName", step.appName)
                step.packageName?.let { put("packageName", it) }
            }
            is Search -> {
                put("target", step.target.toJson())
                put("query", step.query)
            }
            is ClickTarget -> put("target", step.target.toJson())
            is FocusInput -> put("target", step.target.toJson())
            is TypeText -> put("text", step.text)
            is Send -> put("target", step.target.toJson())
            is FallbackImage -> {
                put("action", JSONObject(step.action.toJson()))
                put("target", step.target.toJson())
            }
            is Click -> {
                put("x", step.x)
                put("y", step.y)
                put("scale", step.scale)
            }
            is ClickText -> put("text", step.text)
            is SystemButton -> put("button", step.button)
            is Wait -> Unit
            is Verify -> put("rule", step.rule.toJson())
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): SkillStep? {
            val waitMs = obj.optLong("waitMs", 500L)
            return when (obj.optString("type")) {
                "open_app" -> OpenApp(obj.optString("appName"), obj.optString("packageName", null), waitMs)
                "search" -> Search(SkillTarget.fromJson(obj.optJSONObject("target")), obj.optString("query"), waitMs)
                "click_target" -> ClickTarget(SkillTarget.fromJson(obj.optJSONObject("target")), waitMs)
                "focus_input" -> FocusInput(SkillTarget.fromJson(obj.optJSONObject("target")), waitMs)
                "type_text" -> TypeText(obj.optString("text"), waitMs)
                "send" -> Send(SkillTarget.fromJson(obj.optJSONObject("target")), waitMs)
                "fallback_image" -> {
                    val action = Action.fromJson(obj.optJSONObject("action")?.toString() ?: "") ?: return null
                    FallbackImage(action, SkillTarget.fromJson(obj.optJSONObject("target")), waitMs)
                }
                "click" -> Click(obj.optInt("x"), obj.optInt("y"), obj.optInt("scale", 1000), waitMs)
                "click_text" -> ClickText(obj.optString("text"), waitMs)
                "system_button" -> SystemButton(obj.optString("button"), waitMs)
                "wait" -> Wait(waitMs)
                "verify" -> Verify(VerifyRule.fromJson(obj.optJSONObject("rule")) ?: return null, waitMs)
                else -> null
            }
        }
    }
}

data class SkillTarget(
    val text: String? = null,
    val className: String? = null,
    val contentDesc: String? = null,
    val bounds: TargetBounds? = null,
    val fallbackX: Int? = null,
    val fallbackY: Int? = null
) {
    fun withFallbackFrom(action: Action): SkillTarget {
        return if (fallbackX != null || fallbackY != null || action.x == null || action.y == null) this else copy(fallbackX = action.x, fallbackY = action.y)
    }

    fun replaceParams(params: Map<String, String>): SkillTarget = copy(
        text = text?.replaceParams(params),
        className = className?.replaceParams(params),
        contentDesc = contentDesc?.replaceParams(params)
    )

    fun toJson(): JSONObject = JSONObject().apply {
        text?.let { put("text", it) }
        className?.let { put("className", it) }
        contentDesc?.let { put("contentDesc", it) }
        bounds?.let { put("bounds", it.toJson()) }
        fallbackX?.let { put("fallbackX", it) }
        fallbackY?.let { put("fallbackY", it) }
    }

    companion object {
        fun fromJson(obj: JSONObject?): SkillTarget {
            if (obj == null) return SkillTarget()
            return SkillTarget(
                text = obj.optString("text", null),
                className = obj.optString("className", null),
                contentDesc = obj.optString("contentDesc", null),
                bounds = TargetBounds.fromJson(obj.optJSONObject("bounds")),
                fallbackX = if (obj.has("fallbackX")) obj.optInt("fallbackX") else null,
                fallbackY = if (obj.has("fallbackY")) obj.optInt("fallbackY") else null
            )
        }
    }
}

data class TargetBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("left", left)
        put("top", top)
        put("right", right)
        put("bottom", bottom)
    }

    companion object {
        fun fromJson(obj: JSONObject?): TargetBounds? {
            if (obj == null) return null
            return TargetBounds(obj.optInt("left"), obj.optInt("top"), obj.optInt("right"), obj.optInt("bottom"))
        }
    }
}

sealed class VerifyRule {
    data class PackageVisible(val packageName: String) : VerifyRule()
    data class UiTextContains(val text: String) : VerifyRule()
    data object KeyboardVisible : VerifyRule()

    fun toJson(): JSONObject = JSONObject().apply {
        when (val rule = this@VerifyRule) {
            is PackageVisible -> {
                put("type", "package_visible")
                put("packageName", rule.packageName)
            }
            is UiTextContains -> {
                put("type", "ui_text_contains")
                put("text", rule.text)
            }
            KeyboardVisible -> put("type", "keyboard_visible")
        }
    }

    companion object {
        fun fromJson(obj: JSONObject?): VerifyRule? {
            if (obj == null) return null
            return when (obj.optString("type")) {
                "package_visible" -> PackageVisible(obj.optString("packageName"))
                "ui_text_contains" -> UiTextContains(obj.optString("text"))
                "keyboard_visible" -> KeyboardVisible
                else -> null
            }
        }
    }
}

data class SkillWorkflowPlan(
    val id: String,
    val name: String,
    val params: Map<String, String>,
    val steps: List<SkillStep>
) {
    fun replaceParams(params: Map<String, String>): SkillWorkflowPlan = copy(
        params = params,
        steps = steps.map { step ->
            when (step) {
                is SkillStep.OpenApp -> step.copy(appName = step.appName.replaceParams(params), packageName = step.packageName?.replaceParams(params))
                is SkillStep.Search -> step.copy(target = step.target.replaceParams(params), query = step.query.replaceParams(params))
                is SkillStep.ClickTarget -> step.copy(target = step.target.replaceParams(params))
                is SkillStep.FocusInput -> step.copy(target = step.target.replaceParams(params))
                is SkillStep.TypeText -> step.copy(text = step.text.replaceParams(params))
                is SkillStep.Send -> step.copy(target = step.target.replaceParams(params))
                is SkillStep.FallbackImage -> step.copy(target = step.target.replaceParams(params), action = step.action.copy(text = step.action.text?.replaceParams(params)))
                is SkillStep.Click -> step
                is SkillStep.ClickText -> step.copy(text = step.text.replaceParams(params))
                is SkillStep.SystemButton -> step
                is SkillStep.Wait -> step
                is SkillStep.Verify -> when (val rule = step.rule) {
                    is VerifyRule.PackageVisible -> step
                    is VerifyRule.UiTextContains -> step.copy(rule = VerifyRule.UiTextContains(rule.text.replaceParams(params)))
                    VerifyRule.KeyboardVisible -> step
                }
            }
        }
    )
}

private fun String.replaceParams(params: Map<String, String>): String {
    var value = this
    for ((key, param) in params) value = value.replace("{$key}", param)
    return value
}

fun JSONArray.toSkillSteps(): List<SkillStep> = (0 until length()).mapNotNull { SkillStep.fromJson(getJSONObject(it)) }
