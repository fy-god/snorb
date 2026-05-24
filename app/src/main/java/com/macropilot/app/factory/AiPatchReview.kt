package com.macropilot.app.factory

import android.content.Context
import com.macropilot.app.model.AppUiModule
import com.macropilot.app.model.CoordinatePack
import com.macropilot.app.model.CoordinateSource
import com.macropilot.app.model.CoordinateStatus
import com.macropilot.app.model.ElementMatcher
import com.macropilot.app.model.ElementStatus
import com.macropilot.app.model.ModuleStatus
import com.macropilot.app.model.NormalizedPoint
import com.macropilot.app.model.NormalizedRect
import com.macropilot.app.model.ReliabilityLevel
import com.macropilot.app.model.UiElementSpec
import com.macropilot.app.model.UiRole
import com.macropilot.app.model.UiScreenModule
import com.macropilot.app.model.UiSignature
import com.macropilot.app.safety.SafeActionPolicy
import com.macropilot.app.store.AppUiModuleStore
import com.macropilot.app.store.CandidateKnowledgeStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AiPatchReview(
    private val context: Context,
    private val safetyPolicy: SafeActionPolicy = SafeActionPolicy(),
    private val appUiStore: AppUiModuleStore = AppUiModuleStore(context),
    private val candidateStore: CandidateKnowledgeStore = CandidateKnowledgeStore(context)
) {
    fun reviewPatch(candidateIdOrPath: String): PatchReviewReport {
        val file = resolvePatchFile(candidateIdOrPath)
            ?: return PatchReviewReport(false, "Patch candidate not found", null, emptyList(), emptyList())
        val candidate = runCatching { JSONObject(file.readText()) }.getOrElse {
            return PatchReviewReport(false, "Patch candidate is not valid JSON", file, emptyList(), emptyList())
        }
        return reviewPatchJson(file, candidate)
    }

    fun applyPatch(candidateIdOrPath: String): PatchApplyResult {
        val review = reviewPatch(candidateIdOrPath)
        if (!review.approved) {
            return PatchApplyResult(false, review.reason, null, review)
        }
        val file = review.file ?: return PatchApplyResult(false, "Patch file missing", null, review)
        val candidate = JSONObject(file.readText())
        val module = buildCandidateModule(candidate)
            ?: return PatchApplyResult(false, "Patch output could not be converted into AppUiModule", null, review)
        val saved = appUiStore.saveCandidate(module, sourceId = file.nameWithoutExtension)
        saveReviewSidecar(saved, file, review)
        return PatchApplyResult(true, "Candidate AppUiModule saved", saved, review)
    }

    fun dryRunCandidateModule(path: String): PatchDryRunReport {
        val module = appUiStore.loadCandidateFile(path)
            ?: return PatchDryRunReport(false, "Candidate module not found or invalid", null, emptyList(), emptyList())
        return dryRun(module, File(path))
    }

    fun listCandidateModules(limit: Int = 100): List<JSONObject> {
        return appUiStore.listCandidates()
            .sortedByDescending { it.first.lastModified() }
            .take(limit.coerceIn(1, 500))
            .map { (file, module) ->
                JSONObject()
                    .put("file", file.absolutePath)
                    .put("packageName", module.packageName)
                    .put("appName", module.appName)
                    .put("screens", module.screens.size)
                    .put("elements", module.elements.size)
                    .put("status", module.status.name)
                    .put("updatedAt", module.updatedAt)
            }
    }

    private fun reviewPatchJson(file: File, candidate: JSONObject): PatchReviewReport {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        if (candidate.optString("type") != "AiUiModulePatchCandidate") {
            errors += "Not an AiUiModulePatchCandidate"
        }
        if (candidate.optBoolean("runtimeExecutable", true)) {
            errors += "Patch candidate must not be runtimeExecutable"
        }
        if (!candidate.optString("redactionStatus").contains("redacted", ignoreCase = true)) {
            errors += "Patch candidate is not marked redacted"
        }
        if (!candidate.optBoolean("candidateUsable", false)) {
            errors += "Patch candidate is not usable yet"
        }

        val output = candidate.optJSONObject("output")
        if (output == null) {
            errors += "Patch has no AI output JSON"
        } else {
            val safety = safetyPolicy.evaluateText(output.toString())
            if (!safety.allowed) {
                errors += safety.reason
            }
            if (output.toString().contains("runtimeExecutable\":true", ignoreCase = true)) {
                errors += "AI output attempted to mark runtimeExecutable=true"
            }
            val module = buildCandidateModule(candidate)
            if (module == null) {
                errors += "No valid screens or elements in patch output"
            } else {
                val dryRun = dryRun(module, null)
                errors += dryRun.errors
                warnings += dryRun.warnings
            }
        }

        val approved = errors.isEmpty()
        return PatchReviewReport(
            approved = approved,
            reason = if (approved) "Patch review passed" else errors.joinToString("; "),
            file = file,
            warnings = warnings,
            errors = errors
        )
    }

    private fun dryRun(module: AppUiModule, file: File?): PatchDryRunReport {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        if (module.packageName.isBlank()) errors += "packageName is blank"
        if (module.screens.isEmpty() && module.elements.isEmpty()) {
            errors += "module has no screens and no elements"
        }
        module.screens.forEach { screen ->
            if (screen.id.isBlank()) errors += "screen id is blank"
            if (screen.signatures.isEmpty()) warnings += "screen ${screen.id} has no signature"
            screen.signatures.forEach { signature ->
                if (signature.packageName != module.packageName) {
                    warnings += "screen ${screen.id} signature package differs from module package"
                }
            }
        }
        module.elements.forEach { element ->
            if (element.id.isBlank()) errors += "element id is blank"
            if (element.matchers.isEmpty() && element.coordinatePack == null) {
                errors += "element ${element.id} has no matcher and no coordinate fallback"
            }
            val safety = safetyPolicy.evaluateText("${element.id} ${element.name} ${element.matchers}")
            if (!safety.allowed) errors += safety.reason
            if (element.coordinatePack != null && element.matchers.isEmpty()) {
                warnings += "element ${element.id} is coordinate-only and cannot become high confidence"
            }
        }
        return PatchDryRunReport(
            passed = errors.isEmpty(),
            reason = if (errors.isEmpty()) "Dry run passed" else errors.joinToString("; "),
            file = file,
            warnings = warnings,
            errors = errors
        )
    }

    private fun buildCandidateModule(candidate: JSONObject): AppUiModule? {
        val output = candidate.optJSONObject("output") ?: return null
        val packageName = output.optString("packageName")
            .ifBlank { candidate.optString("packageName") }
            .ifBlank { return null }
        val screens = extractScreens(output, packageName)
        val elements = extractElements(output)
        if (screens.isEmpty() && elements.isEmpty()) return null
        return AppUiModule(
            schemaVersion = 1,
            packageName = packageName,
            appName = output.optString("appName", packageName),
            screens = screens,
            elements = elements,
            status = ModuleStatus.DEGRADED,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun extractScreens(output: JSONObject, packageName: String): List<UiScreenModule> {
        val direct = output.optJSONArray("screens")
            ?: output.optJSONArray("screenProposals")
            ?: collectOps(output, "add_screen", "screen")
        return direct.toJsonObjects().mapNotNull { json ->
            val id = cleanId(json.optString("id", json.optString("screenId", "screen_unknown")))
            val signatures = json.optJSONArray("signatures").toJsonObjects().map { signatureJson ->
                UiSignature(
                    packageName = signatureJson.optString("packageName", packageName).ifBlank { packageName },
                    activityName = signatureJson.optNullableString("activityName"),
                    requiredTextsAny = signatureJson.optJSONArray("requiredTextsAny").toStringList(),
                    requiredTextsAll = signatureJson.optJSONArray("requiredTextsAll").toStringList(),
                    requiredResourceIdsAny = signatureJson.optJSONArray("requiredResourceIdsAny").toStringList(),
                    requiredRolesAny = signatureJson.optJSONArray("requiredRolesAny").toStringList().map { enumValue(it, UiRole.UNKNOWN) },
                    negativeTexts = signatureJson.optJSONArray("negativeTexts").toStringList(),
                    minScore = signatureJson.optDouble("minScore", 0.75).toFloat()
                )
            }.ifEmpty {
                listOf(
                    UiSignature(
                        packageName = packageName,
                        requiredTextsAny = json.optJSONArray("requiredTextsAny").toStringList()
                            .ifEmpty { json.optJSONArray("texts").toStringList() }
                            .take(8),
                        requiredResourceIdsAny = json.optJSONArray("requiredResourceIdsAny").toStringList().take(8),
                        minScore = json.optDouble("minScore", 0.70).toFloat()
                    )
                )
            }
            UiScreenModule(
                id = id,
                name = json.optString("name", id),
                signatures = signatures
            )
        }
    }

    private fun extractElements(output: JSONObject): List<UiElementSpec> {
        val direct = output.optJSONArray("elements")
            ?: output.optJSONArray("elementProposals")
            ?: collectOps(output, "add_element", "element")
        return direct.toJsonObjects().mapNotNull { json ->
            val id = cleanId(json.optString("id", json.optString("elementId", "element_unknown")))
            val matchers = parseMatchers(json)
            val pack = parseCoordinatePack(json.optJSONObject("coordinatePack"))
            UiElementSpec(
                id = id,
                name = json.optString("name", id),
                role = enumValue(json.optString("role"), UiRole.UNKNOWN),
                matchers = matchers,
                coordinatePack = pack,
                requiredScreenId = json.optNullableString("requiredScreenId") ?: json.optNullableString("screenId"),
                reliability = enumValue(json.optString("reliability"), ReliabilityLevel.LOW),
                samples = 0,
                successRate = 0f,
                status = ElementStatus.DEGRADED
            )
        }
    }

    private fun parseMatchers(json: JSONObject): List<ElementMatcher> {
        val matcherArray = json.optJSONArray("matchers")
        val fromArray = matcherArray.toJsonObjects().map { matcher ->
            ElementMatcher(
                text = matcher.optNullableString("text"),
                textContains = matcher.optNullableString("textContains"),
                contentDesc = matcher.optNullableString("contentDesc"),
                contentDescContains = matcher.optNullableString("contentDescContains"),
                resourceId = matcher.optNullableString("resourceId"),
                className = matcher.optNullableString("className"),
                clickable = matcher.optNullableBoolean("clickable"),
                editable = matcher.optNullableBoolean("editable"),
                scrollable = matcher.optNullableBoolean("scrollable"),
                enabled = matcher.optNullableBoolean("enabled")
            )
        }
        if (fromArray.isNotEmpty()) return fromArray
        val inline = ElementMatcher(
            text = json.optNullableString("text"),
            textContains = json.optNullableString("textContains"),
            contentDesc = json.optNullableString("contentDesc"),
            contentDescContains = json.optNullableString("contentDescContains"),
            resourceId = json.optNullableString("resourceId"),
            className = json.optNullableString("className"),
            clickable = json.optNullableBoolean("clickable"),
            editable = json.optNullableBoolean("editable"),
            scrollable = json.optNullableBoolean("scrollable"),
            enabled = json.optNullableBoolean("enabled")
        )
        val hasAny = listOf(
            inline.text,
            inline.textContains,
            inline.contentDesc,
            inline.contentDescContains,
            inline.resourceId,
            inline.className,
            inline.clickable,
            inline.editable,
            inline.scrollable,
            inline.enabled
        ).any { it != null }
        return if (hasAny) listOf(inline) else emptyList()
    }

    private fun parseCoordinatePack(json: JSONObject?): CoordinatePack? {
        if (json == null) return null
        val fallback = json.optJSONObject("fallbackTap") ?: json.optJSONObject("point") ?: return null
        val bounds = json.optJSONObject("normalizedBounds")
        val point = NormalizedPoint(
            x = fallback.optDouble("x", 0.5).toFloat(),
            y = fallback.optDouble("y", 0.5).toFloat()
        )
        return CoordinatePack(
            baseWidth = json.optInt("baseWidth", 0),
            baseHeight = json.optInt("baseHeight", 0),
            normalizedBounds = NormalizedRect(
                left = bounds?.optDouble("left", point.x.toDouble())?.toFloat() ?: point.x,
                top = bounds?.optDouble("top", point.y.toDouble())?.toFloat() ?: point.y,
                right = bounds?.optDouble("right", point.x.toDouble())?.toFloat() ?: point.x,
                bottom = bounds?.optDouble("bottom", point.y.toDouble())?.toFloat() ?: point.y
            ),
            fallbackTap = point,
            confidence = json.optDouble("confidence", 0.0).toFloat(),
            source = enumValue(json.optString("source"), CoordinateSource.AI_ASSISTED),
            status = enumValue(json.optString("status"), CoordinateStatus.NEEDS_RECALIBRATION)
        )
    }

    private fun collectOps(output: JSONObject, opName: String, payloadName: String): JSONArray? {
        val ops = output.optJSONArray("ops") ?: return null
        val result = JSONArray()
        for (index in 0 until ops.length()) {
            val op = ops.optJSONObject(index) ?: continue
            if (op.optString("op") == opName || op.optString("type") == opName) {
                op.optJSONObject(payloadName)?.let { result.put(it) }
            }
        }
        return result.takeIf { it.length() > 0 }
    }

    private fun resolvePatchFile(idOrPath: String): File? {
        if (idOrPath.isBlank()) return null
        val direct = File(idOrPath)
        if (direct.isFile) return direct
        candidateStore.ensureCreated()
        val safe = idOrPath.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".json")
        val file = File(candidateStore.candidatePatchDir, "$safe.json")
        return file.takeIf { it.isFile }
    }

    private fun saveReviewSidecar(moduleFile: File, patchFile: File, review: PatchReviewReport) {
        File(moduleFile.parentFile, "${moduleFile.nameWithoutExtension}.review.json").writeText(
            JSONObject()
                .put("sourcePatch", patchFile.absolutePath)
                .put("approved", review.approved)
                .put("reason", review.reason)
                .put("warnings", JSONArray(review.warnings))
                .put("errors", JSONArray(review.errors))
                .put("runtimeExecutable", false)
                .put("createdAt", System.currentTimeMillis())
                .toString(2)
        )
    }

    private fun cleanId(raw: String): String {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
        return cleaned.ifBlank { "unknown" }
    }

    private fun JSONArray?.toJsonObjects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optJSONObject(index) }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableBoolean(name: String): Boolean? {
        if (!has(name) || isNull(name)) return null
        return optBoolean(name)
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T {
        if (name.isNullOrBlank()) return fallback
        return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
    }
}

data class PatchReviewReport(
    val approved: Boolean,
    val reason: String,
    val file: File?,
    val warnings: List<String>,
    val errors: List<String>
)

data class PatchApplyResult(
    val applied: Boolean,
    val reason: String,
    val moduleFile: File?,
    val review: PatchReviewReport
)

data class PatchDryRunReport(
    val passed: Boolean,
    val reason: String,
    val file: File?,
    val warnings: List<String>,
    val errors: List<String>
)
