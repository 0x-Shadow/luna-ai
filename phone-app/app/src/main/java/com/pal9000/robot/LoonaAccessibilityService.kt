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
import kotlin.jvm.Volatile

class LoonaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: LoonaAccessibilityService? = null
        private const val TAG = "PAL9000"
    }

    @Volatile var rootNode: AccessibilityNodeInfo? = null
        private set

    private val _windowEvents = MutableSharedFlow<AccessibilityEvent>(replay = 1, extraBufferCapacity = 8)

    val windowEvents: SharedFlow<AccessibilityEvent> = _windowEvents.asSharedFlow()

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

    /** Ask the service for the current window right now. onAccessibilityEvent only
     *  updates rootNode when something changes, so a snapshot taken between events
     *  (or right after launch) came back empty — the agent could never see a
     *  screen to tap. */
    fun requestSnapshot(): AccessibilityNodeInfo? {
        // getRootInActiveWindow() can legitimately return null right after the
        // service connects or while an app is still launching; retry briefly
        // instead of handing a null back up.
        for (i in 0 until 3) {
            val r = getRootInActiveWindow()
            if (r != null) {
                rootNode?.recycle()
                rootNode = r
                return r
            }
            if (i < 2) try { Thread.sleep(200) } catch (e: Exception) {}
        }
        return null
    }
}
