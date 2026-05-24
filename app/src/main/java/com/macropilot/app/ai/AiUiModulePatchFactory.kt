package com.macropilot.app.ai

import android.content.Context
import android.graphics.Rect
import com.macropilot.app.model.NodeSample
import com.macropilot.app.model.UiStateSample
import com.macropilot.app.privacy.PrivacyRedactor
import com.macropilot.app.runtime.NodeInspector
import com.macropilot.app.store.CandidateKnowledgeStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AiUiModulePatchFactory(
    private val context: Context,
    private val inspector: NodeInspector = NodeInspector(context),
    private val aiClient: AiAssistClient = AiAssistClient(context),
    private val redactor: PrivacyRedactor = PrivacyRedactor(),
    private val candidateStore: CandidateKnowledgeStore = CandidateKnowledgeStore(context)
) {
    fun suggestPatch(
        task: String,
        screenHint: String?,
        elementGoal: String?,
        maxNodes: Int = 120
    ): AiPatchSuggestionResult {
        val state = inspector.currentState()
            ?: return AiPatchSuggestionResult(
                ok = false,
                status = AiAssistStatus.FAILED,
                message = "No Accessibility UiState available",
                file = null
            )
        val redacted = redactor.redact(state)
        val input = buildInputJson(redacted, task, screenHint, elementGoal, maxNodes)
        val request = AiAssistRequest(
            useCase = AiAssistUseCase.UI_MODULE_PATCH,
            inputJson = input,
            redacted = true,
            userVisible = true,
            runtimePath = false
        )
        val response = aiClient.request(request)
        val evidenceNodeCount = input.optJSONArray("nodes")?.length() ?: 0
        val candidateUsable = response.status == AiAssistStatus.SUCCESS &&
            response.outputJson != null &&
            evidenceNodeCount > 0
        val file = saveCandidate(
            state = redacted,
            input = input,
            response = response,
            task = task,
            screenHint = screenHint,
            elementGoal = elementGoal,
            candidateUsable = candidateUsable,
            evidenceNodeCount = evidenceNodeCount
        )
        return AiPatchSuggestionResult(
            ok = candidateUsable,
            status = response.status,
            message = if (candidateUsable) {
                response.message
            } else if (response.status == AiAssistStatus.SUCCESS) {
                "AI returned output, but current node evidence is insufficient for a usable patch"
            } else {
                response.message
            },
            file = file
        )
    }

    private fun buildInputJson(
        state: UiStateSample,
        task: String,
        screenHint: String?,
        elementGoal: String?,
        maxNodes: Int
    ): JSONObject {
        val selectedNodes = state.nodes
            .filter { node ->
                node.enabled &&
                    (node.text?.isNotBlank() == true ||
                        node.contentDesc?.isNotBlank() == true ||
                        node.resourceId?.isNotBlank() == true ||
                        node.clickable ||
                        node.editable ||
                        node.scrollable)
            }
            .sortedWith(
                compareByDescending<NodeSample> { it.editable }
                    .thenByDescending { it.clickable }
                    .thenBy { it.depth }
            )
            .take(maxNodes.coerceIn(20, 300))

        return JSONObject()
            .put("system", "MacroPilot Skill Factory")
            .put("task", task.ifBlank { "Generate a UI module patch proposal from the current Accessibility node tree" })
            .put("instructions", patchInstructions())
            .put("screenHint", screenHint)
            .put("elementGoal", elementGoal)
            .put("state", JSONObject()
                .put("appPackage", state.appPackage)
                .put("windowClassName", state.windowClassName)
                .put("screenWidth", state.screenWidth)
                .put("screenHeight", state.screenHeight)
                .put("timestamp", state.timestamp)
                .put("nodeCount", state.nodes.size))
            .put("nodes", JSONArray(selectedNodes.map { nodeJson(it, state.screenWidth, state.screenHeight) }))
            .put("expectedOutputSchema", JSONObject()
                .put("type", "UiModulePatch")
                .put("ops", JSONArray(listOf("add_screen", "add_element", "update_element_matcher", "add_coordinate_pack")))
                .put("rules", JSONArray(listOf(
                    "Return JSON only",
                    "Do not include raw personal data",
                    "Do not propose direct phone actions",
                    "Do not mark the patch approved",
                    "Use coordinates only as fallback"
                ))))
    }

    private fun patchInstructions(): String {
        return "Given a redacted Android Accessibility node tree, propose a structured AppUiModule patch. " +
            "Prefer stable matchers such as resourceId, className, editable/clickable, text or contentDescription. " +
            "Coordinate packs are fallback only. Output a JSON object with patchId, packageName, screen proposals, " +
            "element proposals, confidence, risks, and verify suggestions."
    }

    private fun nodeJson(node: NodeSample, width: Int, height: Int): JSONObject {
        return JSONObject()
            .put("nodeId", node.nodeId)
            .put("packageName", node.packageName)
            .put("text", node.text)
            .put("contentDesc", node.contentDesc)
            .put("resourceId", node.resourceId)
            .put("className", node.className)
            .put("bounds", rectJson(node.bounds))
            .put("normalizedBounds", normalizedRectJson(node.bounds, width, height))
            .put("clickable", node.clickable)
            .put("editable", node.editable)
            .put("scrollable", node.scrollable)
            .put("focused", node.focused)
            .put("enabled", node.enabled)
            .put("selected", node.selected)
            .put("depth", node.depth)
            .put("parentRoleHint", node.parentRoleHint)
    }

    private fun saveCandidate(
        state: UiStateSample,
        input: JSONObject,
        response: AiAssistResponse,
        task: String,
        screenHint: String?,
        elementGoal: String?,
        candidateUsable: Boolean,
        evidenceNodeCount: Int
    ): File {
        candidateStore.ensureCreated()
        val id = "ai_patch_${System.currentTimeMillis()}"
        val file = File(candidateStore.candidatePatchDir, "$id.json")
        file.writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("id", id)
                .put("type", "AiUiModulePatchCandidate")
                .put("packageName", state.appPackage)
                .put("windowClassName", state.windowClassName)
                .put("task", task)
                .put("screenHint", screenHint)
                .put("elementGoal", elementGoal)
                .put("aiStatus", response.status.name)
                .put("aiMessage", response.message)
                .put("candidateUsable", candidateUsable)
                .put("evidenceNodeCount", evidenceNodeCount)
                .put("redactionStatus", "basic_redacted")
                .put("promotionStatus", "CANDIDATE_ONLY")
                .put("runtimeExecutable", false)
                .put("input", input)
                .put("output", response.outputJson)
                .put("createdAt", System.currentTimeMillis())
                .toString(2)
        )
        return file
    }

    private fun rectJson(rect: Rect): JSONObject {
        return JSONObject()
            .put("left", rect.left)
            .put("top", rect.top)
            .put("right", rect.right)
            .put("bottom", rect.bottom)
    }

    private fun normalizedRectJson(rect: Rect, width: Int, height: Int): JSONObject {
        val safeWidth = width.coerceAtLeast(1).toFloat()
        val safeHeight = height.coerceAtLeast(1).toFloat()
        return JSONObject()
            .put("left", rect.left / safeWidth)
            .put("top", rect.top / safeHeight)
            .put("right", rect.right / safeWidth)
            .put("bottom", rect.bottom / safeHeight)
    }
}

data class AiPatchSuggestionResult(
    val ok: Boolean,
    val status: AiAssistStatus,
    val message: String,
    val file: File?
)
