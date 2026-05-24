package com.roubao.autopilot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.roubao.autopilot.skills.SkillTarget
import com.roubao.autopilot.skills.UiNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AutomationAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set

        private const val TAG = "[AutomationA11y]"
    }

    override fun onServiceConnected() {
        instance = this
        println("$TAG connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        println("$TAG destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun clickByTarget(target: SkillTarget): Boolean {
        val beforeHash = activeWindowHash()
        val candidates = findNodes(target)
        println("$TAG click target text=${target.text} class=${target.className} desc=${target.contentDesc} candidates=${candidates.size}")
        val node = chooseNode(candidates, target)
        if (node == null) {
            println("$TAG click no candidate")
            return false
        }

        val bounds = node.boundsString()
        val clickable = node.isClickable
        val parent = clickableParent(node)
        println("$TAG click selected=$bounds clickable=$clickable parentClickable=${parent != null}")

        val clickNode = if (clickable) node else parent
        val actionOk = clickNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        Thread.sleep(180)
        val actionChanged = beforeHash != activeWindowHash()
        println("$TAG click performAction=$actionOk hashChanged=$actionChanged")
        if (actionOk && actionChanged) return true

        val rect = Rect()
        (clickNode ?: node).getBoundsInScreen(rect)
        val gestureOk = gestureTap(rect.centerX(), rect.centerY())
        Thread.sleep(180)
        val gestureChanged = beforeHash != activeWindowHash()
        println("$TAG click fallbackGesture=$gestureOk hashChanged=$gestureChanged")
        return gestureOk && (gestureChanged || actionOk)
    }

    fun focusByTarget(target: SkillTarget): Boolean {
        val node = chooseInputNode(target) ?: chooseNode(findNodes(target), target) ?: return false
        val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        println("$TAG focus target text=${target.text} class=${target.className} focused=$focused bounds=${node.boundsString()}")
        return focused
    }

    fun setTextByTarget(target: SkillTarget, text: String): Boolean {
        val beforeHash = activeWindowHash()
        val node = chooseInputNode(target) ?: findBestEditText() ?: return false
        val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Thread.sleep(180)
        val containsText = activeWindowText().contains(text)
        val changed = beforeHash != activeWindowHash()
        println("$TAG setText focused=$focused set=$set containsText=$containsText hashChanged=$changed bounds=${node.boundsString()}")
        return set && (containsText || changed)
    }

    fun gestureTap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        if (!dispatched) return false
        latch.await(500, TimeUnit.MILLISECONDS)
        println("$TAG dispatchGesture x=$x y=$y completed=${completed.get()}")
        return completed.get()
    }

    fun activeWindowHash(): String {
        val root = rootInActiveWindow ?: return ""
        val values = mutableListOf<String>()
        root.walk { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            values.add("${node.className}|${node.text}|${node.contentDescription}|${node.isClickable}|$rect")
        }
        return values.joinToString("#").hashCode().toString()
    }

    fun snapshotNodes(): List<UiNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<UiNode>()
        root.walk { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            result.add(
                UiNode(
                    text = node.text?.toString(),
                    contentDesc = node.contentDescription?.toString(),
                    resourceId = node.viewIdResourceName,
                    className = node.className?.toString(),
                    bounds = rect,
                    clickable = node.isClickable,
                    editable = node.isEditable || node.isFocusable || node.isFocused,
                    scrollable = node.isScrollable,
                    enabled = node.isEnabled,
                    visible = node.isVisibleToUser,
                    checked = node.isChecked
                )
            )
        }
        return result
    }

    private fun activeWindowText(): String {
        val root = rootInActiveWindow ?: return ""
        val values = mutableListOf<String>()
        root.walk { node ->
            node.text?.let { values.add(it.toString()) }
            node.contentDescription?.let { values.add(it.toString()) }
        }
        return values.joinToString("\n")
    }

    private fun findNodes(target: SkillTarget): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        root.walk { node -> if (matches(node, target)) result.add(node) }
        return result
    }

    private fun findBestEditText(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val result = mutableListOf<AccessibilityNodeInfo>()
        root.walk { node ->
            if (node.className?.toString() == "android.widget.EditText") result.add(node)
        }
        return chooseBestInputNode(result)
    }

    private fun chooseInputNode(target: SkillTarget): AccessibilityNodeInfo? {
        val matches = findNodes(target)
        if (target.className == "android.widget.EditText" || matches.any { isInputCandidate(it) }) {
            chooseBestInputNode(matches)?.let { return it }
        }
        return null
    }

    private fun chooseBestInputNode(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        return nodes
            .filter { isInputCandidate(it) }
            .minWithOrNull(compareBy<AccessibilityNodeInfo> { inputScore(it) }.thenBy { nodeTop(it) })
    }

    private fun isInputCandidate(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        if (rect.centerY() > safeInputBottom()) return false
        return node.className?.toString() == "android.widget.EditText" || node.isFocusable || node.isFocused
    }

    private fun inputScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString()
        return when {
            className == "android.widget.EditText" -> 0
            node.isFocused -> 1
            node.isFocusable -> 2
            else -> 3
        }
    }

    private fun safeInputBottom(): Int {
        val root = rootInActiveWindow ?: return Int.MAX_VALUE
        val rect = Rect()
        root.getBoundsInScreen(rect)
        val height = rect.height().takeIf { it > 0 } ?: return Int.MAX_VALUE
        return rect.top + (height * 0.86f).toInt()
    }

    private fun chooseNode(nodes: List<AccessibilityNodeInfo>, target: SkillTarget): AccessibilityNodeInfo? {
        if (nodes.isEmpty()) return null
        target.bounds?.let { wanted ->
            nodes.minByOrNull { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                kotlin.math.abs(rect.centerX() - ((wanted.left + wanted.right) / 2)) +
                    kotlin.math.abs(rect.centerY() - ((wanted.top + wanted.bottom) / 2))
            }?.let { return it }
        }
        return nodes.minWithOrNull(compareByDescending<AccessibilityNodeInfo> { it.isClickable }.thenBy { nodeTop(it) })
    }

    private fun matches(node: AccessibilityNodeInfo, target: SkillTarget): Boolean {
        var hasSelector = false
        target.text?.takeIf { it.isNotBlank() }?.let {
            hasSelector = true
            val text = node.text?.toString().orEmpty()
            if (!text.contains(it)) return false
        }
        target.contentDesc?.takeIf { it.isNotBlank() }?.let {
            hasSelector = true
            val desc = node.contentDescription?.toString().orEmpty()
            if (!desc.contains(it)) return false
        }
        target.className?.takeIf { it.isNotBlank() }?.let {
            hasSelector = true
            if (node.className?.toString() != it) return false
        }
        target.bounds?.let { wanted ->
            hasSelector = true
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!Rect.intersects(rect, Rect(wanted.left, wanted.top, wanted.right, wanted.bottom))) return false
        }
        return hasSelector
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }
        return null
    }

    private fun nodeTop(node: AccessibilityNodeInfo): Int {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.top
    }

    private fun AccessibilityNodeInfo.boundsString(): String {
        val rect = Rect()
        getBoundsInScreen(rect)
        return rect.toString()
    }

    private fun AccessibilityNodeInfo.walk(block: (AccessibilityNodeInfo) -> Unit) {
        block(this)
        for (i in 0 until childCount) {
            getChild(i)?.walk(block)
        }
    }
}
