package com.macropilot.app.factory

import android.content.Context
import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.model.InputCapability
import com.macropilot.app.model.NodeReadability
import com.macropilot.app.model.UiStateSample
import com.macropilot.app.model.VerificationCapability
import com.macropilot.app.runtime.CapabilityProfiler
import com.macropilot.app.store.CandidateKnowledgeStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CalibrationRequestFactory(
    private val context: Context,
    private val profiler: CapabilityProfiler = CapabilityProfiler(),
    private val actions: DeviceActions = DeviceActions(context),
    private val candidateStore: CandidateKnowledgeStore = CandidateKnowledgeStore(context)
) {
    fun saveWeChatSendMessageRequest(state: UiStateSample?): File {
        candidateStore.ensureCreated()
        val rawProfile = profiler.profile(state, serviceConnected = true)
        val profile = if (rawProfile.inputCapability == InputCapability.NONE && actions.isMacroPilotImeSelected()) {
            rawProfile.copy(
                inputCapability = InputCapability.CUSTOM_IME,
                reason = "${rawProfile.reason}, customIme=selected"
            )
        } else {
            rawProfile
        }
        val id = "wechat_calibration_${System.currentTimeMillis()}"
        val file = File(candidateStore.pendingSkillDir, "$id.json")
        file.writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("id", id)
                .put("type", "WeChatCalibrationRequest")
                .put("packageName", "com.tencent.mm")
                .put("intentId", "send_message")
                .put("status", "FAILED_NEEDS_CALIBRATION")
                .put("runtimeExecutable", false)
                .put("nodeReadability", profile.nodeReadability.name)
                .put("inputCapability", profile.inputCapability.name)
                .put("verificationCapability", profile.verificationCapability.name)
                .put("reason", reason(profile.nodeReadability, profile.inputCapability, profile.verificationCapability))
                .put("observed", JSONObject()
                    .put("appPackage", state?.appPackage)
                    .put("windowClassName", state?.windowClassName)
                    .put("screenWidth", state?.screenWidth)
                    .put("screenHeight", state?.screenHeight)
                    .put("nodeCount", state?.nodes?.size ?: 0)
                    .put("clickableCount", state?.nodes?.count { it.clickable } ?: 0)
                    .put("editableCount", state?.nodes?.count { it.editable } ?: 0))
                .put("requiredCalibration", JSONArray(listOf(
                    element("search_entry", "Open WeChat search entry", "coordinate_or_node", "LOW_RISK"),
                    element("search_input", "Focus search input", "input_channel_required", "LOW_RISK"),
                    element("contact_result", "Select target contact or File Transfer", "coordinate_or_node", "MEDIUM_RISK"),
                    element("chat_input", "Focus chat text input", "input_channel_required", "MEDIUM_RISK"),
                    element("send_button", "Send message button", "coordinate_or_node", "HIGH_SIDE_EFFECT")
                )))
                .put("blockedRuntimeReasons", JSONArray(blockers(profile.inputCapability, profile.verificationCapability)))
                .put("promotionRequired", "Must pass factory test runs before any Approved skill is created")
                .put("createdAt", System.currentTimeMillis())
                .toString(2)
        )
        return file
    }

    private fun reason(
        readability: NodeReadability,
        input: InputCapability,
        verification: VerificationCapability
    ): String {
        return when {
            input == InputCapability.NONE -> "WeChat send-message requires text input, but no input channel is currently available"
            readability == NodeReadability.C || readability == NodeReadability.D -> "WeChat nodes are insufficient for high-confidence automation"
            verification == VerificationCapability.LOW || verification == VerificationCapability.NONE -> "Final send result cannot be verified with enough confidence"
            else -> "Calibration required before WeChat skill promotion"
        }
    }

    private fun blockers(input: InputCapability, verification: VerificationCapability): List<String> {
        return buildList {
            if (input == InputCapability.NONE) add("FAILED_INPUT_CHANNEL_MISSING")
            if (verification == VerificationCapability.LOW || verification == VerificationCapability.NONE) add("FAILED_UNVERIFIABLE")
            add("FAILED_NEEDS_CALIBRATION")
        }
    }

    private fun element(id: String, label: String, requirement: String, risk: String): JSONObject {
        return JSONObject()
            .put("elementId", id)
            .put("label", label)
            .put("requirement", requirement)
            .put("risk", risk)
            .put("status", "PENDING_USER_CALIBRATION")
    }
}
