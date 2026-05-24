package com.macropilot.app.factory

import com.macropilot.app.safety.SafeActionPolicy
import org.json.JSONArray
import org.json.JSONObject

class SkillJsonDryRunEngine(
    private val safetyPolicy: SafeActionPolicy = SafeActionPolicy()
) {
    fun dryRun(skill: JSONObject, knownSkillIds: Set<String> = emptySet()): JSONObject {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val id = skill.optString("id")
        val kind = skill.optString("kind")
        val status = skill.optString("status")
        val appPackage = skill.optString("appPackage")
        val implementsName = skill.optString("implements")

        if (skill.optInt("schemaVersion", 0) <= 0) errors += "missing_or_invalid_schemaVersion"
        if (id.isBlank()) errors += "missing_id"
        if (kind !in setOf("atomic_skill", "macro_skill", "reactive_rule")) errors += "invalid_kind:$kind"
        if (appPackage.isBlank()) errors += "missing_appPackage"
        if (status == "APPROVED") errors += "candidate_json_must_not_claim_APPROVED"
        if (status.isBlank()) errors += "missing_status"
        if (implementsName.isBlank()) errors += "missing_implements"

        val safetySubject = safetySubjectForSkill(skill)
        val safety = safetyPolicy.evaluateText(safetySubject.toString())
        if (!safety.allowed && !safetyOverrideAllows(skill, safety.matchedKeyword)) {
            errors += "dangerous_action:${safety.matchedKeyword}"
        }

        if (kind == "atomic_skill") {
            validateAtomic(skill, errors, warnings)
        }
        if (kind == "macro_skill") {
            validateMacro(skill, knownSkillIds, errors, warnings)
        }

        val passed = errors.isEmpty()
        return JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_dry_run_report")
            .put("skillId", id)
            .put("appPackage", appPackage)
            .put("passed", passed)
            .put("status", if (passed) "DRY_RUN_PASSED" else "REJECTED")
            .put("errors", JSONArray(errors))
            .put("warnings", JSONArray(warnings))
            .put("summary", if (passed) "Skill JSON dry-run passed" else errors.joinToString("; "))
            .put("timestamp", System.currentTimeMillis())
    }

    private fun safetySubjectForSkill(skill: JSONObject): JSONObject {
        val action = skill.optJSONObject("action")
        val verify = skill.optJSONObject("verify")
        val out = JSONObject()
        action?.let { raw ->
            val cleaned = JSONObject(raw.toString())
            if (cleaned.optString("type") in setOf("OpenApp", "CheckCapability")) {
                cleaned.remove("packageName")
            }
            out.put("action", cleaned)
        }
        verify?.let { raw ->
            val cleaned = JSONObject(raw.toString())
            if (cleaned.optString("type") in setOf("ForegroundPackage", "CapabilityObserved")) {
                cleaned.remove("packageName")
            }
            out.put("verify", cleaned)
        }
        skill.optJSONObject("target")?.let { out.put("target", it) }
        skill.optJSONObject("match")?.let { out.put("match", it) }
        return out
    }

    private fun validateAtomic(skill: JSONObject, errors: MutableList<String>, warnings: MutableList<String>) {
        val action = skill.optJSONObject("action")
        val verify = skill.optJSONObject("verify")
        val resultPolicy = skill.optJSONObject("resultPolicy")
        val capability = skill.optJSONObject("capabilityRequired")
        val target = skill.optJSONObject("target")
        val actionType = action?.optString("type").orEmpty()
        val supportedActions = setOf(
            "ResolveElement",
            "ClickElement",
            "ClickVisibleText",
            "ClickSelector",
            "ClickCoordinate",
            "LongPressCoordinate",
            "OpenHupuPostEditor",
            "TypeText",
            "InputText",
            "PasteText",
            "TypeIntoFirstInput",
            "TypeIntoFocusedInput",
            "FillHupuPostEditor",
            "TypeAtCoordinate",
            "VerifyGoal",
            "Back",
            "OpenApp",
            "OpenBilibiliSearch",
            "CheckCapability",
            "EnsureTextVideoAsset",
            "HandleSystemPermissionDialog",
            "RecoverDouyinHomeSurface",
            "RecoverBilibiliHomeSurface",
            "HandleReactiveOverlays",
            "DismissBlockingOverlay",
            "EnsureHupuSectionSelected",
            "VerifyHupuPostPublished",
            "FindEntryByBottomTabSweep",
            "CaptureWechatBottomTabs",
            "ScrollForward",
            "Wait",
            "EnsureXiaohongshuDraftEditor"
        )

        if (action == null) errors += "atomic_skill_missing_action"
        if (verify == null) errors += "atomic_skill_missing_verify"
        if (resultPolicy == null) errors += "atomic_skill_missing_resultPolicy"
        if (capability == null) errors += "atomic_skill_missing_capabilityRequired"
        if (action != null && actionType !in supportedActions) {
            errors += "unsupported_action_type:$actionType"
        }

        if (actionType in setOf("TypeText", "PasteText", "InputText", "TypeIntoFirstInput", "TypeIntoFocusedInput", "FillHupuPostEditor", "TypeAtCoordinate")) {
            if (capability?.optBoolean("inputRequired", false) != true) {
                errors += "input_skill_missing_inputRequired"
            }
            val channels = capability?.optJSONArray("allowedInputChannels")
            if (channels == null || channels.length() == 0) {
                errors += "input_skill_missing_allowedInputChannels"
            }
        }

        if (actionType in setOf("ClickElement", "TypeText", "ResolveElement") && target == null) {
            warnings += "target_missing_for_element_action"
        }
        if (actionType == "ClickVisibleText" &&
            action?.optString("text").isNullOrBlank() &&
            action?.optString("textParam").isNullOrBlank() &&
            (action?.optJSONArray("textAliases")?.length() ?: 0) == 0
        ) {
            errors += "click_visible_text_missing_text"
        }
        if (actionType == "ClickSelector" && action?.optString("selector").isNullOrBlank() && target == null) {
            errors += "click_selector_missing_selector_or_target"
        }
        if (actionType == "ClickSelector") {
            val selector = action?.optString("selector").orEmpty()
            if (selector.contains(">") || selector.contains(":") || selector.contains(" ")) {
                errors += "click_selector_unsupported_hierarchy_or_css:$selector"
            }
        }
        if (actionType == "ClickCoordinate") {
            val x = action?.optDouble("x", -1.0) ?: -1.0
            val y = action?.optDouble("y", -1.0) ?: -1.0
            if (x !in 0.0..1.0 || y !in 0.0..1.0) errors += "click_coordinate_requires_normalized_xy"
        }
        if (actionType == "TypeAtCoordinate") {
            val x = action?.optDouble("x", -1.0) ?: -1.0
            val y = action?.optDouble("y", -1.0) ?: -1.0
            if (x !in 0.0..1.0 || y !in 0.0..1.0) errors += "type_at_coordinate_requires_normalized_xy"
            if (action?.optString("text").isNullOrBlank() && action?.optString("textParam").isNullOrBlank()) {
                errors += "type_at_coordinate_missing_text"
            }
        }
        if (actionType == "EnsureTextVideoAsset") {
            if (action?.optString("text").isNullOrBlank() && action?.optString("textParam").isNullOrBlank()) {
                errors += "ensure_text_video_asset_missing_text"
            }
        }
        if (actionType == "FindEntryByBottomTabSweep") {
            val aliases = action?.optJSONArray("entryAliases")
                ?: action?.optJSONArray("aliases")
                ?: action?.optJSONArray("textAliases")
            if ((aliases?.length() ?: 0) == 0 &&
                action?.optString("entryText").isNullOrBlank() &&
                action?.optString("text").isNullOrBlank()
            ) {
                errors += "find_entry_by_bottom_tab_sweep_missing_entryAliases"
            }
            val maxTabs = action?.optInt("maxTabs", 0) ?: 0
            if (maxTabs !in 1..8) warnings += "find_entry_by_bottom_tab_sweep_maxTabs_default_or_outside_1_8"
        }

        val coordinatePack = target?.optJSONObject("coordinatePack")
        if (coordinatePack != null) {
            val fallback = coordinatePack.optJSONObject("fallbackTap")
            val confidence = coordinatePack.optDouble("confidence", -1.0)
            if (fallback == null) errors += "coordinatePack_missing_fallbackTap"
            if (confidence !in 0.0..1.0) errors += "coordinatePack_invalid_confidence"
            val resultValues = resultPolicy?.keys()?.asSequence()?.mapNotNull { resultPolicy.optString(it) }?.toList().orEmpty()
            if ("SUCCESS" in resultValues && skill.optJSONObject("capabilityRequired")?.optBoolean("coordinateFallbackAllowed", false) == true) {
                warnings += "coordinate_fallback_present_skill_must_cap_success_by_runtime_confidence"
            }
        }
    }

    private fun safetyOverrideAllows(skill: JSONObject, matchedKeyword: String?): Boolean {
        if (matchedKeyword.isNullOrBlank()) return false
        val safety = skill.optJSONObject("safety") ?: return false
        if (!safety.optBoolean("userConfirmedDangerousAction", false)) return false
        val allowed = safety.optJSONArray("allowedDangerousKeywords") ?: return false
        return (0 until allowed.length()).any { index ->
            allowed.optString(index).equals(matchedKeyword, ignoreCase = true)
        }
    }

    private fun validateMacro(
        skill: JSONObject,
        knownSkillIds: Set<String>,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ) {
        val atoms = skill.optJSONArray("atoms")
        val resultPolicy = skill.optJSONObject("resultPolicy")
        val verify = skill.optJSONObject("verify")
        if (atoms == null || atoms.length() == 0) errors += "macro_skill_missing_atoms"
        if (resultPolicy == null) errors += "macro_skill_missing_resultPolicy"
        if (verify == null) errors += "macro_skill_missing_verify"

        for (i in 0 until (atoms?.length() ?: 0)) {
            val atom = atoms?.optJSONObject(i) ?: continue
            if (atom.optString("type") != "UseSkill") {
                errors += "macro_atom_${i}_must_use_UseSkill"
            }
            val skillId = atom.optString("skillId")
            if (skillId.isBlank()) {
                errors += "macro_atom_${i}_missing_skillId"
            } else if (knownSkillIds.isNotEmpty() && skillId !in knownSkillIds) {
                warnings += "macro_atom_${i}_references_unknown_skill:$skillId"
            }
        }
    }
}
