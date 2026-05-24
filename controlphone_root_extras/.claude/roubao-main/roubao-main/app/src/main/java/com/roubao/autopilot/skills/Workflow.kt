package com.roubao.autopilot.skills

import com.roubao.autopilot.agent.Action
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Workflow 参数定义
 * @param name 参数名，如 "contact"
 * @param description 描述，如 "联系人名字"
 * @param extractPattern 正则提取模式，如 "给(.+?)发"
 */
data class WfParam(
    val name: String,
    val description: String,
    val extractPattern: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("extractPattern", extractPattern)
    }

    companion object {
        fun fromJson(obj: JSONObject): WfParam = WfParam(
            name = obj.getString("name"),
            description = obj.optString("description", ""),
            extractPattern = obj.getString("extractPattern")
        )
    }
}

/**
 * Workflow — 预录制的多步操作序列
 *
 * steps 中的 Action.text 可包含 {param_name} 占位符，
 * 回放时替换为从用户指令中提取的参数值。
 */
data class Workflow(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val appPackage: String,
    val appName: String,
    val keywords: List<String>,
    val params: List<WfParam> = emptyList(),
    val steps: List<Action>,
    val createdAt: Long = System.currentTimeMillis(),
    val timesUsed: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("appPackage", appPackage)
        put("appName", appName)
        put("keywords", JSONArray(keywords))
        put("params", JSONArray().apply {
            params.forEach { put(it.toJson()) }
        })
        put("steps", JSONArray().apply {
            steps.forEach { put(JSONObject(it.toJson())) }
        })
    }

    /**
     * 检查 keywords 是否命中用户指令
     * @return 0.0-1.0 的匹配分数
     */
    fun matchScore(instruction: String): Float {
        if (steps.isEmpty()) return 0f
        val executableSteps = steps.count { it.type != "wait" }
        if (executableSteps == 0) return 0f
        val cleaned = instruction.trim().lowercase()
        // 指令和 workflow 名字高度重合 → 直接匹配
        if (name.length >= 4 && cleaned.contains(name.lowercase().take(12))) {
            return 0.85f
        }
        for (kw in keywords) {
            if (cleaned.contains(kw.lowercase())) {
                // 关键词越长，匹配越精确
                return minOf(0.5f + kw.length * 0.1f, 1.0f)
            }
        }
        return 0f
    }

    /**
     * 用正则从用户指令中提取参数值
     * @return Map<paramName, value>，提取不到则为 null
     */
    fun extractParams(instruction: String): Map<String, String>? {
        val result = mutableMapOf<String, String>()
        for (param in params) {
            val regex = Regex(param.extractPattern)
            val match = regex.find(instruction)
            if (match != null) {
                val value = match.groupValues.getOrNull(1)?.trim() ?: ""
                if (value.isNotEmpty()) {
                    result[param.name] = value
                }
            }
        }
        // 只有所有参数都提取到才算成功
        return if (result.size == params.size) result else null
    }

    /**
     * 生成参数替换后的 Action 序列
     */
    fun buildSteps(params: Map<String, String>): List<Action> {
        return steps.map { action ->
            var text = action.text
            if (text != null && text.contains("{")) {
                for ((key, value) in params) {
                    text = text?.replace("{${key}}", value)
                }
            }
            action.copy(text = text)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): Workflow = Workflow(
            id = obj.optString("id", UUID.randomUUID().toString().take(8)),
            name = obj.getString("name"),
            appPackage = obj.optString("appPackage", ""),
            appName = obj.optString("appName", ""),
            keywords = obj.optJSONArray("keywords")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            params = obj.optJSONArray("params")?.let { arr ->
                (0 until arr.length()).map { WfParam.fromJson(arr.getJSONObject(it)) }
            } ?: emptyList(),
            steps = obj.optJSONArray("steps")?.let { arr ->
                (0 until arr.length()).mapNotNull { Action.fromJson(arr.getJSONObject(it).toString()) }
            } ?: emptyList(),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            timesUsed = obj.optInt("timesUsed", 0)
        )

        /**
         * 从指令和历史动作自动生成 Workflow
         * 自动提取关键词、参数
         */
        fun autoGenerate(instruction: String, actions: List<Action>): Workflow {
            // 提取应用名
            val firstOpen = actions.firstOrNull { it.type == "open_app" }
            val appName = firstOpen?.text ?: ""
            val appPackage = ""  // 运行时会通过 AppScanner 解析

            // 自动生成关键词：从指令中提取动词短语
            val keywords = extractKeywords(instruction)

            // 自动检测参数：type 动作中的文本如果和指令有重叠，标记为参数
            val params = detectParams(instruction, actions)

            // 参数化 steps：把 type 动作中的参数值替换为 {param_name}
            val paramSteps = parameterizeSteps(actions, params)

            return Workflow(
                name = instruction.take(20),
                appPackage = appPackage,
                appName = appName,
                keywords = keywords,
                params = params,
                steps = paramSteps,
            )
        }

        private fun extractKeywords(instruction: String): List<String> {
            val keywords = mutableListOf<String>()
            // 常见动词模式
            val verbs = listOf("打开", "发", "搜", "给", "点", "看", "播放", "设置", "扫", "打", "去", "查", "写")
            for (verb in verbs) {
                if (instruction.contains(verb)) {
                    keywords.add(verb)
                    // 动词 + 名词组合
                    val idx = instruction.indexOf(verb)
                    if (idx >= 0) {
                        val after = instruction.substring(idx).take(6)
                        if (after.length > verb.length) keywords.add(after)
                    }
                }
            }
            // 提取应用名作为关键词
            val appNames = listOf("微信", "支付宝", "淘宝", "抖音", "美团", "浏览器", "设置", "电话", "短信", "微博", "小红书", "B站", "网易云")
            for (app in appNames) {
                if (instruction.contains(app)) keywords.add(app)
            }
            return keywords.distinct().take(6)
        }

        private fun detectParams(instruction: String, actions: List<Action>): List<WfParam> {
            return emptyList()
        }

        private fun parameterizeSteps(actions: List<Action>, params: List<WfParam>): List<Action> {
            return actions
        }
    }
}

/**
 * 匹配结果
 */
data class WorkflowMatch(
    val workflow: Workflow,
    val params: Map<String, String>,
    val score: Float
)
