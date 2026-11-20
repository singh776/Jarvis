package com.mandeep.jarvis

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * HD voices: Gemini's own natural voices (the same kind Gemini uses), spoken from your API key.
 * Clear, human-sounding, different men and women voices. Needs internet; if it can't be used
 * (no internet, limit reached…) Singh AI falls back to the phone's voice by itself.
 */
object HdVoice {

    /** name, style, man/woman */
    val VOICES = listOf(
        Triple("Puck", "Upbeat", "Man"), Triple("Charon", "Informative", "Man"), Triple("Fenrir", "Excitable", "Man"),
        Triple("Orus", "Firm", "Man"), Triple("Iapetus", "Clear", "Man"), Triple("Enceladus", "Breathy", "Man"),
        Triple("Algieba", "Smooth", "Man"), Triple("Algenib", "Gravelly", "Man"), Triple("Alnilam", "Firm", "Man"),
        Triple("Gacrux", "Mature", "Man"), Triple("Achird", "Friendly", "Man"), Triple("Sadaltager", "Knowledgeable", "Man"),
        Triple("Kore", "Firm", "Woman"), Triple("Zephyr", "Bright", "Woman"), Triple("Leda", "Youthful", "Woman"),
        Triple("Aoede", "Breezy", "Woman"), Triple("Callirrhoe", "Easy-going", "Woman"), Triple("Autonoe", "Bright", "Woman"),
        Triple("Despina", "Smooth", "Woman"), Triple("Erinome", "Clear", "Woman"), Triple("Achernar", "Soft", "Woman"),
        Triple("Vindemiatrix", "Gentle", "Woman"), Triple("Sulafat", "Warm", "Woman"), Triple("Pulcherrima", "Forward", "Woman")
    )

    private const val DEFAULT_MODEL = "gemini-2.5-flash-preview-tts"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var track: AudioTrack? = null
    @Volatile private var playId = 0
    @Volatile var speaking = false
        private set

