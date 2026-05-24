package com.macropilot.app.store

import android.content.Context
import android.graphics.Rect
import com.macropilot.app.model.CapabilityProfile
import com.macropilot.app.model.MacroTrainingSample
import com.macropilot.app.model.NodeSample
import com.macropilot.app.model.UiEventSample
import com.macropilot.app.model.UiStateSample
import com.macropilot.app.privacy.PrivacyRedactor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TrainingSampleStore(private val context: Context) {
    private val redactor = PrivacyRedactor()
    private val maxNodesPerTrainingSample = 80
    private val maxEventsPerTrainingSample = 30

    private val rootDir: File
        get() = File(context.filesDir, "macro_training/raw_samples")

    fun saveSnapshot(
        state: UiStateSample,
        profile: CapabilityProfile,
        note: String = "node_inspector_snapshot"
    ): File {
        val id = "sample_${System.currentTimeMillis()}"
        val redactedState = redactor.redact(state)
        val sample = MacroTrainingSample(
            id = id,
            beforeState = redactedState,
            capabilityProfile = profile,
            note = note,
            privacyLevel = "local_sensitive",
            containsPersonalData = true,
            redactionStatus = "basic_redacted"
        )
        val appDir = File(rootDir, redactedState.appPackage ?: "unknown")
        appDir.mkdirs()
        val file = File(appDir, "$id.json")
        file.writeText(toJson(sample).toString(2))
        return file
    }

    private fun toJson(sample: MacroTrainingSample): JSONObject {
        return JSONObject()
            .put("id", sample.id)
            .put("note", sample.note)
            .put("privacyLevel", sample.privacyLevel)
            .put("containsPersonalData", sample.containsPersonalData)
            .put("redactionStatus", sample.redactionStatus)
            .put("capabilityProfile", toJson(sample.capabilityProfile))
            .put("beforeState", toJson(sample.beforeState))
    }

    private fun toJson(profile: CapabilityProfile): JSONObject {
        return JSONObject()
            .put("nodeReadability", profile.nodeReadability.name)
            .put("inputCapability", profile.inputCapability.name)
            .put("verificationCapability", profile.verificationCapability.name)
            .put("coordinateFallback", profile.coordinateFallback.name)
            .put("serviceState", profile.serviceState.name)
            .put("reason", profile.reason)
    }

    private fun toJson(state: UiStateSample): JSONObject {
        val importantNodes = state.nodes
            .sortedWith(
                compareByDescending<NodeSample> { nodeImportance(it) }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.left }
            )
            .take(maxNodesPerTrainingSample)
        return JSONObject()
            .put("appPackage", state.appPackage)
            .put("windowClassName", state.windowClassName)
            .put("screenWidth", state.screenWidth)
            .put("screenHeight", state.screenHeight)
            .put("timestamp", state.timestamp)
            .put("nodeCount", state.nodes.size)
            .put("nodesStored", importantNodes.size)
            .put("storagePolicy", "redacted_compact_important_nodes_only")
            .put("nodes", JSONArray(importantNodes.map { toJson(it) }))
            .put("eventsSinceLastState", JSONArray(state.eventsSinceLastState.takeLast(maxEventsPerTrainingSample).map { toJson(it) }))
    }

    private fun toJson(node: NodeSample): JSONObject {
        return JSONObject()
            .put("nodeId", node.nodeId)
            .put("text", node.text)
            .put("contentDesc", node.contentDesc)
            .put("resourceId", node.resourceId)
            .put("className", node.className)
            .put("bounds", toJson(node.bounds))
            .put("clickable", node.clickable)
            .put("editable", node.editable)
            .put("scrollable", node.scrollable)
            .put("focused", node.focused)
            .put("enabled", node.enabled)
            .put("selected", node.selected)
            .put("depth", node.depth)
            .put("parentRoleHint", node.parentRoleHint)
    }

    private fun toJson(event: UiEventSample): JSONObject {
        return JSONObject()
            .put("eventType", event.eventType)
            .put("packageName", event.packageName)
            .put("className", event.className)
            .put("text", event.text)
            .put("timestamp", event.timestamp)
    }

    private fun toJson(rect: Rect): JSONObject {
        return JSONObject()
            .put("left", rect.left)
            .put("top", rect.top)
            .put("right", rect.right)
            .put("bottom", rect.bottom)
    }

    private fun nodeImportance(node: NodeSample): Int {
        var score = 0
        if (node.clickable) score += 8
        if (node.editable) score += 12
        if (node.scrollable) score += 4
        if (node.focused) score += 6
        if (!node.text.isNullOrBlank() || !node.contentDesc.isNullOrBlank() || !node.resourceId.isNullOrBlank()) score += 5
        val label = listOfNotNull(node.text, node.contentDesc, node.resourceId)
            .joinToString(" ")
            .lowercase()
        listOf("搜索", "搜", "发布", "发帖", "发表", "创作", "评论", "点赞", "发送", "关闭", "跳过", "取消", "确定", "下一步", "完成")
            .forEach { if (label.contains(it)) score += 4 }
        return score
    }
}
