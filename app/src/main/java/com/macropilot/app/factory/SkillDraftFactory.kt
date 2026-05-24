package com.macropilot.app.factory

import android.content.Context
import com.macropilot.app.model.AppUiModule
import com.macropilot.app.model.MacroAtom
import com.macropilot.app.model.MacroParam
import com.macropilot.app.model.MacroTemplate
import com.macropilot.app.model.SideEffectLevel
import com.macropilot.app.model.SkillSource
import com.macropilot.app.model.UiRole
import com.macropilot.app.model.VerificationCapability
import com.macropilot.app.model.VerifyRule
import com.macropilot.app.model.VerifyRuleType
import com.macropilot.app.safety.SafeActionPolicy
import com.macropilot.app.store.AppUiModuleStore
import com.macropilot.app.store.MacroTemplateStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SkillDraftFactory(
    private val context: Context,
    private val appUiStore: AppUiModuleStore = AppUiModuleStore(context),
    private val templateStore: MacroTemplateStore = MacroTemplateStore(context),
    private val safetyPolicy: SafeActionPolicy = SafeActionPolicy()
) {
    fun generateSearchDraft(
        modulePath: String?,
        packageName: String?,
        queryParam: String = "query"
    ): SkillDraftResult {
        val moduleSource = loadModule(modulePath, packageName)
            ?: return SkillDraftResult(false, "AppUiModule not found", null, null)
        val (sourceFile, appUi) = moduleSource
        val input = appUi.elements.firstOrNull { element ->
            element.role in setOf(UiRole.SEARCH_INPUT, UiRole.INPUT, UiRole.CHAT_INPUT) ||
                element.matchers.any { matcher -> matcher.editable == true }
        } ?: return SkillDraftResult(false, "No input/search element found in module", null, sourceFile)

        val screen = input.requiredScreenId
            ?: appUi.screens.firstOrNull()?.id
        val verify = VerifyRule(
            type = VerifyRuleType.INPUT_CONTAINS,
            textParam = queryParam,
            minVerification = VerificationCapability.HIGH
        )
        val atoms = buildList {
            add(MacroAtom.OpenApp(appUi.packageName, timeoutMs = 3000L))
            if (!screen.isNullOrBlank()) add(MacroAtom.EnsureScreen(screen, timeoutMs = 3000L))
            add(MacroAtom.TypeText(queryParam, targetElementId = input.id, timeoutMs = 2000L))
            add(MacroAtom.Verify(verify, timeoutMs = 2000L))
        }
        val template = MacroTemplate(
            id = "draft_${appUi.packageName.safeId()}_search_${System.currentTimeMillis()}",
            name = "Draft search in ${appUi.appName}",
            packageName = appUi.packageName,
            intentId = "draft_search",
            params = listOf(MacroParam(queryParam)),
            atoms = atoms,
            finalVerify = verify,
            source = SkillSource.CANDIDATE,
            sideEffectLevel = SideEffectLevel.LOW,
            idempotent = true
        )
        val dryRun = dryRun(template, appUi)
        if (!dryRun.passed) {
            return SkillDraftResult(false, dryRun.reason, null, sourceFile)
        }
        val file = templateStore.saveCandidate(template)
        return SkillDraftResult(true, "Skill draft saved", file, sourceFile)
    }

    fun dryRunDraft(skillId: String, modulePath: String? = null): SkillDraftDryRun {
        val template = templateStore.loadCandidate(skillId)
            ?: return SkillDraftDryRun(false, "Candidate skill draft not found", emptyList(), emptyList())
        val appUi = loadModule(modulePath, template.packageName)?.second
            ?: return SkillDraftDryRun(false, "Matching AppUiModule not found", emptyList(), listOf("missing_module"))
        return dryRun(template, appUi)
    }

    fun listDrafts(limit: Int = 100): List<JSONObject> {
        return templateStore.listCandidates()
            .sortedByDescending { it.first.lastModified() }
            .take(limit.coerceIn(1, 500))
            .map { (file, template) ->
                JSONObject()
                    .put("file", file.absolutePath)
                    .put("id", template.id)
                    .put("name", template.name)
                    .put("packageName", template.packageName)
                    .put("intentId", template.intentId)
                    .put("source", template.source.name)
                    .put("sideEffectLevel", template.sideEffectLevel.name)
                    .put("atomCount", template.atoms.size)
                    .put("params", JSONArray(template.params.map { it.name }))
            }
    }

    private fun dryRun(template: MacroTemplate, appUi: AppUiModule): SkillDraftDryRun {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        if (template.source != SkillSource.CANDIDATE) {
            errors += "Draft source must be CANDIDATE"
        }
        if (template.sideEffectLevel != SideEffectLevel.LOW) {
            errors += "Auto-generated drafts must be LOW side-effect"
        }
        val safety = safetyPolicy.evaluateTemplate(template)
        if (!safety.allowed) errors += safety.reason
        if (template.packageName != appUi.packageName && template.packageName !in appUi.packageAliases) {
            errors += "Template package does not match AppUiModule"
        }
        if (template.params.none { it.required }) {
            warnings += "Draft has no required params"
        }
        val elementIds = appUi.elements.map { it.id }.toSet()
        val screenIds = appUi.screens.map { it.id }.toSet()
        template.atoms.forEach { atom ->
            when (atom) {
                is MacroAtom.OpenApp -> if (atom.packageName != appUi.packageName && atom.packageName !in appUi.packageAliases) {
                    errors += "OpenApp package does not match module"
                }
                is MacroAtom.EnsureScreen -> if (atom.screenId !in screenIds) {
                    errors += "Unknown screen ${atom.screenId}"
                }
                is MacroAtom.ClickElement -> if (atom.elementId !in elementIds) {
                    errors += "Unknown click element ${atom.elementId}"
                }
                is MacroAtom.FocusTextInput -> {
                    if (atom.targetElementId !in elementIds) {
                        errors += "Unknown focus target ${atom.targetElementId}"
                    }
                    atom.toggleElementIds
                        .filter { it !in elementIds }
                        .forEach { errors += "Unknown focus toggle $it" }
                }
                is MacroAtom.TypeText -> {
                    val target = atom.targetElementId
                    if (target.isNullOrBlank() || target !in elementIds) {
                        errors += "Unknown text target ${target.orEmpty()}"
                    }
                    if (template.params.none { it.name == atom.textParam }) {
                        errors += "Missing param ${atom.textParam}"
                    }
                }
                is MacroAtom.SearchInApp -> {
                    if (atom.searchEntryElementId !in elementIds) errors += "Unknown search element ${atom.searchEntryElementId}"
                    if (template.params.none { it.name == atom.queryParam }) errors += "Missing param ${atom.queryParam}"
                }
                is MacroAtom.SelectItem -> if (template.params.none { it.name == atom.itemTextParam }) {
                    errors += "Missing param ${atom.itemTextParam}"
                }
                is MacroAtom.ScrollUntilVisible -> if (template.params.none { it.name == atom.targetTextParam }) {
                    errors += "Missing param ${atom.targetTextParam}"
                }
                is MacroAtom.WaitFor -> Unit
                is MacroAtom.Verify -> Unit
            }
        }
        val hasFinalVerify = template.finalVerify.type != VerifyRuleType.ALWAYS ||
            template.finalVerify.minVerification != VerificationCapability.NONE
        if (!hasFinalVerify) errors += "Draft requires a real finalVerify rule"

        return SkillDraftDryRun(
            passed = errors.isEmpty(),
            reason = if (errors.isEmpty()) "Skill draft dry-run passed" else errors.joinToString("; "),
            warnings = warnings,
            errors = errors
        )
    }

    private fun loadModule(modulePath: String?, packageName: String?): Pair<File?, AppUiModule>? {
        if (!modulePath.isNullOrBlank()) {
            val module = appUiStore.loadCandidateFile(modulePath)
                ?: runCatching { appUiStore.fromJson(JSONObject(File(modulePath).readText())) }.getOrNull()
            if (module != null) return File(modulePath) to module
        }
        if (!packageName.isNullOrBlank()) {
            val candidate = appUiStore.listCandidates()
                .firstOrNull { (_, module) -> module.packageName == packageName }
            if (candidate != null) return candidate
            val approved = appUiStore.loadApproved(packageName)
            if (approved != null) return null to approved
        }
        return null
    }

    private fun String.safeId(): String {
        return lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifBlank { "app" }
    }
}

data class SkillDraftResult(
    val ok: Boolean,
    val reason: String,
    val file: File?,
    val moduleFile: File?
)

data class SkillDraftDryRun(
    val passed: Boolean,
    val reason: String,
    val warnings: List<String>,
    val errors: List<String>
)
