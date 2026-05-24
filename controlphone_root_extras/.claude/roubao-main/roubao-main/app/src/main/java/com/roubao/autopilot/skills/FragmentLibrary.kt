package com.roubao.autopilot.skills

import android.content.Context
import com.roubao.autopilot.App
import com.roubao.autopilot.agent.Action
import com.roubao.autopilot.ble.BleCommandReceiver
import com.roubao.autopilot.controller.DeviceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 碎片库 — 存储/索引/拼装/执行技能碎片
 *
 * 流程:
 *   用户指令 → IntentParser 解析意图
 *   → FragmentLibrary 查找模板 + 碎片
 *   → 全部碎片就绪 → 拼装执行 (零截图)
 *   → 有碎片缺失 → 降级 VLM → 执行后存为新碎片
 */
class FragmentLibrary(
    private val context: Context,
    private val controller: DeviceController
) {
    // 碎片索引: appPackage → category → [fragments]
    private val fragmentIndex = mutableMapOf<String, MutableMap<String, MutableList<SkillFragment>>>()
    // 模板索引: templateId → template
    private val templates = mutableMapOf<String, FragmentSkillTemplate>()
    // 意图解析器
    private val intentParser = IntentParser()

    private val storageDir: File by lazy { File(context.filesDir, "fragments") }

    companion object {
        private const val TAG = "[FragmentLib]"
    }

    /**
     * 加载预置碎片和模板
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        try {
            val json = context.assets.open("fragments.json").bufferedReader().readText()
            val root = JSONObject(json)

            // 加载碎片
            val fragArr = root.optJSONArray("fragments") ?: JSONArray()
            for (i in 0 until fragArr.length()) {
                addFragment(SkillFragment.fromJson(fragArr.getJSONObject(i)))
            }
            println("$TAG 已加载 ${fragArr.length()} 个碎片")

            // 加载模板
            val templArr = root.optJSONArray("templates") ?: JSONArray()
            for (i in 0 until templArr.length()) {
                val t = FragmentSkillTemplate.fromJson(templArr.getJSONObject(i))
                templates[t.id] = t
            }
            println("$TAG 已加载 ${templArr.length()} 个模板")
        } catch (e: Exception) {
            println("$TAG 加载预置失败: ${e.message}")
        }

        // 加载用户录制的碎片
        loadUserFragments()

        println("$TAG 总共 ${countFragments()} 个碎片, ${templates.size} 个模板就绪")
    }

    private fun loadUserFragments() {
        if (!storageDir.exists()) return
        storageDir.listFiles()?.forEach { file ->
            try {
                val obj = JSONObject(file.readText())
                addFragment(SkillFragment.fromJson(obj))
            } catch (e: Exception) {
                println("$TAG 加载用户碎片失败: ${file.name} - ${e.message}")
            }
        }
    }

    private fun addFragment(f: SkillFragment) {
        val appIndex = fragmentIndex.getOrPut(f.appPackage) { mutableMapOf() }
        val catIndex = appIndex.getOrPut(f.category) { mutableListOf() }
        // 去重
        catIndex.removeAll { it.id == f.id }
        catIndex.add(f)
    }

    /**
     * 尝试用碎片库响应用户指令
     * @return 组装好的完整 Action 序列，或 null（需要降级 VLM）
     */
    fun tryResolve(instruction: String): FragmentMatch? {
        // 1. 意图解析
        val intent = intentParser.parse(instruction) ?: return null

        // 2. 查找模板
        val template = templates[intent.templateId] ?: return null
        println("$TAG 意图: ${template.name} (${intent.params})")

        // 3. 确定目标应用
        val targetApp = resolveApp(intent, template)

        // 4. 查找并组装碎片
        val assembledFragments = mutableListOf<SkillFragment>()
        val allParams = intent.params.toMutableMap()

        for (fragId in template.fragments) {
            val frag = findFragment(fragId, targetApp) ?: run {
                println("$TAG 碎片缺失: $fragId (app=$targetApp)")
                return null  // 有碎片缺失，降级 VLM
            }
            assembledFragments.add(frag)
            // 映射参数
            for ((tplParam, fragParam) in template.paramMapping) {
                val value = intent.params[tplParam] ?: allParams[tplParam]
                if (value != null) {
                    allParams[fragParam] = value
                }
            }
        }

        println("$TAG 组装成功: ${assembledFragments.size} 个碎片")
        return FragmentMatch(assembledFragments, allParams, template, intent.confidence)
    }

    fun tryResolveSkillPlan(plan: ExecutionPlan): FragmentMatch? {
        val templateId = when (plan.skillId) {
            "send_message" -> "wechat_send_message"
            "post_social" -> if (plan.app.packageName == "com.tencent.mm") "wechat_moments" else null
            "scan" -> if (plan.app.packageName == "com.eg.android.AlipayGphone") "alipay_scan" else null
            else -> null
        } ?: return null

        val template = templates[templateId] ?: return null
        val assembledFragments = mutableListOf<SkillFragment>()
        val allParams = mutableMapOf<String, String>()
        for ((key, value) in plan.params) {
            if (value != null && key != "_raw_query") allParams[key] = value.toString()
        }
        if (!allParams.containsKey("message")) {
            allParams["message"] = plan.params["content"]?.toString() ?: plan.params["_raw_query"]?.toString().orEmpty()
        }

        for (fragId in template.fragments) {
            val frag = findFragment(fragId, plan.app.packageName) ?: run {
                println("$TAG Skill 碎片缺失: $fragId (app=${plan.app.packageName})")
                return null
            }
            assembledFragments.add(frag)
        }

        println("$TAG Skill 组装成功: ${plan.skillName} -> ${assembledFragments.size} 个碎片")
        return FragmentMatch(assembledFragments, allParams, template, 0.95f)
    }

    /**
     * 执行组装好的碎片序列
     */
    suspend fun execute(match: FragmentMatch): Boolean = withContext(Dispatchers.IO) {
        println("$TAG 开始执行: ${match.template.name} (${match.fragments.size} 碎片)")

        for ((fi, frag) in match.fragments.withIndex()) {
            println("$TAG [${fi + 1}/${match.fragments.size}] ${frag.name}")
            val steps = frag.buildSteps(match.params)
            println("$TAG   步骤数: ${steps.size}")

            for ((si, step) in steps.withIndex()) {
                try {
                    val stepDesc = "${step.type}" + (step.text?.let { "($it)" } ?: "")
                    println("$TAG   └─ 步骤${si + 1}: $stepDesc")
                    executeStep(step)
                    println("$TAG   └─ 步骤${si + 1}: 完成")
                } catch (e: Exception) {
                    println("$TAG   └─ 步骤${si + 1}: 异常 - ${e.message}")
                    e.printStackTrace()
                    println("$TAG 执行失败")
                    return@withContext false
                }
                if (si < steps.size - 1) {
                    delay(getStepDelayMs(step))
                }
            }

            // 碎片间延迟
            if (fi < match.fragments.size - 1) {
                delay(500L)
            }
        }

        println("$TAG 执行完成")
        return@withContext true
    }

    /**
     * 从 VLM 执行结果中录制新碎片
     */
    suspend fun recordFragment(
        name: String,
        appPackage: String,
        appName: String,
        category: String,
        precondition: String,
        resultState: String,
        params: List<String>,
        actions: List<Action>
    ) = withContext(Dispatchers.IO) {
        val id = "user_${appName}_${category}_${System.currentTimeMillis()}"
        val fragment = SkillFragment(
            id = id, name = name,
            appPackage = appPackage, appName = appName,
            category = category,
            precondition = precondition, resultState = resultState,
            params = params, steps = actions
        )
        addFragment(fragment)
        // 持久化
        storageDir.mkdirs()
        File(storageDir, "${id}.json").writeText(fragment.toJson().toString(2))
        println("$TAG 已录制新碎片: $name")
    }

    private fun findFragment(id: String, appPackage: String): SkillFragment? {
        // 先在指定应用下找
        fragmentIndex[appPackage]?.values?.forEach { list ->
            list.find { it.id == id }?.let { return it }
        }
        // 全局搜索
        fragmentIndex.values.forEach { cats ->
            cats.values.forEach { list ->
                list.find { it.id == id }?.let { return it }
            }
        }
        return null
    }

    private fun resolveApp(intent: ParsedIntent, template: FragmentSkillTemplate): String {
        // 尝试从意图参数中获取应用名
        val appFromIntent = intent.appName
        if (appFromIntent.isNotBlank()) {
            // 尝试在已有碎片中匹配
            for ((pkg, cats) in fragmentIndex) {
                for ((_, frags) in cats) {
                    if (frags.any { it.appName.contains(appFromIntent, ignoreCase = true) }) {
                        return pkg
                    }
                }
            }
        }
        // 使用模板的 appMatcher
        return template.appMatcher
    }

    private fun executeStep(action: Action) {
        if (action.type == "wait") return
        var mappedAction = action
        // open_app: 将应用名解析为包名 (如 "微信" → "com.tencent.mm")
        if (mappedAction.type == "open_app" && mappedAction.text != null) {
            val pkg = App.getInstance().appScanner.findPackage(mappedAction.text!!)
            if (pkg != null) {
                mappedAction = mappedAction.copy(text = pkg)
                println("$TAG 应用名解析: ${action.text} → $pkg")
            }
        }
        // 碎片坐标是 0-999 归一化的，映射到屏幕像素
        mappedAction = mapActionCoordinates(mappedAction)
        BleCommandReceiver.executeAction(mappedAction, controller)
    }

    /**
     * 将 0-999 归一化坐标映射到屏幕像素坐标
     * >= 1000 的值视为绝对像素坐标，直接使用
     */
    private fun mapActionCoordinates(action: Action): Action {
        val (screenW, screenH) = controller.getScreenSize()
        fun map(v: Int?) = v?.let {
            if (it < 1000) (it * screenW / 999) else it.coerceAtMost(screenW)
        }
        fun mapY(v: Int?) = v?.let {
            if (it < 1000) (it * screenH / 999) else it.coerceAtMost(screenH)
        }
        return action.copy(
            x = map(action.x),
            y = mapY(action.y),
            x2 = map(action.x2),
            y2 = mapY(action.y2)
        )
    }

    private fun getStepDelayMs(action: Action): Long = when (action.type) {
        "open_app" -> 2800L
        "click", "double_tap", "long_press" -> 700L
        "swipe", "drag" -> 500L
        "type" -> 400L
        "system_button" -> 500L
        "wait" -> (action.duration ?: 1) * 1000L
        else -> 500L
    }

    /** 获取模板提示列表 (触发词 → 描述)，供 UI 显示 */
    fun getTemplateHints(): List<Pair<String, String>> = buildList {
        for ((id, tpl) in templates) {
            val trigger = tpl.intentPatterns.firstOrNull()?.replace("X", "…") ?: id
            add(trigger to tpl.name)
        }
    }

    fun countFragments(): Int =
        fragmentIndex.values.sumOf { cats -> cats.values.sumOf { it.size } }

    fun getAllFragments(): List<SkillFragment> = buildList {
        fragmentIndex.values.forEach { cats ->
            cats.values.forEach { list -> addAll(list) }
        }
    }
}

data class FragmentMatch(
    val fragments: List<SkillFragment>,
    val params: Map<String, String>,
    val template: FragmentSkillTemplate,
    val confidence: Float
)
