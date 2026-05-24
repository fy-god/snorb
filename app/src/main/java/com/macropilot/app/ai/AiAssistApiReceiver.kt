package com.macropilot.app.ai

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import com.macropilot.app.factory.AiPatchReview
import com.macropilot.app.store.CandidateKnowledgeStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AiAssistApiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in API_ACTIONS) return

        val pending = goAsync()
        Thread {
            val response = runCatching {
                handle(context.applicationContext, intent)
            }.getOrElse { error ->
                JSONObject()
                    .put("ok", false)
                    .put("action", action)
                    .put("error", error.message ?: error.javaClass.simpleName)
                    .put("timestamp", System.currentTimeMillis())
            }
            respond(pending, response)
        }.start()
    }

    private fun handle(context: Context, intent: Intent): JSONObject {
        return when (intent.action) {
            ACTION_GET_CONFIG -> getConfig(context)
            ACTION_SAVE_CONFIG -> saveConfig(context, intent)
            ACTION_DISABLE -> disable(context)
            ACTION_REQUEST -> request(context, intent)
            ACTION_SUGGEST_UI_PATCH -> suggestUiPatch(context, intent)
            ACTION_IMPORT_PATCH_CANDIDATE -> importPatchCandidate(context, intent)
            ACTION_REVIEW_PATCH -> reviewPatch(context, intent)
            ACTION_APPLY_PATCH -> applyPatch(context, intent)
            ACTION_DRY_RUN_CANDIDATE_MODULE -> dryRunCandidateModule(context, intent)
            ACTION_LIST_CANDIDATE_MODULES -> listCandidateModules(context, intent)
            ACTION_TEST_POLICY -> testPolicy(context, intent)
            ACTION_LIST_LOGS -> listLogs(context, intent)
            ACTION_DELETE_LOGS -> deleteLogs(context)
            else -> failed(intent.action.orEmpty(), "Unknown AI API action")
        }
    }

    private fun getConfig(context: Context): JSONObject {
        val config = AiAssistConfigStore(context).load()
        return ok(ACTION_GET_CONFIG)
            .put("enabled", config.enabled)
            .put("provider", config.provider.name)
            .put("endpoint", config.endpoint)
            .put("model", config.model)
            .put("apiKeyPresent", config.apiKeyPresent)
            .put("openAiResponsesEndpoint", AiAssistConfig.OPENAI_RESPONSES_ENDPOINT)
            .put("dashScopeChatEndpoint", AiAssistConfig.DASHSCOPE_CHAT_ENDPOINT)
            .put("runtimeAllowed", false)
            .put("allowedUseCases", JSONArray(AiAssistUseCase.entries.map { it.name }))
    }

    private fun saveConfig(context: Context, intent: Intent): JSONObject {
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
        val provider = parseProvider(intent.getStringExtra(EXTRA_PROVIDER))
        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        val model = intent.getStringExtra(EXTRA_MODEL).orEmpty()
        val apiKey = if (intent.hasExtra(EXTRA_API_KEY)) intent.getStringExtra(EXTRA_API_KEY) else null
        AiAssistConfigStore(context).save(enabled, endpoint, model, apiKey, provider)
        val config = AiAssistConfigStore(context).load()
        return ok(ACTION_SAVE_CONFIG)
            .put("enabled", config.enabled)
            .put("provider", config.provider.name)
            .put("endpoint", config.endpoint)
            .put("model", config.model)
            .put("apiKeyPresent", config.apiKeyPresent)
    }

    private fun disable(context: Context): JSONObject {
        val current = AiAssistConfigStore(context).load()
        AiAssistConfigStore(context).save(
            enabled = false,
            endpoint = current.endpoint,
            model = current.model,
            apiKey = null,
            provider = current.provider
        )
        return ok(ACTION_DISABLE)
            .put("enabled", false)
            .put("provider", current.provider.name)
            .put("endpoint", current.endpoint)
            .put("model", current.model)
            .put("apiKeyPresent", current.apiKeyPresent)
    }

    private fun request(context: Context, intent: Intent): JSONObject {
        val request = buildRequest(intent)
        if (request.runtimePath) {
            return policyOnly(ACTION_REQUEST, request)
        }
        val response = AiAssistClient(context).request(request)
        return ok(ACTION_REQUEST)
            .put("useCase", request.useCase.name)
            .put("status", response.status.name)
            .put("message", response.message)
            .put("provider", response.provider?.name)
            .put("httpStatus", response.httpStatus)
            .put("output", response.outputJson)
    }

    private fun suggestUiPatch(context: Context, intent: Intent): JSONObject {
        val result = AiUiModulePatchFactory(context).suggestPatch(
            task = intent.getStringExtra(EXTRA_TASK).orEmpty(),
            screenHint = intent.getStringExtra(EXTRA_SCREEN_HINT),
            elementGoal = intent.getStringExtra(EXTRA_ELEMENT_GOAL),
            maxNodes = intent.getIntExtra(EXTRA_MAX_NODES, 120)
        )
        return ok(ACTION_SUGGEST_UI_PATCH)
            .put("status", result.status.name)
            .put("message", result.message)
            .put("candidateUsable", result.ok)
            .put("file", result.file?.absolutePath)
    }

    private fun importPatchCandidate(context: Context, intent: Intent): JSONObject {
        val output = outputJsonFromIntent(intent)
            ?: return failed(ACTION_IMPORT_PATCH_CANDIDATE, "Missing or invalid outputJson")
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            ?: output.optString("packageName")
        if (packageName.isBlank()) {
            return failed(ACTION_IMPORT_PATCH_CANDIDATE, "Missing packageName")
        }
        val store = CandidateKnowledgeStore(context)
        store.ensureCreated()
        val id = "imported_patch_${System.currentTimeMillis()}"
        val file = File(store.candidatePatchDir, "$id.json")
        file.writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("id", id)
                .put("type", "AiUiModulePatchCandidate")
                .put("packageName", packageName)
                .put("task", intent.getStringExtra(EXTRA_TASK))
                .put("screenHint", intent.getStringExtra(EXTRA_SCREEN_HINT))
                .put("elementGoal", intent.getStringExtra(EXTRA_ELEMENT_GOAL))
                .put("aiStatus", "IMPORTED")
                .put("aiMessage", "Imported through AI API")
                .put("candidateUsable", true)
                .put("redactionStatus", "basic_redacted")
                .put("promotionStatus", "CANDIDATE_ONLY")
                .put("runtimeExecutable", false)
                .put("input", JSONObject().put("source", "AI_IMPORT_PATCH_CANDIDATE"))
                .put("output", output)
                .put("createdAt", System.currentTimeMillis())
                .toString(2)
        )
        return ok(ACTION_IMPORT_PATCH_CANDIDATE)
            .put("id", id)
            .put("file", file.absolutePath)
    }

    private fun reviewPatch(context: Context, intent: Intent): JSONObject {
        val report = AiPatchReview(context).reviewPatch(intent.patchIdOrPath())
        return ok(ACTION_REVIEW_PATCH)
            .put("approved", report.approved)
            .put("reason", report.reason)
            .put("file", report.file?.absolutePath)
            .put("warnings", JSONArray(report.warnings))
            .put("errors", JSONArray(report.errors))
    }

    private fun applyPatch(context: Context, intent: Intent): JSONObject {
        val result = AiPatchReview(context).applyPatch(intent.patchIdOrPath())
        return ok(ACTION_APPLY_PATCH)
            .put("applied", result.applied)
            .put("reason", result.reason)
            .put("moduleFile", result.moduleFile?.absolutePath)
            .put("reviewApproved", result.review.approved)
            .put("reviewWarnings", JSONArray(result.review.warnings))
            .put("reviewErrors", JSONArray(result.review.errors))
    }

    private fun dryRunCandidateModule(context: Context, intent: Intent): JSONObject {
        val path = intent.getStringExtra(EXTRA_MODULE_PATH).orEmpty()
        val report = AiPatchReview(context).dryRunCandidateModule(path)
        return ok(ACTION_DRY_RUN_CANDIDATE_MODULE)
            .put("passed", report.passed)
            .put("reason", report.reason)
            .put("file", report.file?.absolutePath)
            .put("warnings", JSONArray(report.warnings))
            .put("errors", JSONArray(report.errors))
    }

    private fun listCandidateModules(context: Context, intent: Intent): JSONObject {
        val limit = intent.getIntExtra(EXTRA_LIMIT, 100).coerceIn(1, 500)
        val modules = AiPatchReview(context).listCandidateModules(limit)
        return ok(ACTION_LIST_CANDIDATE_MODULES)
            .put("count", modules.size)
            .put("modules", JSONArray(modules))
    }

    private fun testPolicy(context: Context, intent: Intent): JSONObject {
        val request = buildRequest(intent)
        val blocked = AiAssistPolicy().evaluate(request)
        if (blocked != null) {
            AiAssistLogStore(context).save(request, blocked, 0L)
            return ok(ACTION_TEST_POLICY)
                .put("allowed", false)
                .put("status", blocked.status.name)
                .put("message", blocked.message)
        }
        return ok(ACTION_TEST_POLICY)
            .put("allowed", true)
            .put("status", "ALLOWED")
            .put("message", "Policy allowed this request")
    }

    private fun policyOnly(action: String, request: AiAssistRequest): JSONObject {
        val response = AiAssistPolicy().evaluate(request)
            ?: AiAssistResponse(AiAssistStatus.BLOCKED, "Runtime AI request rejected by API boundary")
        return ok(action)
            .put("useCase", request.useCase.name)
            .put("status", response.status.name)
            .put("message", response.message)
            .put("provider", response.provider?.name)
            .put("httpStatus", response.httpStatus)
            .put("output", response.outputJson)
    }

    private fun listLogs(context: Context, intent: Intent): JSONObject {
        val limit = intent.getIntExtra(EXTRA_LIMIT, 50).coerceIn(1, 500)
        val files = AiAssistLogStore(context).latest(limit).map { logJson(it) }
        return ok(ACTION_LIST_LOGS)
            .put("count", files.size)
            .put("logs", JSONArray(files))
    }

    private fun deleteLogs(context: Context): JSONObject {
        val deleted = AiAssistLogStore(context).deleteAll()
        return ok(ACTION_DELETE_LOGS).put("deleted", deleted)
    }

    private fun buildRequest(intent: Intent): AiAssistRequest {
        val useCaseName = intent.getStringExtra(EXTRA_USE_CASE).orEmpty()
        val useCase = runCatching { AiAssistUseCase.valueOf(useCaseName) }
            .getOrDefault(AiAssistUseCase.FAILURE_SUMMARY)
        val inputJson = inputJsonFromIntent(intent)
            ?: JSONObject().put("note", "empty_ai_assist_request")
        return AiAssistRequest(
            useCase = useCase,
            inputJson = inputJson,
            redacted = intent.getBooleanExtra(EXTRA_REDACTED, false),
            userVisible = intent.getBooleanExtra(EXTRA_USER_VISIBLE, false),
            runtimePath = intent.getBooleanExtra(EXTRA_RUNTIME_PATH, false)
        )
    }

    private fun inputJsonFromIntent(intent: Intent): JSONObject? {
        val direct = intent.getStringExtra(EXTRA_INPUT_JSON)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (direct != null) return direct
        val decoded = intent.getStringExtra(EXTRA_INPUT_JSON_BASE64)
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                runCatching {
                    String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()
            }
        return decoded?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun outputJsonFromIntent(intent: Intent): JSONObject? {
        val direct = intent.getStringExtra(EXTRA_OUTPUT_JSON)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (direct != null) return direct
        val decoded = intent.getStringExtra(EXTRA_OUTPUT_JSON_BASE64)
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                runCatching {
                    String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()
            }
        return decoded?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun parseProvider(value: String?): AiProvider {
        return runCatching {
            AiProvider.valueOf(value.orEmpty().ifBlank { AiProvider.CUSTOM_JSON.name })
        }.getOrDefault(AiProvider.CUSTOM_JSON)
    }

    private fun logJson(file: File): JSONObject {
        val body = runCatching { JSONObject(file.readText()) }.getOrNull()
        return JSONObject()
            .put("file", file.absolutePath)
            .put("useCase", body?.optString("useCase"))
            .put("provider", body?.optString("provider"))
            .put("httpStatus", body?.optInt("httpStatus"))
            .put("runtimePath", body?.optBoolean("runtimePath"))
            .put("redacted", body?.optBoolean("redacted"))
            .put("userVisible", body?.optBoolean("userVisible"))
            .put("status", body?.optString("status"))
            .put("message", body?.optString("message"))
            .put("durationMs", body?.optLong("durationMs"))
            .put("timestamp", body?.optLong("timestamp", file.lastModified()) ?: file.lastModified())
            .put("inputPreview", body?.optString("inputPreview"))
            .put("outputPreview", body?.optString("outputPreview"))
    }

    private fun ok(action: String): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("timestamp", System.currentTimeMillis())
    }

    private fun failed(action: String, error: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("action", action)
            .put("error", error)
            .put("timestamp", System.currentTimeMillis())
    }

    private fun respond(pending: PendingResult, response: JSONObject) {
        val body = response.toString()
        pending.setResultCode(if (response.optBoolean("ok")) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        pending.setResultData(body)
        pending.setResultExtras(Bundle().apply { putString(EXTRA_RESULT_JSON, body) })
        Log.i(TAG, "AI_API_RESPONSE $body")
        pending.finish()
    }

    companion object {
        const val ACTION_GET_CONFIG = "com.macropilot.app.AI_GET_CONFIG"
        const val ACTION_SAVE_CONFIG = "com.macropilot.app.AI_SAVE_CONFIG"
        const val ACTION_DISABLE = "com.macropilot.app.AI_DISABLE"
        const val ACTION_REQUEST = "com.macropilot.app.AI_REQUEST"
        const val ACTION_SUGGEST_UI_PATCH = "com.macropilot.app.AI_SUGGEST_UI_PATCH"
        const val ACTION_IMPORT_PATCH_CANDIDATE = "com.macropilot.app.AI_IMPORT_PATCH_CANDIDATE"
        const val ACTION_REVIEW_PATCH = "com.macropilot.app.AI_REVIEW_PATCH"
        const val ACTION_APPLY_PATCH = "com.macropilot.app.AI_APPLY_PATCH"
        const val ACTION_DRY_RUN_CANDIDATE_MODULE = "com.macropilot.app.AI_DRY_RUN_CANDIDATE_MODULE"
        const val ACTION_LIST_CANDIDATE_MODULES = "com.macropilot.app.AI_LIST_CANDIDATE_MODULES"
        const val ACTION_TEST_POLICY = "com.macropilot.app.AI_TEST_POLICY"
        const val ACTION_LIST_LOGS = "com.macropilot.app.AI_LIST_LOGS"
        const val ACTION_DELETE_LOGS = "com.macropilot.app.AI_DELETE_LOGS"

        const val EXTRA_RESULT_JSON = "result_json"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_MODEL = "model"
        const val EXTRA_API_KEY = "apiKey"
        const val EXTRA_USE_CASE = "useCase"
        const val EXTRA_INPUT_JSON = "inputJson"
        const val EXTRA_INPUT_JSON_BASE64 = "inputJsonBase64"
        const val EXTRA_OUTPUT_JSON = "outputJson"
        const val EXTRA_OUTPUT_JSON_BASE64 = "outputJsonBase64"
        const val EXTRA_REDACTED = "redacted"
        const val EXTRA_USER_VISIBLE = "userVisible"
        const val EXTRA_RUNTIME_PATH = "runtimePath"
        const val EXTRA_LIMIT = "limit"
        const val EXTRA_TASK = "task"
        const val EXTRA_SCREEN_HINT = "screenHint"
        const val EXTRA_ELEMENT_GOAL = "elementGoal"
        const val EXTRA_MAX_NODES = "maxNodes"
        const val EXTRA_PATCH_ID = "patchId"
        const val EXTRA_PATCH_PATH = "patchPath"
        const val EXTRA_MODULE_PATH = "modulePath"
        const val EXTRA_PACKAGE_NAME = "packageName"

        private const val TAG = "MacroPilotAiApi"

        private val API_ACTIONS = setOf(
            ACTION_GET_CONFIG,
            ACTION_SAVE_CONFIG,
            ACTION_DISABLE,
            ACTION_REQUEST,
            ACTION_SUGGEST_UI_PATCH,
            ACTION_IMPORT_PATCH_CANDIDATE,
            ACTION_REVIEW_PATCH,
            ACTION_APPLY_PATCH,
            ACTION_DRY_RUN_CANDIDATE_MODULE,
            ACTION_LIST_CANDIDATE_MODULES,
            ACTION_TEST_POLICY,
            ACTION_LIST_LOGS,
            ACTION_DELETE_LOGS
        )
    }

    private fun Intent.patchIdOrPath(): String {
        return getStringExtra(EXTRA_PATCH_PATH)
            ?: getStringExtra(EXTRA_PATCH_ID)
            ?: ""
    }
}
