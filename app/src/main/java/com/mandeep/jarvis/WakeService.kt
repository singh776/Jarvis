package com.mandeep.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * "Hey Singh AI": listens in the background (only while the screen is on) for the words "Singh AI".
 * When heard, a small popup appears at the top of the screen (the app does NOT open), it listens to
 * your request, answers out loud and does the job (opens apps, sends WhatsApp, …).
 */
class WakeService : Service(), TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }
    private val brain by lazy { Brain(this) }

    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var punjabiVoice = false

    /** WAKE = waiting for "Singh AI"; COMMAND = listening to the request; BUSY = thinking/speaking. */
    private enum class State { WAKE, COMMAND, BUSY, OFF }
    private var state = State.OFF
    private var listeningNow = false
    private var commandMisses = 0

    private val punjabi get() = prefs.getBoolean("punjabi", true)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> { endSession(true); stopRecognizer() }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> main.postDelayed({ listenForWake() }, 600)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            startAsForeground()
        } catch (e: Exception) {
            // Android won't let the mic run from the background here (e.g. restarted by the system).
            // It starts again next time the app is opened.
            instance = null
            stopSelf()
            return
        }
        tts = TextToSpeech(this, this)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, f)
        main.postDelayed({ listenForWake() }, 800)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SESSION) {
            // side key: show the popup and listen right away
            main.post { beginSession() }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean("wake_word", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        state = State.OFF
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        stopRecognizer()
        JarvisAccess.instance?.hideMini()
        tts?.shutdown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) return
        punjabiVoice = t.isLanguageAvailable(Locale("pa", "IN")) >= TextToSpeech.LANG_AVAILABLE
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { afterSpeaking(id) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { afterSpeaking(id) } }
        })
    }

    // ------------------------------------------------------------ foreground

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Hey Singh AI", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shows while Singh AI listens for its name"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Singh AI")
            .setContentText(if (punjabi) "\"Singh AI\" ਕਹੋ ਤੇ ਬੋਲੋ" else "Say \"Singh AI\" and speak")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    // ------------------------------------------------------------- listening

    private fun canListen(): Boolean {
        if (appVisible) return false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val pm = getSystemService(PowerManager::class.java)
        return pm?.isInteractive == true
    }

    private fun stopRecognizer() {
        listeningNow = false
        try { recognizer?.cancel() } catch (e: Exception) {}
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
    }

    private fun makeRecognizer(): SpeechRecognizer? {
        if (recognizer != null) return recognizer
        // On-device recognition: no beeps, private, light on battery (when the phone supports it).
        recognizer = try {
            if (Build.VERSION.SDK_INT >= 33 && !onDeviceFailed && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                onDevice = true
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                onDevice = false
                if (SpeechRecognizer.isRecognitionAvailable(this)) SpeechRecognizer.createSpeechRecognizer(this) else null
            }
        } catch (e: Exception) { null }
        recognizer?.setRecognitionListener(listener)
        return recognizer
    }

    private var onDeviceFailed = false

    private fun beginSession() {
        if (JarvisAccess.instance == null) return
        main.removeCallbacksAndMessages(WAKE_TOKEN)
        stopRecognizer()
        tts?.stop()
        commandMisses = 0
        brain.clearHistory() // fresh chat each time (long-term memory stays)
        listenForCommand()
    }

    /** Quietly waits for "Singh AI" (only when "Hey Singh AI" is switched on). */
    fun listenForWake() {
        main.removeCallbacksAndMessages(WAKE_TOKEN)
        if (state == State.BUSY || state == State.COMMAND) return
        state = State.WAKE
        if (!enabled(this)) { stopRecognizer(); return }
        if (!canListen()) { stopRecognizer(); return }
        val rec = makeRecognizer() ?: return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        try {
            listeningNow = true
            rec.startListening(i)
        } catch (e: Exception) {
            listeningNow = false
            restartSoon(1500)
        }
    }

    /** Listens to what the user wants (after "Singh AI"). */
    private fun listenForCommand() {
        state = State.COMMAND
        stopRecognizer() // a fresh (online) recognizer understands Punjabi better
        val rec = (if (SpeechRecognizer.isRecognitionAvailable(this)) SpeechRecognizer.createSpeechRecognizer(this) else null)
            ?: return endSession(true)
        recognizer = rec
        onDevice = false
        rec.setRecognitionListener(listener)
        val lang = if (punjabi) "pa-IN" else "en-IN"
        val other = if (punjabi) "en-IN" else "pa-IN"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            .putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(other))
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        JarvisAccess.instance?.showMini(
            if (punjabi) "ਸੁਣ ਰਿਹਾ ਹਾਂ… ਬੋਲੋ" else "Listening… go ahead",
            "Singh AI", OrbView.Mode.LISTENING
        )
        try {
            listeningNow = true
            rec.startListening(i)
        } catch (e: Exception) {
            endSession(true)
        }
    }

    private fun restartSoon(ms: Long) {
        main.removeCallbacksAndMessages(WAKE_TOKEN)
        main.postAtTime({ if (state == State.WAKE) listenForWake() }, WAKE_TOKEN, android.os.SystemClock.uptimeMillis() + ms)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (state == State.COMMAND) JarvisAccess.instance?.miniLevel((rmsdB + 2f) / 12f)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            if (state == State.COMMAND) JarvisAccess.instance?.showMini(
                if (punjabi) "ਸੋਚ ਰਿਹਾ ਹਾਂ…" else "Thinking…", "", OrbView.Mode.THINKING
            )
        }

        override fun onError(error: Int) {
            listeningNow = false
            when (state) {
                State.WAKE -> {
                    if (onDevice && (error == 12 || error == 13 || error == SpeechRecognizer.ERROR_CLIENT)) {
                        // on-device model missing → use the normal recognizer
                        onDeviceFailed = true
                        stopRecognizer()
                    }
                    val wait = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 150L
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { stopRecognizer(); 800L }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 60_000L
                        else -> 1500L
                    }
                    restartSoon(wait)
                }
                State.COMMAND -> {
                    if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && commandMisses == 0) {
                        commandMisses++
                        main.postDelayed({ if (state == State.COMMAND) listenForCommand() }, 200)
                    } else endSession(true)
                }
                else -> {}
            }
        }

        override fun onResults(results: Bundle?) {
            listeningNow = false
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            when (state) {
                State.WAKE -> {
                    val hit = list.firstOrNull { wakeIndex(it) >= 0 }
                    if (hit != null) onWake(hit) else restartSoon(100)
                }
                State.COMMAND -> {
                    val text = list.firstOrNull().orEmpty()
                    if (text.isBlank()) endSession(true) else handle(text)
                }
                else -> {}
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val text = list.firstOrNull().orEmpty()
            when (state) {
                State.WAKE -> {
                    // react as soon as "Singh AI" is heard, don't wait for the end of the sentence
                    if (wakeIndex(text) >= 0 && text.trim().split(" ").size <= 3) onWake(text)
                }
                State.COMMAND -> if (text.isNotBlank()) JarvisAccess.instance?.showMini(text, "Singh AI", OrbView.Mode.LISTENING)
                else -> {}
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ------------------------------------------------------------ wake word

    /** Where the wake words end in [text], or -1. Accepts "Singh AI", "Sing AI", "ਸਿੰਘ ਏਆਈ", "सिंह एआई"… */
    private fun wakeIndex(text: String): Int {
        val t = text.lowercase(Locale.ROOT)
        val m = WAKE.find(t) ?: return -1
        return m.range.last + 1
    }

    private fun onWake(heard: String) {
        if (state != State.WAKE) return
        val end = wakeIndex(heard)
        val rest = if (end >= 0) heard.substring(end).trim(' ', ',', '.', '!', '?') else ""
        stopRecognizer()
        if (JarvisAccess.instance == null) {
            // the popup needs Screen Control; never pop the whole app open by itself
            state = State.WAKE
            restartSoon(3000)
            return
        }
        commandMisses = 0
        // each popup session starts a fresh chat (long-term memory stays)
        brain.clearHistory()
        if (rest.split(" ").count { it.isNotBlank() } >= 2) handle(rest) else listenForCommand()
    }

    // ------------------------------------------------------------- answering

    private fun handle(text: String) {
        state = State.BUSY
        stopRecognizer()
        val pa = punjabi
        val acc = JarvisAccess.instance
        acc?.showMini(text, if (pa) "ਸੋਚ ਰਿਹਾ ਹਾਂ…" else "Thinking…", OrbView.Mode.THINKING)
        Thread {
            var failed = false
            val reply: Reply = try {
                brain.ask(text, pa, punjabiVoice, phoneInfo())
            } catch (e: Exception) {
                failed = true
                Reply(Gemini.friendlyError(e, pa), null)
            }
            main.post { act(text, reply, pa, failed) }
        }.start()
    }

    private fun act(userText: String, reply: Reply, pa: Boolean, failed: Boolean) {
        val ctx: Context = JarvisAccess.instance ?: this
        val res = try { Actions(ctx).run(reply.action, pa, confirmed = true) } catch (e: Exception) { ActionResult() }
        val type = reply.action?.optString("type", "none") ?: "none"
        when {
            !res.task.isNullOrBlank() -> {
                speak(reply.say.ifBlank { if (pa) "ਹੁਣੇ ਕਰਦਾ ਹਾਂ।" else "On it, Sir." }, again = false, hideAfter = true)
                JarvisAccess.instance?.runTask(res.task, pa)
            }
            res.pressSend -> {
                JarvisAccess.instance?.pressSendSoon(pa)
                finishSoon(reply.say.ifBlank { if (pa) "ਭੇਜ ਰਿਹਾ ਹਾਂ…" else "Sending…" })
            }
            res.followUp != null -> {
                JarvisAccess.instance?.showMini(if (pa) "ਪੜ੍ਹ ਰਿਹਾ ਹਾਂ…" else "Reading…", "", OrbView.Mode.THINKING)
                val data = res.followUp ?: ""
                Thread {
                    val answer = try { brain.summarize(userText, data, pa, punjabiVoice) } catch (e: Exception) {
                        Gemini.friendlyError(e, pa)
                    }
                    main.post { speak(answer, again = false, hideAfter = true) }
                }.start()
            }
            else -> {
                val spoken = listOfNotNull(reply.say.takeIf { it.isNotBlank() }, res.message).joinToString(" ")
                // keep talking if Singh AI asked something back
                val again = !failed && type == "none" && spoken.trim().let { it.endsWith("?") || it.endsWith("？") }
                speak(spoken.ifBlank { "…" }, again = again, hideAfter = type != "none" || !again)
            }
        }
    }

    private var listenAfter = false
    private var hideAfterSpeech = true

    private fun speak(text: String, again: Boolean, hideAfter: Boolean) {
        listenAfter = again
        hideAfterSpeech = hideAfter
        JarvisAccess.instance?.showMini(text, "Singh AI", OrbView.Mode.SPEAKING)
        val t = tts
        if (!ttsReady || t == null) { afterSpeaking(null); return }
        val locale = when {
            text.any { it in '਀'..'੿' } && punjabiVoice -> Locale("pa", "IN")
            text.any { it in 'ऀ'..'ॿ' } -> Locale("hi", "IN")
            else -> Locale("en", "IN")
        }
        t.setLanguage(locale)
        t.setSpeechRate(prefs.getFloat("voice_speed", 1.0f))
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wake-" + System.currentTimeMillis())
    }

    private fun afterSpeaking(@Suppress("UNUSED_PARAMETER") id: String?) {
        if (state != State.BUSY) return
        if (listenAfter) {
            commandMisses = 0
            listenForCommand()
        } else if (hideAfterSpeech) {
            main.postDelayed({ if (state == State.BUSY) endSession(true) }, 1800)
        } else endSession(true)
    }

    private fun finishSoon(text: String) {
        JarvisAccess.instance?.showMini(text, "Singh AI", OrbView.Mode.IDLE)
        main.postDelayed({ if (state == State.BUSY) endSession(true) }, 2500)
    }

    /** Closes the popup and goes back to waiting for "Singh AI". */
    fun endSession(@Suppress("UNUSED_PARAMETER") hide: Boolean) {
        JarvisAccess.instance?.hideMini()
        tts?.stop()
        stopRecognizer()
        state = State.WAKE
        if (!enabled(this)) {
            // only opened for one side-key question → close the service again
            main.postDelayed({ if (state == State.WAKE && !enabled(this)) stopSelf() }, 400)
            return
        }
        restartSoon(700)
    }

    private fun phoneInfo(): String {
        val bm = getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return "Battery: $level%" + (if (bm?.isCharging == true) " (charging)." else ".")
    }

    companion object {
        @Volatile var instance: WakeService? = null

        /** True while the Singh AI app itself is on screen (it uses the mic then). */
        @Volatile var appVisible = false
            private set

        private const val CHANNEL = "wake"
        private const val NOTIF_ID = 11
        private const val ACTION_STOP = "com.mandeep.jarvis.STOP_WAKE"
        private const val ACTION_SESSION = "com.mandeep.jarvis.SESSION"
        private val WAKE_TOKEN = Any()

        private val WAKE = Regex(
            "(?<![a-z])(singh|sing|sinh|sink|ਸਿੰਘ|सिंह|सिंग)\\s*[-.]?\\s*(a\\.?\\s?i\\b|ai\\b|ay\\b|eye\\b|ਏ\\s?ਆਈ|ਏਆਈ|ए\\s?आई|एआई)"
        )

        fun enabled(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("wake_word", false)

        /** Starts listening for "Singh AI" (call while the app is on screen). */
        fun start(c: Context) {
            if (!enabled(c)) return
            if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            if (instance != null) return
            try {
                val i = Intent(c, WakeService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) {}
        }

        fun stop(c: Context) {
            try { c.stopService(Intent(c, WakeService::class.java)) } catch (e: Exception) {}
        }

        /** Side key: show the popup and listen once (works even when "Hey Singh AI" is off). */
        fun startSession(c: Context) {
            try {
                val i = Intent(c, WakeService::class.java).setAction(ACTION_SESSION)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) {}
        }

        /** The app tells the service when it is on screen, so they don't both use the mic. */
        fun setAppVisible(visible: Boolean) {
            appVisible = visible
            val s = instance ?: return
            s.main.post {
                if (visible) {
                    if (s.state == State.WAKE) s.stopRecognizer()
                } else s.listenForWake()
            }
        }
    }
}
