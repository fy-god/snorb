package com.macropilot.app.store

import android.content.Context
import com.macropilot.app.model.AppUiModule
import com.macropilot.app.model.CoordinatePack
import com.macropilot.app.model.CoordinateSource
import com.macropilot.app.model.CoordinateStatus
import com.macropilot.app.model.ElementMatcher
import com.macropilot.app.model.ElementStatus
import com.macropilot.app.model.ModuleStatus
import com.macropilot.app.model.NormalizedPoint
import com.macropilot.app.model.NormalizedRect
import com.macropilot.app.model.RecoverAction
import com.macropilot.app.model.ReliabilityLevel
import com.macropilot.app.model.UiElementSpec
import com.macropilot.app.model.UiRole
import com.macropilot.app.model.UiScreenModule
import com.macropilot.app.model.UiSignature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppUiModuleStore(private val context: Context) {
    private val approvedStore = ApprovedKnowledgeStore(context)
    private val candidateStore = CandidateKnowledgeStore(context)

    fun saveApproved(module: AppUiModule): File {
        approvedStore.ensureCreated()
        val appDir = File(approvedStore.appModuleDir, module.packageName)
        appDir.mkdirs()
        val file = File(appDir, "app.json")
        file.writeText(toJson(module).toString(2))
        return file
    }

    fun appDir(packageName: String): File {
        return File(approvedStore.appModuleDir, packageName)
    }

    fun loadApproved(packageName: String): AppUiModule? {
        val file = File(appDir(packageName), "app.json")
        if (!file.isFile) return null
        return fromJson(JSONObject(file.readText()))
    }

    fun listApproved(): List<AppUiModule> {
        approvedStore.ensureCreated()
        return approvedStore.appModuleDir
            .listFiles { file -> file.isDirectory }
            ?.mapNotNull { dir -> File(dir, "app.json").takeIf { it.isFile } }
            ?.mapNotNull { file -> runCatching { fromJson(JSONObject(file.readText())) }.getOrNull() }
            .orEmpty()
    }

    fun saveCandidate(module: AppUiModule, sourceId: String): File {
        candidateStore.ensureCreated()
        val appDir = File(candidateStore.candidateAppModuleDir, module.packageName)
        appDir.mkdirs()
        val file = File(appDir, "${sourceId.ifBlank { "candidate" }}.json")
        file.writeText(toJson(module).toString(2))
        return file
    }

    fun listCandidates(): List<Pair<File, AppUiModule>> {
        candidateStore.ensureCreated()
        if (!candidateStore.candidateAppModuleDir.isDirectory) return emptyList()
        return candidateStore.candidateAppModuleDir.walkTopDown()
            .filter { file -> file.isFile && file.extension == "json" }
            .mapNotNull { file ->
                runCatching { file to fromJson(JSONObject(file.readText())) }.getOrNull()
            }
            .toList()
    }

    fun loadCandidateFile(path: String): AppUiModule? {
        val file = File(path)
        if (!file.isFile) return null
        return runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun toJson(module: AppUiModule): JSONObject {
        return JSONObject()
            .put("schemaVersion", module.schemaVersion)
            .put("packageName", module.packageName)
            .put("packageAliases", JSONArray(module.packageAliases))
            .put("appName", module.appName)
            .put("appVersionName", module.appVersionName)
            .put("appVersionCode", module.appVersionCode)
            .put("status", module.status.name)
            .put("updatedAt", module.updatedAt)
            .put("screens", JSONArray(module.screens.map { toJson(it) }))
            .put("elements", JSONArray(module.elements.map { toJson(it) }))
    }

    private fun toJson(screen: UiScreenModule): JSONObject {
        return JSONObject()
            .put("id", screen.id)
            .put("name", screen.name)
            .put("signatures", JSONArray(screen.signatures.map { toJson(it) }))
            .put("recoverActions", JSONArray(screen.recoverActions.map { toJson(it) }))
    }

    private fun toJson(signature: UiSignature): JSONObject {
        return JSONObject()
            .put("packageName", signature.packageName)
            .put("activityName", signature.activityName)
            .put("requiredTextsAny", JSONArray(signature.requiredTextsAny))
            .put("requiredTextsAll", JSONArray(signature.requiredTextsAll))
            .put("requiredResourceIdsAny", JSONArray(signature.requiredResourceIdsAny))
            .put("requiredRolesAny", JSONArray(signature.requiredRolesAny.map { it.name }))
            .put("negativeTexts", JSONArray(signature.negativeTexts))
            .put("minScore", signature.minScore)
    }

    private fun toJson(element: UiElementSpec): JSONObject {
        return JSONObject()
            .put("id", element.id)
            .put("name", element.name)
            .put("role", element.role.name)
            .put("matchers", JSONArray(element.matchers.map { toJson(it) }))
            .put("coordinatePack", element.coordinatePack?.let { toJson(it) })
            .put("requiredScreenId", element.requiredScreenId)
            .put("reliability", element.reliability.name)
            .put("samples", element.samples)
            .put("successRate", element.successRate)
            .put("status", element.status.name)
    }

    private fun toJson(matcher: ElementMatcher): JSONObject {
        return JSONObject()
            .put("text", matcher.text)
            .put("textContains", matcher.textContains)
            .put("contentDesc", matcher.contentDesc)
            .put("contentDescContains", matcher.contentDescContains)
            .put("resourceId", matcher.resourceId)
            .put("className", matcher.className)
            .put("clickable", matcher.clickable)
            .put("editable", matcher.editable)
            .put("scrollable", matcher.scrollable)
            .put("enabled", matcher.enabled)
    }

    private fun toJson(pack: CoordinatePack): JSONObject {
        return JSONObject()
            .put("baseWidth", pack.baseWidth)
            .put("baseHeight", pack.baseHeight)
            .put("densityDpi", pack.densityDpi)
            .put("fontScale", pack.fontScale)
            .put("navigationMode", pack.navigationMode)
            .put("keyboardVisible", pack.keyboardVisible)
            .put("normalizedBounds", toJson(pack.normalizedBounds))
            .put("fallbackTap", toJson(pack.fallbackTap))
            .put("anchorElementId", pack.anchorElementId)
            .put("confidence", pack.confidence)
            .put("source", pack.source.name)
            .put("successRate", pack.successRate)
            .put("lastVerifiedAt", pack.lastVerifiedAt)
            .put("status", pack.status.name)
    }

    private fun toJson(rect: NormalizedRect): JSONObject {
        return JSONObject()
            .put("left", rect.left)
            .put("top", rect.top)
            .put("right", rect.right)
            .put("bottom", rect.bottom)
    }

    private fun toJson(point: NormalizedPoint): JSONObject {
        return JSONObject()
            .put("x", point.x)
            .put("y", point.y)
    }

    private fun toJson(action: RecoverAction): JSONObject {
        return when (action) {
            RecoverAction.Back -> JSONObject().put("type", "Back")
            is RecoverAction.ClickElement -> JSONObject()
                .put("type", "ClickElement")
                .put("elementId", action.elementId)
            is RecoverAction.WaitForText -> JSONObject()
                .put("type", "WaitForText")
                .put("text", action.text)
                .put("timeoutMs", action.timeoutMs)
        }
    }

    fun fromJson(json: JSONObject): AppUiModule {
        return AppUiModule(
            schemaVersion = json.optInt("schemaVersion", 1),
            packageName = json.getString("packageName"),
            packageAliases = json.optJSONArray("packageAliases").toStringList(),
            appName = json.optString("appName", json.getString("packageName")),
            appVersionName = json.optNullableString("appVersionName"),
            appVersionCode = json.optNullableLong("appVersionCode"),
            screens = json.optJSONArray("screens").toList { fromScreenJson(it) },
            elements = json.optJSONArray("elements").toList { fromElementJson(it) },
            status = enumValue(json.optString("status"), ModuleStatus.ACTIVE),
            updatedAt = json.optLong("updatedAt", 0L)
        )
    }

    private fun fromScreenJson(json: JSONObject): UiScreenModule {
        return UiScreenModule(
            id = json.getString("id"),
            name = json.optString("name", json.getString("id")),
            signatures = json.optJSONArray("signatures").toList { fromSignatureJson(it) },
            recoverActions = json.optJSONArray("recoverActions").toList { fromRecoverActionJson(it) }
        )
    }

    private fun fromSignatureJson(json: JSONObject): UiSignature {
        return UiSignature(
            packageName = json.getString("packageName"),
            activityName = json.optNullableString("activityName"),
            requiredTextsAny = json.optJSONArray("requiredTextsAny").toStringList(),
            requiredTextsAll = json.optJSONArray("requiredTextsAll").toStringList(),
            requiredResourceIdsAny = json.optJSONArray("requiredResourceIdsAny").toStringList(),
            requiredRolesAny = json.optJSONArray("requiredRolesAny").toStringList().map { enumValue(it, UiRole.UNKNOWN) },
            negativeTexts = json.optJSONArray("negativeTexts").toStringList(),
            minScore = json.optDouble("minScore", 0.75).toFloat()
        )
    }

    private fun fromElementJson(json: JSONObject): UiElementSpec {
        return UiElementSpec(
            id = json.getString("id"),
            name = json.optString("name", json.getString("id")),
            role = enumValue(json.optString("role"), UiRole.UNKNOWN),
            matchers = json.optJSONArray("matchers").toList { fromMatcherJson(it) },
            coordinatePack = json.optJSONObject("coordinatePack")?.let { fromCoordinatePackJson(it) },
            requiredScreenId = json.optNullableString("requiredScreenId"),
            reliability = enumValue(json.optString("reliability"), ReliabilityLevel.UNKNOWN),
            samples = json.optInt("samples", 0),
            successRate = json.optDouble("successRate", 0.0).toFloat(),
            status = enumValue(json.optString("status"), ElementStatus.ACTIVE)
        )
    }

    private fun fromMatcherJson(json: JSONObject): ElementMatcher {
        return ElementMatcher(
            text = json.optNullableString("text"),
            textContains = json.optNullableString("textContains"),
            contentDesc = json.optNullableString("contentDesc"),
            contentDescContains = json.optNullableString("contentDescContains"),
            resourceId = json.optNullableString("resourceId"),
            className = json.optNullableString("className"),
            clickable = json.optNullableBoolean("clickable"),
            editable = json.optNullableBoolean("editable"),
            scrollable = json.optNullableBoolean("scrollable"),
            enabled = json.optNullableBoolean("enabled")
        )
    }

    private fun fromCoordinatePackJson(json: JSONObject): CoordinatePack {
        return CoordinatePack(
            baseWidth = json.getInt("baseWidth"),
            baseHeight = json.getInt("baseHeight"),
            densityDpi = json.optNullableInt("densityDpi"),
            fontScale = json.optNullableFloat("fontScale"),
            navigationMode = json.optNullableString("navigationMode"),
            keyboardVisible = json.optNullableBoolean("keyboardVisible"),
            normalizedBounds = fromNormalizedRectJson(json.getJSONObject("normalizedBounds")),
            fallbackTap = fromNormalizedPointJson(json.getJSONObject("fallbackTap")),
            anchorElementId = json.optNullableString("anchorElementId"),
            confidence = json.optDouble("confidence", 0.0).toFloat(),
            source = enumValue(json.optString("source"), CoordinateSource.USER_RECORDING),
            successRate = json.optDouble("successRate", 0.0).toFloat(),
            lastVerifiedAt = json.optLong("lastVerifiedAt", 0L),
            status = enumValue(json.optString("status"), CoordinateStatus.ACTIVE)
        )
    }

    private fun fromNormalizedRectJson(json: JSONObject): NormalizedRect {
        return NormalizedRect(
            left = json.optDouble("left", 0.0).toFloat(),
            top = json.optDouble("top", 0.0).toFloat(),
            right = json.optDouble("right", 0.0).toFloat(),
            bottom = json.optDouble("bottom", 0.0).toFloat()
        )
    }

    private fun fromNormalizedPointJson(json: JSONObject): NormalizedPoint {
        return NormalizedPoint(
            x = json.optDouble("x", 0.0).toFloat(),
            y = json.optDouble("y", 0.0).toFloat()
        )
    }

    private fun fromRecoverActionJson(json: JSONObject): RecoverAction {
        return when (val type = json.optString("type")) {
            "Back" -> RecoverAction.Back
            "ClickElement" -> RecoverAction.ClickElement(json.getString("elementId"))
            "WaitForText" -> RecoverAction.WaitForText(
                text = json.getString("text"),
                timeoutMs = json.optLong("timeoutMs", 1000L)
            )
            else -> error("Unknown RecoverAction type $type")
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
    }

    private fun <T> JSONArray?.toList(mapper: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(mapper) }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableLong(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return optLong(name)
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return optInt(name)
    }

    private fun JSONObject.optNullableFloat(name: String): Float? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name).toFloat()
    }

    private fun JSONObject.optNullableBoolean(name: String): Boolean? {
        if (!has(name) || isNull(name)) return null
        return optBoolean(name)
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T {
        if (name.isNullOrBlank()) return fallback
        return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
    }
}