    private fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("hd_voice", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("hd_voice", on).apply()
    fun voice(c: Context) = prefs(c).getString("hd_voice_name", "Puck") ?: "Puck"
    fun setVoice(c: Context, v: String) = prefs(c).edit().putString("hd_voice_name", v).apply()

    /** Use HD for this answer? (on, internet, and not recently failed) */
    fun usable(c: Context) = enabled(c) && Offline.online(c) && System.currentTimeMillis() > pauseUntil
    @Volatile private var pauseUntil = 0L

    /** Why the last HD try didn't work (shown in Settings). */
    @Volatile var lastError = ""
        private set

    /** Text-to-speech models on this key, the remembered working one first. */
    private fun models(c: Context): List<String> {
        val saved = prefs(c).getString("tts_model", null)?.takeIf { it.isNotEmpty() }
        val found = try {
            val (ok, _, names) = Gemini.testKey(Gemini.apiKey(c))
            if (ok) names.filter { it.contains("tts") } else emptyList()
        } catch (e: Exception) { emptyList() }
        // flash ones first (free tier), newest first
        val sorted = found.sortedWith(compareBy<String>({ !it.contains("flash") }, { it.contains("pro") }).thenByDescending { it })
        return (listOfNotNull(saved) + sorted + DEFAULT_MODEL).distinct()
    }

    fun stop() {
        playId++
        speaking = false
        try { track?.pause(); track?.flush(); track?.release() } catch (e: Exception) {}
        track = null
    }

    /**
     * Speaks [text]. [onStart] when sound begins, [onDone] when finished.
     * [onFail] (main thread) if HD can't be used — then speak with the phone voice instead.
     */
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(2)

    /** Splits into sentences (small ones joined) so the first one can play while the rest is made. */
    private fun chunks(text: String): List<String> {
        val parts = text.split(Regex("(?<=[.!?।])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        for (p in parts) {
            if (out.isNotEmpty() && (out.last().length < 60 || p.length < 25) && out.last().length + p.length < 220) out[out.size - 1] = out.last() + " " + p
            else out.add(p)
        }
        return out.ifEmpty { listOf(text) }
    }

    fun speak(c: Context, text: String, onStart: () -> Unit, onDone: () -> Unit, onFail: () -> Unit, voiceName: String? = null) {
        stop()
        val id = playId
        val app = c.applicationContext
        val voice = voiceName ?: voice(app)
        speaking = true
        Thread {
            val parts = chunks(text)
            var next: java.util.concurrent.Future<ByteArray?>? = pool.submit<ByteArray?> {
                try { synth(app, parts[0], voice) } catch (e: Exception) { null }
            }
            var started = false
            for (i in parts.indices) {
                val pcm = try { next?.get() } catch (e: Exception) { null }
                if (id != playId) return@Thread
                if (pcm == null || pcm.isEmpty()) {
                    if (!started) {
                        speaking = false
                        pauseUntil = System.currentTimeMillis() + 5 * 60_000 // use the phone voice for a bit
                        main.post { onFail() }
                        return@Thread
                    }
                    break
                }
                // make the next sentence while this one plays
                next = if (i + 1 < parts.size) pool.submit<ByteArray?> {
                    try { synth(app, parts[i + 1], voice) } catch (e: Exception) { null }
                } else null
                if (!started) { started = true; main.post { if (id == playId) onStart() } }
                if (!playBlocking(app, pcm, id)) return@Thread
            }
            if (id != playId) return@Thread
            speaking = false
            main.post { onDone() }
        }.start()
    }

    private fun synth(c: Context, text: String, voice: String): ByteArray? {
        val key = Gemini.apiKey(c)
        if (key.isEmpty()) { lastError = "No API key"; return null }
        val list = models(c)
        for (m in list) {
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig",
                        JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
            val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(m, "UTF-8") + ":generateContent").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", key)
            }
            val (code, raw) = try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val cd = conn.responseCode
                cd to ((if (cd in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "")
            } catch (e: Exception) { lastError = "Network: ${e.javaClass.simpleName}"; return null }
            if (code !in 200..299) {
                val msg = try { JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty() } catch (e: Exception) { "" }
                lastError = when {
                    code == 429 && msg.contains("limit: 0") -> "Your free key has no HD voice use for $m"
                    code == 429 -> "Free HD voice limit used up for today ($m)"
                    code == 404 -> "$m not available"
                    else -> "$code: ${msg.take(80)}"
                }
                continue // try the next TTS model
            }
            val parts = try {
                JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            } catch (e: Exception) { null } ?: continue
            for (i in 0 until parts.length()) {
                val d = parts.optJSONObject(i)?.optJSONObject("inlineData")
                    ?: parts.optJSONObject(i)?.optJSONObject("inline_data") ?: continue
                val pcm = Base64.decode(d.optString("data"), Base64.DEFAULT)
                if (pcm.isNotEmpty()) {
                    prefs(c).edit().putString("tts_model", m).apply()
                    lastError = ""
                    return pcm
                }
            }
        }
        return null
    }

    /** Plays raw 24 kHz 16-bit mono audio (what Gemini sends). Returns false if stopped. */
    private fun playBlocking(c: Context, pcm: ByteArray, id: Int): Boolean {
        val rate = 24000
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(CarAudio.voiceAttributes(c))
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size.coerceAtLeast(4096))
                .build()
        } catch (e: Exception) { null } ?: return true
        if (id != playId) { t.release(); return false }
        track = t
        t.write(pcm, 0, pcm.size)
        t.play()
        val ms = pcm.size / 2 * 1000L / rate
        val end = System.currentTimeMillis() + ms + 60
        while (System.currentTimeMillis() < end && id == playId) Thread.sleep(30)
        if (id != playId) return false
        try { t.stop(); t.release() } catch (e: Exception) {}
        if (track === t) track = null
        return true
    }
}
