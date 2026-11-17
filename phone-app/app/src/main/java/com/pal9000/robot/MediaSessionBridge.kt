package com.pal9000.robot

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log

class MediaSessionBridge(private val ctx: Context) {

    private val TAG = "PAL9000"
    private val preferredPackages = listOf("com.spotify.music", "com.google.android.apps.youtube.music")

    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    fun play(): Boolean {
        keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
        return true
    }

    fun pause(): Boolean {
        keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        return true
    }

    fun nextTrack(): Boolean {
        keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        return true
    }

    fun previousTrack(): Boolean {
        keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return true
    }

    fun stop(): Boolean {
        keyEvent(android.view.KeyEvent.KEYCODE_MEDIA_STOP)
        return true
    }

    fun volume(percent: Int): Boolean {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (percent * max / 100).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        Log.d(TAG, "[MediaSession] volume $percent% -> $level/$max")
        return true
    }

    fun status(): Map<String, Any?> {
        return mapOf("state" to "unknown", "app" to null)
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
