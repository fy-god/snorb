package com.macropilot.app.store

import android.content.Context
import java.io.File

class CandidateKnowledgeStore(private val context: Context) {
    val rootDir: File
        get() = File(context.filesDir, "macro_v2/factory")

    val candidateSkillDir: File
        get() = File(rootDir, "candidate_skills")

    val pendingSkillDir: File
        get() = File(rootDir, "pending_skills")

    val candidatePatchDir: File
        get() = File(rootDir, "candidate_patches")

    val candidateAppModuleDir: File
        get() = File(rootDir, "candidate_app_modules")

    val testReportDir: File
        get() = File(rootDir, "test_reports")

    fun ensureCreated() {
        candidateSkillDir.mkdirs()
        pendingSkillDir.mkdirs()
        candidatePatchDir.mkdirs()
        candidateAppModuleDir.mkdirs()
        testReportDir.mkdirs()
    }
}
