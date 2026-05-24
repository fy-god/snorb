package com.roubao.autopilot.skills

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SkillTemplateStore — 模板持久化层。
 *
 * 存储位置：{filesDir}/skill_templates.json
 * 格式：JSON 数组，每个元素为 SkillTemplate 的序列化形式。
 */
class SkillTemplateStore(private val context: Context) {

    private val storageFile: File
        get() = File(context.filesDir, "skill_templates.json")

    /** 加载全部模板 */
    suspend fun load(): List<SkillTemplate> = withContext(Dispatchers.IO) {
        try {
            if (!storageFile.exists()) return@withContext emptyList()
            val json = storageFile.readText()
            if (json.isBlank()) return@withContext emptyList()

            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                parseTemplate(arr.getJSONObject(i))
            }
        } catch (e: Exception) {
            android.util.Log.e("SkillTemplateStore", "加载模板失败", e)
            emptyList()
        }
    }

    /** 保存全部模板 */
    suspend fun save(templates: List<SkillTemplate>) = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray()
            templates.forEach { arr.put(serializeTemplate(it)) }
            storageFile.writeText(arr.toString(2))
        } catch (e: Exception) {
            android.util.Log.e("SkillTemplateStore", "保存模板失败", e)
        }
    }

    /** 追加单个模板 */
    suspend fun append(template: SkillTemplate) = withContext(Dispatchers.IO) {
        val existing = load().toMutableList()
        val idx = existing.indexOfFirst { it.id == template.id }
        if (idx != -1) {
            existing[idx] = template
        } else {
            existing.add(template)
        }
        save(existing)
    }

    /** 删除模板 */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val existing = load().toMutableList()
        existing.removeAll { it.id == id }
        save(existing)
    }

    // ---- 序列化 ----

    private fun serializeTemplate(t: SkillTemplate): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("name", t.name)
        put("description", t.description)
        put("goalType", t.goalType.name)
        put("requiredParams", JSONArray(t.requiredParams))
        t.appPackageName?.let { put("appPackageName", it) }
        put("tags", JSONArray(t.tags))
        put("source", t.source.name)
        put("useCount", t.useCount)
        put("successRate", t.successRate.toDouble())
        put("atoms", JSONArray().apply {
            t.atoms.forEach { atom ->
                put(JSONObject().apply {
                    put("goalType", atom.goalType.name)
                    put("paramSlots", JSONObject(atom.paramSlots))
                })
            }
        })
    }

    private fun parseTemplate(obj: JSONObject): SkillTemplate {
        val atoms = (0 until obj.getJSONArray("atoms").length()).map { i ->
            val atomObj = obj.getJSONArray("atoms").getJSONObject(i)
            val slotsObj = atomObj.optJSONObject("paramSlots") ?: JSONObject()
            val slots = mutableMapOf<String, String>()
            slotsObj.keys().forEach { key -> slots[key] = slotsObj.getString(key) }
            SkillAtomSpec(
                goalType = GoalType.valueOf(atomObj.getString("goalType")),
                paramSlots = slots
            )
        }

        return SkillTemplate(
            id = obj.getString("id"),
            name = obj.getString("name"),
            description = obj.optString("description", ""),
            goalType = GoalType.valueOf(obj.getString("goalType")),
            atoms = atoms,
            requiredParams = (0 until obj.getJSONArray("requiredParams").length()).map {
                obj.getJSONArray("requiredParams").getString(it)
            },
            appPackageName = obj.optString("appPackageName", null),
            tags = (0 until obj.getJSONArray("tags").length()).map {
                obj.getJSONArray("tags").getString(it)
            },
            source = try {
                TemplateSource.valueOf(obj.getString("source"))
            } catch (_: Exception) { TemplateSource.BUILT_IN },
            useCount = obj.optInt("useCount", 0),
            successRate = obj.optDouble("successRate", 1.0).toFloat()
        )
    }
}
