package com.roubao.autopilot.skills

import com.roubao.autopilot.agent.Action
import org.json.JSONArray
import org.json.JSONObject

/**
 * 技能碎片 — 最小可复用动作单元
 *
 * 每个碎片是一个已验证的动作序列，有明确的前置状态和结果状态。
 * 碎片可以被拼装成完整的 Skill 来响应用户指令。
 *
 * 例如: "微信-搜索联系人" = [点击搜索栏, 输入{contact}, 等待, 点击第一个结果]
 */
data class SkillFragment(
    val id: String,
    val name: String,              // "微信-搜索联系人"
    val appPackage: String,        // "com.tencent.mm"
    val appName: String,           // "微信"
    val category: String,          // "search" / "input" / "navigate" / "send" / "open"
    val precondition: String,      // "微信主页" — 执行前需要的状态
    val resultState: String,       // "搜索结果页" — 执行后所处的状态
    val params: List<String>,      // ["contact"] — 需要填充的参数名
    val steps: List<Action>,       // 动作序列 (可能含 {param} 占位符)
    val verifiedCount: Int = 0,    // 验证次数
    val lastVerifiedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("appPackage", appPackage)
        put("appName", appName)
        put("category", category)
        put("precondition", precondition)
        put("resultState", resultState)
        put("params", JSONArray(params))
        put("steps", JSONArray().apply {
            steps.forEach { put(JSONObject(it.toJson())) }
        })
        put("verifiedCount", verifiedCount)
    }

    /**
     * 用提取到的参数值填充步骤中的占位符
     */
    fun buildSteps(paramValues: Map<String, String>): List<Action> {
        return steps.map { action ->
            var text = action.text
            if (text != null && text.contains("{")) {
                for ((key, value) in paramValues) {
                    text = text?.replace("{${key}}", value)
                }
            }
            action.copy(text = text)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): SkillFragment = SkillFragment(
            id = obj.getString("id"),
            name = obj.optString("name", ""),
            appPackage = obj.optString("appPackage", ""),
            appName = obj.optString("appName", ""),
            category = obj.optString("category", ""),
            precondition = obj.optString("precondition", ""),
            resultState = obj.optString("resultState", ""),
            params = obj.optJSONArray("params")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            steps = obj.optJSONArray("steps")?.let { arr ->
                (0 until arr.length()).mapNotNull { Action.fromJson(arr.getJSONObject(it).toString()) }
            } ?: emptyList(),
            verifiedCount = obj.optInt("verifiedCount", 0),
            lastVerifiedAt = obj.optLong("lastVerifiedAt", System.currentTimeMillis())
        )
    }
}

/**
 * 技能模板 — 定义如何把碎片拼装成完整任务
 *
 * 模板描述了: 用户意图 → 需要哪些碎片 → 如何映射参数
 */
data class FragmentSkillTemplate(
    val id: String,
    val name: String,                // "发微信消息"
    val intentPatterns: List<String>, // ["给X发Y", "发微信给X Y", "微信X Y"]
    val appMatcher: String,          // "微信" / "浏览器" / "设置" — 匹配哪个应用
    val fragments: List<String>,     // 碎片 ID 列表，按执行顺序
    val paramMapping: Map<String, String>  // 模板参数 → 碎片参数映射: {"X": "contact", "Y": "message"}
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("intentPatterns", JSONArray(intentPatterns))
        put("appMatcher", appMatcher)
        put("fragments", JSONArray(fragments))
        put("paramMapping", JSONObject(paramMapping))
    }

    companion object {
        fun fromJson(obj: JSONObject): FragmentSkillTemplate = FragmentSkillTemplate(
            id = obj.getString("id"),
            name = obj.optString("name", ""),
            intentPatterns = obj.optJSONArray("intentPatterns")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            appMatcher = obj.optString("appMatcher", ""),
            fragments = obj.optJSONArray("fragments")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            paramMapping = obj.optJSONObject("paramMapping")?.let { obj ->
                val map = mutableMapOf<String, String>()
                for (key in obj.keys()) map[key] = obj.getString(key)
                map
            } ?: emptyMap()
        )
    }
}

/**
 * 意图解析结果
 */
data class ParsedIntent(
    val templateId: String,           // 匹配到的模板 ID
    val appName: String,              // 推断的应用名
    val params: Map<String, String>,  // 提取的参数
    val confidence: Float             // 置信度 0-1
)
