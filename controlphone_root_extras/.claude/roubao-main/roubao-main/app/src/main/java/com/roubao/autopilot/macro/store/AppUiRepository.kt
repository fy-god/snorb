package com.roubao.autopilot.macro.store

import android.content.Context
import com.roubao.autopilot.macro.builtin.BuiltinWechatMacros
import com.roubao.autopilot.macro.model.AppUiModule
import com.roubao.autopilot.macro.model.UiElementSpec
import com.roubao.autopilot.macro.model.UiScreenModule
import com.roubao.autopilot.macro.model.UiSignature

class AppUiRepository(
    context: Context
) {
    private val store = AppUiCoordinateStore(context)

    fun load(appPackage: String): AppUiModule? {
        val builtin = when (appPackage) {
            BuiltinWechatMacros.PACKAGE -> BuiltinWechatMacros.appUi
            else -> null
        }

        val storedScreens = store.loadScreens(appPackage)
        if (builtin == null && storedScreens.isEmpty()) return null

        val screens = mergeScreens(
            base = builtin?.screens ?: emptyList(),
            overrides = storedScreens
        )

        return AppUiModule(
            appPackage = appPackage,
            appName = builtin?.appName ?: appPackage,
            screens = screens,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun mergeElements(appPackage: String, elements: List<UiElementSpec>) {
        if (elements.isEmpty()) return
        val module = load(appPackage)
        val screens = module?.screens?.toMutableList() ?: mutableListOf()

        for (element in elements) {
            val screenId = element.requiredScreenId ?: "recorded"
            val index = screens.indexOfFirst { it.id == screenId }
            val screen = if (index >= 0) {
                screens[index]
            } else {
                UiScreenModule(
                    id = screenId,
                    name = screenId,
                    signatures = listOf(UiSignature(packageName = appPackage)),
                    elements = emptyList()
                ).also { screens.add(it) }
            }

            val mergedElements = (screen.elements.filterNot { it.id == element.id } + element)
            val updated = screen.copy(elements = mergedElements)
            val updatedIndex = screens.indexOfFirst { it.id == screenId }
            screens[updatedIndex] = updated
            store.saveScreen(appPackage, updated)
        }
    }

    private fun mergeScreens(
        base: List<UiScreenModule>,
        overrides: List<UiScreenModule>
    ): List<UiScreenModule> {
        val result = base.associateBy { it.id }.toMutableMap()
        for (override in overrides) {
            val current = result[override.id]
            result[override.id] = if (current == null) {
                override
            } else {
                current.copy(
                    aliases = (current.aliases + override.aliases).distinct(),
                    signatures = override.signatures.ifEmpty { current.signatures },
                    elements = (current.elements + override.elements).distinctBy { it.id }
                )
            }
        }
        return result.values.toList()
    }
}
