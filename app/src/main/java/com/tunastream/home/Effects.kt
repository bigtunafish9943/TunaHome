package com.tunastream.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Gentle moving effects behind the apps: snow, rain, a storm, autumn leaves, bats, fireflies,
 * stars or fog. They can follow the theme's season, the weather outside, or stay as chosen.
 */
object Effects {
    enum class Kind { NONE, SNOW, RAIN, STORM, LEAVES, BATS, FIREFLIES, STARS, FOG, CONFETTI, HEARTS }

    const val OFF = 0
    const val FOLLOW_WEATHER = 1
    const val FOLLOW_SEASON = 2
    const val WEATHER_THEN_SEASON = 3
    /** From here on, one effect all the time, in the order of [FIXED]. */
    private const val FIRST_FIXED = 4
    private val FIXED = listOf(Kind.SNOW, Kind.RAIN, Kind.STORM, Kind.LEAVES, Kind.BATS, Kind.FIREFLIES, Kind.STARS, Kind.FOG)

    val CHOICE = Look.Choice(
        "fx_kind", OFF, intArrayOf(
            R.string.off, R.string.fx_weather, R.string.fx_season, R.string.fx_weather_season,
            R.string.fx_snow, R.string.fx_rain, R.string.fx_storm, R.string.fx_leaves, R.string.fx_bats,
            R.string.fx_fireflies, R.string.fx_stars, R.string.fx_fog,
        )
    )

    /** How many: from a few to plenty. */
    val AMOUNT = Look.Level("fine_fx_amount", 20, 200, 10, R.string.fine_percent, { 100 })

    /** Effects on the screen saver too. */
    val ON_SAVER = Look.Switch("fx_on_saver", true)

    /** What to show now, from the choice, the weather and the season. */
    fun kind(context: Context): Kind {
        // A holiday or one of your own dates has its own effect, all that day
        Occasions.today(context)?.let { if (it.effect != Kind.NONE) return it.effect }
        val choice = CHOICE.get(context)
        return when {
            choice == OFF -> Kind.NONE
            choice == FOLLOW_WEATHER -> weather()
            choice == FOLLOW_SEASON -> season(context)
            choice == WEATHER_THEN_SEASON -> weather().takeIf { it != Kind.NONE } ?: season(context)
            else -> FIXED.getOrNull(choice - FIRST_FIXED) ?: Kind.NONE
        }
    }

    /** The weather outside as an effect (WMO weather codes, as Open-Meteo gives them). */
    private fun weather(): Kind {
        val now = Weather.current ?: return Kind.NONE
        return when (now.code) {
            45, 48 -> Kind.FOG
            in 51..67, in 80..82 -> Kind.RAIN
            in 71..77, 85, 86 -> Kind.SNOW
            in 95..99 -> Kind.STORM
            0, 1 -> if (now.day) Kind.NONE else Kind.STARS
            else -> Kind.NONE
        }
    }

    /** The season the theme shows, as an effect. */
    private fun season(context: Context): Kind = when (TunaTheme.current(context)) {
        "winter" -> Kind.SNOW
        "autumn" -> Kind.LEAVES
        "halloween" -> Kind.BATS
        "summer" -> Kind.FIREFLIES
        else -> Kind.NONE
    }
}

