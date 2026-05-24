package com.macropilot.app.safety

import com.macropilot.app.model.MacroAtom
import com.macropilot.app.model.MacroTemplate
import com.macropilot.app.model.NodeSample

class SafeActionPolicy {
    private val hardBlockedKeywords = listOf(
        "\u652f\u4ed8",
        "\u4ed8\u6b3e",
        "\u8f6c\u8d26",
        "\u786e\u8ba4\u4ed8\u6b3e",
        "\u7acb\u5373\u8d2d\u4e70",
        "\u4e0b\u5355",
        "\u63d0\u4ea4\u8ba2\u5355",
        "\u9000\u51fa\u767b\u5f55",
        "\u6ce8\u9500",
        "\u5220\u9664\u8d26\u53f7",
        "\u5220\u9664\u8d26\u6237",
        "\u6ce8\u9500\u8d26\u53f7",
        "\u6ce8\u9500\u8d26\u6237",
        "\u6539\u5bc6\u7801",
        "\u4fee\u6539\u5bc6\u7801",
        "\u89e3\u7ed1\u94f6\u884c\u5361",
        "\u7ed1\u5b9a\u94f6\u884c\u5361",
        "\u94f6\u884c\u5361",
        "\u652f\u4ed8\u5bc6\u7801",
        "\u4ea4\u6613\u5bc6\u7801",
        "\u8d44\u91d1\u5bc6\u7801",
        "\u5237\u8138\u652f\u4ed8",
        "\u4eba\u8138\u652f\u4ed8",
        "\u63d0\u73b0",
        "\u501f\u6b3e",
        "\u8d37\u6b3e",
        "\u4e70\u5165",
        "\u5356\u51fa",
        "\u7533\u8d2d",
        "\u8d4e\u56de",
        "\u8bc1\u5238\u4ea4\u6613",
        "pay",
        "payment",
        "transfer",
        "purchase",
        "checkout",
        "submit order",
        "order submit",
        "delete account",
        "logout",
        "password",
        "bank card",
        "trade",
        "buy stock",
        "sell stock",
        "loan",
        "withdraw"
    )

    fun evaluateNode(node: NodeSample): SafetyDecision {
        val haystack = listOfNotNull(
            node.text,
            node.contentDesc,
            node.resourceId,
            node.className
        ).joinToString(" ")
        return evaluateText(haystack)
    }

    fun evaluateTemplate(template: MacroTemplate): SafetyDecision {
        val atoms = template.atoms.joinToString(" ") { atom ->
            when (atom) {
                is MacroAtom.ClickElement -> atom.elementId
                is MacroAtom.FocusTextInput -> "${atom.targetElementId} ${atom.toggleElementIds.joinToString(" ")}"
                is MacroAtom.TypeText -> atom.targetElementId.orEmpty()
                is MacroAtom.SearchInApp -> atom.searchEntryElementId
                is MacroAtom.SelectItem -> atom.listElementId.orEmpty()
                is MacroAtom.ScrollUntilVisible -> "${atom.targetTextParam} ${atom.listElementId.orEmpty()}"
                is MacroAtom.EnsureScreen -> atom.screenId
                is MacroAtom.OpenApp -> atom.packageName
                is MacroAtom.WaitFor -> atom.rule.toString()
                is MacroAtom.Verify -> atom.rule.toString()
            }
        }
        return evaluateText("${template.name} ${template.intentId} $atoms")
    }

    fun evaluateText(text: String?): SafetyDecision {
        if (text.isNullOrBlank()) return SafetyDecision(allowed = true, reason = "No hard-blocked text")
        val normalized = text.lowercase()
        val keyword = hardBlockedKeywords.firstOrNull { normalized.contains(it.lowercase()) }
        return if (keyword == null) {
            SafetyDecision(allowed = true, reason = "No hard-blocked keyword matched")
        } else {
            SafetyDecision(
                allowed = false,
                reason = "Hard-blocked keyword matched: $keyword",
                matchedKeyword = keyword
            )
        }
    }
}

data class SafetyDecision(
    val allowed: Boolean,
    val reason: String,
    val matchedKeyword: String? = null
)
