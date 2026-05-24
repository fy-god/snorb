package com.macropilot.app.runtime

import com.macropilot.app.model.CapabilityProfile
import com.macropilot.app.model.MacroResult
import com.macropilot.app.model.MacroStatus
import com.macropilot.app.model.UiStateSample
import com.macropilot.app.model.VerificationCapability
import com.macropilot.app.model.VerifyRule
import com.macropilot.app.model.VerifyRuleType

class MacroVerifier {
    fun verify(
        rule: VerifyRule,
        params: Map<String, String>,
        state: UiStateSample?,
        profile: CapabilityProfile
    ): MacroResult {
        if (state == null) {
            return MacroResult(MacroStatus.FAILED_SERVICE_UNAVAILABLE, "No UiState available")
        }

        if (verificationRank(profile.verificationCapability) < verificationRank(rule.minVerification)) {
            return MacroResult(
                MacroStatus.FAILED_UNVERIFIABLE,
                "Verification capability ${profile.verificationCapability} below required ${rule.minVerification}",
                profile.verificationCapability
            )
        }

        val passed = when (rule.type) {
            VerifyRuleType.ALWAYS -> true
            VerifyRuleType.PACKAGE_IS -> state.appPackage == rule.packageName
            VerifyRuleType.TEXT_VISIBLE -> {
                val expected = resolveText(rule, params)
                !expected.isNullOrBlank() && state.nodes.any { node ->
                    node.text?.contains(expected) == true || node.contentDesc?.contains(expected) == true
                }
            }
            VerifyRuleType.ELEMENT_PRESENT -> {
                val expected = rule.elementId
                !expected.isNullOrBlank() && state.nodes.any { node ->
                    node.resourceId?.contains(expected) == true ||
                        node.text?.contains(expected) == true ||
                        node.contentDesc?.contains(expected) == true
                }
            }
            VerifyRuleType.INPUT_CONTAINS -> {
                val expected = resolveText(rule, params)
                !expected.isNullOrBlank() && state.nodes.any { it.editable && it.text?.contains(expected) == true }
            }
            VerifyRuleType.INPUT_CLEARED -> state.nodes.none { it.editable && !it.text.isNullOrBlank() }
            VerifyRuleType.SCREEN_MATCH -> !rule.screenId.isNullOrBlank()
            VerifyRuleType.MESSAGE_SENT -> {
                val expected = resolveText(rule, params)
                val visible = !expected.isNullOrBlank() && state.nodes.any { it.text?.contains(expected) == true }
                val inputCleared = state.nodes.none { it.editable && it.text?.contains(expected.orEmpty()) == true }
                visible && inputCleared
            }
        }

        return if (passed) {
            MacroResult(successStatus(profile.verificationCapability), "Verification passed", profile.verificationCapability)
        } else {
            MacroResult(MacroStatus.FAILED, "Verification failed for ${rule.type}", profile.verificationCapability)
        }
    }

    private fun resolveText(rule: VerifyRule, params: Map<String, String>): String? {
        return rule.literalText ?: rule.textParam?.let { params[it] }
    }

    private fun successStatus(capability: VerificationCapability): MacroStatus {
        return when (capability) {
            VerificationCapability.HIGH -> MacroStatus.SUCCESS
            VerificationCapability.MEDIUM -> MacroStatus.SUCCESS_MEDIUM_CONFIDENCE
            VerificationCapability.LOW -> MacroStatus.SUCCESS_LOW_CONFIDENCE
            VerificationCapability.NONE -> MacroStatus.FAILED_UNVERIFIABLE
        }
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
