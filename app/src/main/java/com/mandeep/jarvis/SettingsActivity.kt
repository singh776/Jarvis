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
        build() // refresh values / permission status every time we come back
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        build()
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

    private fun build() {
        p = Ui.palette(this)
        Ui.setupWindow(this, p)

        val root = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
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
            addView(Ui.text(context, if (isMain) "Jarvis" else getString0(), 17f, p.accent))
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
        texts.addView(Ui.text(this, "Jarvis", 22f, p.text, Ui.medium))
        texts.addView(Ui.text(this, "Gemini · ਪੰਜਾਬੀ + English", 14f, p.muted))
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
            "Server busy (503) hove taan Jarvis 1, 2 te 4 second baad dubara koshish karda hai, fer Backup Model warat'da hai."
        )

        // ---- Voice
        val speed = prefs.getFloat("voice_speed", 1.0f)
        section(
            c, "Voice", listOf(
                row("Language", Glyph.GLOBE, iosBlue, value = if (pa) "ਪੰਜਾਬੀ" else "English", chevron = true) {
                    open(PAGE_LANGUAGE)
                },
                row("Voice Speed", Glyph.SPEED, iosPink, value = speedLabel(speed), chevron = true) {
                    open(PAGE_SPEED)
                },
                row("Keep Listening", Glyph.CHAT, iosGreen, toggle = prefs.getBoolean("keep_talking", true), onToggle = {
                    prefs.edit().putBoolean("keep_talking", it).apply()
                }),
                row("Side Key → Jarvis", Glyph.MIC, iosPurple, chevron = true) {
                    openSetting(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), "Digital assistant app → Jarvis chuno")
                }
            ),
            "Keep Listening: Jarvis sawal puchhe taan aap hi fer sunda hai. Side key lamba dabao te Jarvis khul ke sunna shuru kar dinda hai."
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
                    openSetting(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Installed apps → Jarvis screen control → ON")
                },
                row("Read Notifications", Glyph.BELL, iosRed, value = on(notif), valueColor = onColor(notif), chevron = true) {
                    openSetting(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "Jarvis nu ON karo")
                },
                row("Change Brightness", Glyph.SUN, iosOrange, value = on(bright), valueColor = onColor(bright), chevron = true) {
                    openSetting(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")), "Allow karo")
                },
                row("'Restricted setting' Help", Glyph.LOCK, iosGray, chevron = true) { showRestrictedHelp() }
            ),
            "Screen Control naal Jarvis kise vi app vich tap, type te scroll kar sakda hai."
        )

        // ---- About
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) { "1.0" }
        section(
            c, "About", listOf(
                row("Version", Glyph.INFO, iosGray, value = version),
                row("GitHub", Glyph.CODE, if (p.dark) Color.parseColor("#636366") else Color.parseColor("#1C1C1E"),
                    value = "singh776/Jarviss", chevron = true) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/singh776/Jarviss"))) }
                    catch (e: Exception) {}
                }
            ),
            "Nawi gall-baat: main screen te gole nu lamba dabao."
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
        section(c, "Custom model ID", listOf(Row(f, false)), "Model da naam ethe likh sakde ho. Khaali chhado taan upar wala chuniya model chalega.")
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
                row("ਪੰਜਾਬੀ (Punjabi)", checked = pa) { prefs.edit().putBoolean("punjabi", true).apply(); finish() },
                row("English", checked = !pa) { prefs.edit().putBoolean("punjabi", false).apply(); finish() }
            ),
            "Main screen te ਪੰਜਾਬੀ / EN button naal vi badal sakde ho."
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
        }, "Automatic: phone di setting naal Dark / Light.")
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
            "Jarvis da dimaag Google Gemini hai. Muft key lao: aistudio.google.com/apikey → \"Create API key\" → copy karke ethe paste karo → Save.")
        section(c, null, listOf(
            row("Get a free key", Glyph.KEY, iosOrange, chevron = true) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
                catch (e: Exception) {}
            }
        ))
        f.post { f.requestFocus() }
    }

    private fun saveKey() {
        val f = keyField ?: return
        prefs.edit().putString("api_key", f.text.toString().trim()).apply()
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

    private fun showRestrictedHelp() {
        AlertDialog.Builder(this, Ui.dialogTheme(this))
            .setTitle("Restricted setting")
            .setMessage(
                "Android GitHub ton install hoyi app nu Screen control ON karan ton rokda hai.\n\n" +
                    "1. Hun 'App info' khulega.\n" +
                    "2. Upar sajje ⋮ (3 dots) dabao → \"Allow restricted settings\".\n" +
                    "3. Fer Settings → Screen Control → ON karo."
            )
            .setPositiveButton("App info kholo") { _, _ ->
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
        private const val REQ_ALL = 8
    }
}
