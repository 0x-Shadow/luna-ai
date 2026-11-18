package com.pal9000.robot

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Native bridge for the Loona WebView.
 *
 * JS contract:
 *   PalBridge.call(requestId, method, payloadJson)
 *     → window.__palResult(requestId, ok, resultJson)
 *   push events:
 *     → window.__palEvent(name, eventJson)
 *
 * Methods: http, listen, stopListen, speak, stopSpeak, reminder, alarm,
 * timer, cancel, torch, brightness, volume, apps, openApp, settings,
 * status, battery, vibrate.
 */
class PalBridge(private val ctx: Context, private val emit: (String) -> Unit) {

    companion object {
        private const val TAG = "PAL9000"
        const val EXTRA_ID = "id"
        const val EXTRA_KIND = "kind"       // reminder | alarm | timer
        const val EXTRA_TITLE = "title"
        const val ITEM_PREFIX = "pal."
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    private val seq = AtomicInteger(0)

    private var speech: SpeechRecognizer? = null
    private var listenMode = "off"           // off | once | loop | conversation | whisper
    @Volatile private var lang = "en-US"     // BCP-47 tag for STT + TTS
    private var onDeviceOnly = true          // keep audio on the phone
    private var recognizerOnDevice = false

    // Whisper on the LAN (Wyoming protocol — the TrueNAS/Home-Assistant
    // whisper add-on). Audio goes nowhere else; empty url disables it.
    @Volatile private var whisperUrl = ""
    private var whisperOn = true
    // Configurable Whisper endpointing (ms), set via the `voice` bridge method.
    private var whisperNoSpeechMs = 2000L
    private var whisperEndMs = 450L
    private var capturing = false
    @Volatile private var recordRef: AudioRecord? = null
    private var captureThread: Thread? = null
    // Bumped on every listen/stop so a queued auto-restart can't resurrect a
    // session that was already replaced (that race killed the wake loop).
    @Volatile private var listenToken = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitAttempts = 0
    private var ttsQueue: MutableList<Pair<String, String>> = mutableListOf()

    /** Media session bridge — Spotify, YouTube Music, or any active session;
     * falls back to media key events when no session is available. */
    private val mediaSessionBridge = MediaSessionBridge(ctx)

    /** App-control automation layer: launch, tap, type, scroll, multi-step tasks. */
    private val automationBridge = AutomationBridge(ctx)

    // ── app access control (user-picked allow list for app control) ──
    private fun appAccessPrefs() = ctx.getSharedPreferences("pal_app_access", Context.MODE_PRIVATE)
    private fun appAccessEnabled(pkg: String): Boolean =
        appAccessPrefs().getBoolean(pkg, true)   // default: enabled
    fun setAppAccessEnabled(pkg: String, enabled: Boolean) {
        appAccessPrefs().edit().putBoolean(pkg, enabled).apply()
    }

    /** All launcher apps with their access status. */
    fun appsWithAccessJson(): String {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val acts = pm.queryIntentActivities(intent, 0)
        val arr = JSONArray()
        acts.sortedBy { it.loadLabel(pm).toString().lowercase() }.forEach { ai ->
            val pkg = ai.activityInfo.packageName
            arr.put(JSONObject()
                .put("label", ai.loadLabel(pm).toString())
                .put("pkg", pkg)
                .put("enabled", appAccessEnabled(pkg)))
        }
        return JSONObject().put("apps", arr).toString()
    }

    /* ═══════════ entry point from JS ═══════════ */

    @JavascriptInterface
    fun call(requestId: String, method: String, payloadJson: String) {
        try {
            val payload = if (payloadJson.isBlank()) JSONObject() else JSONObject(payloadJson)
            when (method) {
                "http" -> http(requestId, payload)
                "stream" -> stream(requestId, payload)
                "listen" -> listen(requestId, payload)
                "stopListen" -> { stopListen(); resolve(requestId, true, "{}") }
                "speak" -> speak(requestId, payload)
                "voice" -> voice(requestId, payload)
                "voices" -> voices(requestId, payload)
                "transcribe" -> transcribe(requestId, payload)
                "display" -> display(requestId, payload)
                "panel" -> resolve(requestId, true, JSONObject()
                    .put("url", panelUrl).put("running", panelUrl.isNotBlank()).toString())
                "eval" -> {
                    val r = evalInPage(payload.optString("js", ""))
                    resolve(requestId, r != null, JSONObject().put("result", r ?: "").toString())
                }
                "stopSpeak" -> { stopSpeak(); resolve(requestId, true, "{}") }
                "reminder", "alarm", "timer" -> schedule(requestId, method, payload)
                "cancel" -> cancel(requestId, payload)
                "torch" -> torch(requestId, payload)
                "brightness" -> brightness(requestId, payload)
                "volume" -> volume(requestId, payload)
                "apps" -> resolve(requestId, true, appsWithAccessJson())
                "appsWithAccess" -> resolve(requestId, true, appsWithAccessJson())
                "setAppAccess" -> {
                    val pkg = payload.optString("pkg", "")
                    val enabled = payload.optBoolean("enabled", true)
                    setAppAccessEnabled(pkg, enabled)
                    resolve(requestId, true, JSONObject().put("ok", true).put("pkg", pkg).put("enabled", enabled).toString())
                }
                "openApp" -> openApp(requestId, payload)
                "settings" -> openSettings(requestId, payload)
                "dial" -> dial(requestId, payload)
                "url" -> openUrl(requestId, payload)
                "search" -> search(requestId, payload)
                "youtube" -> youtube(requestId, payload)
                "spotify" -> spotify(requestId, payload)
                "navigate" -> navigate(requestId, payload)
                "media" -> media(requestId, payload)
                "ha" -> ha(requestId, payload)
                "automation" -> automation(requestId, payload)
                "notify" -> notify(requestId, payload)
                "callLog" -> resolve(requestId, true, callLogJson())
                "status" -> resolve(requestId, true, statusJson())
                "setup" -> resolve(requestId, true, setupJson())
                "diag" -> resolve(requestId, true, JSONObject()
                    .put("lines", JSONArray(Diag.dump())).toString())
                "clearDiag" -> { Diag.clear(); resolve(requestId, true, "{}") }
                "clipboard" -> {
                    try {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Loona diagnostics", payload.optString("text", "")))
                        resolve(requestId, true, "{}")
                    } catch (e: Exception) { resolve(requestId, false, err(e.message ?: "clipboard failed")) }
                }
                "openSetup" -> openSetupSettings(requestId)
                "foreground" -> {
                    try {
                        val i = Intent(ctx, com.pal9000.robot.MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        ctx.startActivity(i)
                        resolve(requestId, true, "{}")
                    } catch (e: Exception) {
                        resolve(requestId, false, err(e.message ?: "foreground failed"))
                    }
                }
                "command" -> {
                    val text = payload.optString("text", "").trim()
                    if (text.isBlank()) resolve(requestId, false, err("empty command"))
                    else {
                        // Dispatch to the page's intent pipeline (Greek/English).
                        // Fire-and-forget: handleUtterance drives TTS + actions.
                        evalInPage("(function(){try{handleUtterance(${JSONObject.quote(text)});return 'ok';}catch(e){return 'err: '+e}})()")
                        resolve(requestId, true, JSONObject().put("dispatched", text).toString())
                    }
                }
                "battery" -> resolve(requestId, true, batteryJson())
                "vibrate" -> { vibrate(payload.optLong("ms", 60)); resolve(requestId, true, "{}") }
                else -> resolve(requestId, false, err("unknown method $method"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Bridge] $method failed: $e")
            resolve(requestId, false, err(e.message ?: "exception"))
        }
    }

    private fun resolve(requestId: String, ok: Boolean, json: String) {
        syncWaiters.remove(requestId)?.let { latch ->
            syncResults[requestId] = ok to json
            latch.countDown()
        }
        // Only page-originated calls have a numeric id (Bridge.call uses seq++),
        // so only they have a pending JS promise to settle. Server-side blocking
        // calls (invokeBlocking → "srvN") are answered through the latch above;
        // emitting them to the page would evaluate `window.__palResult(srvN, …)`
        // and throw "ReferenceError: srvN is not defined" on every panel tool tap.
        if (requestId.isNotEmpty() && requestId.all { it.isDigit() }) {
            emit("window.__palResult && window.__palResult($requestId, ${if (ok) "true" else "false"}, ${jsonForJs(json)});")
        }
    }

    /* ═══════════ synchronous access for the on-phone web panel ═══════════ */

    private val syncWaiters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()
    private val syncResults = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, String>>()

    /** Run a bridge method from a server thread and wait for its answer. */
    fun invokeBlocking(method: String, payloadJson: String, timeoutMs: Long): Pair<Boolean, String> {
        val id = "srv" + seq.incrementAndGet()
        val latch = java.util.concurrent.CountDownLatch(1)
        syncWaiters[id] = latch
        call(id, method, payloadJson)
        val answered = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        syncWaiters.remove(id)
        return syncResults.remove(id)
            ?: (false to JSONObject().put("error", if (answered) "no result" else "timeout").toString())
    }

    /** Set by MainActivity: evaluate JS in the page and return its result. */
    @Volatile var jsEval: ((String, (String?) -> Unit) -> Unit)? = null
    @Volatile var panelUrl: String = ""

    fun evalInPage(js: String, timeoutMs: Long = 4000): String? {
        val runner = jsEval ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: String? = null
        runner(js) { r -> result = r; latch.countDown() }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result
    }

    fun statusSnapshot(): String = statusJson()
    fun batterySnapshot(): String = batteryJson()

    /** Start the TTS engine early so the first spoken answer isn't late. */
    fun warmUp() {
        mainHandler.post { if (tts == null) createTts(null) }
    }

    fun voicesSnapshot(): String = try {
        if (tts == null) {
            mainHandler.post { if (tts == null) createTts(null) }
            JSONObject().put("engine", "starting").put("el", -2).toString()
        } else voicesJson()
    } catch (e: Exception) { "{}" }

    private fun event(name: String, json: JSONObject) {
        // The page parses this as a JSON *string* — passing a bare object
        // literal made every event arrive empty (no STT text, no wake word).
        emit("window.__palEvent && window.__palEvent('$name', ${jsonForJs(json.toString())});")
    }

    private fun jsonForJs(json: String): String = JSONObject.quote(json)
    private fun err(msg: String): String = JSONObject().put("error", msg).toString()

    /** Build a JSONObject from a Map. JSONObject(map) is NOT a constructor — calling
     *  JSONObject(it.toString()) parsed Kotlin's Map.toString() and threw
     *  "Expected literal value" on every snapshot/runTask result. */
    private fun mapToJsonObject(m: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) {
            when (v) {
                is JSONObject -> o.put(k, v)
                is JSONArray -> o.put(k, v)
                is Map<*, *> -> o.put(k, mapToJsonObject(v as Map<String, Any?>))
                is List<*> -> {
                    val arr = JSONArray()
                    v.forEach { arr.put(it?.toString() ?: "") }
                    o.put(k, arr)
                }
                is Number, is Boolean, is String -> o.put(k, v)
                else -> o.put(k, v?.toString() ?: "")
            }
        }
        return o
    }

    /* ═══════════ HTTP (CORS-free Hermes proxy) ═══════════ */

    private fun http(requestId: String, p: JSONObject) {
        io.execute {
            val url = p.optString("url").trim()
            if (url.isBlank()) { resolve(requestId, false, err("no server URL")); return@execute }
            val method = (p.optString("method", "POST")).uppercase(Locale.US)
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 90_000
                conn.instanceFollowRedirects = false
                val headers = p.optJSONObject("headers")
                if (headers != null) {
                    val it = headers.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        conn.setRequestProperty(k, headers.optString(k))
                    }
                }
                if (method == "POST" || method == "PUT" || method == "PATCH") {
                    conn.doOutput = true
                    val body = p.optString("body", "")
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { w -> w.write(body) }
                }
                val status = conn.responseCode
                val stream = if (status in 200..399) conn.inputStream else conn.errorStream
                val text = stream?.let { s ->
                    BufferedReader(InputStreamReader(s, Charsets.UTF_8)).readText()
                } ?: ""
                val out = JSONObject().put("status", status).put("body", text)
                resolve(requestId, true, out.toString())
            } catch (e: Exception) {
                Log.e(TAG, "[Bridge] http $url failed: $e")
                resolve(requestId, false, err(e.message ?: "network error"))
            } finally {
                conn?.disconnect()
            }
        }
    }

    /* ═══════════ streaming chat completions (SSE) ═══════════ */

    /**
     * POSTs to an OpenAI-compatible endpoint with "stream": true and pushes
     * each delta to the page as a `hermes` event, so the face can show the
     * agent thinking in real time instead of freezing for 20 seconds.
     */
    private fun stream(requestId: String, p: JSONObject) {
        io.execute {
            val url = p.optString("url")
            val body = p.optString("body", "")
            var conn: HttpURLConnection? = null
            val answer = StringBuilder()
            val reasoning = StringBuilder()
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 120_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "text/event-stream")
                val headers = p.optJSONObject("headers")
                if (headers != null) {
                    val it = headers.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        conn.setRequestProperty(k, headers.optString(k))
                    }
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { w -> w.write(body) }

                val status = conn.responseCode
                if (status !in 200..299) {
                    val text = conn.errorStream?.let { s ->
                        BufferedReader(InputStreamReader(s, Charsets.UTF_8)).readText()
                    } ?: ""
                    resolve(requestId, true, JSONObject()
                        .put("status", status).put("body", text).put("text", "").put("reasoning", "").toString())
                    return@execute
                }

                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                    val ctype = conn.contentType ?: ""
                    var line = r.readLine()
                    // A server that ignores "stream": true answers with one
                    // JSON body — parsing it line-by-line as SSE yielded an
                    // empty answer with status 200 ("I couldn't reach my brain"
                    // though the connection was fine).
                    if (line != null && !line.trim().startsWith("data:") &&
                        ctype.contains("json", ignoreCase = true)) {
                        val rest = StringBuilder(line)
                        while (r.readLine().also { rest.append('\n').append(it) } != null) { /* keep reading */ }
                        try {
                            val o = JSONObject(rest.toString())
                            val ch = o.optJSONArray("choices")?.optJSONObject(0)
                            val msg = ch?.optJSONObject("message") ?: ch?.optJSONObject("delta")
                            val words = msg?.optString("content", "") ?: ""
                            val think = msg?.optString("reasoning_content", "") ?: ""
                            if (think.isNotEmpty()) { reasoning.append(think); pushChunk(requestId, "reasoning", think) }
                            if (words.isNotEmpty()) { answer.append(words); pushChunk(requestId, "content", words) }
                        } catch (e: Exception) {
                            Log.w(TAG, "[Stream] non-SSE body not JSON: ${e.message}")
                        }
                        line = null
                    }
                    while (line != null) {
                        val l = line.trim()
                        if (l.startsWith("data:")) {
                            val payload = l.substring(5).trim()
                            if (payload.isEmpty() || payload == "[DONE]") {
                                if (payload == "[DONE]") break
                            } else {
                                try {
                                    val o = JSONObject(payload)
                                    val delta = o.optJSONArray("choices")
                                        ?.optJSONObject(0)?.optJSONObject("delta")
                                    val think = delta?.optString("reasoning_content", "") ?: ""
                                    val words = delta?.optString("content", "") ?: ""
                                    if (think.isNotEmpty()) {
                                        reasoning.append(think)
                                        pushChunk(requestId, "reasoning", think)
                                    }
                                    if (words.isNotEmpty()) {
                                        answer.append(words)
                                        pushChunk(requestId, "content", words)
                                    }
                                } catch (e: Exception) { /* keep-alive or junk */ }
                            }
                        }
                        line = r.readLine()
                    }
                }
                Log.d(TAG, "[Stream] done: ${answer.length} chars (thinking ${reasoning.length})")
                resolve(requestId, true, JSONObject()
                    .put("status", status)
                    .put("text", answer.toString())
                    .put("reasoning", reasoning.toString())
                    .toString())
            } catch (e: Exception) {
                Log.e(TAG, "[Stream] $url failed: $e")
                // A partial answer is still worth keeping
                resolve(requestId, true, JSONObject()
                    .put("status", 0)
                    .put("error", e.message ?: "stream error")
                    .put("text", answer.toString())
                    .put("reasoning", reasoning.toString())
                    .toString())
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun pushChunk(requestId: String, kind: String, delta: String) {
        event("hermes", JSONObject().put("id", requestId).put("kind", kind).put("delta", delta))
    }

    /* ═══════════ speech recognition (STT + wake loop) ═══════════ */

    private fun listen(requestId: String, p: JSONObject) {
        mainHandler.post {
            try {
                val mode = p.optString("mode", "once")
                if (mode == "off") {
                    teardownSpeech()
                    resolve(requestId, true, "{}")
                    return@post
                }
                listenMode = mode
                listenToken++
                // Whisper is an optional LAN add-on. Verify it actually speaks
                // Wyoming before using it: an unresponsive server made every
                // command sound like the phone didn't hear anything (recorded,
                // streamed, then "Read timed out" → no transcript). Fall back to
                // the phone's built-in recognizer when it's down.
                if (mode == "whisper" && whisperOn && whisperReachable()) {
                    // Stop the recognizer without destroying it: it keeps the
                    // microphone input open while it listens, and a second
                    // AudioRecord started on top of it reads near-silence —
                    // which is what produced the old "no speech within 9 s"
                    // and lost the whole sentence. cancel() releases the input
                    // and its ERROR_CLIENT callback is ignored in whisper mode.
                    try { speech?.stopListening() } catch (e: Exception) { /* ignore */ }
                    try { speech?.cancel() } catch (e: Exception) { /* ignore */ }
                    startWhisperCapture(
                        { text: String? ->
                            if (text.isNullOrBlank()) {
                                speechEvent("empty", null, "whisper heard nothing", 0)
                            } else {
                                Log.d(TAG, "[Whisper] final: $text")
                                speechEvent("final", text, null, 0)
                            }
                        },
                        p.optLong("noSpeechMs", whisperNoSpeechMs),
                        p.optLong("endMs", whisperEndMs)
                    )
                    resolve(requestId, true, "{}")
                    return@post
                }
                if (mode == "whisper") listenMode = "conversation"   // whisper disabled
                stopCapture()
                ensureRecognizer()
                // cancel()/destroy() take effect asynchronously: starting again
                // in the same breath yields ERROR_RECOGNIZER_BUSY and silence.
                try { speech?.cancel() } catch (e: Exception) { /* ignore */ }
                startListening(120)
                resolve(requestId, true, "{}")
            } catch (e: Exception) {
                resolve(requestId, false, err(e.message ?: "listen failed"))
            }
        }
    }

    private fun sttOnDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

    // Languages whose on-device model turned out to be missing (error 12/13).
    // Persisted so the next boot goes straight to the network recognizer
    // instead of paying a ~10 s dead window discovering it again.
    private fun sttPrefs() = ctx.getSharedPreferences("pal_stt", Context.MODE_PRIVATE)
    private fun onDeviceBroken(tag: String): Boolean = sttPrefs().getBoolean("broken_$tag", false)
    private fun markOnDeviceBroken(tag: String) {
        sttPrefs().edit().putBoolean("broken_$tag", true).apply()
    }

    private fun ensureRecognizer() {
        // Android 13+ can run recognition entirely on the device: audio never
        // leaves the phone. Fall back to the network recognizer when there is
        // no on-device model for the chosen language.
        val wantOnDevice = onDeviceOnly && sttOnDeviceAvailable() && !onDeviceBroken(lang)
        if (speech != null && recognizerOnDevice == wantOnDevice) return
        try {
            speech?.destroy()
        } catch (e: Exception) { /* ignore */ }
        speech = null
        speech = if (wantOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizerOnDevice = wantOnDevice
        speech?.setRecognitionListener(recognitionListener)
        Log.d(TAG, "[STT] recognizer = ${if (wantOnDevice) "on-device" else "network"} ($lang)")
    }

    /* ═══════════ voice configuration (language + privacy) ═══════════ */

    private fun voice(requestId: String, p: JSONObject) {
        if (p.has("lang")) {
            val newLang = p.optString("lang", lang).ifBlank { "en-US" }
            if (newLang != lang) langBareRetry = false
            lang = newLang
        }
        if (p.has("onDevice")) onDeviceOnly = p.optBoolean("onDevice", onDeviceOnly)
        if (p.has("whisper")) whisperUrl = p.optString("whisper", whisperUrl).trim()
        if (p.has("whisperOn")) whisperOn = p.optBoolean("whisperOn", whisperOn)
        if (p.has("noSpeechMs")) whisperNoSpeechMs = p.optLong("noSpeechMs", whisperNoSpeechMs)
        if (p.has("endMs")) whisperEndMs = p.optLong("endMs", whisperEndMs)
        if (p.has("rate")) speechRate = p.optDouble("rate", speechRate.toDouble()).toFloat().coerceIn(0.5f, 2.0f)
        if (p.has("pitch")) speechPitch = p.optDouble("pitch", speechPitch.toDouble()).toFloat().coerceIn(0.5f, 2.0f)
        mainHandler.post {
            try {
                val prevMode = listenMode
                teardownSpeech()
                applyTtsLocale()
                if (prevMode != "off") {
                    // Restore the session, not just the mode string: without
                    // this, any voice-config save (settings sheet or web panel)
                    // killed the native loop while JS still believed it armed.
                    listenMode = prevMode
                    ensureRecognizer()
                    startListening(300)
                }
                val out = JSONObject()
                    .put("lang", lang)
                    .put("onDeviceOnly", onDeviceOnly)
                    .put("sttOnDevice", sttOnDeviceAvailable() && onDeviceOnly)
                    .put("ttsLang", ttsLangCode())
                    .put("whisper", whisperUrl)
                    .put("whisperOn", whisperOn)
                Log.d(TAG, "[Voice] $out")
                resolve(requestId, true, out.toString())
            } catch (e: Exception) {
                resolve(requestId, false, err(e.message ?: "voice config failed"))
            }
        }
    }

    private fun applyTtsLocale() {
        val t = tts ?: return
        val loc = Locale.forLanguageTag(lang)
        var res = try { t.setLanguage(loc) } catch (e: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
        var ok = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
        if (ok) {
            // Make sure the chosen voice really is the requested language:
            // Samsung's engine reports -2 for Greek, Google's has it.
            val want = lang.lowercase().take(2)
            val voice = try {
                (t.voices ?: emptySet()).firstOrNull {
                    it.locale.toLanguageTag().lowercase().take(2) == want
                }
            } catch (e: Exception) { null }
            if (voice != null) {
                try { t.voice = voice } catch (e: Exception) { /* ignore */ }
                res = try { t.setLanguage(voice.locale) } catch (e: Exception) { res }
                ok = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        Log.d(TAG, "[TTS] engine=$ttsEngine setLanguage($lang) → $res (ok=$ok, voice=${ttsLangCode()})")
        if (!ok && ttsEngine != null) {
            // Google engine has no voice data for this language → try the
            // system default engine before giving up (and vice versa).
            Log.w(TAG, "[TTS] $ttsEngine cannot speak $lang — falling back to default engine")
            ttsReady = false
            tts = null
            createTts(null, true)
            return
        }
        if (!ok) {
            try { t.setLanguage(Locale.US) } catch (e: Exception) { /* ignore */ }
        }
        event("voice", JSONObject()
            .put("type", "tts-locale")
            .put("ok", ok)
            .put("lang", lang)
            .put("engine", ttsEngine ?: ""))
    }

    /** Which TTS languages this engine can actually speak. */
    private fun voicesJson(): String {
        val t = tts ?: return JSONObject().put("error", "tts engine not started yet").toString()
        val el = Locale.forLanguageTag("el-GR")
        val list = try {
            (t.voices ?: emptySet()).map { it.locale.toLanguageTag() }.sorted()
        } catch (e: Exception) { emptyList() }
        return JSONObject()
            .put("el", t.isLanguageAvailable(el))
            .put("en", t.isLanguageAvailable(Locale.US))
            .put("current", t.voice?.locale?.toLanguageTag() ?: "")
            .put("greekVoices", JSONArray(list.filter { it.startsWith("el") }))
            .put("voiceCount", list.size)
            .put("engine", ttsEngine ?: "default")
            .toString()
    }

    private fun voices(requestId: String, p: JSONObject) {
        mainHandler.post {
            if (tts == null) {
                resolve(requestId, false, JSONObject().put("error", "tts engine not started yet").toString())
                return@post
            }
            resolve(requestId, true, voicesJson())
        }
    }

    private fun ttsLangCode(): String = try {
        tts?.voice?.locale?.toLanguageTag() ?: ""
    } catch (e: Exception) { "" }

    private fun startListening(delayMs: Long = 0) {
        if (listenMode == "off") return
        val token = listenToken
        mainHandler.postDelayed({
            if (listenMode == "off" || token != listenToken) return@postDelayed
            val bypass = gateBypass
            gateBypass = false
            if (listenMode == "loop" && !bypass) {
                if (mediaPlaying()) musicBeforeLoop = true
                if (musicBeforeLoop && !mediaPlaying()) resumeMediaOnce()
                if (musicBeforeLoop) {
                    Log.d(TAG, "[STT] music is playing — wake loop parked")
                    Diag.add("music playing → wake word via hotword only")
                    parkLoopForMedia()
                    return@postDelayed
                }
            }
            ensureRecognizer()
            val intent = buildRecognizerIntent()
            lastPartialText = null            // never leak a previous session's words
            try {
                Log.d(TAG, "[STT] start ($listenMode)")
                speech?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "[STT] start failed: $e")
                speechEvent("error", null, e.message ?: "start failed", 0)
                scheduleRestart(1200)
            }
        }, delayMs)
    }

    // Set when the network recognizer also rejects the language (error 12/13):
    // we then retry with the bare subtag ("el-GR" → "el"), which is what the
    // Google network recognizer actually advertises.
    private var langBareRetry = false

    private fun sttLang(): String =
        if (langBareRetry && lang.contains('-')) lang.substringBefore('-') else lang

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, sttLang())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, sttLang())
            // Only ask for OFFLINE when the on-device recognizer is the one
            // running. onDeviceOnly can be true while we've fallen back to the
            // network recognizer (broken model persisted) — sending
            // EXTRA_PREFER_OFFLINE=true there made Google look for the missing
            // offline pack and loop on error 12 forever.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, recognizerOnDevice)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // Endpointing: Google's DEFAULTS are used on purpose. An earlier
            // tuning passed these extras as Long (Google reads Int → logged
            // "expected Integer", silently dropped), and once fixed to Int the
            // tightened values made quiet-room sessions die in <1 s
            // (VAD blip → 600ms silence → "no match" → restart churn + beeps).
            // Wake detection runs on partial results anyway, so a tight
            // final window buys nothing.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

    private var lastLevelAt = 0L

    private val recognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            clientErrors = 0
            busyErrors = 0
            deliver(results, true)
            // The recognizer stops itself after a result — re-arm the loop or
            // the wake word works exactly once per app start.
            if (listenMode == "loop" || listenMode == "conversation") scheduleRestart(180)
        }
        override fun onPartialResults(partialResults: Bundle?) { deliver(partialResults, false) }
        override fun onError(error: Int) {
            if (listenMode == "whisper") {
                Log.d(TAG, "[STT] error $error ignored while whisper is listening")
                return
            }
            // In the wake loop, "no match" (7) and "no speech" (6) are the normal
            // outcome of every listen cycle in a quiet or noisy room. Logging and
            // emitting them as errors floods logcat and the page with noise, so
            // restart silently for those two while looping.
            val quietLoopError = listenMode == "loop" &&
                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            if (!quietLoopError) {
                Log.d(TAG, "[STT] error $error (mode=$listenMode, lang=$lang, onDevice=$recognizerOnDevice)")
            }
            if (error == SpeechRecognizer.ERROR_CLIENT) clientErrors++
            // 12/13 = language model not installed on-device — drop to network
            if ((error == 12 || error == 13) && recognizerOnDevice) {
                Log.w(TAG, "[STT] no on-device model for $lang — using network recognizer")
                onDeviceOnly = false
                markOnDeviceBroken(lang)          // remember across boots
                event("voice", JSONObject().put("type", "stt-fallback").put("lang", lang))
                mainHandler.post {
                    val prevMode = listenMode
                    teardownSpeech()
                    listenMode = prevMode
                    ensureRecognizer()
                    startListening(250)
                }
                return
            }
            // Network path also rejects the language: retry once with the bare
            // subtag ("el-GR" → "el"), then back off hard instead of hammering
            // a recognizer that will never accept this language.
            if ((error == 12 || error == 13) && !recognizerOnDevice) {
                if (!langBareRetry) {
                    langBareRetry = true
                    Log.w(TAG, "[STT] network rejects $lang — retrying with ${sttLang()}")
                    speechEvent("error", null, sttError(error), error)
                    scheduleRestart(400, rebuild = true)
                    return
                }
                langBareRetry = false
                Log.w(TAG, "[STT] network has no model for $lang (or bare tag) — slow retry")
                event("voice", JSONObject().put("type", "stt-fallback").put("lang", lang))
                speechEvent("error", null, sttError(error), error)
                if (listenMode == "loop" || listenMode == "conversation") scheduleRestart(15000, rebuild = true)
                return
            }
            // ERROR_RECOGNIZER_BUSY (8): hammering startListening() on a wedged
            // session makes it worse. Rebuild the recognizer once, then back off
            // exponentially. (7 = NO_MATCH / 6 = SPEECH_TIMEOUT are the normal
            // end of a listen cycle and are handled below.)
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) busyErrors++
            if (!quietLoopError) speechEvent("error", null, sttError(error), error)
            if (listenMode == "loop" || listenMode == "conversation") {
                val wait = when {
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        (500L * (1 shl busyErrors.coerceAtMost(4))).coerceAtMost(8000)
                    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 400
                    else -> (500 + clientErrors * 500).coerceAtMost(3000).toLong()
                }
                scheduleRestart(wait, rebuild = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            }
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            // Feed the phone's own face: it reacts to your voice without the
            // WebView ever opening a second microphone (that contention was
            // what silenced the recognizer).
            val t = System.currentTimeMillis()
            if (t - lastLevelAt < 70) return
            lastLevelAt = t
            val level = ((rmsdB + 50f) / 50f).coerceIn(0f, 1f)
            event("level", JSONObject().put("level", level).put("mode", listenMode))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // Google's network recognizer on this device delivers the utterance only
    // through onPartialResults — the final bundle arrives with an EMPTY
    // RESULTS_RECOGNITION (verified via logcat: list=null, bundle[]). Without
    // this fallback every final was silently dropped and turns never started.
    private var lastPartialText: String? = null

    private fun deliver(results: Bundle?, final: Boolean) {
        if (listenMode == "whisper") return      // whisper owns this turn
        var list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        var text = list?.firstOrNull()?.trim()
        if (final && text.isNullOrEmpty()) {
            text = lastPartialText
            if (!text.isNullOrEmpty()) Log.d(TAG, "[STT] final from last partial: $text")
        }
        if (text.isNullOrEmpty()) return
        if (final) {
            lastPartialText = null
            Log.d(TAG, "[STT] final: $text")
        } else {
            lastPartialText = text
        }
        speechEvent(if (final) "final" else "partial", text, null, 0)
        if (!final && listenMode == "once") { /* keep listening */ }
    }

    private fun speechEvent(type: String, text: String?, message: String?, code: Int) {
        val o = JSONObject().put("type", type).put("mode", listenMode)
        if (text != null) o.put("text", text)
        if (message != null) o.put("message", message)
        if (code != 0) o.put("code", code)
        event("speech", o)
    }

    private fun sttError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission denied"
        SpeechRecognizer.ERROR_NETWORK -> "network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech"
        else -> "error $code"
    }

    private var clientErrors = 0
    private var busyErrors = 0

    // Google's speech service asks for AUDIOFOCUS_GAIN on every listen, which
    // pauses whatever is playing. A continuous wake loop restarts every ~3 s,
    // so music stopped over and over — the "it plays and then randomly stops"
    // bug. While media is playing the loop is parked instead; it re-arms by
    // itself as soon as the music stops.
    private var musicBeforeLoop = false

    private fun mediaPlaying(): Boolean = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.isMusicActive
    } catch (e: Exception) { false }

