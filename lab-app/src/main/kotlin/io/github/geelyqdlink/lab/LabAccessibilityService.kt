package io.github.geelyqdlink.lab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class LabAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: LabAccessibilityService? = null

        fun dispatchTap(displayId: Int, x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .setDisplayId(displayId)
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }
    }

    override fun onServiceConnected() {
        instance = this
        LabLog.event("ACCESSIBILITY_CONNECTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

