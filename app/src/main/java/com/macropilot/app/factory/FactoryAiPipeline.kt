package com.macropilot.app.factory

import android.content.Context
import com.macropilot.app.ai.AiAssistConfig
import com.macropilot.app.ai.AiAssistConfigStore
import com.macropilot.app.ai.AiAssistClient
import com.macropilot.app.ai.AiAssistRequest
import com.macropilot.app.ai.AiAssistStatus
import com.macropilot.app.ai.AiAssistUseCase
import com.macropilot.app.store.FactorySkillJsonStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

class FactoryAiPipeline(
    private val context: Context,
    private val store: FactorySkillJsonStore = FactorySkillJsonStore(context),
    private val aiClient: AiAssistClient = AiAssistClient(context),
    private val ruleGenerator: SkillJsonRuleGenerator = SkillJsonRuleGenerator(),
    private val archetypeFactory: SkillJsonArchetypeFactory = SkillJsonArchetypeFactory()
) {
    fun analyzeInstructionAndGenerateCandidates(
        instruction: String,
        packageName: String? = null
    ): FactoryAiPipelineResult {
        val startedAt = System.currentTimeMillis()
        val config = AiAssistConfigStore(context).load()
        val loweredInstruction = instruction.lowercase()
        val expectedIntentDraft = when {
            packageName == SkillJsonRuleGenerator.BILIBILI_PACKAGE ||
                instruction.contains("哔哩") ||
                instruction.contains("B站") ||
                loweredInstruction.contains("bilibili") -> JSONObject()
                    .put("intentId", "search_in_app")
                    .put("appPackage", SkillJsonRuleGenerator.BILIBILI_PACKAGE)
                    .put("params", JSONArray(listOf("query")))
            !packageName.isNullOrBlank() -> JSONObject()
                .put("intentId", "generic_app_task")
                .put("appPackage", packageName)
                .put("params", JSONArray(listOf("query", "text")))
            else -> JSONObject()
                .put("intentId", "send_message_current_chat")
                .put("appPackage", SkillJsonRuleGenerator.WECHAT_PACKAGE)
                .put("params", JSONArray(listOf("message")))
        }
        val task = JSONObject()
            .put("taskType", "ANALYZE_INSTRUCTION_AND_GENERATE_SKILL_JSON")
            .put("instruction", instruction)
            .put("targetPackage", packageName)
            .put("constraints", JSONObject()
                .put("runtimeAiCalls", 0)
                .put("candidateOnly", true)
                .put("mustSplitAtomicSkills", true)
                .put("statusMustNotBeApproved", true)
                .put("noScreenshotOrVlmMainChain", true))
            .put("expectedOutput", JSONObject()
                .put("intentDraft", expectedIntentDraft)
                .put("skillJsonShape", "atomic_skill[] + macro_skill")
                .put("requiredTopLevelJson", JSONObject()
                    .put("intentDraft", "object")
                    .put("candidates", "array of SkillCandidate JSON objects"))
                .put("skillMustContain", JSONArray(listOf(
                    "schemaVersion",
                    "kind",
                    "id",
                    "implements",
                    "status",
                    "appPackage",
                    "capabilityRequired",
                    "action",
                    "verify",
                    "resultPolicy"
                ))))

        val response = aiClient.request(
            AiAssistRequest(
                useCase = AiAssistUseCase.TRACE_GENERALIZATION,
                inputJson = task,
                redacted = true,
                userVisible = true,
                runtimePath = false
            )
        )

        val jobId = "factory_ai_${System.currentTimeMillis()}"
        val aiOutput = response.outputJson
        val intentDraft = extractIntentDraftV2(instruction, aiOutput, packageName)
        val extractedAiCandidates = if (response.status == AiAssistStatus.SUCCESS) {
            extractSkillCandidatesFromAiOutput(aiOutput, intentDraft, packageName, jobId, response.status.name)
        } else {
            emptyList()
        }
        val aiCandidatesFineGrained = isFineGrainedCandidateSet(extractedAiCandidates, intentDraft, packageName)
        val aiCandidates = if (aiCandidatesFineGrained) extractedAiCandidates else emptyList()
        val archetypePackage = intentDraft.optString("appPackage").ifBlank { packageName.orEmpty() }
        val ruleCandidates = if (aiCandidates.isEmpty()) {
            when {
                response.status == AiAssistStatus.SUCCESS && intentDraft.optString("appPackage") == SkillJsonRuleGenerator.WECHAT_PACKAGE ->
                    ruleGenerator.generateWechatCurrentChat(
                        messageHint = intentDraft.optJSONObject("params")?.optString("message")
                    )
                response.status == AiAssistStatus.SUCCESS &&
                    intentDraft.optString("appPackage") == SkillJsonRuleGenerator.BILIBILI_PACKAGE ->
                    ruleGenerator.generateBilibiliSearch(
                        queryHint = intentDraft.optJSONObject("params")?.optString("query")
                    )
                response.status != AiAssistStatus.SUCCESS &&
                    (packageName == SkillJsonRuleGenerator.WECHAT_PACKAGE ||
                        "微信" in instruction ||
                        instruction.lowercase().contains("wechat")) -> ruleGenerator.generateWechatCurrentChat(
                            messageHint = intentDraft.optJSONObject("params")?.optString("message")
                        )
                response.status != AiAssistStatus.SUCCESS &&
                    (packageName == SkillJsonRuleGenerator.BILIBILI_PACKAGE ||
                        "哔哩" in instruction ||
                        instruction.lowercase().contains("bilibili")) -> ruleGenerator.generateBilibiliSearch(
                            queryHint = extractSearchQuery(instruction)
                        )
                archetypePackage.isNotBlank() -> buildArchetypeCandidates(
                    packageName = archetypePackage,
                    label = intentDraft.optString("appName").ifBlank { archetypePackage },
                    instruction = instruction,
                    queryHint = intentDraft.optJSONObject("params")?.optString("query")
                        ?.ifBlank { extractSearchQuery(instruction) }
                        ?: extractSearchQuery(instruction)
                )
                else -> emptyList()
            }
        } else {
            emptyList()
        }
        val generated = when {
            aiCandidates.isNotEmpty() -> aiCandidates
            ruleCandidates.isNotEmpty() -> ruleCandidates
            response.status == AiAssistStatus.SUCCESS -> when (intentDraft.optString("appPackage")) {
                SkillJsonRuleGenerator.WECHAT_PACKAGE -> ruleGenerator.generateWechatCurrentChat(
                    messageHint = intentDraft.optJSONObject("params")?.optString("message")
                )
                SkillJsonRuleGenerator.BILIBILI_PACKAGE -> ruleGenerator.generateBilibiliSearch(
                    queryHint = intentDraft.optJSONObject("params")?.optString("query")
                        ?.ifBlank { extractSearchQuery(instruction) }
                )
                else -> buildArchetypeCandidates(
                    packageName = intentDraft.optString("appPackage").ifBlank { packageName.orEmpty() },
                    label = intentDraft.optString("appName").ifBlank {
                        intentDraft.optString("appPackage").ifBlank { packageName.orEmpty() }
                    },
                    instruction = instruction,
                    queryHint = intentDraft.optJSONObject("params")?.optString("query")
                        ?.ifBlank { extractSearchQuery(instruction) }
                        ?: extractSearchQuery(instruction)
                )
            }
            else -> emptyList()
        }
        generated.map { it.optString("appPackage") }
            .filter { it.isNotBlank() }
            .toSet()
            .forEach { store.clearCandidateSkills(it) }
        val files = generated.map { skill ->
            val withJob = skill
                .put("factory", skill.optJSONObject("factory")
                    ?.put("aiJobId", jobId)
                    ?.put("aiStatus", response.status.name)
                    ?: JSONObject().put("aiJobId", jobId).put("aiStatus", response.status.name))
            store.saveSkill(withJob.getString("appPackage"), withJob)
        }
        val durationMs = System.currentTimeMillis() - startedAt
        val job = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "factory_ai_job")
            .put("id", jobId)
            .put("taskType", "ANALYZE_INSTRUCTION")
            .put("appPackage", intentDraft.optString("appPackage", packageName.orEmpty()))
            .put("relatedTraceIds", JSONArray())
            .put("relatedSampleIds", JSONArray())
            .put("relatedCandidateIds", JSONArray(generated.map { it.optString("id") }))
            .put("createdAt", System.currentTimeMillis())
            .put("durationMs", durationMs)
            .put("inputSummary", instruction.take(500))
            .put("promptSnapshot", task)
            .put("aiStatus", response.status.name)
            .put("aiMessage", response.message)
            .put("provider", response.provider?.name)
            .put("httpStatus", response.httpStatus)
            .put("model", aiOutput?.optString("model").orEmpty().ifBlank { config.model })
            .put("baseUrlHost", endpointHost(config.endpoint.ifBlank {
                when (config.provider) {
                    com.macropilot.app.ai.AiProvider.OPENAI_COMPAT_CHAT -> ""
                    com.macropilot.app.ai.AiProvider.OPENAI_RESPONSES -> AiAssistConfig.OPENAI_RESPONSES_ENDPOINT
                    com.macropilot.app.ai.AiProvider.DASHSCOPE_COMPAT_CHAT -> AiAssistConfig.DASHSCOPE_CHAT_ENDPOINT
                    com.macropilot.app.ai.AiProvider.CUSTOM_JSON -> ""
                }
            }))
            .put("rawResponse", aiOutput ?: JSONObject.NULL)
            .put("parsedJson", JSONObject()
                .put("intentDraft", intentDraft)
                .put("aiCandidateCount", extractedAiCandidates.size)
                .put("acceptedAiCandidateCount", aiCandidates.size)
                .put("aiFineGrained", aiCandidatesFineGrained)
                .put("ruleCandidateCount", ruleCandidates.size)
                .put("archetypeCandidateCount", generated.count { isArchetypeCandidate(it) })
                .put("generatedCandidateIds", JSONArray(generated.map { it.optString("id") })))
            .put("parseSuccess", response.status == AiAssistStatus.SUCCESS && (intentDraft.optString("appPackage").isNotBlank() || aiCandidates.isNotEmpty()))
            .put("errors", JSONArray(when {
                response.status == AiAssistStatus.SUCCESS && extractedAiCandidates.isNotEmpty() && !aiCandidatesFineGrained && files.isNotEmpty() ->
                    listOf("ai_candidates_not_fine_grained; used_rule_generalizer_fallback")
                response.status == AiAssistStatus.SUCCESS && aiCandidates.isEmpty() && files.isNotEmpty() -> listOf("ai_returned_no_candidates; used_rule_generalizer_fallback")
                response.status != AiAssistStatus.SUCCESS && files.isNotEmpty() -> listOf("ai_request_not_success:${response.status.name}; used_rule_generalizer_fallback")
                response.status != AiAssistStatus.SUCCESS -> listOf("ai_request_not_success:${response.status.name}")
                generated.isEmpty() -> listOf("no_rule_generator_for_intent_or_app")
                else -> emptyList()
            }))
            .put("accepted", files.isNotEmpty())
            .put("acceptedBy", if (files.isNotEmpty()) {
            when {
                aiCandidates.isNotEmpty() -> "FACTORY_AI_CANDIDATES"
                generated.any { isArchetypeCandidate(it) } -> "SKILL_ARCHETYPE_TEMPLATE_V0"
                else -> "RULE_GENERALIZER_V0"
            }
            } else JSONObject.NULL)
            .put("acceptedAt", if (files.isNotEmpty()) System.currentTimeMillis() else JSONObject.NULL)
        val jobFile = store.saveAiJob(job)
        return FactoryAiPipelineResult(
            jobFile = jobFile,
            candidateFiles = files,
            intentDraft = intentDraft,
            aiStatus = response.status.name,
            message = response.message
        )
    }

    private fun isFineGrainedCandidateSet(
        candidates: List<JSONObject>,
        intentDraft: JSONObject,
        requestedPackageName: String?
    ): Boolean {
        if (candidates.isEmpty()) return false
        val appPackage = intentDraft.optString("appPackage").ifBlank { requestedPackageName.orEmpty() }
        if (!candidates.all { candidateSchemaLooksRunnable(it) }) return false
        if (appPackage != SkillJsonRuleGenerator.WECHAT_PACKAGE) return true
        val atomicCount = candidates.count { it.optString("kind") == "atomic_skill" }
        val hasTypeText = candidates.any { candidate ->
            candidate.optString("implements") == "TypeTextSkill" ||
                candidate.optJSONObject("action")?.optString("type") in setOf("TypeText", "InputText", "PasteText")
        }
        val hasClick = candidates.any { candidate ->
            candidate.optString("implements") == "ClickElementSkill" ||
                candidate.optJSONObject("action")?.optString("type") == "ClickElement"
        }
        val hasVerifier = candidates.any { candidate ->
            candidate.optString("implements") == "VerifyGoalSkill" ||
                candidate.optJSONObject("action")?.optString("type") == "VerifyGoal" ||
                candidate.optJSONObject("verify")?.optString("type").orEmpty().contains("Message", ignoreCase = true)
        }
        val hasMacro = candidates.any { candidate ->
            candidate.optString("kind") == "macro_skill" &&
                (candidate.optJSONArray("atoms")?.length() ?: 0) >= 2
        }
        return atomicCount >= 3 && hasTypeText && hasClick && hasVerifier && hasMacro
    }

    private fun buildArchetypeCandidates(
        packageName: String,
        label: String,
        instruction: String,
        queryHint: String?
    ): List<JSONObject> {
        if (packageName.isBlank()) return emptyList()
        return archetypeFactory.buildForApp(
            patch = SkillJsonArchetypeFactory.defaultPatch(
                packageName = packageName,
                label = label.ifBlank { packageName },
                launchable = true
            ),
            queryHint = queryHint,
            includeSearch = true,
            includePostDraft = shouldIncludePostDraftArchetype(instruction, packageName, label)
        )
    }

    private fun shouldIncludePostDraftArchetype(instruction: String, packageName: String, label: String): Boolean {
        val text = "${instruction.lowercase(Locale.CHINA)} ${packageName.lowercase(Locale.US)} ${label.lowercase(Locale.CHINA)}"
        val postingIntent = listOf(
            "\u53d1",
            "\u53d1\u5e16",
            "\u53d1\u5fae\u535a",
            "\u5199\u5fae\u535a",
            "\u5e16\u5b50",
            "\u8349\u7a3f",
            "\u52a8\u6001",
            "post",
            "draft"
        ).any { text.contains(it) }
        val socialApp = listOf(
            "weibo",
            "hupu",
            "zhihu",
            "reddit",
            "instagram",
            "nga",
            "xiaohongshu",
            "\u5fae\u535a",
            "\u864e\u6251",
            "\u77e5\u4e4e",
            "\u5c0f\u7ea2\u4e66"
        ).any { text.contains(it) }
        return postingIntent && socialApp
    }

    private fun isArchetypeCandidate(candidate: JSONObject): Boolean {
        return candidate.optJSONObject("factory")
            ?.optString("source")
            ?.contains("ARCHETYPE", ignoreCase = true) == true
    }

    private fun candidateSchemaLooksRunnable(candidate: JSONObject): Boolean {
        val kind = candidate.optString("kind")
        if (kind == "macro_skill") {
            val atoms = candidate.optJSONArray("atoms") ?: return false
            if (atoms.length() == 0) return false
            for (i in 0 until atoms.length()) {
                val atom = atoms.optJSONObject(i) ?: return false
                if (atom.optString("type") != "UseSkill") return false
                if (atom.optString("skillId").isBlank()) return false
            }
            return candidate.optJSONObject("verify") != null &&
                candidate.optJSONObject("resultPolicy") != null
        }
        if (kind != "atomic_skill" && kind != "reactive_rule") return false
        val actionType = candidate.optJSONObject("action")?.optString("type").orEmpty()
        val supportedActions = setOf(
            "ResolveElement",
            "ClickElement",
            "OpenHupuPostEditor",
            "TypeText",
            "InputText",
            "PasteText",
            "FillHupuPostEditor",
            "VerifyGoal",
            "Back",
            "OpenApp",
            "CheckCapability",
            "FindEntryByBottomTabSweep",
            "HandleReactiveOverlays",
            "EnsureHupuSectionSelected",
            "VerifyHupuPostPublished",
            "ScrollForward"
        )
        return candidate.optJSONObject("capabilityRequired") != null &&
            actionType in supportedActions &&
            candidate.optJSONObject("verify") != null &&
            candidate.optJSONObject("resultPolicy") != null
    }

    private fun extractIntentDraftV2(instruction: String, aiOutput: JSONObject?, packageName: String?): JSONObject {
        val lowered = instruction.lowercase()
        val output = aiOutput?.optJSONObject("output") ?: aiOutput
        val aiIntent = output?.optJSONObject("intentDraft")
            ?: output?.optJSONObject("intent")
            ?: output?.optJSONObject("parsedIntent")
        val params = aiIntent?.optJSONObject("params")
        val message = params?.optString("message")?.takeIf { it.isNotBlank() }
            ?: aiIntent?.optString("message")?.takeIf { it.isNotBlank() }
            ?: Regex("(?:发|发送|send)\\s*([^\\s，。]+)\\s*$")
                .find(instruction)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
            ?: Regex("([0-9a-zA-Z_\\-]{1,64})").findAll(instruction).lastOrNull()?.value.orEmpty()
        val appPackage = when {
            aiIntent?.optString("appPackage") == SkillJsonRuleGenerator.WECHAT_PACKAGE -> SkillJsonRuleGenerator.WECHAT_PACKAGE
            aiIntent?.optString("appPackage") == SkillJsonRuleGenerator.BILIBILI_PACKAGE -> SkillJsonRuleGenerator.BILIBILI_PACKAGE
            packageName == SkillJsonRuleGenerator.WECHAT_PACKAGE -> SkillJsonRuleGenerator.WECHAT_PACKAGE
            packageName == SkillJsonRuleGenerator.BILIBILI_PACKAGE -> SkillJsonRuleGenerator.BILIBILI_PACKAGE
            instruction.contains("微信") || lowered.contains("wechat") -> SkillJsonRuleGenerator.WECHAT_PACKAGE
            instruction.contains("哔哩") || lowered.contains("bilibili") -> SkillJsonRuleGenerator.BILIBILI_PACKAGE
            aiOutput?.toString()?.contains("com.tencent.mm") == true -> SkillJsonRuleGenerator.WECHAT_PACKAGE
            aiOutput?.toString()?.contains(SkillJsonRuleGenerator.BILIBILI_PACKAGE) == true -> SkillJsonRuleGenerator.BILIBILI_PACKAGE
            else -> packageName.orEmpty()
        }
        val query = if (appPackage == SkillJsonRuleGenerator.BILIBILI_PACKAGE) {
            aiIntent?.optJSONObject("params")?.optString("query")?.takeIf { it.isNotBlank() }
                ?: aiIntent?.optString("query")?.takeIf { it.isNotBlank() }
                ?: extractSearchQuery(instruction)
        } else {
            ""
        }
        val contact = aiIntent?.optString("contact")?.takeIf { it.isNotBlank() }
            ?: if (instruction.contains("文件传输助手")) "文件传输助手" else ""
        return JSONObject()
            .put("intentId", when (appPackage) {
                SkillJsonRuleGenerator.WECHAT_PACKAGE -> "send_message_current_chat"
                SkillJsonRuleGenerator.BILIBILI_PACKAGE -> "search_in_app"
                else -> "unknown"
            })
            .put("appPackage", appPackage)
            .put("appName", when (appPackage) {
                SkillJsonRuleGenerator.WECHAT_PACKAGE -> "微信"
                SkillJsonRuleGenerator.BILIBILI_PACKAGE -> "哔哩哔哩"
                else -> ""
            })
            .put("contact", contact)
            .put("params", JSONObject()
                .put("message", message)
                .put("query", query))
            .put("note", "Factory v0 只生成候选 JSON，不直接执行手机动作。")
    }

    private fun extractSearchQuery(instruction: String): String {
        val normalized = instruction.trim()
        val explicit = listOf("搜索", "搜")
            .firstNotNullOfOrNull { marker ->
                normalized.substringAfter(marker, missingDelimiterValue = "")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
        return (explicit ?: normalized)
            .replace("哔哩哔哩", "")
            .replace("哔哩", "")
            .replace("bilibili", "", ignoreCase = true)
            .replace("B站", "")
            .trim()
    }

    private fun extractIntentDraft(instruction: String, aiOutput: JSONObject?, packageName: String?): JSONObject {
        val lowered = instruction.lowercase()
        val output = aiOutput?.optJSONObject("output") ?: aiOutput
        val aiIntent = output?.optJSONObject("intentDraft")
            ?: output?.optJSONObject("intent")
            ?: output?.optJSONObject("parsedIntent")
        val message = Regex("(?:发|发送|send)\\s*([^\\s]+)$")
            .find(instruction)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: aiIntent?.optJSONObject("params")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: aiIntent?.optString("message")?.takeIf { it.isNotBlank() }
            ?: Regex("([0-9a-zA-Z_\\-]{1,64})").findAll(instruction).lastOrNull()?.value.orEmpty()
        val appPackage = when {
            aiIntent?.optString("appPackage") == SkillJsonRuleGenerator.WECHAT_PACKAGE -> SkillJsonRuleGenerator.WECHAT_PACKAGE
            packageName == SkillJsonRuleGenerator.WECHAT_PACKAGE -> SkillJsonRuleGenerator.WECHAT_PACKAGE
            "微信" in instruction || "wechat" in lowered -> SkillJsonRuleGenerator.WECHAT_PACKAGE
            aiOutput?.toString()?.contains("com.tencent.mm") == true -> SkillJsonRuleGenerator.WECHAT_PACKAGE
            else -> ""
        }
        val contact = when {
            !aiIntent?.optString("contact").isNullOrBlank() -> aiIntent?.optString("contact").orEmpty()
            "文件传输助手" in instruction -> "文件传输助手"
            else -> ""
        }
        return JSONObject()
            .put("intentId", if (appPackage == SkillJsonRuleGenerator.WECHAT_PACKAGE) "send_message_current_chat" else "unknown")
            .put("appPackage", appPackage)
            .put("appName", if (appPackage == SkillJsonRuleGenerator.WECHAT_PACKAGE) "微信" else "")
            .put("contact", contact)
            .put("params", JSONObject().put("message", message))
            .put("note", "Factory v0 only generates candidate JSON; it does not execute the phone.")
    }

    private fun extractSkillCandidatesFromAiOutput(
        aiOutput: JSONObject?,
        intentDraft: JSONObject,
        requestedPackageName: String?,
        jobId: String,
        aiStatus: String
    ): List<JSONObject> {
        if (aiOutput == null) return emptyList()
        val root = aiOutput.optJSONObject("output") ?: aiOutput
        val arrays = listOfNotNull(
            root.optJSONArray("candidates"),
            root.optJSONArray("skillCandidates"),
            root.optJSONArray("skills"),
            root.optJSONArray("atomicSkills"),
            root.optJSONArray("macroSkills")
        )
        val directSkill = root.optJSONObject("skill")
            ?: root.optJSONObject("candidate")
            ?: root.takeIf { it.optString("kind") in setOf("atomic_skill", "macro_skill", "reactive_rule") }
        val fromArrays = arrays.flatMap { array ->
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
        }
        val packageName = intentDraft.optString("appPackage")
            .ifBlank { requestedPackageName.orEmpty() }
        return (fromArrays + listOfNotNull(directSkill))
            .mapIndexed { index, candidate ->
                normalizeAiCandidate(candidate, index, packageName, jobId, aiStatus)
            }
            .filter { candidate ->
                candidate.optString("kind") in setOf("atomic_skill", "macro_skill", "reactive_rule") &&
                    candidate.optString("id").isNotBlank()
            }
    }

    private fun normalizeAiCandidate(
        raw: JSONObject,
        index: Int,
        packageName: String,
        jobId: String,
        aiStatus: String
    ): JSONObject {
        val candidate = JSONObject(raw.toString())
        if (candidate.optInt("schemaVersion", 0) <= 0) candidate.put("schemaVersion", 1)
        if (candidate.optString("kind").isBlank()) {
            candidate.put("kind", if (candidate.has("atoms")) "macro_skill" else "atomic_skill")
        }
        if (candidate.optString("id").isBlank()) {
            candidate.put("id", "ai.generated.skill_${System.currentTimeMillis()}_$index")
        }
        if (candidate.optString("appPackage").isBlank()) {
            candidate.put("appPackage", packageName)
        }
        candidate.put("status", "CANDIDATE")
        if (candidate.optString("implements").isBlank()) {
            candidate.put("implements", when (candidate.optJSONObject("action")?.optString("type")) {
                "ResolveElement" -> "FindElementSkill"
                "ClickElement" -> "ClickElementSkill"
                "TypeText", "InputText", "PasteText" -> "TypeTextSkill"
                "VerifyGoal" -> "VerifyGoalSkill"
                else -> if (candidate.optString("kind") == "macro_skill") "MacroSkill" else "AtomicSkill"
            })
        }
        if (!candidate.has("params")) candidate.put("params", JSONArray())
        if (!candidate.has("preconditions")) candidate.put("preconditions", JSONArray())
        if (!candidate.has("dependsOn")) candidate.put("dependsOn", JSONArray())
        if (!candidate.has("resolverPolicy")) {
            candidate.put("resolverPolicy", JSONObject()
                .put("order", JSONArray(listOf("ACCESSIBILITY", "COORDINATE_PACK")))
                .put("allowAiSuggestionInFactory", true)
                .put("allowAiInRuntime", false))
        }
        val factory = candidate.optJSONObject("factory") ?: JSONObject()
        factory
            .put("source", "FACTORY_AI")
            .put("aiJobId", jobId)
            .put("aiStatus", aiStatus)
            .put("dryRunStatus", "PENDING")
            .put("testRuns", 0)
            .put("successRate", 0.0)
            .put("promotionStatus", "NOT_PROMOTED")
            .put("createdAt", System.currentTimeMillis())
        candidate.put("factory", factory)
        return candidate
    }

    private fun endpointHost(endpoint: String): String {
        return runCatching { URI(endpoint).host }.getOrNull() ?: endpoint
    }
}

data class FactoryAiPipelineResult(
    val jobFile: java.io.File,
    val candidateFiles: List<java.io.File>,
    val intentDraft: JSONObject,
    val aiStatus: String,
    val message: String
)
