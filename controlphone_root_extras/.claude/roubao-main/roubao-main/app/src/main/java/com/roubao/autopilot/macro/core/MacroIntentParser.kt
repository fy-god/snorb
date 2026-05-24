package com.roubao.autopilot.macro.core

import com.roubao.autopilot.skills.IntentParser
import com.roubao.autopilot.skills.ParsedIntent

class MacroIntentParser(
    private val legacyParser: IntentParser = IntentParser()
) {
    fun parse(instruction: String): ParsedIntent? {
        parseAsciiWechatSend(instruction)?.let { return it }
        parseWechatSend(instruction)?.let { return it }
        return legacyParser.parse(instruction)?.normalize()
    }

    private fun parseAsciiWechatSend(instruction: String): ParsedIntent? {
        val cleaned = instruction.trim()
        val match = Regex("^(?:wx|wechat|wxsend)\\s+(.+?)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(cleaned)
            ?: return null

        val rawContact = match.groupValues[1].trim()
        val message = match.groupValues[2].trim()
        if (rawContact.isBlank() || message.isBlank()) return null

        val contact = when (rawContact.lowercase()) {
            "filehelper", "file_helper", "ft", "transfer", "filetransfer" ->
                "\u5fae\u4fe1\u6587\u4ef6\u4f20\u8f93\u52a9\u624b"
            else -> rawContact
        }

        return ParsedIntent(
            templateId = "wechat_send_message",
            appName = "\u5fae\u4fe1",
            params = mapOf("contact" to contact, "message" to message),
            confidence = 0.90f
        )
    }

    private fun parseWechatSend(instruction: String): ParsedIntent? {
        val cleaned = instruction.trim()
        val directPatterns = listOf(
            Regex("^(?:\\s*\\u5fae\\u4fe1\\s*)?\\u7ed9(.+?)(?:\\u53d1\\u9001|\\u53d1)(.+)$"),
            Regex("^(?:\\s*\\u5fae\\u4fe1\\s*)?(.+?)(?:\\u53d1\\u9001|\\u53d1)(.+)$")
        )

        for (pattern in directPatterns) {
            val match = pattern.find(cleaned) ?: continue
            val contact = match.groupValues[1].trim()
            val message = match.groupValues[2].trim()
            if (contact.isNotBlank() && message.isNotBlank()) {
                return ParsedIntent(
                    templateId = "wechat_send_message",
                    appName = "\u5fae\u4fe1",
                    params = mapOf("contact" to contact, "message" to message),
                    confidence = 0.90f
                )
            }
        }

        val reversed = Regex("^(?:\\s*\\u5fae\\u4fe1\\s*)?(?:\\u53d1\\u9001|\\u53d1)(.+?)\\u7ed9(.+)$")
            .find(cleaned)
        if (reversed != null) {
            val message = reversed.groupValues[1].trim()
            val contact = reversed.groupValues[2].trim()
            if (contact.isNotBlank() && message.isNotBlank()) {
                return ParsedIntent(
                    templateId = "wechat_send_message",
                    appName = "\u5fae\u4fe1",
                    params = mapOf("contact" to contact, "message" to message),
                    confidence = 0.88f
                )
            }
        }

        return null
    }

    private fun ParsedIntent.normalize(): ParsedIntent {
        if (templateId != "wechat_send_message") return this
        val contact = params["contact"] ?: params["X"] ?: params["target"] ?: params["recipient"]
        val message = params["message"] ?: params["Y"] ?: params["text"] ?: params["content"]
        val normalized = buildMap {
            contact?.let { put("contact", it) }
            message?.let { put("message", it) }
        }
        return copy(params = if (normalized.isEmpty()) params else normalized)
    }
}
