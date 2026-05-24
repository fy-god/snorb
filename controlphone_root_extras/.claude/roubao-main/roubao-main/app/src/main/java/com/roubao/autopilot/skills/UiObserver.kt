package com.roubao.autopilot.skills

import android.graphics.Rect
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.service.AutomationAccessibilityService

data class UiNode(
    val text: String? = null,
    val contentDesc: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: Rect = Rect(),
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val checked: Boolean = false
)

data class UiState(
    val packageName: String? = null,
    val activityName: String? = null,
    val screenText: List<String> = emptyList(),
    val nodes: List<UiNode> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    /** 当前可见文字集合（用于快速匹配） */
    val visibleTextSet: Set<String> by lazy { screenText.toSet() }

    val editableNodes: List<UiNode> get() = nodes.filter { it.editable && it.visible }
    val clickableNodes: List<UiNode> get() = nodes.filter { it.clickable && it.visible }
    val scrollableNodes: List<UiNode> get() = nodes.filter { it.scrollable && it.visible }

    fun hasText(text: String): Boolean = screenText.any { it.contains(text) }
    fun hasExactText(text: String): Boolean = text in visibleTextSet
}

/**
 * UiObserver — 统一收集当前界面状态：前台包名、无障碍节点树、可见文字。
 */
class UiObserver(
    private val controller: DeviceController
) {
    companion object {
        private const val TAG = "[UiObserver]"
    }

    fun capture(): UiState {
        val focus = shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        val pkg = extractPackage(focus)
        val act = extractActivity(focus)
        val nodes = dumpNodes()
        val texts = nodes.mapNotNull { node ->
            node.text ?: node.contentDesc
        }.filter { it.isNotBlank() }

        return UiState(
            packageName = pkg,
            activityName = act,
            screenText = texts,
            nodes = nodes,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun extractPackage(focus: String): String? {
        val match = Regex("(\\w+(?:\\.\\w+)+)/").find(focus) ?: Regex("\\s(\\w+(?:\\.\\w+)+)").find(focus)
        return match?.groupValues?.get(1)
    }

    private fun extractActivity(focus: String): String? {
        val match = Regex("/\\.?(\\w+(?:\\.\\w+)*)\\b").find(focus)
        return match?.groupValues?.get(1)
    }

    private fun dumpNodes(): List<UiNode> {
        return AutomationAccessibilityService.instance?.snapshotNodes().orEmpty()
    }

    private fun parseNode(raw: String): UiNode {
        return UiNode(
            text = attr(raw, "text"),
            contentDesc = attr(raw, "content-desc"),
            resourceId = attr(raw, "resource-id"),
            className = attr(raw, "class"),
            bounds = parseBounds(attr(raw, "bounds")),
            clickable = attr(raw, "clickable") == "true",
            editable = attr(raw, "editable") == "true" || attr(raw, "focusable") == "true",
            scrollable = attr(raw, "scrollable") == "true",
            enabled = attr(raw, "enabled") != "false",
            visible = attr(raw, "visible-to-user") != "false",
            checked = attr(raw, "checked") == "true"
        )
    }

    private fun parseBounds(raw: String?): Rect {
        if (raw == null) return Rect()
        val m = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").find(raw) ?: return Rect()
        return Rect(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt(),
            m.groupValues[3].toInt(),
            m.groupValues[4].toInt()
        )
    }

    private fun attr(raw: String, name: String): String? {
        val m = Regex("$name=\"([^\"]*)\"").find(raw) ?: return null
        val v = m.groupValues[1]
        return if (v.isBlank()) null else v
    }

    private fun shell(cmd: String): String {
        return try {
            controller.shell(cmd)
        } catch (_: Exception) { "" }
    }
}
