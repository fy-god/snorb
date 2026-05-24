package com.macropilot.app.runtime

import com.macropilot.app.factory.PromotionGate
import com.macropilot.app.ai.AiAssistPolicy
import com.macropilot.app.ai.AiAssistRequest
import com.macropilot.app.ai.AiAssistStatus
import com.macropilot.app.ai.AiAssistUseCase
import com.macropilot.app.model.CapabilityProfile
import com.macropilot.app.model.CoordinateFallback
import com.macropilot.app.model.FailureCluster
import com.macropilot.app.model.InputCapability
import com.macropilot.app.model.MacroAtom
import com.macropilot.app.model.MacroParam
import com.macropilot.app.model.MacroStatus
import com.macropilot.app.model.MacroTemplate
import com.macropilot.app.model.NodeReadability
import com.macropilot.app.model.PromotionStatus
import com.macropilot.app.model.RuntimeTaskRequirements
import com.macropilot.app.model.ServiceState
import com.macropilot.app.model.SideEffectLevel
import com.macropilot.app.model.SkillCandidate
import com.macropilot.app.model.SkillSource
import com.macropilot.app.model.SkillTestReport
import com.macropilot.app.model.VerificationCapability
import com.macropilot.app.model.VerifyRule
import com.macropilot.app.model.VerifyRuleType
import org.json.JSONArray
import org.json.JSONObject

