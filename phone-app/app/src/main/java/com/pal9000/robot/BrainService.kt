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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BrainService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(NOTIF_ID)
        super.onDestroy()
    }

    private fun startForeground() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(NotificationChannel(CHANNEL, "Loona brain", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps Loona's wake-word loop alive in the background"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Loona brain is running")
            .setContentText("Wake-word detection active in background")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setOngoing(true)
            .setContentIntent(open)
        val notif = builder.build()
        startForeground(NOTIF_ID, notif)
        Log.d(TAG, "[BrainService] foreground started")
    }

    fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // No timeout: a 10-minute cap silently expired while the field stayed
        // non-null, so the lock was never re-acquired and Doze stalled the
        // wake loop ~10 min after screen-off. Re-check held on every start.
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PAL9000:BrainService:wake")
        }
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire()
            Log.d(TAG, "[BrainService] wake lock acquired")
        }
    }

    fun releaseWakeLock() {
        try { wakeLock?.release() } catch (e: Exception) { /* ignore */ }
        wakeLock = null
    }

    companion object {
        private const val TAG = "PAL9000"
        private const val CHANNEL = "loona_brain"
        private const val NOTIF_ID = 4712

        fun ensureRunning(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, BrainService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "[BrainService] cannot start: ${e.message}")
            }
        }
    }
}
