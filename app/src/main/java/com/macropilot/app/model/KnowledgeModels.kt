package com.macropilot.app.model

data class AppUiModule(
    val schemaVersion: Int = 1,
    val packageName: String,
    val packageAliases: List<String> = emptyList(),
    val appName: String,
    val appVersionName: String? = null,
    val appVersionCode: Long? = null,
    val screens: List<UiScreenModule> = emptyList(),
    val elements: List<UiElementSpec> = emptyList(),
    val status: ModuleStatus = ModuleStatus.ACTIVE,
    val updatedAt: Long = System.currentTimeMillis()
)

data class UiScreenModule(
    val id: String,
    val name: String,
    val signatures: List<UiSignature>,
    val recoverActions: List<RecoverAction> = emptyList()
)

data class UiSignature(
    val packageName: String,
    val activityName: String? = null,
    val requiredTextsAny: List<String> = emptyList(),
    val requiredTextsAll: List<String> = emptyList(),
    val requiredResourceIdsAny: List<String> = emptyList(),
    val requiredRolesAny: List<UiRole> = emptyList(),
    val negativeTexts: List<String> = emptyList(),
    val minScore: Float = 0.75f
)

data class UiElementSpec(
    val id: String,
    val name: String,
    val role: UiRole,
    val matchers: List<ElementMatcher>,
    val coordinatePack: CoordinatePack? = null,
    val requiredScreenId: String? = null,
    val reliability: ReliabilityLevel = ReliabilityLevel.UNKNOWN,
    val samples: Int = 0,
    val successRate: Float = 0f,
    val status: ElementStatus = ElementStatus.ACTIVE
)

data class ElementMatcher(
    val text: String? = null,
    val textContains: String? = null,
    val contentDesc: String? = null,
    val contentDescContains: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    val scrollable: Boolean? = null,
    val enabled: Boolean? = null,
    val focused: Boolean? = null
)

data class CoordinatePack(
    val baseWidth: Int,
    val baseHeight: Int,
    val densityDpi: Int? = null,
    val fontScale: Float? = null,
    val navigationMode: String? = null,
    val keyboardVisible: Boolean? = null,
    val normalizedBounds: NormalizedRect,
    val fallbackTap: NormalizedPoint,
    val anchorElementId: String? = null,
    val confidence: Float,
    val source: CoordinateSource,
    val successRate: Float = 0f,
    val lastVerifiedAt: Long = 0L,
    val status: CoordinateStatus = CoordinateStatus.ACTIVE
)

data class NormalizedPoint(
    val x: Float,
    val y: Float
)

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

sealed class RecoverAction {
    data object Back : RecoverAction()
    data class ClickElement(val elementId: String) : RecoverAction()
    data class WaitForText(val text: String, val timeoutMs: Long = 1000L) : RecoverAction()
}

enum class ModuleStatus {
    ACTIVE,
    DEGRADED,
    EXPIRED
}

enum class ElementStatus {
    ACTIVE,
    DEGRADED,
    NEEDS_RECALIBRATION,
    EXPIRED
}

enum class CoordinateStatus {
    ACTIVE,
    NEEDS_RECALIBRATION,
    EXPIRED
}

enum class ReliabilityLevel {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN
}

enum class CoordinateSource {
    ACCESSIBILITY_NODE,
    USER_RECORDING,
    MANUAL_CALIBRATION,
    AI_ASSISTED,
    BUILTIN
}

enum class UiRole {
    BUTTON,
    INPUT,
    SEARCH_ENTRY,
    SEARCH_INPUT,
    SEARCH_RESULT,
    LIST,
    CHAT_INPUT,
    SEND_BUTTON,
    TAB,
    DIALOG_BUTTON,
    BACK_BUTTON,
    CLOSE_BUTTON,
    UNKNOWN
}
