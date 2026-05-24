package com.macropilot.app.ai

import org.json.JSONObject

enum class AiAssistUseCase {
    TRACE_GENERALIZATION,
    UI_MODULE_PATCH,
    ELEMENT_LABELING,
    FAILURE_SUMMARY,
    RUNTIME_PLAN,
    RUNTIME_CHECKPOINT,
    RUNTIME_REPLAN,
    RUNTIME_FINAL_SUMMARY
}

enum class AiAssistStatus {
    DISABLED,
    BLOCKED,
    SUCCESS,
    FAILED
}

enum class AiProvider {
    CUSTOM_JSON,
    OPENAI_COMPAT_CHAT,
    OPENAI_RESPONSES,
    DASHSCOPE_COMPAT_CHAT
}

data class AiAssistRequest(
    val useCase: AiAssistUseCase,
    val inputJson: JSONObject,
    val redacted: Boolean,
    val userVisible: Boolean,
    val runtimePath: Boolean = false
)

data class AiAssistResponse(
    val status: AiAssistStatus,
    val message: String,
    val outputJson: JSONObject? = null,
    val provider: AiProvider? = null,
    val httpStatus: Int? = null
)

data class AiAssistConfig(
    val enabled: Boolean,
    val provider: AiProvider,
    val endpoint: String,
    val model: String,
    val apiKeyPresent: Boolean
) {
    companion object {
        const val DEEPSEEK_CHAT_ENDPOINT = "https://api.deepseek.com/chat/completions"
        const val OPENAI_RESPONSES_ENDPOINT = "https://api.openai.com/v1/responses"
        const val DASHSCOPE_CHAT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        const val DEFAULT_DASHSCOPE_VL_MODEL = "qwen3-vl-flash"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-pro"
    }
}
