package com.mandeep.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/*
 * Gemini-style pieces for the main screen and the Live screen.
 * All of them animate with postInvalidateOnAnimation() — one frame per screen refresh,
 * no new objects per frame — so the motion stays smooth (no stutter).
 */

/** Google colors used by the sparkle. */
object GColors {
    val BLUE = Color.parseColor("#4285F4")
    val RED = Color.parseColor("#EA4335")
    val YELLOW = Color.parseColor("#FBBC04")
    val GREEN = Color.parseColor("#34A853")
    val LIVE_BLUE = Color.parseColor("#0B57D0")
}

/**
 * The colorful 4-point sparkle (like Gemini's logo). Breathes gently when idle,
 * turns and pulses while listening / thinking / speaking.
 * Has the same mode / level / dark fields the old orb had.
 */
class SparkleView(context: Context) : View(context) {

    var mode: OrbView.Mode = OrbView.Mode.IDLE
        set(v) { field = v; if (v != OrbView.Mode.LISTENING) level = 0f; postInvalidateOnAnimation() }

    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f) }

    var dark = false

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val m = Matrix()
    private val shader = SweepGradient(
        0f, 0f,
        intArrayOf(GColors.BLUE, GColors.BLUE, GColors.RED, GColors.YELLOW, GColors.GREEN, GColors.BLUE),
        floatArrayOf(0f, 0.3f, 0.45f, 0.6f, 0.78f, 1f)
    )
    private val glow = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.argb(90, 66, 133, 244), Color.argb(0, 66, 133, 244)),
        null, Shader.TileMode.CLAMP
    )

    private var t = 0f
    private var last = 0L
    private var spin = 0f
    private var energy = 0f
    private var smooth = 0f
    private var intro = 0f

    init { isClickable = true }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = 0L
        intro = 0f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        t += dt
        smooth += (level - smooth) * 0.25f
        val target = when (mode) {
            OrbView.Mode.IDLE -> 0f
            OrbView.Mode.LISTENING -> 0.5f + smooth * 0.5f
            OrbView.Mode.THINKING -> 0.8f
            OrbView.Mode.SPEAKING -> 0.7f
        }
        energy += (target - energy) * 0.08f
        spin += dt * (12f + energy * (if (mode == OrbView.Mode.THINKING) 220f else 70f))
        if (intro < 1f) intro = (intro + dt / 0.6f).coerceAtMost(1f)

        val cx = width / 2f
        val cy = height / 2f
        val half = min(width, height) / 2f
        if (half <= 0f) return
        val e = 1f - (1f - intro) * (1f - intro) * (1f - intro) // ease-out pop-in
        val breathe = 1f + 0.03f * sin(t * 1.8f) + energy * 0.08f * sin(t * 6f) + smooth * 0.12f
        val r = half * 0.82f * breathe * (0.7f + 0.3f * e)

        // soft blue glow behind the star while active
        if (energy > 0.02f) {
            m.setScale(half, half); m.postTranslate(cx, cy)
            glow.setLocalMatrix(m)
            glowPaint.shader = glow
            glowPaint.alpha = (255 * energy).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, half, glowPaint)
        }

        path.reset()
        Glyphs.star4(path, 0f, 0f, r)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(energy * 8f * sin(t * 2f))
        m.setRotate(spin - 90f)
        shader.setLocalMatrix(m)
        paint.shader = shader
        paint.alpha = (255 * e).toInt()
        canvas.drawPath(path, paint)
        canvas.restore()

        if (isAttachedToWindow && visibility == VISIBLE) postInvalidateOnAnimation()
    }
}

/** The light-blue round button with three bars (opens the Live screen). */
class LiveButton(context: Context) : View(context) {
    var dark = false
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }

    init { isClickable = true }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) / 2f
        bg.color = if (dark) Color.parseColor("#1F3A63") else Color.parseColor("#D3E3FD")
        bg.alpha = if (isPressed) 200 else 255
        canvas.drawCircle(w / 2, h / 2, r, bg)
        bar.color = if (dark) Color.parseColor("#A8C7FA") else GColors.LIVE_BLUE
        bar.strokeWidth = r * 0.13f
        val gap = r * 0.3f
        val hs = floatArrayOf(0.28f, 0.55f, 0.28f)
        for (i in 0..2) {
            val x = w / 2 + (i - 1) * gap
            val hh = r * hs[i]
            canvas.drawLine(x, h / 2 - hh, x, h / 2 + hh, bar)
        }
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
}

/**
 * The big glowing pill in the middle of the Live screen's bottom row:
 * white on top, Gemini blue light at the bottom that flows and grows with your voice.
 */
class LivePill(context: Context) : View(context) {
    var mode: OrbView.Mode = OrbView.Mode.IDLE
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f) }
    var dark = false

    private val box = RectF()
    private val clip = Path()
    private val base = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val m = Matrix()
    private var baseShader: LinearGradient? = null
    private var baseH = -1f
    private val blobs = intArrayOf(Color.parseColor("#4C8DFF"), Color.parseColor("#3D7BFF"), Color.parseColor("#7FB2FF"))
        .map {
            RadialGradient(0f, 0f, 1f,
                intArrayOf(Color.argb(230, Color.red(it), Color.green(it), Color.blue(it)), Color.argb(0, Color.red(it), Color.green(it), Color.blue(it))),
                null, Shader.TileMode.CLAMP)
        }
    private var t = 0f
    private var last = 0L
    private var amt = 0.3f
    private var smooth = 0f

    init { isClickable = true }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); last = 0L; postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        t += dt
        smooth += (level - smooth) * 0.25f
        val target = when (mode) {
            OrbView.Mode.LISTENING -> 0.6f + smooth * 0.6f
            OrbView.Mode.SPEAKING -> 0.75f + 0.15f * sin(t * 5f)
            OrbView.Mode.THINKING -> 0.55f + 0.1f * sin(t * 3f)
            OrbView.Mode.IDLE -> 0.3f
        }
        amt += (target - amt) * 0.1f

        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        box.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(box, h / 2, h / 2, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        if (baseH != h) {
            baseShader = LinearGradient(0f, 0f, 0f, h,
                intArrayOf(if (dark) Color.parseColor("#2A2F3A") else Color.WHITE,
                    if (dark) Color.parseColor("#22324F") else Color.parseColor("#F3F7FF")),
                null, Shader.TileMode.CLAMP)
            baseH = h
        }
        base.shader = baseShader
        canvas.drawRect(box, base)
        val speed = if (mode == OrbView.Mode.THINKING) 1.6f else 0.8f
        for (i in blobs.indices) {
            val ph = i * 2.1f + t * speed * (if (i % 2 == 0) 1f else -0.7f)
            val x = w * (0.5f + 0.38f * sin(ph))
            val rx = w * (0.45f + 0.1f * sin(ph * 0.6f + i))
            val ry = h * (0.55f + 0.45f * amt)
            m.setScale(rx, ry)
            m.postTranslate(x, h * 1.05f)
            blobs[i].setLocalMatrix(m)
            blob.shader = blobs[i]
            blob.alpha = (255 * amt.coerceIn(0f, 1f)).toInt()
            canvas.drawRect(box, blob)
        }
        canvas.restore()
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}
