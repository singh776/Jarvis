package com.mandeep.jarvis

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentValues
import android.content.res.Configuration
import android.net.Uri
import android.provider.MediaStore
import android.widget.ImageView
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

    private var showingCrash = false

    // ---- views (rebuilt when the theme changes)
    private lateinit var orb: SparkleView
    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var langBtn: TextView
    private lateinit var mic: MicButton
    private lateinit var sendBtn: GlyphView
    private lateinit var glow: GlowView
    private lateinit var hero: LinearLayout
    private lateinit var greeting: TextView
    private lateinit var liveWave: WaveBar
    private lateinit var liveBtn: LiveButton
    private lateinit var suggestions: LinearLayout
    private lateinit var rootFrame: FrameLayout

    // ---- Live screen (full screen voice chat, like Gemini Live)
    private var liveLayer: FrameLayout? = null
    private var liveText: TextView? = null
    private var livePill: LivePill? = null
    private var liveGlow: GlowView? = null
    private var liveSpark: SparkleView? = null
    private var liveMicBtn: GlyphView? = null
    private var liveMode = false
    private var liveMuted = false
    private var liveMisses = 0

    // status bar / nav bar sizes (the main screen gets them; the Live screen and chat list reuse them)
    private var insetTop = 0
    private var insetBottom = 0
    private val overlayPads = ArrayList<(Int, Int) -> Unit>()

    /** Keeps an overlay's content clear of the status bar (top) and the nav bar (bottom). */
    private fun padForBars(apply: (Int, Int) -> Unit) {
        val top = if (insetTop > 0) insetTop else statusBarFallback()
        apply(top, insetBottom)
        overlayPads.add(apply)
    }

    private fun statusBarFallback(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
    }

    private var liveChat: LinearLayout? = null
    private var liveScroll: ScrollView? = null
    private var livePending: TextView? = null
    private var drawer: FrameLayout? = null
    /** Id of the conversation on screen (saved in the chat list). */
    private var chatId: String = ChatStore.newId()

    /** Chat / Task switch under the suggestions. Task = "do it on my phone". */
    private var taskMode = false
    private lateinit var p: Ui.Palette
    private var uiSignature = ""

    /** Chat history shown on screen (kept so the UI can be rebuilt). */
    private data class Msg(val user: Boolean, val text: String)
    private val messages = ArrayList<Msg>()
    private var liveUserBubble: TextView? = null
    private var thinkingBubble: TextView? = null
    private var thinkingAnim: ObjectAnimator? = null
    private var mode = OrbView.Mode.IDLE

    // ---- "+" attachments (photo / camera / screen / file)
    private var attachment: Attachment? = null
    private lateinit var attachChip: LinearLayout
    private lateinit var attachThumb: ImageView
    private lateinit var attachLabel: TextView

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
        if (showLastCrash()) return
        try {
            startUp()
        } catch (e: Throwable) {
            showingCrash = true
            Ui.showError(this, android.util.Log.getStackTraceString(e))
        }
    }

    private fun startUp() {
        brain.clearHistory() // every time the app starts: a new chat (old ones are in the ≡ list)
        buildUi()
        setStatus(idleText())
        tts = TextToSpeech(this, this)

        // Tell the user (on screen) when Gemini is busy and Jarvis is retrying.
        Gemini.onRetry = { attempt, fallback ->
            runOnUiThread {
                val msg = if (fallback) "Server busy — trying the backup model…"
                    else "Server busy — retrying… ($attempt/3)"
                if (busy) {
                    setStatus(msg)
                    thinkingBubble?.text = msg
                }
            }
        }

        if (brain.apiKey.isEmpty()) openSettings(SettingsActivity.PAGE_APIKEY)
        handleAssist(intent)
        handleScreenshot(intent)
    }

    override fun onResume() {
        super.onResume()
        if (showingCrash) return
        if (uiSignature != Ui.signature(this)) buildUi()
        updateLangButton()
        if (!busy && !listening && mode == OrbView.Mode.IDLE) setStatus(idleText())
        tts?.setSpeechRate(voiceSpeed)
        // been away a while (and not coming back from camera / photos / screen) → fresh chat
        val away = System.currentTimeMillis() - lastPausedAt
        if (lastPausedAt > 0 && away > 2 * 60_000 && !keepChatOnce && !busy) newConversation()
        keepChatOnce = false
        WakeService.setAppVisible(true)
        WakeService.start(this)
        askNotificationsOnce()
        autoListenSoon()
    }

    private var lastPausedAt = 0L
    private var keepChatOnce = false

    /** Clears the screen and the short chat history. Long-term memory (Settings → Memory) stays. */
    private fun newConversation() {
        saveChat()
        chatId = ChatStore.newId()
        brain.clearHistory()
        messages.clear()
        liveUserBubble = null
        thinkingAnim?.cancel(); thinkingAnim = null; thinkingBubble = null
        if (::chat.isInitialized) { chat.removeAllViews(); updateHero() }
        attachment = null
        refreshAttachChip()
    }

    private fun askNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33 || prefs.getBoolean("asked_notif", false)) return
        if (!WakeService.enabled(this)) return
        prefs.edit().putBoolean("asked_notif", true).apply()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 31)
    }

    override fun onPause() {
        super.onPause()
        if (showingCrash) return
        lastPausedAt = System.currentTimeMillis()
        saveChat()
        WakeService.setAppVisible(false)
        orb.removeCallbacks(autoListenTask)
        if (listening) {
            recognizer?.cancel()
            listening = false
            dropLiveText()
            setMode(OrbView.Mode.IDLE)
            setStatus(idleText())
        }
    }

    private val autoListen: Boolean get() = prefs.getBoolean("auto_listen", false)

    private val autoListenTask = Runnable {
        if (!busy && !listening && mode == OrbView.Mode.IDLE && tts?.isSpeaking != true && hasWindowFocus()) {
            startListening()
        }
    }

    /** Opens the mic by itself when the app comes to the screen (Settings → Auto Listen). */
    private fun autoListenSoon() {
        if (!autoListen || brain.apiKey.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        orb.removeCallbacks(autoListenTask)
        orb.postDelayed(autoListenTask, 700)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (showingCrash) return
        if (uiSignature != Ui.signature(this)) buildUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (showingCrash) return
        handleAssist(intent)
        handleScreenshot(intent)
    }

    /** Opened from the side key / "Digital assistant" gesture: start listening right away. */
    private fun handleAssist(i: Intent?) {
        if (i?.action == Intent.ACTION_ASSIST) {
            if (!i.hasExtra(EXTRA_SCREENSHOT)) newConversation()
            if (i.getBooleanExtra(EXTRA_OPEN_LIVE, false)) {
                i.removeExtra(EXTRA_OPEN_LIVE)
                orb.postDelayed({ openLive() }, 300)
            } else orb.postDelayed({ startListening() }, 400)
        }
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
        rootFrame = root
        liveLayer = null
        drawer = null
        overlayPads.clear()

        // Gemini Live-style glow rising from the bottom while listening / speaking
        glow = GlowView(this).apply { dark = p.dark }
        root.addView(glow, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.45f).toInt(), Gravity.BOTTOM))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // ---- top bar (Gemini style):  ≡   Singh AI  Punjabi ⌄            ✎   (M)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val menu = GlyphView(this, Glyph.MENU, p.text).apply {
            glyphScale = 0.5f
            isClickable = true
            contentDescription = "Chats"
            setOnClickListener { openDrawer() }
        }
        top.addView(menu, LinearLayout.LayoutParams(dp(44), dp(44)))
        val title = Ui.text(this, "Singh AI", 22f, p.text, Ui.regular).apply {
            setPadding(dp(8), 0, dp(6), 0)
        }
        top.addView(title, LinearLayout.LayoutParams(-2, -2))
        langBtn = Ui.text(this, "", 22f, p.muted, Ui.regular).apply {
            contentDescription = "Conversation language"
            setOnClickListener {
                punjabi = !punjabi
                updateLangButton()
                updateSuggestions()
                setStatus(if (punjabi) "Talking in Punjabi" else "Talking in English")
            }
        }
        top.addView(langBtn, LinearLayout.LayoutParams(0, -2, 1f))
        val newChat = GlyphView(this, Glyph.PEN, p.text).apply {
            glyphScale = 0.55f
            isClickable = true
            contentDescription = "New chat"
            setOnClickListener {
                newConversation()
                toast("New chat")
            }
        }
        top.addView(newChat, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
        val avatar = Ui.text(this, avatarLetter(), 18f, Color.WHITE, Ui.medium).apply {
            gravity = Gravity.CENTER
            background = Ui.rounded(Color.parseColor("#C2185B"), dp(20).toFloat())
            contentDescription = "Settings"
            setOnClickListener { openSettings(null) }
        }
        top.addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)))
        col.addView(top, LinearLayout.LayoutParams(-1, dp(60)))

        status = Ui.text(this, oldStatus, 14f, p.muted).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }
        col.addView(status, LinearLayout.LayoutParams(-1, -2))

        // ---- middle: welcome screen (sparkle + big greeting + suggestions) or the chat
        val middle = FrameLayout(this)
        hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        hero.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        orb = SparkleView(this).apply {
            dark = p.dark
            contentDescription = "Tap to talk"
            setOnClickListener { openLive() }
        }
        hero.addView(orb, LinearLayout.LayoutParams(dp(64), dp(64)))
        greeting = Ui.text(this, "", 34f, p.text, Ui.regular).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(18), dp(16), 0)
            setLineSpacing(0f, 1.05f)
        }
        hero.addView(greeting, LinearLayout.LayoutParams(-1, -2))
        hero.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        suggestions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        hero.addView(suggestions, LinearLayout.LayoutParams(-1, -2))
        middle.addView(hero, FrameLayout.LayoutParams(-1, -1))

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
        middle.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        col.addView(middle, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- attachment chip (shows the photo / screenshot / file you're asking about)
        attachChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(p.surface, dp(16).toFloat(), p.separator, if (p.dark) 0 else 1)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            visibility = View.GONE
        }
        attachThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.rounded(p.surface2, dp(10).toFloat())
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        attachChip.addView(attachThumb, LinearLayout.LayoutParams(dp(48), dp(48)))
        attachLabel = Ui.text(this, "", 14f, p.text, Ui.medium).apply {
            setPadding(dp(10), 0, dp(6), 0)
            maxLines = 2
        }
        attachChip.addView(attachLabel, LinearLayout.LayoutParams(0, -2, 1f))
        val chipClose = GlyphView(this, Glyph.CLOSE, p.muted).apply {
            glyphScale = 0.45f
            isClickable = true
            contentDescription = "Remove"
            setOnClickListener { setAttachment(null) }
        }
        attachChip.addView(chipClose, LinearLayout.LayoutParams(dp(36), dp(36)))
        col.addView(attachChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        // ---- Chat | Task switch
        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(4))
        }
        val chatTab = Ui.text(this, "Chat", 17f, p.text, Ui.regular).apply { gravity = Gravity.CENTER }
        val taskTab = Ui.text(this, "Task", 17f, p.text, Ui.regular).apply { gravity = Gravity.CENTER }
        fun paintTabs() {
            chatTab.background = if (!taskMode) Ui.rounded(p.surface, dp(22).toFloat()) else null
            taskTab.background = if (taskMode) Ui.rounded(p.surface, dp(22).toFloat()) else null
            chatTab.elevation = if (!taskMode && !p.dark) dp(1).toFloat() else 0f
            taskTab.elevation = if (taskMode && !p.dark) dp(1).toFloat() else 0f
        }
        chatTab.setOnClickListener { taskMode = false; paintTabs() }
        taskTab.setOnClickListener { taskMode = true; paintTabs() }
        paintTabs()
        modes.addView(chatTab, LinearLayout.LayoutParams(dp(92), dp(44)).apply { marginEnd = dp(6) })
        modes.addView(taskTab, LinearLayout.LayoutParams(dp(92), dp(44)))
        col.addView(modes, LinearLayout.LayoutParams(-1, -2))

        // ---- bottom pill:  [ +   Ask Singh AI…        🎤  (|||) ]
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(p.surface, dp(34).toFloat(), p.separator, if (p.dark) 0 else 1)
            setPadding(dp(8), 0, dp(8), 0)
            elevation = if (p.dark) 0f else dp(3).toFloat()
        }
        val plus = GlyphView(this, Glyph.PLUS, p.text).apply {
            glyphScale = 0.5f
            isClickable = true
            contentDescription = "Add photo, camera, screen or file"
            setOnClickListener { showAttachSheet() }
        }
        pill.addView(plus, LinearLayout.LayoutParams(dp(48), dp(48)))
        input = EditText(this).apply {
            hint = "Ask Singh AI"
            background = null
            textSize = 17f
            setTextColor(p.text)
            setHintTextColor(p.muted)
            typeface = Ui.regular
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(4), 0, 0, 0)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
            }
        }
        pill.addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        // while listening / speaking the text box turns into a flowing voice bar
        liveWave = WaveBar(this).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener { if (listening) stopListening() else tts?.stop() }
        }
        pill.addView(liveWave, LinearLayout.LayoutParams(0, -1, 1f))
        mic = MicButton(this).apply {
            iconColor = p.text
            ringBg = Color.TRANSPARENT
            contentDescription = "Talk"
            setOnClickListener { micTapped() }
        }
        pill.addView(mic, LinearLayout.LayoutParams(dp(48), dp(48)))
        liveBtn = LiveButton(this).apply {
            dark = p.dark
            contentDescription = "Live voice chat"
            setOnClickListener { openLive() }
        }
        pill.addView(liveBtn, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(4) })
        sendBtn = GlyphView(this, Glyph.SEND, Color.WHITE).apply {
            bgColor = p.accent
            circleBg = true
            glyphScale = 0.45f
            isClickable = true
            contentDescription = "Send"
            setOnClickListener { sendTyped() }
            scaleX = 0f; scaleY = 0f; visibility = View.GONE
        }
        pill.addView(sendBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { showSend(!s.isNullOrBlank() || attachment != null) }
        })
        bar.addView(pill, LinearLayout.LayoutParams(0, dp(68), 1f))
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))
        updateSuggestions()

        Ui.onInsets(root) { t, b ->
            col.setPadding(dp(16), t, dp(16), b)
            insetTop = t; insetBottom = b
            overlayPads.forEach { it(t, b) }
        }
        setContentView(root)

        refreshAttachChip()

        // restore the conversation (no animation)
        for (m in messages) addBubbleView(m.user, m.text, animate = false)
        updateHero(animate = false)
        updateLangButton()
        setMode(mode)
        if (busy) showThinking()
        scrollToEnd()
        if (liveMode) addLiveLayer(animate = false)
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun showSend(show: Boolean) {
        if (show == (sendBtn.visibility == View.VISIBLE)) return
        liveBtn.visibility = if (show) View.GONE else View.VISIBLE
        mic.visibility = if (show) View.GONE else View.VISIBLE
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
        glow.mode = m
        livePill?.mode = m
        liveGlow?.mode = m
        liveSpark?.mode = m
        mic.active = m == OrbView.Mode.LISTENING
        val live = m == OrbView.Mode.LISTENING || m == OrbView.Mode.SPEAKING
        liveWave.mode = m
        if (live && liveWave.visibility != View.VISIBLE) {
            input.visibility = View.GONE
            liveWave.alpha = 0f
            liveWave.visibility = View.VISIBLE
            liveWave.animate().alpha(1f).setDuration(200).start()
        } else if (!live && liveWave.visibility == View.VISIBLE) {
            liveWave.visibility = View.GONE
            input.visibility = View.VISIBLE
        }
    }

    /** Welcome screen when the chat is empty, chat when there are messages. */
    private fun updateHero(animate: Boolean = true) {
        if (!::hero.isInitialized) return
        val empty = chat.childCount == 0
        greeting.text = greetingText()
        if (empty && hero.visibility != View.VISIBLE) {
            hero.visibility = View.VISIBLE
            if (animate) { hero.alpha = 0f; hero.animate().alpha(1f).setDuration(300).start() }
        } else if (!empty && hero.visibility == View.VISIBLE) {
            if (animate) hero.animate().alpha(0f).setDuration(200).withEndAction { hero.visibility = View.GONE; hero.alpha = 1f }.start()
            else hero.visibility = View.GONE
        }
    }

    private fun greetingText(): String {
        val name = Memory.all(this).firstNotNullOfOrNull { f ->
            Regex("name is ([\\p{L}]+)", RegexOption.IGNORE_CASE).find(f)
                ?.takeIf { f.contains("user", true) && !f.contains("mother", true) && !f.contains("wife", true) }
                ?.groupValues?.get(1)
        }
        return when {
            punjabi && name != null -> "ਸਤ ਸ੍ਰੀ ਅਕਾਲ $name,\nਦੱਸੋ ਕੀ ਕਰੀਏ?"
            punjabi -> "ਸਤ ਸ੍ਰੀ ਅਕਾਲ,\nਦੱਸੋ ਕੀ ਕਰੀਏ?"
            name != null -> "Hi $name,\nwhat's next?"
            else -> "Any new ideas\nto explore?"
        }
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
        updateHero()
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
        addLiveLine(user, text)
        saveChatSoon()
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
        updateHero()
    }

    /** Shows what the mic is hearing in a user bubble that updates live. */
    private fun showLiveText(text: String) {
        if (liveMode) showLivePending(text)
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
            addLiveLine(true, text)
            saveChatSoon()
        } else addMessage(true, text)
    }

    // ============================================================ chat list (≡)

    private val saveTask = Runnable { saveChat() }

    private fun saveChatSoon() {
        if (!::orb.isInitialized) return
        orb.removeCallbacks(saveTask)
        orb.postDelayed(saveTask, 600)
    }

    /** Saves the conversation on screen into the chat list. */
    private fun saveChat() {
        if (messages.isEmpty()) return
        try { ChatStore.save(this, chatId, messages.map { it.user to it.text }, brain.exportHistory()) } catch (e: Exception) {}
    }

    /** Opens an old conversation so you can read it and keep talking in it. */
    private fun openChat(id: String) {
        val c = ChatStore.get(this, id) ?: return
        saveChat()
        chatId = c.id
        messages.clear()
        liveUserBubble = null
        thinkingAnim?.cancel(); thinkingAnim = null; thinkingBubble = null
        chat.removeAllViews()
        for ((u, t) in ChatStore.messages(c)) {
            messages.add(Msg(u, t))
            addBubbleView(u, t, animate = false)
        }
        brain.importHistory(c.hist)
        updateHero(animate = false)
        scrollToEnd()
    }

    /** Gemini-style side panel with all saved chats. */
    private fun openDrawer() {
        if (drawer != null) return
        saveChat()
        val w = (resources.displayMetrics.widthPixels * 0.84f).toInt()
        val layer = FrameLayout(this)
        val dim = View(this).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            alpha = 0f
            setOnClickListener { closeDrawer() }
        }
        layer.addView(dim, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            isClickable = true
            elevation = dp(12).toFloat()
        }
        layer.addView(panel, FrameLayout.LayoutParams(w, -1, Gravity.START))

        fun row(g: Glyph, label: String, onTap: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Ui.pressable(Ui.rounded(Color.TRANSPARENT, dp(24).toFloat()), Ui.rounded(p.surface2, dp(24).toFloat()))
            isClickable = true
            setOnClickListener { onTap() }
            addView(GlyphView(context, g, p.text).apply { glyphScale = 0.5f }, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(Ui.text(context, label, 17f, p.text, Ui.medium).apply { setPadding(dp(14), 0, 0, 0) })
        }

        val head = Ui.text(this, "Singh AI", 24f, p.text, Ui.regular).apply { setPadding(dp(20), dp(16), dp(20), dp(12)) }
        panel.addView(head)
        panel.addView(row(Glyph.PEN, if (punjabi) "ਨਵੀਂ ਚੈਟ" else "New chat") { closeDrawer(); newConversation() },
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), 0, dp(8), 0) })
        panel.addView(Ui.text(this, if (punjabi) "ਪੁਰਾਣੀਆਂ ਚੈਟਾਂ" else "Recent", 14f, p.muted, Ui.medium).apply {
            setPadding(dp(22), dp(18), dp(22), dp(6))
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
        val chats = ChatStore.all(this)
        if (chats.isEmpty()) list.addView(Ui.text(this, if (punjabi) "ਹਾਲੇ ਕੋਈ ਚੈਟ ਨਹੀਂ" else "No chats yet", 15f, p.muted).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
        })
        for (c in chats) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                val sel = c.id == chatId
                background = Ui.pressable(
                    Ui.rounded(if (sel) p.surface else Color.TRANSPARENT, dp(20).toFloat()),
                    Ui.rounded(p.surface2, dp(20).toFloat())
                )
                isClickable = true
                setOnClickListener { closeDrawer(); openChat(c.id) }
                setOnLongClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity, Ui.dialogTheme(this@MainActivity))
                        .setTitle(if (punjabi) "ਇਹ ਚੈਟ ਮਿਟਾਈਏ?" else "Delete this chat?")
                        .setMessage(c.title)
                        .setPositiveButton(if (punjabi) "ਮਿਟਾਓ" else "Delete") { _, _ ->
                            ChatStore.delete(this@MainActivity, c.id)
                            if (c.id == chatId) { chatId = ChatStore.newId() }
                            (parent as? ViewGroup)?.removeView(this)
                        }
                        .setNegativeButton(if (punjabi) "ਰਹਿਣ ਦਿਓ" else "Cancel", null)
                        .show()
                    true
                }
            }
            item.addView(Ui.text(this, c.title, 16f, p.text, Ui.regular).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            item.addView(Ui.text(this, ChatStore.ago(c.time), 12f, p.muted))
            list.addView(item, LinearLayout.LayoutParams(-1, -2))
        }
        val sc = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
        panel.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(row(Glyph.GEAR, if (punjabi) "ਸੈਟਿੰਗ" else "Settings") { closeDrawer(); openSettings(null) },
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), 0, dp(8), dp(8)) })

        padForBars { t, b -> panel.setPadding(0, t, 0, b) }
        rootFrame.addView(layer, FrameLayout.LayoutParams(-1, -1))
        drawer = layer
        panel.translationX = -w.toFloat()
        panel.animate().translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator(2f)).withLayer().start()
        dim.animate().alpha(1f).setDuration(280).start()
    }

    private fun closeDrawer() {
        val layer = drawer ?: return
        drawer = null
        val panel = layer.getChildAt(1)
        val dim = layer.getChildAt(0)
        dim.animate().alpha(0f).setDuration(200).start()
        panel.animate().translationX(-panel.width.toFloat()).setDuration(220)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f)).withLayer()
            .withEndAction { rootFrame.removeView(layer); overlayPads.clear() }.start()
    }

    private fun dropLiveText() {
        val b = liveUserBubble ?: return
        liveUserBubble = null
        chat.removeView(b)
        updateHero()
    }

    // ================================================================ voice out

    override fun onInit(result: Int) {
        val engine = tts ?: return
        if (result != TextToSpeech.SUCCESS) {
            setStatus("Voice engine didn't start")
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
                if (punjabi && punjabiVoice) "ਸਤ ਸ੍ਰੀ ਅਕਾਲ ਸਰ! ਮੈਂ ਸਿੰਘ AI ਹਾਂ। ਬੋਲੋ, ਕੀ ਕੰਮ ਕਰਾਂ?"
                else "Sat Sri Akal, Sir. Singh AI online. Tap the orb and speak.",
                listenAfter = autoListen
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
        VoiceSettings.apply(this, engine, text, punjabiVoice)
        engine.setAudioAttributes(CarAudio.voiceAttributes(this))
        listenAfterSpeech = listenAfter
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-" + System.currentTimeMillis())
        setStatus("Speaking…")
    }

    private fun afterSpeaking() {
        setMode(OrbView.Mode.IDLE)
        setStatus(idleText())
        if (liveMode) {
            // Live: keep the conversation going hands-free
            listenAfterSpeech = false
            if (!liveMuted && hasWindowFocus()) orb.postDelayed({ if (liveMode && !busy && !listening) startListening() }, 250)
            return
        }
        if (listenAfterSpeech && keepTalking) {
            listenAfterSpeech = false
            startListening()
        }
    }

    // ================================================================= voice in

    private fun startListening() {
        if (busy || listening) return
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
            // stop listening sooner after you finish talking → faster answers
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        listening = true
        setMode(OrbView.Mode.LISTENING)
        setStatus("Listening…")
        rec.startListening(i)
    }

    /** Mic button: speaking → stop & listen · listening → done · idle → listen. */
    private fun micTapped() {
        orb.removeCallbacks(autoListenTask)
        when {
            busy -> toast(if (punjabi) "ਸੋਚ ਰਿਹਾ ਹਾਂ…" else "Thinking…")
            tts?.isSpeaking == true -> { tts?.stop(); listenAfterSpeech = false; setMode(OrbView.Mode.IDLE); startListening() }
            listening -> stopListening()
            else -> startListening()
        }
    }

    private var busyRetry = 0

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
            glow.level = lvl
            liveWave.level = lvl
            mic.level = lvl.coerceIn(0f, 1f)
            livePill?.level = lvl
            liveGlow?.level = lvl
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
                    "Didn't catch that — tap again"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Check the internet"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Please allow the microphone"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    // the phone's speech engine was still busy → try once more
                    if (busyRetry++ < 1) {
                        recognizer?.destroy(); recognizer = null
                        orb.postDelayed({ startListening() }, 350)
                        return
                    }
                    idleText()
                }
                12, 13 -> // language not supported / unavailable
                    "This language isn't on the phone — switch ${langBtn.text}"
                else -> idleText()
            }
            setStatus(msg)
            if (liveMode) {
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (quiet && !liveMuted && liveMisses < 3 && hasWindowFocus()) {
                    liveMisses++
                    orb.postDelayed({ if (liveMode && !busy && !listening) startListening() }, 300)
                } else liveText?.text = if (punjabi) "ਬੋਲਣ ਲਈ ਵਿਚਕਾਰ ਦਬਾਓ" else "Tap the middle to talk"
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                dropLiveText()
                setMode(OrbView.Mode.IDLE)
                setStatus(idleText())
            } else {
                liveMisses = 0
                busyRetry = 0
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
        if (text.isEmpty() && attachment == null) return
        input.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        handle(text, fromVoice = false)
    }

    private fun handle(text: String, fromVoice: Boolean = true) {
        if (busy) return
        busy = true
        val file = attachment
        val shown = if (file != null) {
            val q = text.ifBlank { "What is this?" }
            (if (file.mime.startsWith("image/")) "🖼  " else "📄  ") + q
        } else text
        finishUserText(shown)
        showThinking()
        setMode(OrbView.Mode.THINKING)
        setStatus("Thinking…")
        val pa = punjabi
        val paVoice = punjabiVoice
        val info = phoneInfo()
        if (file == null) {
            // "open X" → opens right away, no waiting for the AI
            val quick = try { actions.quickOpen(text, pa) } catch (e: Exception) { null }
            if (quick != null) {
                busy = false
                say(quick, listenAfter = false)
                return
            }
        }
        val asked = if (taskMode && file == null)
            "TASK — do this on my phone now (use an action / screen_task, don't just answer): $text" else text

        Thread {
            var failed = false
            val result: Reply = try {
                if (file != null) Reply(brain.askAbout(text, file, pa, paVoice), null)
                else brain.ask(asked, pa, paVoice, info, fast = liveMode)
            } catch (e: Exception) {
                failed = true
                Reply(Gemini.friendlyError(e, pa), null)
            }
            runOnUiThread {
                busy = false
                // don't keep listening in a loop when something went wrong
                act(text, result, pa, paVoice, fromVoice && !failed)
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
            .setTitle("Send this?")
            .setMessage(question)
            .setPositiveButton("Yes, send") { _, _ ->
                complete(userText, result, actions.run(action, pa, confirmed = true), pa, paVoice, fromVoice)
            }
            .setNegativeButton("No") { _, _ ->
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
            res.pressSend -> {
                // WhatsApp is open with the message typed — screen control taps Send right away.
                // (screen control says "sent" out loud itself, so only show it here)
                JarvisAccess.instance?.pressSendSoon(pa)
                hideThinking()
                addMessage(false, result.say.ifBlank { if (pa) "ਭੇਜ ਰਿਹਾ ਹਾਂ…" else "Sending it, Sir…" })
                setMode(OrbView.Mode.IDLE)
                setStatus(idleText())
            }
            data != null -> {
                busy = true
                showThinking()
                setMode(OrbView.Mode.THINKING)
                setStatus("Reading…")
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
                // Keep the conversation going (Settings → Keep Listening)
                val again = fromVoice && type == "none"
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
            Toast.makeText(this, "Installed apps → Singh AI screen control → ON", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ===================================================================== misc

    // ============================================================ attachments

    private fun setAttachment(a: Attachment?) {
        attachment = a
        refreshAttachChip()
        if (a != null) {
            setStatus(
                "Now ask — e.g. \"read it\", \"translate to Punjabi\", \"what is this?\""
            )
        }
    }

    private fun refreshAttachChip() {
        if (!::attachChip.isInitialized) return
        val a = attachment
        if (a == null) {
            attachChip.visibility = View.GONE
            showSend(input.text.isNotBlank())
            return
        }
        attachChip.visibility = View.VISIBLE
        if (a.thumb != null) attachThumb.setImageBitmap(a.thumb) else attachThumb.setImageDrawable(null)
        attachLabel.text = when {
            a.label == "screenshot" -> "Screen"
            a.mime == "application/pdf" -> "PDF"
            a.mime.startsWith("image/") -> "Photo"
            else -> "File"
        }
        showSend(true)
    }

    /** Gemini-style "+" sheet: Camera · Photos · Screen · Files. */
    private fun showAttachSheet() {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(28))
            background = GradientDrawable().apply {
                setColor(p.surface)
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        val handle = View(this).apply { background = Ui.rounded(p.separator, dp(3).toFloat()) }
        box.addView(handle, LinearLayout.LayoutParams(dp(44), dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(18)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(g: Glyph, label: String, onTap: () -> Unit) {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(14))
                background = Ui.pressable(
                    Ui.rounded(p.surface2, dp(26).toFloat()),
                    Ui.rounded(p.separator, dp(26).toFloat())
                )
                isClickable = true
                setOnClickListener { d.dismiss(); onTap() }
            }
            t.addView(GlyphView(this, g, p.text).apply { glyphScale = 0.62f }, LinearLayout.LayoutParams(dp(34), dp(34)))
            t.addView(Ui.text(this, label, 14f, p.text).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        val pa = punjabi
        tile(Glyph.CAMERA, "Camera") { openCamera() }
        tile(Glyph.IMAGE, "Photos") { pickPhoto() }
        tile(Glyph.SCREEN, "Screen") { shareScreen() }
        tile(Glyph.FILE, "Files") { pickFile() }
        box.addView(row)
        box.addView(Ui.text(this,
            "Show a photo or your screen and ask: read it, translate it, what is this…",
            13f, p.muted).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(16), dp(8), 0) })

        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        d.show()
    }

    private fun openCamera() {
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        var uri: Uri? = null
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "singhai_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SinghAI")
            }
            uri = try { contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) } catch (e: Exception) { null }
        }
        if (uri != null) {
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putString("camera_uri", uri?.toString() ?: "").apply()
        try {
            startActivityForResult(i, REQ_CAMERA)
        } catch (e: Exception) {
            toast("Couldn't open the camera")
        }
    }

    private fun pickPhoto() {
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        try { startActivityForResult(i, REQ_PHOTO) } catch (e: Exception) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PHOTO)
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf", "text/plain"))
        try { startActivityForResult(i, REQ_FILE) } catch (e: Exception) {
            toast("Couldn't open files")
        }
    }

    /** Floating button over other apps — go to any screen, tap it, and ask about that screen. */
    private fun shareScreen() {
        val svc = JarvisAccess.instance
        if (svc == null) {
            AlertDialog.Builder(this, Ui.dialogTheme(this))
                .setTitle("Turn on Screen Control")
                .setMessage(
                    "To see your screen, Singh AI needs Screen Control. Installed apps → Singh AI screen control → ON."
                )
                .setPositiveButton("OK") { _, _ -> openAccessibility() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        svc.startScreenShare(punjabi)
        toast(
            "Go to the screen you want, then tap the floating button"
        )
        moveTaskToBack(true)
    }

    private fun handleScreenshot(i: Intent?) {
        if (i == null || !i.hasExtra(EXTRA_SCREENSHOT)) return
        keepChatOnce = true
        val path = i.getStringExtra(EXTRA_SCREENSHOT).orEmpty()
        i.removeExtra(EXTRA_SCREENSHOT)
        if (path.isEmpty()) {
            toast("Couldn't capture the screen")
            return
        }
        Thread {
            val a = try { Attach.fromFile(this, path, "screenshot") } catch (e: Exception) { null }
            runOnUiThread { if (a != null) setAttachment(a) }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        keepChatOnce = true
        if (requestCode != REQ_CAMERA && requestCode != REQ_PHOTO && requestCode != REQ_FILE) return
        val camUri = prefs.getString("camera_uri", "").orEmpty().takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_CAMERA && camUri != null) try { contentResolver.delete(camUri, null, null) } catch (e: Exception) {}
            return
        }
        Thread {
            val a: Attachment? = try {
                when (requestCode) {
                    REQ_CAMERA -> {
                        if (camUri != null) {
                            val r = Attach.fromUri(this, camUri, "photo")
                            try { contentResolver.delete(camUri, null, null) } catch (e: Exception) {}
                            r
                        } else {
                            @Suppress("DEPRECATION")
                            val bmp = data?.extras?.get("data") as? android.graphics.Bitmap
                            bmp?.let { Attach.fromBitmap(it, "photo") }
                        }
                    }
                    REQ_PHOTO -> data?.data?.let { Attach.fromUri(this, it, "photo") }
                    else -> data?.data?.let { Attach.fromUri(this, it, "file") }
                }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (a != null) setAttachment(a)
                else toast("Couldn't read that (max 8 MB)")
            }
        }.start()
    }

    private fun updateLangButton() {
        if (!::langBtn.isInitialized) return
        langBtn.text = if (punjabi) "Punjabi" else "English"
        input.hint = "Ask Singh AI…"
    }

    private fun idleText() = ""

    private fun avatarLetter(): String {
        val name = Memory.all(this).firstNotNullOfOrNull { f ->
            Regex("name is ([\\p{L}]+)", RegexOption.IGNORE_CASE).find(f)
                ?.takeIf { f.contains("user", true) }?.groupValues?.get(1)
        }
        return (name?.firstOrNull() ?: 'S').uppercaseChar().toString()
    }

    /** The three Gemini-style suggestion rows on the welcome screen. */
    private fun updateSuggestions() {
        if (!::suggestions.isInitialized) return
        suggestions.removeAllViews()
        val list = if (punjabi) listOf(
            Glyph.MUSIC to "ਮੇਰੀ ਪਲੇਲਿਸਟ ਦੇ ਗਾਣੇ ਲਾ ਦੇ",
            Glyph.BELL to "ਮੇਰੀਆਂ ਨੋਟੀਫਿਕੇਸ਼ਨਾਂ ਪੜ੍ਹ ਕੇ ਸੁਣਾ",
            Glyph.GLOBE to "ਅੱਜ ਮੌਸਮ ਕਿਹੋ ਜਿਹਾ ਹੈ?"
        ) else listOf(
            Glyph.MUSIC to "Play my playlist",
            Glyph.BELL to "Read my notifications",
            Glyph.GLOBE to "What's the weather today?"
        )
        for ((g, text) in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Ui.pressable(Ui.rounded(Color.TRANSPARENT, dp(16).toFloat()), Ui.rounded(p.surface2, dp(16).toFloat()))
                isClickable = true
                setOnClickListener { handle(text, fromVoice = false) }
            }
            row.addView(GlyphView(this, g, p.text).apply { glyphScale = 0.5f }, LinearLayout.LayoutParams(dp(36), dp(36)))
            row.addView(Ui.text(this, text, 18f, p.text, Ui.regular).apply { setPadding(dp(18), 0, 0, 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
            suggestions.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    // =============================================================== Live screen

    /** Opens the full-screen Live voice chat (like Gemini Live) with a smooth slide-up. */
    private fun openLive() {
        if (liveMode) return
        liveMode = true
        liveMuted = false
        liveMisses = 0
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        addLiveLayer(animate = true)
        orb.postDelayed({ if (liveMode && !busy && !listening) startListening() }, 380)
    }

    private fun closeLive() {
        if (!liveMode) return
        liveMode = false
        recognizer?.cancel()
        listening = false
        tts?.stop()
        dropLiveText()
        setMode(OrbView.Mode.IDLE)
        setStatus(idleText())
        val layer = liveLayer ?: return
        liveLayer = null
        layer.animate().alpha(0f).translationY(dp(40).toFloat())
            .setDuration(240).setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
            .withLayer()
            .withEndAction {
                rootFrame.removeView(layer)
                overlayPads.clear()
                livePill = null; liveGlow = null; liveSpark = null; liveText = null; liveMicBtn = null
                liveChat = null; liveScroll = null; livePending = null
            }.start()
    }

    /** Adds one line to the Live conversation: you on the right (grey bubble), Singh AI on the left (big text). */
    private fun addLiveLine(user: Boolean, text: String, animate: Boolean = true) {
        val lc = liveChat ?: return
        if (text.isBlank()) return
        livePending?.let { lc.removeView(it) }
        livePending = null
        liveText?.let { if (it.parent != null) lc.removeView(it) }
        val tv = if (user) Ui.text(this, text, 17f, p.text, Ui.regular).apply {
            background = Ui.rounded(p.surface2, dp(20).toFloat())
            setPadding(dp(16), dp(10), dp(16), dp(10))
        } else Ui.text(this, text, 22f, p.text, Ui.regular).apply {
            setLineSpacing(0f, 1.1f)
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (user) Gravity.END else Gravity.START
            topMargin = dp(12)
            if (user) marginStart = dp(48)
        }
        lc.addView(tv, lp)
        while (lc.childCount > 30) lc.removeViewAt(0)
        if (animate) {
            tv.alpha = 0f
            tv.translationY = dp(14).toFloat()
            tv.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
        liveScroll?.let { s -> s.post { s.smoothScrollTo(0, lc.height) } }
    }

    /** What the mic hears right now (updates while you talk). */
    private fun showLivePending(text: String) {
        val lc = liveChat ?: return
        liveText?.let { if (it.parent != null) lc.removeView(it) }
        var tv = livePending
        if (tv == null) {
            tv = Ui.text(this, text, 17f, p.muted, Ui.regular).apply {
                background = Ui.rounded(p.surface2, dp(20).toFloat())
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            lc.addView(tv, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.END; topMargin = dp(12); marginStart = dp(48)
            })
            livePending = tv
        } else tv.text = text
        liveScroll?.let { s -> s.post { s.smoothScrollTo(0, lc.height) } }
    }

    private fun fadeLiveText(text: String) {
        val tv = liveText ?: return
        tv.animate().cancel()
        tv.animate().alpha(0f).setDuration(120).withEndAction {
            tv.text = text
            tv.animate().alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    private fun circleButton(g: Glyph, desc: String, onTap: () -> Unit) = GlyphView(this, g, p.text).apply {
        bgColor = if (p.dark) p.surface2 else Color.WHITE
        circleBg = true
        glyphScale = 0.4f
        isClickable = true
        contentDescription = desc
        elevation = if (p.dark) 0f else dp(2).toFloat()
        setOnClickListener { onTap() }
    }

    private fun addLiveLayer(animate: Boolean) {
        val layer = FrameLayout(this).apply {
            setBackgroundColor(p.bg)
            isClickable = true // nothing behind it gets touched
        }
        val g = GlowView(this).apply { dark = p.dark; mode = this@MainActivity.mode }
        layer.addView(g, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55f).toInt(), Gravity.BOTTOM))
        liveGlow = g

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layer.addView(col, FrameLayout.LayoutParams(-1, -1))

        // top: language on the right
        val top = FrameLayout(this)
        val lang = Ui.text(this, if (punjabi) "ਪੰਜਾਬੀ" else "English", 15f, p.text, Ui.medium).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = Ui.rounded(if (p.dark) p.surface2 else Color.WHITE, dp(18).toFloat())
            setOnClickListener {
                punjabi = !punjabi
                text = if (punjabi) "ਪੰਜਾਬੀ" else "English"
                updateLangButton(); updateSuggestions()
            }
        }
        top.addView(lang, FrameLayout.LayoutParams(-2, dp(36), Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(top, LinearLayout.LayoutParams(-1, dp(60)))

        // middle: animated sparkle on top, then the conversation (like a live chat)
        val sp = SparkleView(this).apply {
            dark = p.dark
            mode = this@MainActivity.mode
            setOnClickListener { if (listening) stopListening() else if (!busy) startListening() }
        }
        col.addView(sp, LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        liveSpark = sp
        val lc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        val ls = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(40))
            addView(lc, ViewGroup.LayoutParams(-1, -2))
        }
        col.addView(ls, LinearLayout.LayoutParams(-1, 0, 1f))
        liveChat = lc
        liveScroll = ls
        val tv = Ui.text(this, if (punjabi) "ਬੋਲੋ, ਮੈਂ ਸੁਣ ਰਿਹਾ ਹਾਂ…" else "Go ahead, I'm listening…", 26f, p.muted, Ui.regular).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(40), dp(8), 0)
        }
        lc.addView(tv, LinearLayout.LayoutParams(-1, -2))
        liveText = tv
        // show the last few lines of this chat for context
        for (mm in messages.takeLast(4)) addLiveLine(mm.user, mm.text, animate = false)

        // bottom: (+) (video)  [ glowing pill ]  (mic) (x)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(14))
        }
        val size = dp(58)
        row.addView(circleButton(Glyph.PLUS, "Add photo or file") { closeLive(); showAttachSheet() },
            LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) })
        row.addView(circleButton(Glyph.VIDEO, "Camera") { closeLive(); openCamera() },
            LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) })
        val lp = LivePill(this).apply {
            dark = p.dark
            mode = this@MainActivity.mode
            contentDescription = "Talk"
            setOnClickListener {
                when {
                    tts?.isSpeaking == true -> { tts?.stop(); afterSpeaking() }
                    listening -> stopListening()
                    !busy -> { liveMuted = false; startListening() }
                }
            }
        }
        row.addView(lp, LinearLayout.LayoutParams(0, dp(66), 1f).apply { marginEnd = dp(8) })
        livePill = lp
        val micB = circleButton(Glyph.MIC, "Mute") {}
        micB.setOnClickListener {
            liveMuted = !liveMuted
            micB.glyph = if (liveMuted) Glyph.MIC_OFF else Glyph.MIC
            micB.bgColor = if (liveMuted) Color.parseColor("#FFDAD6") else (if (p.dark) p.surface2 else Color.WHITE)
            micB.invalidate()
            if (liveMuted && listening) { recognizer?.cancel(); listening = false; setMode(OrbView.Mode.IDLE) }
            else if (!liveMuted && !busy && !listening) startListening()
        }
        liveMicBtn = micB
        row.addView(micB, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) })
        row.addView(circleButton(Glyph.CLOSE, "Close") { closeLive() }, LinearLayout.LayoutParams(size, size))
        col.addView(row, LinearLayout.LayoutParams(-1, -2))

        padForBars { t, b -> col.setPadding(dp(8), t + dp(4), dp(8), b) }
        rootFrame.addView(layer, FrameLayout.LayoutParams(-1, -1))
        liveLayer = layer
        if (animate) {
            layer.alpha = 0f
            layer.translationY = dp(60).toFloat()
            layer.animate().alpha(1f).translationY(0f)
                .setDuration(340).setInterpolator(DecelerateInterpolator(2.2f))
                .withLayer().start()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer != null) { closeDrawer(); return }
        if (liveMode) { closeLive(); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun setStatus(s: String) {
        status.text = s
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                WakeService.start(this)
                startListening()
            }
            else setStatus("I can't hear you without microphone permission")
        }
    }

    /** If Jarvis crashed last time, show the error (so it can be sent to fix it). */
    private fun showLastCrash(): Boolean {
        val f = java.io.File(filesDir, "crash.txt")
        if (!f.exists()) return false
        val msg = try { f.readText() } catch (e: Exception) { "?" }
        f.delete()
        showingCrash = true
        Ui.showError(this, msg)
        return true
    }

    override fun onDestroy() {
        if (showingCrash) { super.onDestroy(); return }
        Gemini.onRetry = null
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_MIC = 7
        private const val REQ_CAMERA = 21
        private const val REQ_PHOTO = 22
        private const val REQ_FILE = 23
        const val EXTRA_SCREENSHOT = "screenshot_path"
        const val EXTRA_OPEN_LIVE = "open_live"
    }
}
