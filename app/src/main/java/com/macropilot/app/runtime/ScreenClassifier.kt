package com.macropilot.app.runtime

import com.macropilot.app.model.AppUiModule
import com.macropilot.app.model.UiScreenModule
import com.macropilot.app.model.UiSignature
import com.macropilot.app.model.UiStateSample

class ScreenClassifier {
    fun classify(state: UiStateSample, appUi: AppUiModule): ScreenMatch? {
        return appUi.screens
            .mapNotNull { screen ->
                val score = score(screen, state, appUi.packageAliases)
                val minScore = screen.signatures.minOfOrNull { it.minScore } ?: 0.75f
                if (score >= minScore) ScreenMatch(screen.id, score) else null
            }
            .maxByOrNull { it.score }
    }

    fun score(screen: UiScreenModule, state: UiStateSample, packageAliases: List<String> = emptyList()): Float {
        return screen.signatures.maxOfOrNull { scoreSignature(it, state, packageAliases) } ?: 0f
    }

    private fun scoreSignature(signature: UiSignature, state: UiStateSample, packageAliases: List<String>): Float {
        var score = 0f
        var total = 0f
        val texts = state.nodes.mapNotNull { it.text ?: it.contentDesc }
        val resourceIds = state.nodes.mapNotNull { it.resourceId }

        total += 0.25f
        if (state.appPackage == signature.packageName || state.appPackage in packageAliases) score += 0.25f

        if (!signature.activityName.isNullOrBlank()) {
            total += 0.10f
            if (state.windowClassName == signature.activityName) score += 0.10f
        }

        if (signature.requiredTextsAny.isNotEmpty()) {
            total += 0.20f
            if (signature.requiredTextsAny.any { expected -> texts.any { it.contains(expected) } }) score += 0.20f
        }

        if (signature.requiredTextsAll.isNotEmpty()) {
            total += 0.20f
            if (signature.requiredTextsAll.all { expected -> texts.any { it.contains(expected) } }) score += 0.20f
        }

        if (signature.requiredResourceIdsAny.isNotEmpty()) {
            total += 0.15f
            if (signature.requiredResourceIdsAny.any { expected -> resourceIds.any { it == expected || it.endsWith("/$expected") } }) score += 0.15f
        }

        if (signature.negativeTexts.isNotEmpty()) {
            total += 0.10f
            if (signature.negativeTexts.none { bad -> texts.any { it.contains(bad) } }) score += 0.10f
        }

        return if (total == 0f) 0f else (score / total).coerceIn(0f, 1f)
    }
}

data class ScreenMatch(
    val screenId: String,
    val score: Float
)
