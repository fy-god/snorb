package com.roubao.autopilot.macro.record

class MacroRecorder {
    private val records = mutableListOf<TraceRecord>()

    fun record(
        before: UiSnapshot,
        action: RawAction,
        targetNode: UiNodeSnapshot?,
        after: UiSnapshot
    ) {
        records.add(
            TraceRecord(
                index = records.size,
                before = before,
                action = action,
                targetNode = targetNode,
                after = after
            )
        )
    }

    fun finish(instruction: String, appPackage: String?): RecordedTrace {
        return RecordedTrace(
            id = "trace_${System.currentTimeMillis()}",
            instruction = instruction,
            appPackage = appPackage,
            appName = null,
            createdAt = System.currentTimeMillis(),
            records = records.toList(),
            finalVerify = null
        )
    }

    fun clear() {
        records.clear()
    }
}
