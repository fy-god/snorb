package com.macropilot.app.automation

import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.TextView

class MacroPilotImeService : InputMethodService() {
    companion object {
        @Volatile
        var instance: MacroPilotImeService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        return TextView(this).apply {
            text = "MacroPilot IME"
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(16, 16, 16, 16)
        }
    }

    fun commitMacroText(text: String): Boolean {
        if (text.isEmpty()) return true
        val connection: InputConnection = currentInputConnection ?: return false
        return connection.commitText(text, 1)
    }

    fun replaceMacroText(text: String): Boolean {
        val connection: InputConnection = currentInputConnection ?: return false
        val before = connection.getTextBeforeCursor(10_000, 0)?.length ?: 0
        val after = connection.getTextAfterCursor(10_000, 0)?.length ?: 0
        if (before > 0 || after > 0) {
            connection.deleteSurroundingText(before, after)
        }
        return if (text.isEmpty()) true else connection.commitText(text, 1)
    }

    fun hasActiveInputConnection(): Boolean {
        return currentInputConnection != null
    }
}
