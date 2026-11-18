package com.mandeep.jarvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = 0L
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
        val base = min(width, height) * 0.30f
        if (base <= 0f) return

        val pulse = when (mode) {
            Mode.SPEAKING -> 1f + 0.10f * (0.5f + 0.5f * sin(t * 7f)) * (0.6f + 0.4f * sin(t * 3.1f))
            Mode.LISTENING -> 1f + smoothLevel * 0.22f
            else -> 1f + 0.04f * sin(t * 1.6f)
        }
        val scale = (0.85f + energy * 0.35f) * pulse

        // big soft halo
        drawGlow(canvas, halo, cx, cy, base * 2.2f * scale)

        // drifting color blobs
        val angle = rot
        for (i in shaders.indices) {
            val ang = phases[i] + angle * dirs[i]
            val orbit = base * spread * (0.7f + 0.3f * sin(t * 0.7f + i))
            val bx = cx + cos(ang) * orbit
            val by = cy + sin(ang) * orbit * 0.85f
            val br = base * scale * (1.0f + 0.15f * sin(t * 1.3f + i * 1.7f))
            drawGlow(canvas, shaders[i], bx, by, br)
        }

        // bright center
        drawGlow(canvas, core, cx, cy, base * (0.45f + energy * 0.25f) * pulse)
        paint.shader = null
    }
}
