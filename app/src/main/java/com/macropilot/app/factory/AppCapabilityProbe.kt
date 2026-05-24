package com.macropilot.app.factory

import android.content.Context
import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.model.CapabilityProfile
import com.macropilot.app.model.InputCapability
import com.macropilot.app.model.NodeSample
import com.macropilot.app.model.UiStateSample
import com.macropilot.app.runtime.CapabilityProfiler
import com.macropilot.app.runtime.NodeInspector
import com.macropilot.app.store.TrainingSampleStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppCapabilityProbe(
    private val context: Context,
    private val inspector: NodeInspector = NodeInspector(context),
    private val profiler: CapabilityProfiler = CapabilityProfiler(),
    private val actions: DeviceActions = DeviceActions(context),
    private val sampleStore: TrainingSampleStore = TrainingSampleStore(context)
) {
    fun probe(packageName: String, saveSample: Boolean = true, timeoutMs: Long = 5000L): AppProbeResult {
        if (packageName.isBlank()) {
            return AppProbeResult(false, "Missing packageName", packageName, null, null, null, emptyList())
        }
        if (!actions.isInteractive()) {
            return AppProbeResult(false, "Screen is off; wake and unlock before probing", packageName, null, null, null, emptyList())
        }
        if (actions.isKeyguardLocked()) {
            return AppProbeResult(false, "Device is locked; unlock before probing", packageName, null, null, null, emptyList())
        }
        val open = actions.openApp(packageName)
        if (!open.success) {
            return AppProbeResult(false, open.reason, packageName, null, null, null, emptyList())
        }
        val waitResult = waitForPackage(packageName, timeoutMs)
        val state = waitResult.targetState
        if (state == null) {
            return AppProbeResult(
                ok = false,
                message = "Target package did not become foreground within ${timeoutMs}ms",
                requestedPackageName = packageName,
                observedPackageName = waitResult.latestState?.appPackage,
                state = null,
                profile = CapabilityProfile(
                    nodeReadability = com.macropilot.app.model.NodeReadability.D,
                    inputCapability = com.macropilot.app.model.InputCapability.NONE,
                    verificationCapability = com.macropilot.app.model.VerificationCapability.NONE,
                    coordinateFallback = com.macropilot.app.model.CoordinateFallback.MISSING,
                    serviceState = if (MacroPilotAccessibilityService.instance == null) {
                        com.macropilot.app.model.ServiceState.DISCONNECTED
                    } else {
                        com.macropilot.app.model.ServiceState.CONNECTED
                    },
                    reason = "Foreground package mismatch: requested=$packageName observed=${waitResult.latestState?.appPackage}"
                ),
                nodeHighlights = emptyList(),
                sampleFile = null
            )
        }
        val profile = withImeCapability(profiler.profile(
            state,
            serviceConnected = MacroPilotAccessibilityService.instance != null
        ))
        val sample = if (saveSample) {
            sampleStore.saveSnapshot(state, profile, note = "capability_probe package=$packageName")
        } else {
            null
        }
        return AppProbeResult(
            ok = true,
            message = "Capability probe completed",
            requestedPackageName = packageName,
            observedPackageName = state.appPackage,
            state = state,
            profile = profile,
            nodeHighlights = highlights(state.nodes),
            sampleFile = sample
        )
    }

    private fun waitForPackage(packageName: String, timeoutMs: Long): ProbeWaitResult {
        val started = System.currentTimeMillis()
        var latest: UiStateSample? = null
        while (System.currentTimeMillis() - started <= timeoutMs) {
            val state = inspector.currentState()
            if (state != null) {
                latest = state
                if (state.matchesPackage(packageName)) {
                    return ProbeWaitResult(targetState = state, latestState = state)
                }
            }
            sleepQuietly(100L)
        }
        return ProbeWaitResult(targetState = null, latestState = latest)
    }

    private fun UiStateSample.matchesPackage(packageName: String): Boolean {
        val targetNodes = nodes.count { node ->
            node.packageName == packageName ||
                node.resourceId?.startsWith("$packageName:") == true
        }
        val nonTargetNodes = nodes.count { node ->
            val nodePackage = node.packageName
            !nodePackage.isNullOrBlank() &&
                nodePackage != packageName &&
                nodePackage != "android" &&
                nodePackage != "com.android.systemui"
        }
        if (targetNodes > 0 && targetNodes >= nonTargetNodes) return true
        return appPackage == packageName && targetNodes > 0 && nonTargetNodes == 0
    }

    private fun highlights(nodes: List<NodeSample>): List<JSONObject> {
        return nodes
            .filter { node ->
                node.enabled &&
                    (node.clickable || node.editable || node.scrollable ||
                        !node.text.isNullOrBlank() ||
                        !node.contentDesc.isNullOrBlank() ||
                        !node.resourceId.isNullOrBlank())
            }
            .sortedWith(
                compareByDescending<NodeSample> { it.editable }
                    .thenByDescending { it.clickable }
                    .thenBy { it.depth }
            )
            .take(40)
            .map { node ->
                JSONObject()
                    .put("nodeId", node.nodeId)
                    .put("packageName", node.packageName)
                    .put("text", node.text)
                    .put("contentDesc", node.contentDesc)
                    .put("resourceId", node.resourceId)
                    .put("className", node.className)
                    .put("clickable", node.clickable)
                    .put("editable", node.editable)
                    .put("scrollable", node.scrollable)
                    .put("focused", node.focused)
                    .put("depth", node.depth)
            }
    }

    private fun withImeCapability(profile: CapabilityProfile): CapabilityProfile {
        if (profile.inputCapability != InputCapability.NONE || !actions.isMacroPilotImeSelected()) return profile
        return profile.copy(
            inputCapability = InputCapability.CUSTOM_IME,
            reason = "${profile.reason}, customIme=selected"
        )
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

private data class ProbeWaitResult(
    val targetState: UiStateSample?,
    val latestState: UiStateSample?
)

data class AppProbeResult(
    val ok: Boolean,
    val message: String,
    val requestedPackageName: String?,
    val observedPackageName: String?,
    val state: UiStateSample?,
    val profile: CapabilityProfile?,
    val nodeHighlights: List<JSONObject>,
    val sampleFile: File? = null
) {
    fun toJson(action: String): JSONObject {
        val stateValue = state
        val profileValue = profile
        return JSONObject()
            .put("ok", ok)
            .put("action", action)
            .put("message", message)
            .put("requestedPackageName", requestedPackageName)
            .put("packageName", observedPackageName)
            .put("observedPackageName", observedPackageName)
            .put("nodeCount", stateValue?.nodes?.size ?: 0)
            .put("clickableCount", stateValue?.nodes?.count { it.clickable } ?: 0)
            .put("editableCount", stateValue?.nodes?.count { it.editable } ?: 0)
            .put("scrollableCount", stateValue?.nodes?.count { it.scrollable } ?: 0)
            .put("nodeReadability", profileValue?.nodeReadability?.name)
            .put("inputCapability", profileValue?.inputCapability?.name)
            .put("verificationCapability", profileValue?.verificationCapability?.name)
            .put("coordinateFallback", profileValue?.coordinateFallback?.name)
            .put("serviceState", profileValue?.serviceState?.name)
            .put("reason", profileValue?.reason)
            .put("sampleFile", sampleFile?.absolutePath)
            .put("highlights", JSONArray(nodeHighlights))
            .put("timestamp", System.currentTimeMillis())
    }
}
