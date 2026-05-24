package com.roubao.autopilot.skills

import android.content.Context

/**
 * SkillTemplateSaver — 将引擎执行轨迹转为 SkillTemplate 并持久化。
 *
 * 替代旧版 WorkflowLibrary.saveUserWorkflow() 和 RecordedSkillLibrary.save()，
 * 新版本保存的是参数化 SkillTemplate（原子序列 + 参数槽位），不含坐标。
 */
object SkillTemplateSaver {

    private val generalizer = TraceToSkillGeneralizer()

    /**
     * 从引擎执行记录中提取轨迹，泛化为模板，并持久化。
     *
     * @param instruction 原始用户指令
     * @param records AtomicSkillEngine 的执行记录
     * @param context Android Context（用于 SkillTemplateStore）
     * @param log 日志回调
     * @return 生成的 SkillTemplate，如果失败则返回 null
     */
    suspend fun saveFromRecords(
        instruction: String,
        records: List<StepRecord>,
        context: Context,
        log: (String) -> Unit = {}
    ): SkillTemplate? {
        val validRecords = records.filter {
            it.goal != null && it.skillId != null && it.phase in setOf(EnginePhase.EXECUTE, EnginePhase.VERIFY)
        }

        if (validRecords.size < 2) {
            log("[SkillTemplateSaver] 有效步骤不足 (${validRecords.size} < 2)，跳过保存")
            return null
        }

        val traceSteps = validRecords.map { record ->
            TraceStep(
                goal = record.goal!!,
                skillId = record.skillId!!,
                success = record.result?.isSuccess == true
            )
        }

        val appPkg = validRecords.firstOrNull()?.stateBefore?.packageName

        val trace = RecordedTrace(
            instruction = instruction,
            steps = traceSteps,
            appPackageName = appPkg,
            success = true
        )

        val template = generalizer.generalize(trace) { msg -> log(msg) }
            ?: return null

        // 持久化到本地
        val store = SkillTemplateStore(context)
        store.append(template)

        log("[SkillTemplateSaver] 已保存模板: ${template.name} (${template.atoms.size} 步, ${template.requiredParams.size} 参数)")

        return template
    }

    /**
     * 从 VLM 执行的录制步骤中提取模板。
     * 用于兼容旧的 VLM 录制路径（RecordedSkillSteps）。
     */
    suspend fun saveFromVlmTrace(
        instruction: String,
        skillSteps: List<RecordedSkillStep>,
        appPackageName: String?,
        context: Context,
        log: (String) -> Unit = {}
    ): SkillTemplate? {
        if (skillSteps.size < 2) {
            log("[SkillTemplateSaver] VLM 步骤不足 (${skillSteps.size} < 2)，跳过保存")
            return null
        }

        // 将 RecordedSkillStep 映射到 TraceStep
        val traceSteps = skillSteps.mapIndexed { index, step ->
            val goalType = inferGoalType(step.skillType)
            TraceStep(
                goal = Goal(
                    type = goalType,
                    targetText = step.target?.text,
                    appPackage = appPackageName,
                    title = "步骤${index + 1}: ${step.skillType}"
                ),
                skillId = step.skillType,
                success = true
            )
        }

        val trace = RecordedTrace(
            instruction = instruction,
            steps = traceSteps,
            appPackageName = appPackageName,
            success = true
        )

        val template = generalizer.generalize(trace) { msg -> log(msg) }
            ?: return null

        val store = SkillTemplateStore(context)
        store.append(template)

        log("[SkillTemplateSaver] 从 VLM 录制保存模板: ${template.name}")

        return template
    }

    /**
     * 将 VLM skillType 映射到 GoalType
     */
    private fun inferGoalType(skillType: String): GoalType = when (skillType) {
        "open_app" -> GoalType.EnsureAppReady
        "focus_input" -> GoalType.FindContactOrConversation
        "click_target" -> GoalType.ClickSemanticTarget
        "type_text" -> GoalType.TypeMessage
        "send" -> GoalType.SendMessage
        "search" -> GoalType.SearchCurrentApp
        "scroll_until" -> GoalType.ScrollUntilVisible
        "dismiss" -> GoalType.DismissDialog
        "verify" -> GoalType.VerifyGoal
        "back" -> GoalType.NavigateBack
        else -> GoalType.VerifyGoal // fallback
    }
}
