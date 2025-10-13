package com.tableos.beakerlab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
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
                    eqText = reaction
                    eqX = (a.x + b.x) / 2f
                    eqY = (a.y + b.y) / 2f - dp(48f)
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
            canvas.drawText(eqText!!, eqX - tw / 2f, eqY, eqPaint)
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

    private fun reactionFor(a: ChemicalType, b: ChemicalType): String? {
        val pair = setOf(a, b)
        return when (pair) {
            setOf(ChemicalType.Na, ChemicalType.H2O) -> "2Na + 2H₂O → 2NaOH + H₂↑"
            setOf(ChemicalType.HCl, ChemicalType.NaOH) -> "HCl + NaOH → NaCl + H₂O"
            setOf(ChemicalType.Na, ChemicalType.Cl2) -> "2Na + Cl₂ → 2NaCl"
            setOf(ChemicalType.H2, ChemicalType.O2) -> "2H₂ + O₂ → 2H₂O"
            setOf(ChemicalType.H2, ChemicalType.Cl2) -> "H₂ + Cl₂ → 2HCl"
            setOf(ChemicalType.CO2, ChemicalType.H2O) -> "CO₂ + H₂O ⇌ H₂CO₃"
            else -> null
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}