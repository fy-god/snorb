package com.roubao.autopilot.skills

import com.roubao.autopilot.controller.AppScanner

class SkillWorkflowAssembler(
    private val appScanner: AppScanner
) {
    fun assemble(instruction: String): SkillPlanCandidate? {
        val cleaned = instruction.trim()
        return assembleWechatSend(cleaned)
            ?: assembleWechatSearch(cleaned)
            ?: assembleWechatMoments(cleaned)
            ?: assembleBrowserSearch(cleaned)
            ?: assembleDouyinSearch(cleaned)
            ?: assembleSettingsSearch(cleaned)
            ?: assembleAlipayScan(cleaned)
            ?: assembleOpenApp(cleaned)
    }

    private fun assembleWechatSend(instruction: String): SkillPlanCandidate? {
        val patterns = listOf(
            Regex("微信.{0,3}给(.+?)发(?!送)(.+)"),
            Regex("给(.+?)发(.+)"),
            Regex("跟(.+?)说(.+)"),
            Regex("告诉(.+?)(.+)")
        )
        val match = patterns.firstNotNullOfOrNull { it.find(instruction) } ?: return null
        val contact = match.groupValues[1].trim()
        val message = match.groupValues[2].trim()
        if (contact.isBlank() || message.isBlank()) return null
        val plan = SkillWorkflowPlan(
            id = "wechat_send_message",
            name = "发微信消息",
            params = mapOf("contact" to contact, "message" to message),
            steps = listOf(
                SkillStep.OpenApp("微信", "com.tencent.mm", 500),
                SkillStep.Verify(VerifyRule.PackageVisible("com.tencent.mm")),
                SkillStep.Search(SkillTarget(text = "搜索", fallbackX = 500, fallbackY = 190), contact, waitMs = 300),
                SkillStep.Verify(VerifyRule.UiTextContains(contact)),
                SkillStep.ClickTarget(SkillTarget(text = contact, fallbackX = 500, fallbackY = 380), waitMs = 450),
                SkillStep.FocusInput(SkillTarget(className = "android.widget.EditText"), waitMs = 250),
                SkillStep.TypeText(message, waitMs = 300),
                SkillStep.Verify(VerifyRule.UiTextContains(message)),
                SkillStep.Send(SkillTarget(text = "发送"), waitMs = 300)
            )
        )
        return SkillPlanCandidate.weak(
            source = "SkillWorkflowAssembler", plan = plan, confidence = 0.55f,
            reason = "微信联系人搜索、聊天入口、发送按钮都需要屏幕判断，硬编码坐标不可靠"
        )
    }

    private fun assembleWechatSearch(instruction: String): SkillPlanCandidate? {
        val match = Regex("微信.{0,2}搜(.+)").find(instruction) ?: return null
        val contact = match.groupValues[1].trim()
        val plan = SkillWorkflowPlan(
            id = "wechat_search",
            name = "微信搜索联系人",
            params = mapOf("contact" to contact),
            steps = listOf(
                SkillStep.OpenApp("微信", "com.tencent.mm", 500),
                SkillStep.Verify(VerifyRule.PackageVisible("com.tencent.mm")),
                SkillStep.Search(SkillTarget(text = "搜索", fallbackX = 500, fallbackY = 190), contact, waitMs = 300),
                SkillStep.Verify(VerifyRule.UiTextContains(contact)),
                SkillStep.ClickTarget(SkillTarget(text = contact, fallbackX = 500, fallbackY = 380), waitMs = 400)
            )
        )
        return SkillPlanCandidate.weak(
            source = "SkillWorkflowAssembler", plan = plan, confidence = 0.55f,
            reason = "微信搜索结果依赖界面状态，定位不可靠"
        )
    }

    private fun assembleWechatMoments(instruction: String): SkillPlanCandidate? {
        if (!Regex("朋友圈|刷朋友圈|看朋友圈").containsMatchIn(instruction)) return null
        val plan = SkillWorkflowPlan(
            id = "wechat_moments",
            name = "微信朋友圈",
            params = emptyMap(),
            steps = listOf(
                SkillStep.OpenApp("微信", "com.tencent.mm", 500),
                SkillStep.Verify(VerifyRule.PackageVisible("com.tencent.mm")),
                SkillStep.Click(330, 940, waitMs = 250),
                SkillStep.Click(500, 780, waitMs = 500)
            )
        )
        return SkillPlanCandidate.weak(
            source = "SkillWorkflowAssembler", plan = plan, confidence = 0.60f,
            reason = "微信底部 Tab 坐标依赖屏幕分辨率，硬编码不可靠"
        )
    }

    private fun assembleBrowserSearch(instruction: String): SkillPlanCandidate? {
        val match = Regex("^(?:搜|搜索|查|百度搜?)(.+)").find(instruction) ?: return null
        val query = match.groupValues[1].trim()
        if (query.isBlank()) return null
        val plan = SkillWorkflowPlan(
            id = "browser_search",
            name = "浏览器搜索",
            params = mapOf("query" to query),
            steps = listOf(
                SkillStep.OpenApp("浏览器", null, 500),
                SkillStep.Click(500, 140, waitMs = 250),
                SkillStep.TypeText(query, waitMs = 300),
                SkillStep.SystemButton("Enter", waitMs = 500)
            )
        )
        return SkillPlanCandidate.weak(
            source = "SkillWorkflowAssembler", plan = plan, confidence = 0.65f,
            reason = "浏览器版本/UI 各异，地址栏定位不稳定"
        )
    }

    private fun assembleDouyinSearch(instruction: String): SkillPlanCandidate? {
        val match = Regex("抖音.{0,2}搜(.+)").find(instruction) ?: return null
        val query = match.groupValues[1].trim()
        val plan = SkillWorkflowPlan(
            id = "douyin_search",
            name = "抖音搜索",
            params = mapOf("query" to query),
            steps = listOf(
                SkillStep.OpenApp("抖音", "com.ss.android.ugc.aweme", 700),
                SkillStep.Verify(VerifyRule.PackageVisible("com.ss.android.ugc.aweme")),
                SkillStep.Click(900, 100, waitMs = 300),
                SkillStep.Click(500, 140, waitMs = 200),
                SkillStep.TypeText(query, waitMs = 300),
                SkillStep.SystemButton("Enter", waitMs = 500)
            )
        )
        return SkillPlanCandidate.weak(
            source = "SkillWorkflowAssembler", plan = plan, confidence = 0.65f,
            reason = "抖音搜索入口坐标依赖版本，硬编码不稳定"
        )
    }

    private fun assembleSettingsSearch(instruction: String): SkillPlanCandidate? {
        val match = Regex("(?:打开)?设置(.+)").find(instruction) ?: return null
        val item = match.groupValues[1].trim()
        if (item.isBlank()) return null
        val plan = SkillWorkflowPlan(
            id = "settings_search",
            name = "设置搜索",
            params = mapOf("item" to item),
            steps = listOf(
                SkillStep.OpenApp("设置", "com.android.settings", 500),
                SkillStep.Verify(VerifyRule.PackageVisible("com.android.settings")),
                SkillStep.Click(500, 100, waitMs = 250),
                SkillStep.TypeText(item, waitMs = 300),
                SkillStep.Verify(VerifyRule.UiTextContains(item)),
                SkillStep.Click(500, 400, waitMs = 500)
            )
        )
        return SkillPlanCandidate.perfect(
            source = "SkillWorkflowAssembler", plan = plan,
            reason = "系统设置搜索流程稳定，有文本验证"
        )
    }

    private fun assembleAlipayScan(instruction: String): SkillPlanCandidate? {
        if (!Regex("支付宝扫码|扫一扫|扫码|付款码").containsMatchIn(instruction)) return null
        val plan = SkillWorkflowPlan(
            id = "alipay_scan",
            name = "支付宝扫码",
            params = emptyMap(),
            steps = listOf(
                SkillStep.OpenApp("支付宝", "com.eg.android.AlipayGphone", 500),
                SkillStep.Verify(VerifyRule.PackageVisible("com.eg.android.AlipayGphone")),
                SkillStep.Click(500, 900, waitMs = 500)
            )
        )
        return SkillPlanCandidate.weak(
            source = "SkillWorkflowAssembler", plan = plan, confidence = 0.65f,
            reason = "支付宝扫码按钮位置依赖首页布局，硬编码不可靠"
        )
    }

    private fun assembleOpenApp(instruction: String): SkillPlanCandidate? {
        val match = Regex("打开(.{1,10}?)(?:应用|app|App)?$").find(instruction) ?: return null
        val app = match.groupValues[1].trim()
        if (app.isBlank()) return null
        val packageName = appScanner.findPackage(app)
        val plan = SkillWorkflowPlan(
            id = "open_app",
            name = "打开$app",
            params = mapOf("app" to app),
            steps = listOf(SkillStep.OpenApp(app, packageName, 500))
        )
        val hasPackage = packageName != null
        return SkillPlanCandidate(
            source = "SkillWorkflowAssembler", plan = plan,
            confidence = if (hasPackage) 0.95f else 0.60f,
            requiresScreenUnderstanding = false,
            hasFinalVerification = hasPackage,
            reason = if (hasPackage) "应用包名已匹配，shell 打开稳定" else "应用包名未找到，无法验证"
        )
    }
}
