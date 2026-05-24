package com.macropilot.app.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class AppUiGraphStore(private val context: Context) {
    private val factoryRoot: File
        get() = File(context.filesDir, "macro_v2/factory/app_ui_graphs")

    private val runtimeRoot: File
        get() = File(context.filesDir, "macro_v2/runtime/approved/apps")

    fun saveCandidate(packageName: String, graph: JSONObject): File {
        val dir = File(factoryRoot, packageName.safeSegment())
        dir.mkdirs()
        val normalized = normalizeGraph(packageName, graph)
        val runId = normalized.optString("id").ifBlank {
            "graph_candidate_${normalized.optLong("createdAt", System.currentTimeMillis())}"
        }
        val archiveFile = File(dir, "${runId.safeSegment()}.json")
        archiveFile.writeText(normalized.toString(2))
        val file = File(dir, "graph_candidate.json")
        file.writeText(normalized.toString(2))
        return file
    }

    fun loadCandidate(packageName: String): JSONObject? {
        val file = candidateFile(packageName)
        return if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrNull() else null
    }

    fun candidateFile(packageName: String): File {
        return File(File(factoryRoot, packageName.safeSegment()), "graph_candidate.json")
    }

    fun saveRuntimeApproved(packageName: String, graph: JSONObject): File {
        val dir = File(runtimeRoot, packageName.safeSegment())
        dir.mkdirs()
        val file = File(dir, "app_ui_graph.json")
        file.writeText(normalizeGraph(packageName, graph).put("status", "APPROVED").toString(2))
        return file
    }

    fun compactSummary(packageName: String, maxScreens: Int = 12): JSONObject {
        val graph = loadCandidate(packageName)
            ?: runCatching {
                JSONObject(File(File(runtimeRoot, packageName.safeSegment()), "app_ui_graph.json").readText())
            }.getOrNull()
            ?: return JSONObject()
                .put("appPackage", packageName)
                .put("status", "MISSING")
                .put("screens", JSONArray())
                .put("edges", JSONArray())

        val screens = graph.optJSONArray("screens") ?: JSONArray()
        val compactScreens = JSONArray()
        for (i in 0 until minOf(screens.length(), maxScreens.coerceIn(1, 50))) {
            val screen = screens.optJSONObject(i) ?: continue
            compactScreens.put(JSONObject()
                .put("screenId", screen.optString("screenId"))
                .put("title", screen.optString("title"))
                .put("screenKey", screen.optString("screenKey"))
                .put("nodeCount", screen.optInt("nodeCount"))
                .put("clickableCount", screen.optInt("clickableCount"))
                .put("editableCount", screen.optInt("editableCount"))
                .put("labels", screen.optJSONArray("labels") ?: JSONArray())
                .put("bottomTabs", screen.optJSONArray("bottomTabs") ?: JSONArray())
                .put("topEntries", screen.optJSONArray("topEntries") ?: JSONArray())
                .put("sideEntries", screen.optJSONArray("sideEntries") ?: JSONArray())
                .put("graphicalCandidates", screen.optJSONArray("graphicalCandidates") ?: JSONArray())
                .put("checkableCandidates", screen.optJSONArray("checkableCandidates") ?: JSONArray())
                .put("entryCandidates", screen.optJSONArray("entryCandidates") ?: JSONArray())
                .put("inputCandidates", screen.optJSONArray("inputCandidates") ?: JSONArray())
                .put("videoCandidates", screen.optJSONArray("videoCandidates") ?: JSONArray())
                .put("snapshotPath", screen.optString("snapshotPath")))
        }
        return JSONObject()
            .put("appPackage", graph.optString("appPackage").ifBlank { packageName })
            .put("status", graph.optString("status").ifBlank { "CANDIDATE" })
            .put("screenCount", screens.length())
            .put("edgeCount", graph.optJSONArray("edges")?.length() ?: 0)
            .put("quality", graph.optJSONObject("quality") ?: graphQuality(screens, graph.optJSONArray("edges") ?: JSONArray()))
            .put("screens", compactScreens)
            .put("updatedAt", graph.optLong("updatedAt"))
    }

    private fun graphQuality(screens: JSONArray, edges: JSONArray): JSONObject {
        var nodeCount = 0
        var clickableCount = 0
        var editableCount = 0
        var labelCount = 0
        var bottomTabCount = 0
        var topEntryCount = 0
        var sideEntryCount = 0
        var graphicalCount = 0
        var checkableCount = 0
        var entryCount = 0
        var inputCount = 0
        var videoCount = 0
        for (i in 0 until screens.length()) {
            val screen = screens.optJSONObject(i) ?: continue
            nodeCount += screen.optInt("nodeCount")
            clickableCount += screen.optInt("clickableCount")
            editableCount += screen.optInt("editableCount")
            labelCount += screen.optJSONArray("labels")?.length() ?: 0
            bottomTabCount += screen.optJSONArray("bottomTabs")?.length() ?: 0
            topEntryCount += screen.optJSONArray("topEntries")?.length() ?: 0
            sideEntryCount += screen.optJSONArray("sideEntries")?.length() ?: 0
            graphicalCount += screen.optJSONArray("graphicalCandidates")?.length() ?: 0
            checkableCount += screen.optJSONArray("checkableCandidates")?.length() ?: 0
            entryCount += screen.optJSONArray("entryCandidates")?.length() ?: 0
            inputCount += screen.optJSONArray("inputCandidates")?.length() ?: 0
            videoCount += screen.optJSONArray("videoCandidates")?.length() ?: 0
        }
        val candidateCount = bottomTabCount + topEntryCount + sideEntryCount + graphicalCount + checkableCount + entryCount + inputCount + videoCount
        val hasNavigation = edges.length() > 0 || bottomTabCount > 0 || topEntryCount > 0 || sideEntryCount > 0
        val usable = screens.length() >= 1 &&
            nodeCount >= 20 &&
            labelCount >= 3 &&
            (clickableCount >= 3 || candidateCount >= 2 || editableCount > 0) &&
            hasNavigation
        return JSONObject()
            .put("usable", usable)
            .put("screenCount", screens.length())
            .put("edgeCount", edges.length())
            .put("nodeCount", nodeCount)
            .put("clickableCount", clickableCount)
            .put("editableCount", editableCount)
            .put("labelCount", labelCount)
            .put("bottomTabCount", bottomTabCount)
            .put("topEntryCount", topEntryCount)
            .put("sideEntryCount", sideEntryCount)
            .put("graphicalCandidateCount", graphicalCount)
            .put("checkableCandidateCount", checkableCount)
            .put("entryCandidateCount", entryCount)
            .put("inputCandidateCount", inputCount)
            .put("videoCandidateCount", videoCount)
            .put("candidateCount", candidateCount)
    }

    private fun normalizeGraph(packageName: String, graph: JSONObject): JSONObject {
        val out = JSONObject(graph.toString())
        if (out.optInt("schemaVersion", 0) <= 0) out.put("schemaVersion", 1)
        if (out.optString("kind").isBlank()) out.put("kind", "app_ui_graph")
        if (out.optString("appPackage").isBlank()) out.put("appPackage", packageName)
        if (out.optString("status").isBlank()) out.put("status", "CANDIDATE")
        if (!out.has("screens")) out.put("screens", JSONArray())
        if (!out.has("edges")) out.put("edges", JSONArray())
        out.put("updatedAt", System.currentTimeMillis())
        return out
    }

    private fun String.safeSegment(): String {
        return lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "item" }
    }
}
