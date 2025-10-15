package com.tableos.beakerlab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
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
            ChemicalType.C -> Color.parseColor("#424242") // Dark gray (carbon)
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
    
    // 形状边框标注相关
    private val detectedShapes = mutableListOf<DetectedElement>()
    
    // 相机和视图尺寸信息，用于坐标转换
    private var cameraWidth: Int = 0
    private var cameraHeight: Int = 0
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0
    
    private val shapeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        color = Color.parseColor("#00FF00") // 绿色边框
    }
    private val shapeInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#FFFFFF") // 白色内边框
    }
    private val shapeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        style = Paint.Style.FILL
        isFakeBoldText = true
    }
    private val shapeTextBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DD000000")
        style = Paint.Style.FILL
    }
    private val coordinateTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF00") // 黄色坐标文字
        textSize = dp(10f)
        style = Paint.Style.FILL
    }
    private val coordinateTextBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA000000")
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
    
    fun updateDetectedShapes(shapes: List<DetectedElement>) {
        detectedShapes.clear()
        detectedShapes.addAll(shapes)
        invalidate()
    }
    
    fun setCameraSize(width: Int, height: Int) {
        cameraWidth = width
        cameraHeight = height
        Log.d("BeakerCanvasView", "Camera size set: ${width}x${height}")
    }
    
    fun clearDetectedShapes() {
        detectedShapes.clear()
        invalidate()
    }
    
    /**
     * 将相机坐标转换为视图坐标
     * 只考虑缩放的影响
     */
    private fun transformCameraCoordinate(cameraX: Float, cameraY: Float): Pair<Float, Float> {
        if (cameraWidth == 0 || cameraHeight == 0 || viewWidth == 0 || viewHeight == 0) {
            // 如果尺寸信息不完整，返回原始坐标
            return Pair(cameraX, cameraY)
        }
        
        // 步骤1: 将相机坐标转换为归一化坐标 (0-1)
        val normalizedX = cameraX / cameraWidth.toFloat()
        val normalizedY = cameraY / cameraHeight.toFloat()
        
        // 步骤2: 计算缩放比例以填满整个视图
        val cameraAspectRatio = cameraWidth.toFloat() / cameraHeight.toFloat()
        val viewAspectRatio = viewWidth.toFloat() / viewHeight.toFloat()
        
        val scaleX: Float
        val scaleY: Float
        
        if (cameraAspectRatio > viewAspectRatio) {
            // 相机更宽，以高度为准进行缩放
            scaleY = 1.0f
            scaleX = viewAspectRatio / cameraAspectRatio
        } else {
            // 相机更高，以宽度为准进行缩放
            scaleX = 1.0f
            scaleY = cameraAspectRatio / viewAspectRatio
        }
        
        // 使用较大的缩放比例以填满整个视图
        val finalScale = maxOf(scaleX, scaleY)
        
        // 步骤3: 应用缩放并转换为视图像素坐标
        val scaledX = normalizedX * finalScale
        val scaledY = normalizedY * finalScale
        
        // 步骤4: 居中显示
        val offsetX = (1.0f - finalScale) / 2.0f
        val offsetY = (1.0f - finalScale) / 2.0f
        
        val viewX = (scaledX + offsetX) * viewWidth
        val viewY = (scaledY + offsetY) * viewHeight
        
        return Pair(viewX, viewY)
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        Log.d("BeakerCanvasView", "View size changed: ${w}x${h}")
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
                ChemicalType.C -> "C"
            }
            val tw = labelPaint.measureText(label)
            canvas.drawText(label, c.x - tw / 2f, c.y + labelPaint.textSize / 3f, labelPaint)
        }
        
        // Draw detected shape borders
        for (shape in detectedShapes) {
            // 转换相机坐标为视图坐标
            val (transformedX, transformedY) = transformCameraCoordinate(shape.x, shape.y)
            
            // 绘制双重圆形边框（检测到的形状）
            val radius = dp(40f) // 固定半径
            canvas.drawCircle(transformedX, transformedY, radius, shapeBorderPaint) // 外边框（绿色）
            canvas.drawCircle(transformedX, transformedY, radius - dp(2f), shapeInnerPaint) // 内边框（白色）
            
            // 绘制元素标签
            val label = when (shape.type) {
                ChemicalType.Na -> "Na"
                ChemicalType.H2O -> "H₂O"
                ChemicalType.HCl -> "HCl"
                ChemicalType.NaOH -> "NaOH"
                ChemicalType.Cl2 -> "Cl₂"
                ChemicalType.O2 -> "O₂"
                ChemicalType.H2 -> "H₂"
                ChemicalType.CO2 -> "CO₂"
                ChemicalType.C -> "C"
            }
            
            val textWidth = shapeTextPaint.measureText(label)
            val textHeight = shapeTextPaint.textSize
            val textX = transformedX - textWidth / 2f
            val textY = transformedY - radius - 15f
            
            // 绘制元素标签背景
            canvas.drawRoundRect(textX - 4f, textY - textHeight, textX + textWidth + 4f, textY + 4f, dp(4f), dp(4f), shapeTextBgPaint)
            // 绘制元素标签文本
            canvas.drawText(label, textX, textY, shapeTextPaint)
            
            // 绘制坐标信息 - 显示百分比坐标
            val percentX = if (viewWidth > 0) ((transformedX / viewWidth) * 100).toInt() else 0
            val percentY = if (viewHeight > 0) ((transformedY / viewHeight) * 100).toInt() else 0
            val coordText = "(${percentX}%, ${percentY}%)"
            val coordWidth = coordinateTextPaint.measureText(coordText)
            val coordHeight = coordinateTextPaint.textSize
            val coordX = transformedX - coordWidth / 2f
            val coordY = transformedY + radius + coordHeight + 8f
            
            // 绘制坐标背景
            canvas.drawRoundRect(coordX - 3f, coordY - coordHeight, coordX + coordWidth + 3f, coordY + 3f, dp(3f), dp(3f), coordinateTextBgPaint)
            // 绘制坐标文本
            canvas.drawText(coordText, coordX, coordY, coordinateTextPaint)
            
            // 绘制十字准星
            val crossSize = dp(8f)
            canvas.drawLine(transformedX - crossSize, transformedY, transformedX + crossSize, transformedY, shapeBorderPaint)
            canvas.drawLine(transformedX, transformedY - crossSize, transformedX, transformedY + crossSize, shapeBorderPaint)
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
            setOf(ChemicalType.H2, ChemicalType.O2) -> ReactionInfo(
                "2H₂ + O₂ → 2H₂O",
                listOf(EffectType.FIRE, EffectType.GAS)
            )
            setOf(ChemicalType.Na, ChemicalType.O2) -> ReactionInfo(
                "4Na + O₂ → 2Na₂O",
                listOf(EffectType.FIRE, EffectType.GLOW)
            )
            setOf(ChemicalType.C, ChemicalType.O2) -> ReactionInfo(
                "C + O₂ → CO₂",
                listOf(EffectType.FIRE, EffectType.GAS)
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