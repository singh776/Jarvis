package com.mandeep.jarvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Simple line icons drawn in code (no image files needed). */
enum class Glyph {
    GEAR, SEND, MIC, KEY, SPARKLE, LAYERS, GLOBE, SPEED, CHAT, MOON, PALETTE, SHIELD, BELL, SUN, PERSON,
    STAR, INFO, CODE, LOCK, CHECK, BACK, CHEVRON, TRASH
}

object Glyphs {

    /** Draws [g] centered at (cx, cy) inside a box of [size]. Uses [p]'s color; changes its style. */
    fun draw(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, p: Paint) {
        val s = size / 2f
        val sw = size * 0.09f
        p.strokeWidth = sw
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.style = Paint.Style.STROKE
        val path = Path()
        val r = RectF()
        when (g) {
            Glyph.GEAR -> {
                c.drawCircle(cx, cy, s * 0.32f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val x1 = cx + cos(a).toFloat() * s * 0.62f
                    val y1 = cy + sin(a).toFloat() * s * 0.62f
                    val x2 = cx + cos(a).toFloat() * s * 0.88f
                    val y2 = cy + sin(a).toFloat() * s * 0.88f
                    p.strokeWidth = sw * 1.5f
                    c.drawLine(x1, y1, x2, y2, p)
                }
                p.strokeWidth = sw
                c.drawCircle(cx, cy, s * 0.64f, p)
            }
            Glyph.SEND -> {
                p.strokeWidth = sw * 1.3f
                c.drawLine(cx, cy + s * 0.6f, cx, cy - s * 0.6f, p)
                path.moveTo(cx - s * 0.5f, cy - s * 0.1f)
                path.lineTo(cx, cy - s * 0.62f)
                path.lineTo(cx + s * 0.5f, cy - s * 0.1f)
                c.drawPath(path, p)
            }
            Glyph.MIC -> {
                p.strokeWidth = sw * 1.2f
                r.set(cx - s * 0.26f, cy - s * 0.8f, cx + s * 0.26f, cy + s * 0.2f)
                c.drawRoundRect(r, s * 0.26f, s * 0.26f, p)
                r.set(cx - s * 0.52f, cy - s * 0.4f, cx + s * 0.52f, cy + s * 0.45f)
                c.drawArc(r, 0f, 180f, false, p)
                c.drawLine(cx, cy + s * 0.45f, cx, cy + s * 0.8f, p)
            }
            Glyph.KEY -> {
                c.drawCircle(cx - s * 0.38f, cy, s * 0.3f, p)
                c.drawLine(cx - s * 0.08f, cy, cx + s * 0.75f, cy, p)
                c.drawLine(cx + s * 0.55f, cy, cx + s * 0.55f, cy + s * 0.3f, p)
                c.drawLine(cx + s * 0.75f, cy, cx + s * 0.75f, cy + s * 0.3f, p)
            }
            Glyph.SPARKLE -> {
                p.style = Paint.Style.FILL
                star4(path, cx, cy, s * 0.85f)
                c.drawPath(path, p)
            }
            Glyph.LAYERS -> {
                for (k in 0..1) {
                    val dy = k * s * 0.35f
                    path.reset()
                    path.moveTo(cx, cy - s * 0.55f + dy)
                    path.lineTo(cx + s * 0.75f, cy - s * 0.18f + dy)
                    path.lineTo(cx, cy + s * 0.2f + dy)
                    path.lineTo(cx - s * 0.75f, cy - s * 0.18f + dy)
                    path.close()
                    c.drawPath(path, p)
                }
            }
            Glyph.GLOBE -> {
                c.drawCircle(cx, cy, s * 0.75f, p)
                r.set(cx - s * 0.32f, cy - s * 0.75f, cx + s * 0.32f, cy + s * 0.75f)
                c.drawOval(r, p)
                c.drawLine(cx - s * 0.75f, cy, cx + s * 0.75f, cy, p)
            }
            Glyph.SPEED -> {
                r.set(cx - s * 0.75f, cy - s * 0.6f, cx + s * 0.75f, cy + s * 0.9f)
                c.drawArc(r, 160f, 220f, false, p)
                c.drawLine(cx, cy + s * 0.15f, cx + s * 0.4f, cy - s * 0.3f, p)
            }
            Glyph.CHAT -> {
                r.set(cx - s * 0.75f, cy - s * 0.6f, cx + s * 0.75f, cy + s * 0.35f)
                c.drawRoundRect(r, s * 0.3f, s * 0.3f, p)
                path.moveTo(cx - s * 0.35f, cy + s * 0.35f)
                path.lineTo(cx - s * 0.5f, cy + s * 0.75f)
                path.lineTo(cx, cy + s * 0.35f)
                c.drawPath(path, p)
            }
            Glyph.MOON -> {
                p.style = Paint.Style.FILL
                path.addCircle(cx, cy, s * 0.7f, Path.Direction.CW)
                val cut = Path().apply { addCircle(cx + s * 0.38f, cy - s * 0.3f, s * 0.55f, Path.Direction.CW) }
                path.op(cut, Path.Op.DIFFERENCE)
                c.drawPath(path, p)
            }
            Glyph.PALETTE -> {
                c.drawCircle(cx, cy, s * 0.75f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx - s * 0.3f, cy - s * 0.25f, s * 0.12f, p)
                c.drawCircle(cx + s * 0.2f, cy - s * 0.35f, s * 0.12f, p)
                c.drawCircle(cx + s * 0.38f, cy + s * 0.1f, s * 0.12f, p)
                c.drawCircle(cx - s * 0.2f, cy + s * 0.35f, s * 0.12f, p)
            }
            Glyph.SHIELD -> {
                path.moveTo(cx, cy - s * 0.8f)
                path.lineTo(cx + s * 0.65f, cy - s * 0.5f)
                path.quadTo(cx + s * 0.6f, cy + s * 0.5f, cx, cy + s * 0.85f)
                path.quadTo(cx - s * 0.6f, cy + s * 0.5f, cx - s * 0.65f, cy - s * 0.5f)
                path.close()
                c.drawPath(path, p)
            }
            Glyph.BELL -> {
                path.moveTo(cx - s * 0.6f, cy + s * 0.4f)
                path.quadTo(cx - s * 0.45f, cy + s * 0.2f, cx - s * 0.45f, cy - s * 0.1f)
                path.quadTo(cx - s * 0.45f, cy - s * 0.7f, cx, cy - s * 0.7f)
                path.quadTo(cx + s * 0.45f, cy - s * 0.7f, cx + s * 0.45f, cy - s * 0.1f)
                path.quadTo(cx + s * 0.45f, cy + s * 0.2f, cx + s * 0.6f, cy + s * 0.4f)
                path.close()
                c.drawPath(path, p)
                c.drawLine(cx - s * 0.15f, cy + s * 0.7f, cx + s * 0.15f, cy + s * 0.7f, p)
            }
            Glyph.SUN -> {
                c.drawCircle(cx, cy, s * 0.33f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(
                        cx + cos(a).toFloat() * s * 0.55f, cy + sin(a).toFloat() * s * 0.55f,
                        cx + cos(a).toFloat() * s * 0.8f, cy + sin(a).toFloat() * s * 0.8f, p
                    )
                }
            }
            Glyph.PERSON -> {
                c.drawCircle(cx, cy - s * 0.45f, s * 0.25f, p)
                c.drawLine(cx - s * 0.65f, cy - s * 0.05f, cx + s * 0.65f, cy - s * 0.05f, p)
                c.drawLine(cx, cy - s * 0.05f, cx, cy + s * 0.3f, p)
                c.drawLine(cx, cy + s * 0.3f, cx - s * 0.35f, cy + s * 0.8f, p)
                c.drawLine(cx, cy + s * 0.3f, cx + s * 0.35f, cy + s * 0.8f, p)
            }
            Glyph.STAR -> {
                p.style = Paint.Style.FILL
                for (i in 0 until 10) {
                    val rad = if (i % 2 == 0) s * 0.85f else s * 0.38f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val x = cx + cos(a).toFloat() * rad
                    val y = cy + sin(a).toFloat() * rad
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, p)
            }
            Glyph.INFO -> {
                c.drawCircle(cx, cy, s * 0.75f, p)
                c.drawLine(cx, cy - s * 0.05f, cx, cy + s * 0.4f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy - s * 0.35f, sw * 0.8f, p)
            }
            Glyph.CODE -> {
                path.moveTo(cx - s * 0.3f, cy - s * 0.5f); path.lineTo(cx - s * 0.8f, cy); path.lineTo(cx - s * 0.3f, cy + s * 0.5f)
                path.moveTo(cx + s * 0.3f, cy - s * 0.5f); path.lineTo(cx + s * 0.8f, cy); path.lineTo(cx + s * 0.3f, cy + s * 0.5f)
                c.drawPath(path, p)
                c.drawLine(cx + s * 0.12f, cy - s * 0.65f, cx - s * 0.12f, cy + s * 0.65f, p)
            }
            Glyph.LOCK -> {
                r.set(cx - s * 0.6f, cy - s * 0.1f, cx + s * 0.6f, cy + s * 0.75f)
                c.drawRoundRect(r, s * 0.12f, s * 0.12f, p)
                r.set(cx - s * 0.38f, cy - s * 0.75f, cx + s * 0.38f, cy + s * 0.1f)
                c.drawArc(r, 180f, 180f, false, p)
            }
            Glyph.CHECK -> {
                p.strokeWidth = sw * 1.4f
                path.moveTo(cx - s * 0.6f, cy)
                path.lineTo(cx - s * 0.15f, cy + s * 0.45f)
                path.lineTo(cx + s * 0.65f, cy - s * 0.45f)
                c.drawPath(path, p)
            }
            Glyph.BACK -> {
                p.strokeWidth = sw * 1.4f
                path.moveTo(cx + s * 0.3f, cy - s * 0.75f)
                path.lineTo(cx - s * 0.4f, cy)
                path.lineTo(cx + s * 0.3f, cy + s * 0.75f)
                c.drawPath(path, p)
            }
            Glyph.CHEVRON -> {
                p.strokeWidth = sw * 1.3f
                path.moveTo(cx - s * 0.25f, cy - s * 0.6f)
                path.lineTo(cx + s * 0.3f, cy)
                path.lineTo(cx - s * 0.25f, cy + s * 0.6f)
                c.drawPath(path, p)
            }
            Glyph.TRASH -> {
                c.drawLine(cx - s * 0.7f, cy - s * 0.5f, cx + s * 0.7f, cy - s * 0.5f, p)
                c.drawLine(cx - s * 0.2f, cy - s * 0.75f, cx + s * 0.2f, cy - s * 0.75f, p)
                path.moveTo(cx - s * 0.5f, cy - s * 0.5f)
                path.lineTo(cx - s * 0.4f, cy + s * 0.8f)
                path.lineTo(cx + s * 0.4f, cy + s * 0.8f)
                path.lineTo(cx + s * 0.5f, cy - s * 0.5f)
                c.drawPath(path, p)
            }
        }
    }

