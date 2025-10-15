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
        p.style = Paint.Style.STROKE // 改为描边样式
        p.strokeWidth = dp(3f) // 设置描边宽度
        p.color = Color.BLACK // 统一使用黑色
        return p
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK // 改为黑色字体
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
    
    // 悬浮化学式效果
    private data class FloatingEquation(
        val equation: String,
        val startMs: Long,
        val x: Float,
        val y: Float,
        val durationMs: Long = 3000L
    )
    private var currentEquation: FloatingEquation? = null
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
    
    // 化学卡片动画相关
    private data class AnimatedChemicalCard(
        val element: DetectedElement,
        val startTime: Long,
        var alpha: Float = 0f,
        var isVisible: Boolean = true,
        var lastUpdateTime: Long = System.currentTimeMillis()
    )
    
    private val animatedCards = mutableMapOf<Int, AnimatedChemicalCard>()
    private val fadeInDuration = 500L // 淡入时间 500ms
    private val fadeOutDuration = 300L // 淡出时间 300ms
    private val cardLifetime = 2500L // 卡片生命周期 5秒
    
    // 相机和视图尺寸信息，用于坐标转换
    private var cameraWidth: Int = 0
    private var cameraHeight: Int = 0
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0
    
    // 预览模式状态
    private var isPreviewMode: Boolean = false
    
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
        
        val currentTime = System.currentTimeMillis()
        val newShapeIds = shapes.map { it.id }.toSet()
        
        // 更新现有卡片或添加新卡片
        for (shape in shapes) {
            if (animatedCards.containsKey(shape.id)) {
                // 更新现有卡片的元素信息和时间
                val card = animatedCards[shape.id]!!
                animatedCards[shape.id] = card.copy(
                    element = shape,
                    lastUpdateTime = currentTime,
                    isVisible = true
                )
            } else {
                // 添加新卡片
                animatedCards[shape.id] = AnimatedChemicalCard(
                    element = shape,
                    startTime = currentTime,
                    alpha = 0f,
                    isVisible = true,
                    lastUpdateTime = currentTime
                )
            }
        }
        
        // 标记不再检测到的卡片为不可见（开始淡出）
        for ((id, card) in animatedCards) {
            if (!newShapeIds.contains(id) && card.isVisible) {
                animatedCards[id] = card.copy(isVisible = false, lastUpdateTime = currentTime)
            }
        }
        
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
     * 现在相机输出4:3横向图像，不需要旋转变换
     */
    private fun transformCameraCoordinate(cameraX: Float, cameraY: Float): Pair<Float, Float> {
        if (cameraWidth == 0 || cameraHeight == 0 || viewWidth == 0 || viewHeight == 0) {
            // 如果尺寸信息不完整，返回原始坐标
            Log.w("BeakerCanvasView", "Size info incomplete: camera=${cameraWidth}x${cameraHeight}, view=${viewWidth}x${viewHeight}")
            return Pair(cameraX, cameraY)
        }
        
        // 步骤1: 将相机坐标转换为归一化坐标 (0-1)
        val normalizedX = cameraX / cameraWidth.toFloat()
        val normalizedY = cameraY / cameraHeight.toFloat()
        
        // 步骤2: 计算缩放比例以适应视图
        val cameraAspectRatio = cameraWidth.toFloat() / cameraHeight.toFloat() // 4:3 = 1.33
        val viewAspectRatio = viewWidth.toFloat() / viewHeight.toFloat()
        
        val scaleX: Float
        val scaleY: Float
        val offsetX: Float
        val offsetY: Float
        
        if (cameraAspectRatio > viewAspectRatio) {
            // 相机更宽，以视图宽度为准进行缩放
            scaleX = 1.0f
            scaleY = viewAspectRatio / cameraAspectRatio
            offsetX = 0.0f
            offsetY = (1.0f - scaleY) / 2.0f
        } else {
            // 相机更高，以视图高度为准进行缩放
            scaleX = cameraAspectRatio / viewAspectRatio
            scaleY = 1.0f
            offsetX = (1.0f - scaleX) / 2.0f
            offsetY = 0.0f
        }
        
        // 步骤3: 应用缩放和偏移，转换为视图像素坐标
        val viewX = (normalizedX * scaleX + offsetX) * viewWidth
        val viewY = (normalizedY * scaleY + offsetY) * viewHeight
        
        Log.d("BeakerCanvasView", "Transform: camera(${cameraX}, ${cameraY}) -> normalized(${normalizedX}, ${normalizedY}) -> view(${viewX}, ${viewY})")
        Log.d("BeakerCanvasView", "Ratios: camera=${cameraAspectRatio}, view=${viewAspectRatio}, scale=(${scaleX}, ${scaleY}), offset=(${offsetX}, ${offsetY})")
        
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
        
        // 如果是预览模式，设置canvas透明度为0以显示相机视角
        if (isPreviewMode) {
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 0)
            canvas.restore()
            return
        }

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
        
        // Draw animated chemical cards
        val currentTime = System.currentTimeMillis()
        val cardsToRemove = mutableListOf<Int>()
        
        for ((id, card) in animatedCards) {
            val timeSinceStart = currentTime - card.startTime
            val timeSinceUpdate = currentTime - card.lastUpdateTime
            
            // 计算透明度
            val targetAlpha = if (card.isVisible) {
                // 淡入动画
                if (timeSinceStart < fadeInDuration) {
                    timeSinceStart.toFloat() / fadeInDuration
                } else {
                    1f
                }
            } else {
                // 淡出动画
                if (timeSinceUpdate < fadeOutDuration) {
                    1f - (timeSinceUpdate.toFloat() / fadeOutDuration)
                } else {
                    0f
                }
            }
            
            // 更新卡片透明度
            animatedCards[id] = card.copy(alpha = targetAlpha)
            
            // 如果卡片已经完全透明，标记为删除
            if (targetAlpha <= 0f && !card.isVisible) {
                cardsToRemove.add(id)
                continue
            }
            
            // 如果卡片超过生命周期且不可见，也标记为删除
            if (timeSinceStart > cardLifetime && !card.isVisible) {
                cardsToRemove.add(id)
                continue
            }
            
            // 绘制卡片
            val shape = card.element
            val (transformedX, transformedY) = transformCameraCoordinate(shape.x, shape.y)
            
            // 使用ChemicalCard的卡片效果绘制
            val radius = dp(30f) // 卡片半径
            val paint = paintFor(shape.type)
            val originalAlpha = paint.alpha
            paint.alpha = (originalAlpha * targetAlpha).toInt().coerceIn(0, 255)
            
            // 绘制化学卡片圆形
            canvas.drawCircle(transformedX, transformedY, radius, paint)
            
            // 绘制化学式标签
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
            val tw = labelPaint.measureText(label)
            val originalLabelAlpha = labelPaint.alpha
            labelPaint.alpha = (originalLabelAlpha * targetAlpha).toInt().coerceIn(0, 255)
            canvas.drawText(label, transformedX - tw / 2f, transformedY + labelPaint.textSize / 3f, labelPaint)
            
            // 绘制坐标信息 - 显示百分比坐标
            val percentX = if (viewWidth > 0) ((transformedX / viewWidth) * 100).toInt() else 0
            val percentY = if (viewHeight > 0) ((transformedY / viewHeight) * 100).toInt() else 0
            val coordText = "(${percentX}%, ${percentY}%)"
            val coordWidth = coordinateTextPaint.measureText(coordText)
            val coordHeight = coordinateTextPaint.textSize
            val coordX = transformedX - coordWidth / 2f
            val coordY = transformedY + radius + coordHeight + 8f
            
            // 绘制坐标信息背景
            val originalCoordBgAlpha = coordinateTextBgPaint.alpha
            coordinateTextBgPaint.alpha = (originalCoordBgAlpha * targetAlpha).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(coordX - 4f, coordY - coordHeight, coordX + coordWidth + 4f, coordY + 4f, dp(4f), dp(4f), coordinateTextBgPaint)
            
            // 绘制坐标信息文本
            val originalCoordTextAlpha = coordinateTextPaint.alpha
            coordinateTextPaint.alpha = (originalCoordTextAlpha * targetAlpha).toInt().coerceIn(0, 255)
            canvas.drawText(coordText, coordX, coordY, coordinateTextPaint)
            
            // 恢复画笔透明度
            paint.alpha = originalAlpha
            labelPaint.alpha = originalLabelAlpha
            coordinateTextBgPaint.alpha = originalCoordBgAlpha
            coordinateTextPaint.alpha = originalCoordTextAlpha
        }
        
        // 移除已经完全消失的卡片
        for (id in cardsToRemove) {
            animatedCards.remove(id)
        }
        
        // 如果有动画正在进行，继续刷新
        if (animatedCards.any { (_, card) -> 
            val timeSinceStart = currentTime - card.startTime
            val timeSinceUpdate = currentTime - card.lastUpdateTime
            (card.isVisible && timeSinceStart < fadeInDuration) || 
            (!card.isVisible && timeSinceUpdate < fadeOutDuration)
        }) {
            postInvalidateOnAnimation()
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
        
        // Draw floating equation if any
        val eq = currentEquation
        if (eq != null) {
            val now = SystemClock.uptimeMillis()
            val t = (now - eq.startMs).toFloat() / eq.durationMs
            if (t in 0f..1f) {
                drawFloatingEquation(canvas, eq.equation, eq.x, eq.y, t)
                // keep animating
                postInvalidateOnAnimation()
            } else {
                currentEquation = null
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 检查是否为左键点击（主按钮）
                if (event.buttonState == MotionEvent.BUTTON_PRIMARY || event.buttonState == 0) {
                    // 检查是否点击到化学卡片
                    val id = hitTest(event.x, event.y)
                    if (id != null) {
                        activeId = id
                        lastX = event.x
                        lastY = event.y
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    } else {
                        // 如果没有点击到卡片，则切换预览模式
                        isPreviewMode = !isPreviewMode
                        invalidate() // 重新绘制
                        return true
                    }
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
    
    // 触发化学反应特效
    fun triggerReactionEffect(reaction: ChemicalReaction, cameraX: Float, cameraY: Float) {
        // 转换相机坐标为视图坐标
        val (viewX, viewY) = transformCameraCoordinate(cameraX, cameraY)
        
        // 获取反应方程式
        val reactantNames = reaction.reactants.map { getChemicalDisplayName(it) }
        val productNames = reaction.products.map { getChemicalDisplayName(it) }
        val equation = "${reactantNames.joinToString(" + ")} → ${productNames.joinToString(" + ")}"
        
        // 启动悬浮化学式效果
        currentEquation = FloatingEquation(
            equation = equation,
            startMs = SystemClock.uptimeMillis(),
            x = viewX,
            y = viewY
        )
        
        // 启动特殊效果
        val effectTypes = when {
            reaction.reactants.contains(ChemicalType.Na) && reaction.reactants.contains(ChemicalType.H2O) -> 
                listOf(EffectType.GAS, EffectType.GLOW)
            reaction.reactants.contains(ChemicalType.H2) && reaction.reactants.contains(ChemicalType.O2) -> 
                listOf(EffectType.FIRE, EffectType.GAS)
            reaction.reactants.contains(ChemicalType.Na) && reaction.reactants.contains(ChemicalType.O2) -> 
                listOf(EffectType.FIRE, EffectType.GLOW)
            reaction.reactants.contains(ChemicalType.C) && reaction.reactants.contains(ChemicalType.O2) -> 
                listOf(EffectType.FIRE, EffectType.GAS)
            else -> listOf(EffectType.GLOW)
        }
        
        currentEffect = ActiveEffect(
            types = effectTypes,
            startMs = SystemClock.uptimeMillis(),
            x = viewX,
            y = viewY
        )
        
        invalidate()
    }
    
    private fun getChemicalDisplayName(type: ChemicalType): String {
        return when (type) {
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
    }
    
    // 单位转换方法
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
    
    // 绘制悬浮化学式
    private fun drawFloatingEquation(canvas: Canvas, equation: String, x: Float, y: Float, t: Float) {
        // 计算动画参数
        val alpha = when {
            t < 0.2f -> (t / 0.2f) // 淡入
            t > 0.8f -> (1f - (t - 0.8f) / 0.2f) // 淡出
            else -> 1f // 保持
        }
        val offsetY = -t * dp(100f) // 向上浮动
        val scale = 1f + t * 0.5f // 逐渐放大
        
        // 设置画笔
        val equationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = sp(18f) * scale
            textAlign = Paint.Align.CENTER
            this.alpha = (255 * alpha).toInt()
        }
        
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#88000000")
            this.alpha = (255 * alpha * 0.8f).toInt()
        }
        
        // 计算文本尺寸
        val textWidth = equationPaint.measureText(equation)
        val textHeight = equationPaint.textSize
        val padding = dp(8f)
        
        val drawY = y + offsetY
        
        // 绘制背景
        canvas.drawRoundRect(
            x - textWidth / 2f - padding,
            drawY - textHeight - padding,
            x + textWidth / 2f + padding,
            drawY + padding,
            dp(8f), dp(8f), bgPaint
        )
        
        // 绘制化学式
        canvas.drawText(equation, x, drawY, equationPaint)
    }

    private fun drawGlowEffect(canvas: Canvas, cx: Float, cy: Float, t: Float) {
        val base = dp(60f)
        val radius = base * (0.8f + 0.2f * kotlin.math.sin(t * Math.PI).toFloat())
        glowPaint.alpha = (120 * (1f - t)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius, glowPaint)
    }

    private fun drawGasEffect(canvas: Canvas, cx: Float, cy: Float, t: Float) {
        // 增加更多气泡，分为多个层次
        val bubbleLayers = 3
        val bubblesPerLayer = 12
        
        for (layer in 0 until bubbleLayers) {
            val layerDelay = layer * 0.2f
            val layerT = (t - layerDelay).coerceIn(0f, 1f)
            if (layerT <= 0f) continue
            
            val layerIntensity = 1f - layer * 0.3f
            val rise = dp(80f) * layerT * layerIntensity
            
            for (i in 0 until bubblesPerLayer) {
                // 更随机的气泡分布
                val angle = (i / bubblesPerLayer.toFloat()) * (2f * Math.PI.toFloat()) + layer * 0.5f
                val radiusVariation = dp(20f + layer * 10f) * (0.5f + 0.5f * kotlin.math.sin(layerT * 3f + i * 0.3f))
                val bx = cx + radiusVariation * kotlin.math.cos(angle)
                val by = cy - rise - i * dp(2f) + dp(5f) * kotlin.math.sin(layerT * 4f + i * 0.2f)
                
                // 不同大小的气泡
                val baseRadius = dp(4f + layer * 2f)
                val sizeVariation = 0.5f + 0.5f * kotlin.math.sin(layerT * 5f + i * 0.4f)
                val r = baseRadius * sizeVariation * layerIntensity
                
                // 气泡透明度和颜色变化
                val alpha = (200 * layerIntensity * (1f - layerT) * sizeVariation).toInt().coerceIn(0, 255)
                
                // 创建不同颜色的气泡
                val bubbleColor = when (layer) {
                    0 -> Color.parseColor("#99FFFFFF") // 白色气泡
                    1 -> Color.parseColor("#99E3F2FD") // 淡蓝色气泡
                    else -> Color.parseColor("#99F0F8FF") // 淡青色气泡
                }
                
                val layerBubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = bubbleColor
                    style = Paint.Style.FILL
                    this.alpha = alpha
                }
                
                canvas.drawCircle(bx, by, r, layerBubblePaint)
                
                // 添加气泡内部高光效果
                if (r > dp(3f)) {
                    val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#CCFFFFFF")
                        style = Paint.Style.FILL
                        this.alpha = (alpha * 0.6f).toInt()
                    }
                    canvas.drawCircle(bx - r * 0.3f, by - r * 0.3f, r * 0.3f, highlightPaint)
                }
            }
            
            // 添加气体流动效果
            if (layerT > 0.3f) {
                drawGasStream(canvas, cx, cy, layerT, layer, layerIntensity)
            }
        }
    }
    
    private fun drawGasStream(canvas: Canvas, cx: Float, cy: Float, t: Float, layer: Int, intensity: Float) {
        val streamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#66E0F7FA")
            style = Paint.Style.STROKE
            strokeWidth = dp(2f + layer)
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(3f)), t * dp(10f))
        }
        
        val streamHeight = dp(60f) * t * intensity
        val waveAmplitude = dp(8f)
        val waveFrequency = 4f
        
        val path = android.graphics.Path()
        path.moveTo(cx, cy)
        
        val steps = 20
        for (i in 0..steps) {
            val stepT = i / steps.toFloat()
            val y = cy - streamHeight * stepT
            val waveOffset = waveAmplitude * kotlin.math.sin(stepT * waveFrequency * Math.PI + layer * 0.5).toFloat()
            val x = cx + waveOffset
            
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        
        streamPaint.alpha = (150 * intensity * (1f - t)).toInt().coerceIn(0, 255)
        canvas.drawPath(path, streamPaint)
    }

    private fun drawFireEffect(canvas: Canvas, cx: Float, cy: Float, t: Float) {
        // 三个连续的放热动画波
        val waveCount = 3
        val waveInterval = 0.3f // 每个波之间的间隔
        
        for (wave in 0 until waveCount) {
            val waveT = (t - wave * waveInterval).coerceIn(0f, 1f)
            if (waveT <= 0f) continue
            
            // 每个波的强度随时间衰减
            val waveIntensity = (1f - waveT) * (1f - wave * 0.2f)
            if (waveIntensity <= 0f) continue
            
            // 更大更丰富的火焰层
            val layers = flamePaints.size + 2 // 增加更多层
            for (i in 0 until layers) {
                val p = if (i < flamePaints.size) flamePaints[i] else flamePaints[flamePaints.size - 1]
                
                // 更大的缩放和更复杂的抖动
                val baseScale = 1.5f + waveT * 0.8f // 增大基础尺寸
                val scale = baseScale * (1f - i * 0.15f) * waveIntensity
                
                // 多方向抖动，创造更自然的火焰效果
                val jitterX = dp(12f) * kotlin.math.sin((waveT + i * 0.1f + wave * 0.5f) * 8f).toFloat()
                val jitterY = dp(8f) * kotlin.math.cos((waveT + i * 0.2f + wave * 0.3f) * 6f).toFloat()
                
                // 透明度随波次和层次变化
                val alpha = (255 * waveIntensity * (1f - i * 0.1f)).toInt().coerceIn(0, 255)
                p.alpha = alpha
                
                val rx = cx + jitterX
                val ry = cy - dp(15f) * i + jitterY - wave * dp(8f)
                val r = dp(36f) * scale // 增大火焰半径
                
                canvas.drawCircle(rx, ry, r, p)
                
                // 添加额外的火花效果
                if (i == 0 && waveT > 0.2f) {
                    drawFireSparks(canvas, cx, cy, waveT, wave, waveIntensity)
                }
            }
        }
    }
    
    private fun drawFireSparks(canvas: Canvas, cx: Float, cy: Float, t: Float, wave: Int, intensity: Float) {
        val sparkCount = 8
        val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD54F")
            style = Paint.Style.FILL
        }
        
        for (i in 0 until sparkCount) {
            val angle = (i / sparkCount.toFloat()) * (2f * Math.PI.toFloat()) + wave * 0.5f
            val distance = dp(50f) * t * intensity
            val sparkX = cx + distance * kotlin.math.cos(angle)
            val sparkY = cy + distance * kotlin.math.sin(angle) - dp(20f) * t
            val sparkRadius = dp(3f) * intensity * (1f - t)
            
            sparkPaint.alpha = (200 * intensity * (1f - t)).toInt().coerceIn(0, 255)
            canvas.drawCircle(sparkX, sparkY, sparkRadius, sparkPaint)
        }
    }
}