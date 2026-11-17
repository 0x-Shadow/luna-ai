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
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
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
    private var lang = "en-US"               // BCP-47 tag for STT + TTS
    private var onDeviceOnly = true          // keep audio on the phone
    private var recognizerOnDevice = false

    // Whisper on the LAN (Wyoming protocol — the TrueNAS/Home-Assistant
    // whisper add-on). Audio goes nowhere else; empty url disables it.
    private var whisperUrl = ""
    private var whisperOn = true
    // Configurable Whisper endpointing (ms), set via the `voice` bridge method.
    private var whisperNoSpeechMs = 2000L
    private var whisperEndMs = 450L
    private var capturing = false
    @Volatile private var recordRef: AudioRecord? = null
    private var captureThread: Thread? = null
    // Bumped on every listen/stop so a queued auto-restart can't resurrect a
    // session that was already replaced (that race killed the wake loop).
    private var listenToken = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsQueue: MutableList<Pair<String, String>> = mutableListOf()

    /** Media session bridge — Spotify, YouTube Music, or any active session;
     * falls back to media key events when no session is available. */
    private val mediaSessionBridge = MediaSessionBridge(ctx)

    /** App-control automation layer: launch, tap, type, scroll, multi-step tasks. */
    private val automationBridge = AutomationBridge(ctx)

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
                "apps" -> resolve(requestId, true, appsJson())
                "openApp" -> openApp(requestId, payload)
                "settings" -> openSettings(requestId, payload)
                "dial" -> dial(requestId, payload)
                "url" -> openUrl(requestId, payload)
                "search" -> search(requestId, payload)
                "youtube" -> youtube(requestId, payload)
                "navigate" -> navigate(requestId, payload)
                "media" -> media(requestId, payload)
                "automation" -> automation(requestId, payload)
                "notify" -> notify(requestId, payload)
                "callLog" -> resolve(requestId, true, callLogJson())
                "status" -> resolve(requestId, true, statusJson())
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
        emit("window.__palResult && window.__palResult($requestId, ${if (ok) "true" else "false"}, ${jsonForJs(json)});")
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

    /* ═══════════ HTTP (CORS-free Hermes proxy) ═══════════ */

    private fun http(requestId: String, p: JSONObject) {
        io.execute {
            val url = p.optString("url")
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
                    var line = r.readLine()
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
                if (mode == "whisper" && whisperOn) {
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

    private fun ensureRecognizer() {
        // Android 13+ can run recognition entirely on the device: audio never
        // leaves the phone. Fall back to the network recognizer when there is
        // no on-device model for the chosen language.
        val wantOnDevice = onDeviceOnly && sttOnDeviceAvailable()
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
        if (p.has("lang")) lang = p.optString("lang", lang).ifBlank { "en-US" }
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
                if (prevMode != "off") { listenMode = prevMode; ensureRecognizer() }
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
            ensureRecognizer()
            val intent = buildRecognizerIntent()
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

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDeviceOnly)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // Short Greek wake words need a tight end-of-speech window, or the
            // recognizer waits ~1 s after the word and often drops it.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

    private var lastLevelAt = 0L

    private val recognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            clientErrors = 0
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
            Log.d(TAG, "[STT] error $error (mode=$listenMode, lang=$lang, onDevice=$recognizerOnDevice)")
            if (error == SpeechRecognizer.ERROR_CLIENT) clientErrors++
            // 12/13 = language model not installed on-device — drop to network
            if ((error == 12 || error == 13) && recognizerOnDevice) {
                Log.w(TAG, "[STT] no on-device model for $lang — using network recognizer")
                onDeviceOnly = false
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
            // 6 client, 7 no-recognition, 1 network, 2 audio busy → keep the loop alive
            speechEvent("error", null, sttError(error), error)
            if (listenMode == "loop" || listenMode == "conversation") {
                val wait = if (error == 7 || error == 6) 300
                    else (500 + clientErrors * 500).coerceAtMost(3000)
                scheduleRestart(wait.toLong())
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

    private fun deliver(results: Bundle?, final: Boolean) {
        if (listenMode == "whisper") return      // whisper owns this turn
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val text = list.firstOrNull()?.trim() ?: return
        if (text.isEmpty()) return
        if (final) Log.d(TAG, "[STT] final: $text")
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

    private fun scheduleRestart(delayMs: Long) {
        val token = listenToken
        mainHandler.postDelayed({
            if (listenMode == "off" || token != listenToken) return@postDelayed
            // A recognizer that has been cancelled and restarted in quick
            // succession wedges itself (ERROR_CLIENT forever). Build a fresh
            // one instead of hammering startListening().
            if (clientErrors >= 2) {
                clientErrors = 0
                try { speech?.cancel() } catch (e: Exception) { }
                try { speech?.destroy() } catch (e: Exception) { }
                speech = null
                ensureRecognizer()
            }
            startListening()
        }, delayMs)
    }

    private fun teardownSpeech() {
        listenMode = "off"
        listenToken++
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

    private fun speak(requestId: String, p: JSONObject) {
        val text = p.optString("text", "").trim()
        if (text.isEmpty()) { resolve(requestId, true, "{}"); return }
        if (p.has("rate")) speechRate = p.optDouble("rate", speechRate.toDouble()).toFloat().coerceIn(0.5f, 2.0f)
        if (p.has("pitch")) speechPitch = p.optDouble("pitch", speechPitch.toDouble()).toFloat().coerceIn(0.5f, 2.0f)
        val queue = p.optString("queue", "flush") == "add"
        mainHandler.post { ensureTts(requestId, text, queue) }
    }

    var speechRate = 1.0f
    var speechPitch = 1.0f

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
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
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
        } else {
            // still initialising: retry shortly
            mainHandler.postDelayed({
                val queued = ttsQueue.toList(); ttsQueue.clear()
                queued.forEach { (req, txt) ->
                    if (ttsReady) doSpeak(req, txt) else ensureTts(req, txt)
                }
            }, 400)
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
        }
    }

    private val uttListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            event("tts", JSONObject().put("type", "start"))
        }
        override fun onDone(utteranceId: String?) {
            event("tts", JSONObject().put("type", "done"))
            utteranceIds.remove(utteranceId)?.let { resolve(it, true, "{}") }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            event("tts", JSONObject().put("type", "error"))
            utteranceIds.remove(utteranceId)?.let { resolve(it, false, err("tts error")) }
        }
    }

    private fun stopSpeak() {
        mainHandler.post { tts?.stop() }
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
        val raw = p.optString("query", "").lowercase().trim()
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
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
        if (launch == null) { resolve(requestId, false, err("cannot launch")); return }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ctx.startActivity(launch)
        Log.d(TAG, "[App] opened ${match.loadLabel(pm)} (${match.activityInfo.packageName})")
        resolve(requestId, true, JSONObject().put("label", match.loadLabel(pm).toString()).toString())
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
        val uri = if (q.isEmpty())
            Uri.parse("https://www.youtube.com")
        else
            Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
        launch(requestId, Intent(Intent.ACTION_VIEW, uri), "youtube $q")
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

    private fun media(requestId: String, p: JSONObject) {
        val op = p.optString("op", "play")
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
        if (result) {
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

    /** App-control automation entry point: {method, payload} with methods
     * listApps, launchApp, snapshot, tapLabel, scroll, typeText, pressBack,
     * runTask, cancelTask. */
    private fun automation(requestId: String, p: JSONObject) {
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
                    (r["elements"] as? List<Map<String, Any?>> ?: emptyList()).forEach { arr.put(JSONObject(it.toString())) }
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
                                "target" to o.optString("target", null),
                                "text" to o.optString("text", null)
                            )
                        }
                    } else emptyList<Map<String, String?>>()
                    val r: Map<String, Any?> = automationBridge.runTask(taskId, steps)
                    val results = (r["results"] as? List<Map<String, Any?>> ?: emptyList())
                    val resultsArr = JSONArray()
                    results.forEach { resultsArr.put(JSONObject(it.toString())) }
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

    private fun statusJson(): String {
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
        val deadline = System.currentTimeMillis() + 8000
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
            val i = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
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

    fun startServer(bridge: PalBridge): PalServer {
        val existing = server
        if (existing != null) {
            existing.bridge = bridge
            return existing
        }
        val srv = PalServer(this, bridge)
        srv.start()
        server = srv
        return srv
    }
}
