package com.roubao.autopilot.skills

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class RecordedSkillLibrary(private val context: Context) {
    private val skills = mutableListOf<RecordedSkill>()
    private val storageFile: File by lazy { File(context.filesDir, "recorded_skills.json") }

    suspend fun load() = withContext(Dispatchers.IO) {
        skills.clear()
        if (!storageFile.exists()) return@withContext
        try {
            val arr = JSONArray(storageFile.readText())
            for (i in 0 until arr.length()) skills.add(RecordedSkill.fromJson(arr.getJSONObject(i)))
            println("[RecordedSkillLibrary] 已加载 ${skills.size} 个录制 skill")
        } catch (e: Exception) {
            println("[RecordedSkillLibrary] 加载失败: ${e.message}")
        }
    }

    fun match(intent: ParsedIntent): SkillWorkflowPlan? {
        val skill = skills.firstOrNull { it.intent == intent.templateId && it.appName == intent.appName }
            ?: skills.firstOrNull { it.intent == intent.templateId }
            ?: return null
        return skill.toPlan(intent.params)
    }

    suspend fun save(skill: RecordedSkill) = withContext(Dispatchers.IO) {
        if (skill.steps.isEmpty()) return@withContext
        skills.removeAll { it.id == skill.id || (it.intent == skill.intent && it.appName == skill.appName) }
        skills.add(skill)
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        skills.forEach { arr.put(it.toJson()) }
        storageFile.writeText(arr.toString(2))
    }

    fun getAll(): List<RecordedSkill> = skills.toList()
}

data class RecordedSkill(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val intent: String,
    val appName: String,
    val params: List<String>,
    val steps: List<SkillStep>,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toPlan(values: Map<String, String>): SkillWorkflowPlan {
        return SkillWorkflowPlan(id, name, values, steps).replaceParams(values)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("intent", intent)
        put("appName", appName)
        put("params", JSONArray(params))
        put("createdAt", createdAt)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(obj: JSONObject): RecordedSkill = RecordedSkill(
            id = obj.optString("id", UUID.randomUUID().toString().take(8)),
            name = obj.optString("name", obj.optString("intent")),
            intent = obj.getString("intent"),
            appName = obj.optString("appName"),
            params = obj.optJSONArray("params")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
            steps = obj.optJSONArray("steps")?.toSkillSteps() ?: emptyList(),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        )
    }
}
