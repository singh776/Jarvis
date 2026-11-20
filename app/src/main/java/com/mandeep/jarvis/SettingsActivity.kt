package com.mandeep.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * iPhone-style Settings: grouped rounded sections, colored icons, chevrons, iOS toggles,
 * a large title that shrinks into the bar as you scroll, and slide-in pages.
 * One activity shows every page; the "page" extra picks which one.
 */
class SettingsActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }
    private lateinit var p: Ui.Palette
    private var page = PAGE_MAIN

    // iOS system colors for the row icons
    private val iosRed = Color.parseColor("#FF3B30")
    private val iosOrange = Color.parseColor("#FF9500")
    private val iosGreen = Color.parseColor("#34C759")
    private val iosTeal = Color.parseColor("#5AC8FA")
    private val iosBlue = Color.parseColor("#007AFF")
    private val iosIndigo = Color.parseColor("#5856D6")
    private val iosPurple = Color.parseColor("#AF52DE")
    private val iosPink = Color.parseColor("#FF2D55")
    private val iosGray = Color.parseColor("#8E8E93")

    private var modelField: EditText? = null
    private var keyField: EditText? = null

    private fun dp(v: Float) = Ui.dp(this, v)
    private fun dp(v: Int) = Ui.dp(this, v.toFloat())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_MAIN
    }

    override fun onResume() {
        super.onResume()
        try {
            build() // refresh values / permission status every time we come back
        } catch (e: Throwable) {
            Ui.showError(this, android.util.Log.getStackTraceString(e))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try { build() } catch (e: Throwable) { Ui.showError(this, android.util.Log.getStackTraceString(e)) }
    }

    override fun onPause() {
        saveCustomModel()
        super.onPause()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun open(target: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, target))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    // ===================================================================== layout

    private var keepScroll = 0
    private var scrollView: ScrollView? = null

    /** Rebuilds the page but stays at the same place in the list. */
    private fun rebuildHere() {
        keepScroll = scrollView?.scrollY ?: 0
        build()
    }

    private fun build() {
        p = Ui.palette(this)
        Ui.setupWindow(this, p)

        val root = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        scrollView = scroll
        if (keepScroll > 0) {
            val y = keepScroll
            keepScroll = 0
            scroll.post { scroll.scrollTo(0, y) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        val topSpace = View(this)
        content.addView(topSpace, LinearLayout.LayoutParams(-1, dp(44)))

        val isMain = page == PAGE_MAIN
        val title = pageTitle()

        // Large title (main page only)
        val largeTitle = Ui.text(this, title, 34f, p.text, Ui.bold).apply {
            setPadding(dp(4), dp(4), 0, dp(4))
            pivotX = 0f
        }
        if (isMain) content.addView(largeTitle)

        when (page) {
            PAGE_MAIN -> mainPage(content)
            PAGE_MODEL -> modelPage(content, "model", Gemini.DEFAULT_MODEL)
            PAGE_FALLBACK -> modelPage(content, "fallback_model", Gemini.DEFAULT_FALLBACK_MODEL)
            PAGE_LANGUAGE -> languagePage(content)
            PAGE_SPEED -> speedPage(content)
            PAGE_THEME -> themePage(content)
            PAGE_ACCENT -> accentPage(content)
            PAGE_APIKEY -> apiKeyPage(content)
            PAGE_VOICES -> voicesPage(content)
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // ---- navigation bar (back + small title), fades in on scroll
        val nav = FrameLayout(this)
        val navBg = View(this).apply {
            setBackgroundColor(withAlpha(p.bg, 245))
        }
        nav.addView(navBg, FrameLayout.LayoutParams(-1, -1))
        val hairline = View(this).apply { setBackgroundColor(p.separator) }
        nav.addView(hairline, FrameLayout.LayoutParams(-1, maxOf(1, dp(0.5f)), Gravity.BOTTOM))

        val bar = FrameLayout(this)
        val back = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(12), 0)
            isClickable = true
            setOnClickListener { finish() }
            addView(GlyphView(context, Glyph.BACK, p.accent), LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(Ui.text(context, if (isMain) "Singh AI" else getString0(), 17f, p.accent))
        }
        bar.addView(back, FrameLayout.LayoutParams(-2, -1, Gravity.START))
        val smallTitle = Ui.text(this, title, 17f, p.text, Ui.medium).apply {
            gravity = Gravity.CENTER
        }
        bar.addView(smallTitle, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
        if (page == PAGE_APIKEY) {
            val save = Ui.text(this, "Save", 17f, p.accent, Ui.medium).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(16), 0)
                setOnClickListener { saveKey() }
            }
            bar.addView(save, FrameLayout.LayoutParams(-2, -1, Gravity.END))
        }
        nav.addView(bar, FrameLayout.LayoutParams(-1, dp(44), Gravity.BOTTOM))
        root.addView(nav, FrameLayout.LayoutParams(-1, dp(44)))

        fun applyScroll(y: Int) {
            val progress = if (isMain) {
                val h = largeTitle.height.takeIf { it > 0 } ?: dp(44)
                (y / (h * 0.9f)).coerceIn(0f, 1f)
            } else (y / dp(12).toFloat()).coerceIn(0f, 1f)
            navBg.alpha = progress
            hairline.alpha = progress
            if (isMain) {
                smallTitle.alpha = ((progress - 0.5f) * 2f).coerceIn(0f, 1f)
                val s = 1f - 0.18f * progress
                largeTitle.scaleX = s
                largeTitle.scaleY = s
                largeTitle.alpha = 1f - progress * 0.8f
            } else smallTitle.alpha = 1f
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> applyScroll(y) }
        applyScroll(0)

        Ui.onInsets(root) { top, bottom ->
            nav.layoutParams = (nav.layoutParams as FrameLayout.LayoutParams).apply { height = top + dp(44) }
            topSpace.layoutParams = (topSpace.layoutParams as LinearLayout.LayoutParams).apply { height = top + dp(44) }
            content.setPadding(dp(16), 0, dp(16), bottom + dp(32))
            nav.requestLayout()
        }
        setContentView(root)
    }

    private fun getString0() = "Settings"

    private fun pageTitle(): String = when (page) {
        PAGE_MODEL -> "AI Model"
        PAGE_FALLBACK -> "Backup Model"
        PAGE_LANGUAGE -> "Language"
        PAGE_SPEED -> "Voice Speed"
        PAGE_THEME -> "Theme"
        PAGE_ACCENT -> "Accent Color"
        PAGE_APIKEY -> "API Key"
        PAGE_VOICES -> "Voice"
        else -> "Settings"
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    // ---------------------------------------------------------------- building blocks

    private class Row(val view: View, val inset: Boolean)

    private fun section(parent: LinearLayout, header: String?, rows: List<Row>, footer: String? = null) {
        if (header != null) {
            parent.addView(Ui.text(this, header.uppercase(), 13f, p.muted).apply {
                setPadding(dp(16), dp(26), dp(16), dp(7))
            })
        } else parent.addView(View(this), LinearLayout.LayoutParams(-1, dp(24)))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(p.surface, Ui.dpf(context, 12f))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) {
                val sep = View(this).apply { setBackgroundColor(p.separator) }
                card.addView(sep, LinearLayout.LayoutParams(-1, maxOf(1, dp(0.5f))).apply {
                    marginStart = if (r.inset) dp(59) else dp(16)
                })
            }
            card.addView(r.view, LinearLayout.LayoutParams(-1, -2))
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2))

        if (footer != null) {
            parent.addView(Ui.text(this, footer, 13f, p.muted).apply {
                setPadding(dp(16), dp(7), dp(16), 0)
                setLineSpacing(0f, 1.1f)
            })
        }
    }

    private fun row(
        title: String,
        glyph: Glyph? = null,
        iconBg: Int = 0,
        value: String? = null,
        valueColor: Int = p.muted,
        chevron: Boolean = false,
        checked: Boolean = false,
        toggle: Boolean? = null,
        onToggle: ((Boolean) -> Unit)? = null,
        titleColor: Int = p.text,
        circleIcon: Boolean = false,
        onClick: (() -> Unit)? = null
    ): Row {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        if (glyph != null) {
            val icon = GlyphView(this, glyph, Color.WHITE).apply {
                bgColor = iconBg
                circleBg = circleIcon
                glyphScale = 0.6f
            }
            r.addView(icon, LinearLayout.LayoutParams(dp(29), dp(29)).apply { marginEnd = dp(14) })
        }
        r.addView(Ui.text(this, title, 17f, titleColor), LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) {
            r.addView(Ui.text(this, value, 17f, valueColor).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(170)
                setPadding(dp(8), 0, 0, 0)
            })
        }
        val sw: Switch? = if (toggle != null) iosSwitch(toggle) else null
        if (sw != null) {
            sw.setOnCheckedChangeListener { _, isOn -> onToggle?.invoke(isOn) }
            r.addView(sw, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
        if (checked) {
            r.addView(GlyphView(this, Glyph.CHECK, p.accent), LinearLayout.LayoutParams(dp(20), dp(20)))
        }
        if (chevron) {
            val chev = if (p.dark) Color.parseColor("#5A5A5E") else Color.parseColor("#C4C4C7")
            r.addView(GlyphView(this, Glyph.CHEVRON, chev), LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                marginStart = dp(6)
            })
        }
        val switch = sw
        if (onClick != null || switch != null) {
            r.background = Ui.pressableColor(p.surface, p.surface2)
            r.isClickable = true
            r.setOnClickListener {
                if (switch != null) {
                    switch.isChecked = !switch.isChecked
                } else {
                    onClick?.invoke()
                }
            }
        }
        return Row(r, glyph != null)
    }

    /** android.widget.Switch dressed like an iOS toggle. */
    private fun iosSwitch(on: Boolean): Switch {
        val h = dp(31)
        val off = if (p.dark) Color.parseColor("#39393D") else Color.parseColor("#E9E9EA")
        fun track(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = h / 2f
            setColor(color)
            setSize(h * 2 - dp(10), h)
        }
        val trackList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), track(iosGreen))
            addState(intArrayOf(), track(off))
        }
        val knob = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setSize(h - dp(4), h - dp(4))
            if (!p.dark) setStroke(maxOf(1, dp(0.5f)), Color.parseColor("#22000000"))
        }
        return Switch(this).apply {
            showText = false
            thumbDrawable = InsetDrawable(knob, dp(2))
            trackDrawable = trackList
            switchMinWidth = h * 2 - dp(10)
            thumbTextPadding = 0
            isChecked = on
            background = null
        }
    }

    private fun field(hint: String, value: String, password: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 17f
        setTextColor(p.text)
        setHintTextColor(p.muted)
        background = null
        setSingleLine(true)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        inputType = InputType.TYPE_CLASS_TEXT or
            (if (password) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        imeOptions = EditorInfo.IME_ACTION_DONE
    }

    private fun on(ok: Boolean) = if (ok) "On" else "Off"
    private fun onColor(ok: Boolean) = if (ok) p.muted else iosOrange

    // ===================================================================== pages

    private fun mainPage(c: LinearLayout) {
        val pa = prefs.getBoolean("punjabi", true)

        // Profile-style card at the top (like the Apple ID card)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val avatar = GlyphView(this, Glyph.SPARKLE, Color.WHITE).apply {
            circleBg = true
            glyphScale = 0.55f
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Ui.GEMINI_BLUE, Ui.GEMINI_PURPLE, Ui.GEMINI_PINK)
            ).apply { shape = GradientDrawable.OVAL }
        }
        header.addView(avatar, LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, "Singh AI", 22f, p.text, Ui.medium))
        texts.addView(Ui.text(this, "Your Punjabi + English assistant", 14f, p.muted))
        header.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        section(c, null, listOf(Row(header, false)))

        // ---- AI
        val key = Gemini.apiKey(this)
        section(
            c, "AI", listOf(
                row(
                    "API Key", Glyph.KEY, iosOrange,
                    value = if (key.isEmpty()) "Not set" else "••••" + key.takeLast(4),
                    valueColor = if (key.isEmpty()) iosRed else p.muted, chevron = true
                ) { open(PAGE_APIKEY) },
                row("Model", Glyph.SPARKLE, iosIndigo, value = Gemini.label(Gemini.model(this)), chevron = true) {
                    open(PAGE_MODEL)
                },
                row("Backup Model", Glyph.LAYERS, iosTeal, value = Gemini.label(Gemini.fallbackModel(this)), chevron = true) {
                    open(PAGE_FALLBACK)
                },
                row("Auto Backup", Glyph.SHIELD, iosGreen, toggle = Gemini.fallbackEnabled(this), onToggle = {
                    prefs.edit().putBoolean("fallback_on", it).apply()
                })
            ),
            "If Google is busy (503), Singh AI retries after 1, 2 and 4 seconds, then uses the Backup Model."
        )

        // ---- Voice
        val speed = prefs.getFloat("voice_speed", 1.0f)
        section(
            c, "Voice", listOf(
                row("Talk in", Glyph.GLOBE, iosBlue, value = if (pa) "Punjabi" else "English", chevron = true) {
                    open(PAGE_LANGUAGE)
                },
                row("Voice", Glyph.PERSON, iosOrange, value = VoiceSettings.pitchLabel(this), chevron = true) {
                    open(PAGE_VOICES)
                },
                row("Voice Speed", Glyph.SPEED, iosPink, value = speedLabel(speed), chevron = true) {
                    open(PAGE_SPEED)
                },
                row("Hey Singh AI", Glyph.SPARKLE, iosIndigo, toggle = WakeService.enabled(this), onToggle = {
                    prefs.edit().putBoolean("wake_word", it).apply()
                    if (it) WakeService.start(this) else WakeService.stop(this)
                }),
                row("Auto Listen", Glyph.MIC, iosRed, toggle = prefs.getBoolean("auto_listen", true), onToggle = {
                    prefs.edit().putBoolean("auto_listen", it).apply()
                }),
                row("Keep Listening", Glyph.CHAT, iosGreen, toggle = prefs.getBoolean("keep_talking", true), onToggle = {
                    prefs.edit().putBoolean("keep_talking", it).apply()
                }),
                row("Side Key → Singh AI", Glyph.MIC, iosPurple, chevron = true) {
                    openSetting(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), "Digital assistant app → choose Singh AI")
                }
            ),
            "Hey Singh AI: say \"Singh AI\" while the screen is on — a small popup answers without opening the app (needs Screen Control; uses some battery; Android shows the green mic dot). Side key: shows the popup and listens. Auto Listen: the mic starts when you open the app. Keep Listening: listens again after each answer."
        )

        // ---- Appearance
        val accentName = Ui.accentKey(this).replaceFirstChar { it.uppercase() }
        section(
            c, "Appearance", listOf(
                row("Theme", Glyph.MOON, iosIndigo, value = themeLabel(Ui.themeMode(this)), chevron = true) {
                    open(PAGE_THEME)
                },
                row("Accent Color", Glyph.PALETTE, p.accent, value = accentName, chevron = true) {
                    open(PAGE_ACCENT)
                }
            )
        )

        // ---- Permissions
        val perms = arrayOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS
        )
        val permsOk = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val access = JarvisAccess.instance != null
        val notif = JarvisNotifications.isEnabled(this)
        val bright = Settings.System.canWrite(this)
        section(
            c, "Permissions", listOf(
                row("Mic, Contacts, Call, SMS", Glyph.MIC, iosRed, value = on(permsOk), valueColor = onColor(permsOk), chevron = true) {
                    requestPermissions(perms, REQ_ALL)
                },
                row("Screen Control", Glyph.PERSON, iosBlue, value = on(access), valueColor = onColor(access), chevron = true) {
                    openSetting(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Installed apps → Singh AI screen control → ON")
                },
                row("Read Notifications", Glyph.BELL, iosRed, value = on(notif), valueColor = onColor(notif), chevron = true) {
                    openSetting(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "Turn Singh AI ON")
                },
                row("Change Brightness", Glyph.SUN, iosOrange, value = on(bright), valueColor = onColor(bright), chevron = true) {
                    openSetting(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")), "Tap Allow")
                },
                row("'Restricted setting' Help", Glyph.LOCK, iosGray, chevron = true) { showRestrictedHelp() }
            ),
            "Screen Control lets Singh AI tap, type and scroll in any app, and show its popup over other apps."
        )

        // ---- Memory
        val mem = Memory.all(this)
        section(
            c, "Memory", listOf(
                row("What Singh AI remembers", Glyph.STAR, iosOrange, value = "${mem.size}", chevron = true) { showMemory() }
            ),
            "Say \"remember…\" and Singh AI keeps it forever (your name, family, likes). Say \"forget…\" to remove it."
        )

        // ---- Learning & backup
        section(
            c, "Learning & backup", listOf(
                row("Learned from Google", Glyph.GLOBE, iosBlue, value = "${Knowledge.count(this)}", chevron = true) {
                    AlertDialog.Builder(this, Ui.dialogTheme(this))
                        .setTitle("Learned from Google")
                        .setMessage("${Knowledge.count(this)} answers Singh AI found on Google are saved. " +
                            "If you ask the same thing without internet, it answers from these.")
                        .setPositiveButton("OK", null)
                        .setNegativeButton("Clear") { _, _ -> Knowledge.clear(this); rebuildHere() }
                        .show()
                },
                row("Daily backup", Glyph.CHECK, iosGreen, toggle = prefs.getBoolean("auto_backup", true), onToggle = { on ->
                    prefs.edit().putBoolean("auto_backup", on).apply()
                }),
                row("Backup now", Glyph.FILE, iosOrange, chevron = true) {
                    Toast.makeText(this, "Making backup…", Toast.LENGTH_SHORT).show()
                    Thread {
                        val where = Backup.export(this)
                        runOnUiThread {
                            AlertDialog.Builder(this, Ui.dialogTheme(this))
                                .setTitle(if (where.startsWith("❌")) "Backup failed" else "Backup saved ✓")
                                .setMessage(if (where.startsWith("❌")) where else
                                    "Saved in $where\n\nIt has your memory, playlist, settings and a training.jsonl file " +
                                        "(answers learned from Google — ready to train your own AI). No chats and no API key inside.")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }.start()
                },
                row("Restore from backup", Glyph.FILE, iosGray, chevron = true) {
                    try {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip"), REQ_RESTORE)
                    } catch (e: Exception) {
                        Toast.makeText(this, "No file picker found", Toast.LENGTH_SHORT).show()
                    }
                }
            ),
            "Singh AI keeps what it finds on Google so it can answer the same question later without internet. " +
                "It does not learn from your chats. A backup zip is saved in Downloads/SinghAI once a day."
        )

        // ---- About
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) { "1.0" }
        section(
            c, "About", listOf(
                row("Version", Glyph.INFO, iosGray, value = version),
                row("GitHub", Glyph.CODE, if (p.dark) Color.parseColor("#636366") else Color.parseColor("#1C1C1E"),
                    value = "singh776/Jarvis", chevron = true) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/singh776/Jarvis"))) }
                    catch (e: Exception) {}
                }
            ),
            "New chat: tap the chat icon at the top left of the main screen."
        )
    }

    private fun modelPage(c: LinearLayout, prefKey: String, default: String) {
        val current = prefs.getString(prefKey, "")?.trim().takeUnless { it.isNullOrEmpty() } ?: default
        val rows = Gemini.MODEL_CHOICES.map { (id, label) ->
            row(label, checked = id == current) {
                prefs.edit().putString(prefKey, id).apply()
                modelField?.setText("")
                finish()
            }
        }
        section(c, if (prefKey == "model") "Main model" else "Used when the main model is busy", rows)

        val custom = if (Gemini.MODEL_CHOICES.none { it.first == current }) current else ""
        val f = field("e.g. gemini-2.5-flash", custom, password = false)
        f.tag = prefKey
        f.setOnEditorActionListener { _, _, _ -> saveCustomModel(); finish(); true }
        modelField = f
        section(c, "Custom model ID", listOf(Row(f, false)), "Type any model ID here. Leave it empty to use the one picked above.")

        // every model your API key can use, with a live check of which ones answer
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(holder)
        val loading = Ui.text(this, "Loading the models on your key…", 14f, p.muted).apply { setPadding(dp(16), dp(20), dp(16), 0) }
        holder.addView(loading)
        Thread {
            val models = try { Gemini.listModels(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                holder.removeAllViews()
                if (models.isEmpty()) {
                    holder.addView(Ui.text(this, "Couldn't load models — check the API key and internet.", 14f, p.muted).apply {
                        setPadding(dp(16), dp(20), dp(16), 0)
                    })
                    return@runOnUiThread
                }
                val statusViews = HashMap<String, TextView>()
                val rows = ArrayList<Row>()
                rows.add(row("Test all models", Glyph.CHECK, Color.parseColor("#34C759"), chevron = true) {
                    Thread {
                        for (m in models) {
                            runOnUiThread { statusViews[m]?.text = "…" }
                            val (_, msg) = Gemini.probe(this, m)
                            runOnUiThread { statusViews[m]?.text = msg }
                            Thread.sleep(1200) // gentle, so the free limit isn't hit by the test itself
                        }
                    }.start()
                })
                for (m in models) {
                    val r = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(10), dp(16), dp(10))
                        isClickable = true
                        background = Ui.pressable(Ui.rounded(Color.TRANSPARENT, 0f), Ui.rounded(p.surface2, 0f))
                        setOnClickListener {
                            prefs.edit().putString(prefKey, m).apply()
                            modelField?.setText("")
                            finish()
                        }
                    }
                    r.addView(Ui.text(this, (if (m == current) "✓  " else "") + m, 16f, if (m == current) p.accent else p.text))
                    val st = Ui.text(this, "", 13f, p.muted)
                    r.addView(st)
                    statusViews[m] = st
                    rows.add(Row(r, false))
                }
                section(holder, "All models on your key (${models.size})", rows,
                    "Tap one to use it. \"Test all\" checks which ones answer on your key right now. " +
                        "❌ No free use = that model needs billing in AI Studio. ⏳ = busy or limit for now; Singh AI then uses the next model by itself.")
            }
        }.start()
    }

    private fun saveCustomModel() {
        val f = modelField ?: return
        val key = f.tag as? String ?: return
        val txt = f.text.toString().trim()
        if (txt.isNotEmpty()) prefs.edit().putString(key, txt).apply()
    }

    private fun languagePage(c: LinearLayout) {
        val pa = prefs.getBoolean("punjabi", true)
        section(
            c, null, listOf(
                row("Punjabi  ·  ਪੰਜਾਬੀ", checked = pa) { prefs.edit().putBoolean("punjabi", true).apply(); finish() },
                row("English", checked = !pa) { prefs.edit().putBoolean("punjabi", false).apply(); finish() }
            ),
            "You can also just say \"talk in Punjabi\" or \"talk in English\", or tap the language button on the main screen."
        )
    }

    private fun speedLabel(v: Float) = when {
        v < 0.8f -> "Slow"
        v < 0.95f -> "Relaxed"
        v < 1.1f -> "Normal"
        v < 1.25f -> "Quick"
        v < 1.4f -> "Fast"
        else -> "Very Fast"
    }

    private fun speedPage(c: LinearLayout) {
        val cur = prefs.getFloat("voice_speed", 1.0f)
        val opts = floatArrayOf(0.75f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f)
        section(c, null, opts.map { v ->
            row("${speedLabel(v)}  ·  ${v}×", checked = kotlin.math.abs(v - cur) < 0.01f) {
                prefs.edit().putFloat("voice_speed", v).apply(); finish()
            }
        })
    }

    private fun themeLabel(m: String) = when (m) { "dark" -> "Dark"; "light" -> "Light"; else -> "Automatic" }

    private fun themePage(c: LinearLayout) {
        val cur = Ui.themeMode(this)
        section(c, null, listOf("system", "light", "dark").map { m ->
            row(themeLabel(m), checked = m == cur) { prefs.edit().putString("theme", m).apply(); finish() }
        }, "Automatic follows your phone's Dark / Light setting.")
    }

    private fun accentPage(c: LinearLayout) {
        val cur = Ui.accentKey(this)
        section(c, null, Ui.ACCENTS.map { (k, hex) ->
            row(k.replaceFirstChar { it.uppercase() }, Glyph.SPARKLE, Color.parseColor(hex), circleIcon = true, checked = k == cur) {
                prefs.edit().putString("accent", k).apply(); finish()
            }
        })
    }

    private fun apiKeyPage(c: LinearLayout) {
        val f = field("Gemini API key", Gemini.apiKey(this), password = true)
        f.setOnEditorActionListener { _, _, _ -> saveKey(); true }
        keyField = f
        section(c, "Gemini API key", listOf(Row(f, false)),
            "Singh AI's brain is Google Gemini. Get a free key at aistudio.google.com/apikey → \"Create API key\" → copy → paste here → Save.")
        section(c, null, listOf(
            row("Test key", Glyph.CHECK, iosGreen, chevron = true) { testKey() },
            row("Get a free key", Glyph.KEY, iosOrange, chevron = true) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
                catch (e: Exception) {}
            }
        ))
        f.post { f.requestFocus() }
    }

    /** Asks Google if the key works, shows the exact answer, and fixes the model if needed. */
    private fun testKey() {
        val raw = keyField?.text?.toString() ?: Gemini.apiKey(this)
        val wait = AlertDialog.Builder(this, Ui.dialogTheme(this))
            .setTitle("Test key")
            .setMessage("Checking with Google…")
            .setCancelable(false)
            .show()
        Thread {
            val (ok, text, models) = try {
                Gemini.testKey(raw)
            } catch (e: Exception) {
                Triple(false, "❌ ${e.javaClass.simpleName}: ${e.message}", emptyList<String>())
            }
            var note = ""
            if (ok) {
                prefs.edit().putString("api_key", Gemini.cleanKey(raw)).apply()
                if (models.isNotEmpty()) {
                    val cur = Gemini.model(this)
                    if (cur !in models) {
                        val pick = bestModel(models, lite = false)
                        if (pick != null) {
                            prefs.edit().putString("model", pick).apply()
                            note += "\n\nModel '$cur' doesn't work with this key → switched to '$pick'."
                        }
                    }
                    val back = Gemini.fallbackModel(this)
                    if (back !in models) {
                        val pick = bestModel(models, lite = true)
                        if (pick != null) prefs.edit().putString("fallback_model", pick).apply()
                    }
                }
            }
            runOnUiThread {
                try { wait.dismiss() } catch (e: Exception) {}
                AlertDialog.Builder(this, Ui.dialogTheme(this))
                    .setTitle(if (ok) "Key works" else "Key doesn't work")
                    .setMessage(text + note + (if (ok) "" else
                        "\n\nFix: make a NEW key at aistudio.google.com/apikey, don't rename it, copy it and paste it here."))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    /** Picks a sensible text model from what the key can use. */
    private fun bestModel(models: List<String>, lite: Boolean): String? {
        val wanted = if (lite) listOf("gemini-flash-lite-latest") else listOf("gemini-flash-latest")
        wanted.firstOrNull { it in models }?.let { return it }
        val bad = listOf("image", "tts", "audio", "live", "embedding", "vision", "exp", "preview", "thinking")
        val flash = models.filter { m -> m.contains("flash") && bad.none { m.contains(it) } && m.contains("lite") == lite }
        return flash.maxOrNull() ?: models.firstOrNull { m -> m.contains("flash") && bad.none { m.contains(it) } }
    }

    // ---------------------------------------------------------------- voices

    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false

    private fun voicesPage(c: LinearLayout) {
        if (tts == null) {
            tts = android.speech.tts.TextToSpeech(this, { status ->
                ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
                runOnUiThread { if (page == PAGE_VOICES) try { build() } catch (e: Exception) {} }
            }, VoiceSettings.engine(this))
        }
        // ---- Gemini Live (real-time voice chat)
        section(c, "Gemini Live", listOf(
            row("Real-time voice chat", toggle = prefs.getBoolean("gemini_live", true), onToggle = { on ->
                prefs.edit().putBoolean("gemini_live", on).apply()
            })
        ), "Like the Gemini app: talk and it answers instantly in a natural voice; you can interrupt it any time. " +
            "Tap the big orb to start. Uses the HD voice picked below. Free on the Gemini free tier (needs internet).")

        // ---- HD voices (Gemini's natural voices)
        val hdOn = HdVoice.enabled(this)
        val hdRows = ArrayList<Row>()
        hdRows.add(row("HD voice (Gemini)", toggle = hdOn, onToggle = { on ->
            HdVoice.setEnabled(this, on)
            if (on) previewHd(HdVoice.voice(this))
            rebuildHere()
        }))
        run { // the list is also the voice of Gemini Live
            val cur = HdVoice.voice(this)
            for ((name, style, who) in HdVoice.VOICES) {
                hdRows.add(row(name, value = "$who · $style", checked = name == cur) {
                    HdVoice.setVoice(this, name)
                    previewHd(name)
                    rebuildHere()
                })
            }
        }
        section(c, "HD voices", hdRows,
            "Natural, clear voices like Gemini's. Tap one to hear it. Needs internet and uses your API key " +
                "(the free key has a daily limit — when it's used up or there's no internet, the phone voice below is used by itself).")

        val curPitch = VoiceSettings.pitch(this)
        section(c, "Voice style", VoiceSettings.PITCHES.map { (v, label) ->
            row(label + when (label) { "Deep" -> "  (more like a man)"; "Higher" -> "  (more like a woman)"; else -> "" },
                checked = kotlin.math.abs(v - curPitch) < 0.01f) {
                prefs.edit().putFloat("voice_pitch", v).apply()
                preview("en", null)
                rebuildHere()
            }
        }, "Pick a voice below for the real man / woman sound; the style makes it deeper or higher.")

        if (!ttsReady) {
            section(c, null, listOf(row("Loading voices…")))
            return
        }
        // ---- phone voice engine (free, also works without internet)
        val engines = try { tts?.engines.orEmpty() } catch (e: Exception) { emptyList() }
        if (engines.size > 1) {
            val curEngine = VoiceSettings.engine(this) ?: tts?.defaultEngine
            section(c, "Phone voice engine (free)", engines.map { en ->
                row(en.label, checked = en.name == curEngine) {
                    VoiceSettings.setEngine(this, en.name)
                    // reload voices from the new engine
                    try { tts?.shutdown() } catch (e: Exception) {}
                    tts = null; ttsReady = false
                    rebuildHere()
                }
            }, "Samsung's and Google's engines have different voices — try both. Voices marked HD sound the most natural.")
        }

        val all = try { tts?.voices?.toList().orEmpty() } catch (e: Exception) { emptyList() }
        for ((lang, title) in listOf("en" to "English voices", "pa" to "Punjabi voices", "hi" to "Hindi voices")) {
            val list = all.filter { v ->
                v.locale.language == lang && (lang != "en" || v.locale.country in listOf("IN", "US", "GB", "AU"))
            }.filter { !it.features.orEmpty().contains("notInstalled") }
                .sortedWith(compareBy({ !VoiceSettings.isHd(it) }, { it.locale.country != "IN" }, { it.isNetworkConnectionRequired }, { it.name }))
                .take(24)
            if (list.isEmpty()) continue
            val saved = VoiceSettings.savedVoice(this, lang)
            val rows = ArrayList<Row>()
            rows.add(row("Automatic", checked = saved == null) {
                VoiceSettings.saveVoice(this, lang, null); preview(lang, null); rebuildHere()
            })
            list.forEachIndexed { i, v ->
                val tag = (if (VoiceSettings.isHd(v)) "HD · " else "") +
                    (if (v.locale.country.isNotEmpty()) v.locale.country + " · " else "") +
                    (if (v.isNetworkConnectionRequired) "online" else "offline")
                rows.add(row(VoiceSettings.label(v).ifBlank { "Voice ${('A' + i)}" }, value = tag, checked = saved == v.name) {
                    VoiceSettings.saveVoice(this, lang, v.name); preview(lang, v); rebuildHere()
                })
            }
            section(c, title, rows, if (lang == "en") "Tap a voice to hear it." else null)
        }
        section(c, null, listOf(
            row("Get more voices", Glyph.GLOBE, iosBlue, chevron = true) {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) {
                    try { startActivity(Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (x: Exception) {}
                }
            }
        ), "Settings → Text-to-speech → Google engine → ⚙ → Install voice data → download English (India) and ਪੰਜਾਬੀ.")
    }

    private fun previewHd(name: String) {
        Toast.makeText(this, "Loading $name…", Toast.LENGTH_SHORT).show()
        HdVoice.speak(this, "Sat Sri Akal! Main Singh AI haan. Hello, I am Singh AI — how can I help you today?",
            onStart = {}, onDone = {},
            onFail = {
                AlertDialog.Builder(this, Ui.dialogTheme(this))
                    .setTitle("HD voice didn't work")
                    .setMessage(HdVoice.lastError.ifBlank { "No answer from Google." } +
                        "\n\nGemini HD voices need the free daily limit on your key. If your key has none, use the free phone voices below " +
                        "— pick an engine (Google / Samsung) and a voice marked HD.")
                    .setPositiveButton("OK", null).show()
            },
            voiceName = name)
    }

    private fun preview(lang: String, v: android.speech.tts.Voice?) {
        val t = tts ?: return
        if (!ttsReady) return
        val sample = when (lang) {
            "pa" -> "ਸਤ ਸ੍ਰੀ ਅਕਾਲ, ਮੈਂ ਸਿੰਘ AI ਹਾਂ।"
            "hi" -> "नमस्ते, मैं सिंह AI हूँ।"
            else -> "Hello, I am Singh AI. How can I help?"
        }
        VoiceSettings.apply(this, t, sample, punjabiVoice = true)
        if (v != null) try { t.voice = v } catch (e: Exception) {}
        t.speak(sample, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_RESTORE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val ok = Backup.restore(this, uri)
            runOnUiThread {
                Toast.makeText(this, if (ok) "Restored ✓" else "Couldn't read that backup", Toast.LENGTH_LONG).show()
                if (ok) rebuildHere()
            }
        }.start()
    }

    override fun onDestroy() {
        tts?.shutdown()
        HdVoice.stop()
        super.onDestroy()
    }

    private fun saveKey() {
        val f = keyField ?: return
        prefs.edit().putString("api_key", Gemini.cleanKey(f.text.toString())).apply()
        Toast.makeText(this, "Saved ✓", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ===================================================================== helpers

    private fun openSetting(i: Intent, hint: String) {
        try {
            startActivity(i)
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun showMemory() {
        val mem = Memory.all(this)
        AlertDialog.Builder(this, Ui.dialogTheme(this))
            .setTitle("Memory (${mem.size})")
            .setMessage(if (mem.isEmpty()) "Nothing saved yet. Say: \"remember my name is …\"." else mem.joinToString("\n") { "• $it" })
            .setPositiveButton("OK", null)
            .setNegativeButton("Clear all") { _, _ ->
                AlertDialog.Builder(this, Ui.dialogTheme(this))
                    .setMessage("Delete everything Singh AI remembers?")
                    .setPositiveButton("Delete") { _, _ -> Memory.clear(this); build() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }

    private fun showRestrictedHelp() {
        AlertDialog.Builder(this, Ui.dialogTheme(this))
            .setTitle("Restricted setting")
            .setMessage(
                "Android blocks Screen Control for apps installed outside the Play Store.\n\n" +
                    "1. 'App info' opens now.\n" +
                    "2. Tap ⋮ (3 dots, top right) → \"Allow restricted settings\".\n" +
                    "3. Then Settings → Screen Control → ON."
            )
            .setPositiveButton("Open App info") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_ALL) {
            val ok = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            Toast.makeText(this, "$ok/${grantResults.size} permissions ✓", Toast.LENGTH_SHORT).show()
            build()
        }
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_MAIN = "main"
        const val PAGE_MODEL = "model"
        const val PAGE_FALLBACK = "fallback"
        const val PAGE_LANGUAGE = "language"
        const val PAGE_SPEED = "speed"
        const val PAGE_THEME = "theme"
        const val PAGE_ACCENT = "accent"
        const val PAGE_APIKEY = "apikey"
        const val PAGE_VOICES = "voices"
        private const val REQ_RESTORE = 77
        private const val REQ_ALL = 8
    }
}
