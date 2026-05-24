package com.macropilot.app.store

import android.content.Context
import com.macropilot.app.model.MacroAtom
import com.macropilot.app.model.MacroParam
import com.macropilot.app.model.MacroTemplate
import com.macropilot.app.model.SideEffectLevel
import com.macropilot.app.model.SkillSource
import com.macropilot.app.model.VerificationCapability
import com.macropilot.app.model.VerifyRule
import com.macropilot.app.model.VerifyRuleType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MacroTemplateStore(private val context: Context) {
    private val approvedStore = ApprovedKnowledgeStore(context)
    private val candidateStore = CandidateKnowledgeStore(context)

    fun saveApproved(template: MacroTemplate): File {
        approvedStore.ensureCreated()
        val file = File(approvedStore.skillDir, "${template.id}.json")
        file.writeText(toJson(template).toString(2))
        return file
    }

    fun loadApproved(id: String): MacroTemplate? {
        approvedStore.ensureCreated()
        val file = File(approvedStore.skillDir, "$id.json")
        if (!file.isFile) return null
        return fromJson(JSONObject(file.readText()))
    }

    fun listApproved(): List<MacroTemplate> {
        return approvedStore.approvedSkillFiles()
            .mapNotNull { file -> runCatching { fromJson(JSONObject(file.readText())) }.getOrNull() }
    }

    fun deleteApproved(id: String): Boolean {
        approvedStore.ensureCreated()
        val file = File(approvedStore.skillDir, "$id.json")
        return file.isFile && file.delete()
    }

    fun saveCandidate(template: MacroTemplate): File {
        candidateStore.ensureCreated()
        val file = File(candidateStore.candidateSkillDir, "${template.id}.json")
        file.writeText(
            toJson(template)
                .put("type", "SkillTemplateDraft")
                .put("runtimeExecutable", false)
                .put("createdAt", System.currentTimeMillis())
                .toString(2)
        )
        return file
    }

    fun loadCandidate(id: String): MacroTemplate? {
        candidateStore.ensureCreated()
        val safe = id.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".json")
        val file = File(candidateStore.candidateSkillDir, "$safe.json")
        if (!file.isFile) return null
        return runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun listCandidates(): List<Pair<File, MacroTemplate>> {
        candidateStore.ensureCreated()
        return candidateStore.candidateSkillDir
            .listFiles { file -> file.isFile && file.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { file to fromJson(JSONObject(file.readText())) }.getOrNull()
            }
            .orEmpty()
    }

    fun toJson(template: MacroTemplate): JSONObject {
        return JSONObject()
            .put("id", template.id)
            .put("name", template.name)
            .put("packageName", template.packageName)
            .put("intentId", template.intentId)
            .put("version", template.version)
            .put("source", template.source.name)
            .put("sideEffectLevel", template.sideEffectLevel.name)
            .put("idempotent", template.idempotent)
            .put("params", JSONArray(template.params.map { toJson(it) }))
            .put("atoms", JSONArray(template.atoms.map { toJson(it) }))
            .put("finalVerify", toJson(template.finalVerify))
    }

    private fun toJson(param: MacroParam): JSONObject {
        return JSONObject()
            .put("name", param.name)
            .put("required", param.required)
            .put("sensitive", param.sensitive)
    }

    private fun toJson(atom: MacroAtom): JSONObject {
        return when (atom) {
            is MacroAtom.OpenApp -> JSONObject()
                .put("type", "OpenApp")
                .put("packageName", atom.packageName)
                .put("timeoutMs", atom.timeoutMs)
            is MacroAtom.EnsureScreen -> JSONObject()
                .put("type", "EnsureScreen")
                .put("screenId", atom.screenId)
                .put("timeoutMs", atom.timeoutMs)
            is MacroAtom.ClickElement -> JSONObject()
                .put("type", "ClickElement")
                .put("elementId", atom.elementId)
                .put("timeoutMs", atom.timeoutMs)
            is MacroAtom.FocusTextInput -> JSONObject()
                .put("type", "FocusTextInput")
                .put("targetElementId", atom.targetElementId)
                .put("toggleElementIds", JSONArray(atom.toggleElementIds))
                .put("timeoutMs", atom.timeoutMs)
            is MacroAtom.TypeText -> JSONObject()
                .put("type", "TypeText")
                .put("textParam", atom.textParam)
                .put("targetElementId", atom.targetElementId)
                .put("timeoutMs", atom.timeoutMs)
            is MacroAtom.SearchInApp -> JSONObject()
                .put("type", "SearchInApp")
                .put("queryParam", atom.queryParam)
                .put("searchEntryElementId", atom.searchEntryElementId)
                .put("timeoutMs", atom.timeoutMs)
            is MacroAtom.SelectItem -> JSONObject()
                .put("type", "SelectItem")
                .put("itemTextParam", atom.itemTextParam)
                .put("listElementId", atom.listElementId)
                .put("timeoutMs", atom.timeoutMs)
            is MacroAtom.ScrollUntilVisible -> JSONObject()
                .put("type", "ScrollUntilVisible")
                .put("targetTextParam", atom.targetTextParam)
                .put("listElementId", atom.listElementId)
                .put("maxSwipes", atom.maxSwipes)
                .put("timeoutMs", atom.timeoutMs)
            is MacroAtom.WaitFor -> JSONObject()
                .put("type", "WaitFor")
                .put("rule", toJson(atom.rule))
                .put("timeoutMs", atom.timeoutMs)
            is MacroAtom.Verify -> JSONObject()
                .put("type", "Verify")
                .put("rule", toJson(atom.rule))
                .put("timeoutMs", atom.timeoutMs)
        }
    }

    private fun toJson(rule: VerifyRule): JSONObject {
        return JSONObject()
            .put("type", rule.type.name)
            .put("textParam", rule.textParam)
            .put("literalText", rule.literalText)
            .put("elementId", rule.elementId)
            .put("packageName", rule.packageName)
            .put("screenId", rule.screenId)
            .put("minVerification", rule.minVerification.name)
    }

    fun fromJson(json: JSONObject): MacroTemplate {
        return MacroTemplate(
            id = json.getString("id"),
            name = json.optString("name", json.getString("id")),
            packageName = json.getString("packageName"),
            intentId = json.getString("intentId"),
            params = json.optJSONArray("params").toList { fromParamJson(it) },
            atoms = json.optJSONArray("atoms").toList { fromAtomJson(it) },
            finalVerify = fromRuleJson(json.getJSONObject("finalVerify")),
            version = json.optInt("version", 1),
            source = enumValue(json.optString("source"), SkillSource.APPROVED),
            sideEffectLevel = enumValue(json.optString("sideEffectLevel"), SideEffectLevel.LOW),
            idempotent = json.optBoolean("idempotent", true)
        )
    }

    private fun fromParamJson(json: JSONObject): MacroParam {
        return MacroParam(
            name = json.getString("name"),
            required = json.optBoolean("required", true),
            sensitive = json.optBoolean("sensitive", false)
        )
    }

    private fun fromAtomJson(json: JSONObject): MacroAtom {
        return when (val type = json.getString("type")) {
            "OpenApp" -> MacroAtom.OpenApp(json.getString("packageName"), json.optLong("timeoutMs", 3000L))
            "EnsureScreen" -> MacroAtom.EnsureScreen(json.getString("screenId"), json.optLong("timeoutMs", 3000L))
            "ClickElement" -> MacroAtom.ClickElement(json.getString("elementId"), json.optLong("timeoutMs", 1500L))
            "FocusTextInput" -> MacroAtom.FocusTextInput(
                targetElementId = json.getString("targetElementId"),
                toggleElementIds = json.optJSONArray("toggleElementIds").stringList(),
                timeoutMs = json.optLong("timeoutMs", 3500L)
            )
            "TypeText" -> MacroAtom.TypeText(
                textParam = json.getString("textParam"),
                targetElementId = json.optNullableString("targetElementId"),
                timeoutMs = json.optLong("timeoutMs", 2000L)
            )
            "SearchInApp" -> MacroAtom.SearchInApp(
                queryParam = json.getString("queryParam"),
                searchEntryElementId = json.getString("searchEntryElementId"),
                timeoutMs = json.optLong("timeoutMs", 5000L)
            )
            "SelectItem" -> MacroAtom.SelectItem(
                itemTextParam = json.getString("itemTextParam"),
                listElementId = json.optNullableString("listElementId"),
                timeoutMs = json.optLong("timeoutMs", 3000L)
            )
            "ScrollUntilVisible" -> MacroAtom.ScrollUntilVisible(
                targetTextParam = json.getString("targetTextParam"),
                listElementId = json.optNullableString("listElementId"),
                maxSwipes = json.optInt("maxSwipes", 5),
                timeoutMs = json.optLong("timeoutMs", 6000L)
            )
            "WaitFor" -> MacroAtom.WaitFor(fromRuleJson(json.getJSONObject("rule")), json.optLong("timeoutMs", 3000L))
            "Verify" -> MacroAtom.Verify(fromRuleJson(json.getJSONObject("rule")), json.optLong("timeoutMs", 2000L))
            else -> error("Unknown MacroAtom type $type")
        }
    }

    private fun fromRuleJson(json: JSONObject): VerifyRule {
        return VerifyRule(
            type = enumValue(json.optString("type"), VerifyRuleType.ALWAYS),
            textParam = json.optNullableString("textParam"),
            literalText = json.optNullableString("literalText"),
            elementId = json.optNullableString("elementId"),
            packageName = json.optNullableString("packageName"),
            screenId = json.optNullableString("screenId"),
            minVerification = enumValue(json.optString("minVerification"), VerificationCapability.MEDIUM)
        )
    }

    private fun <T> JSONArray?.toList(mapper: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(mapper) }
    }

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T {
        if (name.isNullOrBlank()) return fallback
        return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
    }
}
