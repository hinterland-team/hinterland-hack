package com.novaremote.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// performs the remote taps/swipes/text on the robot. android only runs this once the dev switches
// it on in Settings - Accessibility, and they can switch it off any time. the embedded server
// never injects input directly; it goes through here, and only for a paired client.
class RemoteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // a single tap at screen pixel (x, y)
    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, 0, 60)
    }

    // a swipe/drag from (x1,y1) to (x2,y2) over durationMs
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatch(path, 0, durationMs.coerceIn(20, 2000))
    }

    private fun dispatch(path: Path, startMs: Long, durMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, startMs, durMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        runCatching { dispatchGesture(gesture, null, null) }
    }

    // one of the system buttons
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // type into the currently focused text field; returns false if there is no editable focus
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findEditableFocus(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditableFocus(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val found = findEditableFocus(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set

        val enabled: Boolean get() = instance != null
    }
}