    // isMusicActive() goes false for a moment between tracks / while the
    // player buffers. A single reading used to re-arm the loop, which then
    // paused the song and — since a paused player looks "not playing" — could
    // never park again. Require several quiet checks in a row.
    private var quietChecks = 0

    private val mediaWatch = object : Runnable {
        override fun run() {
            if (listenMode != "loop") return
            if (mediaPlaying() || musicBeforeLoop) {
                quietChecks = 0
                if (!mediaPlaying()) {
                    // Don't hammer the player forever if it refuses to resume
                    // (closed, killed, or it wants a fresh session).
                    resumeAttempts++
                    if (resumeAttempts <= 3) resumeMediaOnce()
                    else { musicBeforeLoop = false; resumeAttempts = 0 }
                }
                mainHandler.postDelayed(this, 2000)
                return
            }
            quietChecks++
            if (quietChecks < 3) { mainHandler.postDelayed(this, 2000); return }
            quietChecks = 0
            Log.d(TAG, "[STT] media stopped — re-arming wake loop")
            Diag.add("media stopped → normal listening")
            hotword.stop()
            startListening(250)
        }
    }

    /** Press play on whatever paused, once, without starting random playback. */
    private fun resumeMediaOnce() {
        io.execute {
            // Retry: a single press landed while the player was still tearing
            // down its focus and did nothing, which left the song stopped.
            for (tryNo in 1..4) {
                if (mediaPlaying()) break
                mediaSessionBridge.play()
                Thread.sleep(1200)
                if (!mediaPlaying()) {
                    // transport play ignored it — try a media key
                    try {
                        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                    } catch (e: Exception) { }
                    Thread.sleep(1200)
                }
            }
            Log.d(TAG, "[STT] resumed media (playing=${mediaPlaying()})")
            Diag.add("resume music → playing=${mediaPlaying()}")
            if (mediaPlaying()) { musicBeforeLoop = false; resumeAttempts = 0 }
        }
    }

