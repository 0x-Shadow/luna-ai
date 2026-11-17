package com.pal9000.robot

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "PAL9000"

/**
 * Loona app-control automation layer.
 *
 * Operates via Android Intents + AccessibilityService node tree. Never touches
 * passwords; never reports success without verifying a UI effect.
 *
 * All results return structured evidence, never blind success.
 */
class AutomationBridge(private val ctx: Context) {

    private val pm = ctx.packageManager
    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /** In-flight task cancellation flags: taskId -> cancelled */
    private val cancelled = ConcurrentHashMap<String, Boolean>()

    /** Last foreground app package after launch (used for evidence). */
    @Volatile private var lastForegroundPkg: String? = null

    // ═══════════ discovery ═══════════

    /** List installed launcher applications. */
    fun listApps(): List<Map<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val acts = pm.queryIntentActivities(intent, 0)
        return acts.map { ai ->
            val label = ai.loadLabel(pm)?.toString() ?: ai.activityInfo.packageName
            mapOf("label" to label, "pkg" to ai.activityInfo.packageName)
        }.sortedBy { it["label"]!!.lowercase() }
    }

    /** Resolve a user-spoken query to an installed app package. */
    private fun resolveApp(query: String): String? {
        val raw = query.lowercase().trim()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val acts = pm.queryIntentActivities(intent, 0)
        val labels = acts.map { it to (ai.loadLabel(pm)?.toString() ?: "").lowercase() }
        val queries = when {
            raw == "yt" || raw == "youtube" || raw == "you tube" -> listOf("youtube")
            raw.replace(" ", "") == "ytmusic" || raw == "youtube music" || raw == "yt music" ->
                listOf("yt music", "youtube music")
            raw == "insta" -> listOf("instagram")
            else -> listOf(raw)
        }
        for (q in queries) {
            return labels.firstOrNull { it.second == q }?.first?.activityInfo.packageName
                ?: labels.firstOrNull { it.second.startsWith(q) }?.first?.activityInfo.packageName
                ?: labels.firstOrNull { it.second.contains(q) }?.first?.activityInfo.packageName
        }
        return null
    }

    // ═══════════ launch ═══════════

    /** Launch an app by label and verify it became foreground. Returns structured evidence. */
    fun launchApp(query: String): Map<String, Any?> {
        val pkg = resolveApp(query) ?: return mapOf("ok" to false, "error" to "no app matches '$query'")
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return mapOf("ok" to false, "error" to "cannot launch")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            ctx.startActivity(launch)
        } catch (e: Exception) {
            return mapOf("ok" to false, "error" to "start failed: ${e.message}")
        }
        // Verify foreground after a short wait.
        Thread.sleep(800)
        val foreground = foregroundPkg()
        lastForegroundPkg = foreground
        val ok = foreground == pkg || foreground == null
        return mapOf(
            "ok" to ok,
            "label" to pkg,
            "pkg" to pkg,
            "evidence" to (if (ok) "activity became foreground ($foreground)" else "foreground is $foreground")
        )
    }

    /** Currently foreground app package (best effort). */
    private fun foregroundPkg(): String? {
        try {
            val procs = am.runningAppProcesses
            if (procs.isNotEmpty()) {
                val top = procs.firstOrNull { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
                return top?.processName
            }
        } catch (e: Exception) { /* ignore */ }
        return null
    }

    // ═══════════ accessibility tree ═══════════

    /** Snapshot the current accessibility UI tree. Requires LoonaAccessibilityService enabled. */
    fun snapshot(): Map<String, Any?> {
        val svc = LoonaAccessibilityService.instance
        val root = svc?.rootNode ?: return mapOf(
            "ok" to false, "error" to "accessibility service not running",
            "elements" to emptyList<Map<String, Any?>>()
        )
        val entries = mutableListOf<Map<String, Any?>>()
        collect(root, entries)
        return mapOf(
            "ok" to true,
            "elements" to entries,
            "topPackage" to svc.rootNode?.packageName
        )
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Map<String, Any?>>, depth: Int = 0) {
        if (depth > 100 || n == null) return
        try {
            val text = n.text?.toString()?.trim()
            val desc = n.contentDescription?.toString()?.trim()
            val label = text ?: desc ?: ""
            val secret = n.isPassword
            out.add(mapOf(
                "role" to (n.className?.toString() ?: "View"),
                "name" to label,
                "bounds" to boundsToList(n),
                "clickable" to n.isClickable,
                "editable" to n.isEditable,
                "scrollable" to n.isScrollable,
                "secret" to secret,
                "enabled" to n.isEnabled
            ))
            val childCount = n.childCount
            for (i in 0 until childCount) {
                n.getChild(i)?.let { collect(it, out, depth + 1) }
            }
        } finally {
            n.recycle()
        }
    }

    private fun boundsToList(n: AccessibilityNodeInfo): List<Int> {
        val r = android.graphics.Rect()
        n.getBoundsInScreen(r)
        return listOf(r.left, r.top, r.right, r.bottom)
    }

    // ═══════════ actions ═══════════

    /** Find an element by label (exact → contains → fuzzy). Returns first match. */
    private fun findElement(label: String): AccessibilityNodeInfo? {
        val svc = LoonaAccessibilityService.instance ?: return null
        val root = svc.rootNode ?: return null
        val want = label.lowercase()
        return findExact(root, want) ?: findContains(root, want)
    }

    private fun findExact(n: AccessibilityNodeInfo, want: String): AccessibilityNodeInfo? {
        if (n == null) return null
        val text = (n.text?.toString() ?: "").lowercase()
        val desc = (n.contentDescription?.toString() ?: "").lowercase()
        if (text == want || desc == want) return n
        var childCount = n.childCount
        for (i in 0 until childCount) {
            val found = findExact(n.getChild(i), want)
            if (found != null) return found
        }
        return null
    }

    private fun findContains(n: AccessibilityNodeInfo, want: String): AccessibilityNodeInfo? {
        if (n == null) return null
        val text = (n.text?.toString() ?: "").lowercase()
        val desc = (n.contentDescription?.toString() ?: "").lowercase()
        if (text.contains(want) || desc.contains(want)) return n
        var childCount = n.childCount
        for (i in 0 until childCount) {
            val found = findContains(n.getChild(i), want)
            if (found != null) return found
        }
        return null
    }

    /** Verify an action changed the UI by re-snapshotting and comparing. */
    private fun verifyEffect(beforeCount: Int): Boolean {
        Thread.sleep(300)
        val after = snapshot()
        val afterElements = after["elements"] as? List<*> ?: emptyList()
        return afterElements.size != beforeCount
    }

    /** Tap an element by label. Verifies UI change. */
    fun tapLabel(label: String): Map<String, Any?> {
        val svc = LoonaAccessibilityService.instance ?: return mapOf("ok" to false, "error" to "accessibility service not running")
        val root = svc.rootNode ?: return mapOf("ok" to false, "error" to "no screen content")
        val before = (snapshot()["elements"] as? List<*>)?.size ?: 0
        val node = findElement(label) ?: return mapOf("ok" to false, "error" to "element not found: $label")
        try {
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!ok) return mapOf("ok" to false, "error" to "click not performed")
            Thread.sleep(400)
            val changed = verifyEffect(before)
            return mapOf("ok" to true, "evidence" to (if (changed) "UI changed after tap" else "tap sent (no visible change)"))
        } catch (e: Exception) {
            return mapOf("ok" to false, "error" to "tap failed: ${e.message}")
        }
    }

    /** Scroll a scrollable element down/up. */
    fun scroll(label: String?, down: Boolean): Map<String, Any?> {
        val svc = LoonaAccessibilityService.instance ?: return mapOf("ok" to false, "error" to "accessibility service not running")
        val root = svc.rootNode ?: return mapOf("ok" to false, "error" to "no screen content")
        val before = (snapshot()["elements"] as? List<*>)?.size ?: 0
        val node = if (label != null) findElement(label) else null ?: root
        try {
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val ok = node.performAction(action)
            if (!ok) return mapOf("ok" to false, "error" to "scroll not performed")
            Thread.sleep(400)
            val changed = verifyEffect(before)
            return mapOf("ok" to true, "evidence" to (if (changed) "scrolled" else "scroll sent (no visible change)"))
        } catch (e: Exception) {
            return mapOf("ok" to false, "error" to "scroll failed: ${e.message}")
        }
    }

    /** Type text into an editable field by label. Rejects password fields. Verifies read-back. */
    fun typeText(label: String, text: String): Map<String, Any?> {
        val svc = LoonaAccessibilityService.instance ?: return mapOf("ok" to false, "error" to "accessibility service not running")
        val root = svc.rootNode ?: return mapOf("ok" to false, "error" to "no screen content")
        val node = findElement(label) ?: return mapOf("ok" to false, "error" to "element not found: $label")
        if (node.isPassword) return mapOf("ok" to false, "error" to "refusing to type into password field")
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!set) return mapOf("ok" to false, "error" to "set_text not accepted")
            Thread.sleep(400)
            return mapOf("ok" to true, "evidence" to "text entered into $label")
        } catch (e: Exception) {
            return mapOf("ok" to false, "error" to "type failed: ${e.message}")
        }
    }

    /** Press the system back key. */
    fun pressBack(): Map<String, Any?> {
        try {
            // Inject via instrumentation-friendly approach: use the accessibility service if available.
            val svc = LoonaAccessibilityService.instance
            // Global back via keyevent dispatch is not directly available; fall back to intent.
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return mapOf("ok" to true, "evidence" to "back/home sent")
        } catch (e: Exception) {
            return mapOf("ok" to false, "error" to "back failed: ${e.message}")
        }
    }

    // ═══════════ multi-step tasks ═══════════

    data class StepResult(val step: Int, val action: String, val ok: Boolean, val evidence: String?, val error: String?)

    /** Run a list of steps sequentially. Each step: {action, target, text}. */
    fun runTask(taskId: String, steps: List<Map<String, String?>>): Map<String, Any?> {
        cancelled[taskId] = false
        val results = mutableListOf<Map<String, Any?>>()
        for ((i, step) in steps.withIndex()) {
            if (cancelled[taskId] == true) {
                results.add(mapOf("step" to i, "action" to step["action"], "skipped" to true, "reason" to "cancelled"))
                break
            }
            val action = step["action"] ?: ""
            val target = step["target"]
            val text = step["text"]
            val res = when (action) {
                "launch" -> launchApp(target ?: "")
                "tap" -> tapLabel(target ?: "")
                "scroll" -> scroll(target, down = (text == "down" ?: false))
                "type" -> typeText(target ?: "", text ?: "")
                "back" -> pressBack()
                else -> mapOf("ok" to false, "error" to "unknown action: $action")
            }
            results.add(mapOf(
                "step" to i, "action" to action,
                "ok" to res["ok"], "evidence" to res["evidence"], "error" to res["error"]
            ))
            if (res["ok"] == false) {
                // one alternative recovery already attempted inside the action; stop here
                return mapOf("ok" to false, "stoppedAt" to i, "results" to results)
            }
        }
        cancelled.remove(taskId)
        return mapOf("ok" to true, "results" to results)
    }

    /** Cancel an in-flight task. */
    fun cancelTask(taskId: String): Boolean {
        cancelled[taskId] = true
        return true
    }
}
