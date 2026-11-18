package com.mandeep.jarvis

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    // ---- views (rebuilt when the theme changes)
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var langBtn: TextView
    private lateinit var mic: MicButton
    private lateinit var sendBtn: GlyphView
    private lateinit var glow: View
    private lateinit var p: Ui.Palette
    private var uiSignature = ""

    /** Chat history shown on screen (kept so the UI can be rebuilt). */
    private data class Msg(val user: Boolean, val text: String)
    private val messages = ArrayList<Msg>()
    private var liveUserBubble: TextView? = null
    private var thinkingBubble: TextView? = null
    private var thinkingAnim: ObjectAnimator? = null
    private var mode = OrbView.Mode.IDLE

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var punjabiVoice = false
    private var listening = false
    private var busy = false

    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }
    private val brain by lazy { Brain(this) }
    private val actions by lazy { Actions(this) }

    private var punjabi: Boolean
        get() = prefs.getBoolean("punjabi", true)
        set(v) { prefs.edit().putBoolean("punjabi", v).apply() }

    private val keepTalking: Boolean get() = prefs.getBoolean("keep_talking", true)
    private val voiceSpeed: Float get() = prefs.getFloat("voice_speed", 1.0f)

    private fun dp(v: Int) = Ui.dp(this, v.toFloat())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setStatus(idleText())
        tts = TextToSpeech(this, this)

        // Tell the user (on screen) when Gemini is busy and Jarvis is retrying.
        Gemini.onRetry = { attempt, fallback ->
            runOnUiThread {
                val msg = if (punjabi) {
                    if (fallback) "ਸਰਵਰ ਬਿਜ਼ੀ ਹੈ, ਬੈਕਅੱਪ ਮਾਡਲ ਨਾਲ ਕੋਸ਼ਿਸ਼ ਕਰ ਰਿਹਾ ਹਾਂ..."
                    else "ਸਰਵਰ ਬਿਜ਼ੀ ਹੈ, ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰ ਰਿਹਾ ਹਾਂ... ($attempt/3)"
                } else {
                    if (fallback) "Server busy — trying the backup model…"
                    else "Server busy — retrying… ($attempt/3)"
                }
                if (busy) {
                    setStatus(msg)
                    thinkingBubble?.text = msg
                }
            }
        }

        if (brain.apiKey.isEmpty()) openSettings(SettingsActivity.PAGE_APIKEY)
        handleAssist(intent)
    }

    override fun onResume() {
        super.onResume()
        if (uiSignature != Ui.signature(this)) buildUi()
        updateLangButton()
        if (!busy && !listening && mode == OrbView.Mode.IDLE) setStatus(idleText())
        tts?.setSpeechRate(voiceSpeed)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (uiSignature != Ui.signature(this)) buildUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAssist(intent)
    }

    /** Opened from the side key / "Digital assistant" gesture: start listening right away. */
    private fun handleAssist(i: Intent?) {
        if (i?.action == Intent.ACTION_ASSIST) orb.postDelayed({ startListening() }, 400)
    }

    // ===================================================================== UI

    private fun buildUi() {
        p = Ui.palette(this)
        uiSignature = Ui.signature(this)
        Ui.setupWindow(this, p)
        val oldStatus = if (::status.isInitialized) status.text else ""
        // old bubble views are thrown away; they are re-made from [messages]
        liveUserBubble = null
        thinkingAnim?.cancel()
        thinkingAnim = null
        thinkingBubble = null

        val root = FrameLayout(this).apply { setBackgroundColor(p.bg) }

        // Gemini Live-style glow rising from the bottom while listening / speaking
        glow = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(
                    withAlpha(Ui.GEMINI_PURPLE, if (p.dark) 110 else 70),
                    withAlpha(Ui.GEMINI_BLUE, if (p.dark) 45 else 30),
                    Color.TRANSPARENT
                )
            )
            alpha = 0f
        }
        root.addView(glow, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.5f).toInt(), Gravity.BOTTOM))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // ---- top bar: language chip · Jarvis · settings
        val top = FrameLayout(this)
        langBtn = Ui.text(this, "", 14f, p.text, Ui.medium).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = Ui.pressable(
                Ui.rounded(p.surface, dp(18).toFloat()),
                Ui.rounded(p.surface2, dp(18).toFloat())
            )
            setOnClickListener {
                punjabi = !punjabi
                updateLangButton()
                setStatus(idleText())
            }
        }
        top.addView(langBtn, FrameLayout.LayoutParams(-2, dp(36), Gravity.START or Gravity.CENTER_VERTICAL))

        val title = GradientText(this).apply {
            text = "Jarvis"
            textSize = 24f
            typeface = Ui.medium
            gravity = Gravity.CENTER
        }
        top.addView(title, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        // keep the side buttons clickable above the centered title
        langBtn.bringToFront()

        val gear = GlyphView(this, Glyph.GEAR, p.text).apply {
            bgColor = p.surface
            circleBg = true
            glyphScale = 0.48f
            isClickable = true
            contentDescription = "Settings"
            setOnClickListener { openSettings(null) }
        }
        top.addView(gear, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(top, LinearLayout.LayoutParams(-1, dp(56)))

        status = Ui.text(this, oldStatus, 14f, p.muted).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }
        col.addView(status, LinearLayout.LayoutParams(-1, -2))

        // ---- the orb
        orb = OrbView(this).apply {
            dark = p.dark
            contentDescription = "Tap to talk"
            setOnClickListener { if (listening) stopListening() else startListening() }
            setOnLongClickListener {
                brain.clearHistory()
                messages.clear()
                chat.removeAllViews()
                toast(if (punjabi) "ਨਵੀਂ ਗੱਲਬਾਤ" else "New conversation")
                true
            }
        }
        col.addView(orb, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- chat bubbles
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            clipToPadding = false
        }
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        scroll.addView(chat, ViewGroup.LayoutParams(-1, -2))
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1.15f))

        // ---- bottom bar: [ text pill (send) ] (mic)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(10))
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(p.surface, dp(27).toFloat(), p.separator, if (p.dark) 0 else 1)
            setPadding(dp(20), 0, dp(6), 0)
            elevation = if (p.dark) 0f else dp(2).toFloat()
        }
        input = EditText(this).apply {
            hint = if (punjabi) "Jarvis nu puchho…" else "Ask Jarvis…"
            background = null
            textSize = 16f
            setTextColor(p.text)
            setHintTextColor(p.muted)
            typeface = Ui.regular
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(0, 0, 0, 0)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
            }
        }
        pill.addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        sendBtn = GlyphView(this, Glyph.SEND, Color.WHITE).apply {
            bgColor = p.accent
            circleBg = true
            glyphScale = 0.45f
            isClickable = true
            contentDescription = "Send"
            setOnClickListener { sendTyped() }
            scaleX = 0f; scaleY = 0f; visibility = View.GONE
        }
        pill.addView(sendBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { showSend(!s.isNullOrBlank()) }
        })
        bar.addView(pill, LinearLayout.LayoutParams(0, dp(54), 1f))

        mic = MicButton(this).apply {
            iconColor = p.text
            ringBg = p.surface
            contentDescription = "Talk"
            setOnClickListener { if (listening) stopListening() else startListening() }
        }
        bar.addView(mic, LinearLayout.LayoutParams(dp(62), dp(62)).apply { marginStart = dp(8) })
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))

        Ui.onInsets(root) { t, b -> col.setPadding(dp(16), t, dp(16), b) }
        setContentView(root)

        // restore the conversation (no animation)
        for (m in messages) addBubbleView(m.user, m.text, animate = false)
        updateLangButton()
        setMode(mode)
        if (busy) showThinking()
        scrollToEnd()
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun showSend(show: Boolean) {
        if (show == (sendBtn.visibility == View.VISIBLE)) return
        if (show) {
            sendBtn.visibility = View.VISIBLE
            sendBtn.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        } else {
            sendBtn.animate().scaleX(0f).scaleY(0f).setDuration(140)
                .withEndAction { sendBtn.visibility = View.GONE }.start()
        }
    }

    /** One place that switches the orb, mic button and bottom glow together. */
    private fun setMode(m: OrbView.Mode) {
        mode = m
        if (!::orb.isInitialized) return
        orb.mode = m
        mic.active = m == OrbView.Mode.LISTENING
        val target = when (m) {
            OrbView.Mode.LISTENING -> 1f
            OrbView.Mode.SPEAKING -> 0.8f
            OrbView.Mode.THINKING -> 0.45f
            OrbView.Mode.IDLE -> 0f
        }
        glow.animate().alpha(target).setDuration(450).start()
    }

    // ---------------------------------------------------------------- bubbles

    private fun bubbleBg(user: Boolean): GradientDrawable {
        val r = dp(20).toFloat()
        val small = dp(6).toFloat()
        return GradientDrawable().apply {
            setColor(if (user) p.accent else p.surface)
            // corners: TL, TR, BR, BL (x,y each) — the "tail" corner is smaller
            cornerRadii = if (user) floatArrayOf(r, r, r, r, small, small, r, r)
            else floatArrayOf(r, r, r, r, r, r, small, small)
        }
    }

    private fun addBubbleView(user: Boolean, text: String, animate: Boolean): TextView {
        val maxW = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        val tv = Ui.text(this, text, 16f, if (user) p.onAccent else p.text).apply {
            background = bubbleBg(user)
            setPadding(dp(15), dp(10), dp(15), dp(11))
            setLineSpacing(dp(2).toFloat(), 1f)
            maxWidth = maxW
            if (!user) setTextIsSelectable(true)
        }
        val rowView: View = if (user) tv else LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            val spark = GlyphView(context, Glyph.SPARKLE, Ui.GEMINI_PURPLE).apply { glyphScale = 0.75f }
            addView(spark, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8); bottomMargin = dp(4) })
            addView(tv, LinearLayout.LayoutParams(-2, -2))
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (user) Gravity.END else Gravity.START
            topMargin = dp(8)
        }
        chat.addView(rowView, lp)
        if (animate) {
            rowView.alpha = 0f
            rowView.translationY = dp(14).toFloat()
            rowView.animate().alpha(1f).translationY(0f).setDuration(260)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        scrollToEnd()
        return tv
    }

    private fun addMessage(user: Boolean, text: String): TextView {
        messages.add(Msg(user, text))
        while (messages.size > 80) {
            messages.removeAt(0)
            if (chat.childCount > 0) chat.removeViewAt(0)
        }
        return addBubbleView(user, text, animate = true)
    }

    private fun scrollToEnd() {
        scroll.post { scroll.smoothScrollTo(0, chat.height) }
    }

    private fun showThinking() {
        if (thinkingBubble != null) return
        val tv = addBubbleView(false, "•  •  •", animate = true)
        thinkingBubble = tv
        thinkingAnim = ObjectAnimator.ofFloat(tv, "alpha", 1f, 0.45f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun hideThinking() {
        thinkingAnim?.cancel()
        thinkingAnim = null
        val tv = thinkingBubble ?: return
        thinkingBubble = null
        val row = tv.parent as? View ?: return
        (row.parent as? ViewGroup)?.removeView(row)
    }

    /** Shows what the mic is hearing in a user bubble that updates live. */
    private fun showLiveText(text: String) {
        val b = liveUserBubble
        if (b == null) liveUserBubble = addBubbleView(true, text, animate = true)
        else { b.text = text; scrollToEnd() }
    }

    private fun finishUserText(text: String) {
        val b = liveUserBubble
        liveUserBubble = null
        if (b != null) {
            b.text = text
            messages.add(Msg(true, text))
        } else addMessage(true, text)
    }

    private fun dropLiveText() {
        val b = liveUserBubble ?: return
        liveUserBubble = null
        chat.removeView(b)
    }

    // ================================================================ voice out

    override fun onInit(result: Int) {
        val engine = tts ?: return
        if (result != TextToSpeech.SUCCESS) {
            setStatus("Voice engine nahi chaliya")
            return
        }
        ttsReady = true
        punjabiVoice = engine.isLanguageAvailable(Locale("pa", "IN")) >= TextToSpeech.LANG_AVAILABLE
        engine.setSpeechRate(voiceSpeed)
        engine.setPitch(0.95f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread { setMode(OrbView.Mode.SPEAKING) }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread { afterSpeaking() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { afterSpeaking() }
            }
        })
        if (prefs.getBoolean("greeted", false).not()) {
            prefs.edit().putBoolean("greeted", true).apply()
            say(
                if (punjabi && punjabiVoice) "ਸਤ ਸ੍ਰੀ ਅਕਾਲ ਸਰ! ਮੈਂ ਜਾਰਵਿਸ ਹਾਂ। ਗੋਲੇ ਨੂੰ ਛੂਹੋ ਤੇ ਬੋਲੋ।"
                else "Sat Sri Akal, Sir. Jarvis online. Tap the orb and speak.",
                listenAfter = false
            )
        }
    }

    private var listenAfterSpeech = false

    private fun say(text: String, listenAfter: Boolean) {
        hideThinking()
        if (text.isNotBlank()) addMessage(false, text)
        val engine = tts
        if (!ttsReady || engine == null || text.isBlank()) {
            setMode(OrbView.Mode.IDLE)
            setStatus(idleText())
            return
        }
        val locale = when {
            text.any { it in '਀'..'੿' } && punjabiVoice -> Locale("pa", "IN")
            text.any { it in 'ऀ'..'ॿ' } -> Locale("hi", "IN")
            else -> Locale("en", "IN")
        }
        engine.setLanguage(locale)
        engine.setSpeechRate(voiceSpeed)
        listenAfterSpeech = listenAfter
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-" + System.currentTimeMillis())
        setStatus(if (punjabi) "ਬੋਲ ਰਿਹਾ ਹਾਂ…" else "Speaking…")
    }

    private fun afterSpeaking() {
        setMode(OrbView.Mode.IDLE)
        setStatus(idleText())
        if (listenAfterSpeech && keepTalking) {
            listenAfterSpeech = false
            startListening()
        }
    }

    // ================================================================= voice in

    private fun startListening() {
        if (busy) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Is phone te Google speech recognition nahi mili. Google app update karo.", false)
            return
        }
        tts?.stop()
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
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        listening = true
        setMode(OrbView.Mode.LISTENING)
        setStatus(if (punjabi) "ਸੁਣ ਰਿਹਾ ਹਾਂ… ਬੋਲੋ" else "Listening…")
        rec.startListening(i)
    }

    private fun stopListening() {
        recognizer?.stopListening()
        listening = false
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val lvl = (rmsdB + 2f) / 12f
            orb.level = lvl
            mic.level = lvl.coerceIn(0f, 1f)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            setMode(OrbView.Mode.THINKING)
        }

        override fun onError(error: Int) {
            listening = false
            setMode(OrbView.Mode.IDLE)
            dropLiveText()
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    if (punjabi) "ਕੁਝ ਸੁਣਿਆ ਨਹੀਂ — ਦੁਬਾਰਾ ਛੂਹੋ" else "Didn't catch that — tap again"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    if (punjabi) "ਇੰਟਰਨੈੱਟ ਚੈੱਕ ਕਰੋ" else "Check the internet"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic di permission deo"
                12, 13 -> // language not supported / unavailable
                    "Eh bhasha phone te nahi: ${langBtn.text} badal ke dekho"
                else -> idleText()
            }
            setStatus(msg)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                dropLiveText()
                setMode(OrbView.Mode.IDLE)
                setStatus(idleText())
            } else {
                handle(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) showLiveText(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ==================================================================== think

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        handle(text, fromVoice = false)
    }

    private fun handle(text: String, fromVoice: Boolean = true) {
        if (busy) return
        busy = true
        finishUserText(text)
        showThinking()
        setMode(OrbView.Mode.THINKING)
        setStatus(if (punjabi) "ਸੋਚ ਰਿਹਾ ਹਾਂ…" else "Thinking…")
        val pa = punjabi
        val paVoice = punjabiVoice
        val info = phoneInfo()

        Thread {
            val result: Reply = try {
                brain.ask(text, pa, paVoice, info)
            } catch (e: Exception) {
                Reply(Gemini.friendlyError(e, pa), null)
            }
            runOnUiThread {
                busy = false
                act(text, result, pa, paVoice, fromVoice)
            }
        }.start()
    }

    /** Runs the brain's chosen action, asking for a yes first when it sends something to someone. */
    private fun act(userText: String, result: Reply, pa: Boolean, paVoice: Boolean, fromVoice: Boolean) {
        val action = result.action
        val first = actions.run(action, pa, confirmed = false)
        val question = first.confirm
        if (question == null) {
            complete(userText, result, first, pa, paVoice, fromVoice)
            return
        }
        say(if (pa) "ਭੇਜ ਦਿਆਂ?" else "Shall I send it?", listenAfter = false)
        AlertDialog.Builder(this, Ui.dialogTheme(this))
            .setTitle(if (pa) "ਭੇਜਣਾ ਹੈ?" else "Send this?")
            .setMessage(question)
            .setPositiveButton(if (pa) "ਹਾਂ, ਭੇਜੋ" else "Yes, send") { _, _ ->
                complete(userText, result, actions.run(action, pa, confirmed = true), pa, paVoice, fromVoice)
            }
            .setNegativeButton(if (pa) "ਨਹੀਂ" else "No") { _, _ ->
                say(if (pa) "ਠੀਕ ਹੈ, ਨਹੀਂ ਭੇਜਿਆ।" else "Okay, not sent.", listenAfter = false)
            }
            .setCancelable(false)
            .show()
    }

    private fun complete(userText: String, result: Reply, res: ActionResult, pa: Boolean, paVoice: Boolean, fromVoice: Boolean) {
        val task = res.task
        val data = res.followUp
        when {
            task != null && task.isNotBlank() -> startTask(task, result.say, pa)
            data != null -> {
                busy = true
                showThinking()
                setMode(OrbView.Mode.THINKING)
                setStatus(if (pa) "ਪੜ੍ਹ ਰਿਹਾ ਹਾਂ…" else "Reading…")
                Thread {
                    val answer = try {
                        brain.summarize(userText, data, pa, paVoice)
                    } catch (e: Exception) {
                        if (e is GeminiException || e is java.io.IOException) Gemini.friendlyError(e, pa)
                        else if (pa) "ਮਾਫ਼ ਕਰਨਾ, ਪੜ੍ਹ ਨਹੀਂ ਸਕਿਆ।" else "Sorry, I couldn't read them."
                    }
                    runOnUiThread { busy = false; say(answer, listenAfter = false) }
                }.start()
            }
            else -> {
                val spoken = listOfNotNull(result.say.takeIf { it.isNotBlank() }, res.message).joinToString(" ")
                val type = result.action?.optString("type", "none") ?: "none"
                // Keep the conversation going if Jarvis asked a question (and we didn't leave the app).
                val again = fromVoice && type == "none" && spoken.trim().let { it.endsWith("?") || it.endsWith("？") }
                say(spoken.ifBlank { "…" }, listenAfter = again)
            }
        }
    }

    /** Hands a goal to the screen-control agent, then steps out of the way. */
    private fun startTask(goal: String, sayText: String, pa: Boolean) {
        val svc = JarvisAccess.instance
        if (svc == null) {
            say(
                if (pa) "ਇਸ ਕੰਮ ਲਈ ਪਹਿਲਾਂ ਸਕਰੀਨ ਕੰਟਰੋਲ ਚਾਲੂ ਕਰੋ। ਮੈਂ ਸੈਟਿੰਗ ਖੋਲ੍ਹ ਰਿਹਾ ਹਾਂ।"
                else "Turn on Screen control first, Sir. Opening the setting.",
                listenAfter = false
            )
            orb.postDelayed({ openAccessibility() }, 2500)
            return
        }
        say(sayText.ifBlank { if (pa) "ਹੁਣੇ ਕਰਦਾ ਹਾਂ।" else "On it, Sir." }, listenAfter = false)
        orb.postDelayed({
            moveTaskToBack(true)
            svc.runTask(goal, pa)
        }, 1200)
    }

    private fun phoneInfo(): String {
        val bm = getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        return "Battery: $level%" + (if (charging) " (charging)." else ".")
    }

    // ================================================================= settings

    private fun openSettings(page: String?) {
        val i = Intent(this, SettingsActivity::class.java)
        if (page != null) i.putExtra(SettingsActivity.EXTRA_PAGE, page)
        startActivity(i)
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Installed apps → Jarvis screen control → ON", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ===================================================================== misc

    private fun updateLangButton() {
        if (!::langBtn.isInitialized) return
        langBtn.text = if (punjabi) "ਪੰਜਾਬੀ" else "EN"
        input.hint = if (punjabi) "Jarvis nu puchho…" else "Ask Jarvis…"
    }

    private fun idleText() =
        if (punjabi) "ਗੋਲੇ ਨੂੰ ਛੂਹੋ ਜਾਂ ਮਾਈਕ ਦਬਾ ਕੇ ਬੋਲੋ" else "Tap the orb or the mic and speak"

    private fun setStatus(s: String) {
        status.text = s
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
            else setStatus("Mic di permission bina main sun nahi sakda")
        }
    }

    override fun onDestroy() {
        Gemini.onRetry = null
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_MIC = 7
    }
}
