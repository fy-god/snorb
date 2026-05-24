package com.macropilot.app.privacy

import com.macropilot.app.model.NodeSample
import com.macropilot.app.model.UiEventSample
import com.macropilot.app.model.UiStateSample

class PrivacyRedactor {
    fun redact(state: UiStateSample): UiStateSample {
        return state.copy(
            nodes = state.nodes.map { redact(it) },
            eventsSinceLastState = state.eventsSinceLastState.map { redact(it) }
        )
    }

    private fun redact(node: NodeSample): NodeSample {
        return node.copy(
            text = redactText(node.text),
            contentDesc = redactText(node.contentDesc)
        )
    }

    private fun redact(event: UiEventSample): UiEventSample {
        return event.copy(text = redactText(event.text))
    }

    private fun redactText(value: String?): String? {
        if (value.isNullOrBlank()) return value
        var redacted = value
        redacted = emailRegex.replace(redacted, "[EMAIL]")
        redacted = phoneRegex.replace(redacted, "[PHONE]")
        redacted = longNumberRegex.replace(redacted, "[NUMBER]")
        redacted = mixedTokenRegex.replace(redacted, "[TOKEN]")
        redacted = tokenRegex.replace(redacted, "[TOKEN]")
        redacted = privacyKeywordRegex.replace(redacted) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        return redacted
    }

    private companion object {
        val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        val phoneRegex = Regex("(?<!\\d)(?:\\+?\\d[\\d -]{7,}\\d|1[3-9]\\d{9})(?!\\d)")
        val longNumberRegex = Regex("(?<!\\d)\\d{6,}(?!\\d)")
        val mixedTokenRegex = Regex("\\b(?=[A-Za-z0-9_-]{8,}\\b)(?=[A-Za-z0-9_-]*[A-Za-z])(?=[A-Za-z0-9_-]*\\d)[A-Za-z0-9_-]+\\b")
        val tokenRegex = Regex("\\b[A-Za-z0-9_-]{16,}\\b")
        val privacyKeywordRegex = Regex("(昵称|账号|手机号|手机|邮箱|地址|姓名|身份证|验证码)[^\\s，,。:：/]{1,40}")
    }
}