    /* ── wake word while media plays ──
     * The recognizer is parked (it would pause the song). An on-device Vosk
     * hotword detector keeps listening instead: it uses no audio focus and a
     * tiny grammar, so the song and the TV are ignored and only "Λούνα" wakes
     * the recognizer for a real command. */
    private var gateBypass = false

    private val hotword: HotwordEngine by lazy {
        HotwordEngine(ctx) { onHotwordDetected() }
    }

    private fun onHotwordDetected() {
        mainHandler.post {
            if (listenMode != "loop") return@post
            hotword.stop()
            // Pause deliberately and remember it, so the command is heard
            // cleanly and the song comes back when she has answered. Doing
            // this ourselves beats letting the recognizer's focus grab pause
            // it by accident, because then we know it should resume.
            if (mediaPlaying()) {
                mediaWasPlaying = true
                io.execute {
                    mediaSessionBridge.pause()
                    Thread.sleep(400)
                    if (mediaPlaying()) {
                        // transport pause ignored it — toggle via media key
                        try {
                            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
                            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
                        } catch (e: Exception) { }
                        Thread.sleep(300)
                    }
                    Log.d(TAG, "[Hotword] music paused for the command (playing=${mediaPlaying()})")
                    Diag.add("music paused for the command (playing=${mediaPlaying()})")
                }
                Diag.add("wake word → listening for the command")
            }
            gateBypass = true          // one recognizer window
            startListening(420)        // let the pause land before we listen
        }
    }

    /** Stop the wake loop while music plays and put playback back if the last
     *  listen cycle paused it. */
    private fun parkLoopForMedia() {
        mainHandler.removeCallbacks(mediaWatch)
        mainHandler.postDelayed(mediaWatch, 2000)
        hotword.start()
        if (!musicBeforeLoop || mediaPlaying()) return
        resumeAttempts++
        if (resumeAttempts > 2) {
            // The player is gone (closed/killed) — stop trying so we don't
            // press play into some other app.
            musicBeforeLoop = false
            resumeAttempts = 0
            return
        }
        resumeMediaOnce()
    }

    private var resumeAttempts = 0

    private fun scheduleRestart(delayMs: Long, rebuild: Boolean = false) {
        val token = listenToken
        mainHandler.postDelayed({
            if (listenMode == "off" || token != listenToken) return@postDelayed
            if (listenMode == "loop" && (mediaPlaying() || musicBeforeLoop)) {
                parkLoopForMedia()
                return@postDelayed
            }
            // A recognizer that has been cancelled and restarted in quick
            // succession wedges itself (ERROR_CLIENT forever). Build a fresh
            // one instead of hammering startListening().
            if (rebuild || clientErrors >= 2 || busyErrors >= 2) {
                clientErrors = 0
                busyErrors = 0
                try { speech?.cancel() } catch (e: Exception) { }
                try { speech?.destroy() } catch (e: Exception) { }
                speech = null
                ensureRecognizer()
                // destroy() settles asynchronously — give it a beat before
                // startListening(), or the fresh session starts busy again.
                mainHandler.postDelayed({
                    if (listenMode != "off" && token == listenToken) startListening()
                }, 250)
                return@postDelayed
            }
            startListening()
        }, delayMs)
    }

    private fun teardownSpeech() {
        listenMode = "off"
        hotword.stop()
        listenToken++
        resumeMediaIfNeeded()
        stopCapture()
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        try {
            speech?.cancel()
            speech?.destroy()
        } catch (e: Exception) { /* ignore */ }
        speech = null
    }

    private fun stopListen() {
        mainHandler.post { teardownSpeech() }
    }

    /** JS-driven hard stop (conversation turn ended). */
    fun haltListening() = stopListen()

    /* ═══════════ whisper on the LAN (Wyoming protocol) ═══════════
       SpeechRecognizer is great for the always-on wake word but poor at
       Greek commands. Once the phone is awake we record the sentence
       ourselves and hand it to whisper on the NAS: better accuracy, no
       cloud, and it understands the same language you speak to it. */

    /** One-shot helper: record, transcribe, resolve with {text}. */
    private fun transcribe(requestId: String, p: JSONObject) {
        if (p.has("url")) whisperUrl = p.optString("url", whisperUrl).trim()
        mainHandler.post {
            if (capturing) { resolve(requestId, false, err("already recording")); return@post }
            startWhisperCapture(
                { text: String? ->
                    resolve(requestId, !text.isNullOrBlank(), JSONObject()
                        .put("text", text ?: "")
                        .put("url", whisperUrl)
                        .toString())
                },
                whisperNoSpeechMs,
                whisperEndMs
            )
        }
    }

