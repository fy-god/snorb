package com.macropilot.app.ai

import android.content.Context

class AiAssistConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai_assist", Context.MODE_PRIVATE)

    fun load(): AiAssistConfig {
        val key = prefs.getString(KEY_API_KEY, "").orEmpty()
        val provider = runCatching {
            AiProvider.valueOf(prefs.getString(KEY_PROVIDER, AiProvider.DASHSCOPE_COMPAT_CHAT.name).orEmpty())
        }.getOrDefault(AiProvider.DASHSCOPE_COMPAT_CHAT)
        val endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty()
        val rawModel = prefs.getString(KEY_MODEL, "").orEmpty()
        return AiAssistConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            provider = provider,
            endpoint = endpoint,
            model = normalizeModel(endpoint, provider, rawModel),
            apiKeyPresent = key.isNotBlank()
        )
    }

    fun save(
        enabled: Boolean,
        endpoint: String,
        model: String,
        apiKey: String?,
        provider: AiProvider = load().provider
    ) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_ENDPOINT, endpoint)
            .putString(KEY_MODEL, model)
            .apply {
                if (apiKey != null) putString(KEY_API_KEY, apiKey)
            }
            .apply()
    }

    fun apiKey(): String {
        return prefs.getString(KEY_API_KEY, "").orEmpty()
    }

    private fun normalizeModel(endpoint: String, provider: AiProvider, model: String): String {
        if (provider == AiProvider.DASHSCOPE_COMPAT_CHAT && model.isBlank()) {
            return AiAssistConfig.DEFAULT_DASHSCOPE_VL_MODEL
        }
        if (provider == AiProvider.OPENAI_RESPONSES && model.isBlank()) {
            return "gpt-4.1-mini"
        }
        return model
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "api_key"
    }
}
