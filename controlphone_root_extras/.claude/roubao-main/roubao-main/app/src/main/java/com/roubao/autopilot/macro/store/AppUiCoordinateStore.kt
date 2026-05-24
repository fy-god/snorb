package com.roubao.autopilot.macro.store

import android.content.Context
import com.roubao.autopilot.macro.model.MacroTemplate
import com.roubao.autopilot.macro.model.UiScreenModule
import java.io.File

class AppUiCoordinateStore(
    private val context: Context
) {
    fun appDir(packageName: String): File {
        return File(context.filesDir, "macro_v2/apps/$packageName")
    }

    fun screenFile(packageName: String, screenId: String): File {
        return File(appDir(packageName), "screens/$screenId.json")
    }

    fun skillFile(packageName: String, skillId: String): File {
        return File(appDir(packageName), "skills/$skillId.json")
    }

    fun saveScreen(packageName: String, screen: UiScreenModule) {
        val file = screenFile(packageName, screen.id)
        file.parentFile?.mkdirs()
        file.writeText(MacroJson.screenToJson(screen).toString(2))
    }

    fun loadScreens(packageName: String): List<UiScreenModule> {
        val dir = File(appDir(packageName), "screens")
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    MacroJson.screenFromJson(org.json.JSONObject(file.readText()))
                }.getOrNull()
            }
            ?: emptyList()
    }

    fun saveTemplate(template: MacroTemplate) {
        val file = skillFile(template.appPackage, template.id)
        file.parentFile?.mkdirs()
        file.writeText(MacroJson.templateToJson(template).toString(2))
    }

    fun loadTemplates(packageName: String? = null): List<MacroTemplate> {
        val root = if (packageName == null) {
            File(context.filesDir, "macro_v2/apps")
        } else {
            appDir(packageName)
        }
        if (!root.exists()) return emptyList()

        val skillDirs = if (packageName == null) {
            root.listFiles()?.map { File(it, "skills") } ?: emptyList()
        } else {
            listOf(File(root, "skills"))
        }

        return skillDirs
            .flatMap { dir -> dir.listFiles()?.toList() ?: emptyList() }
            .filter { it.extension == "json" }
            .mapNotNull { file ->
                runCatching {
                    MacroJson.templateFromJson(org.json.JSONObject(file.readText()))
                }.getOrNull()
            }
    }
}
