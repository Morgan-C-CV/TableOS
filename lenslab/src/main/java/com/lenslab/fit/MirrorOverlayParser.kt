package com.lenslab.fit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object MirrorOverlayParser {

    data class Pt(val x: Float, val y: Float)
    data class MirrorResult(val center: PointF, val length: Float, val angleRad: Float, val decorPath: Path?)

    private fun extractPoints(bitmap: Bitmap, downsample: Int = 2): List<Pt> {
        val w = bitmap.width
        val h = bitmap.height
        val pts = ArrayList<Pt>()
        val hsv = FloatArray(3)
        val stepX = if (downsample <= 0) 1 else downsample
        val stepY = if (downsample <= 0) 1 else downsample
        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val c = bitmap.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val valV = hsv[2]
                if (hue in 190f..260f && sat >= 0.25f && valV >= 0.2f) {
                    pts.add(Pt(x.toFloat(), y.toFloat()))
                }
            }
        }
        if (pts.size < 20) {
            val altPts = ArrayList<Pt>()
            for (y in 0 until h step stepY) {
                for (x in 0 until w - 1 step stepX) {
                    val c1 = bitmap.getPixel(x, y)
                    val c2 = bitmap.getPixel(x + 1, y)
                    val v1 = Color.luminance(c1)
                    val v2 = Color.luminance(c2)
                    if (abs(v1 - v2) > 0.08f) {
                        altPts.add(Pt(x.toFloat(), y.toFloat()))
                    }
                }
            }
            return altPts
        }
        return pts
    }

    fun estimatePlaneMirrorFromBitmap(bitmap: Bitmap, downsample: Int = 2): MirrorResult? {
        val pts = extractPoints(bitmap, downsample)
        if (pts.size < 20) return null
        // Mean
        var mx = 0f
        var my = 0f
        for (p in pts) { mx += p.x; my += p.y }
        mx /= pts.size
        my /= pts.size
        // Covariance
        var sxx = 0f
        var sxy = 0f
        var syy = 0f
        for (p in pts) {
            val dx = p.x - mx
            val dy = p.y - my
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        sxx /= pts.size
        sxy /= pts.size
        syy /= pts.size
        // Major axis (length direction)
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = max(0f, tr * tr / 4f - det)
        val lambda1 = tr / 2f + sqrt(disc)
        var axX = sxy
        var axY = lambda1 - sxx
        val n1 = sqrt(max(axX * axX + axY * axY, 1e-6f))
        axX /= n1
        axY /= n1
        // Minor axis (width direction)
        val nxX = -axY
        val nxY = axX

        val projLen = FloatArray(pts.size)
        val projWid = FloatArray(pts.size)
        for (i in pts.indices) {
            val dx = pts[i].x - mx
            val dy = pts[i].y - my
            projLen[i] = dx * axX + dy * axY
            projWid[i] = dx * nxX + dy * nxY
        }
        val lenMin = projLen.minOrNull() ?: return null
        val lenMax = projLen.maxOrNull() ?: return null
        val widMin = projWid.minOrNull() ?: 0f
        val widMax = projWid.maxOrNull() ?: 0f
        val rangeLen = lenMax - lenMin
        val rangeWid = widMax - widMin
        if (rangeLen <= 4f) return null
        // Select points near main line by width band
        var sumWid = 0f
        for (w in projWid) sumWid += w
        val meanWid = sumWid / projWid.size
        val halfBand = max(3f, rangeWid * 0.25f)
        var endMin = Float.POSITIVE_INFINITY
        var endMax = Float.NEGATIVE_INFINITY
        for (i in pts.indices) {
            if (abs(projWid[i] - meanWid) <= halfBand) {
                endMin = min(endMin, projLen[i])
                endMax = max(endMax, projLen[i])
            }
        }
        if (!endMin.isFinite() || !endMax.isFinite()) return null
        val center = PointF(
            (mx + axX * ((endMin + endMax) * 0.5f)),
            (my + axY * ((endMin + endMax) * 0.5f))
        )
        val length = (endMax - endMin)
        val angleRad = kotlin.math.atan2(axY.toDouble(), axX.toDouble()).toFloat()

        // Simple decoration path: main line segment
        val pA = PointF(mx + axX * endMin, my + axY * endMin)
        val pB = PointF(mx + axX * endMax, my + axY * endMax)
        val path = Path().apply {
            moveTo(pA.x, pA.y)
            lineTo(pB.x, pB.y)
        }
        return MirrorResult(center, length, angleRad, path)
    }
}