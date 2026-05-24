package com.macropilot.app.automation

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

object ScreenMetrics {
    data class Size(val width: Int, val height: Int, val source: String)

    fun touchSize(context: Context): Size {
        val candidates = mutableListOf<Size>()
        val resources = context.resources.displayMetrics
        candidates += Size(
            width = resources.widthPixels.coerceAtLeast(1),
            height = resources.heightPixels.coerceAtLeast(1),
            source = "resources"
        )

        MacroPilotAccessibilityService.instance?.lastScreenshotSize()?.let { (width, height) ->
            if (width > 0 && height > 0) {
                candidates += Size(width, height, "accessibility_screenshot")
            }
        }

        val windowManager = runCatching {
            context.getSystemService(WindowManager::class.java)
        }.getOrNull()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { windowManager?.currentWindowMetrics?.bounds }.getOrNull()?.let { bounds ->
                if (bounds.width() > 0 && bounds.height() > 0) {
                    candidates += Size(bounds.width(), bounds.height(), "current_window_metrics")
                }
            }
            runCatching {
                val display = context.display ?: return@runCatching null
                DisplayMetrics().also { display.getRealMetrics(it) }
            }.getOrNull()?.let { metrics ->
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    candidates += Size(metrics.widthPixels, metrics.heightPixels, "display_real_metrics")
                }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                DisplayMetrics().also { windowManager?.defaultDisplay?.getRealMetrics(it) }
            }.getOrNull()?.let { metrics ->
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    candidates += Size(metrics.widthPixels, metrics.heightPixels, "display_real_metrics")
                }
            }
        }

        return candidates
            .filter { it.width > 0 && it.height > 0 }
            .maxWithOrNull(compareBy<Size> { it.width * it.height }.thenBy { it.height })
            ?: Size(1, 1, "fallback")
    }
}
