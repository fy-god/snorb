package com.macropilot.app.wechat

import com.macropilot.app.model.InputCapability
import org.json.JSONObject

data class WechatCapability(
    val packageReady: Boolean,
    val rootReadable: Boolean,
    val nodeCount: Int,
    val hasEditableNode: Boolean,
    val hasSendButtonNode: Boolean,
    val hasChatInputCoordinate: Boolean,
    val hasSendButtonCoordinate: Boolean,
    val inputChannel: InputCapability,
    val automationLevel: WechatAutomationLevel,
    val macroPilotImeSelected: Boolean,
    val macroPilotImeReady: Boolean,
    val reason: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("packageReady", packageReady)
            .put("rootReadable", rootReadable)
            .put("nodeCount", nodeCount)
            .put("hasEditableNode", hasEditableNode)
            .put("hasSendButtonNode", hasSendButtonNode)
            .put("hasChatInputCoordinate", hasChatInputCoordinate)
            .put("hasSendButtonCoordinate", hasSendButtonCoordinate)
            .put("inputChannel", inputChannel.name)
            .put("automationLevel", automationLevel.name)
            .put("macroPilotImeSelected", macroPilotImeSelected)
            .put("macroPilotImeReady", macroPilotImeReady)
            .put("reason", reason)
    }
}

enum class WechatAutomationLevel {
    A_NODE_READABLE,
    B_PARTIAL_NODE,
    C_COORDINATE_ONLY,
    D_NOT_EXECUTABLE
}

