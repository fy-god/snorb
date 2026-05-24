package com.roubao.autopilot.macro.model

import android.graphics.Rect
import com.roubao.autopilot.skills.UiNode

enum class MacroDomain {
    MESSAGE,
    SEARCH,
    NAVIGATION,
    PAYMENT,
    MEDIA,
    SHOPPING,
    SYSTEM,
    CUSTOM
}

enum class ParamType {
    TEXT,
    CONTACT,
    APP_NAME,
    URL,
    SEARCH_QUERY,
    NUMBER
}

enum class TemplateSource {
    BUILTIN,
    RECORDED_GENERALIZED,
    AI_GENERATED,
    MANUAL_EDIT
}

enum class RecoverPolicy {
    NONE,
    BACK_TO_KNOWN_SCREEN
}

enum class UiRole {
    BUTTON,
    INPUT,
    SEARCH_ENTRY,
    SEARCH_RESULT,
    TAB,
    LIST,
    CHAT_INPUT,
    SEND_BUTTON,
    DIALOG_BUTTON,
    UNKNOWN
}

enum class CoordinateSource {
    ACCESSIBILITY_NODE,
    USER_RECORDING,
    AI_ASSISTED,
    MANUAL_EDIT,
    BUILTIN
}

data class MacroParam(
    val name: String,
    val type: ParamType,
    val required: Boolean = true,
    val aliases: List<String> = emptyList()
)

data class MacroTemplate(
    val id: String,
    val name: String,
    val appPackage: String,
    val appName: String,
    val domain: MacroDomain,
    val intentId: String,
    val paramSchema: List<MacroParam>,
    val atoms: List<MacroAtom>,
    val finalVerify: VerifyRule,
    val version: Int = 1,
    val source: TemplateSource = TemplateSource.RECORDED_GENERALIZED
)

sealed class MacroAtom {
    data class OpenApp(val appPackage: String) : MacroAtom()

    data class EnsureScreen(
        val screenId: String,
        val recoverPolicy: RecoverPolicy = RecoverPolicy.BACK_TO_KNOWN_SCREEN
    ) : MacroAtom()

    data class ClickElement(
        val elementId: String,
        val fallbackAllowed: Boolean = true
    ) : MacroAtom()

    data class TypeText(
        val textParam: String,
        val targetElementId: String? = null
    ) : MacroAtom()

    data class SearchInApp(
        val queryParam: String,
        val searchEntryElementId: String = "search_entry"
    ) : MacroAtom()

    data class SelectItem(
        val itemTextParam: String,
        val listElementId: String? = null
    ) : MacroAtom()

    data class ScrollUntilVisible(
        val targetTextParam: String,
        val containerElementId: String? = null,
        val maxSwipes: Int = 6
    ) : MacroAtom()

    data class WaitFor(
        val rule: VerifyRule,
        val timeoutMs: Long = 3000L
    ) : MacroAtom()

    data class Verify(val rule: VerifyRule) : MacroAtom()
}

data class MacroPlan(
    val templateId: String,
    val appPackage: String,
    val params: Map<String, String>,
    val atoms: List<MacroAtom>
)

data class MacroResult(
    val success: Boolean,
    val message: String
) {
    companion object {
        fun success(message: String) = MacroResult(true, message)
        fun failed(message: String) = MacroResult(false, message)
    }
}

data class VerifyResult(
    val success: Boolean,
    val reason: String = ""
) {
    companion object {
        fun success() = VerifyResult(true)
        fun failed(reason: String) = VerifyResult(false, reason)
    }
}

sealed class VerifyRule {
    data class PackageVisible(val packageName: String) : VerifyRule()
    data class ScreenContains(val text: String? = null, val textParam: String? = null) : VerifyRule()
    data class MessageSent(val contactParam: String, val messageParam: String) : VerifyRule()
    object ScreenChanged : VerifyRule()
    object AlwaysTrue : VerifyRule()
}

