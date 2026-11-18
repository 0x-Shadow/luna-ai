package com.pal9000.robot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Keeps the web panel alive while Loona is in the background.
 *
 * Without a foreground service Android freezes the app, and a frozen app still
 * holds its listening socket — connections arrive but nothing ever answers,
 * which looks exactly like "the page won't load".
 *
 * The server is started on a background thread so onCreate()/onStartCommand()
 * never block the main thread (which caused ANRs on Android 14+).
 */
class PanelService : Service() {

    companion object {
        private const val TAG = "PAL9000"
        private const val CHANNEL = "loona_panel"
        private const val NOTIF_ID = 4711

        fun ensureRunning(ctx: Context) {
            val intent = Intent(ctx, PanelService::class.java)
            try {
                ContextCompat.startForegroundService(ctx, intent)
            } catch (e: Exception) {
                Log.w(TAG, "[Panel] cannot start service: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Post the notification FIRST so the foreground service is valid
        // immediately, then start the server on a background thread.
        startForeground(NOTIF_ID, buildNotification(null))
        thread(name = "pal-panel-start", isDaemon = true) {
            try { attachServer() } catch (e: Exception) {
                Log.w(TAG, "[Panel] attachServer failed: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY can revive this service with no Activity around and
        // app.bridge still null — attachServer() used to bail silently and
        // nothing ever retried, leaving the LAN panel permanently dead after
        // a process kill. Retry until the bridge shows up.
        attachWithRetry(0)
        return START_STICKY
    }

    private fun attachWithRetry(attempt: Int) {
        val app = application as? PalApp ?: return
        if (app.bridge != null) { attachServer(); return }
        if (attempt >= 30) {
            Log.w(TAG, "[Panel] bridge never came back — panel stays down")
            return
        }
        android.os.Handler(mainLooper).postDelayed({ attachWithRetry(attempt + 1) }, 2000)
    }

    private fun attachServer() {
        val app = application as? PalApp ?: return
        val bridge = app.bridge ?: return
        val srv = app.startServer(bridge)
        bridge.panelUrl = srv.url()
        if (srv.port > 0) {
            val mgr = getSystemService(NotificationManager::class.java)
            // Show the tokenized link so the token can be recovered from the
            // notification if it's ever lost (panel otherwise shows "offline").
            mgr?.notify(NOTIF_ID, buildNotification(srv.tokenizedUrl()))
        }
        Log.d(TAG, "[Panel] service attached, url=${srv.tokenizedUrl()}")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Loona panel", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Keeps the web control panel reachable"
        ch.setShowBadge(false)
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(url: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("Loona panel is running")
            .setContentText(url ?: "starting…")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, "[Panel] service destroyed")
        super.onDestroy()
    }
}
