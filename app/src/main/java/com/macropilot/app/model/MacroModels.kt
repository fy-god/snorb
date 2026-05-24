package com.macropilot.app.model

data class MacroTemplate(
    val id: String,
    val name: String,
    val packageName: String,
    val intentId: String,
    val params: List<MacroParam> = emptyList(),
    val atoms: List<MacroAtom> = emptyList(),
    val finalVerify: VerifyRule,
    val version: Int = 1,
    val source: SkillSource = SkillSource.APPROVED,
    val sideEffectLevel: SideEffectLevel = SideEffectLevel.LOW,
    val idempotent: Boolean = true
)

data class MacroParam(
    val name: String,
    val required: Boolean = true,
    val sensitive: Boolean = false
)

data class MacroPlan(
    val templateId: String,
    val packageName: String,
    val params: Map<String, String>,
    val atoms: List<MacroAtom>,
    val finalVerify: VerifyRule,
    val sideEffectLevel: SideEffectLevel
)

sealed class MacroAtom {
    data class OpenApp(val packageName: String, val timeoutMs: Long = 3000L) : MacroAtom()
    data class EnsureScreen(val screenId: String, val timeoutMs: Long = 3000L) : MacroAtom()
    data class ClickElement(val elementId: String, val timeoutMs: Long = 1500L) : MacroAtom()
    data class FocusTextInput(
        val targetElementId: String,
        val toggleElementIds: List<String> = emptyList(),
        val timeoutMs: Long = 3500L
    ) : MacroAtom()
    data class TypeText(val textParam: String, val targetElementId: String? = null, val timeoutMs: Long = 2000L) : MacroAtom()
    data class SearchInApp(val queryParam: String, val searchEntryElementId: String, val timeoutMs: Long = 5000L) : MacroAtom()
    data class SelectItem(val itemTextParam: String, val listElementId: String? = null, val timeoutMs: Long = 3000L) : MacroAtom()
    data class ScrollUntilVisible(
        val targetTextParam: String,
        val listElementId: String? = null,
        val maxSwipes: Int = 5,
        val timeoutMs: Long = 6000L
    ) : MacroAtom()
    data class WaitFor(val rule: VerifyRule, val timeoutMs: Long = 3000L) : MacroAtom()
    data class Verify(val rule: VerifyRule, val timeoutMs: Long = 2000L) : MacroAtom()
}

data class VerifyRule(
    val type: VerifyRuleType,
    val textParam: String? = null,
    val literalText: String? = null,
    val elementId: String? = null,
    val packageName: String? = null,
    val screenId: String? = null,
    val minVerification: VerificationCapability = VerificationCapability.MEDIUM
)

data class MacroResult(
    val status: MacroStatus,
    val message: String,
    val confidence: VerificationCapability = VerificationCapability.NONE
)

data class RuntimeTaskRequirements(
    val needsTextInput: Boolean,
    val requiresVerification: Boolean = true,
    val allowLowConfidence: Boolean = false,
    val sideEffectLevel: SideEffectLevel = SideEffectLevel.LOW,
    val finalVerify: VerifyRule? = null
)

data class RuntimeGateDecision(
    val allowed: Boolean,
    val blockedStatus: MacroStatus? = null,
    val confidenceCeiling: MacroStatus = MacroStatus.SUCCESS,
    val reason: String
)

data class MacroRuntimePolicy(
    val totalTimeboxMs: Long = 30_000L,
    val maxCriticalFailures: Int = 2,
    val runtimeAiCallsAllowed: Int = 0
)

enum class SkillSource {
    APPROVED,
    CANDIDATE,
    PENDING
}

enum class SideEffectLevel {
    LOW,
    MEDIUM,
    HIGH
}

enum class VerifyRuleType {
    ALWAYS,
    PACKAGE_IS,
    TEXT_VISIBLE,
    ELEMENT_PRESENT,
    INPUT_CONTAINS,
    INPUT_CLEARED,
    SCREEN_MATCH,
    MESSAGE_SENT
}

enum class MacroStatus {
    SUCCESS,
    SUCCESS_MEDIUM_CONFIDENCE,
    SUCCESS_LOW_CONFIDENCE,
    FAILED,
    FAILED_NEEDS_CALIBRATION,
    FAILED_INPUT_CHANNEL_MISSING,
    FAILED_CAPABILITY_D,
    FAILED_DANGEROUS_ACTION_BLOCKED,
    FAILED_SERVICE_UNAVAILABLE,
    FAILED_TIMEOUT,
    FAILED_UNVERIFIABLE,
    FAILED_PRECONDITION
}
