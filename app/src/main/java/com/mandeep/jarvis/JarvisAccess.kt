package com.mandeep.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONArray
import java.util.Locale

/**
 * "Screen control": lets Jarvis see the screen and tap / type / scroll in any app.
 * Runs the on-screen agent: look at the screen -> ask Gemini what to do -> do it -> repeat.
 */
class JarvisAccess : AccessibilityService(), TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var punjabiVoice = false
    private var pill: TextView? = null

    @Volatile private var running = false
    @Volatile private var stopRequested = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) punjabiVoice = t.isLanguageAvailable(Locale("pa", "IN")) >= TextToSpeech.LANG_AVAILABLE
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        stopRequested = true
        hidePill()
        stopScreenShare()
        hideMini()
        tts?.shutdown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------- global keys

    fun globalLater(cmd: String, delayMs: Long) {
        main.postDelayed({ global(cmd) }, delayMs)
    }

    fun global(cmd: String): Boolean {
        val a = when (cmd) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_menu" -> GLOBAL_ACTION_POWER_DIALOG
            "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            else -> return false
        }
        return performGlobalAction(a)
    }

    // ------------------------------------------- mini panel ("Singh AI" popup)

    private var mini: android.view.View? = null
    private var miniWave: WaveBar? = null
    private var miniText: TextView? = null
    private var miniHint: TextView? = null

    /**
     * Gemini-style popup at the bottom of the screen, over any app (the app itself stays closed):
     * glowing gradient edge, what you said / the answer, and a flowing voice bar.
     */
    fun showMini(text: String, hint: String, mode: OrbView.Mode) {
        main.post {
            val wm = getSystemService(WindowManager::class.java) ?: return@post
            val d = resources.displayMetrics.density
            val p = Ui.palette(this)
            if (mini == null) {
                val radius = 30 * d
                // gradient "glow" edge around the card
                val edge = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Ui.GEMINI_BLUE, Ui.GEMINI_PURPLE, Ui.GEMINI_PINK)
                ).apply { cornerRadius = radius }
                val inner = Ui.rounded(if (p.dark) Color.parseColor("#1C1C1E") else Color.WHITE, radius - 2 * d)
                val bg = android.graphics.drawable.LayerDrawable(arrayOf(edge, inner)).apply {
                    val inset = (2 * d).toInt()
                    setLayerInset(1, inset, inset, inset, inset)
                }
                val card = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background = bg
                    elevation = 16 * d
                    val pad = (16 * d).toInt()
                    setPadding(pad, (12 * d).toInt(), (8 * d).toInt(), (10 * d).toInt())
                    setOnClickListener {
                        // tap → open the full app
                        WakeService.instance?.endSession(false)
                        startActivity(
                            android.content.Intent(this@JarvisAccess, MainActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                // header: ✦ Singh AI ............ ✕
                val head = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                head.addView(GlyphView(this, Glyph.SPARKLE, Ui.GEMINI_PURPLE).apply { glyphScale = 0.8f },
                    android.widget.LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()))
                val t2 = Ui.text(this, "Singh AI", 13f, p.muted, Ui.medium).apply { setPadding((8 * d).toInt(), 0, 0, 0) }
                head.addView(t2, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                head.addView(GlyphView(this, Glyph.CLOSE, p.muted).apply {
                    glyphScale = 0.4f
                    isClickable = true
                    setOnClickListener { WakeService.instance?.endSession(true) }
                }, android.widget.LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt()))
                card.addView(head)

                val t1 = Ui.text(this, "", 19f, p.text).apply {
                    maxLines = 6
                    setPadding(0, (2 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                    setLineSpacing(2 * d, 1f)
                }
                card.addView(t1)
                val wave = WaveBar(this)
                card.addView(wave, android.widget.LinearLayout.LayoutParams(-1, (30 * d).toInt()).apply {
                    marginEnd = (8 * d).toInt()
                })

                val lp = WindowManager.LayoutParams(
                    resources.displayMetrics.widthPixels - (16 * d).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = (28 * d).toInt()
                }
                try {
                    wm.addView(card, lp)
                    mini = card; miniWave = wave; miniText = t1; miniHint = t2
                    card.alpha = 0f
                    card.translationY = 80 * d
                    card.animate().alpha(1f).translationY(0f).setDuration(280)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                } catch (e: Exception) {
                    mini = null
                    return@post
                }
            }
            miniText?.text = text
            miniHint?.text = if (hint.isEmpty()) "Singh AI" else hint
            miniWave?.mode = mode
        }
    }

    fun miniLevel(level: Float) { main.post { miniWave?.level = level } }

    fun hideMini() {
        val v = mini ?: return
        mini = null; miniWave = null; miniText = null; miniHint = null
        main.post {
            v.animate().alpha(0f).translationY(60 * resources.displayMetrics.density).setDuration(200).withEndAction {
                try { getSystemService(WindowManager::class.java)?.removeView(v) } catch (e: Exception) {}
            }.start()
        }
    }

    // ------------------------------------------------------ screen share

    private var capturePill: TextView? = null

    /**
     * "Screen share": shows a floating button over other apps. Go to any screen, tap it,
     * and Singh AI comes back with a screenshot of that screen attached, ready for your question.
     */
    fun startScreenShare(punjabi: Boolean) {
        main.post {
            if (capturePill != null) return@post
            val wm = getSystemService(WindowManager::class.java) ?: return@post
            val d = resources.displayMetrics.density
            val v = TextView(this).apply {
                text = if (punjabi) "  📸  ਇਸ ਸਕਰੀਨ ਬਾਰੇ ਪੁੱਛੋ  " else "  📸  Ask about this screen  "
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                val pad = (12 * d).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#4C8DFF"), Color.parseColor("#9B72FF"), Color.parseColor("#FF5FA2"))
                ).apply { cornerRadius = 28 * d }
                elevation = 8 * d
                setOnClickListener { captureAndReturn() }
                setOnLongClickListener { stopScreenShare(); true }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (110 * d).toInt()
            }
            try { wm.addView(v, lp); capturePill = v } catch (e: Exception) { capturePill = null }
        }
    }

    fun stopScreenShare() {
        val v = capturePill ?: return
        capturePill = null
        main.post { try { getSystemService(WindowManager::class.java)?.removeView(v) } catch (e: Exception) {} }
    }

    private fun captureAndReturn() {
        stopScreenShare()
        if (Build.VERSION.SDK_INT < 30) {
            openSinghAI(null)
            return
        }
        // wait for the floating button to disappear, then take the screenshot
        main.postDelayed({
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        var path: String? = null
                        try {
                            val hb = result.hardwareBuffer
                            val hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            val bmp = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            hb.close()
                            if (bmp != null) {
                                val f = java.io.File(cacheDir, "screen.jpg")
                                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                                path = f.absolutePath
                            }
                        } catch (e: Exception) {}
                        openSinghAI(path)
                    }

                    override fun onFailure(errorCode: Int) { openSinghAI(null) }
                })
            } catch (e: Exception) {
                openSinghAI(null)
            }
        }, 350)
    }

    private fun openSinghAI(screenshotPath: String?) {
        val i = android.content.Intent(this, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SCREENSHOT, screenshotPath ?: "")
        startActivity(i)
    }

    // ------------------------------------------------- fast WhatsApp send

    /** Waits for WhatsApp's Send button (message already typed) and taps it — no AI steps needed. */
    fun pressSendSoon(punjabi: Boolean) {
        Thread {
            var sent = false
            val end = System.currentTimeMillis() + 9000
            Thread.sleep(700)
            while (!sent && System.currentTimeMillis() < end) {
                val root = rootInActiveWindow
                val pkg = root?.packageName?.toString().orEmpty()
                if (root != null && pkg.startsWith("com.whatsapp")) {
                    val btn = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()
                        ?: findByDesc(root, listOf("send", "ਭੇਜੋ", "भेजें", "भेजो"))
                    if (btn != null && btn.isVisibleToUser) {
                        click(btn)
                        sent = true
                        break
                    }
                }
                Thread.sleep(250)
            }
            main.post {
                speak(
                    if (sent) (if (punjabi) "ਭੇਜ ਦਿੱਤਾ।" else "Sent, Sir.")
                    else (if (punjabi) "ਸੁਨੇਹਾ ਲਿਖ ਦਿੱਤਾ, Send ਦਬਾ ਦਿਓ।" else "Message is typed, please tap Send.")
                )
            }
        }.start()
    }

    private fun findByDesc(n: AccessibilityNodeInfo?, words: List<String>, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        val d = n.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (d.isNotEmpty() && words.any { d == it }) return n
        for (i in 0 until n.childCount) findByDesc(n.getChild(i), words, depth + 1)?.let { return it }
        return null
    }

    // ------------------------------------------------------------ the agent

    /** Starts an on-screen task in the background. */
    fun runTask(goal: String, punjabi: Boolean) {
        if (running) {
            stopRequested = true
            main.postDelayed({ runTask(goal, punjabi) }, 1500)
            return
        }
        running = true
        stopRequested = false
        showPill("Singh AI kamm kar reha… ✕")
        Thread {
            val result = try {
                agentLoop(goal, punjabi)
            } catch (e: Exception) {
                if (e is GeminiException || e is java.io.IOException) Gemini.friendlyError(e, punjabi)
                else if (punjabi) "ਮਾਫ਼ ਕਰਨਾ, ਕੰਮ ਪੂਰਾ ਨਹੀਂ ਹੋ ਸਕਿਆ।" else "Sorry Sir, I couldn't finish that."
            }
            running = false
            main.post {
                hidePill()
                speak(result)
            }
        }.start()
    }

    private fun agentLoop(goal: String, punjabi: Boolean): String {
        val steps = ArrayList<String>()
        Thread.sleep(600) // let the screen settle after the app goes to the background
        for (step in 1..MAX_STEPS) {
            if (stopRequested) return if (punjabi) "ਠੀਕ ਹੈ, ਰੋਕ ਦਿੱਤਾ।" else "Stopped, Sir."
            main.post { pill?.text = "Singh AI: step $step… ✕" }

            val nodes = ArrayList<AccessibilityNodeInfo>()
            val screen = snapshot(nodes)
            val prompt = "GOAL: $goal\n\nSTEPS DONE SO FAR:\n" +
                (if (steps.isEmpty()) "(none)" else steps.joinToString("\n")) +
                "\n\nCURRENT SCREEN (step $step of max $MAX_STEPS):\n$screen"

            val out = Gemini.generate(this, agentSystem(punjabi), JSONArray().put(Gemini.part("user", prompt)), json = true)
            val a = Gemini.parseJson(out) ?: continue
            val kind = a.optString("action")
            val id = a.optInt("id", -1)
            val node = nodes.getOrNull(id)
            val note = a.optString("note", "")

            when (kind) {
                "done" -> return a.optString("say", if (punjabi) "ਹੋ ਗਿਆ।" else "Done, Sir.")
                "click" -> if (node != null) click(node) else steps.add("$step. (bad id $id)")
                "long_press" -> if (node != null) tapAt(node, 800)
                "type" -> if (node != null) type(node, a.optString("text"))
                "scroll" -> swipe(a.optString("direction", "down"))
                "back" -> global("back")
                "home" -> global("home")
                "open_app" -> openApp(a.optString("name"))
                "wait" -> Thread.sleep(1500)
                else -> {}
            }
            steps.add("$step. $kind${if (id >= 0) " [$id]" else ""} ${a.optString("text", a.optString("name", a.optString("direction", "")))} — $note".trim())
            waitForScreen(if (kind == "open_app") 1500L else 900L)
        }
        return if (punjabi) "ਬਹੁਤ ਕਦਮ ਹੋ ਗਏ, ਮੈਂ ਰੁਕ ਗਿਆ। ਬਾਕੀ ਤੁਸੀਂ ਦੇਖ ਲਓ।" else "That took too many steps, Sir. I stopped — please check the screen."
    }

    /** Waits until the screen stops changing (max [maxMs]) instead of a long fixed sleep. */
    private fun waitForScreen(maxMs: Long) {
        Thread.sleep(250)
        var last = screenHash()
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            Thread.sleep(200)
            val now = screenHash()
            if (now == last) return
            last = now
        }
    }

    private fun screenHash(): Int {
        val root = rootInActiveWindow ?: return 0
        var h = root.packageName?.hashCode() ?: 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25) return
            h = h * 31 + (n.text?.hashCode() ?: 0) + n.childCount
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return h
    }

    private fun agentSystem(punjabi: Boolean): String {
        val lang = if (!punjabi) "English"
        else if (punjabiVoice) "Punjabi in Gurmukhi script" else "romanized Punjabi (English letters)"
        return """
You control an Android phone (Samsung Galaxy S26) for the user, one step at a time, to reach the GOAL.
You see the current screen as a numbered list of elements: [id] Type "text" (description) {flags} @x,y.
Reply with ONLY one JSON object for the next step:
{"action":"click","id":N,"note":"why"}
{"action":"long_press","id":N}
{"action":"type","id":N,"text":"..."}   (id = an {edit} field; replaces its text)
{"action":"scroll","direction":"down|up|left|right"}
{"action":"back"} {"action":"home"} {"action":"wait"}
{"action":"open_app","name":"<app name>"}
{"action":"done","say":"<1-2 short spoken sentences in $lang telling the user the result; if the goal was to read something, say what it says>"}
Rules: do EXACTLY the goal — nothing extra, never ask questions; take the shortest path (fewest steps, act fast);
to message someone in a chat app, use the app's search, open the chat, type in the message box and tap Send only if the goal says send; if an element is not visible, scroll; if a popup blocks, close it; after typing a search, click the search/enter button or a suggestion.
Never pay, buy, transfer money, enter passwords or card numbers, or delete accounts/data: stop with "done" and tell the user to finish that part themselves.
If the goal is already complete, reply "done".
""".trimIndent()
    }

    /** Flattens the active window into numbered lines; fills [out] with matching nodes. */
    private fun snapshot(out: ArrayList<AccessibilityNodeInfo>): String {
        val root = rootInActiveWindow ?: return "(screen not readable — try 'back' or 'home')"
        val sb = StringBuilder("App: ${root.packageName}\n")
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || out.size >= MAX_NODES) return
            if (!n.isVisibleToUser) return
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
            val flags = buildList {
                if (n.isClickable) add("click")
                if (n.isEditable) add("edit")
                if (n.isScrollable) add("scroll")
                if (n.isCheckable) add(if (n.isChecked) "on" else "off")
                if (n.isSelected) add("selected")
            }
            if (text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() || flags.isNotEmpty()) {
                n.getBoundsInScreen(r)
                val type = n.className?.toString()?.substringAfterLast('.') ?: "View"
                val id = out.size
                out.add(n)
                sb.append("[").append(id).append("] ").append(type)
                if (text.isNotEmpty()) sb.append(" \"").append(text.take(80)).append("\"")
                if (desc.isNotEmpty() && desc != text) sb.append(" (").append(desc.take(60)).append(")")
                if (hint.isNotEmpty() && hint != text) sb.append(" hint:\"").append(hint.take(40)).append("\"")
                if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append("}")
                sb.append(" @").append(r.centerX()).append(",").append(r.centerY()).append("\n")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    private fun click(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n == null || !n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tapAt(node, 60)
    }

    private fun type(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            click(node)
            Thread.sleep(400)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    private fun tapAt(node: AccessibilityNodeInfo, durationMs: Long) {
        val r = Rect()
        node.getBoundsInScreen(r)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        gesture(path, durationMs)
    }

    private fun swipe(direction: String) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val path = Path()
        when (direction) {
            "up" -> { path.moveTo(w / 2, h * 0.30f); path.lineTo(w / 2, h * 0.72f) }
            "left" -> { path.moveTo(w * 0.20f, h / 2); path.lineTo(w * 0.80f, h / 2) }
            "right" -> { path.moveTo(w * 0.80f, h / 2); path.lineTo(w * 0.20f, h / 2) }
            else -> { path.moveTo(w / 2, h * 0.72f); path.lineTo(w / 2, h * 0.30f) }
        }
        gesture(path, 350)
    }

    private fun gesture(path: Path, durationMs: Long) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        main.post { dispatchGesture(g, null, null) }
    }

    private fun openApp(name: String) {
        val wanted = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        if (wanted.isEmpty()) return
        val pm = packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, android.content.pm.PackageManager.MATCH_ALL).firstOrNull {
            it.loadLabel(pm).toString().lowercase(Locale.ROOT).filter { c -> c.isLetterOrDigit() }.contains(wanted)
        } ?: return
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // -------------------------------------------------------- overlay + voice

    private fun showPill(text: String) {
        main.post {
            if (pill != null) { pill?.text = text; return@post }
            val wm = getSystemService(WindowManager::class.java) ?: return@post
            val v = TextView(this).apply {
                this.text = text
                setTextColor(Color.parseColor("#05080D"))
                setBackgroundColor(Color.parseColor("#3FE0FF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                val pad = (10 * resources.displayMetrics.density).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
                setOnClickListener { stopRequested = true; this.text = "Rok reha haan…" }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (40 * resources.displayMetrics.density).toInt()
            }
            try { wm.addView(v, lp); pill = v } catch (e: Exception) { pill = null }
        }
    }

    private fun hidePill() {
        val v = pill ?: return
        pill = null
        main.post {
            try { getSystemService(WindowManager::class.java)?.removeView(v) } catch (e: Exception) {}
        }
    }

    private fun speak(text: String) {
        val t = tts ?: return
        if (!ttsReady || text.isBlank()) return
        val locale = when {
            text.any { it in '਀'..'੿' } && punjabiVoice -> Locale("pa", "IN")
            text.any { it in 'ऀ'..'ॿ' } -> Locale("hi", "IN")
            else -> Locale("en", "IN")
        }
        t.setLanguage(locale)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agent-" + System.currentTimeMillis())
    }

    companion object {
        @Volatile var instance: JarvisAccess? = null
        private const val MAX_STEPS = 20
        private const val MAX_NODES = 180
    }
}
