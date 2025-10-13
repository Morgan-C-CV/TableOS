package com.lenslab.optics

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class TriangularPrism(
    var center: Vector2,
    var sideLength: Float = 160f,
    var angleRad: Float = 0f,
    var refractiveIndex: Float = 1.5f
) : OpticalComponent {

    private fun vertices(): List<Vector2> {
        // Equilateral triangle centered at center, rotated by angleRad
        val r = sideLength / (kotlin.math.sqrt(3f)) // circumscribed radius for equilateral triangle
        val angles = listOf(0f, 2f * Math.PI.toFloat() / 3f, 4f * Math.PI.toFloat() / 3f)
        return angles.map { a ->
            val theta = a + angleRad
            Vector2(center.x + r * cos(theta), center.y + r * sin(theta))
        }
    }

    fun segments(): List<Segment> {
        val v = vertices()
        return listOf(
            Segment(v[0], v[1]),
            Segment(v[1], v[2]),
            Segment(v[2], v[0])
        )
    }

    override fun draw(canvas: Canvas, paint: Paint) {
        val v = vertices()
        paint.style = Paint.Style.STROKE
        paint.color = 0xFF00BCD4.toInt() // Cyan
        paint.strokeWidth = 5f
        val path = Path()
        path.moveTo(v[0].x, v[0].y)
        path.lineTo(v[1].x, v[1].y)
        path.lineTo(v[2].x, v[2].y)
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun bounds(): RectF {
        val v = vertices()
        val xs = v.map { it.x }
        val ys = v.map { it.y }
        val left = xs.minOrNull() ?: center.x
        val right = xs.maxOrNull() ?: center.x
        val top = ys.minOrNull() ?: center.y
        val bottom = ys.maxOrNull() ?: center.y
        return RectF(left, top, right, bottom)
    }

    fun rotate(deltaRad: Float) { angleRad += deltaRad }
}