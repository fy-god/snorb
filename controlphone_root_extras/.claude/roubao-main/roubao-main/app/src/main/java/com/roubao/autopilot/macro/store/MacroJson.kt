package com.roubao.autopilot.macro.store

import com.roubao.autopilot.macro.model.AnchorSpec
import com.roubao.autopilot.macro.model.CoordinatePack
import com.roubao.autopilot.macro.model.CoordinateSample
import com.roubao.autopilot.macro.model.CoordinateSource
import com.roubao.autopilot.macro.model.ElementMatcher
import com.roubao.autopilot.macro.model.MacroAtom
import com.roubao.autopilot.macro.model.MacroDomain
import com.roubao.autopilot.macro.model.MacroParam
import com.roubao.autopilot.macro.model.MacroTemplate
import com.roubao.autopilot.macro.model.NormalizedPoint
import com.roubao.autopilot.macro.model.NormalizedRect
import com.roubao.autopilot.macro.model.ParamType
import com.roubao.autopilot.macro.model.RecoverPolicy
import com.roubao.autopilot.macro.model.TemplateSource
import com.roubao.autopilot.macro.model.UiElementSpec
import com.roubao.autopilot.macro.model.UiRole
import com.roubao.autopilot.macro.model.UiScreenModule
import com.roubao.autopilot.macro.model.UiSignature
import com.roubao.autopilot.macro.model.VerifyRule
import org.json.JSONArray
import org.json.JSONObject

object MacroJson {

    fun templateToJson(template: MacroTemplate): JSONObject = JSONObject().apply {
        put("id", template.id)
        put("name", template.name)
        put("appPackage", template.appPackage)
        put("appName", template.appName)
        put("domain", template.domain.name)
        put("intentId", template.intentId)
        put("paramSchema", JSONArray(template.paramSchema.map(::paramToJson)))
        put("atoms", JSONArray(template.atoms.map(::atomToJson)))
        put("finalVerify", verifyRuleToJson(template.finalVerify))
        put("version", template.version)
        put("source", template.source.name)
    }

    fun templateFromJson(obj: JSONObject): MacroTemplate {
        return MacroTemplate(
            id = obj.getString("id"),
            name = obj.optString("name", obj.getString("id")),
            appPackage = obj.getString("appPackage"),
            appName = obj.optString("appName", obj.optString("appPackage")),
            domain = enumValue(obj.optString("domain"), MacroDomain.CUSTOM),
            intentId = obj.optString("intentId", obj.getString("id")),
            paramSchema = obj.optJSONArray("paramSchema").toList { paramFromJson(it) },
            atoms = obj.optJSONArray("atoms").toList { atomFromJson(it) },
            finalVerify = verifyRuleFromJson(obj.optJSONObject("finalVerify")) ?: VerifyRule.AlwaysTrue,
            version = obj.optInt("version", 1),
            source = enumValue(obj.optString("source"), TemplateSource.RECORDED_GENERALIZED)
        )
    }

    fun screenToJson(screen: UiScreenModule): JSONObject = JSONObject().apply {
        put("id", screen.id)
        put("name", screen.name)
        put("aliases", JSONArray(screen.aliases))
        put("signatures", JSONArray(screen.signatures.map(::signatureToJson)))
        put("elements", JSONArray(screen.elements.map(::elementToJson)))
    }

    fun screenFromJson(obj: JSONObject): UiScreenModule {
        return UiScreenModule(
            id = obj.getString("id"),
            name = obj.optString("name", obj.getString("id")),
            aliases = obj.optJSONArray("aliases").stringList(),
            signatures = obj.optJSONArray("signatures").toList { signatureFromJson(it) },
            elements = obj.optJSONArray("elements").toList { elementFromJson(it) }
        )
    }

    private fun paramToJson(param: MacroParam): JSONObject = JSONObject().apply {
        put("name", param.name)
        put("type", param.type.name)
        put("required", param.required)
        put("aliases", JSONArray(param.aliases))
    }

    private fun paramFromJson(obj: JSONObject): MacroParam {
        return MacroParam(
            name = obj.getString("name"),
            type = enumValue(obj.optString("type"), ParamType.TEXT),
            required = obj.optBoolean("required", true),
            aliases = obj.optJSONArray("aliases").stringList()
        )
    }

