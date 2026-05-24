package com.roubao.autopilot.skills

import android.content.Context
import com.roubao.autopilot.agent.Action
import com.roubao.autopilot.ble.BleCommandReceiver
import com.roubao.autopilot.controller.DeviceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Workflow 库 — 预录制操作序列的存储/匹配/回放引擎
 *
 * 位置: FastCommands 之后, SkillManager/VLM 之前
 * 命中 → 直接回放 (~1-2s), 零 VLM 调用
 * 未命中 → 降级到 SkillManager → VLM 流水线
 */
class WorkflowLibrary(
    private val context: Context,
    private val controller: DeviceController
) {
    private val workflows = mutableListOf<Workflow>()
    private val userWorkflowIds = mutableSetOf<String>()
    private val storageFile: File by lazy {
        File(context.filesDir, "workflows.json")
    }

    companion object {
        private const val MIN_SCORE = 0.5f
        private const val TAG = "[WorkflowLib]"
    }

    /**
     * 加载预置 + 用户 workflows
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        try {
            // 1. 加载预置 (assets/workflows.json)
            val presetsJson = context.assets.open("workflows.json").bufferedReader().readText()
            val presets = JSONArray(presetsJson)
            for (i in 0 until presets.length()) {
                workflows.add(Workflow.fromJson(presets.getJSONObject(i)))
            }
            println("$TAG 已加载 ${presets.length()} 个预置 workflow")
        } catch (e: Exception) {
            println("$TAG 预置 workflow 加载失败 (文件可能不存在): ${e.message}")
        }

        try {
            // 2. 加载用户自定义 (内部存储)
            if (storageFile.exists()) {
                val customJson = storageFile.readText()
                val custom = JSONArray(customJson)
                for (i in 0 until custom.length()) {
                    val wf = Workflow.fromJson(custom.getJSONObject(i))
                    workflows.add(wf)
                    userWorkflowIds.add(wf.id)
                }
                println("$TAG 已加载 ${custom.length()} 个自定义 workflow")
            }
        } catch (e: Exception) {
            println("$TAG 自定义 workflow 加载失败: ${e.message}")
        }

        println("$TAG 总共 ${workflows.size} 个 workflow 就绪")
    }

    /**
     * 匹配用户指令 → 返回最佳 WorkflowMatch 或 null
     */
    fun match(instruction: String): WorkflowMatch? {
        if (workflows.isEmpty()) return null

        var best: WorkflowMatch? = null
        var bestScore = MIN_SCORE

        for (wf in workflows) {
            val score = wf.matchScore(instruction)
            if (score <= 0f) continue

            // 提取参数
            val params = wf.extractParams(instruction)
            if (params != null) {
                if (score > bestScore) {
                    bestScore = score
                    best = WorkflowMatch(wf, params, score)
                }
            } else if (wf.params.isEmpty()) {
                // 无需参数的 workflow，直接匹配
                if (score > bestScore) {
                    bestScore = score
                    best = WorkflowMatch(wf, emptyMap(), score)
                }
            }
            // 有参数但提取失败 → 跳过此 workflow，让后面的命中
        }

        if (best != null) {
            println("$TAG 命中: ${best.workflow.name} (score=${best.score}, params=${best.params})")
        }
        return best
    }

    /**
     * 回放 workflow 步骤
     */
    suspend fun execute(match: WorkflowMatch): Boolean {
        val wf = match.workflow
        println("$TAG 开始回放: ${wf.name} (${wf.steps.size} 步)")

        val steps = wf.buildSteps(match.params)
        if (steps.isEmpty()) {
            println("$TAG 回放取消: workflow 没有步骤")
            return false
        }
        if (steps.any { !isValidStep(it) }) {
            println("$TAG 回放取消: workflow 步骤无效")
            return false
        }

        for ((i, step) in steps.withIndex()) {
            println("$TAG 步骤 ${i + 1}/${steps.size}: ${step.type}")
            if (!executeStep(step)) {
                println("$TAG 步骤失败: ${step.type}")
                return false
            }
            // 步骤间延迟 (open_app 需要更久)
            if (i < steps.size - 1) {
                val waitMs = getStepDelayMs(step)
                delay(waitMs)
            }
        }

        // 更新使用计数
        val updated = wf.copy(timesUsed = wf.timesUsed + 1)
        val idx = workflows.indexOf(wf)
        if (idx >= 0) {
            workflows[idx] = updated
        }
        println("$TAG 回放完成: ${wf.name}")
        return true
    }

    private fun isValidStep(action: Action): Boolean {
        return when (action.type) {
            "wait" -> true
            "click", "tap", "double_tap", "long_press" -> action.x != null && action.y != null
            "swipe" -> action.direction != null || (action.x != null && action.y != null && action.x2 != null && action.y2 != null)
            "type" -> !action.text.isNullOrBlank()
            "system_button" -> action.button in setOf("Home", "Back", "Enter", "home", "back", "enter")
            "open_app" -> !action.text.isNullOrBlank()
            else -> false
        }
    }

    /**
     * 执行单个步骤
     */
    private fun executeStep(action: Action): Boolean {
        return when (action.type) {
            "wait" -> true
            "click", "tap", "long_press", "swipe", "type", "system_button", "open_app", "double_tap" -> {
                BleCommandReceiver.executeAction(action, controller)
                true
            }
            else -> false
        }
    }

    /**
     * 步骤间延迟 — 参考 MobileAgent.getActionWaitMs
     */
    private fun getStepDelayMs(action: Action): Long = when (action.type) {
        "open_app" -> 2800L
        "click", "tap", "double_tap", "long_press" -> 700L
        "swipe", "drag" -> 500L
        "type" -> 400L
        "system_button" -> 500L
        "wait" -> (action.duration ?: 1) * 1000L
        else -> 500L
    }

    /**
     * 保存用户自定义 workflow
     */
    suspend fun saveUserWorkflow(wf: Workflow) = withContext(Dispatchers.IO) {
        if (wf.steps.isEmpty() || wf.keywords.isEmpty()) return@withContext
        workflows.removeAll { it.id == wf.id }
        workflows.add(wf)
        userWorkflowIds.add(wf.id)
        persistUserWorkflows()
    }

    /**
     * 删除用户自定义 workflow
     */
    suspend fun deleteWorkflow(id: String) = withContext(Dispatchers.IO) {
        workflows.removeAll { it.id == id }
        userWorkflowIds.remove(id)
        persistUserWorkflows()
    }

    private fun persistUserWorkflows() {
        try {
            val arr = JSONArray()
            for (wf in workflows.filter { userWorkflowIds.contains(it.id) }) {
                arr.put(wf.toJson())
            }
            storageFile.writeText(arr.toString(2))
        } catch (e: Exception) {
            println("$TAG 保存失败: ${e.message}")
        }
    }

    fun getAll(): List<Workflow> = workflows.toList()
}
