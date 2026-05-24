package com.macropilot.app.runtime

import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.model.AppUiModule
import com.macropilot.app.model.RecoverAction

class RecoveryPolicy(
    private val inspector: NodeInspector,
    private val actions: DeviceActions,
    private val classifier: ScreenClassifier = ScreenClassifier()
) {
    fun recoverToScreen(screenId: String, appUi: AppUiModule, timeoutMs: Long = 5000L): Boolean {
        if (isScreen(screenId, appUi)) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        val screen = appUi.screens.firstOrNull { it.id == screenId } ?: return false

        for (action in screen.recoverActions) {
            if (System.currentTimeMillis() > deadline) return false
            execute(action, appUi)
            waitBriefly()
            if (isScreen(screenId, appUi)) return true
        }

        return false
    }

    private fun execute(action: RecoverAction, appUi: AppUiModule) {
        when (action) {
            RecoverAction.Back -> actions.back()
            is RecoverAction.ClickElement -> {
                val spec = appUi.elements.firstOrNull { it.id == action.elementId }
                if (spec != null) {
                    actions.clickByMatcher(spec.matchers)
                    return
                }
                val state = inspector.currentState() ?: return
                val element = state.nodes.firstOrNull {
                    it.resourceId?.contains(action.elementId) == true ||
                        it.text?.contains(action.elementId) == true ||
                        it.contentDesc?.contains(action.elementId) == true
                }
                val label = element?.text ?: element?.contentDesc ?: action.elementId
                actions.clickByVisibleText(label)
            }
            is RecoverAction.WaitForText -> {
                val deadline = System.currentTimeMillis() + action.timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val visible = inspector.currentState()?.nodes?.any {
                        it.text?.contains(action.text) == true || it.contentDesc?.contains(action.text) == true
                    } == true
                    if (visible) return
                    waitBriefly()
                }
            }
        }
    }

    private fun isScreen(screenId: String, appUi: AppUiModule): Boolean {
        val state = inspector.currentState() ?: return false
        return classifier.classify(state, appUi)?.screenId == screenId
    }

    private fun waitBriefly() {
        try {
            Thread.sleep(200L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