    private fun atomToJson(atom: MacroAtom): JSONObject = JSONObject().apply {
        when (atom) {
            is MacroAtom.OpenApp -> {
                put("type", "OpenApp")
                put("appPackage", atom.appPackage)
            }
            is MacroAtom.EnsureScreen -> {
                put("type", "EnsureScreen")
                put("screenId", atom.screenId)
                put("recoverPolicy", atom.recoverPolicy.name)
            }
            is MacroAtom.ClickElement -> {
                put("type", "ClickElement")
                put("elementId", atom.elementId)
                put("fallbackAllowed", atom.fallbackAllowed)
            }
            is MacroAtom.TypeText -> {
                put("type", "TypeText")
                put("textParam", atom.textParam)
                atom.targetElementId?.let { put("targetElementId", it) }
            }
            is MacroAtom.SearchInApp -> {
                put("type", "SearchInApp")
                put("queryParam", atom.queryParam)
                put("searchEntryElementId", atom.searchEntryElementId)
            }
            is MacroAtom.SelectItem -> {
                put("type", "SelectItem")
                put("itemTextParam", atom.itemTextParam)
                atom.listElementId?.let { put("listElementId", it) }
            }
            is MacroAtom.ScrollUntilVisible -> {
                put("type", "ScrollUntilVisible")
                put("targetTextParam", atom.targetTextParam)
                atom.containerElementId?.let { put("containerElementId", it) }
                put("maxSwipes", atom.maxSwipes)
            }
            is MacroAtom.WaitFor -> {
                put("type", "WaitFor")
                put("rule", verifyRuleToJson(atom.rule))
                put("timeoutMs", atom.timeoutMs)
            }
            is MacroAtom.Verify -> {
                put("type", "Verify")
                put("rule", verifyRuleToJson(atom.rule))
            }
        }
    }

    private fun atomFromJson(obj: JSONObject): MacroAtom {
        return when (obj.optString("type")) {
            "OpenApp" -> MacroAtom.OpenApp(obj.getString("appPackage"))
            "EnsureScreen" -> MacroAtom.EnsureScreen(
                screenId = obj.getString("screenId"),
                recoverPolicy = enumValue(obj.optString("recoverPolicy"), RecoverPolicy.BACK_TO_KNOWN_SCREEN)
            )
            "ClickElement" -> MacroAtom.ClickElement(
                elementId = obj.getString("elementId"),
                fallbackAllowed = obj.optBoolean("fallbackAllowed", true)
            )
            "TypeText" -> MacroAtom.TypeText(
                textParam = obj.getString("textParam"),
                targetElementId = obj.optStringOrNull("targetElementId")
            )
            "SearchInApp" -> MacroAtom.SearchInApp(
                queryParam = obj.getString("queryParam"),
                searchEntryElementId = obj.optString("searchEntryElementId", "search_entry")
            )
            "SelectItem" -> MacroAtom.SelectItem(
                itemTextParam = obj.getString("itemTextParam"),
                listElementId = obj.optStringOrNull("listElementId")
            )
            "ScrollUntilVisible" -> MacroAtom.ScrollUntilVisible(
                targetTextParam = obj.getString("targetTextParam"),
                containerElementId = obj.optStringOrNull("containerElementId"),
                maxSwipes = obj.optInt("maxSwipes", 6)
            )
            "WaitFor" -> MacroAtom.WaitFor(
                rule = verifyRuleFromJson(obj.optJSONObject("rule")) ?: VerifyRule.AlwaysTrue,
                timeoutMs = obj.optLong("timeoutMs", 3000L)
            )
            "Verify" -> MacroAtom.Verify(verifyRuleFromJson(obj.optJSONObject("rule")) ?: VerifyRule.AlwaysTrue)
            else -> MacroAtom.Verify(VerifyRule.AlwaysTrue)
        }
    }

    fun verifyRuleToJson(rule: VerifyRule): JSONObject = JSONObject().apply {
        when (rule) {
            is VerifyRule.PackageVisible -> {
                put("type", "PackageVisible")
                put("packageName", rule.packageName)
            }
            is VerifyRule.ScreenContains -> {
                put("type", "ScreenContains")
                rule.text?.let { put("text", it) }
                rule.textParam?.let { put("textParam", it) }
            }
            is VerifyRule.MessageSent -> {
                put("type", "MessageSent")
                put("contactParam", rule.contactParam)
                put("messageParam", rule.messageParam)
            }
            VerifyRule.ScreenChanged -> put("type", "ScreenChanged")
            VerifyRule.AlwaysTrue -> put("type", "AlwaysTrue")
        }
    }

