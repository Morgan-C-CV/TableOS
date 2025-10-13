package com.tableos.beakerlab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.os.SystemClock
import kotlin.math.hypot

class BeakerCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val cards = mutableListOf<ChemicalCard>()
    private var nextId = 1

    private fun paintFor(type: ChemicalType): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = when (type) {
            ChemicalType.Na -> Color.parseColor("#FFB300") // Amber
            ChemicalType.H2O -> Color.parseColor("#2196F3") // Blue
            ChemicalType.HCl -> Color.parseColor("#E53935") // Red
            ChemicalType.NaOH -> Color.parseColor("#8BC34A") // Green
            ChemicalType.Cl2 -> Color.parseColor("#43A047") // Dark green
            ChemicalType.O2 -> Color.parseColor("#3F51B5") // Indigo
            ChemicalType.H2 -> Color.parseColor("#00BCD4") // Cyan
            ChemicalType.CO2 -> Color.parseColor("#9E9E9E") // Gray
        }
        return p
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
    }
    private val eqPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(16f)
    }
    private val eqBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66000000")
        style = Paint.Style.FILL
    }

    private var activeId: Int? = null
    private var lastX = 0f
    private var lastY = 0f

    // --- Reaction effects ---
    private enum class EffectType { FIRE, GAS, GLOW }
    private data class ActiveEffect(
        val types: List<EffectType>,
        val startMs: Long,
        val x: Float,
        val y: Float,
        val durationMs: Long = 2000L
    )
    private var currentEffect: ActiveEffect? = null
    private val flamePaints = listOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD54F") }, // yellow
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FB8C00") }, // orange
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }  // red
    )
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99FFFFFF")
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFF66")
        style = Paint.Style.FILL
    }

    fun addCard(type: ChemicalType) {
        val r = dp(28f)
        val cx = width.takeIf { it > 0 }?.let { it / 2f } ?: dp(80f)
        val cy = height.takeIf { it > 0 }?.let { it / 2f } ?: dp(120f)
        val jitterX = if (type == ChemicalType.Na) -dp(40f) else dp(40f)
        val card = ChemicalCard(nextId++, type, cx + jitterX, cy, r)
        cards.add(card)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw cards
        for (c in cards) {
            val paint = paintFor(c.type)
            canvas.drawCircle(c.x, c.y, c.radius, paint)
            val label = when (c.type) {
                ChemicalType.Na -> "Na"
                ChemicalType.H2O -> "H₂O"
                ChemicalType.HCl -> "HCl"
                ChemicalType.NaOH -> "NaOH"
                ChemicalType.Cl2 -> "Cl₂"
                ChemicalType.O2 -> "O₂"
                ChemicalType.H2 -> "H₂"
                ChemicalType.CO2 -> "CO₂"
            }
            val tw = labelPaint.measureText(label)
            canvas.drawText(label, c.x - tw / 2f, c.y + labelPaint.textSize / 3f, labelPaint)
        }

        // Proximity detection and equation display
        val threshold = dp(120f)
        var eqText: String? = null
        var eqX = 0f
        var eqY = 0f
        for (i in 0 until cards.size) {
            for (j in i + 1 until cards.size) {
                val a = cards[i]
                val b = cards[j]
                val dist = hypot(a.x - b.x, a.y - b.y)
                val reaction = reactionFor(a.type, b.type)
                if (reaction != null && dist <= threshold) {
                    eqText = reaction.equation
                    eqX = (a.x + b.x) / 2f
                    eqY = (a.y + b.y) / 2f - dp(48f)
                    val now = SystemClock.uptimeMillis()
                    val eff = currentEffect
                    val needStart = eff == null || now - eff.startMs >= eff.durationMs
                    if (needStart) {
                        currentEffect = ActiveEffect(reaction.effects, now, eqX, eqY + dp(24f))
                    }
                    break
                }
            }
        }
        if (eqText != null) {
            val padding = dp(8f)
            val tw = eqPaint.measureText(eqText)
            val th = eqPaint.textSize
            canvas.drawRoundRect(
                eqX - tw / 2f - padding,
                eqY - th - padding,
                eqX + tw / 2f + padding,
                eqY + padding,
                dp(8f), dp(8f), eqBgPaint
            )
            canvas.drawText(eqText, eqX - tw / 2f, eqY, eqPaint)
        }

        // Draw active effects if any
        val eff = currentEffect
        if (eff != null) {
            val now = SystemClock.uptimeMillis()
            val t = (now - eff.startMs).toFloat() / eff.durationMs
            if (t in 0f..1f) {
                for (type in eff.types) {
                    when (type) {
                        EffectType.FIRE -> drawFireEffect(canvas, eff.x, eff.y, t)
                        EffectType.GAS -> drawGasEffect(canvas, eff.x, eff.y, t)
                        EffectType.GLOW -> drawGlowEffect(canvas, eff.x, eff.y, t)
                    }
                }
                // keep animating
                postInvalidateOnAnimation()
            } else {
                currentEffect = null
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val id = hitTest(event.x, event.y)
                if (id != null) {
                    activeId = id
                    lastX = event.x
                    lastY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val id = activeId ?: return false
                val c = cards.find { it.id == id } ?: return false
                val dx = event.x - lastX
                val dy = event.y - lastY
                c.x = (c.x + dx).coerceIn(c.radius, width.toFloat() - c.radius)
                c.y = (c.y + dy).coerceIn(c.radius, height.toFloat() - c.radius)
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeId = null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Int? {
        for (i in cards.indices.reversed()) { // top-most first
            val c = cards[i]
            val dx = x - c.x
            val dy = y - c.y
            if (dx * dx + dy * dy <= c.radius * c.radius) return c.id
        }
        return null
    }

    private data class ReactionInfo(val equation: String, val effects: List<EffectType>)
    private fun reactionFor(a: ChemicalType, b: ChemicalType): ReactionInfo? {
        val pair = setOf(a, b)
        return when (pair) {
            setOf(ChemicalType.Na, ChemicalType.H2O) -> ReactionInfo(
                "2Na + 2H₂O → 2NaOH + H₂↑",
                listOf(EffectType.GAS, EffectType.GLOW)
            )
            setOf(ChemicalType.HCl, ChemicalType.NaOH) -> ReactionInfo(
                "HCl + NaOH → NaCl + H₂O",
                listOf(EffectType.GLOW)
            )
            setOf(ChemicalType.Na, ChemicalType.Cl2) -> ReactionInfo(
                "2Na + Cl₂ → 2NaCl",
                listOf(EffectType.GLOW)
            )
            setOf(ChemicalType.H2, ChemicalType.O2) -> ReactionInfo(
                "2H₂ + O₂ → 2H₂O",
                listOf(EffectType.FIRE, EffectType.GAS)
            )
            setOf(ChemicalType.H2, ChemicalType.Cl2) -> ReactionInfo(
                "H₂ + Cl₂ → 2HCl",
                listOf(EffectType.GLOW)
            )
            setOf(ChemicalType.CO2, ChemicalType.H2O) -> ReactionInfo(
                "CO₂ + H₂O ⇌ H₂CO₃",
                emptyList()
            )
            else -> null
        }
    }

    private fun drawGlowEffect(canvas: Canvas, cx: Float, cy: Float, t: Float) {
        val base = dp(60f)
        val radius = base * (0.8f + 0.2f * kotlin.math.sin(t * Math.PI).toFloat())
        glowPaint.alpha = (120 * (1f - t)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius, glowPaint)
    }

    private fun drawGasEffect(canvas: Canvas, cx: Float, cy: Float, t: Float) {
        val bubbles = 8
        val rise = dp(60f) * t
        for (i in 0 until bubbles) {
            val angle = (i / bubbles.toFloat()) * (2f * Math.PI.toFloat())
            val bx = cx + dp(12f) * kotlin.math.cos(angle)
            val by = cy - rise - i * dp(3f)
            val r = dp(6f) * (0.6f + 0.4f * ((i + 1) / bubbles.toFloat()))
            bubblePaint.alpha = (180 * (1f - t)).toInt().coerceIn(0, 255)
            canvas.drawCircle(bx, by, r, bubblePaint)
        }
    }

    private fun drawFireEffect(canvas: Canvas, cx: Float, cy: Float, t: Float) {
        val layers = flamePaints.size
        for (i in 0 until layers) {
            val p = flamePaints[i]
            val scale = 1f - i * 0.2f
            val jitter = dp(6f) * kotlin.math.sin((t + i * 0.15f) * 6f).toFloat()
            p.alpha = (200 * (1f - t)).toInt().coerceIn(0, 255)
            val rx = cx + jitter
            val ry = cy - dp(10f) * i
            val r = dp(24f) * scale
            canvas.drawCircle(rx, ry, r, p)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}