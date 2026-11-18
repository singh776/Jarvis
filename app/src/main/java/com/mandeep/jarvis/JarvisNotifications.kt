package com.mandeep.jarvis

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lets Jarvis read your recent notifications ("Mere messages padh ke suna"). */
class JarvisNotifications : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    companion object {
        @Volatile private var instance: JarvisNotifications? = null

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, JarvisNotifications::class.java).flattenToString()
            return flat.split(":").any { it == me }
        }

        /** Up to 20 current notifications as plain text lines. */
        fun recentText(): String {
            val svc = instance ?: return ""
            val list: Array<StatusBarNotification> = try { svc.activeNotifications ?: emptyArray() } catch (e: Exception) { emptyArray() }
            val pm = svc.packageManager
            val fmt = SimpleDateFormat("h:mm a", Locale.ENGLISH)
            return list
                .filter { it.packageName != svc.packageName && !it.isOngoing }
                .sortedByDescending { it.postTime }
                .take(20)
                .mapNotNull { sbn ->
                    val ex = sbn.notification.extras
                    val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                    val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
                        ?.toString().orEmpty()
                    if (title.isBlank() && text.isBlank()) return@mapNotNull null
                    val app = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                    } catch (e: Exception) { sbn.packageName }
                    "${fmt.format(Date(sbn.postTime))} | $app | $title: ${text.take(200)}"
                }
                .joinToString("\n")
        }
    }
}
