package com.roubao.autopilot.agent.multi

import com.roubao.autopilot.data.SettingsManager

data class AgentApiConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        fun from(settingsManager: SettingsManager): AgentApiConfig {
            val settings = settingsManager.settings.value
            return AgentApiConfig(
                apiKey = settings.apiKey.ifBlank { settingsManager.getAgentApiKey() },
                baseUrl = settings.baseUrl.ifBlank { settingsManager.getAgentBaseUrl() },
                model = settings.model
            )
        }
    }
}
