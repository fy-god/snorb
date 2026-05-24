package com.roubao.autopilot.skills

/**
 * 执行路线
 */
enum class ExecuteRoute {
    UNRESOLVED,
    LOCAL_SKILL,
    AI,
    FAILED
}

/**
 * 计划中的一个 Goal — 对应一个语义子目标，由 GoalDecomposer 拆出。
 * 每个 PlanGoal 携带：
 *   - goal: 语义子目标（EnsureAppReady / FindContact / TypeMessage 等）
 *   - route: 执行路线（LOCAL_SKILL / AI）
 *   - selectedSkill: 匹配到的 AtomicSkill 提案
 */
data class PlanGoal(
    val id: String,
    val title: String,
    val instruction: String,
    val goal: Goal? = null,
    val intent: ParsedIntent? = null,
    val route: ExecuteRoute = ExecuteRoute.UNRESOLVED,
    val selectedSkill: SkillProposal? = null,
    val reason: String = ""
)

/**
 * 用户计划 — 包含一个或多个子目标。
 */
data class UserPlan(
    val originalInstruction: String,
    val goals: List<PlanGoal>
)

/**
 * TaskPlanner — 用 GoalDecomposer 把用户指令拆成子目标序列。
 * 每个子目标由 AtomicSkillResolver + AtomicSkill 独立执行。
 */
class TaskPlanner(
    private val intentParser: IntentParser = IntentParser(),
    private val decomposer: GoalDecomposer = GoalDecomposer()
) {
    companion object {
        private const val TAG = "[TaskPlanner]"
    }

    fun createPlan(userInstruction: String): UserPlan {
        val intent = intentParser.parse(userInstruction)
        val decomposedGoals = decomposer.decompose(userInstruction, intent)

        if (decomposedGoals.isNotEmpty()) {
            println("$TAG 分解出 ${decomposedGoals.size} 个子目标")
            val planGoals = decomposedGoals.mapIndexed { i, goal ->
                PlanGoal(
                    id = "goal_${i + 1}",
                    title = goal.title.ifBlank { goal.type.name },
                    instruction = buildGoalInstruction(goal),
                    goal = goal,
                    intent = intent
                )
            }
            return UserPlan(originalInstruction = userInstruction, goals = planGoals)
        }

        // 兜底：无法分解 → 单 goal，后续交给 AI
        val title = intent?.let {
            when (it.templateId) {
                "send_message", "wechat_send_message" -> "发送消息"
                "open_app" -> "打开应用"
                "search" -> "搜索"
                "wechat_search" -> "微信搜索"
                "browser_search" -> "浏览器搜索"
                "douyin_search" -> "抖音搜索"
                "settings_search" -> "设置搜索"
                "alipay_scan" -> "支付宝扫码"
                "wechat_moments" -> "微信朋友圈"
                else -> "执行用户指令"
            }
        } ?: "执行用户指令"

        println("$TAG 未能分解子目标，使用单 goal: $title")
        return UserPlan(
            originalInstruction = userInstruction,
            goals = listOf(
                PlanGoal(
                    id = "goal_1",
                    title = title,
                    instruction = userInstruction,
                    goal = null,
                    intent = intent
                )
            )
        )
    }

    /** 从 Goal 构造自然语言描述，供 AI 降级时使用 */
    private fun buildGoalInstruction(goal: Goal): String = when (goal.type) {
        GoalType.EnsureAppReady -> "打开${goal.appName ?: "目标应用"}"
        GoalType.FindElement -> "找到${goal.targetText ?: "目标元素"}"
        GoalType.FindContactOrConversation -> "找到${goal.targetText ?: "目标联系人"}"
        GoalType.OpenConversation -> "进入${goal.targetText ?: "聊天"}"
        GoalType.TypeMessage -> "输入${goal.message ?: "消息"}"
        GoalType.SendMessage -> "发送消息"
        GoalType.ClickSemanticTarget -> "点击${goal.targetText ?: "目标"}"
        GoalType.SearchCurrentApp -> "搜索${goal.query ?: ""}"
        GoalType.ScrollUntilVisible -> "滑动直到${goal.targetText ?: "目标可见"}"
        GoalType.SelectItem -> "选择${goal.targetText ?: "目标项"}"
        GoalType.DismissDialog -> "关闭弹窗"
        GoalType.WaitUntil -> "等待"
        GoalType.VerifyGoal -> "验证${goal.targetText ?: goal.message ?: "目标"}"
        GoalType.NavigateBack -> "返回上一页"
    }
}
