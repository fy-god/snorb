package com.macropilot.app.runtime

import android.content.Context
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.automation.ScreenMetrics
import com.macropilot.app.model.UiStateSample

class NodeInspector(private val context: Context) {
    fun currentState(): UiStateSample? {
        val service = MacroPilotAccessibilityService.instance ?: return null
        val metrics = ScreenMetrics.touchSize(context)
        val nodes = service.snapshotNodes() ?: return null
        val nodePackage = nodes
            .asSequence()
            .mapNotNull { it.packageName }
            .filter { it != "com.android.systemui" }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
        return UiStateSample(
            appPackage = nodePackage ?: service.activePackageName(),
            windowClassName = service.activeWindowClassName(),
            screenWidth = metrics.width,
            screenHeight = metrics.height,
            timestamp = System.currentTimeMillis(),
            nodes = nodes,
            eventsSinceLastState = service.consumeRecentEvents()
        )
    }
}
