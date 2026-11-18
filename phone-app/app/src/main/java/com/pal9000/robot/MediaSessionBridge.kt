package com.pal9000.robot

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class MediaSessionBridge(private val ctx: Context) {

    private val TAG = "PAL9000"
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = ConcurrentHashMap<String, MediaSession>()

    private val preferredPackages = listOf("com.spotify.music", "com.google.android.apps.youtube.music")

    fun isAvailable(): Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P

    /** System volume via AudioManager (the hardware volume keys and the media stream). */
    fun volume(percent: Int): Boolean {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (percent * max / 100).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_PLAY_SOUND)
        Log.d(TAG, "[MediaSession] volume $percent% -> $level/$max")
        return true
    }

    fun volumeUp(): Boolean {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_PLAY_SOUND)
        return true
    }

    fun volumeDown(): Boolean {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_PLAY_SOUND)
        return true
    }

    /** Send a media key to whichever app holds the active media session (Spotify, YouTube Music…). */
    private fun keyEvent(keyCode: Int) {
        try {
            audioManager.dispatchMediaKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        } catch (e: Exception) {
            Log.w(TAG, "[MediaSession] key event failed: ${e.message}")
        }
    }

    /**
     * The active media session, reached through our accessibility service.
     *
     * dispatchMediaKeyEvent() needs MEDIA_CONTENT_CONTROL, which a normal app
     * does not have — it silently did nothing, so "resume music" kept failing.
     * An enabled accessibility service is allowed to read active sessions and
     * drive their transport controls directly.
     */
    @SuppressLint("NewApi")
    private fun activeController(): android.media.session.MediaController? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = android.content.ComponentName(ctx, LoonaAccessibilityService::class.java)
            val sessions = msm.getActiveSessions(comp)
            sessions
                .filter { it.packageName != ctx.packageName }
                .sortedByDescending { s ->
                    when (s.playbackState?.state) {
                        PlaybackState.STATE_PLAYING -> 3
                        PlaybackState.STATE_PAUSED -> 2
                        PlaybackState.STATE_BUFFERING -> 1
                        else -> 0
                    }
                }
                .firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "[MediaSession] no session access: ${e.message}")
            null
        }
    }

    fun play(): Boolean {
        val c = activeController()
        if (c != null) {
            try { c.transportControls.play(); return true } catch (e: Exception) { /* fall through */ }
        }
        keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
        return true
    }

    fun pause(): Boolean {
        val c = activeController()
        if (c != null) {
            try { c.transportControls.pause(); return true } catch (e: Exception) { /* fall through */ }
        }
        keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        return true
    }

    fun nextTrack(): Boolean {
        activeController()?.let { try { it.transportControls.skipToNext(); return true } catch (e: Exception) { } }
        keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        return true
    }

    fun previousTrack(): Boolean {
        activeController()?.let { try { it.transportControls.skipToPrevious(); return true } catch (e: Exception) { } }
        keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return true
    }

    fun stop(): Boolean {
        keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_STOP)
        return true
    }

    /** Hold real MediaSession handles so play/pause/etc. can address a named session safely. */
    fun openSession(tag: String, callback: android.media.session.MediaSession.Callback?) {
        try {
            lock.remove(tag)?.release()
            val session = MediaSession(ctx, tag)
            session.setCallback(callback ?: object : MediaSession.Callback() {})
            session.isActive = true
            lock[tag] = session
            Log.d(TAG, "[MediaSession] opened $tag")
        } catch (e: Exception) {
            Log.w(TAG, "[MediaSession] open $tag failed: ${e.message}")
        }
    }

    fun closeSession(tag: String) {
        lock.remove(tag)?.let { try { it.release() } catch (e: Exception) { /* ignore */ } }
        Log.d(TAG, "[MediaSession] closed $tag")
    }

    /** List apps that currently hold an active media session (best effort). */
    fun activeSessions(): List<String> {
        return try {
            val mgr = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = mgr.getActiveSessions(null)
            controllers.mapNotNull { c ->
                c.packageName
            }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Best-effort playback state of the most recent active session. */
    fun status(): Map<String, Any?> {
        return try {
            val mgr = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = mgr.getActiveSessions(null)
            var best: PlaybackState? = null
            var bestTime = 0L
            for (c in controllers) {
                c.playbackState?.let { ps ->
                    if (ps.lastPositionUpdateTime > bestTime) {
                        bestTime = ps.lastPositionUpdateTime
                        best = ps
                    }
                }
            }
            val state = when (best?.state) {
                PlaybackState.STATE_PLAYING -> "playing"
                PlaybackState.STATE_PAUSED -> "paused"
                PlaybackState.STATE_STOPPED -> "stopped"
                else -> "unknown"
            }
            val appName = best?.let {
                controllers.firstOrNull { it.playbackState === best }?.packageName
            }
            mapOf("state" to state, "app" to appName)
        } catch (e: Exception) {
            mapOf("state" to "unknown", "app" to null)
        }
    }

    /** Release everything when Loona goes away. */
    fun releaseAll() {
        lock.forEach { (_, s) -> try { s.release() } catch (e: Exception) { /* ignore */ } }
        lock.clear()
    }
}
