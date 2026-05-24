package com.roubao.autopilot.macro.record

import com.roubao.autopilot.macro.model.MacroDomain
import com.roubao.autopilot.macro.model.MacroTemplate
import com.roubao.autopilot.macro.model.VerifyRule

data class RecordedTrace(
    val id: String,
    val instruction: String,
    val appPackage: String?,
    val appName: String?,
    val createdAt: Long,
    val records: List<TraceRecord>,
    val finalVerify: VerifySnapshot?
)

data class TraceRecord(
    val index: Int,
    val before: UiSnapshot,
    val action: RawAction,
    val targetNode: UiNodeSnapshot?,
    val after: UiSnapshot,
    val userVisibleNote: String? = null
)

data class UiSnapshot(
    val packageName: String?,
    val activityName: String?,
    val texts: List<String>,
    val width: Int = 0,
    val height: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

data class UiNodeSnapshot(
    val text: String? = null,
    val contentDesc: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val clickable: Boolean = false,
    val editable: Boolean = false
)

data class VerifySnapshot(
    val rule: VerifyRule,
    val success: Boolean,
    val reason: String? = null
)

sealed class RawAction {
    data class Click(val x: Int, val y: Int) : RawAction()
    data class TypeText(val text: String) : RawAction()
    data class Swipe(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int) : RawAction()
    data class OpenApp(val packageName: String) : RawAction()
    object Back : RawAction()
    object Home : RawAction()
    data class Wait(val ms: Long) : RawAction()
}

data class MacroTemplateDraft(
    val template: MacroTemplate,
    val confidence: Float,
    val notes: List<String> = emptyList()
)

data class MacroCategory(
    val appPackage: String,
    val appName: String,
    val domain: MacroDomain,
    val skillId: String,
    val displayName: String
)
