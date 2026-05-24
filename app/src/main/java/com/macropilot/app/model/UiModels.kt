package com.macropilot.app.model

import android.graphics.Rect

data class UiStateSample(
    val appPackage: String?,
    val windowClassName: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val timestamp: Long,
    val nodes: List<NodeSample>,
    val eventsSinceLastState: List<UiEventSample>
)

data class NodeSample(
    val nodeId: String,
    val packageName: String?,
    val text: String?,
    val contentDesc: String?,
    val resourceId: String?,
    val className: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val focused: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val depth: Int,
    val parentRoleHint: String?
) {
    fun compactLabel(): String {
        val name = (text ?: contentDesc ?: resourceId ?: className ?: "node").compact(96)
        val flags = buildList {
            if (clickable) add("click")
            if (editable) add("edit")
            if (scrollable) add("scroll")
            if (checkable) add("check")
            if (focused) add("focused")
        }.joinToString(",")
        return "$nodeId $name $bounds $flags"
    }

    private fun String.compact(max: Int): String {
        val oneLine = replace('\n', ' ').replace('\r', ' ')
        return if (oneLine.length <= max) oneLine else oneLine.take(max) + "..."
    }
}

data class UiEventSample(
    val eventType: String,
    val packageName: String?,
    val className: String?,
    val text: String?,
    val timestamp: Long
)

data class CapabilityProfile(
    val nodeReadability: NodeReadability,
    val inputCapability: InputCapability,
    val verificationCapability: VerificationCapability,
    val coordinateFallback: CoordinateFallback,
    val serviceState: ServiceState,
    val reason: String
)

enum class NodeReadability {
    A,
    B,
    C,
    D
}

enum class InputCapability {
    SET_TEXT,
    PASTE,
    CUSTOM_IME,
    SHIZUKU_ADB,
    NONE
}

enum class VerificationCapability {
    HIGH,
    MEDIUM,
    LOW,
    NONE
}

enum class CoordinateFallback {
    AVAILABLE,
    MISSING
}

enum class ServiceState {
    CONNECTED,
    DISCONNECTED,
    ROOT_UNAVAILABLE
}

enum class TrialResult {
    SUCCESS,
    SUCCESS_MEDIUM_CONFIDENCE,
    SUCCESS_LOW_CONFIDENCE,
    FAILED_NO_NODE,
    FAILED_NO_INPUT_CHANNEL,
    FAILED_NO_VERIFICATION,
    FAILED_TIMEOUT,
    FAILED_DANGEROUS_ACTION_BLOCKED,
    FAILED_APP_STATE_CHANGED,
    FAILED_SERVICE_UNAVAILABLE,
    UNKNOWN
}

data class MacroTrainingSample(
    val id: String,
    val beforeState: UiStateSample,
    val capabilityProfile: CapabilityProfile,
    val note: String,
    val privacyLevel: String,
    val containsPersonalData: Boolean,
    val redactionStatus: String
)
