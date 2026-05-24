package com.roubao.autopilot.macro.store

import android.content.Context
import com.roubao.autopilot.agent.Action
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RecordedTraceStore(
    private val context: Context
) {
    fun savePendingActionTrace(
        instruction: String,
        appPackage: String?,
        actions: List<Action>,
        reason: String
    ): String {
        val id = "pending_${System.currentTimeMillis()}"
        val dir = File(context.filesDir, "macro_v2/pending_traces")
        dir.mkdirs()
        val file = File(dir, "$id.json")
        val obj = JSONObject().apply {
            put("id", id)
            put("instruction", instruction)
            put("appPackage", appPackage)
            put("createdAt", System.currentTimeMillis())
            put("reason", reason)
            put("actions", JSONArray(actions.map(::actionToJson)))
        }
        file.writeText(obj.toString(2))
        return id
    }

    private fun actionToJson(action: Action): JSONObject = JSONObject().apply {
        put("type", action.type)
        action.x?.let { put("x", it) }
        action.y?.let { put("y", it) }
        action.x2?.let { put("x2", it) }
        action.y2?.let { put("y2", it) }
        action.text?.let { put("text", it) }
        action.button?.let { put("button", it) }
        action.duration?.let { put("duration", it) }
        action.direction?.let { put("direction", it) }
        action.status?.let { put("status", it) }
    }
}
