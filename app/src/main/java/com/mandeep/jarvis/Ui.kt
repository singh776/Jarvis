package com.mandeep.jarvis

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.TextView

/** Colors, fonts and small view helpers shared by every screen (dark + light mode). */
object Ui {

    class Palette(
        val dark: Boolean,
        val bg: Int,          // screen background
        val surface: Int,     // cards / grouped sections / Jarvis bubbles
        val surface2: Int,    // input pill, pressed rows
        val text: Int,
        val muted: Int,
        val separator: Int,
        val accent: Int,
        val onAccent: Int
    )

    /** Accent colors offered in Settings → Appearance. */
    val ACCENTS = listOf(
        "blue" to "#4C8DFF",
        "purple" to "#9B72FF",
        "pink" to "#FF5FA2",
        "cyan" to "#22C7E8",
        "green" to "#30C85E",
        "orange" to "#FF9F0A"
    )

    /** Gemini-style gradient colors. */
    val GEMINI_BLUE = Color.parseColor("#4C8DFF")
    val GEMINI_PURPLE = Color.parseColor("#9B72FF")
    val GEMINI_PINK = Color.parseColor("#FF5FA2")

    private fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    /** "system", "dark" or "light". */
    fun themeMode(c: Context): String = prefs(c).getString("theme", "system") ?: "system"

    fun accentKey(c: Context): String = prefs(c).getString("accent", "blue") ?: "blue"

    fun isDark(c: Context): Boolean = when (themeMode(c)) {
        "dark" -> true
        "light" -> false
        else -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun accentColor(c: Context): Int =
        Color.parseColor(ACCENTS.firstOrNull { it.first == accentKey(c) }?.second ?: ACCENTS[0].second)

    fun palette(c: Context): Palette {
        val accent = accentColor(c)
        return if (isDark(c)) Palette(
            dark = true,
            bg = Color.parseColor("#000000"),
            surface = Color.parseColor("#1C1C1E"),
            surface2 = Color.parseColor("#2C2C2E"),
            text = Color.parseColor("#F2F2F7"),
            muted = Color.parseColor("#8E8E93"),
            separator = Color.parseColor("#38383A"),
            accent = accent,
            onAccent = Color.WHITE
        ) else Palette(
            dark = false,
            bg = Color.parseColor("#F2F2F7"),
            surface = Color.parseColor("#FFFFFF"),
            surface2 = Color.parseColor("#E5E5EA"),
            text = Color.parseColor("#000000"),
            muted = Color.parseColor("#6C6C70"),
            separator = Color.parseColor("#C6C6C8"),
            accent = accent,
            onAccent = Color.WHITE
        )
    }

    /** Changes whenever the look changes, so screens know to rebuild. */
    fun signature(c: Context): String = "${isDark(c)}|${accentKey(c)}"

    // ------------------------------------------------------------------ fonts

    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    // ---------------------------------------------------------------- helpers

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(c: Context, v: Float): Float = v * c.resources.displayMetrics.density

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, stroke)
        }

    fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** Background that darkens a little while pressed (like iOS table rows). */
    fun pressable(normal: Drawable, pressed: Drawable): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }

    fun pressableColor(normal: Int, pressed: Int): StateListDrawable =
        pressable(ColorDrawable(normal), ColorDrawable(pressed))

    fun text(c: Context, s: CharSequence, sizeSp: Float, color: Int, face: Typeface = regular): TextView =
        TextView(c).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            typeface = face
            includeFontPadding = true
        }

    fun dialogTheme(c: Context): Int =
        if (isDark(c)) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    /** Draw behind the status / nav bars, with the right icon color for the theme. */
    @Suppress("DEPRECATION")
    fun setupWindow(a: Activity, p: Palette) {
        val w = a.window
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        w.setBackgroundDrawable(ColorDrawable(p.bg))
        if (Build.VERSION.SDK_INT >= 29) {
            w.isNavigationBarContrastEnforced = false
            w.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (p.dark) 0 else light, light)
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (!p.dark) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            w.decorView.systemUiVisibility = flags
        }
    }

    /** Calls [apply] with (top, bottom) insets: status bar on top, nav bar or keyboard at the bottom. */
    @Suppress("DEPRECATION")
    fun onInsets(v: View, apply: (top: Int, bottom: Int) -> Unit) {
        v.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                apply(bars.top, maxOf(bars.bottom, ime.bottom))
                WindowInsets.CONSUMED
            } else {
                apply(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
                insets.consumeSystemWindowInsets()
            }
        }
        v.requestApplyInsets()
    }
}
