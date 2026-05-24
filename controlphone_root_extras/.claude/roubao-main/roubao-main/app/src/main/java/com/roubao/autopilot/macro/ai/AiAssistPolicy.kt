package com.roubao.autopilot.macro.ai

sealed class AiAssistPolicy {
    object NoAi : AiAssistPolicy()

    data class LocalFirstAssistOnly(
        val maxPerceptionCalls: Int,
        val maxGeneralizeCalls: Int,
        val maxVerifyCalls: Int,
        val allowFullAiFallback: Boolean,
        var perceptionCalls: Int = 0,
        var generalizeCalls: Int = 0,
        var verifyCalls: Int = 0
    ) : AiAssistPolicy()

    object FullAiAllowed : AiAssistPolicy()

    fun canUsePerception(): Boolean {
        return when (this) {
            is LocalFirstAssistOnly -> perceptionCalls < maxPerceptionCalls
            FullAiAllowed -> true
            NoAi -> false
        }
    }

    fun recordPerception() {
        if (this is LocalFirstAssistOnly) perceptionCalls += 1
    }

    fun canGeneralize(): Boolean {
        return when (this) {
            is LocalFirstAssistOnly -> generalizeCalls < maxGeneralizeCalls
            FullAiAllowed -> true
            NoAi -> false
        }
    }

    fun recordGeneralize() {
        if (this is LocalFirstAssistOnly) generalizeCalls += 1
    }

    fun canVerify(): Boolean {
        return when (this) {
            is LocalFirstAssistOnly -> verifyCalls < maxVerifyCalls
            FullAiAllowed -> true
            NoAi -> false
        }
    }

    fun recordVerify() {
        if (this is LocalFirstAssistOnly) verifyCalls += 1
    }

    fun fullAiFallbackAllowed(): Boolean {
        return when (this) {
            is LocalFirstAssistOnly -> allowFullAiFallback
            FullAiAllowed -> true
            NoAi -> false
        }
    }
}
