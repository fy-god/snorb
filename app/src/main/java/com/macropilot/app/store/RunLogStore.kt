package com.macropilot.app.store

import android.content.Context
import com.macropilot.app.model.MacroResult
import org.json.JSONObject
import java.io.File

class RunLogStore(private val context: Context) {
    private val rootDir: File
        get() = File(context.filesDir, "macro_v2/run_logs")

    private val maxRunLogs = 200

    fun save(
        templateId: String,
        result: MacroResult,
        durationMs: Long,
        note: String
    ): File {
        rootDir.mkdirs()
        val id = "run_${System.currentTimeMillis()}"
        val file = File(rootDir, "$id.json")
        file.writeText(
            JSONObject()
                .put("id", id)
                .put("templateId", templateId)
                .put("status", result.status.name)
                .put("message", result.message)
                .put("confidence", result.confidence.name)
                .put("durationMs", durationMs)
                .put("note", note)
                .put("timestamp", System.currentTimeMillis())
                .toString(2)
        )
        pruneOldFiles()
        return file
    }

    fun latest(): File? {
        if (!rootDir.isDirectory) return null
        return rootDir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.maxByOrNull { it.lastModified() }
    }

    fun latestFiles(limit: Int = 50): List<File> {
        if (!rootDir.isDirectory) return emptyList()
        return rootDir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            .orEmpty()
    }

    private fun pruneOldFiles() {
        if (!rootDir.isDirectory) return
        val files = rootDir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (files.size <= maxRunLogs) return
        files.drop(maxRunLogs).forEach { file ->
            runCatching { file.delete() }
        }
    }
}
