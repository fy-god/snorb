package com.roubao.autopilot.macro.core

class MacroEventLogger(
    private val sink: (String) -> Unit = {}
) {
    fun emit(message: String) {
        sink(message)
    }
}
