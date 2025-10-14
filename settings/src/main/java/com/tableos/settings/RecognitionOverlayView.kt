package com.tableos.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class RecognitionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxes = mutableListOf<RectF>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFCC44.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    fun showBoxes(newBoxes: List<RectF>) {
        boxes.clear()
        boxes.addAll(newBoxes)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (r in boxes) {
            canvas.drawRect(r, paint)
        }
    }
}