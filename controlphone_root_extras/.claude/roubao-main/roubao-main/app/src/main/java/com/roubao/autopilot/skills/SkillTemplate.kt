package com.roubao.autopilot.skills

/**
 * 模板来源
 */
enum class TemplateSource {
    BUILT_IN,       // 预置模板
    USER_RECORDED,  // TraceToSkillGeneralizer 从录制轨迹生成
    AI_GENERATED    // AI 生成的模板（未来扩展）
}

/**
 * 技能原子规格 — 模板中的一个步骤。
 * 描述"要做什么"（GoalType + 参数槽位），不是坐标点击。
 *
 * 参数槽位示例：
 *   SkillAtomSpec(GoalType.OpenApp, mapOf("appName" to "{appName}"))
 *   SkillAtomSpec(GoalType.TypeMessage, mapOf("message" to "{message}"))
 *
 * 运行时用 Goal 中的实际值填充 {param} 占位符。
 */
data class SkillAtomSpec(
    val goalType: GoalType,
    val paramSlots: Map<String, String> = emptyMap()
) {
    /**
     * 用实际参数值填充占位符，生成可执行的 Goal。
     * 例如：paramSlots = {"appName": "{appName}", "target": "{target}"}
     *      params = {"appName": "微信", "target": "传输助手"}
     *      → Goal(type=..., appName="微信", targetText="传输助手")
     */
    fun toGoal(params: Map<String, String>): Goal {
        val resolved = paramSlots.mapValues { (_, slot) ->
            if (slot.startsWith("{") && slot.endsWith("}")) {
                params[slot.removeSurrounding("{", "}")] ?: slot
            } else slot
        }
        return Goal(
            type = goalType,
            appName = resolved["appName"],
            appPackage = resolved["appPackage"],
            targetText = resolved["target"],
            message = resolved["message"],
            query = resolved["query"],
            title = resolved["title"] ?: ""
        )
    }
}

/**
 * SkillTemplate — 参数化的原子技能序列。
 *
 * 与 Workflow（坐标序列）的关键区别：
 *   - 模板存储 GoalType + 参数槽位，不存储坐标
 *   - 运行时根据当前 UiState 动态匹配元素，不盲点
 *   - 可跨设备、跨屏幕尺寸复用
 *
 * 示例：发微信消息模板
 *   atoms = [
 *     SkillAtomSpec(EnsureAppReady, {"appName": "{appName}"}),
 *     SkillAtomSpec(FindContactOrConversation, {"target": "{target}"}),
 *     SkillAtomSpec(OpenConversation, {}),
 *     SkillAtomSpec(TypeMessage, {"message": "{message}"}),
 *     SkillAtomSpec(SendMessage, {}),
 *   ]
 *   requiredParams = ["appName", "target", "message"]
 */
data class SkillTemplate(
    val id: String,
    val name: String,
    val description: String,
    val goalType: GoalType,
    val atoms: List<SkillAtomSpec>,
    val requiredParams: List<String> = emptyList(),
    val appPackageName: String? = null,
    val tags: List<String> = emptyList(),
    val source: TemplateSource = TemplateSource.BUILT_IN,
    val useCount: Int = 0,
    val successRate: Float = 1.0f
) {
    /**
     * 检查 Goal 是否能被此模板匹配。
     * 规则：
     *   1. Goal 的 type 必须与模板的 goalType 一致
     *   2. 模板所需参数必须全部可从 Goal 中获取
     */
    fun matches(goal: Goal): Boolean {
        if (goal.type != goalType) return false
        return requiredParams.all { param ->
            goal.getParam(param) != null
        }
    }

    /**
     * 将 Goal 拆解为子 Goal 序列（模板实例化）。
     * 每个 SkillAtomSpec 用 Goal 的参数填充后生成对应的子 Goal。
     */
    fun decompose(goal: Goal): List<Goal> {
        val params = goal.toParamMap()
        return atoms.map { it.toGoal(params) }
    }

    /**
     * 匹配分数：Goal 参数匹配度 + 应用匹配度 + 历史成功率。
     */
    fun matchScore(goal: Goal): Float {
        var score = 0.7f // 基础分

        // 参数匹配度
        val params = goal.toParamMap()
        val matchedParams = requiredParams.count { params.containsKey(it) }
        if (requiredParams.isNotEmpty()) {
            score += 0.15f * matchedParams / requiredParams.size
        }

        // 应用精确匹配加分
        if (appPackageName != null && goal.appPackage == appPackageName) {
            score += 0.1f
        }

        // 历史成功率加权
        score *= (0.7f + 0.3f * successRate)

        return score.coerceIn(0f, 1f)
    }

    companion object {
        /**
         * 从原子序列和元数据构建模板。
         * 用于 TraceToSkillGeneralizer 和预置模板。
         */
        fun create(
            name: String,
            description: String,
            atoms: List<SkillAtomSpec>,
            requiredParams: List<String>,
            appPackageName: String? = null,
            tags: List<String> = emptyList(),
            source: TemplateSource = TemplateSource.BUILT_IN
        ): SkillTemplate {
            val id = "tpl_${name.hashCode()}_${System.currentTimeMillis()}"
            return SkillTemplate(
                id = id,
                name = name,
                description = description,
                goalType = atoms.firstOrNull()?.goalType ?: GoalType.VerifyGoal,
                atoms = atoms,
                requiredParams = requiredParams,
                appPackageName = appPackageName,
                tags = tags,
                source = source
            )
        }
    }
}

/**
 * Goal 参数提取扩展
 */
fun Goal.toParamMap(): Map<String, String> = buildMap {
    appName?.let { put("appName", it) }
    appPackage?.let { put("appPackage", it) }
    targetText?.let { put("target", it) }
    message?.let { put("message", it) }
    query?.let { put("query", it) }
    if (title.isNotEmpty()) put("title", title)
}

fun Goal.getParam(name: String): String? = when (name) {
    "appName" -> appName
    "appPackage" -> appPackage
    "target" -> targetText
    "message" -> message
    "query" -> query
    "title" -> title.ifEmpty { null }
    else -> null
}
