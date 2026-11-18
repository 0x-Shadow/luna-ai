package com.pal9000.robot

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import org.json.JSONArray
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * On-device wake-word detector.
 *
 * Why this exists: Google's SpeechRecognizer asks for AUDIOFOCUS_GAIN every time
 * it starts, which pauses whatever is playing. That made the always-on wake loop
 * stop the music every few seconds, and a loudness gate could not tell a voice
 * from the song or the TV.
 *
 * Vosk runs a tiny grammar ("luna", "hey luna") against the microphone and never
 * touches audio focus, so music keeps playing. Only a real match wakes the
 * recognizer for an actual command.
 */
class HotwordEngine(private val ctx: Context, private val onWake: () -> Unit) {

    private var model: Model? = null
    private var loading = false
    private var loadFailed = false

    @Volatile private var running = false
    private var thread: Thread? = null

    /** Grammar-constrained to the wake words — everything else is ignored. */
    private val grammar = JSONArray(
        listOf("luna", "hey luna", "loona", "hey loona", "luna luna", "luna, luna")
    ).toString()

    fun prepare() {
        synchronized(this) {
            if (model != null || loading || loadFailed) return
            loading = true
        }
        Log.d(TAG, "[Hotword] loading model")
        StorageService.unpack(
            ctx, "model-hotword", "hotword-model",
            { m ->
                synchronized(this) { model = m; loading = false }
                Log.d(TAG, "[Hotword] model ready")
                Diag.add("hotword model loaded")
            },
            { e ->
                synchronized(this) { loading = false; loadFailed = true }
                Log.w(TAG, "[Hotword] model failed: ${e.message}")
                Diag.add("hotword model FAILED: ${e.message}")
            }
        )
    }

    fun start() {
        if (running) return
        prepare()
        running = true
        thread = Thread {
            var rec: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            var recognizer: Recognizer? = null
            try {
                // Wait for the model without blocking the caller.
                var waited = 0
                while (model == null && !loadFailed && waited < 15000) {
                    Thread.sleep(200); waited += 200
                }
                val m = model ?: return@Thread
                recognizer = Recognizer(m, 16000.0f, grammar)
                val rate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                // VOICE_COMMUNICATION + echo canceller strips the phone's own
                // speaker output, so the music does not drown the wake word.
                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, 8192))
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "[Hotword] mic unavailable")
                    Diag.add("hotword mic unavailable")
                    return@Thread
                }
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        aec = AcousticEchoCanceler.create(rec.audioSessionId)
                        aec?.enabled = true
                    }
                } catch (e: Exception) { /* keep going without AEC */ }
                rec.startRecording()
                Log.d(TAG, "[Hotword] listening for the wake word")
                Diag.add("wake word listening (no audio focus)")
                val buf = ShortArray(1600)   // ~100 ms at 16 kHz
                var lastFireAt = 0L
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    // Energy gate: only feed Vosk when something loud is at the
                    // mic. Music tail / silence never produces phantom wake words.
                    var sumSq = 0L
                    for (i in 0 until n) { val v = buf[i].toInt(); sumSq += v.toLong() * v }
                    val rms = Math.sqrt(sumSq.toDouble() / n)
                    if (rms < 600) continue
                    val hit = try {
                        recognizer.acceptWaveForm(buf, n) &&
                            recognizer.result.lowercase().let { r ->
                                r.contains("luna") || r.contains("loona")
                            }
                    } catch (e: Exception) { false }
                    if (hit) {
                        val now = System.currentTimeMillis()
                        // Cooldown: a single utterance must not fire twice, and a
                        // song that happens to contain "luna" must not re-trigger.
                        if (now - lastFireAt < 4000) {
                            try { recognizer.reset() } catch (e: Exception) { }
                            continue
                        }
                        lastFireAt = now
                        Log.d(TAG, "[Hotword] wake word heard (rms=${rms.toInt()})")
                        Diag.add("WAKE WORD heard")
                        try { recognizer.reset() } catch (e: Exception) { }
                        onWake()
                        // The caller stops us; keep the loop from firing twice.
                        Thread.sleep(400)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[Hotword] ${e.message}")
            } finally {
                running = false
                try { aec?.release() } catch (e: Exception) { }
                try { rec?.stop() } catch (e: Exception) { }
                try { rec?.release() } catch (e: Exception) { }
                try { recognizer?.close() } catch (e: Exception) { }
                Log.d(TAG, "[Hotword] stopped")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        if (!running) return
        running = false
        // Never join ourselves — onWake() is called from this thread.
        if (Thread.currentThread() === thread) return
        // The read() call returns within ~100 ms and the thread exits.
        try { thread?.join(600) } catch (e: Exception) { }
        thread = null
    }

    companion object {
        private const val TAG = "PAL9000"
    }
}
