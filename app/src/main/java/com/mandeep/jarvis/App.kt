package com.mandeep.jarvis

import android.app.Application
import java.io.File

/** Saves any crash to crash.txt so Jarvis can show it on the next launch. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText(
                    "Singh AI ${BuildInfo.version(this)}  •  Android ${android.os.Build.VERSION.RELEASE}\n\n" +
                        android.util.Log.getStackTraceString(e)
                )
            } catch (x: Exception) {}
            old?.uncaughtException(t, e)
        }
    }
}

object BuildInfo {
    fun version(app: Application): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }
}
