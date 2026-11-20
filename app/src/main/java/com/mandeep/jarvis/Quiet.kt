package com.mandeep.jarvis

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Stops the "ting-tong" beep the phone's speech engine plays every time the mic starts/stops:
 * briefly mutes the system / notification sounds (and media only when nothing is playing).
 */
object Quiet {
    private val main = Handler(Looper.getMainLooper())
    private var count = 0
    private val muted = HashSet<Int>()

    fun beep(c: Context, ms: Long = 700) {
        val am = c.getSystemService(AudioManager::class.java) ?: return
        val streams = ArrayList(listOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION))
        if (!am.isMusicActive && !HdVoice.speaking) streams.add(AudioManager.STREAM_MUSIC)
        main.post {
            for (s in streams) {
                if (s in muted) continue
                try {
                    if (!am.isStreamMute(s)) { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0); muted.add(s) }
                } catch (e: Exception) {}
            }
            count++
            main.postDelayed({
                count--
                if (count <= 0) {
                    count = 0
                    for (s in muted) try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) {}
                    muted.clear()
                }
            }, ms)
        }
    }
}
