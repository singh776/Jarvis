package com.mandeep.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Side key ("Digital assistant app"): a small Gemini-style popup at the bottom of the screen
 * over whatever app is open — glowing light along the bottom, a card with the sparkle,
 * what you say and Singh AI's answer. Works by itself (no screen-control permission needed).
 */
class AssistActivity : Activity(), TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }
    private val punjabi get() = prefs.getBoolean("punjabi", true)
    private val brain by lazy { Brain(this) }

    private lateinit var p: Ui.Palette
    private lateinit var card: LinearLayout
    private lateinit var spark: SparkleView
    private lateinit var glow: GlowView
    private lateinit var said: TextView
    private lateinit var answer: TextView
    private lateinit var mic: MicButton

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var punjabiVoice = false
    private var listening = false
    private var busy = false
    private var listenAfter = false
    private var closeAfter = false
    private var misses = 0
    private var closing = false

    private fun dp(v: Int) = Ui.dp(this, v.toFloat())

    companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_FROM_WAKE = "from_wake"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micOk || Gemini.apiKey(this).isEmpty()) {
            // first-time setup happens in the full app
            startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.setDimAmount(0f)
        WakeService.setAppVisible(true) // the "Hey Singh AI" listener lets go of the mic
        p = Ui.palette(this)
        buildUi()
        tts = TextToSpeech(this, this, VoiceSettings.engine(this))
        Gemini.warmUp(this)
        val cmd = intent?.getStringExtra(EXTRA_COMMAND)
        if (!cmd.isNullOrBlank()) main.postDelayed({ handle(cmd) }, 200)  // "Singh AI, open YouTube" — do it straight away
        else main.postDelayed({ listen() }, 250)
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val root = FrameLayout(this).apply { setOnClickListener { close() } }
        glow = GlowView(this).apply { dark = p.dark; mode = OrbView.Mode.LISTENING }
        root.addView(glow, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.38f).toInt(), Gravity.BOTTOM))

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(if (p.dark) Color.parseColor("#1E1F22") else Color.WHITE, dp(30).toFloat())
            elevation = dp(10).toFloat()
            setPadding(dp(18), dp(16), dp(12), dp(12))
            isClickable = true // taps on the card don't close it
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        spark = SparkleView(this).apply { dark = p.dark; mode = OrbView.Mode.LISTENING }
        top.addView(spark, LinearLayout.LayoutParams(dp(34), dp(34)))
        said = Ui.text(this, if (punjabi) "ਸੁਣ ਰਿਹਾ ਹਾਂ…" else "Listening…", 18f, p.muted, Ui.regular).apply {
            setPadding(dp(12), 0, dp(8), 0)
            maxLines = 3
        }
        top.addView(said, LinearLayout.LayoutParams(0, -2, 1f))
        mic = MicButton(this).apply {
            iconColor = p.text
            ringBg = Color.TRANSPARENT
            contentDescription = "Talk"
            setOnClickListener {
                when {
                    busy -> {}
                    (tts?.isSpeaking == true || HdVoice.speaking) -> { tts?.stop(); HdVoice.stop(); listen() }
                    listening -> recognizer?.stopListening()
                    else -> { misses = 0; listen() }
                }
            }
        }
        top.addView(mic, LinearLayout.LayoutParams(dp(46), dp(46)))
        val live = LiveButton(this).apply {
            dark = p.dark
            contentDescription = "Live"
            setOnClickListener {
                startActivity(Intent(this@AssistActivity, MainActivity::class.java)
                    .setAction(Intent.ACTION_ASSIST).putExtra(MainActivity.EXTRA_OPEN_LIVE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
        top.addView(live, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(4) })
        card.addView(top, LinearLayout.LayoutParams(-1, -2))

        answer = Ui.text(this, "", 19f, p.text, Ui.regular).apply {
            setPadding(dp(4), dp(10), dp(8), dp(4))
            maxLines = 8
            setLineSpacing(0f, 1.08f)
            visibility = View.GONE
        }
        card.addView(answer, LinearLayout.LayoutParams(-1, -2))

        val lp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(14)) }
        root.addView(card, lp)
        Ui.onInsets(root) { _, b -> (card.layoutParams as FrameLayout.LayoutParams).bottomMargin = b + dp(14); card.requestLayout() }
        setContentView(root)

        // slide up
        card.translationY = dp(140).toFloat()
        card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(320).setInterpolator(DecelerateInterpolator(2.2f)).withLayer().start()
        glow.alpha = 0f
        glow.animate().alpha(1f).setDuration(400).start()
    }

    private fun setMode(m: OrbView.Mode) {
        spark.mode = m
        glow.mode = m
        mic.active = m == OrbView.Mode.LISTENING
    }

    private fun showAnswer(text: String) {
        answer.visibility = View.VISIBLE
        answer.alpha = 0f
        answer.text = text
        answer.animate().alpha(1f).setDuration(220).start()
    }

    private fun close() {
        if (closing) return
        closing = true
        recognizer?.cancel()
        CarAudio.micOff(this)
        if (!isChangingConfigurations && !HdVoice.speaking) HdVoice.stop()
        card.animate().translationY(dp(160).toFloat()).alpha(0f).setDuration(220).withLayer().start()
        glow.animate().alpha(0f).setDuration(220).withEndAction { finish(); overridePendingTransition(0, 0) }.start()
    }

    // --------------------------------------------------------------- voice in

    private fun listen() {
        if (busy || listening || closing) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { said.text = "Google speech recognition missing"; return }
        recognizer?.destroy()
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        rec.setRecognitionListener(listener)
        val lang = if (punjabi) "pa-IN" else "en-IN"
        val other = if (punjabi) "en-IN" else "pa-IN"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            .putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(other))
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !Offline.online(this))
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        listening = true
        setMode(OrbView.Mode.LISTENING)
        said.setTextColor(p.muted)
        said.text = if (punjabi) "ਸੁਣ ਰਿਹਾ ਹਾਂ…" else "Listening…"
        val start = Runnable { try { Quiet.beep(this); rec.startListening(i) } catch (e: Exception) { listening = false } }
        if (CarAudio.micOn(this)) main.postDelayed(start, 700) else start.run()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val l = (rmsdB + 2f) / 12f
            spark.level = l; glow.level = l; mic.level = l.coerceIn(0f, 1f)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { Quiet.beep(this@AssistActivity, 600); setMode(OrbView.Mode.THINKING) }
        override fun onError(error: Int) {
            listening = false
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!quiet && CarAudio.isCarMicOn()) { CarAudio.carMicFailed(this@AssistActivity); main.postDelayed({ listen() }, 300); return }
            CarAudio.micOff(this@AssistActivity)
            // keep listening quietly while we're talking; close only after a long silence (~20 s)
            if ((quiet || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) && misses++ < 4) { main.postDelayed({ listen() }, 150); return }
            setMode(OrbView.Mode.IDLE)
            said.text = if (punjabi) "ਸੁਣਿਆ ਨਹੀਂ — 🎤 ਦਬਾ ਕੇ ਫਿਰ ਬੋਲੋ" else "Didn't catch that — tap 🎤"
            main.postDelayed({ if (!listening && !busy && !(tts?.isSpeaking == true || HdVoice.speaking)) close() }, 4000)
        }
        override fun onResults(results: Bundle?) {
            listening = false
            CarAudio.micOff(this@AssistActivity)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return }
            misses = 0
            handle(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) { said.setTextColor(p.text); said.text = t }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ----------------------------------------------------------------- think

    private fun handle(text: String) {
        busy = true
        said.setTextColor(p.text)
        said.text = text
        setMode(OrbView.Mode.THINKING)
        val pa = punjabi
        val quick = try { Actions(this).quickOpen(text, pa) } catch (e: Exception) { null }
        if (quick != null) { busy = false; speak(quick, again = false); return }
        if (!Offline.online(this)) { busy = false; Knowledge.offlineAnswer(this, text, pa)?.let { speak(it, again = false); return }; speak(if (pa) "ਨੈੱਟ ਨਹੀਂ ਚੱਲਦਾ। ਬਿਨਾਂ ਨੈੱਟ ਮੈਂ ਐਪ ਖੋਲ੍ਹ ਸਕਦਾ, ਕਾਲ ਕਰ ਸਕਦਾ, ਟਾਰਚ, ਆਵਾਜ਼ ਤੇ ਫ਼ੋਨ ਦੇ ਗਾਣੇ ਚਲਾ ਸਕਦਾ ਹਾਂ।" else "No internet. Offline I can open apps, make calls, torch, volume and play songs saved on the phone.", again = false); return }
        Thread {
            var failed = false
            val reply = try {
                brain.ask(text, pa, punjabiVoice,
                    JarvisAccess.instance?.foregroundInfo()?.takeIf { it.isNotBlank() }?.let { "Open app (the user is looking at it now): $it" } ?: "",
                    fast = true)
            } catch (e: Exception) {
                failed = true
                Reply(Gemini.friendlyError(e, pa), null)
            }
            main.post { busy = false; act(text, reply, pa, failed) }
        }.start()
    }

    private fun act(userText: String, reply: Reply, pa: Boolean, failed: Boolean) {
        if (closing) return
        if (reply.action?.optString("type") == "search_answer") {
            busy = true
            setMode(OrbView.Mode.THINKING)
            said.text = if (pa) "Google ਤੇ ਲੱਭ ਰਿਹਾ ਹਾਂ…" else "Searching Google…"
            val q = reply.action.optString("query").ifBlank { userText }
            Thread {
                val (ans, src) = try { Gemini.searchAnswer(this, q, pa) } catch (e: Exception) { Gemini.friendlyError(e, pa) to emptyList() }
                main.post {
                    busy = false
                    said.text = userText
                    speak(ans, false)
                    if (src.isNotEmpty()) answer.text = ans + "\n\n🔗 " + src.take(3).joinToString("  ·  ") { it.first }
                }
            }.start()
            return
        }
        val res = try { Actions(this).run(reply.action, pa, confirmed = true) } catch (e: Exception) { ActionResult() }
        val type = reply.action?.optString("type", "none") ?: "none"
        when {
            res.typeText != null -> {
                val acc = JarvisAccess.instance
                showAnswer(reply.say.ifBlank { if (pa) "ਲਿਖ ਰਿਹਾ ਹਾਂ…" else "Typing…" })
                close() // get out of the way, then type in the user's app
                acc?.typeHere(res.typeText, res.typeSend, pa)
            }
            !res.task.isNullOrBlank() -> {
                val acc = JarvisAccess.instance
                if (acc == null) speak(if (pa) "ਇਸ ਕੰਮ ਲਈ ਸੈਟਿੰਗ ਵਿੱਚ ਸਕਰੀਨ ਕੰਟਰੋਲ ਚਾਲੂ ਕਰੋ।" else "Turn on Screen control in Settings for this.", false)
                else {
                    showAnswer(reply.say.ifBlank { if (pa) "ਹੁਣੇ ਕਰਦਾ ਹਾਂ।" else "On it." })
                    close() // close first, so the task works in the user's app (not on this popup)
                    acc.runTask(res.task!!, pa)
                }
            }
            res.pressSend -> {
                JarvisAccess.instance?.pressSendSoon(pa)
                speak(reply.say.ifBlank { if (pa) "ਭੇਜ ਰਿਹਾ ਹਾਂ…" else "Sending…" }, false)
            }
            res.followUp != null -> {
                busy = true
                setMode(OrbView.Mode.THINKING)
                val data = res.followUp ?: ""
                Thread {
                    val a = try { brain.summarize(userText, data, pa, punjabiVoice) } catch (e: Exception) { Gemini.friendlyError(e, pa) }
                    main.post { busy = false; speak(a, false) }
                }.start()
            }
            else -> {
                val spoken = listOfNotNull(reply.say.takeIf { it.isNotBlank() }, res.message).joinToString(" ")
                val again = !failed && type == "none" // keep the conversation going until you stop talking
                speak(spoken.ifBlank { "…" }, again)
            }
        }
    }

    // -------------------------------------------------------------- voice out

    override fun onInit(status: Int) {
        val t = tts ?: return
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) return
        punjabiVoice = t.isLanguageAvailable(java.util.Locale("pa", "IN")) >= TextToSpeech.LANG_AVAILABLE
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post { setMode(OrbView.Mode.SPEAKING) } }
            override fun onDone(id: String?) { main.post { afterSpeaking() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { afterSpeaking() } }
        })
    }

    private fun speak(text: String, again: Boolean) {
        showAnswer(text)
        listenAfter = again
        tts?.stop(); HdVoice.stop() // only one voice at a time
        if (HdVoice.usable(this)) {
            HdVoice.speak(this, text,
                onStart = { setMode(OrbView.Mode.SPEAKING) },
                onDone = { afterSpeaking() },
                onFail = { phoneSpeak(text) })
            return
        }
        phoneSpeak(text)
    }

    private fun phoneSpeak(text: String) {
        val t = tts
        if (!ttsReady || t == null) { afterSpeaking(); return }
        VoiceSettings.apply(this, t, text, punjabiVoice)
        t.setAudioAttributes(CarAudio.voiceAttributes(this))
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assist-" + System.currentTimeMillis())
    }

    private fun afterSpeaking() {
        setMode(OrbView.Mode.IDLE)
        if (closing) return
        if (listenAfter) { listenAfter = false; listen() }
        else main.postDelayed({ if (!listening && !busy && !(tts?.isSpeaking == true || HdVoice.speaking)) close() }, 2500)
    }

    override fun onDestroy() {
        WakeService.setAppVisible(false)
        recognizer?.destroy()
        CarAudio.micOff(this)
        // let the last sentence finish even if another app opened on top
        val t = tts
        if (t != null && t.isSpeaking) main.postDelayed({ t.shutdown() }, 6000) else t?.shutdown()
        super.onDestroy()
    }
}
