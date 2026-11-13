package com.eysnatch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent

class EySnatchAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun perform(action: EyeAction) {
        when (action) {
            EyeAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            EyeAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            EyeAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            EyeAction.SCREENSHOT ->
                if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            EyeAction.SWIPE_LEFT -> swipe(0.8f, 0.5f, 0.2f, 0.5f)
            EyeAction.SWIPE_RIGHT -> swipe(0.2f, 0.5f, 0.8f, 0.5f)
            EyeAction.SCROLL_UP -> swipe(0.5f, 0.3f, 0.5f, 0.7f)
            EyeAction.SCROLL_DOWN -> swipe(0.5f, 0.7f, 0.5f, 0.3f)
        }
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val m = resources.displayMetrics
        val path = Path().apply {
            moveTo(m.widthPixels * x1, m.heightPixels * y1)
            lineTo(m.widthPixels * x2, m.heightPixels * y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build()
        dispatchGesture(g, null, null)
    }

    companion object {
        @Volatile var instance: EySnatchAccessibilityService? = null
    }
}
