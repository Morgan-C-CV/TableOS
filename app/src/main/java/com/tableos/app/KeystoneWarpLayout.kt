package com.tableos.app

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout

class KeystoneWarpLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    private var points: Array<Pair<Float, Float>>? = null // 归一化坐标
    private val warpMatrix = Matrix()
    private val path = Path()
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    fun loadConfig() {
        val csv = readFromProvider()
        points = parseCsv(csv)
        buildMatrix()
        invalidate()
    }

    private fun buildMatrix() {
        val pts = points ?: return
        if (width == 0 || height == 0) return
        val src = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            width.toFloat(), height.toFloat(),
            0f, height.toFloat()
        )
        val p0 = toPx(pts[0]); val p1 = toPx(pts[1]); val p2 = toPx(pts[2]); val p3 = toPx(pts[3])
        val dst = floatArrayOf(
            p0.first, p0.second,
            p1.first, p1.second,
            p2.first, p2.second,
            p3.first, p3.second
        )
        warpMatrix.reset()
        warpMatrix.setPolyToPoly(src, 0, dst, 0, 4)

        path.reset()
        path.moveTo(p0.first, p0.second)
        path.lineTo(p1.first, p1.second)
        path.lineTo(p2.first, p2.second)
        path.lineTo(p3.first, p3.second)
        path.close()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildMatrix()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val pts = points
        if (pts == null) {
            super.dispatchDraw(canvas)
            return
        }
        // 背景黑色
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blackPaint)
        // 在选区内绘制子视图，并对坐标系做透视变换
        val save = canvas.save()
        canvas.clipPath(path)
        canvas.concat(warpMatrix)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    private fun toPx(p: Pair<Float, Float>): Pair<Float, Float> {
        return Pair(p.first.coerceIn(0f, 1f) * width, p.second.coerceIn(0f, 1f) * height)
    }

    private fun parseCsv(csv: String?): Array<Pair<Float, Float>>? {
        if (csv.isNullOrBlank()) return null
        val parts = csv.split(";")
        if (parts.size != 4) return null
        return try {
            Array(4) { i ->
                val (sx, sy) = parts[i].split(",")
                Pair(sx.toFloat(), sy.toFloat())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readFromProvider(): String? {
        return try {
            val uri = Uri.parse("content://com.tableos.app.keystone/config")
            context.contentResolver.query(uri, arrayOf("value"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}