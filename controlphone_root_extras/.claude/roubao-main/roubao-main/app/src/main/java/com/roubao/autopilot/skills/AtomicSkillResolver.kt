package com.roubao.autopilot.skills

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.skills.atomic.*

/**
 * AtomicSkillRegistry — 持有所有 AtomicSkill 实例。
 * 单一注册入口，AtomicSkillResolver 通过它查询。
 */
class AtomicSkillRegistry(
    controller: DeviceController,
    observer: UiObserver
) {
    /** 按 id 索引所有技能 */
    val skills: List<AtomicSkill> = listOf(
        OpenAppSkill(controller, observer),
        FindElementSkill(controller),
        ClickElementSkill(controller, observer),
        TypeTextSkill(controller, observer),
        SearchSkill(controller, observer),
        ScrollUntilSkill(controller, observer),
        SelectItemSkill(controller, observer),
        SendMessageSkill(controller, observer),
        DismissDialogSkill(controller, observer),
        WaitUntilSkill(),
        VerifyGoalSkill(controller, observer),
        NavigateBackSkill(controller, observer)
    )

    fun get(id: String): AtomicSkill? = skills.find { it.id == id }
}

/**
 * AtomicSkillResolver — 根据 Goal + UiState 匹配最佳 AtomicSkill。
 *
 * 流程：
 *   1. 遍历所有 AtomicSkill，调用 propose(goal, state)
 *   2. 收集所有非 null 的 SkillProposal
 *   3. 按 confidence 降序排列
 *   4. 返回 confidence >= MIN_CONFIDENCE 且 executable 的最佳提案
 */
class AtomicSkillResolver(
    private val registry: AtomicSkillRegistry
) {
    companion object {
        private const val TAG = "[AtomicSkillResolver]"
        private const val MIN_CONFIDENCE = 0.7f
    }

    /**
     * 为单个 Goal 匹配最佳 AtomicSkill。
     * @return Pair<AtomicSkill, SkillProposal> 或 null（无可用技能）
     */
    fun resolve(goal: Goal, state: UiState): Pair<AtomicSkill, SkillProposal>? {
        val proposals = mutableListOf<Pair<AtomicSkill, SkillProposal>>()

        for (skill in registry.skills) {
            val proposal = skill.propose(goal, state)
            if (proposal != null) {
                proposals.add(skill to proposal)
            }
        }

        if (proposals.isEmpty()) {
            println("$TAG 无 AtomicSkill 匹配 goal=${goal.type} app=${goal.appName} target=${goal.targetText}")
            return null
        }

        // 按置信度降序排列
        proposals.sortByDescending { (_, proposal) -> proposal.confidence }

        val (bestSkill, bestProposal) = proposals.first()
        println(
            "$TAG ${goal.type}: " +
            "${proposals.size} 个提案 → 最佳=${bestSkill.id} " +
            "confidence=${bestProposal.confidence} " +
            "executable=${bestProposal.executable} " +
            "reason=${bestProposal.reason}"
        )

        // 需要可执行且置信度足够
        if (!bestProposal.executable || bestProposal.confidence < MIN_CONFIDENCE) {
            if (bestProposal.requiresAi) {
                println("$TAG 最佳提案需要 AI: ${bestSkill.id}")
            } else {
                println("$TAG 最佳提案不可执行: confidence=${bestProposal.confidence}")
            }
            // 返回最佳提案以供调用者判断（可用于 NeedsAi 降级）
            return bestSkill to bestProposal
        }

        return bestSkill to bestProposal
    }

    /**
     * 批量解析 — 为 Goal 序列匹配技能。
     * @return Map<Int, Pair<AtomicSkill, SkillProposal>> 键为目标序号
     */
    fun resolveAll(goals: List<Goal>, state: UiState): List<ResolvedGoal> {
        return goals.mapIndexed { index, goal ->
            val match = resolve(goal, state)
            ResolvedGoal(
                index = index,
                goal = goal,
                skill = match?.first,
                proposal = match?.second
            )
        }
    }
}

/**
 * 已解析的子目标 — Goal + 匹配的 AtomicSkill + 提案。
 */
data class ResolvedGoal(
    val index: Int,
    val goal: Goal,
    val skill: AtomicSkill?,
    val proposal: SkillProposal?
) {
    val isResolved: Boolean get() = skill != null && proposal != null && proposal.executable
    val needsAi: Boolean get() = proposal?.requiresAi == true || skill == null
}
