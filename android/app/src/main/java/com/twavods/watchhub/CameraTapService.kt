package com.twavods.watchhub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Presses buttons in the Xiaomi camera app on behalf of the watch.
 * Only active for the package com.android.camera (see res/xml/camera_tap.xml).
 */
class CameraTapService : AccessibilityService() {

    companion object {
        @Volatile var instance: CameraTapService? = null
            private set
        @Volatile var lastAction = ""
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tapShutter(): Boolean {
        if (clickNode(
                ids = listOf("shutter"),
                descriptions = listOf("shutter", "sluiter", "foto maken", "take photo", "capture")
            )
        ) { lastAction = "sluiter: getikt op gevonden knop"; return true }
        lastAction = "sluiter: tik op positie"
        return tapAt(0.5f, Prefs.shutterY(this))
    }

    fun tapSwitch(): Boolean {
        if (clickNode(
                ids = listOf("switch_camera", "camera_switch", "switch_cam", "toggle_camera", "camera_picker"),
                descriptions = listOf("switch camera", "wissel", "omdraaien", "front", "voorcamera", "flip")
            )
        ) { lastAction = "wissel: getikt op gevonden knop"; return true }
        lastAction = "wissel: tik op positie"
        // On the Xiaomi camera the switch button sits to the right of the shutter
        return tapAt(0.92f, Prefs.shutterY(this))
    }

    private fun clickNode(ids: List<String>, descriptions: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "com.android.camera") return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (node.isVisibleToUser &&
                (ids.any { id.contains(it) } || descriptions.any { desc.contains(it) })
            ) {
                // The Xiaomi camera ignores accessibility "clicks", so we touch
                // the centre of the button with a real gesture instead.
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                val (sw, sh) = Screen.size(this)
                // Only accept something button-sized in the lower part of the screen
                if (r.width() in 11 until sw / 2 && r.height() in 11 until sh / 4 && r.centerY() > sh * 0.6f) {
                    return tapPx(r.exactCenterX(), r.exactCenterY())
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    private fun tapAt(fx: Float, fy: Float): Boolean {
        val (w, h) = Screen.size(this)
        return tapPx(fx * w, fy * h)
    }

    private fun tapPx(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
