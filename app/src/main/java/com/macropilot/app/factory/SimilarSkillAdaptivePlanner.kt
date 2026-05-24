package com.macropilot.app.factory

import android.content.Context
import com.macropilot.app.runtime.NodeInspector
import com.macropilot.app.store.FactorySkillJsonStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class SimilarSkillAdaptivePlan(
    val intentId: String,
    val confidence: String,
    val planSteps: List<JSONObject>,
    val evidence: JSONObject
)

class SimilarSkillAdaptivePlanner(
    private val context: Context,
    private val store: FactorySkillJsonStore = FactorySkillJsonStore(context),
    private val inspector: NodeInspector = NodeInspector(context)
) {
    fun plan(instruction: String, appPackage: String): SimilarSkillAdaptivePlan? {
        val normalizedPackage = appPackage.trim()
        if (normalizedPackage.isBlank()) return null
        if (hasDedicatedPlanner(instruction, normalizedPackage)) return null
        val intentId = inferIntentId(instruction, normalizedPackage)
        val similar = findSimilarCases(intentId, normalizedPackage, instruction)
        val steps = when (intentId) {
            "wechat_bottom_tab_capture" -> wechatBottomTabCaptureSteps(normalizedPackage)
            "wechat_moment_private_post" -> wechatMomentPrivatePostSteps(instruction, normalizedPackage)
            "generic_post_or_publish" -> genericPostSteps(instruction, normalizedPackage)
            "generic_search" -> genericSearchSteps(instruction, normalizedPackage)
            "open_app_only" -> listOf(
                JSONObject()
                    .put("type", "OpenApp")
                    .put("packageName", normalizedPackage)
                    .put("timeoutMs", 3_000L)
            )
            else -> emptyList()
        }
        if (steps.isEmpty()) return null
        val current = inspector.currentState()
        val evidence = JSONObject()
            .put("planner", "PHONE_SIDE_SIMILAR_SKILL_ADAPTER_V1")
            .put("intentId", intentId)
            .put("appPackage", normalizedPackage)
            .put("instruction", instruction.take(300))
            .put("source", "local_rules_plus_existing_skill_similarity")
            .put("similarCases", similar)
            .put("stepCount", steps.size)
            .put("currentPackage", current?.appPackage ?: JSONObject.NULL)
            .put("nodeCount", current?.nodes?.size ?: 0)
            .put("notes", JSONArray(listOf(
                "App-side planner generated candidate JSON without ADB UI control.",
                "Existing candidate/approved skills are used as similarity evidence; generated steps are still dry-run checked before execution.",
                "AI remains a fallback for empty or failed local planning."
            )))
        return SimilarSkillAdaptivePlan(
            intentId = intentId,
            confidence = if (similar.length() > 0 || intentId == "wechat_moment_private_post") "MEDIUM" else "LOW",
            planSteps = steps,
            evidence = evidence
        )
    }

    private fun hasDedicatedPlanner(instruction: String, appPackage: String): Boolean {
        val lowerPackage = appPackage.lowercase(Locale.US)
        val lowerInstruction = instruction.lowercase(Locale.CHINA)
        val douyinVideoPublish = (lowerPackage.contains("aweme") || lowerPackage.contains("douyin")) &&
            instruction.contains("\u89c6\u9891") &&
            (instruction.contains("\u53d1") || instruction.contains("\u53d1\u5e03") || instruction.contains("\u4e0a\u4f20") ||
                lowerInstruction.contains("publish") || lowerInstruction.contains("upload"))
        val xhsPost = appPackage == SkillJsonRuleGenerator.XIAOHONGSHU_PACKAGE &&
            (instruction.contains("\u53d1") || instruction.contains("\u7b14\u8bb0") || instruction.contains("\u5c0f\u7ea2\u4e66"))
        val hupuPost = lowerPackage.contains("hupu") &&
            (instruction.contains("\u53d1\u5e16") || instruction.contains("\u53d1\u5e03") || lowerInstruction.contains("post"))
        val bilibiliTask = appPackage == SkillJsonRuleGenerator.BILIBILI_PACKAGE &&
            (instruction.contains("\u641c") || instruction.contains("\u8bc4\u8bba") || instruction.contains("\u70b9\u8d5e") || instruction.contains("\u89c6\u9891"))
        return douyinVideoPublish || xhsPost || hupuPost || bilibiliTask
    }

    private fun inferIntentId(instruction: String, appPackage: String): String {
        val lower = instruction.lowercase(Locale.CHINA)
        return when {
            appPackage == SkillJsonRuleGenerator.WECHAT_PACKAGE &&
                (instruction.contains("\u8bb0\u5f55") || instruction.contains("\u91c7\u6837") || instruction.contains("\u4fdd\u5b58") || lower.contains("snapshot")) &&
                (instruction.contains("\u5e95\u680f") || instruction.contains("\u5e95\u90e8") || instruction.contains("UI", ignoreCase = true) || instruction.contains("\u754c\u9762")) &&
                (instruction.contains("\u5fae\u4fe1") || instruction.contains("\u901a\u8baf\u5f55") || instruction.contains("\u53d1\u73b0") || instruction.contains("\u6211")) ->
                "wechat_bottom_tab_capture"
            appPackage == SkillJsonRuleGenerator.WECHAT_PACKAGE &&
                (instruction.contains("\u670b\u53cb\u5708") || lower.contains("moments")) &&
                (instruction.contains("\u4ec5\u81ea\u5df1") || instruction.contains("\u81ea\u5df1\u53ef\u89c1") || instruction.contains("\u79c1\u5bc6")) &&
                (publishLike(instruction) || postLike(instruction) || instruction.contains("\u53d1")) -> "wechat_moment_private_post"
            searchLike(instruction) -> "generic_search"
            publishLike(instruction) || postLike(instruction) -> "generic_post_or_publish"
            instruction.contains("\u6253\u5f00") || instruction.contains("\u542f\u52a8") -> "open_app_only"
            else -> "unknown"
        }
    }

    private fun wechatBottomTabCaptureSteps(appPackage: String): List<JSONObject> {
        return listOf(
            JSONObject().put("type", "OpenApp").put("packageName", appPackage).put("timeoutMs", 3_000L),
            JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", appPackage).put("maxPasses", 3),
            JSONObject()
                .put("type", "CaptureWechatBottomTabs")
                .put("packageName", appPackage)
                .put("settleMs", 1_000L)
                .put("bottomY", 0.965)
        )
    }

    private fun wechatMomentPrivatePostSteps(instruction: String, appPackage: String): List<JSONObject> {
        val content = extractMomentContent(instruction).ifBlank { "\u6211\u662fAI" }
        return listOf(
            JSONObject().put("type", "OpenApp").put("packageName", appPackage).put("timeoutMs", 3_000L),
            JSONObject().put("type", "Wait").put("timeoutMs", 2_600L),
            JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", appPackage).put("maxPasses", 3),
            JSONObject()
                .put("type", "CaptureWechatBottomTabs")
                .put("packageName", appPackage)
                .put("settleMs", 1_000L)
                .put("bottomY", 0.965),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "\u53d1\u73b0")
                .put("textAliases", JSONArray(listOf("\u53d1\u73b0")))
                .put("x", 0.625)
                .put("y", 0.965)
                .put("requiresUiStateForCoordinate", false)
                .put("timeoutMs", 1_500L),
            JSONObject().put("type", "Wait").put("timeoutMs", 900L),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "\u670b\u53cb\u5708")
                .put("textAliases", JSONArray(listOf("\u670b\u53cb\u5708")))
                .put("x", 0.50)
                .put("y", 0.16)
                .put("requiresUiStateForCoordinate", false)
                .put("timeoutMs", 1_500L),
            JSONObject().put("type", "Wait").put("timeoutMs", 1_200L),
            JSONObject()
                .put("type", "LongPressCoordinate")
                .put("x", 0.93)
                .put("y", 0.06)
                .put("durationMs", 850L)
                .put("timeoutMs", 1_500L),
            JSONObject().put("type", "Wait").put("timeoutMs", 1_200L),
            JSONObject()
                .put("type", "TypeAtCoordinate")
                .put("text", content)
                .put("x", 0.50)
                .put("y", 0.18)
                .put("replace", true)
                .put("focusDelayMs", 700L)
                .put("timeoutMs", 2_600L),
            JSONObject().put("type", "Wait").put("timeoutMs", 600L),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "\u8c01\u53ef\u4ee5\u770b")
                .put("textAliases", JSONArray(listOf("\u8c01\u53ef\u4ee5\u770b", "\u516c\u5f00", "\u90e8\u5206\u53ef\u89c1", "\u79c1\u5bc6")))
                .put("x", 0.54)
                .put("y", 0.485)
                .put("requiresUiStateForCoordinate", false)
                .put("timeoutMs", 1_500L),
            JSONObject().put("type", "Wait").put("timeoutMs", 800L),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "\u79c1\u5bc6")
                .put("textAliases", JSONArray(listOf("\u79c1\u5bc6", "\u4ec5\u81ea\u5df1\u53ef\u89c1", "\u4ec5\u81ea\u5df1", "\u53ea\u6709\u81ea\u5df1\u53ef\u89c1")))
                .put("x", 0.50)
                .put("y", 0.255)
                .put("requiresUiStateForCoordinate", false)
                .put("timeoutMs", 1_500L),
            JSONObject().put("type", "Wait").put("timeoutMs", 500L),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "\u5b8c\u6210")
                .put("textAliases", JSONArray(listOf("\u5b8c\u6210", "\u786e\u5b9a")))
                .put("x", 0.895)
                .put("y", 0.075)
                .put("requiresUiStateForCoordinate", false)
                .put("timeoutMs", 1_500L),
            JSONObject().put("type", "Wait").put("timeoutMs", 700L),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "\u53d1\u8868")
                .put("textAliases", JSONArray(listOf("\u53d1\u8868", "\u53d1\u5e03")))
                .put("x", 0.895)
                .put("y", 0.075)
                .put("verifyType", "WechatMomentPublishSubmitted")
                .put("requiresUiStateForCoordinate", false)
                .put("timeoutMs", 4_500L)
                .put("userConfirmedSubmitAction", true)
                .put("submitReason", "\u7528\u6237\u660e\u786e\u8981\u6c42\u53d1\u5fae\u4fe1\u670b\u53cb\u5708\uff0c\u4e14\u8981\u8bbe\u4e3a\u4ec5\u81ea\u5df1\u53ef\u89c1"),
            JSONObject().put("type", "Wait").put("timeoutMs", 3_500L)
        )
    }

    private fun genericSearchSteps(instruction: String, appPackage: String): List<JSONObject> {
        val query = extractAfterAny(instruction, listOf("\u641c\u7d22", "\u641c")).ifBlank { instruction.take(30) }
        return listOf(
            JSONObject().put("type", "OpenApp").put("packageName", appPackage).put("timeoutMs", 3_000L),
            JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", appPackage).put("maxPasses", 3),
            JSONObject()
                .put("type", "ClickVisibleText")
                .put("text", "\u641c\u7d22")
                .put("textAliases", JSONArray(listOf("\u641c\u7d22", "\u641c", "\u67e5\u627e")))
                .put("x", 0.88)
                .put("y", 0.08)
                .put("requiresUiStateForCoordinate", false),
            JSONObject().put("type", "Wait").put("timeoutMs", 600L),
            JSONObject().put("type", "TypeIntoFirstInput").put("text", query).put("timeoutMs", 2_000L),
            JSONObject().put("type", "ClickVisibleText").put("text", "\u641c\u7d22")
                .put("textAliases", JSONArray(listOf("\u641c\u7d22", "\u786e\u5b9a"))).put("timeoutMs", 1_500L),
            JSONObject().put("type", "Wait").put("timeoutMs", 1_800L)
        )
    }

    private fun genericPostSteps(instruction: String, appPackage: String): List<JSONObject> {
        val content = extractPostContent(instruction).ifBlank { "\u6211\u662fAI" }
        return listOf(
            JSONObject().put("type", "OpenApp").put("packageName", appPackage).put("timeoutMs", 3_000L),
            JSONObject().put("type", "HandleReactiveOverlays").put("appPackage", appPackage).put("maxPasses", 4),
            JSONObject()
                .put("type", "FindEntryByBottomTabSweep")
                .put("appPackage", appPackage)
                .put("entryAliases", JSONArray(listOf("\u53d1\u5e03", "\u53d1\u5e16", "\u521b\u4f5c", "\u5199", "+")))
                .put("maxTabs", 5)
                .put("homeBackPresses", 3)
                .put("timeoutMs", 12_000L),
            JSONObject().put("type", "Wait").put("timeoutMs", 800L),
            JSONObject().put("type", "TypeIntoFirstInput").put("text", content).put("timeoutMs", 2_400L)
        )
    }

    private fun findSimilarCases(intentId: String, appPackage: String, instruction: String): JSONArray {
        val out = JSONArray()
        val wanted = tagsFor(intentId, instruction)
        store.listSkillFiles(limit = 700)
            .asSequence()
            .mapNotNull { file ->
                val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@mapNotNull null
                val score = scoreSkill(json, wanted, appPackage)
                if (score <= 0) null else file to json to score
            }
            .sortedByDescending { it.second }
            .take(8)
            .forEach { item ->
                val fileJson = item.first.second
                out.put(JSONObject()
                    .put("skillId", fileJson.optString("id"))
                    .put("kind", fileJson.optString("kind"))
                    .put("appPackage", fileJson.optString("appPackage"))
                    .put("implements", fileJson.optString("implements"))
                    .put("intentId", fileJson.optString("intentId"))
                    .put("score", item.second)
                    .put("path", item.first.first.absolutePath))
            }
        return out
    }

    private fun scoreSkill(skill: JSONObject, wanted: Set<String>, targetPackage: String): Int {
        val text = skill.toString().lowercase(Locale.CHINA)
        var score = 0
        wanted.forEach { tag -> if (text.contains(tag.lowercase(Locale.CHINA))) score += 8 }
        if (skill.optString("appPackage") == targetPackage) score += 4
        if (skill.optString("kind") == "macro_skill") score += 3
        if (skill.optString("status") == "APPROVED") score += 5
        if (text.contains("typeintofirstinput") || text.contains("typetext")) score += 1
        if (text.contains("clickvisibletext")) score += 1
        return score
    }

    private fun tagsFor(intentId: String, instruction: String): Set<String> {
        val base = mutableSetOf(intentId, "openapp", "clickvisibletext")
        if (searchLike(instruction)) base += setOf("search", "\u641c\u7d22")
        if (publishLike(instruction) || postLike(instruction)) base += setOf("publish", "\u53d1\u5e03", "\u53d1\u8868", "typeintofirstinput")
        if (instruction.contains("\u670b\u53cb\u5708")) base += setOf("\u670b\u53cb\u5708", "\u8c01\u53ef\u4ee5\u770b", "\u79c1\u5bc6")
        return base
    }

    private fun extractMomentContent(instruction: String): String {
        val afterMomentColon = listOf(
            "\u670b\u53cb\u5708\uff1a",
            "\u670b\u53cb\u5708:",
            "\u670b\u53cb\u5708 \uff1a",
            "\u670b\u53cb\u5708 :"
        ).firstNotNullOfOrNull { marker ->
            instruction.substringAfter(marker, missingDelimiterValue = "")
                .takeIf { it.isNotBlank() && it != instruction }
        }
        val raw = afterMomentColon
            ?: extractAfterAny(instruction, listOf("\u5185\u5bb9\u662f", "\u6587\u6848\u662f", "\u5199", "\u8bf4", "\u53d1"))
        return raw
            .substringBefore("\uff0c\u5fc5\u987b")
            .substringBefore(",\u5fc5\u987b")
            .substringBefore("\uff1b\u5fc5\u987b")
            .substringBefore(";\u5fc5\u987b")
            .substringBefore("\uff0c\u5e76\u9a8c\u8bc1")
            .substringBefore(",\u5e76\u9a8c\u8bc1")
            .substringBefore("\u5e76\u9a8c\u8bc1")
            .substringBefore("\uff0c\u4e0d\u80fd")
            .substringBefore(",\u4e0d\u80fd")
            .substringBefore("\u4e0d\u80fd")
            .substringBefore("\uff0c\u8bbe\u7f6e")
            .substringBefore(",\u8bbe\u7f6e")
            .substringBefore("\u8bbe\u7f6e\u4e3a")
            .substringBefore("\uff0c\u7136\u540e")
            .substringBefore(",\u7136\u540e")
            .substringBefore("\u7136\u540e")
            .substringBefore("\uff0c\u5e76")
            .substringBefore(",\u5e76")
            .substringBefore("\u5e76")
            .replace("\u4e00\u6761\u670b\u53cb\u5708", "")
            .replace("\u670b\u53cb\u5708", "")
            .replace("\u4ec5\u81ea\u5df1\u53ef\u89c1", "")
            .replace("\u4ec5\u81ea\u5df1", "")
            .replace("\u81ea\u5df1\u53ef\u89c1", "")
            .replace("\u8bd5\u4e00\u4e0b", "")
            .replace("\u5fc5\u987b\u771f\u6b63\u70b9\u51fb\u53d1\u8868", "")
            .replace("\u5e76\u9a8c\u8bc1\u5df2\u7ecf\u63d0\u4ea4", "")
            .replace("\u4e0d\u80fd\u53ea\u505c\u5728\u8349\u7a3f\u9875", "")
            .trim(' ', '\uff0c', ',', '\u3002', ':', '\uff1a')
            .take(80)
    }

    private fun extractPostContent(instruction: String): String {
        return extractAfterAny(instruction, listOf("\u5185\u5bb9\u662f", "\u6587\u6848\u662f", "\u5199", "\u53d1\u5e03", "\u53d1\u5e16", "\u53d1"))
            .trim(' ', '\uff0c', ',', '\u3002', ':', '\uff1a')
            .take(120)
    }

    private fun extractAfterAny(text: String, markers: List<String>): String {
        markers.forEach { marker ->
            val value = text.substringAfter(marker, missingDelimiterValue = "")
                .takeIf { it.isNotBlank() && it != text }
            if (value != null) return value.trim()
        }
        return ""
    }

    private fun searchLike(instruction: String): Boolean {
        return instruction.contains("\u641c\u7d22") || instruction.contains("\u641c")
    }

    private fun publishLike(instruction: String): Boolean {
        return instruction.contains("\u53d1\u5e03") ||
            instruction.contains("\u53d1\u8868") ||
            instruction.contains("\u53d1\u51fa\u53bb") ||
            instruction.contains("publish", ignoreCase = true) ||
            instruction.contains("post", ignoreCase = true)
    }

    private fun postLike(instruction: String): Boolean {
        return instruction.contains("\u53d1\u4e00\u6761") ||
            instruction.contains("\u53d1\u4e2a") ||
            instruction.contains("\u53d1\u5e16") ||
            instruction.contains("\u670b\u53cb\u5708") ||
            instruction.contains("\u52a8\u6001")
    }
}
