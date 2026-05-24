package com.roubao.autopilot.macro.core

import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.macro.ai.AiAssist
import com.roubao.autopilot.macro.ai.AiAssistPolicy
import com.roubao.autopilot.macro.model.AppUiModule
import com.roubao.autopilot.macro.model.ResolvedElement
import com.roubao.autopilot.macro.model.UiElementSpec
import com.roubao.autopilot.skills.UiObserver
import com.roubao.autopilot.skills.UiState

class UiElementResolver(
    private val controller: DeviceController,
    private val uiObserver: UiObserver,
    private val logger: MacroEventLogger
) {
    suspend fun resolve(
        elementId: String,
        appUi: AppUiModule,
        aiPolicy: AiAssistPolicy,
        aiAssist: AiAssist
    ): ResolvedElement? {
        val state = uiObserver.capture()
        val spec = appUi.findElement(elementId) ?: return null

        findByAccessibility(spec, state)?.let {
            logger.emit("[RESOLVE] $elementId source=Accessibility")
            return it
        }

        spec.coordinatePack?.let { pack ->
            val (width, height) = controller.getScreenSize()
            val point = pack.toRealPoint(width, height)
            logger.emit("[RESOLVE] $elementId source=CoordinatePack")
            return ResolvedElement.Coordinate(point.first, point.second, confidence = pack.confidence)
        }

        if (aiPolicy.canUsePerception()) {
            aiPolicy.recordPerception()
            val aiResult = aiAssist.findElement(
                elementName = spec.name,
                screenState = state,
                appUi = appUi
            )

            if (aiResult != null) {
                logger.emit("[AI_ASSIST] FIND_ELEMENT accepted: ${spec.name}")
                logger.emit("[RESOLVE] $elementId source=AI Assist")
                return aiResult
            }
        }

        logger.emit("[RESOLVE] $elementId failed")
        return null
    }

    fun findText(text: String, state: UiState): ResolvedElement? {
        return state.nodes
            .filter { it.visible }
            .firstOrNull { node ->
                node.text?.contains(text) == true || node.contentDesc?.contains(text) == true
            }
            ?.let { ResolvedElement.Node(it, confidence = 0.90f) }
    }

    private fun findByAccessibility(spec: UiElementSpec, state: UiState): ResolvedElement? {
        return state.nodes
            .asSequence()
            .filter { it.visible && it.enabled }
            .firstOrNull { node -> spec.matchers.any { it.matches(node) } }
            ?.let { ResolvedElement.Node(it, confidence = spec.confidence) }
    }
}
