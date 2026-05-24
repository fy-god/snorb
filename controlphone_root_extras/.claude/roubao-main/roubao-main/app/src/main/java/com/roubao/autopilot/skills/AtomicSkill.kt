package com.roubao.autopilot.skills

/**
 * 语义子目标类型 — 描述"要做什么"，不是"怎么点"。
 */
enum class GoalType {
    EnsureAppReady,
    FindElement,
    FindContactOrConversation,
    OpenConversation,
    TypeMessage,
    SendMessage,
    ClickSemanticTarget,
    SearchCurrentApp,
    ScrollUntilVisible,
    SelectItem,
    DismissDialog,
    WaitUntil,
    VerifyGoal,
    NavigateBack
}

/**
 * 一个语义子目标。
 * 例如：Goal(type=TypeMessage, targetText="微信传输助手", message="111", title="输入消息")
 */
data class Goal(
    val type: GoalType,
    val appName: String? = null,
    val appPackage: String? = null,
    val targetText: String? = null,
    val message: String? = null,
    val query: String? = null,
    val title: String = ""
)

typealias SkillArgs = Map<String, String>

/**
 * 技能提案 — skill 回答"我能处理这个 goal，置信度 X，需要前置条件 Y"。
 */
data class SkillProposal(
    val skillId: String,
    val confidence: Float,
    val executable: Boolean,
    val requiresAi: Boolean,
    val preconditions: List<String> = emptyList(),
    val reason: String = ""
)

/**
 * 技能执行结果 — 不能只返回 Boolean。
 */
sealed class AtomicSkillResult {
    abstract val message: String

    data class Success(override val message: String) : AtomicSkillResult()
    data class Failed(override val message: String) : AtomicSkillResult()
    data class Unknown(override val message: String) : AtomicSkillResult()
    data class NeedsAi(override val message: String) : AtomicSkillResult()
    data class NeedsReplan(override val message: String) : AtomicSkillResult()

    val isSuccess: Boolean get() = this is Success
}

/**
 * AtomicSkill — 原子技能接口。
 * 每个实现必须回答：能不能做 → 怎么做 → 结果如何。
 */
interface AtomicSkill {
    val id: String
    val name: String

    /** 根据当前 UI 和 Goal 判断能否执行 */
    fun propose(goal: Goal, state: UiState): SkillProposal?

    /** 执行技能，返回结构化结果 */
    suspend fun execute(goal: Goal, args: SkillArgs, state: UiState): AtomicSkillResult
}
