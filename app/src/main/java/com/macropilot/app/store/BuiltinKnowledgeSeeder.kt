package com.macropilot.app.store

import android.content.Context
import com.macropilot.app.model.AppUiModule
import com.macropilot.app.model.ElementMatcher
import com.macropilot.app.model.MacroAtom
import com.macropilot.app.model.MacroParam
import com.macropilot.app.model.MacroTemplate
import com.macropilot.app.model.RecoverAction
import com.macropilot.app.model.ReliabilityLevel
import com.macropilot.app.model.SideEffectLevel
import com.macropilot.app.model.SkillSource
import com.macropilot.app.model.UiElementSpec
import com.macropilot.app.model.UiRole
import com.macropilot.app.model.UiScreenModule
import com.macropilot.app.model.UiSignature
import com.macropilot.app.model.VerificationCapability
import com.macropilot.app.model.VerifyRule
import com.macropilot.app.model.VerifyRuleType

class BuiltinKnowledgeSeeder(private val context: Context) {
    private val appStore = AppUiModuleStore(context)
    private val templateStore = MacroTemplateStore(context)

    fun seedSettingsSearch(): SeedResult {
        val moduleFile = appStore.saveApproved(settingsModule())
        val templateFile = templateStore.saveApproved(settingsSearchTemplate())
        return SeedResult(
            modulePath = moduleFile.absolutePath,
            templatePath = templateFile.absolutePath
        )
    }

    fun seedSettingsScrollVisible(): SeedResult {
        val moduleFile = appStore.saveApproved(settingsModule())
        val templateFile = templateStore.saveApproved(settingsScrollVisibleTemplate())
        return SeedResult(
            modulePath = moduleFile.absolutePath,
            templatePath = templateFile.absolutePath
        )
    }

    fun seedSettingsSelectVisible(): SeedResult {
        val moduleFile = appStore.saveApproved(settingsModule())
        val templateFile = templateStore.saveApproved(settingsSelectVisibleTemplate())
        return SeedResult(
            modulePath = moduleFile.absolutePath,
            templatePath = templateFile.absolutePath
        )
    }

    private fun settingsModule(): AppUiModule {
        return AppUiModule(
            packageName = SETTINGS_PACKAGE,
            packageAliases = listOf("com.oplus.wirelesssettings"),
            appName = "Android Settings",
            screens = listOf(
                UiScreenModule(
                    id = "settings_home",
                    name = "Settings home",
                    signatures = listOf(
                        UiSignature(
                            packageName = SETTINGS_PACKAGE,
                            requiredTextsAny = listOf("设置", "搜索设置项", "WLAN", "蓝牙", "应用"),
                            requiredResourceIdsAny = listOf("com.android.settings:id/searchView", "searchView"),
                            negativeTexts = listOf("清除文本"),
                            minScore = 0.85f
                        )
                    ),
                    recoverActions = listOf(RecoverAction.Back)
                )
            ),
            elements = listOf(
                UiElementSpec(
                    id = "settings_search_input",
                    name = "Settings search input",
                    role = UiRole.SEARCH_INPUT,
                    matchers = listOf(
                        ElementMatcher(className = "android.widget.EditText", editable = true, enabled = true),
                        ElementMatcher(textContains = "搜索", editable = true, enabled = true),
                        ElementMatcher(contentDescContains = "搜索", editable = true, enabled = true)
                    ),
                    requiredScreenId = "settings_home",
                    reliability = ReliabilityLevel.MEDIUM,
                    samples = 1,
                    successRate = 1f
                )
            )
        )
    }

    private fun settingsSearchTemplate(): MacroTemplate {
        val verify = VerifyRule(
            type = VerifyRuleType.INPUT_CONTAINS,
            textParam = "query",
            minVerification = VerificationCapability.HIGH
        )
        return MacroTemplate(
            id = "settings_search",
            name = "Search Android Settings",
            packageName = SETTINGS_PACKAGE,
            intentId = "settings_search",
            params = listOf(MacroParam("query")),
            atoms = listOf(
                MacroAtom.OpenApp(SETTINGS_PACKAGE, timeoutMs = 3000L),
                MacroAtom.EnsureScreen("settings_home", timeoutMs = 3000L),
                MacroAtom.TypeText("query", targetElementId = "settings_search_input", timeoutMs = 2000L),
                MacroAtom.Verify(verify, timeoutMs = 2000L)
            ),
            finalVerify = verify,
            source = SkillSource.APPROVED,
            sideEffectLevel = SideEffectLevel.LOW,
            idempotent = true
        )
    }

    private fun settingsScrollVisibleTemplate(): MacroTemplate {
        val verify = VerifyRule(
            type = VerifyRuleType.TEXT_VISIBLE,
            textParam = "target",
            minVerification = VerificationCapability.HIGH
        )
        return MacroTemplate(
            id = SETTINGS_SCROLL_VISIBLE_TEMPLATE_ID,
            name = "Scroll Android Settings until text visible",
            packageName = SETTINGS_PACKAGE,
            intentId = "settings_scroll_visible",
            params = listOf(MacroParam("target")),
            atoms = listOf(
                MacroAtom.OpenApp(SETTINGS_PACKAGE, timeoutMs = 3000L),
                MacroAtom.EnsureScreen("settings_home", timeoutMs = 3000L),
                MacroAtom.ScrollUntilVisible("target", maxSwipes = 5, timeoutMs = 6000L),
                MacroAtom.Verify(verify, timeoutMs = 2000L)
            ),
            finalVerify = verify,
            source = SkillSource.APPROVED,
            sideEffectLevel = SideEffectLevel.LOW,
            idempotent = true
        )
    }

    private fun settingsSelectVisibleTemplate(): MacroTemplate {
        val verify = VerifyRule(
            type = VerifyRuleType.TEXT_VISIBLE,
            textParam = "item",
            minVerification = VerificationCapability.HIGH
        )
        return MacroTemplate(
            id = SETTINGS_SELECT_VISIBLE_TEMPLATE_ID,
            name = "Select visible Android Settings item",
            packageName = SETTINGS_PACKAGE,
            intentId = "settings_select_visible",
            params = listOf(MacroParam("item")),
            atoms = listOf(
                MacroAtom.OpenApp(SETTINGS_PACKAGE, timeoutMs = 3000L),
                MacroAtom.EnsureScreen("settings_home", timeoutMs = 3000L),
                MacroAtom.SelectItem("item", timeoutMs = 3000L),
                MacroAtom.Verify(verify, timeoutMs = 2000L)
            ),
            finalVerify = verify,
            source = SkillSource.APPROVED,
            sideEffectLevel = SideEffectLevel.LOW,
            idempotent = true
        )
    }

    companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val SETTINGS_SEARCH_TEMPLATE_ID = "settings_search"
        const val SETTINGS_SCROLL_VISIBLE_TEMPLATE_ID = "settings_scroll_visible"
        const val SETTINGS_SELECT_VISIBLE_TEMPLATE_ID = "settings_select_visible"
    }
}

data class SeedResult(
    val modulePath: String,
    val templatePath: String
)
