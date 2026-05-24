package com.roubao.autopilot.skills

/**
 * SkillTemplateRepository — 模板仓库，负责模板的存储、查询和匹配。
 *
 * 查询优先级（由 SkillResolver 层控制）：
 *   SkillTemplateRepository → AtomicSkillRegistry → WorkflowReplaySkill
 *
 * 模板匹配规则：
 *   1. GoalType 必须一致
 *   2. 所需参数可从 Goal 中获取
 *   3. 应用匹配（如果指定了 appPackageName）
 *   4. 按 matchScore 降序排列
 */
class SkillTemplateRepository {

    private val templates = mutableListOf<SkillTemplate>()

    /** 注册模板（预置或用户录制） */
    fun register(template: SkillTemplate) {
        // 替换同 ID 的旧模板
        val existing = templates.indexOfFirst { it.id == template.id }
        if (existing != -1) {
            templates[existing] = template
        } else {
            templates.add(template)
        }
    }

    /** 批量注册 */
    fun registerAll(list: List<SkillTemplate>) {
        list.forEach { register(it) }
    }

    /** 移除模板 */
    fun remove(id: String) {
        templates.removeAll { it.id == id }
    }

    /** 根据 Goal 查找匹配的模板，按匹配分数降序 */
    fun findByGoal(goal: Goal): List<SkillTemplate> {
        return templates
            .filter { it.matches(goal) }
            .sortedByDescending { it.matchScore(goal) }
    }

    /** 查找指定应用的模板 */
    fun findByApp(packageName: String): List<SkillTemplate> {
        return templates.filter { it.appPackageName == packageName }
    }

    /** 查找指定 GoalType 的模板 */
    fun findByGoalType(goalType: GoalType): List<SkillTemplate> {
        return templates.filter { it.goalType == goalType }
    }

    /** 按 ID 查找 */
    fun findById(id: String): SkillTemplate? = templates.find { it.id == id }

    /** 按标签搜索 */
    fun findByTag(tag: String): List<SkillTemplate> {
        return templates.filter { tag in it.tags }
    }

    /** 获取最佳匹配模板（分数最高且 >= minScore） */
    fun findBest(goal: Goal, minScore: Float = 0.7f): SkillTemplate? {
        return findByGoal(goal).firstOrNull()?.takeIf { it.matchScore(goal) >= minScore }
    }

    /** 用户录制的模板 */
    fun userRecorded(): List<SkillTemplate> {
        return templates.filter { it.source == TemplateSource.USER_RECORDED }
    }

    /** 预置模板 */
    fun builtIn(): List<SkillTemplate> {
        return templates.filter { it.source == TemplateSource.BUILT_IN }
    }

    /** 全部模板 */
    fun all(): List<SkillTemplate> = templates.toList()

    /** 模板数量 */
    fun size(): Int = templates.size

    /** 清空所有模板 */
    fun clear() {
        templates.clear()
    }

    /** 更新模板使用统计 */
    fun recordUse(id: String, success: Boolean) {
        val idx = templates.indexOfFirst { it.id == id }
        if (idx != -1) {
            val t = templates[idx]
            val newSuccessRate = if (t.useCount > 0) {
                (t.successRate * t.useCount + (if (success) 1f else 0f)) / (t.useCount + 1)
            } else {
                if (success) 1f else 0f
            }
            templates[idx] = t.copy(
                useCount = t.useCount + 1,
                successRate = newSuccessRate
            )
        }
    }
}
