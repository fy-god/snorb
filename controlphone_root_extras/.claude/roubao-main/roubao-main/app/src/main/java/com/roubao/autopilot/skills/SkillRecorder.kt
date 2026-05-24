package com.roubao.autopilot.skills

import com.roubao.autopilot.agent.Action
import com.roubao.autopilot.agent.ExecutorResult
import org.json.JSONObject

class SkillRecorder {
    fun build(instruction: String, intent: ParsedIntent?, executedSteps: List<RecordedSkillStep>): RecordedSkill? {
        if (intent == null || executedSteps.size < 2) return null
        val steps = executedSteps.mapNotNull { it.toSkillStep(intent.params) }
        if (steps.size < 2) return null
        return RecordedSkill(
            name = instruction.take(20),
            intent = intent.templateId,
            appName = intent.appName,
            params = intent.params.keys.toList(),
            steps = steps
        )
    }

    fun parse(result: ExecutorResult): RecordedSkillStep? {
        val action = result.action ?: return null
        if (action.type in listOf("wait", "take_over", "ask_user", "answer", "terminate")) return null
        val obj = runCatching { JSONObject(result.actionStr) }.getOrNull()
        val skillType = obj?.optString("skillType")?.takeIf { it.isNotBlank() }
            ?: inferSkillType(action, result.description)
        val target = SkillTarget.fromJson(obj?.optJSONObject("target")).withFallbackFrom(action)
        return RecordedSkillStep(skillType, action, target)
    }

    private fun inferSkillType(action: Action, description: String): String {
        return when (action.type) {
            "open_app" -> "open_app"
            "type" -> "type_text"
            "click", "tap" -> when {
                description.contains("输入") || description.contains("input", ignoreCase = true) -> "focus_input"
                description.contains("发送") || description.contains("send", ignoreCase = true) -> "send"
                description.contains("搜索") || description.contains("search", ignoreCase = true) -> "search"
                else -> "click_target"
            }
            else -> "fallback_image"
        }
    }
}

data class RecordedSkillStep(
    val skillType: String,
    val action: Action,
    val target: SkillTarget
) {
    fun toSkillStep(params: Map<String, String>): SkillStep? {
        val parameterizedTarget = parameterizeTarget(target, params)
        return when (skillType) {
            "open_app" -> action.text?.let { SkillStep.OpenApp(parameterizeText(it, params)) }
            "search" -> SkillStep.Search(parameterizedTarget, parameterizeText(action.text.orEmpty(), params))
            "select_result", "click_target" -> SkillStep.ClickTarget(parameterizedTarget)
            "focus_input" -> SkillStep.FocusInput(parameterizedTarget)
            "type_text" -> action.text?.let { SkillStep.TypeText(parameterizeText(it, params)) }
            "send" -> SkillStep.Send(if (parameterizedTarget == SkillTarget()) SkillTarget(text = "发送") else parameterizedTarget)
            else -> SkillStep.FallbackImage(action.copy(text = action.text?.let { parameterizeText(it, params) }), parameterizedTarget)
        }
    }

    private fun parameterizeTarget(target: SkillTarget, params: Map<String, String>): SkillTarget {
        return target.copy(
            text = target.text?.let { parameterizeText(it, params) },
            contentDesc = target.contentDesc?.let { parameterizeText(it, params) }
        )
    }

    private fun parameterizeText(text: String, params: Map<String, String>): String {
        var value = text
        for ((key, param) in params) {
            if (param.isNotBlank()) value = value.replace(param, "{$key}")
        }
        return value
    }
}
