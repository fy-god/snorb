package com.roubao.autopilot.macro.ai

import com.roubao.autopilot.macro.model.AppUiModule
import com.roubao.autopilot.macro.model.ResolvedElement
import com.roubao.autopilot.macro.model.UiElementSpec
import com.roubao.autopilot.macro.model.VerifyResult
import com.roubao.autopilot.macro.record.RecordedTrace
import com.roubao.autopilot.macro.record.MacroTemplateDraft
import com.roubao.autopilot.skills.UiState

interface AiAssist {
    suspend fun findElement(
        elementName: String,
        screenState: UiState,
        appUi: AppUiModule
    ): ResolvedElement?

    suspend fun generalizeTrace(trace: RecordedTrace): MacroTemplateDraft?

    suspend fun repairUiModule(
        appUi: AppUiModule,
        failedElementId: String,
        screenState: UiState
    ): UiElementSpec?

    suspend fun verifyResult(
        instruction: String,
        screenState: UiState
    ): VerifyResult
}

object NoopAiAssist : AiAssist {
    override suspend fun findElement(
        elementName: String,
        screenState: UiState,
        appUi: AppUiModule
    ): ResolvedElement? = null

    override suspend fun generalizeTrace(trace: RecordedTrace): MacroTemplateDraft? = null

    override suspend fun repairUiModule(
        appUi: AppUiModule,
        failedElementId: String,
        screenState: UiState
    ): UiElementSpec? = null

    override suspend fun verifyResult(
        instruction: String,
        screenState: UiState
    ): VerifyResult = VerifyResult.failed("AI assist is not configured")
}
