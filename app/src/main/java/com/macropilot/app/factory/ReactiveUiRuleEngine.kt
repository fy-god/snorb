package com.macropilot.app.factory

import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.model.NodeSample
import com.macropilot.app.model.NormalizedPoint
import com.macropilot.app.model.UiStateSample
import com.macropilot.app.runtime.NodeInspector
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class ReactiveUiRuleEngine(
    private val inspector: NodeInspector,
    private val actions: DeviceActions
) {
    fun dismissBlockingOverlays(
        appPackage: String?,
        reason: String,
        maxPasses: Int = 2
    ): ReactiveRuleResult {
        val events = JSONArray()
        var dismissed = 0
        repeat(maxPasses.coerceIn(0, 4)) { pass ->
            val state = inspector.currentState() ?: return@repeat
            val candidate = chooseDismissCandidate(state, appPackage) ?: return@repeat
            MacroPilotAccessibilityService.instance?.showStatusOverlay(
                title = "GKD-like 弹窗规则",
                status = "RUNNING",
                message = "处理 ${candidate.kind}: ${candidate.label.take(32)}"
            )
            val outcome = clickCandidate(state, candidate)
            events.put(JSONObject()
                .put("pass", pass + 1)
                .put("kind", candidate.kind)
                .put("label", candidate.label)
                .put("score", candidate.score)
                .put("reason", reason)
                .put("success", outcome.success)
                .put("message", outcome.reason)
                .put("bounds", candidate.node.bounds.flattenToString()))
            if (!outcome.success) return@repeat
            dismissed++
            sleep(360L)
        }
        return ReactiveRuleResult(
            dismissedCount = dismissed,
            events = events
        )
    }

    fun blockingDialogSummary(state: UiStateSample?): JSONArray {
        if (state == null) return JSONArray()
        val visible = state.visibleLabels()
        val eventTexts = state.eventsSinceLastState.mapNotNull { event ->
            event.text?.takeIf { it.isNotBlank() }?.let { "${event.eventType}: $it" }
        }
        val smallNodes = state.smallPopupLabels()
        val allSignals = visible + eventTexts + smallNodes
        val out = JSONArray()
        val blockers = listOf(
            DialogMarker("app_start_confirm", listOf("想要打开", "30天内允许", "仅本次允许")),
            DialogMarker("ad_or_splash", listOf("广告", "跳过", "开屏", "赞助", "推广")),
            DialogMarker("hupu_section_required", listOf("选择专区", "请选择专区", "请选择一个专区", "选择板块", "选择版块", "专区才能发帖")),
            DialogMarker("login_required", listOf("请登录", "登录后", "未登录", "账号验证", "身份验证", "完成验证", "手机号登录", "验证码")),
            DialogMarker("permission_or_notice", listOf("权限", "通知", "推送", "青少年", "升级", "更新", "隐私")),
            DialogMarker("wechat_personalization_notice", listOf("启用个性化推荐", "个性化推荐", "暂不启用", "不启用", "选择铃声", "历史铃声", "朋友铃声")),
            DialogMarker("publish_blocker", listOf("发布失败", "发帖失败", "内容不能为空", "标题不能为空", "网络异常")),
            DialogMarker("share_sheet", listOf("微信好友", "微信朋友圈", "QQ好友", "QQ空间", "复制链接", "分享海报", "不推荐", "举报", "取消"))
        )
        blockers.forEach { marker ->
            val hits = allSignals.filter { label -> marker.needles.any { label.contains(it, ignoreCase = true) } }
                .take(8)
            if (hits.isNotEmpty()) {
                out.put(JSONObject()
                    .put("type", marker.type)
                    .put("hits", JSONArray(hits)))
            }
        }
        return out
    }

    fun enhancedBlockingDialogSummary(state: UiStateSample?): JSONArray {
        val out = blockingDialogSummary(state)
        if (state == null) return out
        val eventTexts = state.eventsSinceLastState.mapNotNull { event ->
            event.text?.takeIf { it.isNotBlank() }?.let { "${event.eventType}: $it" }
        }
        val smallNodes = state.smallPopupLabels()
        if (eventTexts.isNotEmpty() || smallNodes.isNotEmpty()) {
            out.put(JSONObject()
                .put("type", "popup_observed")
                .put("events", JSONArray(eventTexts.take(10)))
                .put("smallNodes", JSONArray(smallNodes.take(10))))
        }
        return out
    }

    private fun UiStateSample.smallPopupLabels(): List<String> {
        val minWidth = (screenWidth * 0.08f).toInt()
        val maxWidth = (screenWidth * 0.90f).toInt()
        val maxHeight = (screenHeight * 0.18f).toInt()
        val top = (screenHeight * 0.10f).toInt()
        val bottom = (screenHeight * 0.90f).toInt()
        return nodes.mapNotNull { node ->
            val label = node.label().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val width = node.bounds.width()
            val height = node.bounds.height()
            val centerY = node.bounds.centerY()
            val looksLikePopup = width in minWidth..maxWidth &&
                height in 1..maxHeight &&
                centerY in top..bottom &&
                !node.clickable &&
                !node.editable &&
                label.length <= 80
            if (looksLikePopup) label else null
        }
    }

    private fun chooseDismissCandidate(state: UiStateSample, appPackage: String?): DismissCandidate? {
        val visible = state.visibleLabels()
        val context = visible.joinToString(" ").lowercase(Locale.CHINA)
        val hupu = appPackage.orEmpty().contains("hupu", ignoreCase = true) ||
            state.appPackage.orEmpty().contains("hupu", ignoreCase = true)
        val wechat = appPackage == SkillJsonRuleGenerator.WECHAT_PACKAGE ||
            state.appPackage == SkillJsonRuleGenerator.WECHAT_PACKAGE
        val shareSheetContext = (hupu && isHupuShareSheetContext(state, context)) ||
            isGenericShareSheetContext(state, context)
        val windowClass = state.windowClassName.orEmpty().lowercase(Locale.CHINA)
        val appStartConfirmContext = listOf(
            "想要打开",
            "30天内允许",
            "仅本次允许",
            "appstartconfirmdialogactivity"
        ).any { marker ->
            context.contains(marker.lowercase(Locale.CHINA)) ||
                windowClass.contains(marker.lowercase(Locale.CHINA))
        }
        val wechatPersonalizationContext = wechat && listOf(
            "启用个性化推荐",
            "个性化推荐",
            "暂不启用",
            "不启用",
            "选择铃声",
            "历史铃声",
            "朋友铃声",
            "播放器播放"
        ).any { context.contains(it.lowercase(Locale.CHINA)) }
        val pinduoduoCameraGuideContext = isPinduoduoCameraGuideContext(state, context, appPackage)
        val adContext = if (hupu) {
            isHupuRealAdContext(state, context)
        } else {
            listOf("广告", "跳过", "开屏", "赞助", "推广", "splash", "ad")
                .any { context.contains(it.lowercase(Locale.CHINA)) }
        }
        val dialogContext = shareSheetContext || adContext || appStartConfirmContext || wechatPersonalizationContext || pinduoduoCameraGuideContext || listOf(
            "青少年",
            "通知",
            "推送",
            "权限",
            "升级",
            "更新",
            "隐私",
            "个性化",
            "弹窗",
            "稍后",
            "以后再说",
            "我知道了"
        ).any { context.contains(it.lowercase(Locale.CHINA)) }
        return state.nodes
            .asSequence()
            .filter { it.enabled && it.bounds.width() > 0 && it.bounds.height() > 0 }
            .mapNotNull { node ->
                scoreNode(
                    node,
                    state,
                    adContext,
                    dialogContext,
                    shareSheetContext,
                    appStartConfirmContext,
                    pinduoduoCameraGuideContext,
                    appPackage
                )
            }
            .filterNot { candidate -> isForbiddenDismiss(candidate.label, context) }
            .sortedWith(compareByDescending<DismissCandidate> { it.score }
                .thenBy { it.node.bounds.centerY() }
                .thenByDescending { it.node.bounds.centerX() })
            .firstOrNull()
    }

    private fun scoreNode(
        node: NodeSample,
        state: UiStateSample,
        adContext: Boolean,
        dialogContext: Boolean,
        shareSheetContext: Boolean,
        appStartConfirmContext: Boolean,
        pinduoduoCameraGuideContext: Boolean,
        appPackage: String?
    ): DismissCandidate? {
        val label = node.label()
        val lower = label.lowercase(Locale.CHINA)
        val topRight = node.bounds.centerX() > state.screenWidth * 0.72f &&
            node.bounds.centerY() < state.screenHeight * 0.24f
        val small = node.bounds.width() <= state.screenWidth * 0.22f &&
            node.bounds.height() <= state.screenHeight * 0.12f
        val resourceId = node.resourceId.orEmpty().lowercase(Locale.CHINA)
        val hupu = appPackage.orEmpty().contains("hupu", ignoreCase = true) ||
            state.appPackage.orEmpty().contains("hupu", ignoreCase = true)
        val googleMaps = appPackage == "com.google.android.apps.maps" ||
            state.appPackage == "com.google.android.apps.maps"
        val googleMapsCoolingNotice = googleMaps && state.nodes.any { node ->
            val text = node.label()
            text.contains("设备需要冷却") || text.contains("深色主题")
        }
        val wechat = appPackage == SkillJsonRuleGenerator.WECHAT_PACKAGE ||
            state.appPackage == SkillJsonRuleGenerator.WECHAT_PACKAGE
        if (isSearchFieldUtilityControl(resourceId, lower)) return null
        if (googleMaps && isGoogleMapsAccountOrUtilityControl(resourceId, lower)) return null
        if (hupu && isHupuNormalNavigationControl(resourceId)) return null
        if (hupu &&
            resourceId.contains("iv_close") &&
            !shareSheetContext &&
            !adContext &&
            !dialogContext
        ) {
            return null
        }
        val score = when {
            appStartConfirmContext && lower.contains("30天内允许") -> 126
            appStartConfirmContext && lower.contains("仅本次允许") -> 124
            appStartConfirmContext && resourceId.endsWith("button3") -> 122
            appStartConfirmContext && resourceId.endsWith("button1") -> 120
            lower.contains("跳过") -> 120
            lower.contains("skip") -> 118
            resourceId.contains("skip") -> 116
            wechat && (lower.contains("暂不启用") || lower == "暂不") -> 119
            wechat && (lower.contains("不启用") || lower.contains("不用了")) -> 116
            wechat && lower.contains("以后再说") -> 112
            shareSheetContext && lower.contains("关闭工作表") -> 118
            hupu && shareSheetContext && resourceId.contains("btn_cancel") -> 116
            shareSheetContext && lower.contains("取消") -> 115
            shareSheetContext && resourceId.contains("touch_outside") -> 113
            hupu && resourceId.contains("iv_close") && (shareSheetContext || adContext || dialogContext) -> 114
            hupu && resourceId.contains("rl_screen_ad_close") -> 114
            hupu && resourceId.contains("shield_view") -> 111
            lower.contains("关闭广告") -> 112
            lower.contains("确定") && googleMapsCoolingNotice -> 110
            hupu && lower.contains("屏蔽该广告") -> 107
            hupu && lower.contains("不感兴趣") -> 106
            (lower == "×" || lower == "x" || lower == "X".lowercase(Locale.US)) &&
                (!hupu || shareSheetContext || adContext || dialogContext) -> 108
            lower.contains("close") && ((!hupu && topRight) || shareSheetContext || adContext || dialogContext) -> 104
            lower.contains("关闭") && (adContext || dialogContext || (!hupu && topRight)) -> 100
            lower.contains("我知道了") -> 94
            lower.contains("知道了") && dialogContext -> 92
            lower.contains("以后再说") || lower.contains("稍后") || lower.contains("暂不") -> 90
            lower.contains("不感兴趣") && adContext -> 86
            lower.contains("取消") && pinduoduoCameraGuideContext -> 84
            lower.contains("取消") && (adContext || dialogContext) -> 72
            hupu && lower.contains("忽略") -> 70
            hupu &&
                (resourceId.contains("close") || resourceId.contains("dismiss")) &&
                (shareSheetContext || dialogContext || isHupuAdCloseResource(resourceId)) -> 68
            !hupu && node.clickable && topRight && small && (adContext || dialogContext) -> 66
            else -> 0
        }
        if (score <= 0) return null
        return DismissCandidate(
            node = node,
            label = label.ifBlank { "top_right_close_icon" },
            score = score,
            kind = if (adContext) "ad_or_splash" else "dialog"
        )
    }

    private fun clickCandidate(
        state: UiStateSample,
        candidate: DismissCandidate
    ): com.macropilot.app.automation.ActionOutcome {
        val x = candidate.node.bounds.centerX().toFloat() / state.screenWidth.coerceAtLeast(1)
        val y = candidate.node.bounds.centerY().toFloat() / state.screenHeight.coerceAtLeast(1)
        return actions.tapNormalized(
            NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)),
            state.screenWidth,
            state.screenHeight
        )
    }

    private fun isForbiddenDismiss(label: String, context: String): Boolean {
        val lower = label.lowercase(Locale.CHINA)
        val explicitDismiss = listOf("关闭", "跳过", "skip", "close", "iv_close", "shield_view", "×", "暂不启用", "不启用", "以后再说", "稍后", "我知道了", "取消")
            .any { lower.contains(it.lowercase(Locale.CHINA)) } ||
            lower.contains("btn_cancel") ||
            lower.contains("touch_outside") ||
            lower == "x"
        if (explicitDismiss) return false
        val forbidden = listOf(
            "支付",
            "付款",
            "转账",
            "购买",
            "下单",
            "删除",
            "注销",
            "退出登录",
            "发布",
            "发帖",
            "发表",
            "授权",
            "同意"
        )
        return forbidden.any { lower.contains(it.lowercase(Locale.CHINA)) }
    }

    private fun isSearchFieldUtilityControl(resourceId: String, lowerLabel: String): Boolean {
        return resourceId.contains("search_close", ignoreCase = true) ||
            resourceId.contains("search_clear", ignoreCase = true) ||
            resourceId.contains("search_delete", ignoreCase = true) ||
            resourceId.contains("clear_query", ignoreCase = true) ||
            lowerLabel.contains("清除查询") ||
            lowerLabel.contains("清空搜索") ||
            (lowerLabel.contains("search") && lowerLabel.contains("close"))
    }

    private fun isPinduoduoCameraGuideContext(
        state: UiStateSample,
        context: String,
        appPackage: String?
    ): Boolean {
        val isPdd = appPackage.orEmpty().contains("pinduoduo", ignoreCase = true) ||
            state.appPackage.orEmpty().contains("pinduoduo", ignoreCase = true)
        if (!isPdd) return false
        val markers = listOf(
            "一键找同款",
            "找同款",
            "开启相机权限",
            "相机权限",
            "允许摄像头权限",
            "我的相册",
            "去开启"
        )
        return markers.count { marker -> context.contains(marker.lowercase(Locale.CHINA)) } >= 2
    }

    private fun UiStateSample.visibleLabels(): List<String> {
        return nodes.mapNotNull { node ->
            node.label().takeIf { it.isNotBlank() }
        }
    }

    private fun NodeSample.label(): String {
        return listOfNotNull(text, contentDesc, resourceId)
            .joinToString(" ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
    }

    private fun isHupuShareSheetContext(state: UiStateSample, context: String): Boolean {
        if (state.nodes.any { it.resourceId.orEmpty().contains("share_content_container", ignoreCase = true) }) return true
        val shareMarkers = listOf(
            "微信好友",
            "微信朋友圈",
            "QQ好友",
            "QQ空间",
            "新浪微博",
            "收藏",
            "复制链接",
            "分享海报",
            "不推荐",
            "举报",
            "阅读设置",
            "取消"
        )
        return shareMarkers.count { context.contains(it.lowercase(Locale.CHINA)) } >= 2
    }

    private fun isGenericShareSheetContext(state: UiStateSample, context: String): Boolean {
        if (state.windowClassName.orEmpty().contains("supermenu", ignoreCase = true)) return true
        val shareMarkers = listOf(
            "分享",
            "QQ",
            "QQ空间",
            "微信",
            "朋友圈",
            "复制链接",
            "转发",
            "举报",
            "不推荐",
            "关闭工作表",
            "取消"
        )
        return shareMarkers.count { context.contains(it.lowercase(Locale.CHINA)) } >= 2
    }

    private fun isHupuRealAdContext(state: UiStateSample, context: String): Boolean {
        val explicitText = listOf("广告", "跳过", "开屏", "赞助", "推广", "关闭广告", "屏蔽该广告")
            .any { context.contains(it.lowercase(Locale.CHINA)) }
        if (explicitText) return true
        return state.nodes.any { node ->
            isHupuAdCloseResource(node.resourceId.orEmpty().lowercase(Locale.CHINA))
        }
    }

    private fun isHupuAdCloseResource(resourceId: String): Boolean {
        return resourceId.contains("rl_screen_ad_close") ||
            resourceId.contains("shield_view") ||
            resourceId.contains("screen_ad_close") ||
            resourceId.contains("splash_close") ||
            resourceId.contains("ad_close")
    }

    private fun isHupuNormalNavigationControl(resourceId: String): Boolean {
        return listOf(
            "fl_right_edit",
            "tv_search",
            "iv_search",
            "et_search",
            "tv_tab",
            "tab_layout",
            "bottom_tab"
        ).any { marker -> resourceId.contains(marker) }
    }

    private fun isGoogleMapsAccountOrUtilityControl(resourceId: String, lowerLabel: String): Boolean {
        return resourceId.contains("selected_account_disc", ignoreCase = true) ||
            resourceId.contains("account", ignoreCase = true) ||
            resourceId.contains("mic", ignoreCase = true) ||
            resourceId.contains("voice", ignoreCase = true) ||
            lowerLabel.contains("账号和设置") ||
            lowerLabel.contains("语音搜索") ||
            lowerLabel.contains("account") ||
            lowerLabel.contains("settings")
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

data class ReactiveRuleResult(
    val dismissedCount: Int,
    val events: JSONArray
)

private data class DismissCandidate(
    val node: NodeSample,
    val label: String,
    val score: Int,
    val kind: String
)

private data class DialogMarker(
    val type: String,
    val needles: List<String>
)