    /** Gemini-style 4-point sparkle. */
    fun star4(path: Path, cx: Float, cy: Float, r: Float) {
        val k = r * 0.12f
        path.moveTo(cx, cy - r)
        path.quadTo(cx + k, cy - k, cx + r, cy)
        path.quadTo(cx + k, cy + k, cx, cy + r)
        path.quadTo(cx - k, cy + k, cx - r, cy)
        path.quadTo(cx - k, cy - k, cx, cy - r)
        path.close()
    }
}

/** A view that shows one [Glyph], optionally on a colored rounded square (iOS settings icon) or circle. */
class GlyphView(context: Context, var glyph: Glyph, var color: Int) : View(context) {
    /** 0 = no background. */
    var bgColor: Int = 0
    var circleBg: Boolean = false
    var glyphScale = 0.55f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (bgColor != 0) {
            bgPaint.color = bgColor
            if (circleBg) canvas.drawCircle(w / 2, h / 2, min(w, h) / 2, bgPaint)
            else {
                box.set(0f, 0f, w, h)
                canvas.drawRoundRect(box, min(w, h) * 0.24f, min(w, h) * 0.24f, bgPaint)
            }
        }
        paint.color = color
        paint.alpha = if (isPressed) 150 else 255
        Glyphs.draw(canvas, glyph, w / 2, h / 2, min(w, h) * glyphScale, paint)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/** Round mic button with a Gemini gradient and a pulsing glow while listening. */
class MicButton(context: Context) : View(context) {