/** Draws [Effects] across the whole screen. Runs only while shown and started. */
class EffectsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private class Bit(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var size: Float, var phase: Float, var turn: Float, var spin: Float, var colour: Int,
    )

    private val bits = ArrayList<Bit>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density
    private val random = Random(SystemClock.uptimeMillis())
    private var running = false
    private var last = 0L
    private var time = 0f
    private var flash = 0f
    private var nextFlash = 4f
    private var shooting: Bit? = null
    private var nextShooting = 8f
    private val leaf = Path()
    private val bat = Path()

    var kind = Effects.Kind.NONE
        set(value) {
            if (field != value) {
                field = value
                bits.clear()
                invalidate()
            }
        }

    /** 0.2 to 2: how many there are. */
    var amount = 1f
        set(value) {
            if (field != value) {
                field = value
                bits.clear()
            }
        }

    init {
        setWillNotDraw(false)
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // A leaf: a pointed oval, drawn around (0, 0) at size 1
        leaf.moveTo(0f, -1f)
        leaf.quadTo(0.7f, -0.2f, 0f, 1f)
        leaf.quadTo(-0.7f, -0.2f, 0f, -1f)
        leaf.close()
    }

    fun start() {
        running = true
        last = 0L
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
    }

    private fun count(): Int {
        val base = when (kind) {
            Effects.Kind.SNOW -> 90
            Effects.Kind.RAIN -> 140
            Effects.Kind.STORM -> 170
            Effects.Kind.LEAVES -> 22
            Effects.Kind.BATS -> 5
            Effects.Kind.FIREFLIES -> 34
            Effects.Kind.STARS -> 110
            Effects.Kind.FOG -> 7
            Effects.Kind.CONFETTI -> 60
            Effects.Kind.HEARTS -> 18
            Effects.Kind.NONE -> 0
        }
        return maxOf(if (base > 0) 1 else 0, Math.round(base * amount))
    }

    private fun r(from: Float, to: Float) = from + random.nextFloat() * (to - from)

    /** A new bit; [anywhere] spreads them over the screen at the start instead of off its edge. */
    private fun make(anywhere: Boolean): Bit {
        val w = width.toFloat()
        val h = height.toFloat()
        return when (kind) {
            Effects.Kind.SNOW -> Bit(r(0f, w), if (anywhere) r(0f, h) else -10 * dp, 0f, r(25f, 70f) * dp,
                r(1.5f, 4f) * dp, r(0f, 6.28f), 0f, 0f, Color.argb(r(130f, 230f).toInt(), 255, 255, 255))
            Effects.Kind.RAIN, Effects.Kind.STORM -> {
                val fall = r(750f, 1150f) * dp * (if (kind == Effects.Kind.STORM) 1.15f else 1f)
                Bit(r(-0.1f * w, w), if (anywhere) r(0f, h) else -30 * dp, fall * 0.12f, fall,
                    r(14f, 26f) * dp, 0f, 0f, 0f, Color.argb(r(70f, 120f).toInt(), 200, 215, 255))
            }
            Effects.Kind.LEAVES -> Bit(r(0f, w), if (anywhere) r(0f, h) else -20 * dp, r(-15f, 25f) * dp, r(35f, 80f) * dp,
                r(7f, 13f) * dp, r(0f, 6.28f), r(0f, 360f), r(-90f, 90f),
                listOf(0xFFC8552B.toInt(), 0xFFE08E2B.toInt(), 0xFFA63A1E.toInt(), 0xFFD9B341.toInt()).random(random))
            Effects.Kind.BATS -> {
                val right = random.nextBoolean()
                Bit(if (anywhere) r(0f, w) else if (right) -40 * dp else w + 40 * dp, r(0.08f * h, 0.6f * h),
                    (if (right) 1 else -1) * r(60f, 120f) * dp, 0f, r(14f, 24f) * dp, r(0f, 6.28f), 0f, r(7f, 10f),
                    Color.argb(220, 18, 14, 20))
            }
            Effects.Kind.FIREFLIES -> Bit(r(0f, w), r(0.25f * h, h), r(-15f, 15f) * dp, r(-10f, 10f) * dp,
                r(2f, 3.5f) * dp, r(0f, 6.28f), 0f, r(1.5f, 3f), Color.rgb(220, 255, 120))
            Effects.Kind.STARS -> Bit(r(0f, w), r(0f, 0.65f * h), 0f, 0f, r(0.8f, 2.2f) * dp, r(0f, 6.28f), 0f, r(0.6f, 2f),
                Color.WHITE)
            Effects.Kind.FOG -> Bit(if (anywhere) r(0f, w) else -300 * dp, r(0.35f * h, 1.05f * h), r(10f, 24f) * dp, 0f,
                r(180f, 340f) * dp, 0f, 0f, 0f, Color.WHITE)
            Effects.Kind.CONFETTI -> Bit(r(0f, w), if (anywhere) r(0f, h) else -12 * dp, r(-20f, 20f) * dp, r(60f, 130f) * dp,
                r(4f, 7f) * dp, r(0f, 6.28f), r(0f, 360f), r(-260f, 260f),
                listOf(0xFFFF5A5F.toInt(), 0xFFFFC145.toInt(), 0xFF3EC1D3.toInt(), 0xFF8DD35F.toInt(), 0xFFB07CFF.toInt(),
                    0xFFFF8FC7.toInt()).random(random))
            Effects.Kind.HEARTS -> Bit(r(0f, w), if (anywhere) r(0f, h) else h + 20 * dp, r(-10f, 10f) * dp, -r(30f, 60f) * dp,
                r(8f, 16f) * dp, r(0f, 6.28f), 0f, 0f,
                Color.argb(r(120f, 200f).toInt(), 255, listOf(70, 105, 140).random(random), listOf(110, 140, 170).random(random)))
            Effects.Kind.NONE -> Bit(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (kind == Effects.Kind.NONE || width == 0 || height == 0) {
            return
        }
        val now = SystemClock.uptimeMillis()
        // Seconds since the last frame, kept small so a pause doesn't make everything jump
        val dt = if (last == 0L) 0f else ((now - last) / 1000f).coerceAtMost(0.05f)
        last = now
        time += dt
        val wanted = count()
        while (bits.size < wanted) bits.add(make(true))
        while (bits.size > wanted) bits.removeAt(bits.size - 1)
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in bits.indices) {
            val b = bits[i]
            when (kind) {
                Effects.Kind.SNOW -> {
                    b.x += (b.vx + sin(time * 0.8f + b.phase) * 14 * dp) * dt
                    b.y += b.vy * dt
                    paint.style = Paint.Style.FILL
                    paint.color = b.colour
                    canvas.drawCircle(b.x, b.y, b.size, paint)
                    if (b.y > h + 10 * dp) bits[i] = make(false)
                }
                Effects.Kind.RAIN, Effects.Kind.STORM -> {
                    b.x += b.vx * dt
                    b.y += b.vy * dt
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.2f * dp
                    paint.color = b.colour
                    val k = b.size / b.vy
                    canvas.drawLine(b.x, b.y, b.x - b.vx * k, b.y - b.size, paint)
                    if (b.y > h + 30 * dp) bits[i] = make(false)
                }
                Effects.Kind.LEAVES -> {
                    b.x += (b.vx + sin(time * 1.1f + b.phase) * 30 * dp) * dt
                    b.y += b.vy * dt
                    b.turn += b.spin * dt
                    paint.style = Paint.Style.FILL
                    paint.color = b.colour
                    canvas.save()
                    canvas.translate(b.x, b.y)
                    canvas.rotate(b.turn)
                    // Tumbling: squashed as it turns over
                    canvas.scale(b.size * (0.35f + 0.65f * Math.abs(cos(time * 1.7f + b.phase))), b.size)
                    canvas.drawPath(leaf, paint)
                    canvas.restore()
                    if (b.y > h + 20 * dp) bits[i] = make(false)
                }
                Effects.Kind.BATS -> {
                    b.x += b.vx * dt
                    b.y += sin(time * 1.3f + b.phase) * 25 * dp * dt
                    drawBat(canvas, b)
                    if (b.x < -60 * dp || b.x > w + 60 * dp) bits[i] = make(false)
                }
                Effects.Kind.FIREFLIES -> {
                    b.x += (b.vx + sin(time * 0.5f + b.phase) * 12 * dp) * dt
                    b.y += (b.vy + cos(time * 0.4f + b.phase) * 10 * dp) * dt
                    if (b.x < -20 * dp) b.x = w + 10 * dp
                    if (b.x > w + 20 * dp) b.x = -10 * dp
                    if (b.y < 0.2f * h) b.vy = Math.abs(b.vy)
                    if (b.y > h) b.vy = -Math.abs(b.vy)
                    val glow = (0.5f + 0.5f * sin(time * b.spin + b.phase)).coerceIn(0f, 1f)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb((50 * glow).toInt(), 200, 255, 100)
                    canvas.drawCircle(b.x, b.y, b.size * 4, paint)
                    paint.color = Color.argb((60 + 195 * glow).toInt(), 230, 255, 150)
                    canvas.drawCircle(b.x, b.y, b.size, paint)
                }
                Effects.Kind.STARS -> {
                    val twinkle = 0.55f + 0.45f * sin(time * b.spin + b.phase)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb((60 + 170 * twinkle).toInt(), 255, 255, 255)
                    canvas.drawCircle(b.x, b.y, b.size, paint)
                }
                Effects.Kind.FOG -> {
                    b.x += b.vx * dt
                    paint.style = Paint.Style.FILL
                    // Faint, low wisps: the screen stays clear above them
                    paint.shader = RadialGradient(b.x, b.y, b.size, Color.argb(13, 255, 255, 255),
                        Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    canvas.drawCircle(b.x, b.y, b.size, paint)
                    paint.shader = null
                    if (b.x - b.size > w) bits[i] = make(false)
                }
                Effects.Kind.CONFETTI -> {
                    b.x += (b.vx + sin(time * 2f + b.phase) * 18 * dp) * dt
                    b.y += b.vy * dt
                    b.turn += b.spin * dt
                    paint.style = Paint.Style.FILL
                    paint.color = b.colour
                    canvas.save()
                    canvas.translate(b.x, b.y)
                    canvas.rotate(b.turn)
                    // A paper square flipping as it falls
                    val flip = 0.25f + 0.75f * Math.abs(cos(time * 3f + b.phase))
                    canvas.drawRect(-b.size, -b.size * 0.6f * flip, b.size, b.size * 0.6f * flip, paint)
                    canvas.restore()
                    if (b.y > h + 12 * dp) bits[i] = make(false)
                }
                Effects.Kind.HEARTS -> {
                    b.x += (b.vx + sin(time * 0.9f + b.phase) * 16 * dp) * dt
                    b.y += b.vy * dt
                    paint.style = Paint.Style.FILL
                    paint.color = b.colour
                    drawHeart(canvas, b.x, b.y, b.size)
                    if (b.y < -20 * dp) bits[i] = make(false)
                }
                Effects.Kind.NONE -> {}
            }
        }
        if (kind == Effects.Kind.STORM) lightning(canvas, dt)
        if (kind == Effects.Kind.STARS) shootingStar(canvas, dt)
        if (running) {
            postInvalidateOnAnimation()
        }
    }

    private val heart = Path()

    /** A heart of [size] around (x, y), rising gently. */
    private fun drawHeart(canvas: Canvas, x: Float, y: Float, size: Float) {
        heart.reset()
        heart.moveTo(x, y + size * 0.9f)
        heart.cubicTo(x - size * 1.4f, y - size * 0.1f, x - size * 0.7f, y - size * 1.1f, x, y - size * 0.35f)
        heart.cubicTo(x + size * 0.7f, y - size * 1.1f, x + size * 1.4f, y - size * 0.1f, x, y + size * 0.9f)
        heart.close()
        canvas.drawPath(heart, paint)
    }

    /** Now and then the whole screen flickers white, twice. */
    private fun lightning(canvas: Canvas, dt: Float) {
        nextFlash -= dt
        if (nextFlash <= 0f) {
            flash = 1f
            nextFlash = r(6f, 15f)
        }
        if (flash > 0f) {
            // Two quick flickers as it fades
            val flicker = if (flash > 0.7f || flash in 0.35f..0.5f) flash else flash * 0.3f
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = Color.argb((110 * flicker).toInt(), 235, 240, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            flash -= dt * 2.2f
        }
    }

    /** Once in a while a star streaks across. */
    private fun shootingStar(canvas: Canvas, dt: Float) {
        nextShooting -= dt
        if (shooting == null && nextShooting <= 0f) {
            shooting = Bit(r(0.1f, 0.7f) * width, r(0.02f, 0.25f) * height, r(700f, 1000f) * dp, r(250f, 400f) * dp,
                0f, 0f, 0f, 0f, Color.WHITE)
            nextShooting = r(10f, 25f)
        }
        val s = shooting ?: return
        s.x += s.vx * dt
        s.y += s.vy * dt
        s.size += dt
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * dp
        paint.color = Color.argb((200 * (1f - s.size / 0.8f)).toInt().coerceIn(0, 200), 255, 255, 255)
        canvas.drawLine(s.x, s.y, s.x - s.vx * 0.08f, s.y - s.vy * 0.08f, paint)
        if (s.size > 0.8f) shooting = null
    }

    /** A small bat, wings flapping. */
    private fun drawBat(canvas: Canvas, b: Bit) {
        val flap = sin(time * b.spin + b.phase)
        val s = b.size
        bat.reset()
        bat.moveTo(0f, 0f)
        // Left wing, then right, rising and falling together
        bat.quadTo(-0.5f * s, -0.6f * s * flap - 0.1f * s, -s, -0.2f * s * flap)
        bat.quadTo(-0.6f * s, 0.05f * s, -0.45f * s, 0.25f * s)
        bat.quadTo(-0.2f * s, 0.1f * s, 0f, 0.3f * s)
        bat.quadTo(0.2f * s, 0.1f * s, 0.45f * s, 0.25f * s)
        bat.quadTo(0.6f * s, 0.05f * s, s, -0.2f * s * flap)
        bat.quadTo(0.5f * s, -0.6f * s * flap - 0.1f * s, 0f, 0f)
        bat.close()
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = b.colour
        canvas.save()
        canvas.translate(b.x, b.y)
        canvas.drawPath(bat, paint)
        canvas.drawCircle(0f, 0.08f * s, 0.14f * s, paint)
        canvas.restore()
    }
}
