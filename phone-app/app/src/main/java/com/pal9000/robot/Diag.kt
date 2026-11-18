package com.pal9000.robot

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostics ring buffer.
 *
 * Everything important the assistant does is recorded here so it can be read
 * (and copied) from the phone itself — no computer, no adb, no logcat.
 */
object Diag {
    private const val MAX = 250
    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(msg: String) {
        synchronized(lines) {
            if (lines.size >= MAX) lines.removeFirst()
            lines.add(stamp.format(Date()) + "  " + msg)
        }
    }

    fun dump(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
