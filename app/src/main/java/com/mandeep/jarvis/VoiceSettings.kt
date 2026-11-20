package com.mandeep.jarvis

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Which voice Singh AI speaks with (chosen in Settings → Voice → Voice). */
object VoiceSettings {

    /** Voice styles: deep (male-like), normal, higher (female-like). */
    val PITCHES = listOf(0.8f to "Deep", 1.0f to "Normal", 1.2f to "Higher")

    private fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    /** Which phone voice engine to use (e.g. Google or Samsung). null = phone's default. */
    fun engine(c: Context): String? = prefs(c).getString("tts_engine", null)?.takeIf { it.isNotEmpty() }
    fun setEngine(c: Context, pkg: String?) = prefs(c).edit().putString("tts_engine", pkg ?: "").apply()

    /** HD = the engine's high-quality (neural) voices. */
    fun isHd(v: Voice) = v.quality >= Voice.QUALITY_HIGH

    fun pitch(c: Context) = prefs(c).getFloat("voice_pitch", 1.0f)
    fun speed(c: Context) = prefs(c).getFloat("voice_speed", 1.0f)

    fun pitchLabel(c: Context): String {
        val p = pitch(c)
        return PITCHES.minByOrNull { kotlin.math.abs(it.first - p) }?.second ?: "Normal"
    }

    fun localeFor(text: String, punjabiVoice: Boolean): Locale = when {
        text.any { it in '਀'..'੿' } && punjabiVoice -> Locale("pa", "IN")
        text.any { it in 'ऀ'..'ॿ' } -> Locale("hi", "IN")
        else -> Locale("en", "IN")
    }

    fun savedVoice(c: Context, language: String): String? = prefs(c).getString("voice_$language", null)

    fun saveVoice(c: Context, language: String, name: String?) {
        prefs(c).edit().apply { if (name == null) remove("voice_$language") else putString("voice_$language", name) }.apply()
    }

    /** Sets language, the chosen voice, pitch and speed before speaking [text]. */
    fun apply(c: Context, t: TextToSpeech, text: String, punjabiVoice: Boolean) {
        val loc = localeFor(text, punjabiVoice)
        t.setLanguage(loc)
        val name = savedVoice(c, loc.language)
        if (name != null) {
            val v: Voice? = try { t.voices?.firstOrNull { it.name == name } } catch (e: Exception) { null }
            if (v != null) try { t.voice = v } catch (e: Exception) {}
        }
        t.setPitch(pitch(c))
        t.setSpeechRate(speed(c))
    }
}