    fun verifyRuleFromJson(obj: JSONObject?): VerifyRule? {
        if (obj == null) return null
        return when (obj.optString("type")) {
            "PackageVisible" -> VerifyRule.PackageVisible(obj.getString("packageName"))
            "ScreenContains" -> VerifyRule.ScreenContains(
                text = obj.optStringOrNull("text"),
                textParam = obj.optStringOrNull("textParam")
            )
            "MessageSent" -> VerifyRule.MessageSent(
                contactParam = obj.optString("contactParam", "contact"),
                messageParam = obj.optString("messageParam", "message")
            )
            "ScreenChanged" -> VerifyRule.ScreenChanged
            "AlwaysTrue" -> VerifyRule.AlwaysTrue
            else -> null
        }
    }

    private fun signatureToJson(signature: UiSignature): JSONObject = JSONObject().apply {
        put("requiredTextsAny", JSONArray(signature.requiredTextsAny))
        put("requiredTextsAll", JSONArray(signature.requiredTextsAll))
        put("requiredResourceIdsAny", JSONArray(signature.requiredResourceIdsAny))
        signature.packageName?.let { put("packageName", it) }
        signature.activityName?.let { put("activityName", it) }
        put("minScore", signature.minScore.toDouble())
    }

    private fun signatureFromJson(obj: JSONObject): UiSignature = UiSignature(
        requiredTextsAny = obj.optJSONArray("requiredTextsAny").stringList(),
        requiredTextsAll = obj.optJSONArray("requiredTextsAll").stringList(),
        requiredResourceIdsAny = obj.optJSONArray("requiredResourceIdsAny").stringList(),
        packageName = obj.optStringOrNull("packageName"),
        activityName = obj.optStringOrNull("activityName"),
        minScore = obj.optDouble("minScore", 0.75).toFloat()
    )

    private fun elementToJson(element: UiElementSpec): JSONObject = JSONObject().apply {
        put("id", element.id)
        put("name", element.name)
        put("role", element.role.name)
        put("matchers", JSONArray(element.matchers.map(::matcherToJson)))
        element.coordinatePack?.let { put("coordinatePack", coordinatePackToJson(it)) }
        element.requiredScreenId?.let { put("requiredScreenId", it) }
        put("confidence", element.confidence.toDouble())
        element.lastVerifiedAt?.let { put("lastVerifiedAt", it) }
    }

    private fun elementFromJson(obj: JSONObject): UiElementSpec = UiElementSpec(
        id = obj.getString("id"),
        name = obj.optString("name", obj.getString("id")),
        role = enumValue(obj.optString("role"), UiRole.UNKNOWN),
        matchers = obj.optJSONArray("matchers").toList { matcherFromJson(it) },
        coordinatePack = obj.optJSONObject("coordinatePack")?.let(::coordinatePackFromJson),
        requiredScreenId = obj.optStringOrNull("requiredScreenId"),
        confidence = obj.optDouble("confidence", 1.0).toFloat(),
        lastVerifiedAt = if (obj.has("lastVerifiedAt")) obj.optLong("lastVerifiedAt") else null
    )

    private fun matcherToJson(matcher: ElementMatcher): JSONObject = JSONObject().apply {
        matcher.text?.let { put("text", it) }
        matcher.textContains?.let { put("textContains", it) }
        matcher.contentDesc?.let { put("contentDesc", it) }
        matcher.contentDescContains?.let { put("contentDescContains", it) }
        matcher.resourceId?.let { put("resourceId", it) }
        matcher.className?.let { put("className", it) }
        matcher.clickable?.let { put("clickable", it) }
        matcher.editable?.let { put("editable", it) }
    }

    private fun matcherFromJson(obj: JSONObject): ElementMatcher = ElementMatcher(
        text = obj.optStringOrNull("text"),
        textContains = obj.optStringOrNull("textContains"),
        contentDesc = obj.optStringOrNull("contentDesc"),
        contentDescContains = obj.optStringOrNull("contentDescContains"),
        resourceId = obj.optStringOrNull("resourceId"),
        className = obj.optStringOrNull("className"),
        clickable = obj.optBooleanOrNull("clickable"),
        editable = obj.optBooleanOrNull("editable")
    )

