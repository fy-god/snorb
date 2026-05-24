package com.roubao.autopilot.macro.record

import com.roubao.autopilot.macro.ai.AiAssist
import com.roubao.autopilot.macro.core.MacroEventLogger
import com.roubao.autopilot.macro.model.UiElementSpec

class TraceGeneralizer(
    private val aiAssist: AiAssist?,
    private val logger: MacroEventLogger
) {
    suspend fun generalize(trace: RecordedTrace): GeneralizeResult {
        logger.emit("[GENERALIZE] trace=${trace.id} records=${trace.records.size}")
        if (trace.records.isEmpty()) {
            return GeneralizeResult.Failed("empty trace")
        }
        val draft = aiAssist?.generalizeTrace(trace)
        return if (draft != null) {
            GeneralizeResult.Success(draft.template, emptyList())
        } else {
            GeneralizeResult.Pending("trace needs UI element generalization")
        }
    }
}

sealed class GeneralizeResult {
    data class Success(
        val template: com.roubao.autopilot.macro.model.MacroTemplate,
        val elements: List<UiElementSpec>
    ) : GeneralizeResult()

    data class Pending(val reason: String) : GeneralizeResult()
    data class Failed(val reason: String) : GeneralizeResult()
}
