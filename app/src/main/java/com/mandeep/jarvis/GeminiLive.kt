package com.mandeep.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.sqrt

/**
 * Gemini LIVE — the same real-time voice chat the Gemini app uses:
 * your voice streams straight to Gemini and its own natural voice streams back (no "listening… thinking…" steps),
 * you can interrupt it any time, and it can do phone tasks and search Google while talking.
 * Free on the Gemini API free tier (native-audio preview models).
 */
class GeminiLive(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onReady()
        fun onUserText(text: String)          // what you are saying (live)
        fun onAiText(text: String)            // what Gemini is saying (live)
        fun onTurnDone()
        fun onSpeaking(speaking: Boolean)
        fun onLevel(level: Float)
        fun onAction(actionJson: String): String  // run a phone action, return a short result
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile var muted = false
    private var sock: SSLSocket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var aiSpeaking = false
    /** Mic stays closed until this time (so Gemini never hears its own voice from the speaker). */
    @Volatile private var quietUntil = 0L
    private var oldMode = android.media.AudioManager.MODE_NORMAL
    private var loudFrames = 0
    private var lastFrame: ByteArray? = null
    private val userBuf = StringBuilder()
    private val aiBuf = StringBuilder()

    // ------------------------------------------------------------------ start / stop

    fun start(systemPrompt: String, voice: String, punjabi: Boolean) {
        if (running) return
        running = true
        // "phone call" audio mode on the loudspeaker: the phone's echo canceller then removes Gemini's own voice from the mic
        try {
            val am = ctx.getSystemService(android.media.AudioManager::class.java) ?: throw IllegalStateException()
            oldMode = am.mode
            am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            if (!CarAudio.inCar(ctx)) speaker(am, true)
        } catch (e: Exception) {}
        Thread {
            try {
                val key = Gemini.apiKey(ctx)
                if (key.isEmpty()) throw Exception("No API key")
                val model = pickModel(ctx, key)
                connect(key)
                send(setupMessage(model, systemPrompt, voice, punjabi))
                readLoop()
            } catch (e: Exception) {
                if (running) fail(e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    fun stop() {
        running = false
        try {
            val am = ctx.getSystemService(android.media.AudioManager::class.java) ?: throw IllegalStateException()
            speaker(am, false)
            am.mode = oldMode
        } catch (e: Exception) {}
        try { recorder?.stop() } catch (e: Exception) {}
        try { recorder?.release() } catch (e: Exception) {}
        recorder = null
        playQueue.clear()
        try { track?.pause(); track?.flush(); track?.release() } catch (e: Exception) {}
        track = null
        try { sendFrame(0x8, ByteArray(0)) } catch (e: Exception) {}
        try { sock?.close() } catch (e: Exception) {}
        sock = null
    }

    private fun fail(msg: String) {
        val wasRunning = running
        stop()
        if (wasRunning) main.post { listener.onError(msg) }
    }

    // ------------------------------------------------------------------ model

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        /** How loud you must be (0..1) to interrupt Gemini while it talks — above its echo. */
        const val BARGE_LEVEL = 0.32f

        /** A Live (native audio) model this key can use — remembered after the first time. */
        fun pickModel(c: Context, key: String): String {
            val p = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            p.getString("live_model", null)?.takeIf { it.isNotEmpty() }?.let { return it }
            val found = try {
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection() as HttpsURLConnection
                conn.setRequestProperty("x-goog-api-key", key)
                conn.connectTimeout = 6000; conn.readTimeout = 8000
                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONObject(raw).optJSONArray("models") ?: JSONArray()
                val names = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                    val id = m.optString("name").removePrefix("models/")
                    if (methods.contains("bidiGenerateContent") && id.contains("audio")) names.add(id)
                }
                names.sortedWith(compareBy<String>({ !it.contains("flash") }).thenByDescending { it }).firstOrNull()
            } catch (e: Exception) { null }
            val m = found ?: DEFAULT_MODEL
            p.edit().putString("live_model", m).apply()
            return m
        }
    }

    private fun setupMessage(model: String, system: String, voice: String, punjabi: Boolean): String {
        val tools = JSONArray()
            .put(JSONObject().put("functionDeclarations", JSONArray().put(JSONObject()
                .put("name", "phone_action")
                .put("description", "Do something on the user's phone (open apps, play songs, call, message, torch, volume, " +
                    "alarms, navigation, search inside apps, type in the open chat, read notifications…). " +
                    "You MUST call this for every phone task — nothing happens on the phone unless you call it. " +
                    "Never say an app is open or a task is done before calling this and getting the result.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("action_json", JSONObject().put("type", "STRING")
                        .put("description", "ONE action object as JSON, exactly like the actions listed in the instructions, " +
                            "e.g. {\"type\":\"open_app\",\"name\":\"YouTube\"}")))
                    .put("required", JSONArray().put("action_json"))))))
            .put(JSONObject().put("google_search", JSONObject()))
        val live = "\n\nLIVE VOICE MODE: you are talking out loud in real time. Only answer what the USER says; " +
            "ignore any sound that is your own voice. When the user asks to open/play/call/search/send anything, call phone_action " +
            "right away (one call), then say one short line about the result. " +
            "Keep every answer short (1-2 sentences), natural " +
            "and friendly, like a phone call. For phone tasks call the phone_action function with the action JSON " +
            "(do not read JSON out loud). For current facts use Google Search. " +
            (if (punjabi) "Speak Punjabi when the user speaks Punjabi, English when they speak English." else "Reply in the user's language.")
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system + live))))
            .put("tools", tools)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        return JSONObject().put("setup", setup).toString()
    }

    // ------------------------------------------------------------------ mic → Gemini

    @SuppressLint("MissingPermission")
    private fun startMic() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is off"); return
        }
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
        recorder = rec
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true } catch (e: Exception) {}
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true } catch (e: Exception) {}
        rec.startRecording()
        Thread {
            val buf = ByteArray(3200) // 100 ms
            while (running) {
                val n = try { rec.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                if (n <= 0) { if (!running) break; continue }
                var sum = 0.0
                var i = 0
                while (i + 1 < n) { val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble(); sum += s * s; i += 2 }
                val level = (sqrt(sum / (n / 2)) / 3000.0).toFloat().coerceIn(0f, 1f)
                // half-duplex: while Gemini talks (and a moment after) the mic is NOT sent,
                // otherwise it hears itself and answers its own words
                val talking = aiSpeaking || playQueue.isNotEmpty() || System.currentTimeMillis() < quietUntil
                if (talking) {
                    // only a clearly louder voice than the echo counts → you interrupt it
                    if (level > BARGE_LEVEL) loudFrames++ else loudFrames = 0
                    if (loudFrames >= 2 && !muted) {
                        loudFrames = 0
                        bargeIn()
                        lastFrame?.let { prev ->
                            try { send("{\"realtimeInput\":{\"audio\":{\"data\":\"${Base64.encodeToString(prev, Base64.NO_WRAP)}\",\"mimeType\":\"audio/pcm;rate=16000\"}}}") } catch (e: Exception) {}
                        }
                    } else {
                        lastFrame = buf.copyOf(n)
                        continue
                    }
                } else {
                    loudFrames = 0
                    main.post { listener.onLevel(level) }
                }
                if (muted) continue
                val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                try {
                    send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
                } catch (e: Exception) { if (running) fail("Connection lost"); break }
            }
        }.start()
    }

    // ------------------------------------------------------------------ Gemini → speaker

    private fun startPlayer() {
        val rate = 24000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min * 2, 9600))
            .build()
        track = t
        t.play()
        Thread {
            while (running) {
                val pcm = try { playQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
                if (pcm == null) {
                    if (aiSpeaking && playQueue.isEmpty()) {
                        aiSpeaking = false
                        quietUntil = System.currentTimeMillis() + 400 // the speaker still rings out for a moment
                        main.post { listener.onSpeaking(false) }
                    }
                    continue
                }
                if (!aiSpeaking) { aiSpeaking = true; main.post { listener.onSpeaking(true) } }
                try { t.write(pcm, 0, pcm.size) } catch (e: Exception) {}
                quietUntil = System.currentTimeMillis() + pcm.size / 48 + 400 // audio length (24 kHz, 16-bit) + echo tail
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun speaker(am: android.media.AudioManager, on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (on) am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { am.setCommunicationDevice(it) }
            else am.clearCommunicationDevice()
        } else am.isSpeakerphoneOn = on
    }

    /** You spoke over Gemini → stop its voice now and listen to you. */
    private fun bargeIn() {
        playQueue.clear()
        try { track?.pause(); track?.flush(); track?.play() } catch (e: Exception) {}
        quietUntil = 0
        if (aiSpeaking) { aiSpeaking = false; main.post { listener.onSpeaking(false) } }
    }

    // ------------------------------------------------------------------ messages from Gemini

    private fun handle(msg: String) {
        val o = try { JSONObject(msg) } catch (e: Exception) { return }
        if (o.has("setupComplete")) {
            startPlayer()
            startMic()
            main.post { listener.onReady() }
            return
        }
        o.optJSONObject("toolCall")?.let { tc ->
            val calls = tc.optJSONArray("functionCalls") ?: JSONArray()
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val id = c.optString("id"); val name = c.optString("name")
                val args = c.optJSONObject("args") ?: JSONObject()
                val json = args.optString("action_json", args.toString())
                var result = "done"
                val lock = Object()
                var finished = false
                main.post {
                    result = try { listener.onAction(json) } catch (e: Exception) { "failed: ${e.message}" }
                    synchronized(lock) { finished = true; lock.notifyAll() }
                }
                synchronized(lock) { if (!finished) lock.wait(4000) }
                send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(
                    JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result))))).toString())
            }
            return
        }
        val sc = o.optJSONObject("serverContent") ?: return
        if (sc.optBoolean("interrupted")) {
            // you started talking → stop its voice right away
            playQueue.clear()
            try { track?.pause(); track?.flush(); track?.play() } catch (e: Exception) {}
            if (aiSpeaking) { aiSpeaking = false; main.post { listener.onSpeaking(false) } }
        }
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            if (aiBuf.isNotEmpty()) aiBuf.setLength(0)
            userBuf.append(it)
            val t = userBuf.toString().trim()
            main.post { listener.onUserText(t) }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            if (userBuf.isNotEmpty()) userBuf.setLength(0)
            aiBuf.append(it)
            val t = aiBuf.toString().trim()
            main.post { listener.onAiText(t) }
        }
        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) for (i in 0 until parts.length()) {
            val d = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            if (d.optString("mimeType").startsWith("audio")) {
                val pcm = Base64.decode(d.optString("data"), Base64.DEFAULT)
                if (pcm.isNotEmpty()) playQueue.offer(pcm)
            }
        }
        if (sc.optBoolean("turnComplete")) {
            userBuf.setLength(0); aiBuf.setLength(0)
            main.post { listener.onTurnDone() }
        }
    }

    // ------------------------------------------------------------------ tiny WebSocket (RFC 6455)

    private fun connect(key: String) {
        val host = "generativelanguage.googleapis.com"
        val s = SSLSocketFactory.getDefault().createSocket(host, 443) as SSLSocket
        s.soTimeout = 0
        s.startHandshake()
        sock = s
        val wsKey = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val path = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + URLEncoder.encode(key, "UTF-8")
        val req = "GET $path HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $wsKey\r\nSec-WebSocket-Version: 13\r\n\r\n"
        val o = s.outputStream
        o.write(req.toByteArray()); o.flush()
        out = o
        // read the HTTP answer headers
        val inp = s.inputStream
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = inp.read()
            if (b < 0) throw Exception("Connection closed")
            head.append(b.toChar())
            if (head.length > 16000) throw Exception("Bad answer")
        }
        if (!head.startsWith("HTTP/1.1 101")) throw Exception("Live not allowed: " + head.lineSequence().firstOrNull().orEmpty())
    }

    private fun send(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val o = out ?: return
        synchronized(writeLock) {
            val hdr = ByteArrayOutputStream()
            hdr.write(0x80 or opcode)
            val n = payload.size
            when {
                n < 126 -> hdr.write(0x80 or n)
                n < 65536 -> { hdr.write(0x80 or 126); hdr.write(n shr 8); hdr.write(n and 0xff) }
                else -> { hdr.write(0x80 or 127); for (i in 7 downTo 0) hdr.write(((n.toLong() shr (8 * i)) and 0xff).toInt()) }
            }
            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            hdr.write(mask)
            val masked = ByteArray(n) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            o.write(hdr.toByteArray()); o.write(masked); o.flush()
        }
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = inp.read(b, off, n - off); if (r < 0) throw Exception("Connection closed"); off += r }
        return b
    }

    private fun readLoop() {
        val inp = sock?.inputStream ?: return
        val msg = ByteArrayOutputStream()
        while (running) {
            val h = readFully(inp, 2)
            val fin = (h[0].toInt() and 0x80) != 0
            val op = h[0].toInt() and 0x0f
            var len = (h[1].toInt() and 0x7f).toLong()
            if (len == 126L) { val e = readFully(inp, 2); len = (((e[0].toInt() and 0xff) shl 8) or (e[1].toInt() and 0xff)).toLong() }
            else if (len == 127L) { val e = readFully(inp, 8); len = 0; for (b in e) len = (len shl 8) or (b.toLong() and 0xff) }
            val masked = (h[1].toInt() and 0x80) != 0
            val mk = if (masked) readFully(inp, 4) else null
            val data = readFully(inp, len.toInt())
            if (mk != null) for (i in data.indices) data[i] = (data[i].toInt() xor mk[i % 4].toInt()).toByte()
            when (op) {
                0x0, 0x1, 0x2 -> {
                    msg.write(data)
                    if (fin) { val text = msg.toString("UTF-8"); msg.reset(); handle(text) }
                }
                0x8 -> {
                    val reason = if (data.size > 2) String(data, 2, data.size - 2, Charsets.UTF_8) else ""
                    if (running) fail(if (reason.isNotBlank()) reason else "Closed by Google")
                    return
                }
                0x9 -> sendFrame(0xA, data)
            }
        }
    }
}
