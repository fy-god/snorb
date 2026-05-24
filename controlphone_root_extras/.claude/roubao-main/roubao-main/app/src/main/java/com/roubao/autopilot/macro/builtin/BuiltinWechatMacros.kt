package com.roubao.autopilot.macro.builtin

import com.roubao.autopilot.macro.model.AppUiModule
import com.roubao.autopilot.macro.model.CoordinatePack
import com.roubao.autopilot.macro.model.CoordinateSource
import com.roubao.autopilot.macro.model.ElementMatcher
import com.roubao.autopilot.macro.model.MacroAtom
import com.roubao.autopilot.macro.model.MacroDomain
import com.roubao.autopilot.macro.model.MacroParam
import com.roubao.autopilot.macro.model.MacroTemplate
import com.roubao.autopilot.macro.model.NormalizedPoint
import com.roubao.autopilot.macro.model.NormalizedRect
import com.roubao.autopilot.macro.model.ParamType
import com.roubao.autopilot.macro.model.RecoverPolicy
import com.roubao.autopilot.macro.model.TemplateSource
import com.roubao.autopilot.macro.model.UiElementSpec
import com.roubao.autopilot.macro.model.UiRole
import com.roubao.autopilot.macro.model.UiScreenModule
import com.roubao.autopilot.macro.model.UiSignature
import com.roubao.autopilot.macro.model.VerifyRule

object BuiltinWechatMacros {
    const val PACKAGE = "com.tencent.mm"

    val appUi: AppUiModule
        get() = AppUiModule(
            appPackage = PACKAGE,
            appName = WECHAT,
            screens = listOf(homeScreen(), searchScreen(), chatScreen()),
            updatedAt = System.currentTimeMillis()
        )

    val templates: List<MacroTemplate>
        get() = listOf(sendMessageTemplate())

    private fun sendMessageTemplate(): MacroTemplate = MacroTemplate(
        id = "wechat_send_message",
        name = SEND_WECHAT_MESSAGE,
        appPackage = PACKAGE,
        appName = WECHAT,
        domain = MacroDomain.MESSAGE,
        intentId = "wechat_send_message",
        paramSchema = listOf(
            MacroParam(
                name = "contact",
                type = ParamType.CONTACT,
                aliases = listOf(CONTACT, CHAT_TARGET, RECIPIENT)
            ),
            MacroParam(
                name = "message",
                type = ParamType.TEXT,
                aliases = listOf(MESSAGE, CONTENT, SEND_CONTENT)
            )
        ),
        atoms = listOf(
            MacroAtom.OpenApp(PACKAGE),
            MacroAtom.EnsureScreen("wechat_home", RecoverPolicy.BACK_TO_KNOWN_SCREEN),
            MacroAtom.SearchInApp(queryParam = "contact", searchEntryElementId = "search_entry"),
            MacroAtom.SelectItem(itemTextParam = "contact"),
            MacroAtom.WaitFor(VerifyRule.ScreenContains(textParam = "contact"), timeoutMs = 3000L),
            MacroAtom.TypeText(textParam = "message", targetElementId = "chat_input"),
            MacroAtom.ClickElement(elementId = "send_button"),
            MacroAtom.Verify(VerifyRule.MessageSent(contactParam = "contact", messageParam = "message"))
        ),
        finalVerify = VerifyRule.MessageSent(contactParam = "contact", messageParam = "message"),
        version = 1,
        source = TemplateSource.BUILTIN
    )

    private fun homeScreen(): UiScreenModule = UiScreenModule(
        id = "wechat_home",
        name = WECHAT_HOME,
        signatures = listOf(
            UiSignature(
                packageName = PACKAGE,
                requiredTextsAny = listOf(WECHAT, CONTACTS, DISCOVER, ME),
                minScore = 0.75f
            )
        ),
        elements = listOf(
            UiElementSpec(
                id = "search_entry",
                name = SEARCH_ENTRY,
                role = UiRole.SEARCH_ENTRY,
                matchers = listOf(
                    ElementMatcher(contentDescContains = SEARCH, clickable = true),
                    ElementMatcher(textContains = SEARCH, clickable = true)
                ),
                coordinatePack = CoordinatePack(
                    screenWidth = 1080,
                    screenHeight = 2400,
                    normalizedBounds = NormalizedRect(0.82f, 0.03f, 0.94f, 0.09f),
                    fallbackTap = NormalizedPoint(0.89f, 0.06f),
                    confidence = 0.75f,
                    source = CoordinateSource.BUILTIN
                ),
                requiredScreenId = "wechat_home",
                confidence = 0.75f
            )
        )
    )

