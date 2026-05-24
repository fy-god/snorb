package com.macropilot.app.ai

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AiAssistClient(
    private val context: Context,
    private val configStore: AiAssistConfigStore = AiAssistConfigStore(context),
    private val policy: AiAssistPolicy = AiAssistPolicy(),
    private val logStore: AiAssistLogStore = AiAssistLogStore(context)
) {
    fun request(request: AiAssistRequest): AiAssistResponse {
        val startedAt = System.currentTimeMillis()
        val response = policy.evaluate(request) ?: callProvider(request)
        logStore.save(request, response, System.currentTimeMillis() - startedAt)
        return response
    }

    private fun callProvider(request: AiAssistRequest): AiAssistResponse {
        val config = configStore.load()
        if (!config.enabled) {
            return AiAssistResponse(AiAssistStatus.DISABLED, "AI Assist is disabled", provider = config.provider)
        }
        if (config.endpoint.isBlank() || config.model.isBlank() || !config.apiKeyPresent) {
            val endpointMissing = when (config.provider) {
                AiProvider.OPENAI_RESPONSES,
                AiProvider.OPENAI_COMPAT_CHAT,
                AiProvider.DASHSCOPE_COMPAT_CHAT -> false
                AiProvider.CUSTOM_JSON -> config.endpoint.isBlank()
            }
            if (endpointMissing || config.model.isBlank() || !config.apiKeyPresent) {
                return AiAssistResponse(AiAssistStatus.DISABLED, "AI Assist config is incomplete", provider = config.provider)
            }
        }

        return runCatching {
            val endpoint = when (config.provider) {
                AiProvider.OPENAI_RESPONSES -> config.endpoint.ifBlank { AiAssistConfig.OPENAI_RESPONSES_ENDPOINT }
                AiProvider.OPENAI_COMPAT_CHAT -> config.endpoint
                AiProvider.DASHSCOPE_COMPAT_CHAT -> config.endpoint.ifBlank { AiAssistConfig.DASHSCOPE_CHAT_ENDPOINT }
                AiProvider.CUSTOM_JSON -> config.endpoint
            }
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = if (request.runtimePath) 20_000 else 15_000
                readTimeout = if (request.runtimePath) 90_000 else 45_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${configStore.apiKey()}")
            }
            val selectedModel = modelForRequest(config, request)
            val payload = when (config.provider) {
                AiProvider.OPENAI_RESPONSES -> openAiResponsesPayload(selectedModel, request)
                AiProvider.OPENAI_COMPAT_CHAT -> openAiCompatChatPayload(selectedModel, request)
                AiProvider.DASHSCOPE_COMPAT_CHAT -> openAiCompatChatPayload(selectedModel, request)
                AiProvider.CUSTOM_JSON -> JSONObject()
                    .put("model", selectedModel)
                    .put("useCase", request.useCase.name)
                    .put("input", request.inputJson)
            }
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
            }
            val httpStatus = connection.responseCode
            val body = readBody(if (httpStatus in 200..299) connection.inputStream else connection.errorStream)
            if (httpStatus !in 200..299) {
                return@runCatching AiAssistResponse(
                    status = AiAssistStatus.FAILED,
                    message = "AI provider HTTP $httpStatus: ${body.take(240)}",
                    provider = config.provider,
                    httpStatus = httpStatus
                )
            }
            val json = when (config.provider) {
                AiProvider.OPENAI_RESPONSES -> parseOpenAiResponsesBody(body)
                AiProvider.OPENAI_COMPAT_CHAT -> parseOpenAiCompatChatBody(body, config.provider)
                AiProvider.DASHSCOPE_COMPAT_CHAT -> parseOpenAiCompatChatBody(body, config.provider)
                AiProvider.CUSTOM_JSON -> runCatching { JSONObject(body) }.getOrElse {
                    JSONObject().put("text", body)
                }
            }
            AiAssistResponse(
                status = AiAssistStatus.SUCCESS,
                message = "AI Assist request completed",
                outputJson = json,
                provider = config.provider,
                httpStatus = httpStatus
            )
        }.getOrElse { error ->
            AiAssistResponse(
                status = AiAssistStatus.FAILED,
                message = error.message ?: error.javaClass.simpleName,
                provider = config.provider
            )
        }
    }

    private fun modelForRequest(config: AiAssistConfig, request: AiAssistRequest): String {
        if (config.model.isNotBlank()) return config.model
        if (config.provider == AiProvider.DASHSCOPE_COMPAT_CHAT) return AiAssistConfig.DEFAULT_DASHSCOPE_VL_MODEL
        return config.model
    }

    private fun openAiResponsesPayload(model: String, request: AiAssistRequest): JSONObject {
        val instructions = if (request.runtimePath) {
            runtimeSystemPrompt(request.useCase)
        } else {
            "You are MacroPilot Skill Factory AI Assist. " +
                "Return one JSON object only. Generate candidate Skill JSON, not executable phone actions. " +
                "Never set status to APPROVED. Split skills into atomic_skill and macro_skill. " +
                "Coordinates are fallback only, and coordinate-only results must be low confidence."
        }
        return JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", JSONObject()
                .put("useCase", request.useCase.name)
                .put("input", request.inputJson)
                .toString())
            .put("store", false)
    }

    private fun openAiCompatChatPayload(model: String, request: AiAssistRequest): JSONObject {
        val system = if (request.runtimePath) {
            runtimeSystemPrompt(request.useCase)
        } else {
            "You are MacroPilot Skill Factory AI Assist. " +
                "Return one JSON object only. Generate candidate Skill JSON, not executable phone actions. " +
                "Never set status to APPROVED. Split skills into atomic_skill and macro_skill. " +
                "Coordinates are fallback only, and coordinate-only results must be low confidence. " +
                "The JSON object should contain intentDraft and candidates when generating skills."
        }
        val user = JSONObject()
            .put("useCase", request.useCase.name)
            .put("input", request.inputJson)
            .toString()
        return JSONObject()
            .put("model", model)
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0)
    }

    private fun runtimeSystemPrompt(useCase: AiAssistUseCase): String {
        val phase = when (useCase) {
            AiAssistUseCase.RUNTIME_CHECKPOINT -> "CHECKPOINT"
            AiAssistUseCase.RUNTIME_REPLAN -> "REPLAN_AFTER_FAILURE"
            AiAssistUseCase.RUNTIME_FINAL_SUMMARY -> "FINAL_SUMMARY"
            else -> "PLAN"
        }
        return "You are MacroPilot phone-side AI controller. Phase=$phase. Return one JSON object only. " +
            "The app itself will execute your JSON through Accessibility/IME, so every decision must be visible and auditable. " +
            "Allowed executable step types: OpenApp, ClickVisibleText, ClickSelector, TypeIntoFirstInput, TypeIntoFocusedInput, OpenHupuPostEditor, FillHupuPostEditor, Back, Wait, ClickCoordinate, LongPressCoordinate, ScrollForward, HandleReactiveOverlays, FindEntryByBottomTabSweep, EnsureHupuSectionSelected, VerifyHupuPostPublished. " +
            "Prefer accessibility text/selector actions over coordinates. Coordinates must be normalized 0..1 and used only as low-confidence fallback. " +
            "ClickSelector supports only simple bracket selectors such as [text='...'], [text*='...'], [desc='...'], [desc*='...'], [resourceId='...'], [class='...'][clickable=true]. Never output CSS hierarchy, XPath, >, spaces, or :first-child selectors. " +
            "When currentUi.clickTargets or currentUi.videoCandidates are provided, choose from those candidates. For video selection, cite targetCandidateIndex, text/desc/bounds, and use ClickCoordinate with the candidate center. If no candidate is visible or the page is loading, return Wait or ScrollForward only. " +
            "For complex app tasks, do not pretend the goal is complete unless the current UI evidence supports it. " +
            "Use currentUi.blockingDialogs as hard evidence: ad_or_splash means close or skip the overlay before continuing; hupu_section_required means Hupu posting is blocked by forum/section selection, so choose a section before trying to publish again. Never keep returning Wait for a visible blocking dialog. " +
            "If an entry for posting, publishing, search, or messages is not visible, use FindEntryByBottomTabSweep with entryAliases; it returns to app home, sweeps bottom tabs, and clicks the matching entry before replanning. " +
            "At search-result/detail-page ambiguity, after screen transitions, after low-confidence coordinate use, or after any failed action, return a checkpoint/replan decision. " +
            "For CHECKPOINT and REPLAN_AFTER_FAILURE, diagnose the current screen first, then return only the next 1-5 recovery steps, not the whole old plan. " +
            "If currentUi.serviceConnected=false, say that phone control is unavailable; do not mark the goal complete and do not return OpenApp/Wait as a fake recovery for click/search/like/comment tasks. " +
            "If the goal is already complete, set decision.goalCompleted=true and return an empty steps array. " +
            "If you include candidate skills, split them into fine-grained atomic_skill and macro_skill JSON. " +
            "Do not claim APPROVED status; use CANDIDATE for generated skills."
    }

    private fun parseOpenAiResponsesBody(body: String): JSONObject {
        val responseJson = runCatching { JSONObject(body) }.getOrElse {
            return JSONObject().put("rawText", body)
        }
        val outputText = responseJson.optString("output_text").takeIf { it.isNotBlank() }
            ?: extractOutputText(responseJson)
        val parsed = outputText
            ?.let { text -> parseJsonObjectFromText(text) }
            ?: responseJson.optJSONObject("output")
            ?: responseJson
        return JSONObject()
            .put("provider", AiProvider.OPENAI_RESPONSES.name)
            .put("responseId", responseJson.optString("id"))
            .put("model", responseJson.optString("model"))
            .put("output", parsed)
            .put("rawOutputText", outputText)
    }

    private fun parseOpenAiCompatChatBody(body: String, provider: AiProvider): JSONObject {
        val responseJson = runCatching { JSONObject(body) }.getOrElse {
            return JSONObject().put("rawText", body)
        }
        val choice = responseJson.optJSONArray("choices")?.optJSONObject(0)
        val content = choice
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
        val parsed = content
            ?.let { text -> parseJsonObjectFromText(text) }
            ?: JSONObject()
                .put("text", content)
                .put("raw", responseJson)
        return JSONObject()
            .put("provider", provider.name)
            .put("responseId", responseJson.optString("id"))
            .put("model", responseJson.optString("model"))
            .put("output", parsed)
            .put("rawOutputText", content)
    }

    private fun parseJsonObjectFromText(text: String): JSONObject? {
        val trimmed = text.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        runCatching { return JSONObject(trimmed) }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { return JSONObject(trimmed.substring(start, end + 1)) }
        }
        return null
    }

    private fun extractOutputText(responseJson: JSONObject): String? {
        val output = responseJson.optJSONArray("output") ?: return null
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val contentItem = content.optJSONObject(j) ?: continue
                val text = contentItem.optString("text").takeIf { it.isNotBlank() }
                    ?: contentItem.optString("content").takeIf { it.isNotBlank() }
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }

    private fun readBody(stream: InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(stream.reader()).use { it.readText() }
    }
}
