package com.roubao.autopilot.skills

import android.graphics.Bitmap
import android.graphics.Rect

// ============================================================
// AI 辅助模式 — 控制 AI 参与程度
// ============================================================

/**
 * AI 策略 — 控制 AI 在 Agent 中的参与程度。
 *
 * - AssistOnly: AI 仅做局部辅助（感知/规划/修复），不接管执行（AUTO 模式默认）
 * - FullAiAllowed: 允许完整 VLM 接管（AI_ONLY 模式）
 * - NoAi: 完全禁止 AI，只走本地（LOCAL_ONLY 模式）
 */
enum class AiPolicy {
    AssistOnly,
    FullAiAllowed,
    NoAi
}

// ============================================================
// AI Lease — 限制 AI 调用次数
// ============================================================

/**
 * AI Lease — 单次用例中 AI 调用的配额限制。
 *
 * 在 AUTO 模式下，AI 调用次数受限，防止本地引擎频繁降级 AI。
 * 配额耗尽后，引擎必须自行决策或返回失败。
 */
data class AiLease(
    val maxPlanCalls: Int = 1,
    val maxPerceptionCallsPerStep: Int = 1,
    val maxRepairCallsPerStep: Int = 1,
    val maxVerifyCallsPerStep: Int = 1
) {
    private var planCalls = 0
    private var perceptionCalls = 0
    private var repairCalls = 0
    private var verifyCalls = 0

    fun canPlan(): Boolean = planCalls < maxPlanCalls
    fun canPerceive(): Boolean = perceptionCalls < maxPerceptionCallsPerStep
    fun canRepair(): Boolean = repairCalls < maxRepairCallsPerStep
    fun canVerify(): Boolean = verifyCalls < maxVerifyCallsPerStep

    fun recordPlan() { planCalls++ }
    fun recordPerception() { perceptionCalls++ }
    fun recordRepair() { repairCalls++ }
    fun recordVerify() { verifyCalls++ }

    /** 每步之后重置感知/修复/验证配额 */
    fun resetStep() {
        perceptionCalls = 0
        repairCalls = 0
        verifyCalls = 0
    }

    /** 全部重置 */
    fun reset() {
        planCalls = 0
        resetStep()
    }

    companion object {
        /** AUTO 模式默认配额 — 严格限制 AI 调用 */
        val AUTO_DEFAULT = AiLease(
            maxPlanCalls = 1,
            maxPerceptionCallsPerStep = 1,
            maxRepairCallsPerStep = 1,
            maxVerifyCallsPerStep = 1
        )

        /** AI_ONLY 模式 — 不限制 */
        val UNLIMITED = AiLease(
            maxPlanCalls = Int.MAX_VALUE,
            maxPerceptionCallsPerStep = Int.MAX_VALUE,
            maxRepairCallsPerStep = Int.MAX_VALUE,
            maxVerifyCallsPerStep = Int.MAX_VALUE
        )

        /** LOCAL_ONLY 模式 — 完全禁止 AI */
        val NONE = AiLease(0, 0, 0, 0)
    }
}

// ============================================================
// AI 结构化数据类型 — AI 只能返回这些，不能返回 Action
// ============================================================

/**
 * 元素查询 — 询问 AI "这个元素在屏幕哪里？"
 */
data class ElementQuery(
    /** 要查找的文字（支持模糊匹配） */
    val text: String? = null,
    /** 内容描述 */
    val contentDesc: String? = null,
    /** 控件类名前缀 */
    val className: String? = null,
    /** 资源 ID 前缀 */
    val resourceId: String? = null,
    /** 位置提示: "top" / "bottom" / "center" / "left" 等 */
    val positionHint: String? = null,
    /** 元素用途描述，帮助 AI 理解上下文 */
    val description: String = ""
) {
    companion object {
        fun forText(text: String, description: String = "") = ElementQuery(
            text = text, description = description.ifEmpty { "查找文字: $text" }
        )
        fun forSearch(description: String = "") = ElementQuery(
            className = "EditText", description = description.ifEmpty { "查找搜索框" }
        )
        fun forSend(description: String = "") = ElementQuery(
            contentDesc = "发送", description = description.ifEmpty { "查找发送按钮" }
        )
    }
}

/**
 * 元素定位结果 — AI 回答 "元素在这里"
 */
