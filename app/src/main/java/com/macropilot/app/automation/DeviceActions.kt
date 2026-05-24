package com.macropilot.app.automation

import android.accessibilityservice.GestureDescription
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.KeyguardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import com.macropilot.app.model.CoordinatePack
import com.macropilot.app.model.ElementMatcher
import com.macropilot.app.model.NormalizedPoint

class DeviceActions(
    private val context: Context,
    private val serviceProvider: () -> MacroPilotAccessibilityService? = { MacroPilotAccessibilityService.instance }
) {
    fun home(): ActionOutcome {
        val service = serviceProvider() ?: return ActionOutcome(false, "Accessibility service unavailable")
        val ok = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        return ActionOutcome(ok, if (ok) "GLOBAL_ACTION_HOME performed" else "GLOBAL_ACTION_HOME rejected")
    }

    fun back(): ActionOutcome {
        val service = serviceProvider() ?: return ActionOutcome(false, "Accessibility service unavailable")
        val ok = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        return ActionOutcome(ok, if (ok) "GLOBAL_ACTION_BACK performed" else "GLOBAL_ACTION_BACK rejected")
    }

    fun dismissNotificationShade(): ActionOutcome {
        val service = serviceProvider() ?: return ActionOutcome(false, "Accessibility service unavailable")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ActionOutcome(false, "GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE requires Android 12+")
        }
        val ok = service.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
        )
        return ActionOutcome(
            ok,
            if (ok) "GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE performed" else "GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE rejected"
        )
    }

    fun openApp(packageName: String): ActionOutcome {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: if (packageName == "com.android.settings") {
                Intent(Settings.ACTION_SETTINGS)
            } else {
                null
            }
            ?: return ActionOutcome(false, "No launch intent for $packageName")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ActionOutcome(true, "Launch intent sent for $packageName")
    }

    fun openAppLauncherRoot(packageName: String): ActionOutcome {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: if (packageName == "com.android.settings") {
                Intent(Settings.ACTION_SETTINGS)
            } else {
                null
            }
            ?: return ActionOutcome(false, "No launcher root intent for $packageName")

        intent.action = Intent.ACTION_MAIN
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        context.startActivity(intent)
        return ActionOutcome(true, "Launcher root intent sent for $packageName")
    }

    fun openAppExplicitLauncher(packageName: String): ActionOutcome {
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val activity = context.packageManager.queryIntentActivities(launcherIntent, 0)
            .firstOrNull()
            ?: return ActionOutcome(false, "No explicit launcher activity for $packageName")
        val component = ComponentName(activity.activityInfo.packageName, activity.activityInfo.name)
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        context.startActivity(intent)
        return ActionOutcome(true, "Explicit launcher activity sent for $packageName/$component")
    }

    fun isInteractive(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        return powerManager.isInteractive
    }

    fun isKeyguardLocked(): Boolean {
        val keyguardManager = context.getSystemService(KeyguardManager::class.java) ?: return false
        return keyguardManager.isKeyguardLocked
    }

    fun ensureAccessibilityServiceEnabled(): ActionOutcome {
        val component = ComponentName(context, MacroPilotAccessibilityService::class.java).flattenToString()
        return runCatching {
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val services = current
                .split(':')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toMutableList()
            if (services.none { it.equals(component, ignoreCase = true) }) {
                services += component
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    services.joinToString(":")
                )
            }
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            ActionOutcome(
                success = MacroPilotAccessibilityService.instance != null ||
                    Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1,
                reason = "Accessibility secure settings repaired for $component"
            )
        }.getOrElse { error ->
            ActionOutcome(false, "Unable to repair accessibility settings: ${error.message}")
        }
    }

    fun isMacroPilotImeSelected(): Boolean {
        val selected = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?: return false
        return selected.contains(context.packageName) &&
            selected.contains(MacroPilotImeService::class.java.simpleName)
    }

    fun isMacroPilotImeReady(): Boolean {
        return isMacroPilotImeSelected() && MacroPilotImeService.instance?.hasActiveInputConnection() == true
    }

    fun ensureMacroPilotImeSelected(): ActionOutcome {
        if (isMacroPilotImeSelected()) {
            return ActionOutcome(true, "MacroPilot IME already selected")
        }
        val component = ComponentName(context, MacroPilotImeService::class.java).flattenToShortString()
        return runCatching {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, component)
            ActionOutcome(isMacroPilotImeSelected(), "MacroPilot IME selected through secure settings")
        }.getOrElse { error ->
            ActionOutcome(false, "Unable to select MacroPilot IME automatically: ${error.message}")
        }
    }

    fun commitTextWithMacroPilotIme(text: String): ActionOutcome {
        if (!isMacroPilotImeSelected()) {
            return ActionOutcome(false, "MacroPilot IME is not the selected input method")
        }
        val ime = MacroPilotImeService.instance
            ?: return ActionOutcome(false, "MacroPilot IME is not active")
        val committed = ime.commitMacroText(text)
        return ActionOutcome(committed, if (committed) "MacroPilot IME committed text" else "MacroPilot IME rejected text")
    }

    fun replaceTextWithMacroPilotIme(text: String): ActionOutcome {
        if (!isMacroPilotImeSelected()) {
            return ActionOutcome(false, "MacroPilot IME is not the selected input method")
        }
        val ime = MacroPilotImeService.instance
            ?: return ActionOutcome(false, "MacroPilot IME is not active")
        val replaced = ime.replaceMacroText(text)
        return ActionOutcome(replaced, if (replaced) "MacroPilot IME replaced text" else "MacroPilot IME rejected replace")
    }

    fun clickByMatcher(matchers: List<ElementMatcher>): ActionOutcome {
        val service = serviceProvider() ?: return ActionOutcome(false, "Accessibility service unavailable")
        val node = service.findFirstNode(matchers) ?: return ActionOutcome(false, "No matching node")
        val clicked = clickNodeOrClickableAncestor(node)
        return ActionOutcome(clicked, if (clicked) "ACTION_CLICK performed" else "ACTION_CLICK rejected")
    }

    fun clickByVisibleText(text: String): ActionOutcome {
        if (text.isBlank()) return ActionOutcome(false, "Visible text is blank")
        return clickByMatcher(
            listOf(
                ElementMatcher(textContains = text, enabled = true),
                ElementMatcher(contentDescContains = text, enabled = true)
            )
        )
    }

    fun setTextByMatcher(matchers: List<ElementMatcher>, text: String): ActionOutcome {
        val service = serviceProvider() ?: return ActionOutcome(false, "Accessibility service unavailable")
        val node = service.findFirstNode(matchers) ?: return ActionOutcome(false, "No matching editable node")
        if (!node.isEditable) return ActionOutcome(false, "Matched node is not editable")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val changed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return ActionOutcome(changed, if (changed) "ACTION_SET_TEXT performed" else "ACTION_SET_TEXT rejected")
    }

    fun setTextByEditableIndex(index: Int, text: String): ActionOutcome {
        val service = serviceProvider() ?: return ActionOutcome(false, "Accessibility service unavailable")
        val nodes = service.findNodes(listOf(ElementMatcher(editable = true, enabled = true)))
            .filter { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.width() > 8 && rect.height() > 8
            }
            .sortedWith(compareBy<AccessibilityNodeInfo> { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.top
            }.thenBy { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.left
            })
        val node = nodes.getOrNull(index)
            ?: return ActionOutcome(false, "No editable node at index=$index, count=${nodes.size}")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val changed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return ActionOutcome(changed, if (changed) "ACTION_SET_TEXT index=$index performed" else "ACTION_SET_TEXT index=$index rejected")
    }

    fun scrollForwardByMatcher(matchers: List<ElementMatcher>): ActionOutcome {
        val service = serviceProvider() ?: return ActionOutcome(false, "Accessibility service unavailable")
        val node = service.findFirstNode(matchers) ?: return ActionOutcome(false, "No matching scrollable node")
        if (!node.isScrollable) return ActionOutcome(false, "Matched node is not scrollable")
        val scrolled = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        return ActionOutcome(scrolled, if (scrolled) "ACTION_SCROLL_FORWARD performed" else "ACTION_SCROLL_FORWARD rejected")
    }

    fun scrollFirstScrollableForward(): ActionOutcome {
        return scrollForwardByMatcher(listOf(ElementMatcher(scrollable = true, enabled = true)))
    }

    fun tapCoordinate(pack: CoordinatePack, screenWidth: Int, screenHeight: Int): ActionOutcome {
        return tapNormalized(pack.fallbackTap, screenWidth, screenHeight)
    }

    fun tapNormalized(point: NormalizedPoint, screenWidth: Int, screenHeight: Int): ActionOutcome {
        val service = serviceProvider() ?: return ActionOutcome(false, "Accessibility service unavailable")
        val x = point.x.coerceIn(0f, 1f) * screenWidth
        val y = point.y.coerceIn(0f, 1f) * screenHeight
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        val dispatched = service.dispatchGesture(gesture, null, null)
        val detail = "normalized=%.3f,%.3f px=%d,%d screen=%dx%d".format(
            point.x.coerceIn(0f, 1f),
            point.y.coerceIn(0f, 1f),
            x.toInt(),
            y.toInt(),
            screenWidth,
            screenHeight
        )
        return ActionOutcome(
            dispatched,
            if (dispatched) "dispatchGesture tap sent ($detail)" else "dispatchGesture rejected ($detail)"
        )
    }

    fun longPressNormalized(point: NormalizedPoint, screenWidth: Int, screenHeight: Int, durationMs: Long = 850L): ActionOutcome {
        val service = serviceProvider() ?: return ActionOutcome(false, "Accessibility service unavailable")
        val x = point.x.coerceIn(0f, 1f) * screenWidth
        val y = point.y.coerceIn(0f, 1f) * screenHeight
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(400L, 2_000L)))
            .build()
        val dispatched = service.dispatchGesture(gesture, null, null)
        return ActionOutcome(dispatched, if (dispatched) "dispatchGesture long press sent" else "dispatchGesture long press rejected")
    }

    fun swipeNormalized(
        start: NormalizedPoint,
        end: NormalizedPoint,
        screenWidth: Int,
        screenHeight: Int,
        durationMs: Long = 220L
    ): ActionOutcome {
        val service = serviceProvider() ?: return ActionOutcome(false, "Accessibility service unavailable")
        val startX = start.x.coerceIn(0f, 1f) * screenWidth
        val startY = start.y.coerceIn(0f, 1f) * screenHeight
        val endX = end.x.coerceIn(0f, 1f) * screenWidth
        val endY = end.y.coerceIn(0f, 1f) * screenHeight
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(120L, 1_000L)))
            .build()
        val dispatched = service.dispatchGesture(gesture, null, null)
        val detail = "start=%.3f,%.3f end=%.3f,%.3f px=%d,%d->%d,%d screen=%dx%d".format(
            start.x.coerceIn(0f, 1f),
            start.y.coerceIn(0f, 1f),
            end.x.coerceIn(0f, 1f),
            end.y.coerceIn(0f, 1f),
            startX.toInt(),
            startY.toInt(),
            endX.toInt(),
            endY.toInt(),
            screenWidth,
            screenHeight
        )
        return ActionOutcome(
            dispatched,
            if (dispatched) "dispatchGesture swipe sent ($detail)" else "dispatchGesture swipe rejected ($detail)"
        )
    }

    fun swipeLeft(screenWidth: Int, screenHeight: Int): ActionOutcome {
        return swipeNormalized(NormalizedPoint(0.82f, 0.52f), NormalizedPoint(0.18f, 0.52f), screenWidth, screenHeight)
    }

    fun swipeRight(screenWidth: Int, screenHeight: Int): ActionOutcome {
        return swipeNormalized(NormalizedPoint(0.18f, 0.52f), NormalizedPoint(0.82f, 0.52f), screenWidth, screenHeight)
    }

    fun swipeUp(screenWidth: Int, screenHeight: Int): ActionOutcome {
        return swipeNormalized(NormalizedPoint(0.50f, 0.80f), NormalizedPoint(0.50f, 0.22f), screenWidth, screenHeight)
    }

    fun swipeDown(screenWidth: Int, screenHeight: Int): ActionOutcome {
        return swipeNormalized(NormalizedPoint(0.50f, 0.22f), NormalizedPoint(0.50f, 0.80f), screenWidth, screenHeight)
    }

    private fun clickNodeOrClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isEnabled && current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}

data class ActionOutcome(
    val success: Boolean,
    val reason: String
)
