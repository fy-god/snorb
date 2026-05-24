package com.roubao.autopilot.macro.core

import com.roubao.autopilot.macro.model.AppUiModule
import com.roubao.autopilot.macro.model.UiScreenModule
import com.roubao.autopilot.macro.model.UiSignature
import com.roubao.autopilot.skills.UiState

class UiScreenClassifier {
    fun classify(state: UiState, appUi: AppUiModule): UiScreenModule? {
        return appUi.screens
            .mapNotNull { screen ->
                val score = screen.signatures.maxOfOrNull { scoreSignature(it, state) } ?: 0f
                if (score >= (screen.signatures.minOfOrNull { it.minScore } ?: 0.75f)) {
                    screen to score
                } else {
                    null
                }
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun scoreSignature(signature: UiSignature, state: UiState): Float {
        var score = 0f
        var possible = 0f

        signature.packageName?.let {
            possible += 0.25f
            if (state.packageName == it) score += 0.25f
        }

        signature.activityName?.let {
            possible += 0.10f
            if (state.activityName?.contains(it) == true) score += 0.10f
        }

        if (signature.requiredTextsAll.isNotEmpty()) {
            possible += 0.35f
            val matched = signature.requiredTextsAll.count { state.hasText(it) }
            score += 0.35f * matched / signature.requiredTextsAll.size
        }

        if (signature.requiredTextsAny.isNotEmpty()) {
            possible += 0.20f
            if (signature.requiredTextsAny.any { state.hasText(it) }) score += 0.20f
        }

        if (signature.requiredResourceIdsAny.isNotEmpty()) {
            possible += 0.10f
            val matched = state.nodes.any { node ->
                node.resourceId != null && signature.requiredResourceIdsAny.any { it == node.resourceId }
            }
            if (matched) score += 0.10f
        }

        if (possible == 0f) return 0f
        return (score / possible).coerceIn(0f, 1f)
    }
}
