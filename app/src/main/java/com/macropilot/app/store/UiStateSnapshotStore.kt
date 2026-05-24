package com.macropilot.app.store

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.model.CapabilityProfile
import com.macropilot.app.model.NodeSample
import com.macropilot.app.model.UiEventSample
import com.macropilot.app.model.UiStateSample
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UiStateSnapshotStore(private val context: Context) {
    private val rootDir: File
        get() = File(context.filesDir, "macro_v2/factory/ui_snapshots")

    private val screenshotRootDir: File
        get() = File(context.filesDir, "macro_v2/factory/ui_screenshots")

    private val maxSnapshotsPerApp = 12
    private val maxGlobalSnapshots = 420
    private val maxGlobalScreenshots = 160
    private val maxFullNodesPerSnapshot = 45
    private val maxEventsPerSnapshot = 20

    fun save(
        state: UiStateSample,
        profile: CapabilityProfile,
        source: String,
        note: String = "",
        relatedSkillId: String? = null,
        relatedActionType: String? = null,
        forceScreenshot: Boolean = false
    ): File {
        val packageName = state.appPackage.orEmpty().ifBlank { "__unknown__" }
        val appDir = File(rootDir, safeSegment(packageName))
        appDir.mkdirs()
        val screenKey = screenKey(state)
        val stamp = timestamp()
        val screenshotFile = maybeSaveScreenshot(packageName, stamp, screenKey, state, forceScreenshot)
        val screenshotSha256 = screenshotFile?.let { sha256(it) }
        val visualHash = screenshotFile?.let { visualHash(it) }
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "ui_state_snapshot")
            .put("id", "ui_snapshot_${System.currentTimeMillis()}")
            .put("source", source)
            .put("note", note)
            .put("screenshotPath", screenshotFile?.absolutePath ?: JSONObject.NULL)
            .put("screenshotSha256", screenshotSha256 ?: JSONObject.NULL)
            .put("visualHash", visualHash ?: JSONObject.NULL)
            .put("screenshotSizeBytes", screenshotFile?.length() ?: JSONObject.NULL)
            .put("appPackage", state.appPackage ?: JSONObject.NULL)
            .put("windowClassName", state.windowClassName ?: JSONObject.NULL)
            .put("screenKey", screenKey)
            .put("relatedSkillId", relatedSkillId ?: JSONObject.NULL)
            .put("relatedActionType", relatedActionType ?: JSONObject.NULL)
            .put("capability", JSONObject()
                .put("nodeReadability", profile.nodeReadability.name)
                .put("inputCapability", profile.inputCapability.name)
                .put("verificationCapability", profile.verificationCapability.name)
                .put("coordinateFallback", profile.coordinateFallback.name)
                .put("serviceState", profile.serviceState.name)
                .put("reason", profile.reason))
            .put("state", compactStateJson(state))
            .put("summary", summaryJson(state))
            .put("retentionPolicy", JSONObject()
                .put("fullNodeTreeStored", false)
                .put("storedNodeLimit", maxFullNodesPerSnapshot)
                .put("storedEventLimit", maxEventsPerSnapshot)
                .put("longTermKnowledge", "summary + app_ui_graph + assembly/runtime reports"))
            .put("createdAt", System.currentTimeMillis())
        val file = File(appDir, "${stamp}_${safeSegment(screenKey)}.json")
        file.writeText(json.toString(2))
        pruneAppSnapshots(appDir)
        pruneGlobalSnapshots()
        return file
    }

    fun listRecent(packageName: String? = null, limit: Int = 20): List<File> {
        val root = packageName
            ?.takeIf { it.isNotBlank() }
            ?.let { File(rootDir, safeSegment(it)) }
            ?: rootDir
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .toList()
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.absolutePath })
            .take(limit.coerceIn(1, 100))
    }

    fun recentSummary(packageName: String? = null, limit: Int = 8): JSONArray {
        val out = JSONArray()
        listRecent(packageName, limit).forEach { file ->
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@forEach
            out.put(JSONObject()
                .put("id", json.optString("id"))
                .put("source", json.optString("source"))
                .put("note", json.optString("note"))
                .put("appPackage", json.optString("appPackage"))
                .put("windowClassName", json.optString("windowClassName"))
                .put("screenKey", json.optString("screenKey"))
                .put("screenshotPath", json.optString("screenshotPath"))
                .put("visualHash", json.optString("visualHash"))
                .put("summary", json.optJSONObject("summary") ?: JSONObject())
                .put("path", file.absolutePath))
        }
        return out
    }

    private fun maybeSaveScreenshot(
        packageName: String,
        stamp: String,
        screenKey: String,
        state: UiStateSample,
        forceScreenshot: Boolean
    ): File? {
        val labels = state.nodes.mapNotNull { nodeLabel(it).takeIf { label -> label.isNotBlank() } }
        val shouldCapture = forceScreenshot ||
            labels.isEmpty() ||
            state.nodes.size <= 3 ||
            state.nodes.count { !it.bounds.isEmpty } <= 1
        if (!shouldCapture) return null
        val service = MacroPilotAccessibilityService.instance ?: return null
        val appDir = File(screenshotRootDir, safeSegment(packageName))
        val file = File(appDir, "${stamp}_${safeSegment(screenKey)}.png")
        repeat(3) { attempt ->
            if (service.takeScreenshotPngBlocking(file, timeoutMs = 1_800L)) return file
            if (attempt < 2) {
                try {
                    Thread.sleep(240L + attempt * 180L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    private fun sha256(file: File): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun visualHash(file: File): String? {
        return runCatching {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 8
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching null
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val rows = 12
                val cols = 12
                for (row in 0 until rows) {
                    val yRatio = 0.16 + row * (0.78 / (rows - 1))
                    val y = (bitmap.height * yRatio).toInt().coerceIn(0, bitmap.height - 1)
                    for (col in 0 until cols) {
                        val xRatio = 0.05 + col * (0.90 / (cols - 1))
                        val x = (bitmap.width * xRatio).toInt().coerceIn(0, bitmap.width - 1)
                        val pixel = bitmap.getPixel(x, y)
                        digest.update(((pixel shr 16) and 0xF0).toByte())
                        digest.update(((pixel shr 8) and 0xF0).toByte())
                        digest.update((pixel and 0xF0).toByte())
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    private fun summaryJson(state: UiStateSample): JSONObject {
        val labels = state.nodes.mapNotNull { nodeLabel(it).takeIf { label -> label.isNotBlank() } }
        val bottom = state.nodes
            .filter { node ->
                node.bounds.centerY() > state.screenHeight * 0.68f &&
                    (node.clickable || !node.text.isNullOrBlank() || !node.contentDesc.isNullOrBlank())
            }
            .sortedBy { it.bounds.centerX() }
            .take(12)
            .map { node ->
                JSONObject()
                    .put("label", nodeLabel(node))
                    .put("bounds", rectJson(node.bounds))
                    .put("clickable", node.clickable)
            }
        return JSONObject()
            .put("nodeCount", state.nodes.size)
            .put("clickableCount", state.nodes.count { it.clickable })
            .put("editableCount", state.nodes.count { it.editable })
            .put("scrollableCount", state.nodes.count { it.scrollable })
            .put("labels", JSONArray(labels.take(80)))
            .put("bottomCandidates", JSONArray(bottom))
    }

    private fun compactStateJson(state: UiStateSample): JSONObject {
        val importantNodes = state.nodes
            .sortedWith(
                compareByDescending<NodeSample> { nodeImportance(it) }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.left }
            )
            .take(maxFullNodesPerSnapshot)
        return JSONObject()
            .put("appPackage", state.appPackage ?: JSONObject.NULL)
            .put("windowClassName", state.windowClassName ?: JSONObject.NULL)
            .put("screenWidth", state.screenWidth)
            .put("screenHeight", state.screenHeight)
            .put("timestamp", state.timestamp)
            .put("nodeCount", state.nodes.size)
            .put("nodesStored", importantNodes.size)
            .put("nodesStorage", "compact_important_nodes_only")
            .put("nodes", JSONArray(importantNodes.map { nodeJson(it) }))
            .put("eventsSinceLastState", JSONArray(state.eventsSinceLastState.takeLast(maxEventsPerSnapshot).map { eventJson(it) }))
    }

    private fun nodeImportance(node: NodeSample): Int {
        var score = 0
        if (node.clickable) score += 8
        if (node.editable) score += 12
        if (node.scrollable) score += 4
        if (node.focused) score += 6
        if (nodeLabel(node).isNotBlank()) score += 5
        if (!node.bounds.isEmpty) score += 2
        val label = nodeLabel(node).lowercase(Locale.CHINA)
        listOf("搜索", "搜", "发布", "发帖", "发表", "创作", "评论", "点赞", "发送", "关闭", "跳过", "取消", "确定", "下一步", "完成")
            .forEach { if (label.contains(it)) score += 4 }
        return score
    }

    private fun nodeJson(node: NodeSample): JSONObject {
        return JSONObject()
            .put("nodeId", node.nodeId)
            .put("packageName", node.packageName ?: JSONObject.NULL)
            .put("text", node.text ?: JSONObject.NULL)
            .put("contentDesc", node.contentDesc ?: JSONObject.NULL)
            .put("resourceId", node.resourceId ?: JSONObject.NULL)
            .put("className", node.className ?: JSONObject.NULL)
            .put("bounds", rectJson(node.bounds))
            .put("clickable", node.clickable)
            .put("editable", node.editable)
            .put("scrollable", node.scrollable)
            .put("focused", node.focused)
            .put("enabled", node.enabled)
            .put("selected", node.selected)
            .put("depth", node.depth)
            .put("parentRoleHint", node.parentRoleHint ?: JSONObject.NULL)
    }

    private fun eventJson(event: UiEventSample): JSONObject {
        return JSONObject()
            .put("eventType", event.eventType)
            .put("packageName", event.packageName ?: JSONObject.NULL)
            .put("className", event.className ?: JSONObject.NULL)
            .put("text", event.text ?: JSONObject.NULL)
            .put("timestamp", event.timestamp)
    }

    private fun screenKey(state: UiStateSample): String {
        val labels = state.nodes.mapNotNull { nodeLabel(it).takeIf { label -> label.isNotBlank() } }
            .take(12)
            .joinToString("|")
        val raw = "${state.appPackage}|${state.windowClassName}|$labels"
        return raw.hashCode().toUInt().toString(16)
    }

    private fun nodeLabel(node: NodeSample): String {
        return listOfNotNull(node.text, node.contentDesc, node.resourceId)
            .joinToString(" ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
    }

    private fun rectJson(rect: Rect): JSONObject {
        return JSONObject()
            .put("left", rect.left)
            .put("top", rect.top)
            .put("right", rect.right)
            .put("bottom", rect.bottom)
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    }

    private fun safeSegment(value: String): String {
        return value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "item" }
    }

    private fun pruneAppSnapshots(appDir: File) {
        if (!appDir.isDirectory) return
        val snapshotFiles = appDir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenBy { it.absolutePath }
            )
            .orEmpty()
        if (snapshotFiles.size <= maxSnapshotsPerApp) return

        snapshotFiles.drop(maxSnapshotsPerApp).forEach { snapshotFile ->
            deleteSnapshotBundle(snapshotFile)
        }
        pruneOldScreenshots()
    }

    private fun deleteSnapshotBundle(snapshotFile: File) {
        val screenshotPath = runCatching {
            JSONObject(snapshotFile.readText()).optString("screenshotPath")
        }.getOrNull()
        runCatching { snapshotFile.delete() }
        screenshotPath
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { path ->
                val screenshotFile = File(path)
                val screenshotRoot = screenshotRootDir.absolutePath.lowercase(Locale.US)
                val screenshotPathLower = screenshotFile.absolutePath.lowercase(Locale.US)
                if (screenshotPathLower.startsWith(screenshotRoot)) {
                    runCatching { screenshotFile.delete() }
                }
            }
    }

    private fun pruneOldScreenshots() {
        if (!screenshotRootDir.isDirectory) return
        val screenshotFiles = screenshotRootDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.US) == "png" }
            .sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenBy { it.absolutePath }
            )
            .toList()
        if (screenshotFiles.size <= maxGlobalScreenshots) return
        screenshotFiles.drop(maxGlobalScreenshots).forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun pruneGlobalSnapshots() {
        if (!rootDir.isDirectory) return
        val snapshotFiles = rootDir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenBy { it.absolutePath }
            )
            .toList()
        if (snapshotFiles.size <= maxGlobalSnapshots) return
        snapshotFiles.drop(maxGlobalSnapshots).forEach { snapshotFile ->
            deleteSnapshotBundle(snapshotFile)
        }
        pruneOldScreenshots()
    }
}
