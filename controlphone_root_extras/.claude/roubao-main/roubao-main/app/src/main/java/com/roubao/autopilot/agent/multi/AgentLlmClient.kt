package com.roubao.autopilot.agent.multi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AgentLlmClient(
    private val config: AgentApiConfig,
    private val maxHistoryMessages: Int = 20
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val historyLock = Any()
    private val history = mutableListOf<JSONObject>()

    fun reset() {
        synchronized(historyLock) {
            history.clear()
        }
    }

    suspend fun complete(prompt: String, systemPrompt: String? = null): Result<String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalStateException("AGENT_API_KEY / AGENT_BASE_URL / AGENT_MODEL 未配置"))
        }

        val messages = synchronized(historyLock) {
            addSystemPromptOnce(systemPrompt)
            history.add(message("user", prompt))
            trimHistory()
            JSONArray(history.map { JSONObject(it.toString()) })
        }

        val requestBody = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("temperature", 0.0)
        }

        val request = Request.Builder()
            .url("${normalizeUrl(config.baseUrl)}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Agent API error ${response.code}: $body"))
                }
                val choices = JSONObject(body).optJSONArray("choices") ?: JSONArray()
                val content = choices.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                if (content.isBlank()) {
                    Result.failure(Exception("Agent API returned empty content"))
                } else {
                    synchronized(historyLock) {
                        history.add(message("assistant", content))
                        trimHistory()
                    }
                    Result.success(content)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun snapshotMessages(): JSONArray = synchronized(historyLock) {
        JSONArray(history.map { JSONObject(it.toString()) })
    }

    private fun addSystemPromptOnce(systemPrompt: String?) {
        if (systemPrompt.isNullOrBlank()) return
        if (history.any { it.optString("role") == "system" }) return
        history.add(0, message("system", systemPrompt))
    }

    private fun trimHistory() {
        val limit = maxHistoryMessages.coerceAtLeast(2)
        while (history.size > limit) {
            val removeIndex = if (history.firstOrNull()?.optString("role") == "system" && history.size > 1) 1 else 0
            history.removeAt(removeIndex)
        }
    }

    private fun message(role: String, content: String): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", content)
    }

    private fun normalizeUrl(url: String): String {
        var normalized = url.trim().removeSuffix("/").removeSuffix("/chat/completions")
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        return normalized
    }
}
