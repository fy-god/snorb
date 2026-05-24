package com.roubao.autopilot.skills

/**
 * BuiltInTemplates — 预置 SkillTemplate 工厂。
 *
 * 所有预置模板在此注册，应用启动时加载到 SkillTemplateRepository。
 * 模板使用参数槽位（{appName}, {target}, {message}, {query}），
 * 运行时由 Goal 中的实际值填充。
 */
object BuiltInTemplates {

    /** 微信包名 */
    private const val WECHAT_PKG = "com.tencent.mm"

    /**
     * 全部预置模板列表。
     * 新模板在此注册即可生效。
     */
    fun all(): List<SkillTemplate> = listOf(
        sendWechatMessage,
        sendMessageGeneric,
        searchInApp,
        openAppAndClick,
        openAppAndSearch
    )

    // ---- 具体模板 ----

    /**
     * 发微信消息 — 专用模板
     *
     * 参数槽位：{appName}, {target}, {message}
     * 例如：Goal(type=SendMessage, appName="微信", targetText="传输助手", message="111")
     */
    val sendWechatMessage: SkillTemplate = SkillTemplate.create(
        name = "发微信消息",
        description = "打开微信 → 搜索联系人 → 进入会话 → 输入消息 → 发送",
        atoms = listOf(
            SkillAtomSpec(GoalType.EnsureAppReady, mapOf("appName" to "{appName}")),
            SkillAtomSpec(GoalType.FindContactOrConversation, mapOf("target" to "{target}")),
            SkillAtomSpec(GoalType.OpenConversation, emptyMap()),
            SkillAtomSpec(GoalType.TypeMessage, mapOf("message" to "{message}")),
            SkillAtomSpec(GoalType.SendMessage, emptyMap()),
            SkillAtomSpec(GoalType.VerifyGoal, emptyMap())
        ),
        requiredParams = listOf("appName", "target", "message"),
        appPackageName = WECHAT_PKG,
        tags = listOf("微信", "wechat", "消息", "发送"),
        source = TemplateSource.BUILT_IN
    )

    /**
     * 发消息 — 通用模板（支付宝/抖音/淘宝等通用聊天场景）
     *
     * 参数槽位：{appName}, {target}, {message}
     */
    val sendMessageGeneric: SkillTemplate = SkillTemplate.create(
        name = "发消息（通用）",
        description = "打开应用 → 搜索联系人 → 进入会话 → 输入消息 → 发送",
        atoms = listOf(
            SkillAtomSpec(GoalType.EnsureAppReady, mapOf("appName" to "{appName}")),
            SkillAtomSpec(GoalType.FindContactOrConversation, mapOf("target" to "{target}")),
            SkillAtomSpec(GoalType.OpenConversation, emptyMap()),
            SkillAtomSpec(GoalType.TypeMessage, mapOf("message" to "{message}")),
            SkillAtomSpec(GoalType.SendMessage, emptyMap()),
            SkillAtomSpec(GoalType.VerifyGoal, emptyMap())
        ),
        requiredParams = listOf("appName", "target", "message"),
        appPackageName = null, // 通用，不限应用
        tags = listOf("消息", "发送", "聊天", "通用"),
        source = TemplateSource.BUILT_IN
    )

    /**
     * 应用内搜索
     *
     * 参数槽位：{appName}, {query}
     * 例如：Goal(type=SearchCurrentApp, appName="淘宝", query="手机壳")
     */
    val searchInApp: SkillTemplate = SkillTemplate.create(
        name = "应用内搜索",
        description = "打开应用 → 搜索 → 点击结果",
        atoms = listOf(
            SkillAtomSpec(GoalType.EnsureAppReady, mapOf("appName" to "{appName}")),
            SkillAtomSpec(GoalType.SearchCurrentApp, mapOf("query" to "{query}")),
            SkillAtomSpec(GoalType.VerifyGoal, emptyMap())
        ),
        requiredParams = listOf("appName", "query"),
        appPackageName = null,
        tags = listOf("搜索", "查找"),
        source = TemplateSource.BUILT_IN
    )

    /**
     * 打开应用并点击目标
     *
     * 参数槽位：{appName}, {target}
     * 例如：Goal(type=ClickSemanticTarget, appName="美团", targetText="外卖")
     */
    val openAppAndClick: SkillTemplate = SkillTemplate.create(
        name = "打开应用并点击",
        description = "打开应用 → 点击目标元素",
        atoms = listOf(
            SkillAtomSpec(GoalType.EnsureAppReady, mapOf("appName" to "{appName}")),
            SkillAtomSpec(GoalType.ClickSemanticTarget, mapOf("target" to "{target}")),
            SkillAtomSpec(GoalType.VerifyGoal, emptyMap())
        ),
        requiredParams = listOf("appName", "target"),
        appPackageName = null,
        tags = listOf("点击", "打开"),
        source = TemplateSource.BUILT_IN
    )

    /**
     * 打开应用并搜索
     *
     * 参数槽位：{appName}, {query}
     * 用于"在X里找Y"类任务
     */
    val openAppAndSearch: SkillTemplate = SkillTemplate.create(
        name = "打开应用并搜索",
        description = "打开应用 → 搜索 → 点击结果",
        atoms = listOf(
            SkillAtomSpec(GoalType.EnsureAppReady, mapOf("appName" to "{appName}")),
            SkillAtomSpec(GoalType.SearchCurrentApp, mapOf("query" to "{query}")),
            SkillAtomSpec(GoalType.ClickSemanticTarget, mapOf("target" to "")),
            SkillAtomSpec(GoalType.VerifyGoal, emptyMap())
        ),
        requiredParams = listOf("appName", "query"),
        appPackageName = null,
        tags = listOf("搜索", "打开", "查找"),
        source = TemplateSource.BUILT_IN
    )
}
