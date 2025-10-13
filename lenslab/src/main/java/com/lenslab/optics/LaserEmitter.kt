package com.lenslab.optics

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

class LaserEmitter(var position: Vector2, var angleRad: Float) : OpticalComponent {
    val rayColor = 0xFFFF0000.toInt()

    override fun draw(canvas: Canvas, paint: Paint) {
        // Draw emitter as a small circle with a direction line
        paint.style = Paint.Style.FILL
        paint.color = 0xFF000000.toInt()
        canvas.drawCircle(position.x, position.y, 12f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        val dir = Vector2.fromAngle(angleRad)
        canvas.drawLine(position.x, position.y, position.x + dir.x * 40f, position.y + dir.y * 40f, paint)
    }

    override fun bounds(): RectF = RectF(position.x - 20f, position.y - 20f, position.x + 20f, position.y + 20f)

    fun emitRay(): Ray = Ray(position, Vector2.fromAngle(angleRad).normalized())

    fun rotate(deltaRad: Float) { angleRad += deltaRad }
}