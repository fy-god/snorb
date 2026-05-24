package com.roubao.autopilot.macro.core

import com.roubao.autopilot.macro.model.VerifyResult
import com.roubao.autopilot.macro.model.VerifyRule
import com.roubao.autopilot.skills.UiState

class MacroVerifier {
    fun verify(
        rule: VerifyRule,
        params: Map<String, String>,
        state: UiState
    ): VerifyResult {
        return when (rule) {
            is VerifyRule.PackageVisible -> {
                if (state.packageName == rule.packageName) VerifyResult.success()
                else VerifyResult.failed("package ${state.packageName} != ${rule.packageName}")
            }
            is VerifyRule.ScreenContains -> {
                val text = rule.text ?: rule.textParam?.let(params::get)
                when {
                    text.isNullOrBlank() -> VerifyResult.failed("missing text for ScreenContains")
                    state.hasText(text) -> VerifyResult.success()
                    else -> VerifyResult.failed("screen does not contain $text")
                }
            }
            is VerifyRule.MessageSent -> {
                val message = params[rule.messageParam]
                val hasRealNode = state.nodes.any { node ->
                    node.visible && !node.bounds.isEmpty && (node.text != null || node.contentDesc != null || node.resourceId != null)
                }
                when {
                    message.isNullOrBlank() -> VerifyResult.failed("missing message param ${rule.messageParam}")
                    state.packageName == "com.tencent.mm" && !hasRealNode ->
                        VerifyResult.failed("wechat verification unavailable without accessibility nodes")
                    !state.hasText(message) -> VerifyResult.failed("latest message is not visible")
                    state.editableNodes.any { it.text?.contains(message) == true } ->
                        VerifyResult.failed("message still appears in an editable input")
                    else -> VerifyResult.success()
                }
            }
            VerifyRule.ScreenChanged -> VerifyResult.success()
            VerifyRule.AlwaysTrue -> VerifyResult.success()
        }
    }
}
