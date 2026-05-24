package com.roubao.autopilot.macro.ai

data class AiAssistEvent(
    val type: AiAssistEventType,
    val title: String,
    val inputSummary: String,
    val outputSummary: String,
    val accepted: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

enum class AiAssistEventType {
    FIND_ELEMENT,
    GENERALIZE_TRACE,
    REPAIR_UI_MODULE,
    VERIFY_RESULT
}

data class AiAssistPanelState(
    val events: List<AiAssistEvent> = emptyList()
) {
    val callCount: Int get() = events.size
}
