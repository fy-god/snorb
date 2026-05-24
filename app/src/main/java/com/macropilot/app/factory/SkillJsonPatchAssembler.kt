package com.macropilot.app.factory

import android.content.Context
import com.macropilot.app.store.FactorySkillJsonStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class SkillJsonPatchAssembler(
    private val context: Context,
    private val store: FactorySkillJsonStore = FactorySkillJsonStore(context),
    private val matcher: SkillTemplateMatcher = SkillTemplateMatcher(context, store)
) {
    fun assembleFromPlan(
        instruction: String,
        appPackage: String,
        planSteps: List<JSONObject>,
        aiJobPath: String? = null,
        source: String = "PLAN_PATCH_ASSEMBLER"
    ): SkillJsonPatchAssembly {
        val stamp = System.currentTimeMillis()
        val files = mutableListOf<File>()
        val skillIds = mutableListOf<String>()
        val patchSummaries = JSONArray()

        planSteps.forEachIndexed { index, rawAction ->
            val action = normalizeAction(rawAction, appPackage)
            val match = matcher.bestAtomicTemplate(appPackage, action, instruction)
            val skillId = "patched.${stamp}.${index + 1}.${action.optString("type").safeId()}"
            val patched = patchAtomicSkill(
                sourceSkill = match.source,
                skillId = skillId,
                appPackage = appPackage,
                action = action,
                instruction = instruction,
                index = index,
                match = match,
                aiJobPath = aiJobPath,
                source = source
            )
            files += store.saveSkill(appPackage, patched)
            skillIds += skillId
            patchSummaries.put(JSONObject()
                .put("skillId", skillId)
                .put("actionType", action.optString("type"))
                .put("sourcePath", match.sourcePath)
                .put("sourceOrigin", match.origin)
                .put("matchScore", match.score)
                .put("matchReason", match.reason)
                .put("changedFields", JSONArray(listOf("id", "name", "appPackage", "status", "action", "verify", "factory"))))
        }

        val macro = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "macro_skill")
            .put("id", "patched.command.$stamp")
            .put("name", "手机端任务拼装 Skill")
            .put("appPackage", appPackage)
            .put("appVersion", "*")
            .put("status", "CANDIDATE")
            .put("implements", "AiPlannedMacroSkill")
            .put("intentId", inferIntentId(instruction))
            .put("params", JSONArray())
            .put("preconditions", JSONArray())
            .put("atoms", JSONArray(skillIds.map { id ->
                JSONObject().put("type", "UseSkill").put("skillId", id)
            }))
            .put("verify", verifyForMacro(instruction))
            .put("resultPolicy", JSONObject()
                .put("allAtomicSuccess", "SUCCESS")
                .put("partialAtomicSuccess", "SUCCESS_MEDIUM_CONFIDENCE")
                .put("coordinateOnly", "SUCCESS_LOW_CONFIDENCE")
                .put("unverifiable", "FAILED_UNVERIFIABLE"))
            .put("timeoutMs", 30_000)
            .put("factory", factoryMeta(
                source = source,
                instruction = instruction,
                aiJobPath = aiJobPath,
                extra = JSONObject()
                    .put("assemblyStrategy", "rewrite_allowed_slots_from_existing_skill_json")
                    .put("requiredAtomicSkills", JSONArray(skillIds))
            ))
        val macroFile = store.saveSkill(appPackage, macro)
        files += macroFile

        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "skill_json_patch_assembly_report")
            .put("id", "patch_assembly_${stamp}")
            .put("appPackage", appPackage)
            .put("instruction", instruction)
            .put("source", source)
            .put("aiJobPath", aiJobPath ?: JSONObject.NULL)
            .put("planStepCount", planSteps.size)
            .put("generatedSkillCount", files.size)
            .put("macroSkillId", macro.optString("id"))
            .put("macroFile", macroFile.absolutePath)
            .put("patches", patchSummaries)
            .put("rule", "DeepSeek/规则只给流程和关键 slot；Assembler 只允许替换 action/verify/params/target/metadata 等安全槽位，然后统一 dry-run/test/promotion。")
            .put("createdAt", System.currentTimeMillis())
        val reportFile = store.saveAssemblyReport(appPackage, report)
        return SkillJsonPatchAssembly(files, macroFile, reportFile, report)
    }

    private fun patchAtomicSkill(
        sourceSkill: JSONObject,
        skillId: String,
        appPackage: String,
        action: JSONObject,
        instruction: String,
        index: Int,
        match: SkillTemplateMatch,
        aiJobPath: String?,
        source: String
    ): JSONObject {
        val base = if (sourceSkill.length() > 0) JSONObject(sourceSkill.toString()) else JSONObject()
        base.put("schemaVersion", 1)
        base.put("kind", "atomic_skill")
        base.put("id", skillId)
        base.put("name", "改写步骤 ${index + 1}: ${action.optString("type")}")
        base.put("appPackage", appPackage)
        base.put("appVersion", base.optString("appVersion").ifBlank { "*" })
        base.put("status", "CANDIDATE")
        base.put("implements", implementsForAction(action.optString("type")))
        if (!base.has("params")) base.put("params", JSONArray())
        if (!base.has("preconditions")) base.put("preconditions", JSONArray())
        if (!base.has("dependsOn")) base.put("dependsOn", JSONArray())
        base.put("capabilityRequired", capabilityForAction(action))
        base.put("resolverPolicy", JSONObject()
            .put("order", JSONArray(listOf("ACCESSIBILITY", "APP_UI_GRAPH", "COORDINATE_PACK", "AI_PATCH")))
            .put("allowAiSuggestionInFactory", true)
            .put("allowAiInRuntime", true))
        base.put("safety", base.optJSONObject("safety") ?: JSONObject())
        base.put("action", action)
        base.put("verify", verifyForAction(action))
        base.put("resultPolicy", JSONObject()
            .put("verified", "SUCCESS")
            .put("partialVerified", "SUCCESS_MEDIUM_CONFIDENCE")
            .put("coordinateOnly", "SUCCESS_LOW_CONFIDENCE")
            .put("unverifiable", "FAILED_UNVERIFIABLE"))
        base.put("factory", factoryMeta(
            source = source,
            instruction = instruction,
            aiJobPath = aiJobPath,
            extra = JSONObject()
                .put("sourceSkillId", sourceSkill.optString("id"))
                .put("sourceSkillPath", match.sourcePath)
                .put("sourceOrigin", match.origin)
                .put("templateMatchScore", match.score)
                .put("templateMatchReason", match.reason)
                .put("patchReason", "reuse_existing_skill_json_and_rewrite_key_slots")
        ))
        return base
    }

    private fun capabilityForAction(action: JSONObject): JSONObject {
        val type = action.optString("type")
        val input = type in setOf("TypeIntoFirstInput", "TypeIntoFocusedInput", "TypeAtCoordinate", "FillHupuPostEditor")
        val coordinate = type in setOf(
            "ClickCoordinate",
            "LongPressCoordinate",
            "TypeAtCoordinate",
            "FindEntryByBottomTabSweep",
            "CaptureWechatBottomTabs",
            "OpenHupuPostEditor",
            "RecoverDouyinHomeSurface",
            "RecoverBilibiliHomeSurface"
        ) || (type == "ClickVisibleText" && (action.has("x") || action.has("y")))
        return JSONObject()
            .put("minLevel", if (input || coordinate) "C" else "D")
            .put("inputRequired", input)
            .put("nodeReadablePreferred", true)
            .put("coordinateFallbackAllowed", coordinate)
            .put("allowedInputChannels", JSONArray(if (input) SkillJsonArchetypeFactory.inputChannels() else emptyList<String>()))
            .put("verificationRequired", if (coordinate) "LOW" else "MEDIUM")
    }

    private fun verifyForAction(action: JSONObject): JSONObject {
        return when (action.optString("type")) {
            "OpenApp" -> JSONObject()
                .put("type", "ForegroundPackage")
                .put("packageName", action.optString("packageName"))
                .put("timeoutMs", action.optLong("timeoutMs", 4500))
                .put("retryTimeoutMs", action.optLong("retryTimeoutMs", 4500))
                .put("allowUnverifiedWhenAccessibilityDisconnected", true)
            "TypeIntoFirstInput", "TypeIntoFocusedInput", "TypeAtCoordinate", "FillHupuPostEditor" -> JSONObject()
                .put("type", "InputContainsOrTextChangedEvent")
                .put("text", action.optString("text").ifBlank { action.optString("title").ifBlank { action.optString("body") } })
                .put("timeoutMs", action.optLong("timeoutMs", 1800))
                .put("allowMediumConfidenceWhenEventOnly", true)
                .put("allowLowConfidenceWhenUnverified", true)
            "Wait", "EnsureTextVideoAsset", "HandleReactiveOverlays", "FindEntryByBottomTabSweep", "CaptureWechatBottomTabs",
            "RecoverDouyinHomeSurface", "RecoverBilibiliHomeSurface", "EnsureHupuSectionSelected", "OpenHupuPostEditor" -> JSONObject()
                .put("type", "Always")
                .put("timeoutMs", 1)
            "VerifyHupuPostPublished" -> JSONObject()
                .put("type", "HupuPostPublished")
                .put("timeoutMs", action.optLong("timeoutMs", 6000))
            else -> JSONObject()
                .put("type", "WindowChangedOrNodeStateChanged")
                .put("timeoutMs", action.optLong("timeoutMs", 1500))
                .put("allowLowConfidenceWhenCoordinateOnly", true)
        }
    }

    private fun verifyForMacro(instruction: String): JSONObject {
        return JSONObject()
            .put("type", "GoalEvidenceOrNoFailedAtom")
            .put("instruction", instruction.take(240))
            .put("timeoutMs", 2_000)
            .put("allowLowConfidenceWhenNodeUnreadable", true)
    }

    private fun normalizeAction(raw: JSONObject, appPackage: String): JSONObject {
        val action = raw.optJSONObject("action") ?: raw
        val type = when (action.optString("type").ifBlank { action.optString("action") }.lowercase(Locale.US)) {
            "open_app", "openapp", "launch_app", "launchapp" -> "OpenApp"
            "click_text", "clickvisibletext", "tap_text", "tapvisibletext" -> "ClickVisibleText"
            "click_selector", "clickselector", "tap_selector" -> "ClickSelector"
            "click_coordinate", "clickcoordinate", "tap_coordinate" -> "ClickCoordinate"
            "long_press_coordinate", "longpresscoordinate", "long_press" -> "LongPressCoordinate"
            "type_text", "typetext", "input_text", "typeintofirstinput" -> "TypeIntoFirstInput"
            "type_focused_input", "typefocusedinput", "typeintofocusedinput" -> "TypeIntoFocusedInput"
            "type_at_coordinate", "typeatcoordinate", "tap_and_type" -> "TypeAtCoordinate"
            "back", "press_back" -> "Back"
            "wait", "sleep" -> "Wait"
            "scroll", "scroll_forward", "scrollforward" -> "ScrollForward"
            "handle_reactive_overlays", "dismiss_overlay", "dismissblockingoverlay" -> "HandleReactiveOverlays"
            "find_entry_by_bottom_tab_sweep", "bottom_tab_sweep", "find_entry" -> "FindEntryByBottomTabSweep"
            else -> action.optString("type").ifBlank { "Wait" }
        }
        val out = JSONObject(action.toString()).put("type", type)
        if (type == "OpenApp" && out.optString("packageName").isBlank()) out.put("packageName", appPackage)
        if (type == "OpenApp" && action.optBoolean("launcherRoot", false)) out.put("launcherRoot", true)
        if (type == "OpenApp" && action.has("retryTimeoutMs")) out.put("retryTimeoutMs", action.optLong("retryTimeoutMs", 4500L))
        if ((type == "HandleReactiveOverlays" || type == "FindEntryByBottomTabSweep") && out.optString("appPackage").isBlank()) {
            out.put("appPackage", appPackage)
        }
        if (type == "FindEntryByBottomTabSweep" && out.optString("safetyReason").isBlank()) {
            out
                .put("safetyReason", "这是返回 App 首页后扫描发布/发帖/创作等入口，不是最终提交按钮")
        }
        return out
    }

    private fun implementsForAction(actionType: String): String {
        return when (actionType) {
            "OpenApp" -> "OpenAppSkill"
            "ClickVisibleText", "ClickSelector", "ClickCoordinate", "LongPressCoordinate", "OpenHupuPostEditor" -> "ClickElementSkill"
            "TypeIntoFirstInput", "TypeIntoFocusedInput", "TypeAtCoordinate", "FillHupuPostEditor" -> "TypeTextSkill"
            "FindEntryByBottomTabSweep" -> "FindEntrySkill"
            "CaptureWechatBottomTabs" -> "ObserveScreenSkill"
            "Back" -> "BackSkill"
            "Wait" -> "WaitUntilSkill"
            "ScrollForward" -> "ScrollUntilVisibleSkill"
            "HandleReactiveOverlays" -> "DismissDialogSkill"
            "EnsureHupuSectionSelected" -> "SelectItemSkill"
            "VerifyHupuPostPublished" -> "VerifyGoalSkill"
            "EnsureXiaohongshuDraftEditor", "RecoverDouyinHomeSurface", "RecoverBilibiliHomeSurface" -> "RecoverToScreenSkill"
            "EnsureTextVideoAsset" -> "CreateMediaAssetSkill"
            "HandleSystemPermissionDialog" -> "PermissionDialogSkill"
            else -> "AiDirectActionSkill"
        }
    }

    private fun inferIntentId(instruction: String): String {
        return when {
            instruction.contains("搜索") || instruction.contains("搜") -> "search_in_app"
            instruction.contains("发帖") || instruction.contains("发布") || instruction.contains("发表") -> "publish_or_post"
            instruction.contains("评论") -> "comment"
            instruction.contains("点赞") || instruction.contains("赞") -> "like"
            instruction.contains("打开") -> "open_app"
            else -> "custom"
        }
    }

    private fun factoryMeta(source: String, instruction: String, aiJobPath: String?, extra: JSONObject): JSONObject {
        val meta = JSONObject()
            .put("source", source)
            .put("instruction", instruction.take(500))
            .put("aiJobPath", aiJobPath ?: JSONObject.NULL)
            .put("traceIds", JSONArray())
            .put("sampleCount", 0)
            .put("dryRunStatus", "PENDING")
            .put("testRuns", 0)
            .put("successRate", 0.0)
            .put("promotionStatus", "NOT_PROMOTED")
            .put("createdAt", System.currentTimeMillis())
        extra.keys().forEach { key -> meta.put(key, extra.opt(key)) }
        return meta
    }

    private fun String.safeId(): String {
        return lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "action" }
    }
}

data class SkillJsonPatchAssembly(
    val skillFiles: List<File>,
    val macroFile: File,
    val reportFile: File,
    val report: JSONObject
)
