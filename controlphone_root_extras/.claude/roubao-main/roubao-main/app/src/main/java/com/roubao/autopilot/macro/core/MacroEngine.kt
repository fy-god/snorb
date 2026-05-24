package com.roubao.autopilot.macro.core

import com.roubao.autopilot.macro.ai.AiAssist
import com.roubao.autopilot.macro.ai.AiAssistPolicy
import com.roubao.autopilot.macro.model.MacroAtom
import com.roubao.autopilot.macro.model.MacroResult
import com.roubao.autopilot.macro.store.AppUiRepository
import com.roubao.autopilot.macro.store.MacroTemplateRepository
import com.roubao.autopilot.skills.UiObserver

class MacroEngine(
    private val intentParser: MacroIntentParser,
    private val uiObserver: UiObserver,
    private val appUiRepo: AppUiRepository,
    private val templateRepo: MacroTemplateRepository,
    private val composer: MacroComposer,
    private val executor: MacroExecutor,
    private val verifier: MacroVerifier,
    private val aiAssist: AiAssist,
    private val eventLogger: MacroEventLogger
) {
    suspend fun execute(
        instruction: String,
        aiPolicy: AiAssistPolicy
    ): MacroResult {
        eventLogger.emit("[MACRO] instruction=$instruction")

        val state = uiObserver.capture()
        eventLogger.emit("[OBSERVE] package=${state.packageName ?: "unknown"} nodes=${state.nodes.size}")

        val intent = intentParser.parse(instruction)
            ?: return MacroResult.failed("intent parse failed")

        eventLogger.emit("[INTENT] ${intent.templateId} params=${intent.params}")

        val template = templateRepo.findBest(intent)
            ?: return MacroResult.failed("no MacroTemplate for ${intent.templateId}")

        eventLogger.emit("[TEMPLATE] hit=${template.name}")

        val appUi = appUiRepo.load(template.appPackage)
            ?: return MacroResult.failed("missing App UI module: ${template.appPackage}")

        eventLogger.emit("[APP_UI] loaded=${appUi.appPackage} screens=${appUi.screens.size}")

        val plan = composer.compose(
            template = template,
            params = intent.params,
            currentState = state,
            appUi = appUi
        )

        eventLogger.emit("[COMPOSE] atoms=${plan.atoms.size}")

        val execResult = executor.execute(
            plan = plan,
            appUi = appUi,
            aiPolicy = aiPolicy,
            aiAssist = aiAssist
        )

        if (!execResult.success) {
            eventLogger.emit("[RESULT] failed=${execResult.message}")
            return execResult
        }

        if (plan.atoms.lastOrNull() is MacroAtom.Verify) {
            eventLogger.emit("[VERIFY] final already passed by executor")
            eventLogger.emit("[RESULT] local Macro success")
            return MacroResult.success("local Macro succeeded: ${template.name}")
        }

        val finalState = uiObserver.capture()
        val verify = verifier.verify(template.finalVerify, intent.params, finalState)

        return if (verify.success) {
            eventLogger.emit("[VERIFY] final success")
            eventLogger.emit("[RESULT] local Macro success")
            MacroResult.success("local Macro succeeded: ${template.name}")
        } else {
            eventLogger.emit("[VERIFY] final failed=${verify.reason}")
            MacroResult.failed("executed but verification failed: ${verify.reason}")
        }
    }
}
