package com.macropilot.app.factory

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class SkillJsonArchetypeFactory {
    fun buildForApp(
        patch: AppPatch,
        queryHint: String? = null,
        includeSearch: Boolean = true,
        includePostDraft: Boolean = false
    ): List<JSONObject> {
        val ids = SkillIds(patch.packageName)
        val commonFactory = factoryBase(
            patch = patch,
            source = "SKILL_ARCHETYPE_TEMPLATE_V0",
            archetypeId = "common.app_session.v1"
        )
        val skills = mutableListOf<JSONObject>()

        if (patch.launchable) {
            skills += atomicBase(ids.openApp, "\u6253\u5f00\u5e94\u7528", patch, "OpenAppSkill", commonFactory)
                .put("capabilityRequired", capability("D", inputRequired = false, coordinateFallbackAllowed = false))
                .put("action", JSONObject()
                    .put("type", "OpenApp")
                    .put("packageName", patch.packageName)
                    .put("timeoutMs", 3000))
                .put("verify", JSONObject()
                    .put("type", "ForegroundPackage")
                    .put("packageName", patch.packageName)
                    .put("timeoutMs", 3000)
                    .put("allowUnverifiedWhenAccessibilityDisconnected", true))
                .put("resultPolicy", JSONObject()
                    .put("foregroundVerified", "SUCCESS")
                    .put("launchIntentSentOnly", "SUCCESS_LOW_CONFIDENCE")
                    .put("noLaunchIntent", "FAILED_NO_LAUNCH_INTENT")
                    .put("unverifiable", "FAILED_UNVERIFIABLE"))
        }

        skills += atomicBase(ids.capabilityCheck, "\u68c0\u67e5\u5e94\u7528\u80fd\u529b", patch, "CapabilityCheckSkill", commonFactory)
            .put("capabilityRequired", capability("D", inputRequired = false, coordinateFallbackAllowed = false))
            .put("action", JSONObject()
                .put("type", "CheckCapability")
                .put("packageName", patch.packageName))
            .put("verify", JSONObject()
                .put("type", "CapabilityObserved")
                .put("packageName", patch.packageName)
                .put("timeoutMs", 500))
            .put("resultPolicy", JSONObject()
                .put("levelA", "SUCCESS")
                .put("levelB", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("levelC", "SUCCESS_LOW_CONFIDENCE")
                .put("levelD", "FAILED_CAPABILITY_D"))

        skills += atomicBase(ids.back, "\u8fd4\u56de", patch, "BackSkill", commonFactory)
            .put("capabilityRequired", capability("D", inputRequired = false, coordinateFallbackAllowed = false))
            .put("action", JSONObject()
                .put("type", "Back")
                .put("timeoutMs", 1000))
            .put("verify", JSONObject()
                .put("type", "WindowChanged")
                .put("timeoutMs", 1000)
                .put("allowLowConfidenceWhenUnchanged", true))
            .put("resultPolicy", JSONObject()
                .put("changed", "SUCCESS")
                .put("unchanged", "SUCCESS_LOW_CONFIDENCE"))

        if (patch.launchable) {
            skills += atomicBase(ids.recover, "\u6062\u590d\u5230\u5e94\u7528\u524d\u53f0", patch, "RecoverToScreenSkill", commonFactory)
                .put("dependsOn", JSONArray().put(ids.openApp))
                .put("capabilityRequired", capability("D", inputRequired = false, coordinateFallbackAllowed = false))
                .put("action", JSONObject()
                    .put("type", "OpenApp")
                    .put("packageName", patch.packageName)
                    .put("timeoutMs", 3000))
                .put("verify", JSONObject()
                    .put("type", "ForegroundPackage")
                    .put("packageName", patch.packageName)
                    .put("timeoutMs", 3000)
                    .put("allowUnverifiedWhenAccessibilityDisconnected", true))
                .put("resultPolicy", JSONObject()
                    .put("recovered", "SUCCESS")
                    .put("launchIntentSentOnly", "SUCCESS_LOW_CONFIDENCE")
                    .put("failed", "FAILED_RECOVER_SCREEN"))
        }

        skills += atomicBase(ids.clickVisibleText, "\u70b9\u51fb\u53ef\u89c1\u6587\u5b57", patch, "ClickElementSkill", commonFactory)
            .put("params", JSONArray()
                .put(JSONObject().put("name", "text").put("type", "TEXT").put("required", true)))
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = false))
            .put("action", JSONObject()
                .put("type", "ClickVisibleText")
                .put("textParam", "text")
                .put("timeoutMs", 1500))
            .put("verify", JSONObject()
                .put("type", "WindowChangedOrNodeStateChanged")
                .put("timeoutMs", 1500)
                .put("allowLowConfidenceWhenUnchanged", true))
            .put("resultPolicy", JSONObject()
                .put("nodeClickedAndVerified", "SUCCESS")
                .put("clickedButWeakVerify", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("notFound", "FAILED_ELEMENT_NOT_FOUND"))

        skills += atomicBase(ids.typeFirstInput, "\u5411\u7b2c\u4e00\u4e2a\u8f93\u5165\u6846\u8f93\u5165", patch, "TypeTextSkill", commonFactory)
            .put("params", JSONArray()
                .put(JSONObject().put("name", "text").put("type", "TEXT").put("required", true)))
            .put("capabilityRequired", capability(
                "C",
                inputRequired = true,
                coordinateFallbackAllowed = false,
                allowedInputChannels = inputChannels()
            ))
            .put("action", JSONObject()
                .put("type", "TypeIntoFirstInput")
                .put("textParam", "text")
                .put("timeoutMs", 2200))
            .put("verify", JSONObject()
                .put("type", "InputContainsOrTextChangedEvent")
                .put("textParam", "text")
                .put("timeoutMs", 1800)
                .put("allowMediumConfidenceWhenEventOnly", true))
            .put("resultPolicy", JSONObject()
                .put("inputNodeContainsText", "SUCCESS")
                .put("textChangedEventOnly", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("noInputChannel", "FAILED_INPUT_CHANNEL_MISSING")
                .put("unverifiable", "FAILED_UNVERIFIABLE"))

        if (patch.launchable) {
            skills += macroPrepare(ids, patch, commonFactory)
        }
        if (includeSearch && patch.launchable) {
            skills += searchSkills(ids, patch, commonFactory, queryHint)
        }
        if (includePostDraft && patch.launchable) {
            skills += postDraftSkills(ids, patch, commonFactory)
        }

        return skills
    }

    private fun searchSkills(
        ids: SkillIds,
        patch: AppPatch,
        commonFactory: JSONObject,
        queryHint: String?
    ): List<JSONObject> {
        val factory = JSONObject(commonFactory.toString())
            .put("archetypeId", "common.search_in_app.v1")
        val clickEntry = atomicBase(ids.clickSearchEntry, "\u70b9\u51fb\u641c\u7d22\u5165\u53e3", patch, "ClickElementSkill", factory)
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = true))
            .put("action", clickTextAction(patch.searchEntryTexts, patch.searchEntryX, patch.searchEntryY, 1200))
            .put("verify", JSONObject()
                .put("type", "WindowChangedOrNodeStateChanged")
                .put("timeoutMs", 1500))
            .put("resultPolicy", JSONObject()
                .put("nodeClickedAndVerified", "SUCCESS")
                .put("coordinateClicked", "SUCCESS_LOW_CONFIDENCE")
                .put("notFound", "FAILED_ELEMENT_NOT_FOUND"))

        val typeQuery = atomicBase(ids.typeSearchQuery, "\u8f93\u5165\u641c\u7d22\u8bcd", patch, "TypeTextSkill", factory)
            .put("params", JSONArray()
                .put(JSONObject().put("name", "query").put("type", "TEXT").put("required", true).put("example", queryHint.orEmpty())))
            .put("capabilityRequired", capability(
                "C",
                inputRequired = true,
                coordinateFallbackAllowed = true,
                allowedInputChannels = inputChannels()
            ))
            .put("action", JSONObject()
                .put("type", "TypeIntoFirstInput")
                .put("textParam", "query")
                .put("timeoutMs", 2200))
            .put("verify", JSONObject()
                .put("type", "InputContainsOrTextChangedEvent")
                .put("textParam", "query")
                .put("timeoutMs", 1800)
                .put("allowMediumConfidenceWhenEventOnly", true))
            .put("resultPolicy", JSONObject()
                .put("inputNodeContainsText", "SUCCESS")
                .put("textChangedEventOnly", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("coordinateOnlyNoInputNode", "SUCCESS_LOW_CONFIDENCE")
                .put("noInputChannel", "FAILED_INPUT_CHANNEL_MISSING"))

        val submit = atomicBase(ids.clickSearchSubmit, "\u70b9\u51fb\u641c\u7d22\u786e\u8ba4", patch, "ClickElementSkill", factory)
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = true))
            .put("action", clickTextAction(patch.searchSubmitTexts, patch.searchSubmitX, patch.searchSubmitY, 1200))
            .put("verify", JSONObject()
                .put("type", "WindowChangedOrNodeStateChanged")
                .put("timeoutMs", 1800)
                .put("allowLowConfidenceWhenUnchanged", true))
            .put("resultPolicy", JSONObject()
                .put("nodeClickedAndVerified", "SUCCESS")
                .put("coordinateClicked", "SUCCESS_LOW_CONFIDENCE")
                .put("notFound", "FAILED_ELEMENT_NOT_FOUND"))

        val macro = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "macro_skill")
            .put("id", ids.searchInApp)
            .put("name", "\u901a\u7528\u7ad9\u5185\u641c\u7d22")
            .put("appPackage", patch.packageName)
            .put("appVersion", "*")
            .put("status", "CANDIDATE")
            .put("implements", "SearchInAppSkill")
            .put("intentId", "search_in_app")
            .put("params", JSONArray()
                .put(JSONObject().put("name", "query").put("type", "TEXT").put("required", true).put("example", queryHint.orEmpty())))
            .put("preconditions", JSONArray()
                .put(JSONObject().put("type", "CapabilityAtLeast").put("level", "C"))
                .put(JSONObject().put("type", "InputChannelAvailable").put("channels", JSONArray(inputChannels()))))
            .put("atoms", JSONArray()
                .put(useSkill(ids.openApp))
                .put(useSkill(ids.clickSearchEntry))
                .put(useSkill(ids.typeSearchQuery, JSONObject().put("query", "$" + "query")))
                .put(useSkill(ids.clickSearchSubmit)))
            .put("verify", JSONObject()
                .put("type", "WindowChangedOrNodeStateChanged")
                .put("timeoutMs", 2500)
                .put("allowLowConfidenceWhenNodeUnreadable", true))
            .put("resultPolicy", JSONObject()
                .put("allAtomicSuccess", "SUCCESS")
                .put("partialAtomicSuccess", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("coordinateOnly", "SUCCESS_LOW_CONFIDENCE")
                .put("unverifiable", "FAILED_UNVERIFIABLE"))
            .put("timeoutMs", 10000)
            .put("factory", JSONObject(factory.toString())
                .put("source", "COMPOSED_FROM_SKILL_ARCHETYPE")
                .put("requiredAtomicSkills", JSONArray(listOf(
                    ids.openApp,
                    ids.clickSearchEntry,
                    ids.typeSearchQuery,
                    ids.clickSearchSubmit
                )))
                .put("promotionRule", "requires_dry_run_real_test_and_promotion_gate"))

        return listOf(clickEntry, typeQuery, submit, macro)
    }

    private fun postDraftSkills(
        ids: SkillIds,
        patch: AppPatch,
        commonFactory: JSONObject
    ): List<JSONObject> {
        val factory = JSONObject(commonFactory.toString())
            .put("archetypeId", "common.post_draft.v1")
        val clickEntry = atomicBase(ids.clickPostDraftEntry, "\u70b9\u51fb\u521b\u4f5c\u5165\u53e3", patch, "ClickElementSkill", factory)
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = true))
            .put("action", clickTextAction(patch.postDraftEntryTexts, patch.postDraftEntryX, patch.postDraftEntryY, 1500))
            .put("verify", JSONObject()
                .put("type", "WindowChangedOrNodeStateChanged")
                .put("timeoutMs", 2000)
                .put("allowLowConfidenceWhenUnchanged", true))
            .put("resultPolicy", JSONObject()
                .put("nodeClickedAndVerified", "SUCCESS")
                .put("coordinateClicked", "SUCCESS_LOW_CONFIDENCE")
                .put("notFound", "FAILED_ELEMENT_NOT_FOUND"))

        val typeContent = atomicBase(ids.typePostDraftContent, "\u8f93\u5165\u8349\u7a3f\u5185\u5bb9", patch, "TypeTextSkill", factory)
            .put("params", JSONArray()
                .put(JSONObject().put("name", "content").put("type", "TEXT").put("required", true)))
            .put("capabilityRequired", capability(
                "C",
                inputRequired = true,
                coordinateFallbackAllowed = true,
                allowedInputChannels = inputChannels()
            ))
            .put("action", JSONObject()
                .put("type", "TypeIntoFirstInput")
                .put("textParam", "content")
                .put("timeoutMs", 2600))
            .put("verify", JSONObject()
                .put("type", "InputContainsOrTextChangedEvent")
                .put("textParam", "content")
                .put("timeoutMs", 1800)
                .put("allowMediumConfidenceWhenEventOnly", true))
            .put("resultPolicy", JSONObject()
                .put("inputNodeContainsText", "SUCCESS")
                .put("textChangedEventOnly", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("coordinateOnlyNoInputNode", "SUCCESS_LOW_CONFIDENCE")
                .put("noInputChannel", "FAILED_INPUT_CHANNEL_MISSING"))

        val macro = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "macro_skill")
            .put("id", ids.createPostDraft)
            .put("name", "\u901a\u7528\u53d1\u5e16\u8349\u7a3f")
            .put("appPackage", patch.packageName)
            .put("appVersion", "*")
            .put("status", "CANDIDATE")
            .put("implements", "CreatePostDraftSkill")
            .put("intentId", "create_post_draft")
            .put("params", JSONArray()
                .put(JSONObject().put("name", "content").put("type", "TEXT").put("required", true)))
            .put("preconditions", JSONArray()
                .put(JSONObject().put("type", "CapabilityAtLeast").put("level", "C"))
                .put(JSONObject().put("type", "InputChannelAvailable").put("channels", JSONArray(inputChannels()))))
            .put("atoms", JSONArray()
                .put(useSkill(ids.openApp))
                .put(useSkill(ids.clickPostDraftEntry))
                .put(useSkill(ids.typePostDraftContent, JSONObject().put("content", "$" + "content"))))
            .put("verify", JSONObject()
                .put("type", "InputContainsOrTextChangedEvent")
                .put("textParam", "content")
                .put("timeoutMs", 2500)
                .put("allowLowConfidenceWhenNodeUnreadable", true))
            .put("resultPolicy", JSONObject()
                .put("draftTextVisible", "SUCCESS")
                .put("textChangedEventOnly", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("coordinateOnly", "SUCCESS_LOW_CONFIDENCE")
                .put("unverifiable", "FAILED_UNVERIFIABLE"))
            .put("safety", JSONObject()
                .put("finalPublishAllowed", false)
                .put("note", "Draft archetype stops before final publish/submit."))
            .put("timeoutMs", 10000)
            .put("factory", JSONObject(factory.toString())
                .put("source", "COMPOSED_FROM_SKILL_ARCHETYPE")
                .put("requiredAtomicSkills", JSONArray(listOf(
                    ids.openApp,
                    ids.clickPostDraftEntry,
                    ids.typePostDraftContent
                )))
                .put("promotionRule", "draft_only_requires_visible_text_or_text_changed_event"))

        return listOf(clickEntry, typeContent, macro)
    }

    private fun macroPrepare(ids: SkillIds, patch: AppPatch, commonFactory: JSONObject): JSONObject {
        return JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "macro_skill")
            .put("id", ids.prepareSession)
            .put("name", "\u51c6\u5907\u5e94\u7528\u4f1a\u8bdd")
            .put("appPackage", patch.packageName)
            .put("appVersion", "*")
            .put("status", "CANDIDATE")
            .put("implements", "PrepareAppSessionSkill")
            .put("intentId", "prepare_app_session")
            .put("params", JSONArray())
            .put("preconditions", JSONArray())
            .put("atoms", JSONArray()
                .put(useSkill(ids.openApp))
                .put(useSkill(ids.capabilityCheck)))
            .put("verify", JSONObject()
                .put("type", "CapabilityObserved")
                .put("packageName", patch.packageName)
                .put("timeoutMs", 1000))
            .put("resultPolicy", JSONObject()
                .put("allAtomicSuccess", "SUCCESS")
                .put("partialAtomicSuccess", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("unverifiable", "FAILED_UNVERIFIABLE"))
            .put("timeoutMs", 6000)
            .put("factory", JSONObject(commonFactory.toString())
                .put("source", "COMPOSED_FROM_SKILL_ARCHETYPE")
                .put("requiredAtomicSkills", JSONArray(listOf(ids.openApp, ids.capabilityCheck)))
                .put("promotionRule", "requires_real_probe_and_promotion_gate"))
    }

    private fun atomicBase(
        id: String,
        name: String,
        patch: AppPatch,
        implementsName: String,
        factory: JSONObject
    ): JSONObject {
        return JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "atomic_skill")
            .put("id", id)
            .put("name", name)
            .put("appPackage", patch.packageName)
            .put("appVersion", "*")
            .put("status", "CANDIDATE")
            .put("domain", "app_session")
            .put("implements", implementsName)
            .put("params", JSONArray())
            .put("preconditions", JSONArray())
            .put("dependsOn", JSONArray())
            .put("resolverPolicy", JSONObject()
                .put("order", JSONArray(listOf("ACCESSIBILITY", "COORDINATE_PACK", "PACKAGE_MANAGER")))
                .put("allowAiSuggestionInFactory", true)
                .put("allowAiInRuntime", false))
            .put("safety", JSONObject()
                .put("forbiddenTexts", JSONArray(forbiddenTexts())))
            .put("factory", JSONObject(factory.toString()))
    }

    private fun clickTextAction(texts: List<String>, x: Double, y: Double, timeoutMs: Long): JSONObject {
        val primary = texts.firstOrNull().orEmpty()
        return JSONObject()
            .put("type", "ClickVisibleText")
            .put("text", primary)
            .put("textAliases", JSONArray(texts))
            .put("x", x)
            .put("y", y)
            .put("timeoutMs", timeoutMs)
    }

    private fun factoryBase(patch: AppPatch, source: String, archetypeId: String): JSONObject {
        return JSONObject()
            .put("source", source)
            .put("archetypeId", archetypeId)
            .put("traceIds", JSONArray())
            .put("sampleCount", 0)
            .put("dryRunStatus", "PENDING")
            .put("testRuns", 0)
            .put("successRate", 0.0)
            .put("promotionStatus", "NOT_PROMOTED")
            .put("createdAt", System.currentTimeMillis())
            .put("appPatch", patch.toJson())
    }

    private fun capability(
        minLevel: String,
        inputRequired: Boolean,
        coordinateFallbackAllowed: Boolean,
        allowedInputChannels: List<String> = emptyList()
    ): JSONObject {
        return JSONObject()
            .put("minLevel", minLevel)
            .put("inputRequired", inputRequired)
            .put("nodeReadablePreferred", minLevel != "D")
            .put("coordinateFallbackAllowed", coordinateFallbackAllowed)
            .put("allowedInputChannels", JSONArray(allowedInputChannels))
            .put("verificationRequired", if (minLevel == "D") "LOW" else "MEDIUM")
    }

    private fun useSkill(skillId: String, params: JSONObject? = null): JSONObject {
        val atom = JSONObject()
            .put("type", "UseSkill")
            .put("skillId", skillId)
        if (params != null) atom.put("params", params)
        return atom
    }

    data class AppPatch(
        val packageName: String,
        val label: String,
        val launchable: Boolean = true,
        val launchActivity: String = "",
        val searchEntryTexts: List<String> = listOf("\u641c\u7d22", "\u641c\u4e00\u641c", "Search"),
        val searchSubmitTexts: List<String> = listOf("\u641c\u7d22", "\u786e\u5b9a", "Search"),
        val searchEntryX: Double = 0.50,
        val searchEntryY: Double = 0.08,
        val searchSubmitX: Double = 0.90,
        val searchSubmitY: Double = 0.08,
        val postDraftEntryTexts: List<String> = listOf("+", "\u5199\u5fae\u535a", "\u53d1\u5fae\u535a", "\u53d1\u5e16", "\u521b\u4f5c", "\u5199\u5e16\u5b50", "\u53d1\u52a8\u6001", "\u8bf4\u70b9\u4ec0\u4e48"),
        val postDraftEntryX: Double = 0.90,
        val postDraftEntryY: Double = 0.90
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("packageName", packageName)
                .put("label", label)
                .put("launchable", launchable)
                .put("launchActivity", launchActivity)
                .put("searchEntryTexts", JSONArray(searchEntryTexts))
                .put("searchSubmitTexts", JSONArray(searchSubmitTexts))
                .put("searchEntryCoordinate", JSONObject().put("x", searchEntryX).put("y", searchEntryY))
                .put("searchSubmitCoordinate", JSONObject().put("x", searchSubmitX).put("y", searchSubmitY))
                .put("postDraftEntryTexts", JSONArray(postDraftEntryTexts))
                .put("postDraftEntryCoordinate", JSONObject().put("x", postDraftEntryX).put("y", postDraftEntryY))
        }
    }

    private data class SkillIds(val packageName: String) {
        private val prefix = packageName.safeSkillPrefixForArchetype()
        val openApp = "$prefix.open_app"
        val capabilityCheck = "$prefix.capability_check"
        val recover = "$prefix.recover_to_foreground"
        val prepareSession = "$prefix.prepare_app_session"
        val back = "$prefix.back"
        val clickVisibleText = "$prefix.common.click_visible_text"
        val typeFirstInput = "$prefix.common.type_first_input"
        val clickSearchEntry = "$prefix.search.click_entry"
        val typeSearchQuery = "$prefix.search.type_query"
        val clickSearchSubmit = "$prefix.search.click_submit"
        val searchInApp = "$prefix.search.search_in_app"
        val clickPostDraftEntry = "$prefix.post.click_draft_entry"
        val typePostDraftContent = "$prefix.post.type_draft_content"
        val createPostDraft = "$prefix.post.create_draft"
    }

    companion object {
        fun defaultPatch(
            packageName: String,
            label: String = packageName,
            launchable: Boolean = true,
            launchActivity: String = ""
        ): AppPatch {
            return AppPatch(
                packageName = packageName,
                label = label.ifBlank { packageName },
                launchable = launchable,
                launchActivity = launchActivity
            )
        }

        fun inputChannels(): List<String> = listOf("ACTION_SET_TEXT", "PASTE", "CUSTOM_IME", "SHIZUKU_ADB")

        fun forbiddenTexts(): List<String> = listOf(
            "\u652f\u4ed8",
            "\u4ed8\u6b3e",
            "\u8f6c\u8d26",
            "\u7acb\u5373\u8d2d\u4e70",
            "\u4e0b\u5355",
            "\u63d0\u4ea4\u8ba2\u5355",
            "\u652f\u4ed8\u5bc6\u7801",
            "\u4ea4\u6613\u5bc6\u7801",
            "\u8d44\u91d1\u5bc6\u7801",
            "\u8bc1\u5238\u4ea4\u6613",
            "\u89e3\u7ed1\u94f6\u884c\u5361",
            "\u7ed1\u5b9a\u94f6\u884c\u5361",
            "\u5220\u9664\u8d26\u53f7",
            "\u6ce8\u9500\u8d26\u53f7"
        )
    }
}

private fun String.safeSkillPrefixForArchetype(): String {
    return lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), ".")
        .trim('.')
        .ifBlank { "app" }
}