    private fun startWhisperCapture(onText: (String?) -> Unit, noSpeechMs: Long, endMs: Long) {
        if (capturing) { onText(null); return }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speechEvent("error", null, "microphone permission denied", 9)
            onText(null)
            return
        }
        capturing = true
        val token = listenToken
        captureThread = thread(name = "pal-whisper", start = true) {
            val rate = 16000
            var rec: AudioRecord? = null
            var asr: WyomingAsr? = null
            val pcm = ByteArrayOutputStream(rate * 2 * 12)
            try {
                val min = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                // Factory so a dead stream (HAL still held by the cancelled
                // recognizer) can be reopened without aborting the whole turn.
                fun openRec(): AudioRecord? {
                    val r = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(min * 2, 8192))
                    if (r.state != AudioRecord.STATE_INITIALIZED) {
                        try { r.release() } catch (e: Exception) { /* ignore */ }
                        return null
                    }
                    recordRef = r
                    r.startRecording()
                    return r
                }
                rec = openRec()
                if (rec == null) {
                    Log.e(TAG, "[Whisper] AudioRecord not initialised")
                    speechEvent("error", null, "microphone busy", 9)
                    onText(null)
                    return@thread
                }
                Thread.sleep(350)        // let the mic settle after the switch
                val buf = ShortArray(1024)
                val preroll = ArrayDeque<ByteArray>()
                var started = false
                var silenceMs = 0
                var spokenMs = 0
                var noise = 420.0
                val began = System.currentTimeMillis()
                var lastLevelAt = 0L
                var deadMs = 0L
                var retriedDead = false

                while (capturing && token == listenToken) {
                    val active = rec
                    if (active == null) { Thread.sleep(10); continue }
                    val n = active.read(buf, 0, buf.size)
                    if (n <= 0) { Thread.sleep(10); continue }
                    var sum = 0.0
                    var peak = 0
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        sum += (v * v).toDouble()
                        if (Math.abs(v) > peak) peak = Math.abs(v)
                    }
                    val rms = Math.sqrt(sum / n)
                    val frameMs = n * 1000 / rate

                    val t = System.currentTimeMillis()
                    if (t - lastLevelAt > 70) {
                        lastLevelAt = t
                        event("level", JSONObject().put("level", (peak / 6000.0).coerceIn(0.0, 1.0)).put("mode", "whisper"))
                    }

                    if (!started) {
                        // Detect a genuinely dead input (HAL still holding the
                        // mic after the recognizer was cancelled) and reopen
                        // once instead of waiting the full 3 s watchdog.
                        if (peak == 0) deadMs += frameMs else deadMs = 0L
                        if (deadMs >= 600L && !retriedDead) {
                            deadMs = 0L
                            retriedDead = true
                            Log.d(TAG, "[Whisper] mic stream dead — reopening input")
                            try { rec?.stop() } catch (e: Exception) { /* ignore */ }
                            try { rec?.release() } catch (e: Exception) { /* ignore */ }
                            rec = null
                            Thread.sleep(200)
                            rec = openRec()
                            if (rec == null) {
                                Log.d(TAG, "[Whisper] reopen failed — bailing")
                                break
                            }
                            recordRef = rec
                            Thread.sleep(250)
                            preroll.clear()
                            continue
                        }
                        if (deadMs >= 600L && retriedDead) {
                            Log.d(TAG, "[Whisper] mic dead after reopen — bailing")
                            break
                        }
                        noise += (rms - noise) * 0.08
                        val bytes = ShortArray(n).let { arr ->
                            for (i in 0 until n) arr[i] = buf[i]
                            val b = ByteArray(n * 2)
                            for (i in 0 until n) {
                                val s = arr[i].toInt()
                                b[i * 2] = (s and 0xFF).toByte()
                                b[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                            }
                            b
                        }
                        preroll.addLast(bytes)
                        while (preroll.size > 6) preroll.removeFirst()   // ~380 ms
                        if (rms > maxOf(noise * 1.9, 380.0)) {
                            started = true
                            silenceMs = 0
                            preroll.forEach { pcm.write(it) }
                            // Stream to whisper from the first syllable: it listens
                            // while we talk, so the transcript lands right after
                            // the last word instead of seconds later.
                            asr = try {
                                WyomingAsr(whisperUrl, lang).also { it.open() }
                            } catch (e: Exception) {
                                Log.w(TAG, "[Whisper] streaming unavailable (${e.message}) — batching")
                                null
                            }
                            preroll.forEach { asr?.write(it) }
                            preroll.clear()
                            Log.d(TAG, "[Whisper] speech onset (rms=${rms.toInt()}, stream=${asr != null})")
                        } else if (System.currentTimeMillis() - began > noSpeechMs) {
                            Log.d(TAG, "[Whisper] no speech within ${noSpeechMs}ms (noise=${noise.toInt()}, peak=$peak)")
                            break
                        }
                    } else {
                        val frame = toBytes(buf, n)
                        pcm.write(frame)
                        try { asr?.write(frame) } catch (e: Exception) {
                            Log.w(TAG, "[Whisper] stream broke (${e.message}) — batching the rest")
                            asr = null
                        }
                        spokenMs += frameMs
                        if (rms < maxOf(noise * 1.7, 480.0)) silenceMs += frameMs else silenceMs = 0
                        // 550 ms: long enough not to clip a pause between
                        // words, short enough that the answer starts promptly.
                        if (silenceMs > endMs) { Log.d(TAG, "[Whisper] end of speech"); break }
                        if (spokenMs > 12000) break
                    }
                    if (!capturing || token != listenToken) break
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Whisper] capture failed: ${e.message}")
            } finally {
                try { rec?.stop() } catch (e: Exception) { /* ignore */ }
                try { rec?.release() } catch (e: Exception) { /* ignore */ }
                recordRef = null
                capturing = false
            }