    var active = false
        set(v) { field = v; invalidate() }

    /** Mic loudness 0..1. */
    var level = 0f

    var iconColor = Color.WHITE
    var ringBg = Color.parseColor("#2C2C2E")

    private var t = 0f
    private var smooth = 0f
    private var glowAmt = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = intArrayOf(Ui.GEMINI_BLUE, Ui.GEMINI_PURPLE, Ui.GEMINI_PINK, Ui.GEMINI_BLUE)

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t += 0.016f
            smooth += (level - smooth) * 0.25f
            glowAmt += ((if (active) 1f else 0f) - glowAmt) * 0.12f
            if (active || glowAmt > 0.01f) invalidate()
        }
    }

    init {
        isClickable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null) // for the blur glow
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.72f

        // pulsing glow rings
        if (glowAmt > 0.01f) {
            for (k in 0..1) {
                val phase = ((t * 0.9f + k * 0.5f) % 1f)
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.color = Ui.GEMINI_PURPLE
                paint.alpha = ((1f - phase) * 90 * glowAmt).toInt()
                canvas.drawCircle(cx, cy, r * (1f + phase * 0.38f + smooth * 0.1f), paint)
            }
        }

        // body
        paint.style = Paint.Style.FILL
        if (active) {
            paint.shader = SweepGradient(cx, cy, colors, null).also {
                val m = android.graphics.Matrix(); m.setRotate(t * 120f, cx, cy); it.setLocalMatrix(m)
            }
            paint.alpha = 255
        } else {
            paint.shader = null
            paint.color = ringBg
        }
        canvas.drawCircle(cx, cy, r * (if (isPressed) 0.94f else 1f), paint)
        paint.shader = null

        icon.color = if (active) Color.WHITE else iconColor
        Glyphs.draw(canvas, Glyph.MIC, cx, cy, r * 0.95f, icon)
    }
}

/** Text with the Gemini gradient (for the title). */
class GradientText(context: Context) : android.widget.TextView(context) {
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val tw = paint.measureText(text.toString())
        val start = (w - tw) / 2f
        paint.shader = LinearGradient(
            start, 0f, start + tw, 0f,
            intArrayOf(Ui.GEMINI_BLUE, Ui.GEMINI_PURPLE, Ui.GEMINI_PINK), null, Shader.TileMode.CLAMP
        )
    }
}
