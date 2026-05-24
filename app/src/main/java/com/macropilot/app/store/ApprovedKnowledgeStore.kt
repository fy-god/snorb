package com.macropilot.app.store

import android.content.Context
import java.io.File

class ApprovedKnowledgeStore(private val context: Context) {
    val rootDir: File
        get() = File(context.filesDir, "macro_v2/approved")

    val appModuleDir: File
        get() = File(rootDir, "apps")

    val skillDir: File
        get() = File(rootDir, "skills")

    fun ensureCreated() {
        appModuleDir.mkdirs()
        skillDir.mkdirs()
    }

    fun approvedSkillFiles(): List<File> {
        ensureCreated()
        return skillDir.listFiles { file -> file.isFile && file.extension == "json" }?.toList().orEmpty()
    }
}
