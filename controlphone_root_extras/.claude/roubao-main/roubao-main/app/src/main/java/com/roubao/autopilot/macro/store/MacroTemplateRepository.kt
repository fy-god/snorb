package com.roubao.autopilot.macro.store

import android.content.Context
import com.roubao.autopilot.macro.builtin.BuiltinBrowserMacros
import com.roubao.autopilot.macro.builtin.BuiltinSystemMacros
import com.roubao.autopilot.macro.builtin.BuiltinWechatMacros
import com.roubao.autopilot.macro.model.MacroTemplate
import com.roubao.autopilot.skills.ParsedIntent

class MacroTemplateRepository(
    context: Context
) {
    private val store = AppUiCoordinateStore(context)

    fun findBest(intent: ParsedIntent): MacroTemplate? {
        return loadAllTemplates()
            .filter { it.intentId == intent.templateId || it.id == intent.templateId }
            .filter { template ->
                template.paramSchema.all { !it.required || intent.params.containsKey(it.name) }
            }
            .maxByOrNull { scoreTemplate(it, intent) }
    }

    fun save(template: MacroTemplate) {
        store.saveTemplate(template)
    }

    fun loadAllTemplates(): List<MacroTemplate> {
        val builtins = BuiltinWechatMacros.templates +
            BuiltinSystemMacros.templates +
            BuiltinBrowserMacros.templates
        val stored = store.loadTemplates()
        return (stored + builtins).distinctBy { it.id }
    }

    private fun scoreTemplate(template: MacroTemplate, intent: ParsedIntent): Float {
        var score = 0.50f
        if (template.intentId == intent.templateId) score += 0.25f
        if (template.appName == intent.appName || template.appPackage == intent.appName) score += 0.10f
        val required = template.paramSchema.filter { it.required }
        if (required.isNotEmpty()) {
            val matched = required.count { intent.params.containsKey(it.name) }
            score += 0.15f * matched / required.size
        }
        return score.coerceIn(0f, 1f)
    }
}
