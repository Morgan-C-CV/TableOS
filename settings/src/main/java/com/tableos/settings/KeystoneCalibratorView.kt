package com.tableos.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class KeystoneCalibratorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val pts = Array(4) { Pair(0f, 0f) } // 0:左上 1:右上 2:右下 3:左下 （归一化）
    private var selected = 0
    private val circleR = 16f
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 3f; style = Paint.Style.STROKE }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val paintPoint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val paintPointSel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4DA3F7"); style = Paint.Style.FILL }

    init {
        // 初始为四周 5% 内缩的矩形
        val m = 0.05f
        pts[0] = Pair(m, m)
        pts[1] = Pair(1f - m, m)
        pts[2] = Pair(1f - m, 1f - m)
        pts[3] = Pair(m, 1f - m)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 背景使用纯黑色
        canvas.drawColor(Color.BLACK)

        val p0 = px(0, w, h); val p1 = px(1, w, h); val p2 = px(2, w, h); val p3 = px(3, w, h)
        val path = Path().apply {
            moveTo(p0.first, p0.second)
            lineTo(p1.first, p1.second)
            lineTo(p2.first, p2.second)
            lineTo(p3.first, p3.second)
            close()
        }
        // 填充四边形为白色，并绘制白色边框
        canvas.drawPath(path, paintFill)
        canvas.drawPath(path, paintLine)

        // 四角点
        drawPoint(canvas, p0, 0)
        drawPoint(canvas, p1, 1)
        drawPoint(canvas, p2, 2)
        drawPoint(canvas, p3, 3)
    }

    private fun drawPoint(canvas: Canvas, p: Pair<Float, Float>, idx: Int) {
        val paint = if (idx == selected) paintPointSel else paintPoint
        // 角点外圈白色描边，内部黑色以对比白色填充区域
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawCircle(p.first, p.second, circleR, paint)
        canvas.drawCircle(p.first, p.second, circleR, stroke)
    }

    private fun px(i: Int, w: Float, h: Float): Pair<Float, Float> {
        val (x, y) = pts[i]
        return Pair(x.coerceIn(0f, 1f) * w, y.coerceIn(0f, 1f) * h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x; val y = event.y
                // 命中测试：距离最近的角点
                var best = 0; var bestD = Float.MAX_VALUE
                for (i in 0..3) {
                    val p = px(i, w, h)
                    val d = hypot(x - p.first, y - p.second)
                    if (d < bestD) { bestD = d; best = i }
                }
                selected = best
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                setSelectedByPx(event.x, event.y)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun setSelectedByPx(x: Float, y: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        val nx = (x / w).coerceIn(0f, 1f)
        val ny = (y / h).coerceIn(0f, 1f)
        pts[selected] = Pair(nx, ny)
        invalidate()
    }

    fun nudgeSelected(dx: Float, dy: Float) {
        val (x, y) = pts[selected]
        pts[selected] = Pair((x + dx).coerceIn(0f, 1f), (y + dy).coerceIn(0f, 1f))
        invalidate()
    }

    fun selectNext() {
        selected = (selected + 1) % 4
        invalidate()
    }

    fun buildCsv(): String {
        // 简单 CSV：x0,y0;x1,y1;x2,y2;x3,y3
        return (0..3).joinToString(";") { i ->
            val (x, y) = pts[i]
            "${x},${y}"
        }
    }
}