            val audio = pcm.toByteArray()
            val endedAt = System.currentTimeMillis()
            if (audio.size < 3200) { try { asr?.close() } catch (e: Exception) { }; onText(null); return@thread }
            val text = try {
                val streaming = asr
                if (streaming != null) streaming.finish() else transcribeWyoming(audio)
            } catch (e: Exception) {
                Log.e(TAG, "[Whisper] ${e.message}")
                speechEvent("error", null, "whisper: ${e.message ?: "unreachable"}", 8)
                null
            } finally {
                try { asr?.close() } catch (e: Exception) { /* ignore */ }
            }
            Log.d(TAG, "[Whisper] transcript after ${System.currentTimeMillis() - endedAt}ms (${text ?: "none"})")
            onText(text)
        }
    }

    private fun stopCapture() {
        if (!capturing) return
        capturing = false
        try { recordRef?.stop() } catch (e: Exception) { /* ignore */ }
    }

    /** Does the whisper URL actually speak Wyoming, or is it a dead endpoint?
     *  A silent server made every command sound like the phone didn't hear
     *  anything — record, stream, then "Read timed out" → no transcript. */
    private fun whisperReachable(): Boolean {
        if (whisperUrl.isBlank()) return false
        val uri = try { URI(whisperUrl) } catch (e: Exception) { return false }
        val host = uri.host ?: return false
        val port = if (uri.port > 0) uri.port else 10300
        val language = lang.substringBefore('-').lowercase()
        val deadline = System.currentTimeMillis() + 4000
        var sock: Socket? = null
        try {
            sock = Socket()
            sock.connect(InetSocketAddress(host, port), 2500)
            sock.soTimeout = 2500
            val out = sock.getOutputStream()
            val input = BufferedInputStream(sock.getInputStream())
            fun send(json: String) { out.write((json + "\n").toByteArray(Charsets.UTF_8)); out.flush() }
            fun readLine(): String? {
                val sb = StringBuilder()
                while (System.currentTimeMillis() < deadline) {
                    val b = input.read() ?: return if (sb.isEmpty()) null else sb.toString()
                    if (b == '\n'.code) return sb.toString()
                    if (b != '\r'.code) sb.append(b.toChar())
                }
                return null
            }
            send("{\"type\":\"transcribe\",\"data\":{\"language\":\"$language\"}}")
            send("{\"type\":\"audio-start\",\"data\":{\"rate\":16000,\"width\":2,\"channels\":1}}")
            send("{\"type\":\"audio-stop\",\"data\":{}}")
            while (System.currentTimeMillis() < deadline) {
                val line = readLine() ?: break
                if (line.isBlank()) continue
                val ev = try { JSONObject(line) } catch (e: Exception) { continue }
                val dataLen = ev.optInt("data_length", 0)
                if (dataLen > 0) { val b = ByteArray(dataLen); var off = 0
                    while (off < dataLen) { val r = input.read(b, off, dataLen - off); if (r < 0) break; off += r } }
                val t = ev.getString("type")
                if (t == "transcript" || t == "error") return true
            }
            return false
        } catch (e: Exception) {
            return false
        } finally {
            try { sock?.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun toBytes(buf: ShortArray, n: Int): ByteArray {
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = buf[i].toInt()
            b[i * 2] = (s and 0xFF).toByte()
            b[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return b
    }

    /** Send 16 kHz mono PCM to the Wyoming ASR server, return the transcript. */
    private fun transcribeWyoming(pcm: ByteArray): String? {
        val endpoint = whisperUrl.ifBlank { return null }
        val uri = URI(endpoint)
        val host = uri.host ?: throw IllegalStateException("bad whisper url")
        val port = if (uri.port > 0) uri.port else 10300
        val language = lang.substringBefore('-').lowercase()
        Log.d(TAG, "[Whisper] → $host:$port lang=$language bytes=${pcm.size}")

        val sock = Socket()
        try {
            sock.connect(java.net.InetSocketAddress(host, port), 4000)
            sock.soTimeout = 30000
            val out = sock.getOutputStream()
            fun send(json: String) { out.write((json + "\n").toByteArray(Charsets.UTF_8)) }

            send("{\"type\":\"transcribe\",\"data\":{\"language\":\"$language\"}}")
            send("{\"type\":\"audio-start\",\"data\":{\"rate\":16000,\"width\":2,\"channels\":1}}")
            var sent = 0
            while (sent < pcm.size) {
                val n = minOf(3200, pcm.size - sent)
                send("{\"type\":\"audio-chunk\",\"data\":{\"rate\":16000,\"width\":2,\"channels\":1},\"payload_length\":$n}")
                out.write(pcm, sent, n)
                sent += n
            }
            send("{\"type\":\"audio-stop\",\"data\":{}}")
            out.flush()

            val input = BufferedInputStream(sock.getInputStream())
            val deadline = System.currentTimeMillis() + 30000
            while (System.currentTimeMillis() < deadline) {
                val line = readLine(input) ?: break
                if (line.isBlank()) continue
                val ev = try { JSONObject(line) } catch (e: Exception) { continue }
                val type = ev.optString("type")
                val dataLen = ev.optInt("data_length", 0)
                val payload = if (dataLen > 0) readN(input, dataLen) else null
                when (type) {
                    "transcript" -> {
                        val text = payload?.let { String(it, Charsets.UTF_8) }
                            ?.let { try { JSONObject(it).optString("text") } catch (e: Exception) { it } }
                        return text?.trim()
                    }
                    "error" -> {
                        val msg = payload?.let { String(it, Charsets.UTF_8) } ?: "whisper error"
                        Log.w(TAG, "[Whisper] server error: $msg")
                        return null
                    }
                    "not-implemented" -> Log.w(TAG, "[Whisper] server does not support $type")
                }
            }
            Log.w(TAG, "[Whisper] timed out waiting for transcript")
            return null
        } finally {
            try { sock.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) b else b.copyOf(off)
    }

    /* ═══════════ text-to-speech ═══════════ */

    /* ═══════════ reply bubble over other apps ═══════════ */

    private var overlayView: TextView? = null

    private val hideOverlay = Runnable { overlayView?.visibility = View.GONE }

    /** Show the reply on top of whatever app is in front (Spotify, YouTube…).
     *  Needs the "display over other apps" permission; without it this is a
     *  silent no-op and the in-app card still shows the text. */
    private fun showOverlay(text: String) {
        if (text.isBlank()) return
        if (!Settings.canDrawOverlays(ctx)) return
        mainHandler.post {
            try {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val tv = overlayView ?: TextView(ctx).apply {
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setBackgroundColor(Color.parseColor("#E6000000"))
                    setPadding(36, 24, 36, 24)
                    maxWidth = 900
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        y = 180
                    }
                    wm.addView(this, lp)
                    overlayView = this
                }
                tv.text = text
                tv.visibility = View.VISIBLE
                mainHandler.removeCallbacks(hideOverlay)
                mainHandler.postDelayed(hideOverlay, 8000)
            } catch (e: Exception) {
                Log.w(TAG, "[Overlay] failed: ${e.message}")
            }
        }
    }

    private fun speak(requestId: String, p: JSONObject) {
        val text = p.optString("text", "").trim()
        if (text.isEmpty()) { resolve(requestId, true, "{}"); return }
        showOverlay(text)
        // The TTS engine takes exclusive audio focus, so every reply paused
        // whatever was playing ("playing music" went silent the moment she
        // confirmed it). Remember the state and resume when the talking ends.
        if (!mediaWasPlaying) mediaWasPlaying = musicActive()
        if (p.has("rate")) speechRate = p.optDouble("rate", speechRate.toDouble()).toFloat().coerceIn(0.5f, 2.0f)
        if (p.has("pitch")) speechPitch = p.optDouble("pitch", speechPitch.toDouble()).toFloat().coerceIn(0.5f, 2.0f)
        val queue = p.optString("queue", "flush") == "add"
        mainHandler.post { ensureTts(requestId, text, queue) }
    }

    var speechRate = 1.0f
    var speechPitch = 1.0f

    /** Media was playing when speech started — resume it afterwards. */
    @Volatile private var mediaWasPlaying = false

    private fun resumeMediaIfNeeded() {
        if (!mediaWasPlaying) return
        mediaWasPlaying = false
        io.execute {
            Thread.sleep(500)
            if (musicActive()) return@execute
            mediaSessionBridge.play()
            Thread.sleep(700)
            if (!musicActive()) automationBridge.pressPlay()
            Log.d(TAG, "[TTS] resumed media after speech (playing=${musicActive()})")
        }
    }

    private val utteranceIds = mutableMapOf<String, String>()

    private var ttsEngine: String? = null
    private var engineRetried = false

    /** Google's engine has Greek; Samsung's (the phone's default) does not. */
    private fun pickTtsEngine(): String? {
        val google = "com.google.android.tts"
        return try {
            ctx.packageManager.getPackageInfo(google, 0)
            google
        } catch (e: Exception) { null }
    }

    private fun createTts(engine: String?, attemptedDefault: Boolean = false) {
        val want = engine ?: if (!attemptedDefault) pickTtsEngine() else null
        ttsEngine = want
        // Shutdown the old engine before replacing it: swapping languages can
        // re-create TTS, and a leaked connection means two live engines (ghost
        // audio, doubled speech).
        try { tts?.shutdown() } catch (e: Exception) { /* ignore */ }
        tts = null
        ttsReady = false
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                ttsInitAttempts = 0
                try { tts?.setOnUtteranceProgressListener(uttListener) } catch (e: Exception) { /* ignore */ }
                Log.d(TAG, "[TTS] ready engine=${want ?: "default"}")
                applyTtsLocale()          // may swap engines if the language is missing
            } else {
                Log.e(TAG, "[TTS] init failed engine=${want ?: "default"} (status=$status)")
                ttsReady = false
                tts = null
                if (want != null && !attemptedDefault) {
                    // Fall back so speech still works now…
                    createTts(null, true)
                    // …and try the preferred engine once more shortly: the
                    // service is often not ready during app start.
                    if (!engineRetried) {
                        engineRetried = true
                        mainHandler.postDelayed({
                            if (ttsEngine != null) return@postDelayed
                            ttsReady = false
                            tts = null
                            createTts(null)
                        }, 6000)
                    }
                    return@OnInitListener
                }
            }
            flushTtsQueue()
        }
        tts = if (want != null) TextToSpeech(ctx, listener, want) else TextToSpeech(ctx, listener)
    }

    private fun flushTtsQueue() {
        val queued = ttsQueue.toList(); ttsQueue.clear()
        queued.forEach { (req, txt) ->
            if (ttsReady) doSpeak(req, txt) else resolve(req, false, err("tts unavailable"))
        }
    }

    /* ═══════════ display + screen capture (for the web panel) ═══════════ */

    @Volatile var displayHook: ((JSONObject) -> Unit)? = null
    @Volatile var screenHook: (((ByteArray?) -> Unit) -> Unit)? = null

    private fun display(requestId: String, p: JSONObject) {
        mainHandler.post {
            try {
                displayHook?.invoke(p)
                resolve(requestId, true, p.toString())
            } catch (e: Exception) {
                resolve(requestId, false, err(e.message ?: "display failed"))
            }
        }
    }

    fun captureScreen(cb: (ByteArray?) -> Unit) {
        val hook = screenHook
        if (hook == null) { cb(null); return }
        mainHandler.post { hook(cb) }
    }

    private fun ensureTts(requestId: String, text: String, queue: Boolean = false) {
        if (ttsReady) { doSpeak(requestId, text, queue); return }
        ttsQueue.add(requestId to text)
        if (tts == null) {
            createTts(null)
            // Bounded retry: if OnInit never fires (wedged engine service),
            // fail the queued utterances instead of hanging until the
            // page's 180 s timeout.
            ttsInitAttempts++
            if (ttsInitAttempts <= 5) {
                mainHandler.postDelayed({ drainTtsQueue() }, 1500)
            } else {
                Log.e(TAG, "[TTS] init never completed — failing queued speech")
                val queued = ttsQueue.toList(); ttsQueue.clear()
                queued.forEach { (req, _) -> resolve(req, false, err("tts unavailable")) }
            }
        } else {
            // still initialising: retry shortly
            mainHandler.postDelayed({ drainTtsQueue() }, 400)
        }
    }

    private fun drainTtsQueue() {
        if (ttsQueue.isEmpty()) return
        if (ttsReady) {
            val queued = ttsQueue.toList(); ttsQueue.clear()
            queued.forEach { (req, txt) -> doSpeak(req, txt) }
        } else if (tts != null) {
            mainHandler.postDelayed({ drainTtsQueue() }, 400)
        } else if (ttsInitAttempts <= 5) {
            ttsInitAttempts++
            createTts(null)
            mainHandler.postDelayed({ drainTtsQueue() }, 1500)
        } else {
            val queued = ttsQueue.toList(); ttsQueue.clear()
            queued.forEach { (req, _) -> resolve(req, false, err("tts unavailable")) }
        }
    }

    private fun doSpeak(requestId: String, text: String, queue: Boolean = false) {
        val id = "loona-" + seq.incrementAndGet()
        utteranceIds[id] = requestId
        try {
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(speechPitch)
        } catch (e: Exception) { /* ignore */ }
        val r = tts?.speak(text, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) {
            utteranceIds.remove(id)
            resolve(requestId, false, err("tts speak failed"))
        } else if (!queue) {
            // QUEUE_FLUSH cancels whatever was speaking; engines don't
            // reliably deliver onDone for the cancelled utterances, which
            // left their promises hanging until the 180 s JS timeout and
            // froze appState at 'speaking'. Resolve them ourselves and emit
            // a done so the page's turn logic can move on.
            val orphaned = utteranceIds.keys.filter { it != id }
            orphaned.forEach { oid ->
                utteranceIds.remove(oid)?.let { resolve(it, true, "{}") }
            }
            if (orphaned.isNotEmpty()) event("tts", JSONObject().put("type", "done"))
        }
    }

    private val uttListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            event("tts", JSONObject().put("type", "start"))
        }
        override fun onDone(utteranceId: String?) {
            event("tts", JSONObject().put("type", "done"))
            utteranceIds.remove(utteranceId)?.let { resolve(it, true, "{}") }
            resumeMediaIfNeeded()
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            event("tts", JSONObject().put("type", "error"))
            resumeMediaIfNeeded()
            utteranceIds.remove(utteranceId)?.let { resolve(it, false, err("tts error")) }
        }
    }

    private fun stopSpeak() {
        mainHandler.post {
            tts?.stop()
            // A stopped engine never calls onDone for the cancelled text —
            // release the waiters and tell the page the speech is over.
            if (utteranceIds.isNotEmpty()) {
                val ids = utteranceIds.keys.toList()
                ids.forEach { id -> utteranceIds.remove(id)?.let { resolve(it, true, "{}") } }
                event("tts", JSONObject().put("type", "done"))
            }
        }
    }

    /* ═══════════ schedule: reminders / alarms / timers ═══════════ */

    private fun schedule(requestId: String, kind: String, p: JSONObject) {
        val atMs = p.optLong("atMs", 0L)
        val title = p.optString("title", if (kind == "timer") "Timer" else kind.replaceFirstChar { it.uppercase() })
        val message = p.optString("message", title)
        val durationMs = p.optLong("ms", 0L)
        val whenMs = if (kind == "timer") System.currentTimeMillis() + durationMs else atMs
        if (whenMs <= 0) { resolve(requestId, false, err("no time")); return }

        try {
            LoonaAlerts.ensureChannels(ctx)
            val id = ITEM_PREFIX + kind + "-" + System.currentTimeMillis() + "-" + seq.incrementAndGet()
            val pi = alertPendingIntent(id, kind, title, message, whenMs)
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val exact = Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()
            when {
                exact -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
                else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            }
            saveItem(id, kind, title, whenMs)
            Log.d(TAG, "[Schedule] $kind '$title' in ${whenMs - System.currentTimeMillis()}ms exact=$exact")
            val out = JSONObject()
                .put("id", id)
                .put("atMs", whenMs)
                .put("exact", exact)
                .put("notificationsEnabled", notificationsEnabled())
            resolve(requestId, true, out.toString())
        } catch (e: Exception) {
            Log.e(TAG, "[Schedule] failed: $e")
            resolve(requestId, false, err(e.message ?: "schedule failed"))
        }
    }

    private fun cancel(requestId: String, p: JSONObject) {
        val kind = p.optString("kind", "reminder")
        val items = loadItems()
        var cancelled = 0
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val keep = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (kind == "all" || item.optString("kind") == kind) {
                val pi = alertPendingIntent(
                    item.optString("id"), item.optString("kind"),
                    item.optString("title"), item.optString("title"), item.optLong("atMs")
                )
                am.cancel(pi)
                pi.cancel()
                cancelled++
            } else {
                keep.put(item)
            }
        }
        saveItems(keep)
        Log.d(TAG, "[Schedule] cancelled $cancelled ($kind)")
        resolve(requestId, true, JSONObject().put("cancelled", cancelled).toString())
    }

    private fun alertPendingIntent(id: String, kind: String, title: String, message: String, atMs: Long): PendingIntent {
        val intent = Intent(ctx, AlertReceiver::class.java).apply {
            action = "com.pal9000.robot.ALERT"
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TITLE + "_msg", message)
            putExtra("atMs", atMs)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, id.hashCode(), intent, flags)
    }

    private fun notificationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // ── schedule store (shared prefs) ──
    private fun loadItems(): JSONArray = ScheduleStore.load(ctx)
    private fun saveItems(arr: JSONArray) = ScheduleStore.save(ctx, arr)

    private fun saveItem(id: String, kind: String, title: String, atMs: Long) {
        val arr = ScheduleStore.load(ctx)
        arr.put(JSONObject().put("id", id).put("kind", kind).put("title", title).put("atMs", atMs))
        ScheduleStore.save(ctx, arr)
    }

    fun removeItem(id: String) = ScheduleStore.remove(ctx, id)

    /* ═══════════ device controls ═══════════ */

    private fun torch(requestId: String, p: JSONObject) {
        try {
            val on = p.optBoolean("on", true)
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return resolve(requestId, false, err("no camera with flash"))
            cm.setTorchMode(cameraId, on)
            Log.d(TAG, "[Torch] $on")
            resolve(requestId, true, JSONObject().put("on", on).toString())
        } catch (e: Exception) {
            resolve(requestId, false, err(e.message ?: "torch failed"))
        }
    }

    private fun brightness(requestId: String, p: JSONObject) {
        try {
            if (!Settings.System.canWrite(ctx)) {
                // Bounce straight to the permission screen instead of failing
                // with an error the person can't act on.
                try {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) { Log.w(TAG, "[Brightness] cannot open settings: $e") }
                return resolve(requestId, false, JSONObject()
                    .put("error", "allow 'Modify system settings' for PAL9000, then ask again").toString())
            }
            val cur = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val value = when {
                p.has("value") -> (p.optDouble("value", 0.5) * 255).toInt()
                p.optString("op") == "up" -> cur + 40
                p.optString("op") == "down" -> cur - 40
                else -> cur
            }.coerceIn(10, 255)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            Log.d(TAG, "[Brightness] $value")
            resolve(requestId, true, JSONObject().put("value", value).toString())
        } catch (e: Exception) {
            resolve(requestId, false, err(e.message ?: "brightness failed"))
        }
    }

    private fun volume(requestId: String, p: JSONObject) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stream = when (p.optString("stream", "media")) {
                "alarm" -> AudioManager.STREAM_ALARM
                "ring" -> AudioManager.STREAM_RING
                else -> AudioManager.STREAM_MUSIC
            }
            val max = am.getStreamMaxVolume(stream)
            val op = p.optString("op")
            val level = when {
                p.has("value") -> (p.optDouble("value", 0.5) * max).toInt()
                op == "up" -> am.getStreamVolume(stream) + 1
                op == "down" -> am.getStreamVolume(stream) - 1
                op == "mute" -> 0
                else -> am.getStreamVolume(stream)
            }.coerceIn(0, max)
            am.setStreamVolume(stream, level, 0)
            Log.d(TAG, "[Volume] stream=$stream level=$level/$max")
            resolve(requestId, true, JSONObject().put("level", level).put("max", max).toString())
        } catch (e: Exception) {
            resolve(requestId, false, err(e.message ?: "volume failed"))
        }
    }

    private fun appsJson(): String {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val acts = pm.queryIntentActivities(intent, 0)
        val arr = JSONArray()
        acts.sortedBy { it.loadLabel(pm).toString().lowercase() }.forEach { ai ->
            arr.put(JSONObject()
                .put("label", ai.loadLabel(pm).toString())
                .put("pkg", ai.activityInfo.packageName))
        }
        return JSONObject().put("apps", arr).toString()
    }

    private fun openApp(requestId: String, p: JSONObject) {
        val q = p.optString("query", "").lowercase().trim()
        // Greek recognizers transliterate app names ("σποτιφάι", "γιουτούμπ") —
        // map the common ones back to the launcher label instead of failing.
        val raw = when {
            q.contains("σποτιφ") -> "spotify"
            q.contains("γιουτιουμπ") || q.contains("γιουτιούμπ") || q.contains("γιου τιουμπ") -> "youtube"
            q.contains("ινσταγκραμ") -> "instagram"
            else -> q
        }
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val acts = pm.queryIntentActivities(intent, 0)
        val labels = acts.map { it to it.loadLabel(pm).toString().lowercase() }
        // Spoken names are shorter than the launcher labels: "yt" means YouTube,
        // not "YT Music", and "youtube music" is that app spelled out.
        val queries = when {
            raw.isEmpty() -> listOf(raw)
            raw == "yt" || raw == "youtube" || raw == "you tube" || raw == "tube" -> listOf("youtube")
            raw.replace(" ", "") == "ytmusic" || raw == "youtube music" || raw == "yt music" ->
                listOf("yt music", "youtube music")
            raw == "insta" -> listOf("instagram")
            raw == "google" -> listOf("google")
            else -> listOf(raw)
        }
        var match: android.content.pm.ResolveInfo? = null
        for (q in queries) {
            match = labels.firstOrNull { it.second == q }?.first
                ?: labels.firstOrNull { it.second.startsWith(q) }?.first
                ?: labels.firstOrNull { it.second.contains(q) }?.first
                ?: labels.firstOrNull { it.second.split(Regex("[\\s\\-_.]+")).any { w -> w == q } }?.first
                ?: labels.firstOrNull { q.split(Regex("\\s+")).all { w -> it.second.contains(w) } }?.first
            if (match != null) break
        }
        if (match == null) {
            resolve(requestId, false, JSONObject().put("error", "no app matches '$raw'").toString())
            return
        }
        val pkg = match.activityInfo.packageName
        if (!appAccessEnabled(pkg)) {
            resolve(requestId, false, JSONObject()
                .put("error", "app access disabled: ${match.loadLabel(pm)}")
                .put("pkg", pkg).toString())
            return
        }
        val launch = pm.getLaunchIntentForPackage(pkg)
        if (launch == null) { resolve(requestId, false, err("cannot launch")); return }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ctx.startActivity(launch)
        Log.d(TAG, "[App] opened ${match.loadLabel(pm)} ($pkg)")
        resolve(requestId, true, JSONObject().put("label", match.loadLabel(pm).toString()).put("pkg", pkg).toString())
    }

    /* ── intents: calls, links, search, navigation, media keys ── */

    private fun launch(requestId: String, intent: Intent, label: String) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
            Log.d(TAG, "[Intent] $label")
            resolve(requestId, true, JSONObject().put("launched", label).toString())
        } catch (e: Exception) {
            Log.e(TAG, "[Intent] $label failed: $e")
            resolve(requestId, false, err("nothing can open $label"))
        }
    }

    private fun dial(requestId: String, p: JSONObject) {
        val number = p.optString("number", "").trim()
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.length >= 3) {
            launch(requestId, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(digits))), "dial $digits")
        } else {
            // "call mom" — open the dialer/contacts and let the tap finish it
            launch(requestId, Intent(Intent.ACTION_DIAL), "dialer")
        }
    }

    private fun openUrl(requestId: String, p: JSONObject) {
        val url = p.optString("url", "").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            resolve(requestId, false, err("not a web url")); return
        }
        launch(requestId, Intent(Intent.ACTION_VIEW, Uri.parse(url)), url)
    }

    private fun search(requestId: String, p: JSONObject) {
        val q = p.optString("query", "").trim()
        if (q.isEmpty()) { resolve(requestId, false, err("nothing to search")); return }
        launch(requestId,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))),
            "search $q")
    }

    private fun youtube(requestId: String, p: JSONObject) {
        val q = p.optString("query", "").trim()
        // A query means "play it", not "show me a list". The old behaviour
        // opened the results page and stopped, which is why "play X" looked
        // like she had only searched. `play:false` keeps search-only.
        val play = p.optBoolean("play", q.isNotEmpty())
        // The vnd.youtube results deep link opens the real results list; the
        // https URL sometimes lands on the search suggestion overlay, which is
        // what left her "stuck on the search tab".
        val uri = if (q.isEmpty())
            Uri.parse("https://www.youtube.com")
        else
            Uri.parse("vnd.youtube://results?search_query=" + Uri.encode(q))
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
            Log.d(TAG, "[Intent] youtube $q")
        } catch (e: Exception) {
            resolve(requestId, false, err("nothing can open YouTube"))
            return
        }
        if (!play || q.isEmpty()) {
            resolve(requestId, true, JSONObject().put("launched", "youtube $q").toString())
            return
        }
        // Results render asynchronously: wait, then tap the first matching
        // result (scrolling if the list needs it). Resolve only once the tap
        // happened, so the reply can say honestly whether it started playing.
        io.execute {
            var r: Map<String, Any?> = emptyMap()
            for (i in 0 until 3) {
                Thread.sleep(if (i == 0) 2800L else 2000L)
                r = automationBridge.tapSearchResult(q)
                if (r["ok"] == true) break
            }
            var ok = r["ok"] == true
            // A tap that lands on the search box reports success but nothing
            // starts. Only claim playback when audio is really running.
            if (ok) {
                Thread.sleep(1200)
                if (!musicActive()) {
                    val retry = automationBridge.tapSearchResult(q)
                    if (retry["ok"] == true) Thread.sleep(1500)
                    r = retry
                }
                ok = musicActive()
            }
            if (ok) mediaWasPlaying = true
            Log.d(TAG, "[YouTube] autoplay '$q' → playing=$ok ${r["evidence"] ?: r["error"]}")
            Diag.add("YouTube '$q' → ${r["evidence"] ?: r["error"]}")
            resolve(requestId, true, JSONObject()
                .put("query", q)
                .put("playing", ok)
                .put("evidence", r["evidence"] ?: r["error"] ?: "")
                .toString())
        }
    }

    /**
     * "play despacito on spotify" — open Spotify's own search (deep link) and
     * tap the first matching row, which starts playback. Media keys alone
     * cannot choose a song, and sending the query to YouTube opened the wrong
     * app entirely.
     */
    private fun spotify(requestId: String, p: JSONObject) {
        val q = p.optString("query", "").trim()
        val pkg = "com.spotify.music"
        val intent = if (q.isEmpty())
            ctx.packageManager.getLaunchIntentForPackage(pkg)
        else
            Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))).setPackage(pkg)
        if (intent == null) {
            resolve(requestId, false, JSONObject().put("error", "spotify is not installed").toString())
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            resolve(requestId, false, err("spotify launch failed: ${e.message}"))
            return
        }
        if (q.isEmpty()) {
            resolve(requestId, true, JSONObject().put("launched", "Spotify").toString())
            return
        }
        Log.d(TAG, "[Spotify] search '$q'")
        io.execute {
            var r: Map<String, Any?> = emptyMap()
            for (i in 0 until 3) {
                // Spotify paints the search page before the result rows.
                Thread.sleep(if (i == 0) 5500L else 2500L)
                r = automationBridge.tapSearchResult(q, "spotify")
                if (r["ok"] == true) break
            }
            var ok = r["ok"] == true
            if (ok) mediaWasPlaying = true
            // Opening a result can load the track without starting it; make
            // sure audio is actually running before we claim it is playing.
            if (ok) {
                Thread.sleep(1500)
                if (!musicActive()) {
                    mediaSessionBridge.play()
                    Thread.sleep(600)
                }
                if (!musicActive()) {
                    automationBridge.pressPlay()
                    Thread.sleep(800)
                }
                ok = musicActive()
            }
            Log.d(TAG, "[Spotify] play '$q' → playing=$ok ${r["evidence"] ?: r["error"]}")
            Diag.add("Spotify '$q' → ${r["evidence"] ?: r["error"]}")
            resolve(requestId, ok, JSONObject()
                .put("query", q)
                .put("playing", ok)
                .put("evidence", r["evidence"] ?: r["error"] ?: "")
                .toString())
        }
    }

    private fun navigate(requestId: String, p: JSONObject) {
        val to = p.optString("query", "").trim()
        if (to.isEmpty()) { resolve(requestId, false, err("nowhere to go")); return }
        // Prefer turn-by-turn Maps, fall back to a plain geo: intent
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(to)))
            .setPackage("com.google.android.apps.maps")
        nav.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(nav)
            resolve(requestId, true, JSONObject().put("launched", "navigate to $to").toString())
        } catch (e: Exception) {
            launch(requestId, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(to))), "navigate $to")
        }
    }

    /** Is audio actually coming out of the music stream right now? */
    private fun musicActive(): Boolean = try {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
    } catch (e: Exception) {
        false
    }

    private fun media(requestId: String, p: JSONObject) {
        val op = p.optString("op", "play")
        // "Pause the music" must stay paused when she finishes the reply —
        // don't let the after-speech resume undo the user's own command.
        if (op == "pause") mediaWasPlaying = false
        val result = try {
            when (op) {
                "play" -> mediaSessionBridge.play()
                "pause" -> mediaSessionBridge.pause()
                "next" -> mediaSessionBridge.nextTrack()
                "prev", "previous" -> mediaSessionBridge.previousTrack()
                "stop" -> mediaSessionBridge.stop()
                else -> mediaSessionBridge.play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Media] session command $op failed: ${e.message}")
            false
        }
        // A cold-opened player accepts the key but stays paused. Verify and, if
        // it is still silent, press its own Play control.
        if (result && (op == "play" || op == "toggle")) {
            Thread.sleep(600)
            if (!musicActive()) {
                val tap = automationBridge.pressPlay()
                Log.d(TAG, "[Media] key ignored, tapped Play → $tap")
                Thread.sleep(600)
            }
        }
        if (result) {
            if (op == "play" || op == "toggle") mediaWasPlaying = true
            Log.d(TAG, "[Media] $op via session")
            resolve(requestId, true, JSONObject().put("op", op).put("session", true).toString())
        } else {
            // Fall back to media key events (same behaviour as before sessions existed).
            val key = when (op) {
                "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                "toggle" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                "prev", "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
                else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
                Log.d(TAG, "[Media] $op via key-event fallback")
                resolve(requestId, true, JSONObject().put("op", op).put("session", false).toString())
            } catch (e: Exception) {
                resolve(requestId, false, err(e.message ?: "media key failed"))
            }
        }
    }

    /* ═══════════ Home Assistant (LAN, long-lived access token) ═══════════ */

    private fun haPrefs() = ctx.getSharedPreferences("pal_ha", Context.MODE_PRIVATE)

    /** Backoff after HA bans this phone: skip network calls until this time. */
    @Volatile private var haBannedUntil = 0L

    /**
     * Home Assistant bridge. Actions:
     *   config  {url, token}          → save (empty token keeps the stored one)
     *   states  {}                    → GET /api/states (id, name, domain, state)
     *   call    {domain, service, target, value}
     *                                 → POST /api/services/{domain}/{service}
     * target is matched against friendly names (Greek/English, accent-insensitive);
     * null target = every entity of that domain.
     */
    private fun ha(requestId: String, p: JSONObject) {
        when (p.optString("action", "call")) {
            "config" -> {
                val e = haPrefs().edit()
                if (p.has("url")) e.putString("url", haNormalizeUrl(p.optString("url")))
                if (p.has("token") && p.optString("token").isNotBlank()) {
                    // A pasted long-lived token never expires: drop any old session.
                    e.putString("token", p.optString("token").trim())
                    e.remove("refresh")
                    e.putLong("expires", 0L)
                }
                e.apply()
                haStatesCache = null   // new server/token: drop cached entities
                haBannedUntil = 0L     // user reconfigured: allow retry
                resolve(requestId, true, haConfigJson())
            }
            "test" -> io.execute { haTest(requestId) }
            "login" -> io.execute { haLogin(requestId, p) }
            "discover" -> haDiscover(requestId)
            "states" -> io.execute { haStates(requestId) }
            "call" -> io.execute { haCall(requestId, p) }
            else -> resolve(requestId, false, err("unknown ha action"))
        }
    }

    private fun haConfigJson(): String = JSONObject()
        .put("url", haPrefs().getString("url", "") ?: "")
        .put("hasToken", !(haPrefs().getString("token", "") ?: "").isBlank())
        .put("signedIn", !(haPrefs().getString("refresh", "") ?: "").isBlank())
        .toString()

    /** Entity list kept for a minute: every command used to download all
     *  states first, which was the slow part. */
    private data class HaCache(val at: Long, val json: JSONArray)
    @Volatile private var haStatesCache: HaCache? = null

    private fun haStatesRaw(base: String, force: Boolean = false): JSONArray {
        val c = haStatesCache
        if (!force && c != null && System.currentTimeMillis() - c.at < 60_000) return c.json
        val now = System.currentTimeMillis()
        if (now < haBannedUntil) throw IllegalStateException("home assistant has banned ${localIp()} (retry in ${((haBannedUntil - now) / 60000 + 1)} min)")
        var conn: HttpURLConnection? = null
        try {
            conn = haConn("$base/api/states", "GET", null)
            val code = conn.responseCode
            if (code == 403) { haBannedUntil = System.currentTimeMillis() + 30L * 60 * 1000; throw IllegalStateException("Home Assistant has banned ${localIp()}. Clear ip_bans.yaml or change the phone's IP, then restart HA.") }
            if (code !in 200..299) throw IllegalStateException("home assistant answered $code")
            val arr = JSONArray(haRead(conn))
            haStatesCache = HaCache(System.currentTimeMillis(), arr)
            return arr
        } finally { try { conn?.disconnect() } catch (e: Exception) { } }
    }

    /** Find the Home Assistant server on the local network (zeroconf), so the
     *  URL never has to be typed — the companion app uses the same record. */
    private fun haDiscover(requestId: String) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var listener: android.net.nsd.NsdManager.DiscoveryListener? = null
        var timeout: Runnable? = null

        fun finish(ok: Boolean, payload: String?) {
            if (!done.compareAndSet(false, true)) return
            try { listener?.let { nsd.stopServiceDiscovery(it) } } catch (e: Exception) { }
            timeout?.let { mainHandler.removeCallbacks(it) }
            if (ok) resolve(requestId, true, payload ?: "{}")
            else resolve(requestId, false, err(payload ?: "home assistant not found"))
        }

        listener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) =
                finish(false, "zeroconf blocked (error $errorCode)")
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) { }
            override fun onDiscoveryStarted(serviceType: String?) { }
            override fun onDiscoveryStopped(serviceType: String?) { }
            override fun onServiceLost(info: android.net.nsd.NsdServiceInfo?) { }
            override fun onServiceFound(info: android.net.nsd.NsdServiceInfo) {
                if (done.get()) return
                try {
                    nsd.resolveService(info, object : android.net.nsd.NsdManager.ResolveListener {
                        override fun onResolveFailed(i: android.net.nsd.NsdServiceInfo?, errorCode: Int) { }
                        override fun onServiceResolved(r: android.net.nsd.NsdServiceInfo) {
                            if (done.get()) return
                            val host = r.host?.hostAddress ?: return
                            val url = "http://$host:${r.port}"
                            val e = haPrefs().edit()
                            if ((haPrefs().getString("url", "") ?: "").isBlank()) e.putString("url", url)
                            e.apply()
                            haStatesCache = null
                            Diag.add("home assistant found at $url")
                            finish(true, JSONObject().put("url", url).put("name", r.serviceName).toString())
                        }
                    })
                } catch (e: Exception) { /* keep listening */ }
            }
        }

        timeout = Runnable { finish(false, "not found in 6 s — type the IP instead") }
        mainHandler.postDelayed(timeout, 6500)
        try {
            nsd.discoverServices("_home-assistant._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            finish(false, e.message ?: "discovery failed")
        }
    }

    /** "homeassistant.local:8123" → "http://homeassistant.local:8123"; strip /api and slashes. */
    private fun haNormalizeUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isBlank()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (u.endsWith("/api")) u = u.removeSuffix("/api")
        return u
    }

    /** This phone's LAN address, so an HA ban notice can name the right IP. */
    private fun localIp(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress ?: "this phone"
        } catch (e: Exception) { "this phone" }
    }

    /**
     * Sign in with the account that is already on the phone's Home Assistant:
     * POST /auth/login_flow → /auth/login_flow/{id} → /auth/token, then keep
     * the access token fresh with the refresh token. No token copying needed.
     */
    private fun haLogin(requestId: String, p: JSONObject) {
        val base = haPrefs().getString("url", "") ?: ""
        if (base.isBlank()) { resolve(requestId, false, err("find the server first")); return }
        val user = p.optString("username", "").trim()
        val pass = p.optString("password", "")
        if (user.isBlank() || pass.isBlank()) { resolve(requestId, false, err("username and password required")); return }
        val clientId = "$base/"
        try {
            val flow = haPost("$base/auth/login_flow", JSONObject()
                .put("client_id", clientId)
                // HA expects the handler as [name, extra], not a bare string.
                .put("handler", JSONArray().put("homeassistant").put(JSONObject.NULL))
                .put("redirect_uri", clientId))
            val flowId = flow.optString("flow_id")
            if (flowId.isBlank()) { resolve(requestId, false, err("login flow refused")); return }
            val step = haPost("$base/auth/login_flow/$flowId", JSONObject()
                .put("client_id", clientId).put("username", user).put("password", pass))
            val type = step.optString("type")
            if (type == "form" && step.optJSONObject("errors") != null) {
                val msg = step.optJSONObject("errors")?.keys()?.asSequence()?.joinToString() ?: "login rejected"
                resolve(requestId, false, err("login rejected: $msg")); return
            }
            if (type != "create_entry") {
                val fields = step.optJSONArray("step")?.toString() ?: ""
                resolve(requestId, false, err("extra step needed (2FA?) $fields".trim())); return
            }
            val code = step.optString("result")
            val tok = haPost("$base/auth/token", JSONObject()
                .put("grant_type", "authorization_code").put("code", code).put("client_id", clientId))
            storeHaTokens(tok)
            Diag.add("home assistant: signed in")
            resolve(requestId, true, JSONObject().put("signedIn", true).toString())
        } catch (e: Exception) {
            Log.w(TAG, "[HA] login failed: ${e.message}")
            resolve(requestId, false, err(e.message ?: "login failed"))
        }
    }

    private fun storeHaTokens(tok: JSONObject) {
        val access = tok.optString("access_token", "")
        val e = haPrefs().edit()
        if (access.isNotBlank()) e.putString("token", access)
        val refresh = tok.optString("refresh_token", "")
        if (refresh.isNotBlank()) e.putString("refresh", refresh)
        val ttl = tok.optLong("expires_in", 0L)
        e.putLong("expires", if (ttl > 0) System.currentTimeMillis() + ttl * 1000 else 0L)
        e.apply()
    }

    /** Access token, refreshed with the stored refresh token when it is old. */
    private fun haAccessToken(force: Boolean = false): String {
        val p = haPrefs()
        val tok = p.getString("token", "") ?: ""
        val exp = p.getLong("expires", 0L)
        val refresh = p.getString("refresh", "") ?: ""
        if (!force && tok.isNotBlank() && (exp == 0L || System.currentTimeMillis() < exp - 60_000)) return tok
        if (refresh.isBlank()) return tok
        synchronized(this) {
            val p2 = haPrefs()
            val tok2 = p2.getString("token", "") ?: ""
            val exp2 = p2.getLong("expires", 0L)
            if (!force && tok2.isNotBlank() && (exp2 == 0L || System.currentTimeMillis() < exp2 - 60_000)) return tok2
            val base = p2.getString("url", "") ?: ""
            if (base.isBlank()) return tok2
            return try {
                val t = haPost("$base/auth/token", JSONObject()
                    .put("grant_type", "refresh_token").put("refresh_token", refresh)
                    .put("client_id", "$base/"))
                storeHaTokens(t)
                t.optString("access_token", tok2)
            } catch (e: Exception) {
                Log.w(TAG, "[HA] token refresh failed: ${e.message}")
                tok2
            }
        }
    }

    private fun haPost(url: String, body: JSONObject): JSONObject {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } } ?: ""
            if (code == 403) {
                haBannedUntil = System.currentTimeMillis() + 30L * 60 * 1000
                throw IllegalStateException("Home Assistant has banned ${localIp()}. Clear ip_bans.yaml or change the phone's IP, then restart HA.")
            }
            if (code !in 200..299) throw IllegalStateException("home assistant answered $code ${txt.take(120)}")
            return JSONObject(txt)
        } finally { try { conn?.disconnect() } catch (e: Exception) { } }
    }

    /** Settings "Test": proves the URL + token work and reports what HA knows. */
    private fun haTest(requestId: String) {
        val base = haPrefs().getString("url", "") ?: ""
        if (base.isBlank()) { resolve(requestId, false, err("home assistant url not set")); return }
        if (haAccessToken().isBlank()) {
            resolve(requestId, false, err("not signed in — enter your Home Assistant account")); return
        }
        var conn: HttpURLConnection? = null
        try {
            conn = haConn("$base/api/", "GET", null)
            val code = conn.responseCode
            if (code == 401) {
                resolve(requestId, false, err("token rejected — create a new long-lived token")); return
            }
            if (code == 403) {
                haBannedUntil = System.currentTimeMillis() + 30L * 60 * 1000
                // HA answers 403 to *everything* from a banned address, so this is
                // never a token problem — say what actually fixes it.
                resolve(requestId, false, err("Home Assistant has banned ${localIp()}. Fix: (1) delete that IP's entry in ip_bans.yaml and restart HA, or (2) change the phone's Wi-Fi IP, or (3) set ip_ban_enabled: false in configuration.yaml.")); return
            }
            if (code !in 200..299) {
                resolve(requestId, false, err("home assistant answered $code")); return
            }
            haRead(conn); conn.disconnect(); conn = null
            val arr = haStatesRaw(base, force = true)
            val sample = JSONArray()
            var lights = 0
            for (i in 0 until arr.length()) {
                val st = arr.getJSONObject(i)
                val id = st.optString("entity_id")
                if (id.startsWith("light.") || id.startsWith("switch.")) lights++
                if (sample.length() < 6 &&
                    (id.startsWith("light.") || id.startsWith("switch.") || id.startsWith("climate."))) {
                    val nm = (st.optJSONObject("attributes") ?: JSONObject()).optString("friendly_name", id)
                    sample.put(nm)
                }
            }
            resolve(requestId, true, JSONObject()
                .put("url", base).put("entities", arr.length()).put("controllable", lights)
                .put("sample", sample).toString())
        } catch (e: java.net.UnknownHostException) {
            resolve(requestId, false, err("cannot find ${base.removePrefix("http://").removePrefix("https://").substringBefore("/").substringBefore(":")} — use the home assistant IP, e.g. http://192.168.1.50:8123"))
        } catch (e: Exception) {
            resolve(requestId, false, err(e.message ?: "home assistant unreachable"))
        } finally { try { conn?.disconnect() } catch (e: Exception) { } }
    }

    private fun haConn(url: String, method: String, body: String?): HttpURLConnection {
        val token = haAccessToken()
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 10_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        if (body != null) {
            c.doOutput = true
            OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) }
        }
        return c
    }

    private fun haRead(c: HttpURLConnection): String =
        BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { it.readText() }

    private fun haStates(requestId: String) {
        val base = haPrefs().getString("url", "") ?: ""
        if (base.isBlank()) { resolve(requestId, false, err("home assistant not configured")); return }
        try {
            val arr = haStatesRaw(base)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val st = arr.getJSONObject(i)
                val id = st.optString("entity_id")
                val attrs = st.optJSONObject("attributes") ?: JSONObject()
                val name = attrs.optString("friendly_name", id)
                out.put(JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("domain", id.substringBefore('.', ""))
                    .put("state", st.optString("state")))
            }
            resolve(requestId, true, JSONObject().put("entities", out).toString())
        } catch (e: Exception) {
            Log.w(TAG, "[HA] states failed: ${e.message}")
            resolve(requestId, false, err(e.message ?: "home assistant unreachable"))
        }
    }

    private fun haCall(requestId: String, p: JSONObject) {
        val base = haPrefs().getString("url", "") ?: ""
        if (base.isBlank()) { resolve(requestId, false, err("home assistant not configured")); return }
        val now = System.currentTimeMillis()
        if (now < haBannedUntil) { resolve(requestId, false, err("home assistant has banned ${localIp()} (retry in ${((haBannedUntil - now) / 60000 + 1)} min). Clear ip_bans.yaml or use a different phone IP.")); return }
        val domain = p.optString("domain", "light")
        val service = p.optString("service", "turn_on")
        val target = p.optString("target", "").trim()
        var conn: HttpURLConnection? = null
        try {
            // Resolve the spoken target ("kitchen", "κουζίνα") to entity ids.
            val ids = JSONArray()
            val single = p.optString("entity", "").trim()
            if (single.isNotBlank()) {
                // Panel toggles pass the exact entity — no name matching.
                ids.put(single)
            } else if (target.isNotBlank() || service == "set_temperature") {
                val arr = haStatesRaw(base)
                val needle = haNormalize(target)
                // Most wall switches here are Sonoff *switches*, not light.* entities,
                // so a named light also searches switches. Unnamed "all lights"
                // stays inside the requested domain — otherwise it would flip
                // every plug in the house.
                val domains = if (target.isBlank()) setOf(domain) else when (domain) {
                    "light" -> setOf("light", "switch", "input_boolean")
                    "switch" -> setOf("switch", "light", "input_boolean")
                    "cover" -> setOf("cover", "switch")
                    else -> setOf(domain)
                }
                val exact = JSONArray()
                val loose = JSONArray()
                for (i in 0 until arr.length()) {
                    val st = arr.getJSONObject(i)
                    val id = st.optString("entity_id")
                    val dom = id.substringBefore('.', "")
                    if (dom !in domains) continue
                    if (target.isBlank()) { ids.put(id); continue }
                    val attrs = st.optJSONObject("attributes") ?: JSONObject()
                    val name = haNormalize(attrs.optString("friendly_name", id))
                    // "bedroom" must not also hit "parent bedroom": exact names win.
                    when {
                        name == needle || haNormalize(id) == needle -> exact.put(id)
                        name.contains(needle) || needle.contains(name) || haNormalize(id).contains(needle) -> loose.put(id)
                    }
                }
                if (exact.length() > 0) {
                    for (i in 0 until exact.length()) ids.put(exact.getString(i))
                } else {
                    for (i in 0 until loose.length()) ids.put(loose.getString(i))
                }
                if (ids.length() == 0) {
                    resolve(requestId, false, err("no $domain matched \"$target\"")); return
                }
                Diag.add("home assistant: $domain.$service → ${ids.length()} entity(ies) for \"$target\"")
            }
            val body = JSONObject()
            if (ids.length() > 0) body.put("entity_id", ids)
            if (service == "set_temperature") body.put("temperature", p.optInt("value", 21))
            conn = haConn("$base/api/services/$domain/$service", "POST", body.toString())
            val code = conn.responseCode
            if (code == 403) {
                haBannedUntil = System.currentTimeMillis() + 30L * 60 * 1000
                resolve(requestId, false, err("Home Assistant has banned ${localIp()}. Clear ip_bans.yaml or change the phone's IP, then restart HA.")); return
            }
            if (code !in 200..299) {
                val msg = try { conn.errorStream?.readBytes()?.decodeToString() ?: "" } catch (e: Exception) { "" }
                resolve(requestId, false, err("home assistant answered $code ${msg.take(120)}")); return
            }
            haRead(conn)   // drain: HA answers with the updated states
            Log.d(TAG, "[HA] $domain.$service -> ${ids.length()} entity(ies)")
            resolve(requestId, true, JSONObject()
                .put("domain", domain).put("service", service)
                .put("count", ids.length()).toString())
        } catch (e: Exception) {
            Log.w(TAG, "[HA] call failed: ${e.message}")
            resolve(requestId, false, err(e.message ?: "home assistant unreachable"))
        } finally { try { conn?.disconnect() } catch (e: Exception) { } }
    }

    /** Lowercase + strip accents so "κουζίνα" matches "Kouzina"/"Κουζίνα". */
    private fun haNormalize(s: String): String =
        java.text.Normalizer.normalize(s.trim().lowercase(Locale.US), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    /** App-control automation entry point: {method, payload} with methods
     * listApps, launchApp, snapshot, tapLabel, scroll, typeText, pressBack,
     * runTask, cancelTask. */
    private fun automation(requestId: String, p: JSONObject) {
        // Automation steps sleep hundreds of ms each; running them on the
        // WebView's single @JavascriptInterface thread blocked EVERY bridge
        // call (listen/speak/http) for seconds right after a wake.
        io.execute { automationBlocking(requestId, p) }
    }

    private fun automationBlocking(requestId: String, p: JSONObject) {
        // The panel sends {method, payload:{...}} (nested), but the methods below
        // read query/target/text from the top-level object. Without this every call
        // got an empty payload and resolveApp("") matched the first app in the launcher
        // list, so "open youtube" always opened Gallery. Read from the nested payload,
        // falling back to the outer object for flat {method, query} calls.
        val p = p.optJSONObject("payload") ?: p
        val method = p.optString("method", "")
        val result = try {
            when (method) {
                "listApps" -> {
                    val apps = automationBridge.listApps()
                    val arr = JSONArray()
                    apps.forEach { arr.put(JSONObject(it)) }
                    JSONObject().put("ok", true).put("apps", arr)
                }
                "launchApp" -> {
                    val pkg = p.optString("query", "")
                    val r: Map<String, Any?> = automationBridge.launchApp(pkg)
                    JSONObject().put("ok", (r["ok"] as? Boolean ?: false)).put("result", JSONObject(r))
                }
                "snapshot" -> {
                    val r: Map<String, Any?> = automationBridge.snapshot()
                    val arr = JSONArray()
                    (r["elements"] as? List<Map<String, Any?>> ?: emptyList()).forEach { arr.put(mapToJsonObject(it)) }
                    JSONObject().put("ok", (r["ok"] as? Boolean ?: false)).put("result", JSONObject().put("elements", arr).put("topPackage", r["topPackage"]))
                }
                "tapLabel" -> {
                    val label = p.optString("target", "")
                    val r: Map<String, Any?> = automationBridge.tapLabel(label)
                    JSONObject().put("ok", (r["ok"] as? Boolean ?: false)).put("result", JSONObject(r))
                }
                "scroll" -> {
                    val label = p.optString("target", "")
                    val down = p.optString("text", "") == "down"
                    val r: Map<String, Any?> = automationBridge.scroll(label, down)
                    JSONObject().put("ok", (r["ok"] as? Boolean ?: false)).put("result", JSONObject(r))
                }
                "typeText" -> {
                    val label = p.optString("target", "")
                    val text = p.optString("text", "")
                    val r: Map<String, Any?> = automationBridge.typeText(label, text)
                    JSONObject().put("ok", (r["ok"] as? Boolean ?: false)).put("result", JSONObject(r))
                }
                "pressBack" -> {
                    val r: Map<String, Any?> = automationBridge.pressBack()
                    JSONObject().put("ok", (r["ok"] as? Boolean ?: false)).put("result", JSONObject(r))
                }
                "runTask" -> {
                    val taskId = p.optString("taskId", "task-" + System.currentTimeMillis())
                    val arr = p.optJSONArray("steps")
                    val steps = if (arr != null) {
                        (0 until arr.length()).map { i ->
                            val o = arr.optJSONObject(i) ?: JSONObject()
                            mapOf(
                                "action" to o.optString("action", ""),
                                "target" to o.opt("target")?.toString(),
                                "text" to o.opt("text")?.toString()
                            )
                        }
                    } else emptyList<Map<String, String?>>()
                    val r: Map<String, Any?> = automationBridge.runTask(taskId, steps)
                    val results = (r["results"] as? List<Map<String, Any?>> ?: emptyList())
                    val resultsArr = JSONArray()
                    results.forEach { resultsArr.put(mapToJsonObject(it)) }
                    JSONObject().put("ok", (r["ok"] as? Boolean ?: false)).put("result", JSONObject().put("stoppedAt", r["stoppedAt"]).put("results", resultsArr))
                }
                else -> JSONObject().put("ok", false).put("error", "unknown automation method: $method")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "automation failed: ${e.message}")
        }
        resolve(requestId, result.optBoolean("ok", false), result.toString())
    }

    private fun notify(requestId: String, p: JSONObject) {
        val title = p.optString("title", "Loona")
        val message = p.optString("message", title)
        LoonaAlerts.post(ctx, "reminder", "pal.notify." + System.currentTimeMillis(), title, message)
        resolve(requestId, true, JSONObject().put("notified", title).toString())
    }

    private fun callLogJson(): String {
        val arr = JSONArray()
        try {
            val c = ctx.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.CACHED_NAME,
                        android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.DATE),
                null, null, android.provider.CallLog.Calls.DATE + " DESC"
            )
            c?.use {
                var n = 0
                while (it.moveToNext() && n < 20) {
                    arr.put(JSONObject()
                        .put("number", it.getString(0) ?: "")
                        .put("name", it.getString(1) ?: "")
                        .put("type", it.getInt(2))
                        .put("at", it.getLong(3)))
                    n++
                }
            }
        } catch (e: SecurityException) {
            return JSONObject().put("error", "call log permission").toString()
        } catch (e: Exception) {
            return JSONObject().put("error", "call log unavailable").toString()
        }
        return JSONObject().put("calls", arr).toString()
    }

    private fun openSettings(requestId: String, p: JSONObject) {
        val panel = p.optString("panel", "wifi")
        // Some panels don't exist on every OEM build — try the best target
        // first and fall back rather than failing with nothing to offer.
        val actions: List<String> = when (panel) {
            "wifi" -> listOf(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> listOf(Settings.ACTION_BLUETOOTH_SETTINGS, "android.settings.BLUETOOTH_SETTINGS")
            "sound" -> listOf(Settings.ACTION_SOUND_SETTINGS)
            "display" -> listOf(Settings.ACTION_DISPLAY_SETTINGS)
            "battery" -> listOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_SETTINGS)
            "apps" -> listOf(Settings.ACTION_APPLICATION_SETTINGS)
            "tts" -> listOf(
                "com.android.settings.TTS_SETTINGS",
                TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA,
                Settings.ACTION_SETTINGS
            )
            "voiceinput" -> listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_SETTINGS)
            "language" -> listOf(Settings.ACTION_LOCALE_SETTINGS)
            else -> listOf(Settings.ACTION_SETTINGS)
        }
        for (action in actions) {
            try {
                ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.d(TAG, "[Settings] $panel → $action")
                resolve(requestId, true, JSONObject()
                    .put("panel", panel).put("action", action).toString())
                return
            } catch (e: Exception) {
                Log.w(TAG, "[Settings] $panel: $action unavailable")
            }
        }
        resolve(requestId, false, err("no settings screen for $panel"))
    }

    private fun batteryJson(): String {
        val intent = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManagerExtra_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManagerExtra_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent?.getIntExtra("status", -1) ?: -1
        val charging = status == 2 || status == 5
        return JSONObject().put("percent", pct).put("charging", charging).toString()
    }

    private val BatteryManagerExtra_LEVEL = "level"
    private val BatteryManagerExtra_SCALE = "scale"

    /** What the app still needs the user to switch on, from the phone itself. */
    private fun setupJson(): String {
        val a11yOn = try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            (android.provider.Settings.Secure.getInt(
                ctx.contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1) &&
                am.isEnabled &&
                (android.provider.Settings.Secure.getString(
                    ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                    .contains(ctx.packageName)
        } catch (e: Exception) { false }
        return JSONObject()
            .put("accessibility", a11yOn)
            .put("overlay", Settings.canDrawOverlays(ctx))
            .toString()
    }

    /** Open whichever phone setting is still missing (no computer needed). */
    private fun openSetupSettings(requestId: String) {
        try {
            val i = if (!Settings.canDrawOverlays(ctx)) {
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
            resolve(requestId, true, "{}")
        } catch (e: Exception) {
            resolve(requestId, false, err(e.message ?: "cannot open settings"))
        }
    }

    private fun statusJson(): String {
        // The page asks for status on every start: a good moment to unpack the
        // hotword model in the background instead of when music first plays.
        try { hotword.prepare() } catch (e: Exception) { /* engine logs it */ }
        val am = if (Build.VERSION.SDK_INT >= 31)
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() else true
        return JSONObject()
            .put("notifications", notificationsEnabled())
            .put("exactAlarms", am)
            .put("writeSettings", Settings.System.canWrite(ctx))
            .put("mic", ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            .put("camera", ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            .put("overlay", Settings.canDrawOverlays(ctx))
            .put("sdk", Build.VERSION.SDK_INT)
            .toString()
    }

    private fun vibrate(ms: Long) {
        val vm = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= 26) {
            vm.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vm.vibrate(ms)
        }
    }

    /** Called when a scheduled item fires while the app is alive. */
    fun postAlert(kind: String, id: String, title: String, message: String) {
        LoonaAlerts.post(ctx, kind, id, title, message)
        event("alert", JSONObject().put("kind", kind).put("title", title).put("id", id))
    }

    /** AlertReceiver already posted the notification; only notify the page. */
    fun emitAlert(kind: String, id: String, title: String) {
        event("alert", JSONObject().put("kind", kind).put("title", title).put("id", id))
    }
}

/** Persisted list of scheduled reminders/alarms/timers. */
/**
 * Streaming client for the Wyoming ASR server (whisper on the LAN).
 * Audio is pushed while the person is still talking, so the transcript comes
 * back right after they stop instead of after uploading the whole sentence.
 */
private class WyomingAsr(private val endpoint: String, langTag: String) {
    private val sock = Socket()
    private lateinit var out: java.io.OutputStream
    private lateinit var input: BufferedInputStream
    private val language = langTag.substringBefore('-').lowercase()

    fun open() {
        val uri = URI(endpoint)
        val host = uri.host ?: throw IllegalStateException("bad whisper url")
        val port = if (uri.port > 0) uri.port else 10300
        sock.connect(java.net.InetSocketAddress(host, port), 2500)
        sock.soTimeout = 8000      // a stuck server must not freeze the turn
        sock.tcpNoDelay = true
        out = sock.getOutputStream()
        input = BufferedInputStream(sock.getInputStream())
        send("{\"type\":\"transcribe\",\"data\":{\"language\":\"$language\"}}")
        send("{\"type\":\"audio-start\",\"data\":{\"rate\":16000,\"width\":2,\"channels\":1}}")
        Log.d("PAL9000", "[Whisper] streaming to $host:$port lang=$language")
    }

    private fun send(json: String) {
        out.write((json + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    fun write(pcm: ByteArray) {
        send("{\"type\":\"audio-chunk\",\"data\":{\"rate\":16000,\"width\":2,\"channels\":1},\"payload_length\":${pcm.size}}")
        out.write(pcm)
        out.flush()
    }

    /** Close the utterance and wait for the transcript. */
    fun finish(): String? {
        send("{\"type\":\"audio-stop\",\"data\":{}}")
        // CPU whisper can take many seconds to transcribe a full sentence; an
        // 8 s read deadline cut it off mid-flight ("Read timed out" → no
        // transcript → "I didn't hear that"). Bump the socket timeout and the
        // overall deadline so a slow turn still lands.
        try { sock.soTimeout = 30000 } catch (e: Exception) { /* ignore */ }
        val deadline = System.currentTimeMillis() + 28000
        while (System.currentTimeMillis() < deadline) {
            val line = readLine() ?: break
            if (line.isBlank()) continue
            val ev = try { JSONObject(line) } catch (e: Exception) { continue }
            val dataLen = ev.optInt("data_length", 0)
            val payload = if (dataLen > 0) readN(dataLen) else null
            when (ev.optString("type")) {
                "transcript" -> {
                    val text = payload?.let { String(it, Charsets.UTF_8) }
                        ?.let { try { JSONObject(it).optString("text") } catch (e: Exception) { it } }
                    return text?.trim()
                }
                "error" -> {
                    Log.w("PAL9000", "[Whisper] server error: " + (payload?.let { String(it, Charsets.UTF_8) } ?: "?"))
                    return null
                }
            }
        }
        Log.w("PAL9000", "[Whisper] no transcript in time")
        return null
    }

    fun close() {
        try { sock.close() } catch (e: Exception) { /* ignore */ }
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    private fun readN(n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) b else b.copyOf(off)
    }
}

object ScheduleStore {
    private const val STORE = "pal_schedule"

    fun load(ctx: Context): JSONArray {
        val raw = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString("items", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    fun save(ctx: Context, arr: JSONArray) {
        ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
    }

    fun remove(ctx: Context, id: String) {
        val arr = load(ctx)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") != id) out.put(o)
        }
        save(ctx, out)
    }
}

/**
 * Posts the notification for a fired reminder/alarm/timer. Works even when
 * the app process was killed (AlertReceiver runs in a fresh process).
 */
object LoonaAlerts {
    private const val TAG = "PAL9000"
    private const val CHANNEL_REMINDERS = "loona_reminders"
    private const val CHANNEL_ALARMS = "loona_alarms"

    fun post(ctx: Context, kind: String, id: String, title: String, message: String) {
        ensureChannels(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = if (kind == "alarm") CHANNEL_ALARMS else CHANNEL_REMINDERS
        val sound = RingtoneManager.getDefaultUri(
            if (kind == "alarm") RingtoneManager.TYPE_NOTIFICATION else RingtoneManager.TYPE_NOTIFICATION)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(ctx, channel)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(ctx)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (title.isBlank()) "Loona" else title)
            .setContentText(message)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 350, 250, 350))
            .setSound(sound)
            .build()
        try {
            val enabled = Build.VERSION.SDK_INT < 33 ||
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (enabled) nm.notify(id.hashCode(), notification)
            else Log.w(TAG, "[Alert] notifications disabled — $title")
        } catch (e: Exception) {
            Log.e(TAG, "[Alert] notify failed: $e")
        }
        ScheduleStore.remove(ctx, id)
        Log.d(TAG, "[Alert] posted $kind: $title")
    }

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_REMINDERS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Loona reminders"
                    enableVibration(true)
                })
        }
        if (nm.getNotificationChannel(CHANNEL_ALARMS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALARMS, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Loona alarms"
                    enableVibration(true)
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), null)
                })
        }
    }
}

