package com.lenslab.optics

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

class ConvexLens(
    var center: Vector2,
    var aperture: Float = 200f, // 高度（稍大于三棱镜）
    var thickness: Float = 70f,
    var curvatureRadius: Float = 220f,
    var angleRad: Float = 0f,
    var refractiveIndex: Float = 1.5f
) : OpticalComponent {

    private fun axis(): Vector2 = Vector2.fromAngle(angleRad).normalized()
    private fun ortho(): Vector2 = axis().perp().normalized()

    // 两侧球面中心与半径（凸：中心在面后）
    fun surfaces(): List<Pair<Vector2, Float>> {
        val u = axis()
        val leftSurfacePos = center - u * (thickness / 2f)
        val rightSurfacePos = center + u * (thickness / 2f)
        // 修正：凸透镜两侧球面圆心应位于透镜内部（靠近中心），使得边缘更薄、中心更厚
        val leftCenter = leftSurfacePos + u * curvatureRadius
        val rightCenter = rightSurfacePos - u * curvatureRadius
        return listOf(leftCenter to curvatureRadius, rightCenter to curvatureRadius)
    }

    override fun draw(canvas: Canvas, paint: Paint) {
        val u = axis(); val v = ortho()
        val halfH = aperture / 2f
        val clamp = kotlin.math.min(halfH, curvatureRadius - 1f)
        val (leftCenter, rL) = surfaces()[0]
        val (rightCenter, rR) = surfaces()[1]

        // 采样圆弧：凸透镜两侧向外凸起，边缘更薄、中心更厚
        fun leftPoint(t: Float): Vector2 {
            val sv = v * t
            val su = u * kotlin.math.sqrt((rL * rL - t * t).toDouble()).toFloat()
            return leftCenter - su + sv
        }
        fun rightPoint(t: Float): Vector2 {
            val sv = v * t
            val su = u * kotlin.math.sqrt((rR * rR - t * t).toDouble()).toFloat()
            return rightCenter + su + sv
        }

        val path = Path()
        val samples = 40
        // 从上沿开始沿左表面到下沿
        var t = -clamp
        var p = leftPoint(t)
        path.moveTo(p.x, p.y)
        for (i in 1..samples) {
            t = -clamp + (2f * clamp) * (i.toFloat() / samples.toFloat())
            p = leftPoint(t)
            path.lineTo(p.x, p.y)
        }
        // 再沿右表面从下沿回到上沿
        for (i in samples downTo 0) {
            t = -clamp + (2f * clamp) * (i.toFloat() / samples.toFloat())
            p = rightPoint(t)
            path.lineTo(p.x, p.y)
        }
        path.close()

        paint.style = Paint.Style.STROKE
        paint.color = 0xFF4CAF50.toInt()
        paint.strokeWidth = 5f
        canvas.drawPath(path, paint)
    }

    override fun bounds(): RectF {
        val u = axis(); val v = ortho()
        val halfH = aperture / 2f
        val left = center - u * (thickness / 2f)
        val right = center + u * (thickness / 2f)
        val xs = floatArrayOf(
            (left + v * (-halfH)).x,
            (right + v * (-halfH)).x,
            (right + v * (halfH)).x,
            (left + v * (halfH)).x
        )
        val ys = floatArrayOf(
            (left + v * (-halfH)).y,
            (right + v * (-halfH)).y,
            (right + v * (halfH)).y,
            (left + v * (halfH)).y
        )
        val leftB = xs.minOrNull() ?: center.x
        val rightB = xs.maxOrNull() ?: center.x
        val topB = ys.minOrNull() ?: center.y
        val bottomB = ys.maxOrNull() ?: center.y
        return RectF(leftB, topB, rightB, bottomB)
    }

    fun rotate(deltaRad: Float) { angleRad += deltaRad }
}