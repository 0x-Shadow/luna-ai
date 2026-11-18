package com.pal9000.robot

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService
import android.util.Log
import kotlin.jvm.Volatile

class AutomationBridge(private val ctx: Context) {

    companion object { private const val TAG = "PAL9000" }

    private val pm = ctx.packageManager
    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    @Volatile private var lastForegroundPkg: String? = null

    fun listApps(): List<Map<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val acts = pm.queryIntentActivities(intent, 0)
        return acts.map { ai ->
            val pkgName = ai.activityInfo?.packageName ?: ""
            val label = ai.loadLabel(pm)?.toString() ?: pkgName
            mapOf("label" to label, "pkg" to pkgName)
        }.sortedBy { it["label"]?.toString()?.lowercase() ?: "" }
    }

    private fun resolveApp(query: String): String? {
        val spoken = query.lowercase().trim()
        // An empty query (panel sent an unparseable payload) used to fall through
        // to startsWith("") and match the first app in the launcher list — every
        // "open X" silently opened the same wrong app.
        if (spoken.isEmpty()) return null
        // Greek recognizers return the app name transliterated; map the common
        // ones back to the launcher label instead of failing to match.
        val raw = when {
            spoken.contains("σποτιφ") -> "spotify"
            spoken.contains("γιουτιουμπ") || spoken.contains("γιουτιούμπ") || spoken.contains("γιου τιουμπ") -> "youtube"
            spoken.contains("ινσταγκραμ") -> "instagram"
            else -> spoken
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val acts = pm.queryIntentActivities(intent, 0)
        val labels = acts.map { it to (it.loadLabel(pm)?.toString() ?: "").lowercase() }
        val queries = when {
            raw == "yt" || raw == "youtube" || raw == "you tube" -> listOf("youtube")
            raw.replace(" ", "") == "ytmusic" || raw == "youtube music" || raw == "yt music" ->
                listOf("yt music", "youtube music")
            raw == "insta" -> listOf("instagram")
            else -> listOf(raw)
        }
        for (q in queries) {
            return labels.firstOrNull { it.second == q }?.first?.activityInfo?.packageName
                ?: labels.firstOrNull { it.second.startsWith(q) }?.first?.activityInfo?.packageName
                ?: labels.firstOrNull { it.second.contains(q) }?.first?.activityInfo?.packageName
        }
        return null
    }

    fun launchApp(query: String): Map<String, Any?> {
        val pkg = resolveApp(query) ?: return mapOf("ok" to false, "error" to "no app matches '$query'")
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return mapOf("ok" to false, "error" to "cannot launch")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            ctx.startActivity(launch)
        } catch (e: Exception) {
            return mapOf("ok" to false, "error" to "start failed: ${e.message}")
        }
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

    fun snapshot(): Map<String, Any?> {
        val svc = LoonaAccessibilityService.instance
        // Prefer a fresh window: between events (or right after an app launches)
        // the cached rootNode is stale, and tapLabel's verifyEffect compares
        // element counts — a stale snapshot made every tap look like a no-op.
        val fresh = svc?.requestSnapshot()
        val root = fresh ?: svc?.rootNode ?: return mapOf(
            "ok" to false, "error" to "accessibility service not running",
            "elements" to emptyList<Map<String, Any>>()
        )
        Log.d(TAG, "[Automation] snapshot ok: instance=${svc != null} fresh=${fresh != null} cached=${svc?.rootNode != null} topPkg=${root.packageName} children=${root.childCount}")
        val entries = mutableListOf<Map<String, Any?>>()
        collect(root, entries)
        return mapOf(
            "ok" to true,
            "elements" to entries,
            "topPackage" to svc?.rootNode?.packageName
        )
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<Map<String, Any?>>, depth: Int = 0) {
        // getChild() returns null for nodes that vanish mid-walk; a non-null
        // parameter here turned that into a process-killing NPE.
        if (n == null || depth > 100) return
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
        } catch (e: Exception) { /* ignore */ }
        // Never recycle the root: snapshot() hands it to findElement()/tapLabel()
        // afterwards, and a recycled node crashes or returns nothing. Child nodes
        // are small and GC'd; the root is recycled when requestSnapshot() replaces it.
    }

    private fun boundsToList(n: AccessibilityNodeInfo): List<Int> {
        val r = android.graphics.Rect()
        n.getBoundsInScreen(r)
        return listOf(r.left, r.top, r.right, r.bottom)
    }

    private fun findElement(label: String): AccessibilityNodeInfo? {
        val svc = LoonaAccessibilityService.instance ?: return null
        val root = svc.rootNode ?: return null
        val want = label.lowercase()
        return findExact(root, want) ?: findContains(root, want)
    }

    private fun findExact(n: AccessibilityNodeInfo?, want: String): AccessibilityNodeInfo? {
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

    private fun findContains(n: AccessibilityNodeInfo?, want: String): AccessibilityNodeInfo? {
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

    private fun verifyEffect(beforeCount: Int): Boolean {
        Thread.sleep(300)
        val after = snapshot()
        val afterElements = after["elements"] as? List<Map<String, Any?>> ?: emptyList<Map<String, Any?>>()
        return afterElements.size != beforeCount
    }

    fun tapLabel(label: String): Map<String, Any?> {
        val svc = LoonaAccessibilityService.instance ?: return mapOf("ok" to false, "error" to "accessibility service not running")
        val root = svc.rootNode ?: return mapOf("ok" to false, "error" to "no screen content")
        val before = (snapshot()["elements"] as? List<Map<String, Any?>>)?.size ?: 0
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

    /**
     * Press the player's own Play button. Spotify keeps its media session
     * paused when it is cold-opened and silently ignores media keys, so
     * "play music" left it sitting on the home screen with nothing playing.
     */
    fun pressPlay(): Map<String, Any?> {
        val svc = LoonaAccessibilityService.instance
            ?: return mapOf("ok" to false, "error" to "accessibility service not running")
        val root = svc.requestSnapshot() ?: svc.rootNode
            ?: return mapOf("ok" to false, "error" to "no screen content")
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.contains("spotify") && !pkg.contains("music") && !pkg.contains("youtube")) {
            return mapOf("ok" to false, "error" to "not a media app: $pkg")
        }
        for (label in listOf("play", "αναπαραγωγή", "resume")) {
            val node = findElement(label) ?: continue
            try {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return mapOf("ok" to true, "label" to label, "pkg" to pkg)
                }
            } catch (e: Exception) {
                Log.w(TAG, "[Automation] play tap failed: ${e.message}")
            }
        }
        return mapOf("ok" to false, "error" to "no play control in $pkg")
    }

    /* ── search results: "play X on YouTube" has to actually start something ── */

    /** Labels that belong to app chrome, never to a search result. */
    private val chromeLabels = setOf(
        "home", "shorts", "subscriptions", "subscribed", "search", "create", "you",
        "library", "explore", "notifications", "menu", "back", "filter", "voice search",
        "trending", "music", "gaming", "news", "settings", "sign in", "share", "cast"
    )

    private fun nodeLabel(n: AccessibilityNodeInfo): String =
        (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString() ?: "").trim()

    /**
     * Tap the first result that matches the search query, scrolling the list
     * when nothing on screen matches. Used right after a YouTube search so the
     * command plays something instead of leaving a list of results open.
     */
    fun tapSearchResult(query: String, pkgHint: String = "youtube"): Map<String, Any?> {
        val svc = LoonaAccessibilityService.instance
            ?: return mapOf("ok" to false, "error" to "accessibility service not running")
        val tokens = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in chromeLabels }
        var reason = "no result matched '$query'"
        // Results can sit below a full-screen ad; keep scrolling and looking.
        for (attempt in 0 until 5) {
            val root = svc.requestSnapshot() ?: svc.rootNode
                ?: return mapOf("ok" to false, "error" to "no screen content")
            // Only tap inside the expected app: the search intent may have
            // landed in a browser, where the "first result" is a random link.
            val pkg = root.packageName?.toString() ?: ""
            if (!pkg.contains(pkgHint, ignoreCase = true)) {
                return mapOf("ok" to false, "error" to "top window is $pkg, not $pkgHint")
            }
            // YouTube sometimes lands on the autocomplete list instead of
            // results. Pick a suggestion (that runs the search) and look again
            // on the next pass instead of tapping the search box.
            val suggestion = findSuggestion(root)
            if (suggestion != null) {
                suggestion.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(2500)
                continue
            }
            // Match the actual title first and keep scrolling until it shows
            // up. Blindly tapping the first row played unrelated videos (the
            // top of the list is an ad plus "recommended" filler).
            val youtube = pkg.contains("youtube", ignoreCase = true)
            val target = findResultNode(root, tokens)
                ?: (if (youtube && attempt >= 3) findPlayableRow(root) else null)
                ?: (if (!youtube) findVideoRow(root) else null)
            if (target != null) {
                val label = nodeLabel(target)
                val clicked = try {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (e: Exception) { false }
                if (clicked) {
                    Thread.sleep(700)
                    return mapOf(
                        "ok" to true,
                        "label" to label,
                        "evidence" to ("tapped: " + label.take(80))
                    )
                }
                reason = "click rejected on \"${label.take(60)}\""
            }
            val scroller = findScrollable(root) ?: break
            scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(1200)
        }
        return mapOf("ok" to false, "error" to reason)
    }

    /** Node whose text/description contains one of the query words, promoted to
     *  its nearest clickable ancestor (a result row's title is not clickable). */
    private fun findResultNode(n: AccessibilityNodeInfo?, tokens: List<String>): AccessibilityNodeInfo? {
        if (n == null) return null
        // Never match the search box: its text IS the query, so it looks like a
        // perfect result and the "tap" just focuses an empty field (Spotify
        // then kept playing whatever was already queued).
        val cls = n.className?.toString() ?: ""
        val isSearchBox = cls.contains("EditText", ignoreCase = true) || n.isEditable
        val label = nodeLabel(n).lowercase()
        val isSuggestion = label.contains("suggestion")
        // Results start below the toolbar; the search bar itself sits up top
        // and carries the query as its text, so it always looks like a match.
        val r = android.graphics.Rect()
        n.getBoundsInScreen(r)
        val inToolbar = r.top < 180
        if (!isSearchBox && !isSuggestion && !inToolbar && label.length >= 6 && tokens.isNotEmpty() &&
            tokens.any { label.contains(it) } && label !in chromeLabels
        ) {
            clickableAncestor(n)?.let { return it }
        }
        for (i in 0 until n.childCount) {
            findResultNode(n.getChild(i), tokens)?.let { return it }
        }
        return null
    }

    /** A clickable autocomplete row ("Edit suggestion despacito"). */
    private fun findSuggestion(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val desc = (n.contentDescription?.toString() ?: "").lowercase()
        if (desc.contains("suggestion") && n.isClickable) return n
        for (i in 0 until n.childCount) {
            findSuggestion(n.getChild(i))?.let { return it }
        }
        return null
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable) return cur
            cur = try { cur.parent } catch (e: Exception) { null }
            depth++
        }
        return null
    }

    /** YouTube result rows announce themselves as "… – play video"; skip ads. */
    private fun findPlayableRow(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val desc = (n.contentDescription?.toString() ?: "").lowercase()
        if (desc.contains("play video") && !desc.contains("sponsored") &&
            !desc.contains("advertisement")
        ) {
            return clickableAncestor(n) ?: n
        }
        for (i in 0 until n.childCount) {
            findPlayableRow(n.getChild(i))?.let { return it }
        }
        return null
    }

    /** Last resort: a clickable row with a long label — a YouTube result tile's
     *  contentDescription is "title, channel, views…"; chrome labels are short. */
    private fun findVideoRow(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isClickable) {
            val label = nodeLabel(n)
            val low = label.lowercase()
            if (label.length >= 20 && low !in chromeLabels &&
                !low.contains("sponsored") && !low.contains("advertisement")
            ) return n
        }
        for (i in 0 until n.childCount) {
            findVideoRow(n.getChild(i))?.let { return it }
        }
        return null
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            findScrollable(n.getChild(i))?.let { return it }
        }
        return null
    }

    fun scroll(label: String?, down: Boolean): Map<String, Any?> {
        val svc = LoonaAccessibilityService.instance ?: return mapOf("ok" to false, "error" to "accessibility service not running")
        val root = svc.rootNode ?: return mapOf("ok" to false, "error" to "no screen content")
        val before = (snapshot()["elements"] as? List<Map<String, Any?>>)?.size ?: 0
        val node = if (label != null) findElement(label) else null ?: root
        try {
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val ok = node?.performAction(action) ?: false
            if (!ok) return mapOf("ok" to false, "error" to "scroll not performed")
            Thread.sleep(400)
            val changed = verifyEffect(before)
            return mapOf("ok" to true, "evidence" to (if (changed) "scrolled" else "scroll sent (no visible change)"))
        } catch (e: Exception) {
            return mapOf("ok" to false, "error" to "scroll failed: ${e.message}")
        }
    }

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

    fun pressBack(): Map<String, Any?> {
        try {
            // Real BACK via the accessibility service (enabled by the user). A
            // HOME press is NOT back — it leaves the current app instead of
            // navigating one screen back inside it.
            val acc = com.pal9000.robot.LoonaAccessibilityService.instance
            if (acc != null) {
                acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                return mapOf("ok" to true, "evidence" to "back via accessibility service")
            }
            // Fallback: inject a back key down/up through the input manager.
            ctx.getSystemService(Context.INPUT_METHOD_SERVICE)?.let { im ->
                // no-op kept for future use
            }
            return mapOf("ok" to false, "error" to "accessibility service not enabled — enable PAL9000 in Settings › Accessibility")
        } catch (e: Exception) {
            return mapOf("ok" to false, "error" to "back failed: ${e.message}")
        }
    }

    fun runTask(taskId: String, steps: List<Map<String, String?>>): Map<String, Any?> {
        val results = mutableListOf<Map<String, Any?>>()
        for ((i, step) in steps.withIndex()) {
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
                return mapOf("ok" to false, "stoppedAt" to i, "results" to results)
            }
        }
        return mapOf("ok" to true, "results" to results)
    }
}
