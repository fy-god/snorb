package com.roubao.autopilot.skills

/**
 * GoalDecomposer — 把用户指令拆为语义子目标序列。
 *
 * 输入：原始指令 + IntentParser 解析结果
 * 输出：Goal 列表，例如：
 *   给微信传输助手发111 →
 *     [EnsureAppReady, FindContactOrConversation, OpenConversation, TypeMessage, SendMessage, VerifyGoal]
 *
 * 每个 Goal 携带独立参数（appName/targetText/message），
 * AtomicSkillResolver 用 Goal + UiState 匹配最佳 AtomicSkill。
 */
class GoalDecomposer {

    companion object {
        private const val TAG = "[GoalDecomposer]"

        private val appPackageMap = mapOf(
            "微信" to "com.tencent.mm",
            "支付宝" to "com.eg.android.AlipayGphone",
            "抖音" to "com.ss.android.ugc.aweme",
            "淘宝" to "com.taobao.taobao",
            "京东" to "com.jingdong.app.mall",
            "美团" to "com.sankuai.meituan",
            "饿了么" to "me.ele",
            "高德地图" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "哔哩哔哩" to "tv.danmaku.bili",
            "B站" to "tv.danmaku.bili",
            "小红书" to "com.xingin.xhs",
            "拼多多" to "com.xunmeng.pinduoduo",
            "网易云音乐" to "com.netease.cloudmusic",
            "QQ音乐" to "com.tencent.qqmusic",
            "知乎" to "com.zhihu.android",
            "微博" to "com.sina.weibo",
            "今日头条" to "com.ss.android.article.news",
            "滴滴出行" to "com.sdu.didi.psnger",
            "携程" to "ctrip.android.view",
            "闲鱼" to "com.taobao.idlefish",
            "钉钉" to "com.alibaba.android.rimet",
            "飞书" to "com.ss.android.lark",
            "企业微信" to "com.tencent.wework",
            "百度" to "com.baidu.searchbox",
            "浏览器" to "com.android.browser",
            "Chrome" to "com.android.chrome",
            "设置" to "com.android.settings",
            "相机" to "com.android.camera",
            "相册" to "com.android.gallery3d",
            "电话" to "com.android.dialer",
            "短信" to "com.android.mms",
            "日历" to "com.android.calendar",
            "时钟" to "com.android.deskclock",
            "计算器" to "com.android.calculator2",
            "文件管理" to "com.android.fileexplorer"
        )
    }

    /**
     * 模板 → 子目标序列生成器。
     * 用模板 ID 决定目标序列和参数绑定方式。
     */
    private val templates: Map<String, GoalSequenceBuilder> = mapOf(
        // ======== 微信发消息 ========
        "wechat_send_message" to GoalSequenceBuilder { intent ->
            val target = intent.params["X"] ?: return@GoalSequenceBuilder emptyList()
            val message = intent.params["Y"] ?: return@GoalSequenceBuilder emptyList()
            sequenceForSendMessage("微信", target, message)
        },

        // ======== 微信搜索 ========
        "wechat_search" to GoalSequenceBuilder { intent ->
            val query = intent.params["X"] ?: return@GoalSequenceBuilder emptyList()
            sequenceForAppSearch("微信", query)
        },

        // ======== 微信朋友圈 ========
        "wechat_moments" to GoalSequenceBuilder { _ ->
            listOf(
                Goal(type = GoalType.EnsureAppReady, appName = "微信", appPackage = pkg("微信")),
                Goal(type = GoalType.ClickSemanticTarget, targetText = "朋友圈", title = "进入朋友圈")
            )
        },

        // ======== 浏览器搜索 ========
        "browser_search" to GoalSequenceBuilder { intent ->
            val query = intent.params["X"] ?: return@GoalSequenceBuilder emptyList()
            sequenceForAppSearch("浏览器", query)
        },

        // ======== 抖音搜索 ========
        "douyin_search" to GoalSequenceBuilder { intent ->
            val query = intent.params["X"] ?: return@GoalSequenceBuilder emptyList()
            sequenceForAppSearch("抖音", query)
        },

        // ======== 淘宝搜索 ========
        "taobao_search" to GoalSequenceBuilder { intent ->
            val query = intent.params["X"] ?: return@GoalSequenceBuilder emptyList()
            sequenceForAppSearch("淘宝", query)
        },

        // ======== 淘宝打开 ========
        "taobao_open" to GoalSequenceBuilder { _ ->
            listOf(Goal(type = GoalType.EnsureAppReady, appName = "淘宝", appPackage = pkg("淘宝")))
        },

        // ======== 设置搜索 ========
        "settings_open" to GoalSequenceBuilder { intent ->
            val query = intent.params["X"] ?: return@GoalSequenceBuilder emptyList()
            sequenceForAppSearch("设置", query)
        },

        // ======== 支付宝扫码 ========
        "alipay_scan" to GoalSequenceBuilder { _ ->
            listOf(
                Goal(type = GoalType.EnsureAppReady, appName = "支付宝", appPackage = pkg("支付宝")),
                Goal(type = GoalType.ClickSemanticTarget, targetText = "扫一扫", title = "点击扫码")
            )
        },

        // ======== 通用打开应用 ========
        "open_app_only" to GoalSequenceBuilder { intent ->
            val appName = intent.appName
            listOf(
                Goal(type = GoalType.EnsureAppReady, appName = appName, appPackage = pkg(appName))
            )
        }
    )

