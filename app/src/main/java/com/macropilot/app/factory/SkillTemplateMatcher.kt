package com.macropilot.app.factory

import android.content.Context
import com.macropilot.app.store.FactorySkillJsonStore
import org.json.JSONObject
import java.io.File
import java.util.Locale

class SkillTemplateMatcher(
    private val context: Context,
    private val store: FactorySkillJsonStore = FactorySkillJsonStore(context),
    private val archetypeFactory: SkillJsonArchetypeFactory = SkillJsonArchetypeFactory()
) {
    fun bestAtomicTemplate(
        appPackage: String,
        action: JSONObject,
        instruction: String
    ): SkillTemplateMatch {
        val archetypeTemplates = archetypeFactory.buildForApp(
            patch = SkillJsonArchetypeFactory.defaultPatch(
                packageName = appPackage,
                label = appName(appPackage),
                launchable = true
            ),
            includeSearch = true,
            includePostDraft = instruction.contains("发") || instruction.contains("发布") || instruction.contains("发帖")
        ).map { skill ->
            SkillTemplateSource(
                skill = skill,
                path = "builtin://archetype/${skill.optString("id")}",
                origin = "BUILTIN_ARCHETYPE"
            )
        }

        val fileTemplates = (store.listRuntimeApprovedSkills(limit = 1500) + store.listSkillFiles(limit = 3000))
            .distinctBy { it.absolutePath }
            .mapNotNull { file ->
                val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@mapNotNull null
                SkillTemplateSource(json, file.absolutePath, "LOCAL_JSON")
            }

        return (fileTemplates + archetypeTemplates)
            .asSequence()
            .filter { it.skill.optString("kind") == "atomic_skill" }
            .map { source -> score(source, appPackage, action, instruction) }
            .maxByOrNull { it.score }
            ?: SkillTemplateMatch(
                source = JSONObject(),
                sourcePath = "none",
                origin = "NONE",
                score = 0,
                reason = "no_template_found"
            )
    }

    private fun score(
        source: SkillTemplateSource,
        appPackage: String,
        action: JSONObject,
        instruction: String
    ): SkillTemplateMatch {
        val skill = source.skill
        val actionType = action.optString("type")
        val skillActionType = skill.optJSONObject("action")?.optString("type").orEmpty()
        val implementsName = skill.optString("implements")
        val text = skill.toString().lowercase(Locale.CHINA)
        var score = 0
        val reasons = mutableListOf<String>()

        if (skill.optString("appPackage") == appPackage) {
            score += 12
            reasons += "same_app"
        }
        if (skillActionType == actionType) {
            score += 30
            reasons += "same_action_type"
        }
        val wantedImplements = implementsForAction(actionType)
        if (wantedImplements == implementsName) {
            score += 22
            reasons += "same_capability"
        }
        if (source.origin == "BUILTIN_ARCHETYPE") {
            score += 4
            reasons += "archetype_available"
        }
        val label = action.optString("text")
            .ifBlank { action.optString("selector") }
            .ifBlank { action.optString("packageName") }
            .lowercase(Locale.CHINA)
        if (label.isNotBlank() && text.contains(label)) {
            score += 10
            reasons += "same_label"
        }
        if (actionType.contains("Hupu", ignoreCase = true) && text.contains("hupu")) {
            score += 8
            reasons += "hupu_pattern"
        }
        if (actionType.contains("Bilibili", ignoreCase = true) && text.contains("bilibili")) {
            score += 8
            reasons += "bilibili_pattern"
        }
        if ((instruction.contains("搜索") || instruction.contains("搜")) && text.contains("search")) {
            score += 6
            reasons += "search_intent"
        }
        if ((instruction.contains("发布") || instruction.contains("发帖") || instruction.contains("发")) &&
            (text.contains("post") || text.contains("publish") || text.contains("draft"))
        ) {
            score += 6
            reasons += "publish_intent"
        }

        return SkillTemplateMatch(
            source = skill,
            sourcePath = source.path,
            origin = source.origin,
            score = score,
            reason = reasons.ifEmpty { listOf("weak_generic_match") }.joinToString(",")
        )
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

    private fun appName(packageName: String): String {
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }
}

data class SkillTemplateMatch(
    val source: JSONObject,
    val sourcePath: String,
    val origin: String,
    val score: Int,
    val reason: String
)

private data class SkillTemplateSource(
    val skill: JSONObject,
    val path: String,
    val origin: String
)
