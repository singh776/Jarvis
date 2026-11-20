package com.mandeep.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps Gemini Live's mic allowed while you're in another app (WhatsApp, YouTube…). Shows a small notification. */
class LiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Singh AI Live", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(R.drawable.ic_mono)
            .setContentTitle("Singh AI Live")
            .setContentText("Talking with you — tap to open")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(7, n)
        } catch (e: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "live"
        @Volatile var active = false
            private set

        fun start(c: Context) {
            active = true
            try {
                val i = Intent(c, LiveService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) {}
        }

        fun stop(c: Context) {
            active = false
            try { c.stopService(Intent(c, LiveService::class.java)) } catch (e: Exception) {}
        }
    }
}
