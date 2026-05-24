package com.roubao.autopilot.macro.core

import com.roubao.autopilot.macro.model.AppUiModule
import com.roubao.autopilot.macro.model.MacroAtom
import com.roubao.autopilot.macro.model.MacroPlan
import com.roubao.autopilot.macro.model.MacroTemplate
import com.roubao.autopilot.skills.UiState

class MacroComposer(
    private val screenClassifier: UiScreenClassifier
) {
    fun compose(
        template: MacroTemplate,
        params: Map<String, String>,
        currentState: UiState,
        appUi: AppUiModule
    ): MacroPlan {
        val currentScreen = screenClassifier.classify(currentState, appUi)

        val directChatAtoms = directWechatChatAtoms(template, params, currentState, currentScreen?.id)
        if (directChatAtoms != null) {
            return MacroPlan(
                templateId = template.id,
                appPackage = template.appPackage,
                params = params,
                atoms = directChatAtoms
            )
        }

        val atoms = mutableListOf<MacroAtom>()
        for (atom in template.atoms) {
            when (atom) {
                is MacroAtom.OpenApp -> {
                    if (currentState.packageName != atom.appPackage) atoms.add(atom)
                }
                is MacroAtom.EnsureScreen -> {
                    if (currentScreen?.id != atom.screenId) atoms.add(atom)
                }
                else -> atoms.add(atom)
            }
        }

        return MacroPlan(
            templateId = template.id,
            appPackage = template.appPackage,
            params = params,
            atoms = atoms
        )
    }

    private fun directWechatChatAtoms(
        template: MacroTemplate,
        params: Map<String, String>,
        currentState: UiState,
        currentScreenId: String?
    ): List<MacroAtom>? {
        if (template.id != "wechat_send_message") return null
        if (currentScreenId != "wechat_chat" && !currentState.isCoordinateOnlyWechatFileHelper(params["contact"])) {
            return null
        }
        val contact = params["contact"]
        if (currentScreenId == "wechat_chat" && !contact.isNullOrBlank() && !currentState.containsContactAlias(contact)) {
            return null
        }

        return template.atoms.filter {
            it is MacroAtom.TypeText || it is MacroAtom.ClickElement || it is MacroAtom.Verify
        }
    }

    private fun UiState.containsContactAlias(contact: String): Boolean {
        if (hasText(contact)) return true
        val withoutWechatPrefix = contact.removePrefix("\u5fae\u4fe1")
        return withoutWechatPrefix != contact && withoutWechatPrefix.isNotBlank() && hasText(withoutWechatPrefix)
    }

    private fun UiState.isCoordinateOnlyWechatFileHelper(contact: String?): Boolean {
        if (packageName != "com.tencent.mm") return false
        if (contact.isNullOrBlank()) return false
        if (!contact.contains("\u6587\u4ef6\u4f20\u8f93\u52a9\u624b")) return false
        val hasRealNode = nodes.any { node ->
            node.visible && !node.bounds.isEmpty && (node.text != null || node.contentDesc != null || node.resourceId != null)
        }
        return !hasRealNode
    }
}
