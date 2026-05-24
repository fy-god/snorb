package com.roubao.autopilot.macro.record

import com.roubao.autopilot.macro.model.MacroDomain

class MacroClassifier {
    fun classify(trace: RecordedTrace): MacroCategory {
        val pkg = trace.appPackage ?: "unknown"
        val isWechat = pkg == "com.tencent.mm" || trace.instruction.contains("\u5fae\u4fe1")
        val isMessage = trace.instruction.contains("\u53d1") || trace.instruction.contains("\u6d88\u606f")
        return MacroCategory(
            appPackage = if (isWechat) "com.tencent.mm" else pkg,
            appName = if (isWechat) "\u5fae\u4fe1" else (trace.appName ?: pkg),
            domain = if (isMessage) MacroDomain.MESSAGE else MacroDomain.CUSTOM,
            skillId = if (isWechat && isMessage) "wechat_send_message" else "custom_${trace.id}",
            displayName = trace.instruction.take(24)
        )
    }
}
