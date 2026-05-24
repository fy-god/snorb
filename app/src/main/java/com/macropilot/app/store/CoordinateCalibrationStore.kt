package com.macropilot.app.store

import android.content.Context
import android.graphics.Rect
import com.macropilot.app.model.NodeSample
import com.macropilot.app.model.UiStateSample
import com.macropilot.app.privacy.PrivacyRedactor
import org.json.JSONObject
import java.io.File

class CoordinateCalibrationStore(private val context: Context) {
    private val rootDir: File
        get() = File(context.filesDir, "macro_v2/calibration/coordinates")

    fun saveCandidate(state: UiStateSample): File? {
        val redactedState = PrivacyRedactor().redact(state)
        val node = chooseCandidate(redactedState) ?: return null
        val appPackage = redactedState.appPackage ?: "unknown"
        val appDir = File(rootDir, appPackage)
        appDir.mkdirs()
        val file = File(appDir, "coord_${System.currentTimeMillis()}.json")
        file.writeText(toJson(redactedState, node).toString(2))
        return file
    }

    fun saveManualPointCandidate(
        appPackage: String,
        elementId: String,
        screenWidth: Int,
        screenHeight: Int,
        normalizedX: Float,
        normalizedY: Float,
        note: String? = null
    ): File {
        val appDir = File(rootDir, appPackage.ifBlank { "unknown" })
        appDir.mkdirs()
        val safeElementId = elementId.ifBlank { "manual_point" }
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val file = File(appDir, "${safeElementId}_${System.currentTimeMillis()}.json")
        val x = normalizedX.coerceIn(0f, 1f)
        val y = normalizedY.coerceIn(0f, 1f)
        val halfWidth = 0.035f
        val halfHeight = 0.025f
        file.writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("status", "CANDIDATE")
                .put("source", "MANUAL_POINT")
                .put("appPackage", appPackage)
                .put("elementId", safeElementId)
                .put("screenWidth", screenWidth)
                .put("screenHeight", screenHeight)
                .put("timestamp", System.currentTimeMillis())
                .put("note", note)
                .put("coordinatePack", JSONObject()
                    .put("baseWidth", screenWidth)
                    .put("baseHeight", screenHeight)
                    .put("normalizedBounds", JSONObject()
                        .put("left", (x - halfWidth).coerceIn(0f, 1f))
                        .put("top", (y - halfHeight).coerceIn(0f, 1f))
                        .put("right", (x + halfWidth).coerceIn(0f, 1f))
                        .put("bottom", (y + halfHeight).coerceIn(0f, 1f)))
                    .put("fallbackTap", JSONObject()
                        .put("x", x)
                        .put("y", y))
                    .put("confidence", 0.35)
                    .put("source", "MANUAL_CALIBRATION")
                    .put("status", "NEEDS_RECALIBRATION")
                    .put("lastVerifiedAt", 0L))
                .toString(2)
        )
        return file
    }

    fun countCandidates(): Int {
        if (!rootDir.exists()) return 0
        return rootDir.walkTopDown().count { it.isFile && it.extension == "json" }
    }

    fun deleteCandidates(): Int {
        if (!rootDir.exists()) return 0
        var count = 0
        rootDir.walkBottomUp().forEach { file ->
            if (file.isFile && file.extension == "json" && file.delete()) count++
        }
        return count
    }

    private fun chooseCandidate(state: UiStateSample): NodeSample? {
        val screenArea = state.screenWidth.coerceAtLeast(1).toLong() * state.screenHeight.coerceAtLeast(1).toLong()
        return state.nodes
            .filter { it.enabled && !it.bounds.isEmpty }
            .filter { !it.text.isNullOrBlank() || !it.contentDesc.isNullOrBlank() || !it.resourceId.isNullOrBlank() || it.clickable || it.editable }
            .filter { node ->
                val area = node.bounds.width().coerceAtLeast(0).toLong() * node.bounds.height().coerceAtLeast(0).toLong()
                node.clickable || node.editable || area < screenArea * 3L / 5L
            }
            .sortedWith(
                compareByDescending<NodeSample> { it.editable }
                    .thenByDescending { it.clickable }
                    .thenByDescending { readableScore(it) }
                    .thenByDescending { it.focused }
                    .thenBy { it.depth }
            )
            .firstOrNull()
    }

    private fun readableScore(node: NodeSample): Int {
        return listOf(node.text, node.contentDesc, node.resourceId).count { !it.isNullOrBlank() }
    }

    private fun toJson(state: UiStateSample, node: NodeSample): JSONObject {
        val width = state.screenWidth.coerceAtLeast(1)
        val height = state.screenHeight.coerceAtLeast(1)
        val normalized = normalizedBounds(node.bounds, width, height)
        val centerX = ((normalized.getDouble("left") + normalized.getDouble("right")) / 2.0).toFloat()
        val centerY = ((normalized.getDouble("top") + normalized.getDouble("bottom")) / 2.0).toFloat()
        return JSONObject()
            .put("schemaVersion", 1)
            .put("status", "CANDIDATE")
            .put("source", "MANUAL_CALIBRATION")
            .put("appPackage", state.appPackage)
            .put("windowClassName", state.windowClassName)
            .put("screenWidth", state.screenWidth)
            .put("screenHeight", state.screenHeight)
            .put("timestamp", System.currentTimeMillis())
            .put("node", nodeJson(node))
            .put("coordinatePack", JSONObject()
                .put("baseWidth", state.screenWidth)
                .put("baseHeight", state.screenHeight)
                .put("normalizedBounds", normalized)
                .put("fallbackTap", JSONObject()
                    .put("x", centerX.coerceIn(0f, 1f))
                    .put("y", centerY.coerceIn(0f, 1f)))
                .put("confidence", 0.50)
                .put("source", "MANUAL_CALIBRATION")
                .put("status", "NEEDS_RECALIBRATION")
                .put("lastVerifiedAt", 0L))
    }

    private fun nodeJson(node: NodeSample): JSONObject {
        return JSONObject()
            .put("nodeId", node.nodeId)
            .put("text", node.text)
            .put("contentDesc", node.contentDesc)
            .put("resourceId", node.resourceId)
            .put("className", node.className)
            .put("clickable", node.clickable)
            .put("editable", node.editable)
            .put("scrollable", node.scrollable)
            .put("focused", node.focused)
            .put("enabled", node.enabled)
            .put("depth", node.depth)
            .put("bounds", JSONObject()
                .put("left", node.bounds.left)
                .put("top", node.bounds.top)
                .put("right", node.bounds.right)
                .put("bottom", node.bounds.bottom))
    }

    private fun normalizedBounds(bounds: Rect, width: Int, height: Int): JSONObject {
        fun nx(value: Int): Float = (value.toFloat() / width.toFloat()).coerceIn(0f, 1f)
        fun ny(value: Int): Float = (value.toFloat() / height.toFloat()).coerceIn(0f, 1f)
        return JSONObject()
            .put("left", nx(bounds.left))
            .put("top", ny(bounds.top))
            .put("right", nx(bounds.right))
            .put("bottom", ny(bounds.bottom))
    }
}