    private fun searchScreen(): UiScreenModule = UiScreenModule(
        id = "wechat_search",
        name = WECHAT_SEARCH,
        signatures = listOf(
            UiSignature(
                packageName = PACKAGE,
                requiredTextsAny = listOf(SEARCH, CANCEL),
                minScore = 0.60f
            )
        ),
        elements = listOf(
            UiElementSpec(
                id = "search_input",
                name = SEARCH_INPUT,
                role = UiRole.INPUT,
                matchers = listOf(
                    ElementMatcher(className = "android.widget.EditText", editable = true),
                    ElementMatcher(textContains = SEARCH, editable = true)
                ),
                coordinatePack = CoordinatePack(
                    screenWidth = 1080,
                    screenHeight = 2400,
                    normalizedBounds = NormalizedRect(0.12f, 0.035f, 0.80f, 0.095f),
                    fallbackTap = NormalizedPoint(0.45f, 0.065f),
                    confidence = 0.65f,
                    source = CoordinateSource.BUILTIN
                ),
                requiredScreenId = "wechat_search",
                confidence = 0.65f
            )
        )
    )

    private fun chatScreen(): UiScreenModule = UiScreenModule(
        id = "wechat_chat",
        name = WECHAT_CHAT,
        signatures = listOf(
            UiSignature(
                packageName = PACKAGE,
                requiredTextsAny = listOf(SEND, HOLD_TO_TALK),
                minScore = 0.65f
            )
        ),
        elements = listOf(
            UiElementSpec(
                id = "chat_input",
                name = CHAT_INPUT,
                role = UiRole.CHAT_INPUT,
                matchers = listOf(
                    ElementMatcher(className = "android.widget.EditText", editable = true)
                ),
                coordinatePack = CoordinatePack(
                    screenWidth = 1080,
                    screenHeight = 2400,
                    normalizedBounds = NormalizedRect(0.15f, 0.90f, 0.76f, 0.97f),
                    fallbackTap = NormalizedPoint(0.45f, 0.935f),
                    confidence = 0.70f,
                    source = CoordinateSource.BUILTIN
                ),
                requiredScreenId = "wechat_chat",
                confidence = 0.70f
            ),
            UiElementSpec(
                id = "send_button",
                name = SEND_BUTTON,
                role = UiRole.SEND_BUTTON,
                matchers = listOf(
                    ElementMatcher(text = SEND, clickable = true)
                ),
                coordinatePack = CoordinatePack(
                    screenWidth = 1080,
                    screenHeight = 2400,
                    normalizedBounds = NormalizedRect(0.80f, 0.90f, 0.98f, 0.97f),
                    fallbackTap = NormalizedPoint(0.90f, 0.935f),
                    confidence = 0.70f,
                    source = CoordinateSource.BUILTIN
                ),
                requiredScreenId = "wechat_chat",
                confidence = 0.70f
            )
        )
    )

    private const val WECHAT = "\u5fae\u4fe1"
    private const val CONTACTS = "\u901a\u8baf\u5f55"
    private const val DISCOVER = "\u53d1\u73b0"
    private const val ME = "\u6211"
    private const val SEARCH = "\u641c\u7d22"
    private const val CANCEL = "\u53d6\u6d88"
    private const val SEND = "\u53d1\u9001"
    private const val HOLD_TO_TALK = "\u6309\u4f4f \u8bf4\u8bdd"
    private const val CONTACT = "\u8054\u7cfb\u4eba"
    private const val CHAT_TARGET = "\u804a\u5929\u5bf9\u8c61"
    private const val RECIPIENT = "\u6536\u4ef6\u4eba"
    private const val MESSAGE = "\u6d88\u606f"
    private const val CONTENT = "\u5185\u5bb9"
    private const val SEND_CONTENT = "\u53d1\u9001\u5185\u5bb9"
    private const val SEND_WECHAT_MESSAGE = "\u53d1\u9001\u5fae\u4fe1\u6d88\u606f"
    private const val WECHAT_HOME = "\u5fae\u4fe1\u9996\u9875"
    private const val WECHAT_SEARCH = "\u5fae\u4fe1\u641c\u7d22\u9875"
    private const val WECHAT_CHAT = "\u5fae\u4fe1\u804a\u5929\u9875"
    private const val SEARCH_ENTRY = "\u641c\u7d22\u5165\u53e3"
    private const val SEARCH_INPUT = "\u641c\u7d22\u8f93\u5165\u6846"
    private const val CHAT_INPUT = "\u804a\u5929\u8f93\u5165\u6846"
    private const val SEND_BUTTON = "\u53d1\u9001\u6309\u94ae"
}