/** Fires when a reminder/alarm/timer comes due (app may be dead). */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(PalBridge.EXTRA_ID) ?: return
        val kind = intent.getStringExtra(PalBridge.EXTRA_KIND) ?: "reminder"
        val title = intent.getStringExtra(PalBridge.EXTRA_TITLE) ?: "Loona"
        val message = intent.getStringExtra(PalBridge.EXTRA_TITLE + "_msg") ?: title
        Log.d("PAL9000", "[Alert] fired $kind: $title")
        LoonaAlerts.post(context, kind, id, title, message)
        // If the app is alive, let the face react too.
        (context.applicationContext as? PalApp)?.bridge?.emitAlert(kind, id, title)
    }
}

/** Holds the bridge so AlertReceiver can reach a live page. */
/** Kiosk: bring Loona back up on its own after a reboot or an update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return
        try {
            // Start the UI.
            val i = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            // Keep the wake-word loop and the LAN panel alive across reboots.
            com.pal9000.robot.BrainService.ensureRunning(context)
            com.pal9000.robot.PanelService.ensureRunning(context)
            Log.d("PAL9000", "[Boot] Loona started ($action)")
        } catch (e: Exception) {
            Log.w("PAL9000", "[Boot] cannot start: ${e.message}")
        }
    }
}

class PalApp : android.app.Application() {
    var bridge: PalBridge? = null

    // The web panel lives with the process, not with the activity: keeping
    // the activity's onDestroy from killing it means the panel stays up while
    // Loona is in the background (it just shows cached settings).
    var server: PalServer? = null

    fun startServer(bridge: PalBridge): PalServer = synchronized(this) {
        val existing = server
        if (existing != null) {
            existing.bridge = bridge
            return existing
        }
        val srv = PalServer(this, bridge)
        srv.start()
        server = srv
        srv
    }
}
