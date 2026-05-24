package com.macropilot.app.factory

import android.content.Context
import com.macropilot.app.model.AppUiModule
import com.macropilot.app.model.CoordinatePack
import com.macropilot.app.model.CoordinateSource
import com.macropilot.app.model.CoordinateStatus
import com.macropilot.app.model.ElementMatcher
import com.macropilot.app.model.MacroAtom
import com.macropilot.app.model.MacroParam
import com.macropilot.app.model.MacroTemplate
import com.macropilot.app.model.NormalizedPoint
import com.macropilot.app.model.NormalizedRect
import com.macropilot.app.model.ReliabilityLevel
import com.macropilot.app.model.SideEffectLevel
import com.macropilot.app.model.SkillSource
import com.macropilot.app.model.UiElementSpec
import com.macropilot.app.model.UiRole
import com.macropilot.app.model.UiScreenModule
import com.macropilot.app.model.UiSignature
import com.macropilot.app.model.VerificationCapability
import com.macropilot.app.model.VerifyRule
import com.macropilot.app.model.VerifyRuleType
import com.macropilot.app.store.AppUiModuleStore
import com.macropilot.app.store.MacroTemplateStore
import org.json.JSONObject
import java.io.File

class WeChatCandidateFactory(private val context: Context) {
    fun createTypeDraftCandidate(
        screenWidth: Int,
        screenHeight: Int,
        chatInputX: Float,
        chatInputY: Float
    ): WeChatCandidateResult {
        val module = AppUiModule(
            packageName = WECHAT_PACKAGE,
            appName = "WeChat Candidate",
            screens = listOf(
                UiScreenModule(
                    id = "wechat_current_chat_low_confidence",
                    name = "WeChat current chat low-confidence candidate",
                    signatures = listOf(
                        UiSignature(
                            packageName = WECHAT_PACKAGE,
                            requiredTextsAny = emptyList(),
                            minScore = 0.10f
                        )
                    )
                )
            ),
            elements = listOf(
                UiElementSpec(
                    id = "chat_input",
                    name = "Current chat input coordinate candidate",
                    role = UiRole.CHAT_INPUT,
                    matchers = listOf(
                        ElementMatcher(className = "android.widget.EditText", editable = true, enabled = true)
                    ),
                    coordinatePack = coordinatePack(screenWidth, screenHeight, chatInputX, chatInputY),
                    requiredScreenId = "wechat_current_chat_low_confidence",
                    reliability = ReliabilityLevel.LOW,
                    samples = 1,
                    successRate = 0f
                ),
                UiElementSpec(
                    id = "keyboard_toggle",
                    name = "WeChat keyboard/emoji toggle coordinate candidate",
                    role = UiRole.BUTTON,
                    matchers = listOf(ElementMatcher(clickable = true, enabled = true)),
                    coordinatePack = coordinatePack(screenWidth, screenHeight, 0.865f, chatInputY),
                    requiredScreenId = "wechat_current_chat_low_confidence",
                    reliability = ReliabilityLevel.LOW,
                    samples = 1,
                    successRate = 0f
                ),
                UiElementSpec(
                    id = "voice_toggle",
                    name = "WeChat voice/text toggle coordinate candidate",
                    role = UiRole.BUTTON,
                    matchers = listOf(ElementMatcher(clickable = true, enabled = true)),
                    coordinatePack = coordinatePack(screenWidth, screenHeight, 0.060f, chatInputY),
                    requiredScreenId = "wechat_current_chat_low_confidence",
                    reliability = ReliabilityLevel.LOW,
                    samples = 1,
                    successRate = 0f
                )
            )
        )
        val skill = MacroTemplate(
            id = "wechat_type_current_chat_candidate",
            name = "WeChat type in current chat candidate",
            packageName = WECHAT_PACKAGE,
            intentId = "type_message_draft",
            params = listOf(MacroParam("message")),
            atoms = listOf(
                MacroAtom.FocusTextInput(
                    targetElementId = "chat_input",
                    toggleElementIds = listOf("keyboard_toggle", "voice_toggle"),
                    timeoutMs = 4000L
                ),
                MacroAtom.TypeText(textParam = "message", targetElementId = "chat_input", timeoutMs = 2500L)
            ),
            finalVerify = VerifyRule(type = VerifyRuleType.ALWAYS, minVerification = VerificationCapability.LOW),
            source = SkillSource.CANDIDATE,
            sideEffectLevel = SideEffectLevel.LOW,
            idempotent = false
        )
        val moduleFile = AppUiModuleStore(context).saveCandidate(module, "wechat_type_current_chat_candidate")
        val skillFile = MacroTemplateStore(context).saveCandidate(skill)
        return WeChatCandidateResult(skill.id, moduleFile, skillFile)
    }

    private fun coordinatePack(width: Int, height: Int, x: Float, y: Float): CoordinatePack {
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        return CoordinatePack(
            baseWidth = width,
            baseHeight = height,
            normalizedBounds = NormalizedRect(
                left = (nx - 0.08f).coerceIn(0f, 1f),
                top = (ny - 0.03f).coerceIn(0f, 1f),
                right = (nx + 0.08f).coerceIn(0f, 1f),
                bottom = (ny + 0.03f).coerceIn(0f, 1f)
            ),
            fallbackTap = NormalizedPoint(nx, ny),
            confidence = 0.35f,
            source = CoordinateSource.MANUAL_CALIBRATION,
            status = CoordinateStatus.NEEDS_RECALIBRATION
        )
    }

    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}

data class WeChatCandidateResult(
    val skillId: String,
    val moduleFile: File,
    val skillFile: File
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("skillId", skillId)
            .put("moduleFile", moduleFile.absolutePath)
            .put("skillFile", skillFile.absolutePath)
            .put("runtimeExecutable", false)
            .put("promotionRequired", true)
    }
}
