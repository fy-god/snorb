package com.roubao.autopilot.macro.store

import android.content.Context
import com.roubao.autopilot.macro.ai.AiAssistEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AiAssistLogStore(
    private val context: Context
) {
    fun append(appPackage: String, event: AiAssistEvent) {
        val dir = File(context.filesDir, "macro_v2/apps/$appPackage/ai_assist_logs")
        dir.mkdirs()
        val file = File(dir, "${event.timestamp}.json")
        file.writeText(eventToJson(event).toString(2))
    }

    private fun eventToJson(event: AiAssistEvent): JSONObject = JSONObject().apply {
        put("type", event.type.name)
        put("title", event.title)
        put("inputSummary", event.inputSummary)
        put("outputSummary", event.outputSummary)
        put("accepted", event.accepted)
        put("timestamp", event.timestamp)
    }
}
