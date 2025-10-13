package com.lenslab.fit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import com.lenslab.fit.FitResult

object LensOverlayParser {

    data class Pt(val x: Float, val y: Float)

    fun extractBlueContourPath(bitmap: Bitmap, downsample: Int = 2): Path {
        val w = bitmap.width
        val h = bitmap.height
        // 构建蓝色像素掩码
        val mask = BooleanArray(w * h)
        val hsv = FloatArray(3)
        val stepX = if (downsample <= 0) 1 else downsample
        val stepY = if (downsample <= 0) 1 else downsample
        fun idx(x: Int, y: Int) = y * w + x
        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val c = bitmap.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val valV = hsv[2]
                if (hue in 190f..260f && sat >= 0.25f && valV >= 0.2f) {
                    mask[idx(x, y)] = true
                }
            }
        }
        // 如果蓝色像素很少，使用亮度梯度作为近似边缘掩码
        var blueCount = 0
        for (b in mask) if (b) blueCount++
        if (blueCount < 20) {
            java.util.Arrays.fill(mask, false)
            // 梯度幅值 + 形态学闭运算，得到更完整的边缘掩码
            fun lum(x: Int, y: Int) = Color.luminance(bitmap.getPixel(x, y))
            for (y in 1 until h - 1 step stepY) {
                for (x in 1 until w - 1 step stepX) {
                    val dx = lum(x + 1, y) - lum(x - 1, y)
                    val dy = lum(x, y + 1) - lum(x, y - 1)
                    val mag = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (mag > 0.12f) mask[idx(x, y)] = true
                }
            }
            // close: 3x3 dilation + erosion
            val dil = BooleanArray(w * h)
            val ero = BooleanArray(w * h)
            val dx9 = intArrayOf(-1, 0, 1, -1, 0, 1, -1, 0, 1)
            val dy9 = intArrayOf(-1, -1, -1, 0, 0, 0, 1, 1, 1)
            fun id(x: Int, y: Int) = y * w + x
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    var any = false
                    for (k in 0 until 9) { if (mask[id(x + dx9[k], y + dy9[k])]) { any = true; break } }
                    dil[id(x, y)] = any
                }
            }
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    var all = true
                    for (k in 0 until 9) { if (!dil[id(x + dx9[k], y + dy9[k])]) { all = false; break } }
                    ero[id(x, y)] = all
                }
            }
            // 用闭运算结果替换掩码
            for (i in 0 until w * h) mask[i] = ero[i]
        }
        // 寻找边界起点（最上最左的边界像素）
        fun isInside(x: Int, y: Int) = (x in 0 until w && y in 0 until h)
        fun isBoundary(x: Int, y: Int): Boolean {
            if (!isInside(x, y)) return false
            if (!mask[idx(x, y)]) return false
            // 八邻域中有非掩码或越界则视为边界
            val dx = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
            val dy = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)
            for (k in 0 until 8) {
                val nx = x + dx[k]
                val ny = y + dy[k]
                if (!isInside(nx, ny) || !mask[idx(nx, ny)]) return true
            }
            return false
        }
        var startX = -1
        var startY = -1
        run {
            outer@ for (y in 0 until h) {
                for (x in 0 until w) {
                    if (isBoundary(x, y)) { startX = x; startY = y; break@outer }
                }
            }
        }
        if (startX < 0 || startY < 0) return Path()
        // Moore邻域边界跟踪
        val dx8 = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
        val dy8 = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)
        var x = startX
        var y = startY
        var prevDir = 7 // 从左侧开始扫描
        val contour = ArrayList<Pt>()
        contour.add(Pt(x.toFloat(), y.toFloat()))
        var steps = 0
        val maxSteps = w * h * 4
        while (steps < maxSteps) {
            var found = false
            // 以上一邻域为参考，顺时针扫描8邻域
            for (i in 0 until 8) {
                val dir = (prevDir + 1 + i) % 8
                val nx = x + dx8[dir]
                val ny = y + dy8[dir]
                if (isInside(nx, ny) && mask[idx(nx, ny)]) {
                    // 添加折线点（简单抽稀：方向改变或每3步）
                    val last = contour.last()
                    if (contour.size < 2 || steps % 3 == 0 || last.x != x.toFloat() || last.y != y.toFloat()) {
                        contour.add(Pt(nx.toFloat(), ny.toFloat()))
                    }
                    x = nx; y = ny
                    // 下一次扫描从该邻域的逆方向起步
                    prevDir = (dir + 6) % 8
                    found = true
                    break
                }
            }
            steps++
            if (!found) break
            if (x == startX && y == startY && contour.size > 10) break
        }
        // 如果边界太短，回退到凸包
        val path = Path()
        if (contour.size >= 10) {
            path.moveTo(contour[0].x, contour[0].y)
            var i = 1
            while (i < contour.size) {
                path.lineTo(contour[i].x, contour[i].y)
                i++
            }
            path.close()
            return path
        }
        // 回退：使用凸包避免空路径
        val pts = ArrayList<Pt>()
        for (y2 in 0 until h step stepY) {
            for (x2 in 0 until w step stepX) {
                if (mask[idx(x2, y2)]) pts.add(Pt(x2.toFloat(), y2.toFloat()))
            }
        }
        if (pts.size < 3) return Path()
        val hull = convexHull(pts)
        if (hull.isEmpty()) return Path()
        path.moveTo(hull[0].x, hull[0].y)
        var j = 1
        while (j < hull.size) { path.lineTo(hull[j].x, hull[j].y); j++ }
        path.close()
        return path
    }

    private fun cross(o: Pt, a: Pt, b: Pt): Float {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    }

    private fun convexHull(points: List<Pt>): List<Pt> {
        // Monotonic chain algorithm
        val pts = points.sortedWith(compareBy<Pt>({ it.x }, { it.y }))
        val lower = ArrayList<Pt>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }
        val upper = ArrayList<Pt>()
        for (i in pts.indices.reversed()) {
            val p = pts[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }
        // Concatenate lower and upper to get full hull; last point of each list is duplicate of the first point of the other list
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun extractPoints(bitmap: Bitmap, downsample: Int = 2): List<Pt> {
        val w = bitmap.width
        val h = bitmap.height
        val pts = ArrayList<Pt>()
        val hsv = FloatArray(3)
        val stepX = if (downsample <= 0) 1 else downsample
        val stepY = if (downsample <= 0) 1 else downsample
        var blueCount = 0
        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val c = bitmap.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val valV = hsv[2]
                if (hue in 190f..260f && sat >= 0.25f && valV >= 0.2f) {
                    pts.add(Pt(x.toFloat(), y.toFloat()))
                    blueCount++
                }
            }
        }
        if (blueCount >= 20) return pts
        // Fallback: 使用梯度幅值的边缘检测，并做一次形态学闭运算增强连通性
        val mask = BooleanArray(w * h)
        fun idx(x: Int, y: Int) = y * w + x
        for (y in 1 until h - 1 step stepY) {
            for (x in 1 until w - 1 step stepX) {
                val vxm1 = Color.luminance(bitmap.getPixel(x - 1, y))
                val vxp1 = Color.luminance(bitmap.getPixel(x + 1, y))
                val vym1 = Color.luminance(bitmap.getPixel(x, y - 1))
                val vyp1 = Color.luminance(bitmap.getPixel(x, y + 1))
                val dx = vxp1 - vxm1
                val dy = vyp1 - vym1
                val mag = kotlin.math.sqrt(dx * dx + dy * dy)
                if (mag > 0.12f) mask[idx(x, y)] = true
            }
        }
        // 3x3 dilation then erosion (close)
        val dil = BooleanArray(w * h)
        val ero = BooleanArray(w * h)
        val dx8 = intArrayOf(-1, 0, 1, -1, 0, 1, -1, 0, 1)
        val dy8 = intArrayOf(-1, -1, -1, 0, 0, 0, 1, 1, 1)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var any = false
                for (k in 0 until 9) { if (mask[idx(x + dx8[k], y + dy8[k])]) { any = true; break } }
                dil[idx(x, y)] = any
            }
        }
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var all = true
                for (k in 0 until 9) { if (!dil[idx(x + dx8[k], y + dy8[k])]) { all = false; break } }
                ero[idx(x, y)] = all
            }
        }
        val altPts = ArrayList<Pt>()
        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                if (ero[idx(x, y)]) altPts.add(Pt(x.toFloat(), y.toFloat()))
            }
        }
        return altPts
    }

    // Estimate fitted parameters from bitmap points (simplified version of Python code)
    fun estimateFitFromBitmap(bitmap: Bitmap, downsample: Int = 2): Pair<FitResult, PointF>? {
        var pts = extractPoints(bitmap, downsample)
        if (pts.size < 40) {
            // 尝试提高采样密度
            pts = extractPoints(bitmap, 1)
            if (pts.size < 12) return null
        }
        // Compute mean
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
        // Principal axes of covariance [[sxx, sxy],[sxy, syy]]
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = max(0f, tr * tr / 4f - det)
        val lambda1 = tr / 2f + sqrt(disc)
        // eigenvector for largest eigenvalue (major axis)
        var mx1 = sxy
        var my1 = lambda1 - sxx
        val n1 = sqrt(max(mx1 * mx1 + my1 * my1, 1e-6f))
        mx1 /= n1
        my1 /= n1
        // minor axis is orthogonal to major axis
        val axX = -my1 // lens axis should align with minor axis (thickness direction)
        val axY = mx1
        val htX = mx1   // height direction along major axis
        val htY = my1
        // Project points
        val projAxis = FloatArray(pts.size)
        val projHeight = FloatArray(pts.size)
        for (i in pts.indices) {
            val dx = pts[i].x - mx
            val dy = pts[i].y - my
            projAxis[i] = dx * axX + dy * axY
            projHeight[i] = dx * htX + dy * htY
        }
        // 优先使用靠近中部的点进行侧面圆弧拟合，减少上下角点对半径的偏置
        val centralBandHalf = max(((projHeight.maxOrNull() ?: 0f) - (projHeight.minOrNull() ?: 0f)) * 0.45f, 6f)
        val leftPts = ArrayList<Pt>()
        val rightPts = ArrayList<Pt>()
        val leftCentral = ArrayList<Pt>()
        val rightCentral = ArrayList<Pt>()
        for (i in pts.indices) {
            val isLeft = projAxis[i] < 0f
            val p = pts[i]
            if (isLeft) leftPts.add(p) else rightPts.add(p)
            if (abs(projHeight[i]) <= centralBandHalf) {
                if (isLeft) leftCentral.add(p) else rightCentral.add(p)
            }
        }
        // 侧面点过少时，允许继续（>=3即可），并使用稳健的近似半径
        val insufficientSides = (leftPts.size < 3 || rightPts.size < 3)

        fun fitCircle(sidePts: List<Pt>): Pair<PointF, Float> {
            var a11 = 0.0
            var a12 = 0.0
            var a13 = 0.0
            var b1 = 0.0
            var a22 = 0.0
            var a23 = 0.0
            var b2 = 0.0
            var a33 = 0.0
            var b3 = 0.0
            for (p in sidePts) {
                val x = p.x.toDouble()
                val y = p.y.toDouble()
                a11 += 4 * x * x
                a12 += 4 * x * y
                a13 += 2 * x
                b1 += 2 * x * x * x + 2 * x * y * y

                a22 += 4 * y * y
                a23 += 2 * y
                b2 += 2 * y * x * x + 2 * y * y * y

                a33 += 1.0
                b3 += x * x + y * y
            }
            // Solve symmetric 3x3 via Cramer's rule or use simple elimination
            // Matrix A:
            // [a11 a12 a13]
            // [a12 a22 a23]
            // [a13 a23 a33]
            // Vector b: [b1 b2 b3]
            // We'll use Gaussian elimination (not optimized)
            var A = arrayOf(
                doubleArrayOf(a11, a12, a13, b1),
                doubleArrayOf(a12, a22, a23, b2),
                doubleArrayOf(a13, a23, a33, b3)
            )
            fun pivot(r: Int) {
                var maxRow = r
                var maxAbs = abs(A[r][r].toFloat())
                for (i in r + 1..2) {
                    val v = abs(A[i][r].toFloat())
                    if (v > maxAbs) { maxAbs = v; maxRow = i }
                }
                if (maxRow != r) {
                    val tmp = A[r]
                    A[r] = A[maxRow]
                    A[maxRow] = tmp
                }
            }
            for (r in 0..2) {
                pivot(r)
                val div = A[r][r]
                if (abs(div.toFloat()) < 1e-6f) continue
                for (c in r..3) A[r][c] /= div
                for (i in 0..2) if (i != r) {
                    val factor = A[i][r]
                    for (c in r..3) A[i][c] -= factor * A[r][c]
                }
            }
            val cx = A[0][3]
            val cy = A[1][3]
            val d = A[2][3]
            val r = sqrt(max(cx * cx + cy * cy - d, 1e-6))
            return PointF(cx.toFloat(), cy.toFloat()) to r.toFloat()
        }

        // 若中部点充足，使用中部点拟合；否则退回全体侧面点
        val useLeft = if (leftCentral.size >= 8) leftCentral else leftPts
        val useRight = if (rightCentral.size >= 8) rightCentral else rightPts
        val (cL, rL) = if (!insufficientSides) fitCircle(useLeft) else PointF(mx, my) to max(
            abs(projAxis.maxOrNull() ?: 0f),
            abs(projAxis.minOrNull() ?: 0f)
        )
        val (cR, rR) = if (!insufficientSides) fitCircle(useRight) else PointF(mx, my) to max(
            abs(projAxis.maxOrNull() ?: 0f),
            abs(projAxis.minOrNull() ?: 0f)
        )
        val aperture = (projHeight.maxOrNull() ?: 0f) - (projHeight.minOrNull() ?: 0f)
        // 厚度改为中心带宽（更符合几何中“轴向两表面间距”的定义）
        // 若中心带宽不可用则退回全局极值计算
        fun bandWidth(bandCenterU: Float, halfBandU: Float = max(aperture * 0.05f, 4.0f)): Float {
            var minV = Float.POSITIVE_INFINITY
            var maxV = Float.NEGATIVE_INFINITY
            var count = 0
            for (i in pts.indices) {
                if (abs(projHeight[i] - bandCenterU) <= halfBandU) {
                    minV = min(minV, projAxis[i]); maxV = max(maxV, projAxis[i]); count++
                }
            }
            return if (count < 8) Float.NaN else (maxV - minV)
        }
        val centerW = bandWidth(0f)
        val thickness = if (centerW.isNaN()) {
            (projAxis.maxOrNull() ?: 0f) - (projAxis.minOrNull() ?: 0f)
        } else centerW
        val curvatureRadius = (rL + rR) / 2f
        val angleRad = kotlin.math.atan2(axY.toDouble(), axX.toDouble()).toFloat()
        val topW = bandWidth(+aperture * 0.45f)
        val botW = bandWidth(-aperture * 0.45f)
        val edgeW = if (topW.isNaN() || botW.isNaN()) Float.NaN else ((topW + botW) * 0.5f)
        val tol = max(2.0f, aperture * 0.02f)
        val uL = (cL.x - mx) * axX + (cL.y - my) * axY
        val uR = (cR.x - mx) * axX + (cR.y - my) * axY
        val leftOut = uL < 0f
        val rightOut = uR > 0f
        val lensType = if (!centerW.isNaN() && !edgeW.isNaN()) {
            if (centerW > edgeW + tol) "convex"
            else if (centerW + tol < edgeW) "concave"
            else if (leftOut && rightOut) "convex" else if (!leftOut && !rightOut) "concave" else "unknown"
        } else {
            if (leftOut && rightOut) "convex" else if (!leftOut && !rightOut) "concave" else "unknown"
        }

        val res = FitResult(
            type = lensType,
            angleRad = angleRad,
            aperture = aperture,
            thickness = thickness,
            curvatureRadius = curvatureRadius,
        )
        return res to PointF(mx, my)
    }
}