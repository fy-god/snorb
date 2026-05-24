package com.macropilot.app.factory

import org.json.JSONArray
import org.json.JSONObject

class SkillJsonRuleGenerator {
    fun generateWechatCurrentChat(messageHint: String? = null): List<JSONObject> {
        val now = System.currentTimeMillis()
        val commonFactory = JSONObject()
            .put("source", "RULE_GENERALIZER_V0")
            .put("traceIds", JSONArray())
            .put("sampleCount", 0)
            .put("dryRunStatus", "PENDING")
            .put("testRuns", 0)
            .put("successRate", 0.0)
            .put("promotionStatus", "NOT_PROMOTED")
            .put("createdAt", now)

        val chatInputCoordinate = JSONObject()
            .put("baseWidth", 1440)
            .put("baseHeight", 3168)
            .put("fallbackTap", JSONObject().put("x", 0.45).put("y", 0.92))
            .put("source", "USER_RECORDING_OR_MANUAL_CALIBRATION")
            .put("confidence", 0.60)

        val sendCoordinate = JSONObject()
            .put("baseWidth", 1440)
            .put("baseHeight", 3168)
            .put("fallbackTap", JSONObject().put("x", 0.90).put("y", 0.925))
            .put("source", "USER_RECORDING_OR_MANUAL_CALIBRATION")
            .put("confidence", 0.65)

        val findChatInput = atomicBase(
            id = "wechat.chat.find_chat_input",
            name = "查找微信聊天输入框",
            implementsName = "FindElementSkill",
            factory = commonFactory
        )
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = true))
            .put("target", JSONObject()
                .put("elementId", "chat_input")
                .put("role", "CHAT_INPUT")
                .put("matchers", JSONArray()
                    .put(JSONObject().put("selector", "[class='android.widget.EditText'][editable=true]")))
                .put("coordinatePack", chatInputCoordinate))
            .put("action", JSONObject()
                .put("type", "ResolveElement")
                .put("elementId", "chat_input")
                .put("allowCoordinate", true)
                .put("timeoutMs", 1000))
            .put("verify", JSONObject()
                .put("type", "ElementResolved")
                .put("elementId", "chat_input")
                .put("timeoutMs", 1000))
            .put("resultPolicy", JSONObject()
                .put("nodeResolved", "SUCCESS")
                .put("coordinateResolved", "SUCCESS_LOW_CONFIDENCE")
                .put("notFound", "FAILED_ELEMENT_NOT_FOUND"))

        val typeChatInput = atomicBase(
            id = "wechat.chat.type_chat_input",
            name = "在微信聊天输入框输入文字",
            implementsName = "TypeTextSkill",
            factory = commonFactory
        )
            .put("params", JSONArray()
                .put(JSONObject().put("name", "text").put("type", "TEXT").put("required", true)))
            .put("dependsOn", JSONArray().put("wechat.chat.find_chat_input"))
            .put("capabilityRequired", capability(
                minLevel = "C",
                inputRequired = true,
                coordinateFallbackAllowed = true,
                allowedInputChannels = listOf("ACTION_SET_TEXT", "PASTE", "CUSTOM_IME", "SHIZUKU_ADB")
            ))
            .put("target", JSONObject()
                .put("elementId", "chat_input")
                .put("role", "CHAT_INPUT")
                .put("matchers", JSONArray()
                    .put(JSONObject().put("selector", "[class='android.widget.EditText'][editable=true]")))
                .put("coordinatePack", chatInputCoordinate))
            .put("action", JSONObject()
                .put("type", "TypeText")
                .put("textParam", "text")
                .put("targetElementId", "chat_input")
                .put("timeoutMs", 2000))
            .put("verify", JSONObject()
                .put("type", "InputContainsOrTextChangedEvent")
                .put("textParam", "text")
                .put("timeoutMs", 2000)
                .put("allowMediumConfidenceWhenEventOnly", true))
            .put("resultPolicy", JSONObject()
                .put("inputNodeContainsText", "SUCCESS")
                .put("textChangedEventOnly", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("coordinateOnlyNoInputNode", "SUCCESS_LOW_CONFIDENCE")
                .put("noInputChannel", "FAILED_INPUT_CHANNEL_MISSING"))

        val findSendButton = atomicBase(
            id = "wechat.chat.find_send_button",
            name = "查找微信发送按钮",
            implementsName = "FindElementSkill",
            factory = commonFactory
        )
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = true))
            .put("target", JSONObject()
                .put("elementId", "send_button")
                .put("role", "SEND_BUTTON")
                .put("matchers", JSONArray()
                    .put(JSONObject().put("selector", "[text='发送'][clickable=true]"))
                    .put(JSONObject().put("selector", "[desc='发送'][clickable=true]")))
                .put("coordinatePack", sendCoordinate))
            .put("action", JSONObject()
                .put("type", "ResolveElement")
                .put("elementId", "send_button")
                .put("allowCoordinate", true)
                .put("timeoutMs", 1000))
            .put("verify", JSONObject()
                .put("type", "ElementResolved")
                .put("elementId", "send_button")
                .put("timeoutMs", 1000))
            .put("resultPolicy", JSONObject()
                .put("nodeResolved", "SUCCESS")
                .put("coordinateResolved", "SUCCESS_LOW_CONFIDENCE")
                .put("notFound", "FAILED_ELEMENT_NOT_FOUND"))

        val clickSend = atomicBase(
            id = "wechat.chat.click_send_button",
            name = "点击微信发送按钮",
            implementsName = "ClickElementSkill",
            factory = commonFactory
        )
            .put("dependsOn", JSONArray().put("wechat.chat.find_send_button"))
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = true))
            .put("target", JSONObject()
                .put("elementId", "send_button")
                .put("role", "SEND_BUTTON")
                .put("matchers", JSONArray()
                    .put(JSONObject().put("selector", "[text='发送'][clickable=true]"))
                    .put(JSONObject().put("selector", "[desc='发送'][clickable=true]")))
                .put("coordinatePack", sendCoordinate))
            .put("action", JSONObject()
                .put("type", "ClickElement")
                .put("targetElementId", "send_button")
                .put("timeoutMs", 1500))
            .put("verify", JSONObject()
                .put("type", "WindowChangedOrNodeStateChanged")
                .put("timeoutMs", 1500)
                .put("allowLowConfidenceWhenCoordinateOnly", true))
            .put("resultPolicy", JSONObject()
                .put("nodeClickedAndVerified", "SUCCESS")
                .put("coordinateClicked", "SUCCESS_LOW_CONFIDENCE")
                .put("unverifiable", "FAILED_UNVERIFIABLE"))

        val verifySent = atomicBase(
            id = "wechat.chat.verify_message_sent",
            name = "验证微信消息已发送",
            implementsName = "VerifyGoalSkill",
            factory = commonFactory
        )
            .put("params", JSONArray()
                .put(JSONObject().put("name", "message").put("type", "TEXT").put("required", true)))
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = false))
            .put("action", JSONObject()
                .put("type", "VerifyGoal")
                .put("timeoutMs", 2000))
            .put("verify", JSONObject()
                .put("type", "MessageSentOrInputCleared")
                .put("messageParam", "message")
                .put("timeoutMs", 2000)
                .put("allowLowConfidenceWhenNodeUnreadable", true))
            .put("resultPolicy", JSONObject()
                .put("messageNodeVisible", "SUCCESS")
                .put("inputCleared", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("coordinateOnly", "SUCCESS_LOW_CONFIDENCE")
                .put("unverifiable", "FAILED_UNVERIFIABLE"))

        val macro = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "macro_skill")
            .put("id", "wechat.chat.send_in_current_chat")
            .put("name", "当前微信聊天页发送消息")
            .put("appPackage", WECHAT_PACKAGE)
            .put("appVersion", "*")
            .put("status", "CANDIDATE")
            .put("implements", "SendInCurrentChatSkill")
            .put("intentId", "send_message_current_chat")
            .put("params", JSONArray()
                .put(JSONObject()
                    .put("name", "message")
                    .put("type", "TEXT")
                    .put("required", true)
                    .put("example", messageHint.orEmpty())))
            .put("preconditions", JSONArray()
                .put(JSONObject().put("type", "ForegroundPackage").put("packageName", WECHAT_PACKAGE))
                .put(JSONObject().put("type", "CapabilityAtLeast").put("level", "C"))
                .put(JSONObject()
                    .put("type", "InputChannelAvailable")
                    .put("channels", JSONArray(listOf("ACTION_SET_TEXT", "PASTE", "CUSTOM_IME", "SHIZUKU_ADB")))))
            .put("atoms", JSONArray()
                .put(useSkill("wechat.chat.type_chat_input", JSONObject().put("text", "$" + "message")))
                .put(useSkill("wechat.chat.click_send_button"))
                .put(useSkill("wechat.chat.verify_message_sent", JSONObject().put("message", "$" + "message"))))
            .put("verify", JSONObject()
                .put("type", "MessageSentOrInputCleared")
                .put("messageParam", "message")
                .put("timeoutMs", 2000)
                .put("allowLowConfidenceWhenNodeUnreadable", true))
            .put("resultPolicy", JSONObject()
                .put("allAtomicSuccess", "SUCCESS")
                .put("partialAtomicSuccess", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("coordinateOnly", "SUCCESS_LOW_CONFIDENCE")
                .put("unverifiable", "FAILED_UNVERIFIABLE"))
            .put("timeoutMs", 8000)
            .put("factory", JSONObject(commonFactory.toString())
                .put("source", "COMPOSED_FROM_ATOMIC_SKILLS")
                .put("requiredAtomicSkills", JSONArray(listOf(
                    "wechat.chat.type_chat_input",
                    "wechat.chat.click_send_button",
                    "wechat.chat.verify_message_sent"
                )))
                .put("promotionRule", "all_required_atomic_skills_approved_or_tested"))

        return listOf(findChatInput, typeChatInput, findSendButton, clickSend, verifySent, macro)
    }

    fun generateBilibiliSearch(queryHint: String? = null): List<JSONObject> {
        val now = System.currentTimeMillis()
        val commonFactory = JSONObject()
            .put("source", "RULE_GENERALIZER_V0")
            .put("traceIds", JSONArray())
            .put("sampleCount", 0)
            .put("dryRunStatus", "PENDING")
            .put("testRuns", 0)
            .put("successRate", 0.0)
            .put("promotionStatus", "NOT_PROMOTED")
            .put("createdAt", now)

        fun coordinate(x: Double, y: Double, confidence: Double): JSONObject {
            return JSONObject()
                .put("baseWidth", 1440)
                .put("baseHeight", 3168)
                .put("fallbackTap", JSONObject().put("x", x).put("y", y))
                .put("source", "RULE_GENERALIZER_V0_MANUAL_PRIOR")
                .put("confidence", confidence)
        }

        val searchEntryTarget = JSONObject()
            .put("elementId", "bili_search_entry")
            .put("role", "SEARCH_ENTRY")
            .put("matchers", JSONArray()
                .put(JSONObject().put("selector", "[resourceId='tv.danmaku.bili:id/expand_search'][clickable=true]"))
                .put(JSONObject().put("selector", "[desc='搜索栏，按钮'][clickable=true]"))
                .put(JSONObject().put("selector", "[desc*='搜索栏'][clickable=true]"))
                .put(JSONObject().put("selector", "[desc*='搜索'][clickable=true]"))
                .put(JSONObject().put("selector", "[text*='搜索'][clickable=true]"))
                .put(JSONObject().put("selector", "[desc*='Search'][clickable=true]"))
                .put(JSONObject().put("selector", "[text*='Search'][clickable=true]")))
            .put("coordinatePack", coordinate(0.455, 0.078, 0.82))

        val searchInputTarget = JSONObject()
            .put("elementId", "bili_search_input")
            .put("role", "SEARCH_INPUT")
            .put("matchers", JSONArray()
                .put(JSONObject().put("selector", "[resourceId='tv.danmaku.bili:id/search_src_text'][editable=true]"))
                .put(JSONObject().put("selector", "[class='android.widget.EditText'][editable=true]"))
                .put(JSONObject().put("selector", "[desc='搜索查询'][editable=true]"))
                .put(JSONObject().put("selector", "[text*='搜索'][editable=true]")))
            .put("coordinatePack", coordinate(0.50, 0.082, 0.82))

        val searchSubmitTarget = JSONObject()
            .put("elementId", "bili_search_submit")
            .put("role", "SEARCH_SUBMIT")
            .put("matchers", JSONArray()
                .put(JSONObject().put("selector", "[resourceId='tv.danmaku.bili:id/action_search'][clickable=true]"))
                .put(JSONObject().put("selector", "[text='搜索'][clickable=true]"))
                .put(JSONObject().put("selector", "[desc='搜索'][clickable=true]")))
            .put("coordinatePack", coordinate(0.91, 0.082, 0.80))

        val openApp = atomicBase(
            id = "bilibili.open_app",
            name = "打开哔哩哔哩",
            implementsName = "OpenAppSkill",
            factory = commonFactory,
            appPackage = BILIBILI_PACKAGE,
            domain = "video_search"
        )
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = false))
            .put("action", JSONObject()
                .put("type", "OpenApp")
                .put("packageName", BILIBILI_PACKAGE)
                .put("launcherRoot", true)
                .put("timeoutMs", 3000))
            .put("verify", JSONObject()
                .put("type", "ForegroundPackage")
                .put("packageName", BILIBILI_PACKAGE)
                .put("timeoutMs", 3000))
            .put("resultPolicy", JSONObject()
                .put("verified", "SUCCESS")
                .put("timeout", "FAILED_TIMEOUT"))

        val recoverHomeSurface = atomicBase(
            id = "bilibili.recover.home_surface",
            name = "恢复到哔哩哔哩可搜索主页",
            implementsName = "RecoverToScreenSkill",
            factory = commonFactory,
            appPackage = BILIBILI_PACKAGE,
            domain = "video_search"
        )
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = true))
            .put("action", JSONObject()
                .put("type", "RecoverBilibiliHomeSurface")
                .put("appPackage", BILIBILI_PACKAGE)
                .put("timeoutMs", 30000))
            .put("verify", JSONObject()
                .put("type", "Always")
                .put("timeoutMs", 1))
            .put("resultPolicy", JSONObject()
                .put("recovered", "SUCCESS")
                .put("partialRecovered", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("coordinateOnlyRecovered", "SUCCESS_LOW_CONFIDENCE")
                .put("failed", "FAILED_RECOVER_SCREEN"))

        val openSearchDeepLink = atomicBase(
            id = "bilibili.search.open_search_deep_link",
            name = "打开哔哩哔哩搜索深链",
            implementsName = "SearchInAppSkill",
            factory = commonFactory,
            appPackage = BILIBILI_PACKAGE,
            domain = "video_search"
        )
            .put("params", JSONArray()
                .put(JSONObject().put("name", "query").put("type", "TEXT").put("required", true).put("example", queryHint.orEmpty())))
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = false))
            .put("action", JSONObject()
                .put("type", "OpenBilibiliSearch")
                .put("appPackage", BILIBILI_PACKAGE)
                .put("queryParam", "query")
                .put("settleMs", 3000))
            .put("verify", JSONObject()
                .put("type", "WindowChangedOrNodeStateChanged")
                .put("timeoutMs", 3000)
                .put("allowLowConfidenceWhenNodeUnreadable", true))
            .put("resultPolicy", JSONObject()
                .put("searchResultsOpened", "SUCCESS")
                .put("openedButUnverified", "SUCCESS_LOW_CONFIDENCE")
                .put("failed", "FAILED_OPEN_DEEP_LINK"))

        val findSearchEntry = atomicBase(
            id = "bilibili.home.find_search_entry",
            name = "查找哔哩哔哩搜索入口",
            implementsName = "FindElementSkill",
            factory = commonFactory,
            appPackage = BILIBILI_PACKAGE,
            domain = "video_search"
        )
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = true))
            .put("target", searchEntryTarget)
            .put("action", JSONObject()
                .put("type", "ResolveElement")
                .put("elementId", "bili_search_entry")
                .put("allowCoordinate", true)
                .put("timeoutMs", 1000))
            .put("verify", JSONObject()
                .put("type", "ElementResolved")
                .put("elementId", "bili_search_entry")
                .put("timeoutMs", 1000))
            .put("resultPolicy", JSONObject()
                .put("nodeResolved", "SUCCESS")
                .put("coordinateResolved", "SUCCESS_LOW_CONFIDENCE")
                .put("notFound", "FAILED_ELEMENT_NOT_FOUND"))

        val clickSearchEntry = atomicBase(
            id = "bilibili.home.click_search_entry",
            name = "点击哔哩哔哩搜索入口",
            implementsName = "ClickElementSkill",
            factory = commonFactory,
            appPackage = BILIBILI_PACKAGE,
            domain = "video_search"
        )
            .put("dependsOn", JSONArray().put("bilibili.home.find_search_entry"))
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = true))
            .put("target", searchEntryTarget)
            .put("action", JSONObject()
                .put("type", "ClickElement")
                .put("targetElementId", "bili_search_entry")
                .put("timeoutMs", 1500))
            .put("verify", JSONObject()
                .put("type", "WindowChangedOrNodeStateChanged")
                .put("timeoutMs", 1500))
            .put("resultPolicy", JSONObject()
                .put("nodeClickedAndVerified", "SUCCESS")
                .put("coordinateClicked", "SUCCESS_LOW_CONFIDENCE")
                .put("unverifiable", "FAILED_UNVERIFIABLE"))

        val findSearchInput = atomicBase(
            id = "bilibili.search.find_search_input",
            name = "查找哔哩哔哩搜索输入框",
            implementsName = "FindElementSkill",
            factory = commonFactory,
            appPackage = BILIBILI_PACKAGE,
            domain = "video_search"
        )
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = true))
            .put("target", searchInputTarget)
            .put("action", JSONObject()
                .put("type", "ResolveElement")
                .put("elementId", "bili_search_input")
                .put("allowCoordinate", true)
                .put("timeoutMs", 1000))
            .put("verify", JSONObject()
                .put("type", "ElementResolved")
                .put("elementId", "bili_search_input")
                .put("timeoutMs", 1000))
            .put("resultPolicy", JSONObject()
                .put("nodeResolved", "SUCCESS")
                .put("coordinateResolved", "SUCCESS_LOW_CONFIDENCE")
                .put("notFound", "FAILED_ELEMENT_NOT_FOUND"))

        val typeSearchQuery = atomicBase(
            id = "bilibili.search.type_search_query",
            name = "输入哔哩哔哩搜索词",
            implementsName = "TypeTextSkill",
            factory = commonFactory,
            appPackage = BILIBILI_PACKAGE,
            domain = "video_search"
        )
            .put("params", JSONArray()
                .put(JSONObject().put("name", "query").put("type", "TEXT").put("required", true).put("example", queryHint.orEmpty())))
            .put("dependsOn", JSONArray().put("bilibili.search.find_search_input"))
            .put("capabilityRequired", capability(
                minLevel = "C",
                inputRequired = true,
                coordinateFallbackAllowed = true,
                allowedInputChannels = listOf("ACTION_SET_TEXT", "PASTE", "CUSTOM_IME", "SHIZUKU_ADB")
            ))
            .put("target", searchInputTarget)
            .put("action", JSONObject()
                .put("type", "TypeText")
                .put("textParam", "query")
                .put("targetElementId", "bili_search_input")
                .put("replace", true)
                .put("timeoutMs", 2000))
            .put("verify", JSONObject()
                .put("type", "InputContainsOrTextChangedEvent")
                .put("textParam", "query")
                .put("timeoutMs", 2000)
                .put("allowMediumConfidenceWhenEventOnly", true))
            .put("resultPolicy", JSONObject()
                .put("inputNodeContainsText", "SUCCESS")
                .put("textChangedEventOnly", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("coordinateOnlyNoInputNode", "SUCCESS_LOW_CONFIDENCE")
                .put("noInputChannel", "FAILED_INPUT_CHANNEL_MISSING"))

        val clickSubmit = atomicBase(
            id = "bilibili.search.click_search_submit",
            name = "点击哔哩哔哩搜索按钮",
            implementsName = "ClickElementSkill",
            factory = commonFactory,
            appPackage = BILIBILI_PACKAGE,
            domain = "video_search"
        )
            .put("capabilityRequired", capability("C", inputRequired = false, coordinateFallbackAllowed = true))
            .put("target", searchSubmitTarget)
            .put("action", JSONObject()
                .put("type", "ClickElement")
                .put("targetElementId", "bili_search_submit")
                .put("timeoutMs", 1500))
            .put("verify", JSONObject()
                .put("type", "WindowChangedOrNodeStateChanged")
                .put("timeoutMs", 1500))
            .put("resultPolicy", JSONObject()
                .put("nodeClickedAndVerified", "SUCCESS")
                .put("coordinateClicked", "SUCCESS_LOW_CONFIDENCE")
                .put("unverifiable", "FAILED_UNVERIFIABLE"))

        val macro = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "macro_skill")
            .put("id", "bilibili.search.search_in_app")
            .put("name", "哔哩哔哩站内搜索")
            .put("appPackage", BILIBILI_PACKAGE)
            .put("appVersion", "*")
            .put("status", "CANDIDATE")
            .put("implements", "SearchInAppSkill")
            .put("intentId", "search_in_app")
            .put("params", JSONArray()
                .put(JSONObject().put("name", "query").put("type", "TEXT").put("required", true).put("example", queryHint.orEmpty())))
            .put("preconditions", JSONArray()
                .put(JSONObject().put("type", "CapabilityAtLeast").put("level", "C"))
                .put(JSONObject()
                    .put("type", "InputChannelAvailable")
                    .put("channels", JSONArray(listOf("ACTION_SET_TEXT", "PASTE", "CUSTOM_IME", "SHIZUKU_ADB")))))
            .put("atoms", JSONArray()
                .put(useSkill("bilibili.open_app"))
                .put(useSkill("bilibili.search.open_search_deep_link", JSONObject().put("query", "$" + "query")))
                .put(useSkill("bilibili.search.find_search_input"))
                .put(useSkill("bilibili.search.type_search_query", JSONObject().put("query", "$" + "query")))
                .put(useSkill("bilibili.search.click_search_submit")))
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
            .put("factory", JSONObject(commonFactory.toString())
                .put("source", "COMPOSED_FROM_ATOMIC_SKILLS")
                .put("requiredAtomicSkills", JSONArray(listOf(
                    "bilibili.open_app",
                    "bilibili.search.open_search_deep_link",
                    "bilibili.search.find_search_input",
                    "bilibili.search.type_search_query",
                    "bilibili.search.click_search_submit"
                )))
                .put("promotionRule", "all_required_atomic_skills_approved_or_tested"))

        return listOf(openApp, recoverHomeSurface, openSearchDeepLink, findSearchEntry, clickSearchEntry, findSearchInput, typeSearchQuery, clickSubmit, macro)
    }

    private fun atomicBase(
        id: String,
        name: String,
        implementsName: String,
        factory: JSONObject,
        appPackage: String = WECHAT_PACKAGE,
        domain: String = "message"
    ): JSONObject {
        return JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "atomic_skill")
            .put("id", id)
            .put("name", name)
            .put("appPackage", appPackage)
            .put("appVersion", "*")
            .put("status", "CANDIDATE")
            .put("domain", domain)
            .put("implements", implementsName)
            .put("params", JSONArray())
            .put("preconditions", JSONArray())
            .put("dependsOn", JSONArray())
            .put("resolverPolicy", JSONObject()
                .put("order", JSONArray(listOf("ACCESSIBILITY", "COORDINATE_PACK")))
                .put("allowAiSuggestionInFactory", true)
                .put("allowAiInRuntime", false))
            .put("safety", JSONObject()
                .put("forbiddenTexts", JSONArray(SkillJsonArchetypeFactory.forbiddenTexts())))
            .put("factory", JSONObject(factory.toString()))
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
            .put("nodeReadablePreferred", true)
            .put("coordinateFallbackAllowed", coordinateFallbackAllowed)
            .put("allowedInputChannels", JSONArray(allowedInputChannels))
            .put("verificationRequired", if (minLevel == "C") "LOW" else "MEDIUM")
    }

    private fun useSkill(skillId: String, params: JSONObject? = null): JSONObject {
        val atom = JSONObject()
            .put("type", "UseSkill")
            .put("skillId", skillId)
        if (params != null) atom.put("params", params)
        return atom
    }

    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val BILIBILI_PACKAGE = "tv.danmaku.bili"
        const val XIAOHONGSHU_PACKAGE = "com.xingin.xhs"
        const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
    }
}
