package com.roubao.autopilot.skills

/**
 * 一条录制轨迹 — 由 NewSkillEngine 执行成功后产生。
 */
data class RecordedTrace(
    val instruction: String,
    val steps: List<TraceStep>,
    val appPackageName: String?,
    val success: Boolean
)

/**
 * 轨迹中单步 — 一个 Goal + 执行的 skill + 是否成功。
 */
data class TraceStep(
    val goal: Goal,
    val skillId: String,
    val success: Boolean
)

/**
 * TraceToSkillGeneralizer — 将录制轨迹转换为 SkillTemplate。
 *
 * 核心规则：
 *   1. 所有步骤必须有语义 GoalType（非 fallback / 非 workflow_replay）
 *   2. 提取参数槽位：遍历所有 Goal 中使用的参数名
 *   3. 拒绝纯坐标点击（无 targetText/message/query 的 ClickSemanticTarget）
 *   4. 至少需要 2 个有效步骤
 *
 * 拒绝场景：
 *   - 包含 fallback / workflow_replay / unknown 步骤
 *   - 所有点击都没有语义目标（纯坐标操作，不可跨设备复用）
 *   - 少于 2 个有效步骤
 */
class TraceToSkillGeneralizer {

    companion object {
        private const val TAG = "[TraceToSkillGeneralizer]"
        private const val MIN_STEPS = 2

        /** 不可泛化的 skill id */
        private val NON_GENERALIZABLE = setOf(
            "workflow_replay",
            "fallback",
            "unknown"
        )

        /**
         * 将 Goal 的字段名映射到模板参数名。
         * Goal 字段 → 模板参数槽位名
         */
        private val PARAM_MAP = mapOf(
            "appName" to "appName",
            "appPackage" to "appPackage",
            "targetText" to "target",
            "message" to "message",
            "query" to "query"
        )

        /** 哪些 GoalType 必须有 target 参数才算语义化 */
        private val TARGET_REQUIRED_TYPES = setOf(
            GoalType.ClickSemanticTarget,
            GoalType.FindElement,
            GoalType.FindContactOrConversation,
            GoalType.OpenConversation,
            GoalType.SelectItem
        )

        /**
         * GoalType → 可读名称
         */
        private val GOAL_LABELS = mapOf(
            GoalType.EnsureAppReady to "打开应用",
            GoalType.FindContactOrConversation to "找到联系人",
            GoalType.OpenConversation to "进入会话",
            GoalType.TypeMessage to "输入消息",
            GoalType.SendMessage to "发送",
            GoalType.ClickSemanticTarget to "点击目标",
            GoalType.SearchCurrentApp to "搜索",
            GoalType.ScrollUntilVisible to "滚动查找",
            GoalType.SelectItem to "选择项目",
            GoalType.DismissDialog to "关闭弹窗",
            GoalType.WaitUntil to "等待",
            GoalType.VerifyGoal to "验证",
            GoalType.NavigateBack to "返回",
            GoalType.FindElement to "查找元素"
        )
    }

    /**
     * 尝试泛化轨迹为 SkillTemplate。
     * 成功返回 SkillTemplate，失败返回 null（并记录原因）。
     */
    fun generalize(trace: RecordedTrace, log: (String) -> Unit = {}): SkillTemplate? {
        if (!trace.success) {
            log("$TAG 拒绝: 轨迹未成功完成")
            return null
        }

        val validSteps = trace.steps.filter { it.skillId !in NON_GENERALIZABLE }

        if (validSteps.size < MIN_STEPS) {
            log("$TAG 拒绝: 有效步骤不足 (${validSteps.size} < $MIN_STEPS)")
            return null
        }

        // 检查是否有纯坐标操作
        for (step in validSteps) {
            if (!isSemanticStep(step)) {
                log("$TAG 拒绝: 步骤 ${step.goal.type}/${step.skillId} 缺乏语义目标")
                return null
            }
        }

        // 构建 SkillAtomSpec 序列
        val atoms = validSteps.map { step ->
            val slots = mutableMapOf<String, String>()
            for ((goalField, paramName) in PARAM_MAP) {
                val value = step.goal.getParam(paramName)
                if (value != null) {
                    // 使用参数槽位占位符
                    slots[goalField] = "{$paramName}"
                }
            }
            SkillAtomSpec(goalType = step.goal.type, paramSlots = slots)
        }

        // 收集所有需要的参数
        val requiredParams = linkedSetOf<String>()
        for (step in validSteps) {
            for ((_, paramName) in PARAM_MAP) {
                if (step.goal.getParam(paramName) != null) {
                    requiredParams.add(paramName)
                }
            }
        }

        val appName = trace.steps.firstOrNull()?.goal?.appName
        val appPkg = trace.appPackageName ?: trace.steps.firstOrNull()?.goal?.appPackage

        val templateName = buildTemplateName(validSteps, appName)
        val description = buildDescription(validSteps)

        log("$TAG 泛化成功: $templateName (${atoms.size} 步, ${requiredParams.size} 参数)")

        return SkillTemplate(
            id = "tpl_${templateName.hashCode()}_${System.currentTimeMillis()}",
            name = templateName,
            description = description,
            goalType = validSteps.first().goal.type,
            atoms = atoms,
            requiredParams = requiredParams.toList(),
            appPackageName = appPkg,
            tags = buildTags(appName, validSteps),
            source = TemplateSource.USER_RECORDED
        )
    }

    /**
     * 检查步骤是否有足够的语义信息。
     * 纯坐标点击（无 target/message/query）不可跨设备复用。
     */
    private fun isSemanticStep(step: TraceStep): Boolean {
        val g = step.goal

        // 这些类型天然不需要参数
        if (g.type in setOf(GoalType.NavigateBack, GoalType.DismissDialog,
                GoalType.WaitUntil, GoalType.VerifyGoal)) {
            return true
        }

        // EnsureAppReady 需要 app 信息
        if (g.type == GoalType.EnsureAppReady) {
            return g.appName != null || g.appPackage != null
        }

        // TypeMessage / SendMessage 需要 message 或 target
        if (g.type in setOf(GoalType.TypeMessage, GoalType.SendMessage)) {
            return g.message != null || g.targetText != null
        }

        // 需要 target 的类型
        if (g.type in TARGET_REQUIRED_TYPES) {
            return g.targetText != null
        }

        // SearchCurrentApp 需要 query
        if (g.type == GoalType.SearchCurrentApp) {
            return g.query != null
        }

        // ScrollUntilVisible 需要 target
        if (g.type == GoalType.ScrollUntilVisible) {
            return g.targetText != null
        }

        return true
    }

    private fun buildTemplateName(steps: List<TraceStep>, appName: String?): String {
        val app = appName ?: steps.firstOrNull()?.goal?.appName ?: "应用"
        val firstLabel = GOAL_LABELS[steps.first().goal.type] ?: "操作"
        val lastLabel = GOAL_LABELS[steps.last().goal.type] ?: "完成"
        return "$app-$firstLabel-$lastLabel"
    }

    private fun buildDescription(steps: List<TraceStep>): String {
        return steps.joinToString(" → ") { GOAL_LABELS[it.goal.type] ?: it.goal.type.name }
    }

    private fun buildTags(appName: String?, steps: List<TraceStep>): List<String> {
        val tags = mutableSetOf<String>()
        appName?.let { tags.add(it) }
        steps.forEach { tags.add(it.goal.type.name) }
        return tags.toList()
    }
}
