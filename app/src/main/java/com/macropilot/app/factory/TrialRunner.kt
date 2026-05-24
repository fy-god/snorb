package com.macropilot.app.factory

import com.macropilot.app.model.NodeSample
import com.macropilot.app.safety.SafeActionPolicy

class TrialRunner(
    private val safetyPolicy: SafeActionPolicy = SafeActionPolicy()
) {
    fun rankSafeClickCandidates(nodes: List<NodeSample>): List<NodeSample> {
        return nodes
            .filter { it.enabled && it.clickable }
            .filter { safetyPolicy.evaluateNode(it).allowed }
            .sortedWith(
                compareByDescending<NodeSample> { semanticScore(it) }
                    .thenBy { it.depth }
            )
    }

    private fun semanticScore(node: NodeSample): Int {
        val label = listOfNotNull(node.text, node.contentDesc, node.resourceId).joinToString(" ")
        val safeHints = listOf("搜索", "返回", "取消", "关闭", "首页", "消息", "联系人", "设置", "search", "back", "close")
        return safeHints.count { label.contains(it, ignoreCase = true) }
    }
}
