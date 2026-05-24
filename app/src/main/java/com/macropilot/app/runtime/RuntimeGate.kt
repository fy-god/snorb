package com.macropilot.app.runtime

import com.macropilot.app.model.CapabilityProfile
import com.macropilot.app.model.InputCapability
import com.macropilot.app.model.MacroStatus
import com.macropilot.app.model.NodeReadability
import com.macropilot.app.model.RuntimeGateDecision
import com.macropilot.app.model.RuntimeTaskRequirements
import com.macropilot.app.model.ServiceState
import com.macropilot.app.model.SideEffectLevel
import com.macropilot.app.model.SkillSource
import com.macropilot.app.model.VerificationCapability
import com.macropilot.app.safety.SafeActionPolicy

class RuntimeGate(
    private val safetyPolicy: SafeActionPolicy = SafeActionPolicy()
) {
    fun evaluate(
        profile: CapabilityProfile,
        requirements: RuntimeTaskRequirements,
        skillSource: SkillSource,
        staticSafetyText: String? = null
    ): RuntimeGateDecision {
        if (skillSource != SkillSource.APPROVED) {
            return blocked(MacroStatus.FAILED_PRECONDITION, "Runtime refuses non-approved skills")
        }

        if (profile.serviceState != ServiceState.CONNECTED) {
            return blocked(MacroStatus.FAILED_SERVICE_UNAVAILABLE, "Accessibility service/root is unavailable")
        }

        if (profile.nodeReadability == NodeReadability.D) {
            return blocked(MacroStatus.FAILED_CAPABILITY_D, "Capability D is non-executable")
        }

        if (!safetyPolicy.evaluateText(staticSafetyText).allowed) {
            return blocked(MacroStatus.FAILED_DANGEROUS_ACTION_BLOCKED, "Dangerous action blocked")
        }

        if (requirements.needsTextInput && profile.inputCapability == InputCapability.NONE) {
            return blocked(MacroStatus.FAILED_INPUT_CHANNEL_MISSING, "Text task has no input channel")
        }

        if (requirements.requiresVerification && profile.verificationCapability == VerificationCapability.NONE) {
            return blocked(MacroStatus.FAILED_UNVERIFIABLE, "No verification signal available")
        }

        if (requirements.sideEffectLevel == SideEffectLevel.HIGH &&
            verificationRank(profile.verificationCapability) < verificationRank(VerificationCapability.MEDIUM)
        ) {
            return blocked(MacroStatus.FAILED_UNVERIFIABLE, "High side-effect task requires medium or high verification")
        }

        if (profile.nodeReadability == NodeReadability.C && !requirements.allowLowConfidence) {
            return blocked(MacroStatus.FAILED_UNVERIFIABLE, "Capability C can only run with explicit low-confidence allowance")
        }

        val ceiling = when (profile.nodeReadability) {
            NodeReadability.A -> MacroStatus.SUCCESS
            NodeReadability.B -> MacroStatus.SUCCESS_MEDIUM_CONFIDENCE
            NodeReadability.C -> MacroStatus.SUCCESS_LOW_CONFIDENCE
            NodeReadability.D -> MacroStatus.FAILED_CAPABILITY_D
        }

        return RuntimeGateDecision(
            allowed = true,
            confidenceCeiling = ceiling,
            reason = "Runtime gate passed with ceiling $ceiling"
        )
    }

    private fun blocked(status: MacroStatus, reason: String): RuntimeGateDecision {
        return RuntimeGateDecision(
            allowed = false,
            blockedStatus = status,
            confidenceCeiling = MacroStatus.FAILED,
            reason = reason
        )
    }

    private fun verificationRank(capability: VerificationCapability): Int {
        return when (capability) {
            VerificationCapability.HIGH -> 3
            VerificationCapability.MEDIUM -> 2
            VerificationCapability.LOW -> 1
            VerificationCapability.NONE -> 0
        }
    }
}
