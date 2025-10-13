package com.lenslab.optics

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

class CustomLens(
    val path: Path,
    var strokeWidth: Float = 3.5f,
    var color: Int = 0xFF1E88E5.toInt() // Blue 600
) : OpticalComponent {

    private val boundsRect = RectF()

    init {
        // Compute bounds from path once
        path.computeBounds(boundsRect, true)
    }

    override fun draw(canvas: Canvas, paint: Paint) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = strokeWidth
        canvas.drawPath(path, paint)
    }

    override fun bounds(): RectF {
        return RectF(boundsRect)
    }
}