    private fun coordinatePackToJson(pack: CoordinatePack): JSONObject = JSONObject().apply {
        put("screenWidth", pack.screenWidth)
        put("screenHeight", pack.screenHeight)
        pack.densityDpi?.let { put("densityDpi", it) }
        put("normalizedBounds", rectToJson(pack.normalizedBounds))
        pack.anchor?.let { put("anchor", anchorToJson(it)) }
        put("fallbackTap", pointToJson(pack.fallbackTap))
        put("confidence", pack.confidence.toDouble())
        put("source", pack.source.name)
        put("samples", JSONArray(pack.samples.map(::sampleToJson)))
    }

    private fun coordinatePackFromJson(obj: JSONObject): CoordinatePack = CoordinatePack(
        screenWidth = obj.optInt("screenWidth", 1080),
        screenHeight = obj.optInt("screenHeight", 2400),
        densityDpi = if (obj.has("densityDpi")) obj.optInt("densityDpi") else null,
        normalizedBounds = rectFromJson(obj.getJSONObject("normalizedBounds")),
        anchor = obj.optJSONObject("anchor")?.let(::anchorFromJson),
        fallbackTap = pointFromJson(obj.getJSONObject("fallbackTap")),
        confidence = obj.optDouble("confidence", 0.5).toFloat(),
        source = enumValue(obj.optString("source"), CoordinateSource.USER_RECORDING),
        samples = obj.optJSONArray("samples").toList { sampleFromJson(it) }
    )

    private fun rectToJson(rect: NormalizedRect): JSONObject = JSONObject().apply {
        put("left", rect.left.toDouble())
        put("top", rect.top.toDouble())
        put("right", rect.right.toDouble())
        put("bottom", rect.bottom.toDouble())
    }

    private fun rectFromJson(obj: JSONObject): NormalizedRect = NormalizedRect(
        left = obj.optDouble("left", 0.0).toFloat(),
        top = obj.optDouble("top", 0.0).toFloat(),
        right = obj.optDouble("right", 0.0).toFloat(),
        bottom = obj.optDouble("bottom", 0.0).toFloat()
    )

    private fun pointToJson(point: NormalizedPoint): JSONObject = JSONObject().apply {
        put("x", point.x.toDouble())
        put("y", point.y.toDouble())
    }

    private fun pointFromJson(obj: JSONObject): NormalizedPoint = NormalizedPoint(
        x = obj.optDouble("x", 0.0).toFloat(),
        y = obj.optDouble("y", 0.0).toFloat()
    )

    private fun anchorToJson(anchor: AnchorSpec): JSONObject = JSONObject().apply {
        anchor.relativeToElementId?.let { put("relativeToElementId", it) }
        anchor.horizontal?.let { put("horizontal", it) }
        anchor.vertical?.let { put("vertical", it) }
    }

    private fun anchorFromJson(obj: JSONObject): AnchorSpec = AnchorSpec(
        relativeToElementId = obj.optStringOrNull("relativeToElementId"),
        horizontal = obj.optStringOrNull("horizontal"),
        vertical = obj.optStringOrNull("vertical")
    )

    private fun sampleToJson(sample: CoordinateSample): JSONObject = JSONObject().apply {
        put("screenWidth", sample.screenWidth)
        put("screenHeight", sample.screenHeight)
        put("x", sample.x)
        put("y", sample.y)
        put("timestamp", sample.timestamp)
    }

    private fun sampleFromJson(obj: JSONObject): CoordinateSample = CoordinateSample(
        screenWidth = obj.optInt("screenWidth", 1080),
        screenHeight = obj.optInt("screenHeight", 2400),
        x = obj.optInt("x", 0),
        y = obj.optInt("y", 0),
        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
    )

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T {
        return runCatching { enumValueOf<T>(raw ?: "") }.getOrDefault(fallback)
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        val value = optString(name)
        return value.ifBlank { null }
    }

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? {
        return if (has(name) && !isNull(name)) optBoolean(name) else null
    }

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }

    private fun <T> JSONArray?.toList(mapper: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let(mapper)
        }
    }
}
