package com.macropilot.app.ai

import android.content.Context
import org.json.JSONObject
import java.io.File

class AiAssistLogStore(private val context: Context) {
    private val rootDir: File
        get() = File(context.filesDir, "macro_v2/ai/assist_logs")

    fun save(request: AiAssistRequest, response: AiAssistResponse, durationMs: Long): File {
        rootDir.mkdirs()
        val file = File(rootDir, "ai_${System.currentTimeMillis()}.json")
        file.writeText(
            JSONObject()
                .put("useCase", request.useCase.name)
                .put("runtimePath", request.runtimePath)
                .put("redacted", request.redacted)
                .put("userVisible", request.userVisible)
                .put("status", response.status.name)
                .put("message", response.message)
                .put("provider", response.provider?.name)
                .put("httpStatus", response.httpStatus)
                .put("durationMs", durationMs)
                .put("inputPreview", request.inputJson.toString().take(2000))
                .put("outputPreview", response.outputJson?.toString()?.take(2000))
                .put("timestamp", System.currentTimeMillis())
                .toString(2)
        )
        return file
    }

    fun latest(limit: Int = 50): List<File> {
        if (!rootDir.isDirectory) return emptyList()
        return rootDir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit.coerceIn(1, 500))
            .orEmpty()
    }

    fun deleteAll(): Int {
        if (!rootDir.isDirectory) return 0
        return rootDir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.count { it.delete() }
            ?: 0
    }
}
