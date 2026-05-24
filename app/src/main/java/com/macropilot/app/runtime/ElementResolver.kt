package com.macropilot.app.runtime

import com.macropilot.app.model.ElementMatcher
import com.macropilot.app.model.NodeSample
import com.macropilot.app.model.UiElementSpec
import com.macropilot.app.model.UiStateSample

class ElementResolver {
    fun resolve(element: UiElementSpec, state: UiStateSample): ResolvedElement? {
        return state.nodes
            .mapNotNull { node ->
                val score = element.matchers.maxOfOrNull { matcher -> scoreMatcher(matcher, node) } ?: 0f
                if (score > 0f) ResolvedElement(element.id, node, score) else null
            }
            .maxByOrNull { it.score }
            ?.takeIf { it.score >= 0.60f }
    }

    private fun scoreMatcher(matcher: ElementMatcher, node: NodeSample): Float {
        var score = 0f
        var total = 0f

        fun checkString(expected: String?, actual: String?, weight: Float, contains: Boolean = false) {
            if (expected == null) return
            total += weight
            if (actual != null && if (contains) actual.contains(expected) else actual == expected) {
                score += weight
            }
        }

        fun checkBool(expected: Boolean?, actual: Boolean, weight: Float) {
            if (expected == null) return
            total += weight
            if (expected == actual) score += weight
        }

        checkString(matcher.text, node.text, 0.25f)
        checkString(matcher.textContains, node.text, 0.20f, contains = true)
        checkString(matcher.contentDesc, node.contentDesc, 0.20f)
        checkString(matcher.contentDescContains, node.contentDesc, 0.15f, contains = true)
        checkString(matcher.resourceId, node.resourceId, 0.25f)
        checkString(matcher.className, node.className, 0.10f)
        checkBool(matcher.clickable, node.clickable, 0.10f)
        checkBool(matcher.editable, node.editable, 0.10f)
        checkBool(matcher.scrollable, node.scrollable, 0.10f)
        checkBool(matcher.enabled, node.enabled, 0.05f)

        return if (total == 0f) 0f else (score / total).coerceIn(0f, 1f)
    }
}

data class ResolvedElement(
    val elementId: String,
    val node: NodeSample,
    val score: Float
)
