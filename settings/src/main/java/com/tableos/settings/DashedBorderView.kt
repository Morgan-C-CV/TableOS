package com.tableos.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.DashPathEffect
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.net.Uri
import android.util.Log

class DashedBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f * d
        pathEffect = DashPathEffect(floatArrayOf(16f * d, 10f * d), 0f)
    }
    private val path = Path()
    private var normalizedPoints: Array<Pair<Float, Float>>? = null
    private val providerUri = Uri.parse("content://com.tableos.app.keystone/config")

    // 十字闪烁配置
    private val flashColors = intArrayOf(Color.BLACK, Color.WHITE, Color.RED)
    private var flashColorIndex = 0
    private val flashIntervalMs = 450L
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * d
        color = flashColors[flashColorIndex]
    }
    private val crossLength = 12f * d
    private val mainHandler = Handler(Looper.getMainLooper())
    private val flashRunnable = object : Runnable {
        override fun run() {
            flashColorIndex = (flashColorIndex + 1) % flashColors.size
            crossPaint.color = flashColors[flashColorIndex]
            invalidate()
            mainHandler.postDelayed(this, flashIntervalMs)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pts = normalizedPoints
        if (pts == null || width == 0 || height == 0) {
            // 无配置或视图未就绪时不绘制（避免误导的全屏边框）
            return
        }

        // 将归一化坐标缩放为像素坐标
        val p0x = (pts[0].first.coerceIn(0f, 1f)) * width
        val p0y = (pts[0].second.coerceIn(0f, 1f)) * height
        val p1x = (pts[1].first.coerceIn(0f, 1f)) * width
        val p1y = (pts[1].second.coerceIn(0f, 1f)) * height
        val p2x = (pts[2].first.coerceIn(0f, 1f)) * width
        val p2y = (pts[2].second.coerceIn(0f, 1f)) * height
        val p3x = (pts[3].first.coerceIn(0f, 1f)) * width
        val p3y = (pts[3].second.coerceIn(0f, 1f)) * height

        path.reset()
        path.moveTo(p0x, p0y)
        path.lineTo(p1x, p1y)
        path.lineTo(p2x, p2y)
        path.lineTo(p3x, p3y)
        path.close()

        canvas.drawPath(path, strokePaint)

        // 在四角绘制十字标识（黑/白/红交替闪烁）
        drawCross(canvas, p0x, p0y)
        drawCross(canvas, p1x, p1y)
        drawCross(canvas, p2x, p2y)
        drawCross(canvas, p3x, p3y)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadConfig()
        // 开始十字闪烁动画
        mainHandler.removeCallbacks(flashRunnable)
        mainHandler.postDelayed(flashRunnable, flashIntervalMs)
    }

    private fun loadConfig() {
        try {
            context.contentResolver.query(providerUri, arrayOf("value"), null, null, null)?.use { c ->
                val csv = if (c.moveToFirst()) c.getString(0) else null
                normalizedPoints = parseCsv(csv)
                Log.d("DashedBorderView", "loadConfig: csv=$csv parsed=${normalizedPoints != null}")
                invalidate()
            }
        } catch (t: Throwable) {
            Log.e("DashedBorderView", "loadConfig failed", t)
            normalizedPoints = null
        }
    }

    private fun parseCsv(csv: String?): Array<Pair<Float, Float>>? {
        if (csv.isNullOrBlank()) return null
        val segs = csv.split(";")
        if (segs.size != 4) return null
        return try {
            Array(4) { i ->
                val xy = segs[i].split(",")
                val x = xy[0].toFloat()
                val y = xy[1].toFloat()
                Pair(x, y)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun drawCross(canvas: Canvas, x: Float, y: Float) {
        // 水平线
        canvas.drawLine(x - crossLength, y, x + crossLength, y, crossPaint)
        // 垂直线
        canvas.drawLine(x, y - crossLength, x, y + crossLength, crossPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacks(flashRunnable)
    }
}