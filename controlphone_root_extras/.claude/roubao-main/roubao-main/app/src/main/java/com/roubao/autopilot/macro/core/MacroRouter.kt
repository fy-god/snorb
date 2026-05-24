package com.roubao.autopilot.macro.core

import com.roubao.autopilot.agent.AgentResult
import com.roubao.autopilot.agent.RunMode
import com.roubao.autopilot.macro.ai.AiAssistPolicy

class MacroRouter(
    private val engine: MacroEngine,
    private val fullAiRunner: suspend (String) -> AgentResult,
    private val isAiAvailable: () -> Boolean,
    private val allowFullAiFallback: () -> Boolean,
    private val eventLogger: MacroEventLogger
) {
    suspend fun run(instruction: String, mode: RunMode): AgentResult {
        if (mode == RunMode.AI_ONLY) {
            if (!isAiAvailable()) {
                return AgentResult(false, "AI mode requested, but AI is not available")
            }
            eventLogger.emit("[AI_ONLY] full AI runner")
            return fullAiRunner(instruction)
        }

        val aiPolicy = when (mode) {
            RunMode.LOCAL_ONLY -> AiAssistPolicy.NoAi
            RunMode.AUTO -> AiAssistPolicy.LocalFirstAssistOnly(
                maxPerceptionCalls = 1,
                maxGeneralizeCalls = 1,
                maxVerifyCalls = 1,
                allowFullAiFallback = false
            )
            RunMode.AI_ONLY -> AiAssistPolicy.FullAiAllowed
        }

        val result = engine.execute(
            instruction = instruction,
            aiPolicy = aiPolicy
        )

        if (result.success) {
            return AgentResult(true, result.message)
        }

        if (mode == RunMode.AUTO && allowFullAiFallback() && isAiAvailable()) {
            eventLogger.emit("[AUTO] full AI fallback explicitly enabled")
            return fullAiRunner(instruction)
        }

        eventLogger.emit("[AUTO] full AI fallback disabled")
        return AgentResult(false, result.message)
    }
}