data class AppUiModule(
    val appPackage: String,
    val appName: String,
    val appVersionName: String? = null,
    val appVersionCode: Long? = null,
    val screens: List<UiScreenModule>,
    val updatedAt: Long
) {
    fun findScreen(screenId: String): UiScreenModule? = screens.firstOrNull { it.id == screenId }

    fun findElement(elementId: String, screenId: String? = null): UiElementSpec? {
        val source = if (screenId == null) screens else screens.filter { it.id == screenId }
        return source.asSequence()
            .flatMap { it.elements.asSequence() }
            .firstOrNull { it.id == elementId }
    }
}

data class UiScreenModule(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val signatures: List<UiSignature>,
    val elements: List<UiElementSpec>,
    val recoverActions: List<RecoverAction> = emptyList()
)

data class UiSignature(
    val requiredTextsAny: List<String> = emptyList(),
    val requiredTextsAll: List<String> = emptyList(),
    val requiredResourceIdsAny: List<String> = emptyList(),
    val packageName: String? = null,
    val activityName: String? = null,
    val minScore: Float = 0.75f
)

data class UiElementSpec(
    val id: String,
    val name: String,
    val role: UiRole,
    val matchers: List<ElementMatcher>,
    val coordinatePack: CoordinatePack? = null,
    val requiredScreenId: String? = null,
    val confidence: Float = 1.0f,
    val lastVerifiedAt: Long? = null
)

data class ElementMatcher(
    val text: String? = null,
    val textContains: String? = null,
    val contentDesc: String? = null,
    val contentDescContains: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val clickable: Boolean? = null,
    val editable: Boolean? = null
) {
    fun matches(node: UiNode): Boolean {
        if (text != null && node.text != text) return false
        if (textContains != null && node.text?.contains(textContains) != true) return false
        if (contentDesc != null && node.contentDesc != contentDesc) return false
        if (contentDescContains != null && node.contentDesc?.contains(contentDescContains) != true) return false
        if (resourceId != null && node.resourceId != resourceId) return false
        if (className != null && node.className != className) return false
        if (clickable != null && node.clickable != clickable) return false
        if (editable != null && node.editable != editable) return false
        return true
    }
}

data class CoordinatePack(
    val screenWidth: Int,
    val screenHeight: Int,
    val densityDpi: Int? = null,
    val normalizedBounds: NormalizedRect,
    val anchor: AnchorSpec? = null,
    val fallbackTap: NormalizedPoint,
    val confidence: Float,
    val source: CoordinateSource,
    val samples: List<CoordinateSample> = emptyList()
) {
    fun toRealPoint(width: Int, height: Int): Pair<Int, Int> {
        return Pair(
            (fallbackTap.x * width).toInt(),
            (fallbackTap.y * height).toInt()
        )
    }
}

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class NormalizedPoint(
    val x: Float,
    val y: Float
)

data class AnchorSpec(
    val relativeToElementId: String? = null,
    val horizontal: String? = null,
    val vertical: String? = null
)

data class CoordinateSample(
    val screenWidth: Int,
    val screenHeight: Int,
    val x: Int,
    val y: Int,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class RecoverAction {
    object Back : RecoverAction()
    object Home : RecoverAction()
    data class Wait(val ms: Long) : RecoverAction()
}

sealed class ResolvedElement {
    abstract val confidence: Float

    data class Node(
        val node: UiNode,
        override val confidence: Float = 1.0f
    ) : ResolvedElement()

    data class Coordinate(
        val x: Int,
        val y: Int,
        override val confidence: Float
    ) : ResolvedElement()

    fun center(): Pair<Int, Int> {
        return when (this) {
            is Coordinate -> x to y
            is Node -> node.bounds.centerX() to node.bounds.centerY()
        }
    }

    fun bounds(): Rect? {
        return when (this) {
            is Coordinate -> null
            is Node -> node.bounds
        }
    }
}
