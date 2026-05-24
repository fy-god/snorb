package com.macropilot.app.runtime

import com.macropilot.app.model.MacroAtom
import com.macropilot.app.model.MacroPlan
import com.macropilot.app.model.MacroTemplate

class MacroComposer {
    fun compose(
        template: MacroTemplate,
        params: Map<String, String>,
        currentScreenId: String?
    ): MacroPlan {
        val atoms = if (currentScreenId == null) {
            template.atoms
        } else {
            trimAlreadySatisfiedEnsureScreens(template.atoms, currentScreenId)
        }
        return MacroPlan(
            templateId = template.id,
            packageName = template.packageName,
            params = params,
            atoms = atoms,
            finalVerify = template.finalVerify,
            sideEffectLevel = template.sideEffectLevel
        )
    }

    private fun trimAlreadySatisfiedEnsureScreens(atoms: List<MacroAtom>, currentScreenId: String): List<MacroAtom> {
        val firstUnsatisfied = atoms.indexOfFirst { atom ->
            atom !is MacroAtom.EnsureScreen || atom.screenId != currentScreenId
        }
        return if (firstUnsatisfied <= 0) atoms else atoms.drop(firstUnsatisfied)
    }
}
