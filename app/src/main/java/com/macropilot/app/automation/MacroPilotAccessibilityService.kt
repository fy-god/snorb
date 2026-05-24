package com.macropilot.app.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.macropilot.app.model.NodeSample
import com.macropilot.app.model.UiEventSample
import com.macropilot.app.model.UiStateSample
import com.macropilot.app.runtime.CapabilityProfiler
import com.macropilot.app.store.UiStateSnapshotStore
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MacroPilotAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "MacroPilotA11y"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val MAX_SNAPSHOT_NODES = 1200
        private const val MAX_TREE_DEPTH = 80

        @Volatile
        var instance: MacroPilotAccessibilityService? = null
            private set
    }

    private val eventBuffer = ArrayDeque<UiEventSample>()
    @Volatile
    private var lastPackageName: String? = null
    @Volatile
    private var lastWindowClassName: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: LinearLayout? = null
    private var overlayTitle: TextView? = null
    private var overlayBody: TextView? = null
    private var autoSnapshotRunnable: Runnable? = null
    private var lastAutoSnapshotSignature: String = ""
    private var lastAutoSnapshotAt: Long = 0L
    @Volatile
    private var lastScreenshotWidth: Int = 0
    @Volatile
    private var lastScreenshotHeight: Int = 0

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        hideStatusOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        runCatching {
            lastPackageName = event.packageName?.toString()
            lastWindowClassName = event.className?.toString()
            synchronized(eventBuffer) {
                eventBuffer.addLast(
                    UiEventSample(
                        eventType = eventTypeName(event.eventType),
                        packageName = event.packageName?.toString(),
                        className = event.className?.toString(),
                        text = sanitizeText(event.text?.joinToString(" ")),
                        timestamp = System.currentTimeMillis()
                    )
                )
                while (eventBuffer.size > 80) eventBuffer.removeFirst()
            }
        }.onFailure { error ->
            Log.w(TAG, "Ignoring accessibility event read failure", error)
        }
        if (shouldAutoSnapshot(event.eventType)) {
            scheduleAutoSnapshot(eventTypeName(event.eventType))
        }
    }

    fun consumeRecentEvents(): List<UiEventSample> {
        return synchronized(eventBuffer) {
            val copy = eventBuffer.toList()
            eventBuffer.clear()
            copy
        }
    }

    fun peekRecentEvents(limit: Int = 30): List<UiEventSample> {
        return synchronized(eventBuffer) {
            eventBuffer.toList().takeLast(limit.coerceIn(1, 80))
        }
    }

    fun takeScreenshotPngBlocking(file: File, timeoutMs: Long = 1_500L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val latch = CountDownLatch(1)
        var success = false
        val executor = java.util.concurrent.Executor { runnable -> runnable.run() }
        runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        runCatching {
                            file.parentFile?.mkdirs()
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )
                            if (bitmap != null) {
                                lastScreenshotWidth = bitmap.width
                                lastScreenshotHeight = bitmap.height
                                FileOutputStream(file).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)
                                }
                                success = file.isFile && file.length() > 0L
                            }
                            screenshot.hardwareBuffer.close()
                        }.onFailure { error ->
                            Log.w(TAG, "Screenshot save failed", error)
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Screenshot request failed: $errorCode")
                        latch.countDown()
                    }
                }
            )
        }.onFailure { error ->
            Log.w(TAG, "Screenshot request crashed", error)
            latch.countDown()
        }
        latch.await(timeoutMs.coerceAtLeast(200L), TimeUnit.MILLISECONDS)
        return success
    }

    fun lastScreenshotSize(): Pair<Int, Int>? {
        val width = lastScreenshotWidth
        val height = lastScreenshotHeight
        return if (width > 0 && height > 0) width to height else null
    }

    fun activePackageName(): String? {
        val rootPackage = runCatching { bestContentRoot()?.packageName?.toString() }.getOrNull()
        val eventPackage = lastPackageName
        return if (rootPackage == SYSTEM_UI_PACKAGE &&
            !eventPackage.isNullOrBlank() &&
            eventPackage != SYSTEM_UI_PACKAGE
        ) {
            eventPackage
        } else {
            rootPackage ?: eventPackage
        }
    }

    fun activeWindowClassName(): String? {
        return lastWindowClassName
    }

    fun snapshotNodes(): List<NodeSample>? {
        val root = bestContentRoot() ?: return null
        val nodes = mutableListOf<NodeSample>()
        return runCatching {
            walk(root, depth = 0, parentRoleHint = null, out = nodes)
            nodes
        }.onFailure { error ->
            Log.w(TAG, "Snapshot node walk failed", error)
        }.getOrNull()
    }

    fun showStatusOverlay(title: String, status: String, message: String) {
        mainHandler.post {
            ensureOverlay()
            overlayTitle?.text = "${statusText(status)} · ${title.take(18)}"
            overlayBody?.text = message.take(90)
            overlayView?.visibility = View.VISIBLE
        }
    }

    fun hideStatusOverlay(delayMs: Long = 0L) {
        mainHandler.postDelayed({
            val view = overlayView ?: return@postDelayed
            runCatching {
                getSystemService(WindowManager::class.java)?.removeView(view)
            }
            overlayView = null
            overlayTitle = null
            overlayBody = null
        }, delayMs.coerceAtLeast(0L))
    }

    fun findFirstNode(matchers: List<com.macropilot.app.model.ElementMatcher>): AccessibilityNodeInfo? {
        val root = bestContentRoot() ?: return null
        return runCatching { findFirstNode(root, matchers) }
            .onFailure { error -> Log.w(TAG, "Find node failed", error) }
            .getOrNull()
    }

    fun findNodes(matchers: List<com.macropilot.app.model.ElementMatcher>): List<AccessibilityNodeInfo> {
        val root = bestContentRoot() ?: return emptyList()
        return runCatching {
            val out = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(root, matchers, out)
            out
        }.onFailure { error -> Log.w(TAG, "Find nodes failed", error) }
            .getOrDefault(emptyList())
    }

    private fun ensureOverlay() {
        if (overlayView != null) return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(232, 13, 18, 30))
                setStroke(dp(1), Color.argb(190, 165, 185, 255))
            }
            alpha = 0.96f
        }
        overlayTitle = TextView(this).apply {
            setTextColor(Color.rgb(238, 242, 255))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        overlayBody = TextView(this).apply {
            setTextColor(Color.rgb(196, 203, 224))
            textSize = 12f
            maxLines = 2
        }
        container.addView(overlayTitle)
        container.addView(overlayBody)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(72)
            width = resources.displayMetrics.widthPixels - dp(28)
        }
        runCatching {
            getSystemService(WindowManager::class.java)?.addView(container, params)
            overlayView = container
        }
    }

    private fun statusText(status: String): String {
        return when {
            status == "RUNNING" -> "进行中"
            status == "SUCCESS" -> "成功"
            status == "SUCCESS_WITH_FALLBACK" -> "规则兜底成功"
            status.startsWith("FAILED") -> "失败"
            status == "INFO" -> "提示"
            else -> status.ifBlank { "状态" }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun shouldAutoSnapshot(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
    }

    private fun scheduleAutoSnapshot(reason: String) {
        autoSnapshotRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { saveAutoUiSnapshot(reason) }
        autoSnapshotRunnable = runnable
        mainHandler.postDelayed(runnable, 420L)
    }

    private fun saveAutoUiSnapshot(reason: String) {
        runCatching {
            val nodes = snapshotNodes() ?: return
            val appPackage = nodes
                .asSequence()
                .mapNotNull { it.packageName }
                .filter { it != SYSTEM_UI_PACKAGE }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?: activePackageName()
            if (appPackage.isNullOrBlank() || appPackage == SYSTEM_UI_PACKAGE || appPackage == packageName) return
            val metrics = ScreenMetrics.touchSize(this)
            val state = UiStateSample(
                appPackage = appPackage,
                windowClassName = activeWindowClassName(),
                screenWidth = metrics.width,
                screenHeight = metrics.height,
                timestamp = System.currentTimeMillis(),
                nodes = nodes,
                eventsSinceLastState = peekRecentEvents()
            )
            val signature = autoSnapshotSignature(state)
            val now = System.currentTimeMillis()
            val unreadableSurface = state.nodes.size <= 3 ||
                state.nodes.none { node ->
                    !node.text.isNullOrBlank() ||
                        !node.contentDesc.isNullOrBlank() ||
                        !node.resourceId.isNullOrBlank()
                }
            val minSnapshotIntervalMs = if (unreadableSurface) 900L else 2_500L
            if (signature == lastAutoSnapshotSignature && now - lastAutoSnapshotAt < minSnapshotIntervalMs) return
            lastAutoSnapshotSignature = signature
            lastAutoSnapshotAt = now
            val profile = CapabilityProfiler().profile(state, serviceConnected = true)
            UiStateSnapshotStore(this).save(
                state = state,
                profile = profile,
                source = "accessibility_auto",
                note = reason,
                forceScreenshot = unreadableSurface
            )
        }.onFailure { error ->
            Log.w(TAG, "Auto UI snapshot failed", error)
        }
    }

    private fun autoSnapshotSignature(state: UiStateSample): String {
        val labels = state.nodes
            .asSequence()
            .mapNotNull { node ->
                listOfNotNull(node.text, node.contentDesc, node.resourceId)
                    .joinToString(" ")
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .take(16)
            .joinToString("|")
        return "${state.appPackage}|${state.windowClassName}|$labels"
    }

    private fun bestContentRoot(): AccessibilityNodeInfo? {
        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null &&
            runCatching { active.packageName?.toString() }.getOrNull() != SYSTEM_UI_PACKAGE
        ) {
            return active
        }
        val windowRoots = runCatching { windows }.getOrDefault(emptyList())
        val appWindowRoot = windowRoots
            .asSequence()
            .mapNotNull { window -> runCatching { window.root }.getOrNull() }
            .firstOrNull { root ->
                runCatching { root.packageName?.toString() }.getOrNull() != SYSTEM_UI_PACKAGE
            }
        return appWindowRoot ?: active
    }

    private fun findFirstNode(
        node: AccessibilityNodeInfo,
        matchers: List<com.macropilot.app.model.ElementMatcher>
    ): AccessibilityNodeInfo? {
        if (runCatching { matchers.any { matcher -> matches(node, matcher) } }.getOrDefault(false)) return node
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val found = findFirstNode(child, matchers)
            if (found != null) return found
        }
        return null
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        matchers: List<com.macropilot.app.model.ElementMatcher>,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (runCatching { matchers.any { matcher -> matches(node, matcher) } }.getOrDefault(false)) {
            out += node
        }
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectNodes(child, matchers, out)
        }
    }

    private fun matches(
        node: AccessibilityNodeInfo,
        matcher: com.macropilot.app.model.ElementMatcher
    ): Boolean {
        fun textEquals(expected: String?, actual: CharSequence?): Boolean {
            return expected == null || actual?.toString() == expected
        }

        fun textContains(expected: String?, actual: CharSequence?): Boolean {
            return expected == null || actual?.toString()?.contains(expected) == true
        }

        fun stringEquals(expected: String?, actual: String?): Boolean {
            return expected == null || actual == expected || actual?.endsWith("/$expected") == true
        }

        fun boolEquals(expected: Boolean?, actual: Boolean): Boolean {
            return expected == null || expected == actual
        }

        return runCatching {
            textEquals(matcher.text, node.text) &&
                textContains(matcher.textContains, node.text) &&
                textEquals(matcher.contentDesc, node.contentDescription) &&
                textContains(matcher.contentDescContains, node.contentDescription) &&
                stringEquals(matcher.resourceId, node.viewIdResourceName) &&
                stringEquals(matcher.className, node.className?.toString()) &&
                boolEquals(matcher.clickable, node.isClickable) &&
                boolEquals(matcher.editable, node.isEditable) &&
                boolEquals(matcher.scrollable, node.isScrollable) &&
                boolEquals(matcher.enabled, node.isEnabled) &&
                boolEquals(matcher.focused, node.isFocused)
        }.getOrDefault(false)
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        parentRoleHint: String?,
        out: MutableList<NodeSample>
    ) {
        if (depth > MAX_TREE_DEPTH || out.size >= MAX_SNAPSHOT_NODES) return
        val rect = Rect()
        val roleHint = runCatching { node.className?.toString() }.getOrNull()
        runCatching {
            node.getBoundsInScreen(rect)
            val nodeId = "${depth}_${out.size}_${rect.left}_${rect.top}_${rect.right}_${rect.bottom}"
            out.add(
                NodeSample(
                    nodeId = nodeId,
                    packageName = node.packageName?.toString(),
                    text = sanitizeText(node.text),
                    contentDesc = sanitizeText(node.contentDescription),
                    resourceId = node.viewIdResourceName,
                    className = node.className?.toString(),
                    bounds = rect,
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checkable = node.isCheckable,
                    focused = node.isFocused,
                    enabled = node.isEnabled,
                    selected = node.isSelected,
                    depth = depth,
                    parentRoleHint = parentRoleHint
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Skipping unreadable node", error)
        }

        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until childCount) {
            if (out.size >= MAX_SNAPSHOT_NODES) return
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            walk(child, depth + 1, roleHint, out)
        }
    }

    private fun sanitizeText(value: CharSequence?, limit: Int = 240): String? {
        val raw = value?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val compact = raw.replace('\r', ' ').replace('\n', ' ')
        return if (compact.length <= limit) compact else compact.take(limit) + "..."
    }

    private fun eventTypeName(type: Int): String {
        return when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "TYPE_NOTIFICATION_STATE_CHANGED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "TYPE_VIEW_FOCUSED"
            else -> "TYPE_$type"
        }
    }
}