data class ElementLocation(
    /** 元素的屏幕边界 */
    val bounds: Rect,
    /** AI 置信度 */
    val confidence: Float,
    /** 匹配到的实际文字/描述 */
    val matchedText: String? = null,
    /** 补充说明 */
    val note: String = ""
)

/**
 * AI 分解的子目标 — 用于规划辅助
 */
data class SubGoal(
    val type: GoalType,
    val appName: String? = null,
    val appPackage: String? = null,
    val targetText: String? = null,
    val message: String? = null,
    val query: String? = null,
    val title: String = "",
    /** AI 对这一步的解释 */
    val reasoning: String = ""
) {
    fun toGoal(): Goal = Goal(
        type = type,
        appName = appName,
        appPackage = appPackage,
        targetText = targetText,
        message = message,
        query = query,
        title = title.ifEmpty { reasoning.take(30) }
    )
}

/**
 * 验证结果 — AI 回答 "操作是否成功"
 */
data class VerifyResult(
    val success: Boolean,
    val confidence: Float,
    /** 验证依据 */
    val evidence: String = "",
    /** 如果不成功，可能的原因 */
    val failureReason: String? = null
)

/**
 * 目标识别结果 — AI 回答 "应该和哪个元素交互"
 */
data class TargetIdentity(
    /** 元素边界（如果找到） */
    val bounds: Rect? = null,
    /** 交互类型: "click" / "input" / "scroll" 等 */
    val interactionType: String = "click",
    /** 要输入的文本（如果是输入操作） */
    val inputText: String? = null,
    /** 置信度 */
    val confidence: Float,
    /** 如果没有找到明确元素，给一个语义描述 */
    val fallbackHint: String = ""
)

/**
 * 修复建议 — AI 回答 "操作失败后该怎么办"
 */
data class RepairSuggestion(
    /** 建议的 GoalType（如果换一个类型可能成功） */
    val alternativeGoalType: GoalType? = null,
    /** 建议的 AtomicSkill id */
    val suggestedSkillId: String? = null,
    /** 文本描述 */
    val description: String,
    /** 是否需要人工介入 */
    val needsHuman: Boolean = false,
    /** 是否值得重试 */
    val shouldRetry: Boolean = true
)

// ============================================================
// AiAssist 接口
// ============================================================

/**
 * AiAssist — AI 辅助接口。
 *
 * AI 职责边界（关键约束）：
 *   - AI 只能做：感知（locateElement/identifyTarget）、规划（decomposeGoal）、
 *     验证（verifyResult）、修复（suggestRepair）
 *   - AI 绝不返回原始 Action（click/swipe/type 等坐标操作）
 *   - AI 辅助完成后，控制权必须回到 AtomicSkillEngine
 *
 * AUTO 模式下，所有调用受 AiLease 配额限制。
 */
interface AiAssist {
    /** 此辅助是否可用（VLM 客户端已配置） */
    val available: Boolean

    /**
     * 定位元素 — 根据语义描述找到屏幕上的元素位置。
     * 用于 FindElement / ClickSemanticTarget 等 skill 需要感知辅助时。
     */
    suspend fun locateElement(
        query: ElementQuery,
        screenshot: Bitmap,
        lease: AiLease
    ): ElementLocation?

    /**
     * 分解用户指令为子目标序列。
     * 仅用于 AUTO 模式下的首次规划（最多调用 1 次，由 lease 控制）。
     */
    suspend fun decomposeGoal(
        instruction: String,
        state: UiState,
        screenshot: Bitmap,
        lease: AiLease
    ): List<SubGoal>?

    /**
     * 验证操作结果 — 比较前后截图，判断操作是否成功。
     */
    suspend fun verifyResult(
        before: UiState,
        after: UiState,
        goal: Goal,
        screenshotBefore: Bitmap?,
        screenshotAfter: Bitmap?,
        lease: AiLease
    ): VerifyResult?

    /**
     * 识别交互目标 — 根据 Goal 和当前界面确定应交互的元素。
     * 返回结构化 TargetIdentity，不是 Action。
     */
    suspend fun identifyTarget(
        goal: Goal,
        state: UiState,
        screenshot: Bitmap,
        lease: AiLease
    ): TargetIdentity?

    /**
     * 修复建议 — 操作失败后，AI 分析原因并给出替代方案。
     */
    suspend fun suggestRepair(
        goal: Goal,
        state: UiState,
        lastResult: AtomicSkillResult,
        screenshot: Bitmap,
        lease: AiLease
    ): RepairSuggestion?
}
