package com.mandeep.jarvis

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Car helpers for when the phone is connected to Android Auto (the phone is then in "car mode"):
 * - uses the truck's microphone (over Bluetooth hands-free) while Singh AI listens
 * - plays Singh AI's voice on the truck speakers (like Maps directions — music goes quieter)
 * - shows Singh AI's answer on the car screen
 */
object CarAudio {

    @Volatile private var scoOn = false
    /** If the car mic ever fails, use the phone mic for the rest of this drive. */
    @Volatile private var carMicBroken = false

    fun inCar(c: Context): Boolean = try {
        c.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR
    } catch (e: Exception) { false }

    private fun carMicWanted(c: Context): Boolean =
        inCar(c) && !carMicBroken &&
            c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("car_mic", true)

    /** Switches to the truck mic. Returns true if it did (then wait ~0.7 s before listening). */
    fun micOn(c: Context): Boolean {
        if (!inCar(c)) carMicBroken = false // new drive → try the car mic again
        if (scoOn || !carMicWanted(c)) return false
        val am = c.getSystemService(AudioManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?: return false
                scoOn = am.setCommunicationDevice(dev)
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                scoOn = true
            }
            scoOn
        } catch (e: Exception) { false }
    }

    /** Back to normal audio. */
    fun micOff(c: Context) {
        if (!scoOn) return
        scoOn = false
        val am = c.getSystemService(AudioManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
        } catch (e: Exception) {}
    }

    /** Called when listening with the car mic failed: fall back to the phone mic. */
    fun carMicFailed(c: Context) {
        if (scoOn) carMicBroken = true
        micOff(c)
    }

    fun isCarMicOn() = scoOn

    /** Voice settings: in the car, speak like navigation directions so it comes out of the truck speakers. */
    fun voiceAttributes(c: Context): AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setUsage(
            if (inCar(c)) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            else AudioAttributes.USAGE_ASSISTANT
        )
        .build()

    /** Shows text on the car screen (Singh AI's page in Android Auto), if it is open. */
    fun showOnCar(title: String, text: String) {
        CarService.instance?.let { s -> s.post { s.showText(title, text) } }
    }
}