class ArchitectureSelfCheck(
    private val runtimeGate: RuntimeGate = RuntimeGate(),
    private val promotionGate: PromotionGate = PromotionGate(),
    private val aiAssistPolicy: AiAssistPolicy = AiAssistPolicy()
) {
    fun run(): ArchitectureSelfCheckReport {
        val checks = listOf(
            candidateSkillBlocked(),
            capabilityDBlocked(),
            missingInputBlocked(),
            capabilityCNeedsExplicitLowConfidence(),
            dangerousTemplateRejected(),
            promotionRequiresTenRuns(),
            phoneSideRuntimeAiAllowedWhenAuditable(),
            unredactedAiBlocked(),
            invisibleAiBlocked()
        )
        return ArchitectureSelfCheckReport(checks)
    }

    private fun candidateSkillBlocked(): ArchitectureCheck {
        val decision = runtimeGate.evaluate(
            profile = profile(NodeReadability.A, InputCapability.SET_TEXT, VerificationCapability.HIGH),
            requirements = RuntimeTaskRequirements(needsTextInput = false),
            skillSource = SkillSource.CANDIDATE,
            staticSafetyText = "safe candidate"
        )
        return ArchitectureCheck(
            name = "runtime_rejects_candidate",
            passed = !decision.allowed && decision.blockedStatus == MacroStatus.FAILED_PRECONDITION,
            detail = decision.reason
        )
    }

    private fun capabilityDBlocked(): ArchitectureCheck {
        val decision = runtimeGate.evaluate(
            profile = profile(NodeReadability.D, InputCapability.NONE, VerificationCapability.NONE),
            requirements = RuntimeTaskRequirements(needsTextInput = false),
            skillSource = SkillSource.APPROVED,
            staticSafetyText = "safe approved"
        )
        return ArchitectureCheck(
            name = "runtime_rejects_capability_d",
            passed = !decision.allowed && decision.blockedStatus == MacroStatus.FAILED_CAPABILITY_D,
            detail = decision.reason
        )
    }

    private fun missingInputBlocked(): ArchitectureCheck {
        val decision = runtimeGate.evaluate(
            profile = profile(NodeReadability.A, InputCapability.NONE, VerificationCapability.HIGH),
            requirements = RuntimeTaskRequirements(needsTextInput = true),
            skillSource = SkillSource.APPROVED,
            staticSafetyText = "safe text task"
        )
        return ArchitectureCheck(
            name = "runtime_rejects_missing_input_channel",
            passed = !decision.allowed && decision.blockedStatus == MacroStatus.FAILED_INPUT_CHANNEL_MISSING,
            detail = decision.reason
        )
    }

    private fun capabilityCNeedsExplicitLowConfidence(): ArchitectureCheck {
        val decision = runtimeGate.evaluate(
            profile = profile(NodeReadability.C, InputCapability.NONE, VerificationCapability.LOW),
            requirements = RuntimeTaskRequirements(needsTextInput = false, allowLowConfidence = false),
            skillSource = SkillSource.APPROVED,
            staticSafetyText = "safe coordinate task"
        )
        return ArchitectureCheck(
            name = "runtime_rejects_implicit_capability_c",
            passed = !decision.allowed && decision.blockedStatus == MacroStatus.FAILED_UNVERIFIABLE,
            detail = decision.reason
        )
    }

    private fun dangerousTemplateRejected(): ArchitectureCheck {
        val report = report(totalRuns = 10, success = 10)
        val decision = promotionGate.evaluate(
            candidate = SkillCandidate(
                id = "candidate_delete",
                template = template(
                    id = "delete_account",
                    name = "Delete account",
                    atoms = listOf(MacroAtom.ClickElement("delete_button"))
                )
            ),
            report = report
        )
        return ArchitectureCheck(
            name = "promotion_rejects_dangerous_template",
            passed = !decision.approved && decision.status == PromotionStatus.REJECTED,
            detail = decision.reason
        )
    }

    private fun promotionRequiresTenRuns(): ArchitectureCheck {
        val decision = promotionGate.evaluate(
            candidate = SkillCandidate(
                id = "candidate_safe",
                template = template(id = "safe_skill", name = "Safe skill")
            ),
            report = report(totalRuns = 1, success = 1)
        )
        return ArchitectureCheck(
            name = "promotion_requires_ten_runs",
            passed = !decision.approved && decision.status == PromotionStatus.REJECTED,
            detail = decision.reason
        )
    }

    private fun phoneSideRuntimeAiAllowedWhenAuditable(): ArchitectureCheck {
        val response = aiAssistPolicy.evaluate(aiRequest(runtimePath = true, redacted = true, userVisible = true))
        return ArchitectureCheck(
            name = "phone_side_runtime_ai_allowed_when_redacted_and_visible",
            passed = response == null,
            detail = response?.message ?: "phone-side runtime AI request is allowed by policy when redacted and user-visible"
        )
    }

    private fun unredactedAiBlocked(): ArchitectureCheck {
        val response = aiAssistPolicy.evaluate(aiRequest(runtimePath = false, redacted = false, userVisible = true))
        return ArchitectureCheck(
            name = "ai_requires_redacted_input",
            passed = response?.status == AiAssistStatus.BLOCKED,
            detail = response?.message ?: "AI request was not blocked"
        )
    }

    private fun invisibleAiBlocked(): ArchitectureCheck {
        val response = aiAssistPolicy.evaluate(aiRequest(runtimePath = false, redacted = true, userVisible = false))
        return ArchitectureCheck(
            name = "ai_requires_user_visible_request",
            passed = response?.status == AiAssistStatus.BLOCKED,
            detail = response?.message ?: "AI request was not blocked"
        )
    }

    private fun profile(
        readability: NodeReadability,
        input: InputCapability,
        verification: VerificationCapability
    ): CapabilityProfile {
        return CapabilityProfile(
            nodeReadability = readability,
            inputCapability = input,
            verificationCapability = verification,
            coordinateFallback = CoordinateFallback.AVAILABLE,
            serviceState = ServiceState.CONNECTED,
            reason = "self-check profile"
        )
    }

    private fun template(
        id: String,
        name: String,
        atoms: List<MacroAtom> = emptyList()
    ): MacroTemplate {
        val verify = VerifyRule(type = VerifyRuleType.ALWAYS, minVerification = VerificationCapability.LOW)
        return MacroTemplate(
            id = id,
            name = name,
            packageName = "com.example.safe",
            intentId = id,
            params = listOf(MacroParam("value", required = false)),
            atoms = atoms,
            finalVerify = verify,
            source = SkillSource.APPROVED,
            sideEffectLevel = SideEffectLevel.LOW,
            idempotent = true
        )
    }

    private fun report(totalRuns: Int, success: Int): SkillTestReport {
        return SkillTestReport(
            templateId = "self_check",
            totalRuns = totalRuns,
            success = success,
            mediumConfidence = 0,
            lowConfidence = 0,
            failed = totalRuns - success,
            timeout = 0,
            avgDurationMs = 100,
            p95DurationMs = 100,
            failureClusters = if (success == totalRuns) {
                emptyList()
            } else {
                listOf(FailureCluster(reason = "self-check synthetic failure", count = totalRuns - success))
            }
        )
    }

    private fun aiRequest(
        runtimePath: Boolean,
        redacted: Boolean,
        userVisible: Boolean
    ): AiAssistRequest {
        return AiAssistRequest(
            useCase = AiAssistUseCase.UI_MODULE_PATCH,
            inputJson = JSONObject().put("sample", "self_check"),
            redacted = redacted,
            userVisible = userVisible,
            runtimePath = runtimePath
        )
    }
}

data class ArchitectureSelfCheckReport(
    val checks: List<ArchitectureCheck>
) {
    val passed: Boolean = checks.all { it.passed }

    fun compact(): String {
        val failed = checks.filterNot { it.passed }.joinToString(",") { it.name }
        return if (passed) {
            "passed=${checks.size}/${checks.size}"
        } else {
            "passed=${checks.count { it.passed }}/${checks.size} failed=$failed"
        }
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "architecture_self_check_report")
            .put("passed", passed)
            .put("passedCount", checks.count { it.passed })
            .put("total", checks.size)
            .put("checks", JSONArray(checks.map { it.toJson() }))
            .put("timestamp", System.currentTimeMillis())
    }
}

data class ArchitectureCheck(
    val name: String,
    val passed: Boolean,
    val detail: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("passed", passed)
            .put("detail", detail)
    }
}
