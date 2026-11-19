package com.mandeep.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Opened by the side key (Digital assistant app). Instead of opening the whole app it shows the
 * small Singh AI popup at the bottom of the screen and starts listening, then closes itself.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (JarvisAccess.instance != null && micOk && Gemini.apiKey(this).isNotEmpty()) {
            WakeService.startSession(this)
        } else {
            // popup needs Screen Control + mic → open the full app instead
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_ASSIST)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
