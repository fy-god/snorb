package com.roubao.autopilot.skills

/**
 * UiSignature — UI 状态的紧凑签名，用于回放前校验。
 *
 * 不存完整节点树（太大且易变），只存：
 *  - 前台包名
 *  - 关键可交互元素的文字列表
 *  - 结构性哈希（节点类型计数）
 */
data class UiSignature(
    val packageName: String,
    val keyTexts: Set<String>,
    val structHash: Int
) {
    companion object {
        fun from(state: UiState): UiSignature {
            val visible = state.nodes.filter { it.visible }
            val keyTexts = visible
                .filter { it.clickable || it.editable }
                .mapNotNull { it.text ?: it.contentDesc }
                .filter { it.isNotBlank() && it.length <= 50 }
                .toSet()
            val structHash = buildStructHash(visible)
            return UiSignature(
                packageName = state.packageName ?: "",
                keyTexts = keyTexts,
                structHash = structHash
            )
        }

        private fun buildStructHash(nodes: List<UiNode>): Int {
            var hash = 17
            hash = 31 * hash + nodes.count { it.clickable }
            hash = 31 * hash + nodes.count { it.editable }
            hash = 31 * hash + nodes.count { it.scrollable }
            // 按 className 分布计数
            nodes.groupBy { it.className ?: "" }.forEach { (cls, list) ->
                hash = 31 * hash + cls.hashCode()
                hash = 31 * hash + list.size
            }
            return hash
        }
    }
}

/**
 * 签名匹配级别
 */
enum class SignatureMatchLevel {
    /** 相同应用、相同页面结构、相同关键元素 */
    EXACT,
    /** 相同应用、相似页面结构 */
    SIMILAR,
    /** 应用或结构差异过大，不应回放 */
    DIFFERENT
}

/**
 * ReplayGuard — 比较当前 UI 签名与录制时签名，决定能否安全回放。
 *
 * 规则：
 *  - 包名不同 → DIFFERENT（绝对阻断）
 *  - 包名相同 + 关键文字重叠 >= 60% + 结构哈希一致 → EXACT
 *  - 包名相同 + 关键文字重叠 >= 30% → SIMILAR
 *  - 其余 → DIFFERENT
 */
object ReplayGuard {

    private const val EXACT_TEXT_OVERLAP = 0.6f
    private const val SIMILAR_TEXT_OVERLAP = 0.3f
    private const val TAG = "[ReplayGuard]"

    fun check(recorded: UiSignature, current: UiSignature): SignatureMatchLevel {
        // 包名不同 → 直接拒绝
        if (recorded.packageName.isNotBlank() &&
            current.packageName.isNotBlank() &&
            recorded.packageName != current.packageName
        ) {
            println("$TAG 包名不匹配: recorded=${recorded.packageName} current=${current.packageName}")
            return SignatureMatchLevel.DIFFERENT
        }

        val textOverlap = if (recorded.keyTexts.isEmpty()) {
            1.0f // 无关键文字 → 只看结构
        } else {
            val intersection = recorded.keyTexts.intersect(current.keyTexts).size.toFloat()
            intersection / recorded.keyTexts.size.toFloat()
        }

        val sameStruct = recorded.structHash == current.structHash

        println(
            "$TAG textOverlap=$textOverlap structMatch=$sameStruct " +
            "recordedTexts=${recorded.keyTexts.take(5)} currentTexts=${current.keyTexts.take(5)}"
        )

        return when {
            textOverlap >= EXACT_TEXT_OVERLAP && sameStruct -> SignatureMatchLevel.EXACT
            textOverlap >= SIMILAR_TEXT_OVERLAP -> SignatureMatchLevel.SIMILAR
            else -> SignatureMatchLevel.DIFFERENT
        }
    }
}