    // ---- 通用非模板分解（基于意图类型推断） ----

    /**
     * 主入口：把用户指令分解为 Goal 序列。
     * 如果 IntentParser 匹配到模板，用模板分解；否则用启发式分解。
     */
    fun decompose(instruction: String, intent: ParsedIntent?): List<Goal> {
        // 有模板匹配 → 模板分解
        if (intent != null && intent.templateId in templates) {
            val goals = templates[intent.templateId]!!.build(intent)
            if (goals.isNotEmpty()) {
                println("$TAG 模板分解: ${intent.templateId} → ${goals.size} 个子目标")
                goals.forEachIndexed { i, g ->
                    println("$TAG   [$i] ${g.type.name} app=${g.appName} target=${g.targetText} msg=${g.message}")
                }
                return goals
            }
        }

        // 无模板或模板分解失败 → 启发式分解
        val goals = heuristicDecompose(instruction, intent)
        if (goals.isNotEmpty()) {
            println("$TAG 启发式分解: ${goals.size} 个子目标")
        }
        return goals
    }

    /**
     * 启发式分解：不依赖模板，从指令文本中提取动作序列。
     * 覆盖模板未覆盖的通用语义组合。
     */
    private fun heuristicDecompose(instruction: String, intent: ParsedIntent?): List<Goal> {
        val goals = mutableListOf<Goal>()
        val appName = intent?.appName

        // Step 1: 如果有目标应用 → EnsureAppReady
        if (appName != null) {
            goals.add(
                Goal(type = GoalType.EnsureAppReady, appName = appName, appPackage = pkg(appName))
            )
        }

        // Step 2: 根据关键词推断后续动作
        val hasSend = Regex("发|发送|发消息|发微信").containsMatchIn(instruction)
        val hasSearch = Regex("搜|搜索|查|查找").containsMatchIn(instruction)
        val hasScan = Regex("扫|扫码|扫一扫|付款码").containsMatchIn(instruction)
        val hasMoments = Regex("朋友圈").containsMatchIn(instruction)

        val target = intent?.params?.get("X")
        val message = intent?.params?.get("Y")

        when {
            hasSend && target != null && message != null -> {
                goals.addAll(
                    sequenceForSendMessage(appName ?: "微信", target, message)
                        .drop(1) // 跳过 EnsureAppReady（已添加）
                )
            }
            hasSearch && target != null -> {
                goals.add(
                    Goal(type = GoalType.SearchCurrentApp, query = target, title = "搜索 $target")
                )
            }
            hasScan -> {
                goals.add(
                    Goal(type = GoalType.ClickSemanticTarget, targetText = "扫一扫", title = "扫码")
                )
            }
            hasMoments -> {
                goals.add(
                    Goal(type = GoalType.ClickSemanticTarget, targetText = "朋友圈", title = "进入朋友圈")
                )
            }
        }

        return goals
    }

    // ---- 可复用序列构造 ----

    /**
     * "发消息" 完整子目标序列。
     */
    private fun sequenceForSendMessage(appName: String, target: String, message: String): List<Goal> {
        return listOf(
            Goal(type = GoalType.EnsureAppReady, appName = appName, appPackage = pkg(appName), title = "打开 $appName"),
            Goal(type = GoalType.FindContactOrConversation, appName = appName, targetText = target, title = "找到 $target"),
            Goal(type = GoalType.OpenConversation, appName = appName, targetText = target, title = "进入 $target 聊天"),
            Goal(type = GoalType.TypeMessage, targetText = target, message = message, title = "输入 '$message'"),
            Goal(type = GoalType.SendMessage, appName = appName, message = message, title = "发送消息"),
            Goal(type = GoalType.VerifyGoal, appName = appName, targetText = target, message = message, title = "验证消息已发送")
        )
    }

    /**
     * "在应用内搜索" 子目标序列。
     */
    private fun sequenceForAppSearch(appName: String, query: String): List<Goal> {
        return listOf(
            Goal(type = GoalType.EnsureAppReady, appName = appName, appPackage = pkg(appName), title = "打开 $appName"),
            Goal(type = GoalType.SearchCurrentApp, appName = appName, query = query, title = "搜索 '$query'")
        )
    }

    private fun pkg(appName: String): String? = appPackageMap[appName]
}

/**
 * 函数式接口：从 ParsedIntent 构造 Goal 序列。
 */
private fun interface GoalSequenceBuilder {
    fun build(intent: ParsedIntent): List<Goal>
}
