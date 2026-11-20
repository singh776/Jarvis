package com.mandeep.jarvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Gemini Live–style orb: soft blue / purple / pink glows that drift together.
 * Breathes when idle, pulses with your voice while listening, swirls while thinking
 * and flows while speaking. Tap it to talk.
 */
class OrbView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.IDLE
        set(value) {
            field = value
            if (value != Mode.LISTENING) level = 0f
        }

    /** Mic loudness 0..1 while listening. */
    var level: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** Lighter, softer glow on a light background. */
    var dark: Boolean = true
        set(value) { field = value; buildShaders() }

    private val blobColors = intArrayOf(
        Ui.GEMINI_BLUE,
        Ui.GEMINI_PURPLE,
        Ui.GEMINI_PINK,
        Color.parseColor("#36C5F0")
    )
    private val phases = floatArrayOf(0f, 1.7f, 3.4f, 5.0f)
    private val dirs = floatArrayOf(1f, -0.8f, 1.2f, -1.1f)

    private var shaders: List<RadialGradient> = emptyList()
    private lateinit var halo: RadialGradient
    private lateinit var core: RadialGradient
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var ringShader: SweepGradient? = null
    private var ringCx = -1f
    private var ringAlpha = 0.3f
    private var ringSpin = 0f
    private var wave = 0f
    private var waveAmt = 0f
    private var intro = 0f
    private val m = Matrix()

    private var t = 0f
    private var rot = 0f
    private var last = 0L
    private var energy = 0.15f
    private var speed = 0.25f
    private var spread = 0.3f
    private var smoothLevel = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { tick() }
    }

    init {
        isClickable = true
        buildShaders()
    }

    private fun a(c: Int, alpha: Int) = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))

    private fun buildShaders() {
        val strong = if (dark) 230 else 200
        val mid = if (dark) 110 else 90
        shaders = blobColors.map {
            RadialGradient(
                0f, 0f, 1f,
                intArrayOf(a(it, strong), a(it, mid), a(it, 0)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        halo = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(a(Ui.GEMINI_PURPLE, if (dark) 70 else 45), a(Ui.GEMINI_BLUE, if (dark) 25 else 18), a(Ui.GEMINI_BLUE, 0)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        core = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(a(Color.WHITE, if (dark) 200 else 235), a(Color.WHITE, if (dark) 40 else 90), a(Color.WHITE, 0)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        invalidate()
    }

    private fun tick() {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        t += dt
        smoothLevel += (level - smoothLevel) * 0.2f

        val (e, s, sp) = when (mode) {
            Mode.IDLE -> Triple(0.15f, 0.25f, 0.30f)
            Mode.LISTENING -> Triple(0.40f + smoothLevel * 0.6f, 0.6f, 0.26f)
            Mode.THINKING -> Triple(0.35f, 1.8f, 0.45f)
            Mode.SPEAKING -> Triple(0.6f, 0.9f, 0.32f)
        }
        // ease toward the target so changes between modes feel smooth
        energy += (e - energy) * 0.08f
        speed += (s - speed) * 0.05f
        spread += (sp - spread) * 0.05f
        rot += dt * speed
        if (intro < 1f) intro = (intro + dt / 0.9f).coerceAtMost(1f)

        // gradient ring: brighter while active, spins fast while thinking
        val (ra, rs) = when (mode) {
            Mode.IDLE -> 0.28f to 0.35f
            Mode.LISTENING -> 0.85f to 0.9f
            Mode.THINKING -> 1f to 4.5f
            Mode.SPEAKING -> 0.7f to 1.4f
        }
        ringAlpha += (ra - ringAlpha) * 0.08f
        ringSpin += dt * (rs * 90f)

        // ripple waves while listening / speaking
        val wa = when (mode) {
            Mode.LISTENING -> 0.5f + smoothLevel * 0.5f
            Mode.SPEAKING -> 0.7f
            else -> 0f
        }
        waveAmt += (wa - waveAmt) * 0.06f
        wave += dt * (0.35f + energy * 0.5f)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = 0L
        intro = 0f
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun drawGlow(canvas: Canvas, shader: Shader, x: Float, y: Float, r: Float) {
        if (r <= 0f) return
        m.setScale(r, r)
        m.postTranslate(x, y)
        shader.setLocalMatrix(m)
        paint.shader = shader
        canvas.drawCircle(x, y, r, paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val half = min(width, height) / 2f
        // everything stays inside the view and centered
        val base = half * 0.44f
        if (base <= 0f) return
        val limit = half * 0.98f

        val pulse = when (mode) {
            Mode.SPEAKING -> 1f + 0.10f * (0.5f + 0.5f * sin(t * 7f)) * (0.6f + 0.4f * sin(t * 3.1f))
            Mode.LISTENING -> 1f + smoothLevel * 0.20f
            else -> 1f + 0.035f * sin(t * 1.6f)
        }
        // soft pop-in when the screen opens
        val e = 1f - (1f - intro) * (1f - intro) * (1f - intro)
        val pop = 0.6f + 0.4f * e + 0.05f * sin(e * Math.PI.toFloat())
        val scale = (0.9f + energy * 0.25f) * pulse * pop
        paint.alpha = 255

        // big soft halo (never past the edge)
        drawGlow(canvas, halo, cx, cy, min(base * 2.0f * scale, limit))

        // ripple waves while listening / speaking
        if (waveAmt > 0.01f) {
            ring.shader = null
            for (k in 0..2) {
                val ph = ((wave + k / 3f) % 1f)
                val rr = min(base * scale * (1.15f + ph * 0.75f), limit - base * 0.03f)
                ring.color = blobColors[k % 3]
                ring.alpha = ((1f - ph) * (1f - ph) * 140 * waveAmt).toInt()
                ring.strokeWidth = base * 0.022f * (1.4f - ph)
                canvas.drawCircle(cx, cy, rr, ring)
            }
        }

        // color blobs, kept close to the center
        for (i in shaders.indices) {
            val ang = phases[i] + rot * dirs[i]
            val orbit = base * spread * 0.45f * (0.7f + 0.3f * sin(t * 0.7f + i))
            val bx = cx + cos(ang) * orbit
            val by = cy + sin(ang) * orbit
            val br = base * scale * (0.95f + 0.1f * sin(t * 1.3f + i * 1.7f))
            drawGlow(canvas, shaders[i], bx, by, br)
        }

        // rotating gradient ring
        if (ringCx != cx || ringShader == null) {
            ringShader = SweepGradient(
                cx, cy,
                intArrayOf(Ui.GEMINI_BLUE, Ui.GEMINI_PURPLE, Ui.GEMINI_PINK, Color.parseColor("#36C5F0"), Ui.GEMINI_BLUE),
                null
            )
            ringCx = cx
        }
        val rs = ringShader!!
        m.setRotate(ringSpin, cx, cy)
        rs.setLocalMatrix(m)
        ring.shader = rs
        val ringR = base * scale * 1.08f
        ring.alpha = (ringAlpha * 60 * e).toInt()
        ring.strokeWidth = base * 0.10f
        canvas.drawCircle(cx, cy, ringR, ring)
        ring.alpha = (ringAlpha * 220 * e).toInt()
        ring.strokeWidth = base * 0.02f
        canvas.drawCircle(cx, cy, ringR, ring)
        ring.shader = null

        // soft white light behind the star
        drawGlow(canvas, core, cx, cy, base * (0.55f + energy * 0.2f) * pulse)
        paint.shader = null

        // Gemini-style sparkle in the middle: gradient-filled, slowly turning, pulsing with the voice
        val starR = base * 0.42f * scale
        star.reset()
        Glyphs.star4(star, 0f, 0f, starR)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(ringSpin * 0.15f)
        m.setRotate(-ringSpin * 0.6f)
        rs.setLocalMatrix(m)
        starShader.setLocalMatrix(m)
        starPaint.shader = starShader
        starPaint.alpha = (255 * e).toInt()
        canvas.drawPath(star, starPaint)
        canvas.restore()
    }

    private val star = android.graphics.Path()
    private val starShader = SweepGradient(
        0f, 0f,
        intArrayOf(Ui.GEMINI_BLUE, Ui.GEMINI_PURPLE, Ui.GEMINI_PINK, Color.parseColor("#36C5F0"), Ui.GEMINI_BLUE),
        null
    )
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
}

/**
 * Soft moving light along the bottom of the screen (like an AI "live" mode):
 * faint when idle, bright and bouncing with your voice while listening, flowing while speaking.
 */
class GlowView(context: Context) : View(context) {
    var mode: OrbView.Mode = OrbView.Mode.IDLE
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f) }
    var dark = true

    private val colors = intArrayOf(Ui.GEMINI_BLUE, Ui.GEMINI_PURPLE, Ui.GEMINI_PINK, Color.parseColor("#36C5F0"))
    private val shaders = colors.map {
        RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.argb(200, Color.red(it), Color.green(it), Color.blue(it)),
                Color.argb(70, Color.red(it), Color.green(it), Color.blue(it)),
                Color.argb(0, Color.red(it), Color.green(it), Color.blue(it))),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private var t = 0f
    private var last = 0L
    private var amt = 0.25f
    private var smooth = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val now = System.nanoTime()
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
            last = now
            t += dt
            smooth += (level - smooth) * 0.2f
            val target = when (mode) {
                OrbView.Mode.LISTENING -> 0.75f + smooth * 0.35f
                OrbView.Mode.SPEAKING -> 0.7f + 0.15f * sin(t * 6f)
                OrbView.Mode.THINKING -> 0.55f
                OrbView.Mode.IDLE -> 0.28f
            }
            amt += (target - amt) * 0.06f
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); last = 0L; animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    private var maskH = -1f
    private val fadePaint = Paint()
    private var fadeFor = 0
    /** The screen's background color (fades the glow into it). Leave null when drawn over other apps. */
    var fadeColor: Int? = null

    init { paint.isDither = true }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // fadeColor set → paint the background color over the top (cheap & smooth);
        // otherwise (over other apps) use a layer + mask
        val fc = fadeColor
        val save = if (fc == null) canvas.saveLayer(0f, 0f, w, h, null) else -1
        val speed = when (mode) { OrbView.Mode.THINKING -> 1.4f; OrbView.Mode.IDLE -> 0.25f; else -> 0.7f }
        for (i in shaders.indices) {
            val ph = i * 1.7f + t * speed * (if (i % 2 == 0) 1f else -0.8f)
            val x = w * (0.5f + 0.42f * sin(ph))
            val r = h * (0.62f + 0.18f * sin(ph * 0.7f + i)) * (0.75f + amt * 0.4f)
            m.setScale(r * 1.5f, r)
            m.postTranslate(x, h * 1.04f)
            shaders[i].setLocalMatrix(m)
            paint.shader = shaders[i]
            paint.alpha = ((if (dark) 255 else 210) * amt.coerceIn(0f, 1f)).toInt()
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
        if (fc != null) {
            if (maskH != h || fadeFor != fc) {
                fadePaint.shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(fc, Color.argb(Color.alpha(fc) * 2 / 3, Color.red(fc), Color.green(fc), Color.blue(fc)),
                        Color.argb(Color.alpha(fc) / 6, Color.red(fc), Color.green(fc), Color.blue(fc)), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP
                )
                fadePaint.isDither = true
                maskH = h; fadeFor = fc
            }
            canvas.drawRect(0f, 0f, w, h, fadePaint)
            return
        }
        if (maskH != h) {
            maskPaint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(Color.TRANSPARENT, Color.argb(90, 0, 0, 0), Color.argb(220, 0, 0, 0), Color.BLACK),
                floatArrayOf(0f, 0.35f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            maskH = h
        }
        canvas.drawRect(0f, 0f, w, h, maskPaint)
        canvas.restoreToCount(save)
}
}
