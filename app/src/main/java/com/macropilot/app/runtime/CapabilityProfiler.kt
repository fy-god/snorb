package com.macropilot.app.runtime

import com.macropilot.app.model.CapabilityProfile
import com.macropilot.app.model.CoordinateFallback
import com.macropilot.app.model.InputCapability
import com.macropilot.app.model.NodeReadability
import com.macropilot.app.model.ServiceState
import com.macropilot.app.model.UiStateSample
import com.macropilot.app.model.VerificationCapability
import com.macropilot.app.automation.MacroPilotImeService

class CapabilityProfiler {
    fun profile(state: UiStateSample?, serviceConnected: Boolean): CapabilityProfile {
        if (!serviceConnected) {
            return CapabilityProfile(
                nodeReadability = NodeReadability.D,
                inputCapability = InputCapability.NONE,
                verificationCapability = VerificationCapability.NONE,
                coordinateFallback = CoordinateFallback.MISSING,
                serviceState = ServiceState.DISCONNECTED,
                reason = "Accessibility service is disconnected"
            )
        }

        if (state == null) {
            return CapabilityProfile(
                nodeReadability = NodeReadability.D,
                inputCapability = InputCapability.NONE,
                verificationCapability = VerificationCapability.NONE,
                coordinateFallback = CoordinateFallback.MISSING,
                serviceState = ServiceState.ROOT_UNAVAILABLE,
                reason = "rootInActiveWindow is unavailable"
            )
        }

        val nodes = state.nodes
        val textLike = nodes.count { !it.text.isNullOrBlank() || !it.contentDesc.isNullOrBlank() || !it.resourceId.isNullOrBlank() }
        val clickable = nodes.count { it.clickable && it.enabled }
        val editable = nodes.count { it.editable && it.enabled }
        val scrollable = nodes.count { it.scrollable && it.enabled }

        val readability = when {
            nodes.size >= 20 && textLike >= 6 && (clickable + editable + scrollable) >= 3 -> NodeReadability.A
            nodes.size >= 8 && textLike >= 2 && (clickable + editable + scrollable) >= 1 -> NodeReadability.B
            nodes.isNotEmpty() -> NodeReadability.C
            else -> NodeReadability.D
        }

        val input = when {
            editable > 0 -> InputCapability.SET_TEXT
            MacroPilotImeService.instance != null -> InputCapability.CUSTOM_IME
            else -> InputCapability.NONE
        }
        val verification = when (readability) {
            NodeReadability.A -> VerificationCapability.HIGH
            NodeReadability.B -> VerificationCapability.MEDIUM
            NodeReadability.C -> VerificationCapability.LOW
            NodeReadability.D -> VerificationCapability.NONE
        }
        val coordinates = if (nodes.any { !it.bounds.isEmpty }) CoordinateFallback.AVAILABLE else CoordinateFallback.MISSING

        return CapabilityProfile(
            nodeReadability = readability,
            inputCapability = input,
            verificationCapability = verification,
            coordinateFallback = coordinates,
            serviceState = ServiceState.CONNECTED,
            reason = "nodes=${nodes.size}, textLike=$textLike, clickable=$clickable, editable=$editable, scrollable=$scrollable, customIme=${MacroPilotImeService.instance != null}"
        )
    }
}
