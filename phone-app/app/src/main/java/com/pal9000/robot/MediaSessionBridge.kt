package com.pal9000.robot

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.MediaSessionManager
import android.media.MediaController
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Bridges media commands (play/pause/next/prev/stop/volume/status) to active
 * media sessions on the device — Spotify, YouTube Music, or any app exposing a
 * MediaSession.
 *
 * Prefers known packages (Spotify, YouTube Music), then falls back to any active
 * session. When no session is available, falls back to AudioManager media key
 * events — the same behaviour Loona already had.
 *
 * Media session discovery requires API 28+; on older devices every command falls
 * back to key events, so there is no regression.
 */
class MediaSessionBridge(private val ctx: Context) {

    private const val TAG = "PAL9000"
    private val preferredPackages = listOf("com.spotify.music", "com.google.android.apps.youtube.music")

    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mediaSessionManager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private var currentController: MediaController? = null
    private var currentPackage: String? = null

    /** True when full media-session APIs are available (API 28+). */
    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** Connect to the best available media session and return its package name,
     * or null if none is available. Called before each command so the target
     * session is always current. */
    fun connectToBestSession(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null

        val sessions: List<MediaSessionManager.RemoteSessionInfo>
        try {
            sessions = mediaSessionManager.activeSessions
        } catch (e: Exception) {
            Log.w(TAG, "[MediaSession] cannot list active sessions: ${e.message}")
            return null
        }

        if (sessions.isEmpty()) {
            disconnect()
            return null
        }

        // Priority: 1) last-interacted (current controller's package),
        //           2) preferred packages (Spotify, YouTube Music),
        //           3) any active session
        var target: MediaSessionManager.RemoteSessionInfo? = null

        currentPackage?.let { pkg ->
            target = sessions.firstOrNull { it.packageName == pkg }
        }

        if (target == null) {
            for (pkg in preferredPackages) {
                target = sessions.firstOrNull { it.packageName == pkg }
                if (target != null) break
            }
        }

        if (target == null) {
            target = sessions.firstOrNull()
        }

        target?.let { session ->
            connectToSession(session)
            return session.packageName
        }

        disconnect()
        return null
    }

    @SuppressLint("MissingPermission")
    private fun connectToSession(session: MediaSessionManager.RemoteSessionInfo) {
        disconnect()
        try {
            val controller = MediaController(ctx, session.token)
            currentController = controller
            currentPackage = session.packageName
            Log.d(TAG, "[MediaSession] connected to ${session.packageName}")
        } catch (e: Exception) {
            Log.w(TAG, "[MediaSession] connect failed: ${e.message}")
            disconnect()
        }
    }

    fun disconnect() {
        currentController = null
        currentPackage = null
    }

    fun play(): Boolean {
        connectToBestSession()
        return currentController?.transportControls?.let { it.play(); true } ?: run { keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PLAY); false }
    }

    fun pause(): Boolean {
        connectToBestSession()
        return currentController?.transportControls?.let { it.pause(); true } ?: run { keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); false }
    }

    fun next(): Boolean {
        connectToBestSession()
        return currentController?.transportControls?.let { it.next(); true } ?: run { keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_NEXT); false }
    }

    fun previous(): Boolean {
        connectToBestSession()
        return currentController?.transportControls?.let { it.previous(); true } ?: run { keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); false }
    }

    fun stop(): Boolean {
        connectToBestSession()
        return currentController?.transportControls?.let { it.stop(); true } ?: run { keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_STOP); false }
    }

    /** Set system media volume (0-100) via AudioManager. Returns true. */
    fun volume(percent: Int): Boolean {
        val max = audioManager.streamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (percent * max / 100).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        Log.d(TAG, "[MediaSession] volume $percent% -> $level/$max")
        return true
    }

    /** Current playback status. Connects to the best session first. */
    fun status(): Map<String, Any?> {
        connectToBestSession()
        val controller = currentController
        if (controller == null) {
            return mapOf("state" to "none", "app" to currentPackage)
        }
        val pb = controller.playbackState
        val state = when (pb?.state) {
            MediaController.PlaybackState.STATE_PLAYING -> "playing"
            MediaController.PlaybackState.STATE_PAUSED -> "paused"
            MediaController.PlaybackState.STATE_STOPPED -> "stopped"
            MediaController.PlaybackState.STATE_BUFFERING -> "playing"
            else -> "none"
        }
        return mapOf(
            "state" to state,
            "app" to currentPackage,
            "track" to pb?.extras?.getString("android.media.extra.TITLE"),
            "artist" to pb?.extras?.getString("android.media.extra.ARTIST")
        )
    }

    private fun keyEvent(keyCode: Int) {
        try {
            audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        } catch (e: Exception) {
            Log.w(TAG, "[MediaSession] key event failed: ${e.message}")
        }
    }
}
