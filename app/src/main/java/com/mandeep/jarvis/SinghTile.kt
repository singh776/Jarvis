package com.mandeep.jarvis

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * "Singh AI" button in the Quick Settings panel (swipe down from the top).
 * Tap it → the talk popup opens. The mic is only on after you tap — nothing listens in the background.
 */
class SinghTile : TileService() {
    override fun onClick() {
        super.onClick()
        val i = Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        } catch (e: Exception) {
            try { startActivity(i) } catch (e2: Exception) {}
        }
    }
}
