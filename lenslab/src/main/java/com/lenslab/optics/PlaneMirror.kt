package com.lenslab.optics

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

class PlaneMirror(var center: Vector2, var length: Float = 200f, var angleRad: Float = 0f) : OpticalComponent {
    private fun handlePosition(): Vector2 {
        val dir = Vector2.fromAngle(angleRad).normalized()
        val n = dir.perp().normalized() // 法向外侧
        return Vector2(center.x + n.x * 18f, center.y + n.y * 18f)
    }
    fun segment(): Segment {
        val half = length / 2f
        val dir = Vector2.fromAngle(angleRad)
        val a = Vector2(center.x - dir.x * half, center.y - dir.y * half)
        val b = Vector2(center.x + dir.x * half, center.y + dir.y * half)
        return Segment(a, b)
    }

    override fun draw(canvas: Canvas, paint: Paint) {
        val seg = segment()
        paint.color = 0xFF3F51B5.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        canvas.drawLine(seg.a.x, seg.a.y, seg.b.x, seg.b.y, paint)

        // 绘制旋转手柄位置标记（小圆点）
        val handle = handlePosition()
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFA000.toInt() // 橙色强调
        canvas.drawCircle(handle.x, handle.y, 10f, paint)
    }

    override fun bounds(): RectF {
        val seg = segment()
        val left = minOf(seg.a.x, seg.b.x)
        val right = maxOf(seg.a.x, seg.b.x)
        val top = minOf(seg.a.y, seg.b.y)
        val bottom = maxOf(seg.a.y, seg.b.y)
        return RectF(left, top, right, bottom)
    }

    fun rotate(deltaRad: Float) { angleRad += deltaRad }
}