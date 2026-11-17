package com.pal9000.robot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Minimal accessibility service for Loona app control.
 *
 * Exposes the root node of the active window via [rootNode] so AutomationBridge
 * can traverse the UI tree and perform tap/scroll/text actions. The user must
 * enable this service in Android Settings › Accessibility before app control
 * works.
 */
class LoonaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: LoonaAccessibilityService? = null
    }

    private const val TAG = "PAL9000"

    /** Latest root node of the active window; null when the service is not connected. */
    @Volatile var rootNode: AccessibilityNodeInfo? = null
        private set

    /** Window change events for observing foreground app switches. */
    val windowEvents: SharedFlow<AccessibilityEvent> = _windowEvents.asSharedFlow()
    private val _windowEvents = MutableSharedFlow<AccessibilityEvent>(replay = 1, extraBufferCapacity = 8)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo?.let { info ->
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = info
        }
        Log.i(TAG, "[Accessibility] service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        rootNode = null
        Log.i(TAG, "[Accessibility] service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        rootNode = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            _windowEvents.tryEmit(event)
            val root = event.source ?: getRootInActiveWindow()
            root?.let { newRoot ->
                // Release the previous root to avoid holding recycled nodes.
                rootNode?.recycle()
                rootNode = newRoot
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Accessibility] event failed: ${e.message}")
        }
    }

    override fun onInterrupt() {
        rootNode = null
    }
}
