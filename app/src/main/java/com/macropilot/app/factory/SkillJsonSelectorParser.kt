package com.macropilot.app.factory

import com.macropilot.app.model.ElementMatcher
import com.macropilot.app.model.NodeSample
import org.json.JSONArray
import org.json.JSONObject

class SkillJsonSelectorParser {
    fun matchersFromTarget(target: JSONObject?): List<ElementMatcher> {
        val raw = target?.optJSONArray("matchers") ?: return emptyList()
        return (0 until raw.length())
            .mapNotNull { index -> raw.optJSONObject(index)?.optString("selector") }
            .mapNotNull { selector -> matcherFromSelector(selector) }
    }

    fun nodeMatchesAny(node: NodeSample, matchers: List<ElementMatcher>): Boolean {
        return matchers.any { matcher -> nodeMatches(node, matcher) }
    }

    fun selectorStrings(target: JSONObject?): List<String> {
        val raw = target?.optJSONArray("matchers") ?: return emptyList()
        return (0 until raw.length()).mapNotNull { index ->
            raw.optJSONObject(index)?.optString("selector")?.takeIf { it.isNotBlank() }
        }
    }

    fun matcherFromSelector(selector: String): ElementMatcher? {
        if (selector.isBlank()) return null
        val exact = Regex("\\[([a-zA-Z-]+)=([\"'])(.*?)\\2\\]")
            .findAll(selector)
            .associate { normalizeKey(it.groupValues[1]) to it.groupValues[3] }
        val contains = Regex("\\[([a-zA-Z-]+)\\*=([\"'])(.*?)\\2\\]")
            .findAll(selector)
            .associate { normalizeKey(it.groupValues[1]) to it.groupValues[3] }
        val bools = Regex("\\[([a-zA-Z-]+)=(true|false)\\]")
            .findAll(selector)
            .associate { normalizeKey(it.groupValues[1]) to it.groupValues[2].toBoolean() }

        return ElementMatcher(
            text = exact["text"],
            textContains = contains["text"],
            contentDesc = exact["desc"],
            contentDescContains = contains["desc"],
            resourceId = exact["resourceId"] ?: exact["id"],
            className = exact["class"],
            clickable = bools["clickable"],
            editable = bools["editable"],
            scrollable = bools["scrollable"],
            enabled = bools["enabled"],
            focused = bools["focused"]
        )
    }

    private fun normalizeKey(raw: String): String {
        return when (raw) {
            "resource-id", "resource_id", "vid", "view-id", "view_id" -> "resourceId"
            "content-desc", "content_desc" -> "desc"
            else -> raw
        }
    }

    fun validateSelectors(skill: JSONObject, errors: MutableList<String>) {
        val targets = buildList {
            skill.optJSONObject("target")?.let { add(it) }
            skill.optJSONArray("elements")?.let { elements ->
                for (i in 0 until elements.length()) {
                    elements.optJSONObject(i)?.let { add(it) }
                }
            }
        }
        targets.forEach { target ->
            selectorStrings(target).forEach { selector ->
                if (matcherFromSelector(selector) == null) {
                    errors += "selector_parse_failed:$selector"
                }
            }
        }
    }

    private fun nodeMatches(node: NodeSample, matcher: ElementMatcher): Boolean {
        if (matcher.text != null && node.text != matcher.text) return false
        if (matcher.textContains != null && node.text?.contains(matcher.textContains) != true) return false
        if (matcher.contentDesc != null && node.contentDesc != matcher.contentDesc) return false
        if (matcher.contentDescContains != null && node.contentDesc?.contains(matcher.contentDescContains) != true) return false
        if (matcher.resourceId != null && !resourceIdMatches(node.resourceId, matcher.resourceId)) return false
        if (matcher.className != null && node.className != matcher.className) return false
        if (matcher.clickable != null && node.clickable != matcher.clickable) return false
        if (matcher.editable != null && node.editable != matcher.editable) return false
        if (matcher.scrollable != null && node.scrollable != matcher.scrollable) return false
        if (matcher.enabled != null && node.enabled != matcher.enabled) return false
        if (matcher.focused != null && node.focused != matcher.focused) return false
        return true
    }

    private fun resourceIdMatches(nodeResourceId: String?, expected: String): Boolean {
        val actual = nodeResourceId ?: return false
        return actual == expected ||
            actual.endsWith(":id/$expected") ||
            actual.endsWith("/$expected")
    }
}
