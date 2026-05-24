package com.macropilot.app.runtime

import com.macropilot.app.model.MacroAtom

class MacroTimeoutPolicy {
    val totalTaskTimeoutMs: Long = 30_000L

    fun timeoutFor(atom: MacroAtom): Long {
        return when (atom) {
            is MacroAtom.OpenApp -> atom.timeoutMs
            is MacroAtom.EnsureScreen -> atom.timeoutMs
            is MacroAtom.ClickElement -> atom.timeoutMs
            is MacroAtom.FocusTextInput -> atom.timeoutMs
            is MacroAtom.TypeText -> atom.timeoutMs
            is MacroAtom.SearchInApp -> atom.timeoutMs
            is MacroAtom.SelectItem -> atom.timeoutMs
            is MacroAtom.ScrollUntilVisible -> atom.timeoutMs
            is MacroAtom.WaitFor -> atom.timeoutMs
            is MacroAtom.Verify -> atom.timeoutMs
        }
    }
}
