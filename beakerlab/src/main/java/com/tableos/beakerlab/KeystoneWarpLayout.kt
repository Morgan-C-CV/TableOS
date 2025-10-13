package com.tableos.beakerlab

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import androidx.constraintlayout.widget.ConstraintLayout

class KeystoneWarpLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    private val TAG = "KeystoneWarpLayout"
    private var points: Array<Pair<Float, Float>>? = null // 归一化坐标
    private val warpMatrix = Matrix()
    private val path = Path()
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private var hasWarp = false
    private var warpEnabled = true

    fun setWarpEnabled(enabled: Boolean) {
        warpEnabled = enabled
        Log.d(TAG, "setWarpEnabled: enabled=${enabled}")
        invalidate()
    }

    fun isWarpEnabled(): Boolean = warpEnabled

    fun loadConfig() {
        // 读取 TableOS Provider；若为空，回退读取旧版系统设置键（桌面已使用）
        val csv = readFromProvider() ?: run {
            try {
                android.provider.Settings.System.getString(context.contentResolver, "tableos_keystone")
            } catch (_: Exception) { null }
        }
        Log.d(TAG, "loadConfig: csv=${csv?.take(100)}")
        points = parseCsv(csv)
        Log.d(TAG, "loadConfig: parsed points=${points}")
        hasWarp = false
        buildMatrix()
        Log.d(TAG, "loadConfig: size=${width}x${height}, hasWarp=${hasWarp}")
        invalidate()
    }

    private fun buildMatrix() {
        val pts = points ?: return
        if (width == 0 || height == 0) {
            hasWarp = false
            Log.w(TAG, "buildMatrix: skip, size=${width}x${height}")
            return
        }
        val src = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            width.toFloat(), height.toFloat(),
            0f, height.toFloat()
        )
        val p0 = toPx(pts[0]); val p1 = toPx(pts[1]); val p2 = toPx(pts[2]); val p3 = toPx(pts[3])
        if (!isPolygonValid(p0, p1, p2, p3)) {
            hasWarp = false
            Log.w(TAG, "buildMatrix: invalid polygon, points out-of-bounds or non-convex")
            return
        }
        val dst = floatArrayOf(
            p0.first, p0.second,
            p1.first, p1.second,
            p2.first, p2.second,
            p3.first, p3.second
        )
        warpMatrix.reset()
        warpMatrix.setPolyToPoly(src, 0, dst, 0, 4)
        Log.d(TAG, "buildMatrix: dst=[${p0}, ${p1}, ${p2}, ${p3}]")

        path.reset()
        path.moveTo(p0.first, p0.second)
        path.lineTo(p1.first, p1.second)
        path.lineTo(p2.first, p2.second)
        path.lineTo(p3.first, p3.second)
        path.close()

        val area = polygonArea(p0, p1, p2, p3)
        hasWarp = area > 1000f
        Log.d(TAG, "buildMatrix: area=${area}, hasWarp=${hasWarp}")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildMatrix()
        Log.d(TAG, "onSizeChanged: ${w}x${h} (old=${oldw}x${oldh}), hasWarp=${hasWarp}")
    }

    override fun dispatchDraw(canvas: Canvas) {
        val pts = points
        if (pts == null || !hasWarp || !warpEnabled) {
            if (pts == null) Log.w(TAG, "dispatchDraw: points=null, fallback normal draw")
            else if (!hasWarp) Log.d(TAG, "dispatchDraw: hasWarp=false, fallback normal draw")
            else Log.d(TAG, "dispatchDraw: warpEnabled=false, fallback normal draw")
            super.dispatchDraw(canvas)
            return
        }
        val saveContent = canvas.save()
        // 与 settings 模块保持一致：使用前向矩阵绘制
        canvas.concat(warpMatrix)
        Log.d(TAG, "dispatchDraw: applying warp draw, size=${width}x${height}")
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saveContent)

        val full = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        }
        val outside = Path()
        val opOk = outside.op(full, path, Path.Op.DIFFERENCE)
        if (opOk) {
            Log.d(TAG, "dispatchDraw: draw outside black border")
            canvas.drawPath(outside, blackPaint)
        } else {
            Log.w(TAG, "dispatchDraw: Path.op failed, fallback fill full black bg then re-draw content")
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blackPaint)
            val s2 = canvas.save()
            canvas.concat(warpMatrix)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(s2)
        }
    }

    private fun toPx(p: Pair<Float, Float>): Pair<Float, Float> {
        return Pair(p.first.coerceIn(0f, 1f) * width, p.second.coerceIn(0f, 1f) * height)
    }

    private fun parseCsv(csv: String?): Array<Pair<Float, Float>>? {
        if (csv.isNullOrBlank()) return null
        val parts = csv.trim().split(";")
        if (parts.size != 4) return null
        return try {
            Array(4) { i ->
                val seg = parts[i].trim()
                val (sx, sy) = seg.split(",")
                Pair(sx.trim().toFloat(), sy.trim().toFloat())
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseCsv error: ${e.message}")
            return null
        }
    }

    private fun readFromProvider(uriStr: String = "content://com.tableos.app.keystone/config"): String? {
        return try {
            val uri = Uri.parse(uriStr)
            context.contentResolver.query(uri, arrayOf("value"), null, null, null)?.use { c ->
                val v = if (c.moveToFirst()) c.getString(0) else null
                Log.d(TAG, "readFromProvider(${uriStr}): length=${v?.length ?: 0}")
                v
            }
        } catch (e: Exception) {
            Log.e(TAG, "readFromProvider(${uriStr}) error: ${e.message}")
            null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            Log.d(TAG, "onAttachedToWindow: trigger loadConfig")
            loadConfig()
        } catch (_: Throwable) {}
    }

    private fun polygonArea(p0: Pair<Float, Float>, p1: Pair<Float, Float>, p2: Pair<Float, Float>, p3: Pair<Float, Float>): Float {
        fun cross(ax: Float, ay: Float, bx: Float, by: Float) = ax * by - ay * bx
        val s = cross(p0.first, p0.second, p1.first, p1.second) +
                cross(p1.first, p1.second, p2.first, p2.second) +
                cross(p2.first, p2.second, p3.first, p3.second) +
                cross(p3.first, p3.second, p0.first, p0.second)
        return kotlin.math.abs(s) / 2f
    }

    private fun isPolygonValid(p0: Pair<Float, Float>, p1: Pair<Float, Float>, p2: Pair<Float, Float>, p3: Pair<Float, Float>): Boolean {
        fun inBounds(p: Pair<Float, Float>): Boolean =
            p.first in 0f..width.toFloat() && p.second in 0f..height.toFloat()

        val boundsOk = inBounds(p0) && inBounds(p1) && inBounds(p2) && inBounds(p3)
        if (!boundsOk) {
            Log.w(TAG, "isPolygonValid: point out-of-bounds: p0=${p0}, p1=${p1}, p2=${p2}, p3=${p3}")
            return false
        }

        fun cross(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>): Float {
            val abx = b.first - a.first; val aby = b.second - a.second
            val bcx = c.first - b.first; val bcy = c.second - b.second
            return abx * bcy - aby * bcx
        }
        val c0 = cross(p0, p1, p2)
        val c1 = cross(p1, p2, p3)
        val c2 = cross(p2, p3, p0)
        val c3 = cross(p3, p0, p1)
        val hasPos = c0 > 0 || c1 > 0 || c2 > 0 || c3 > 0
        val hasNeg = c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0
        val convex = !(hasPos && hasNeg)
        if (!convex) Log.w(TAG, "isPolygonValid: non-convex polygon: c=[${c0}, ${c1}, ${c2}, ${c3}]")
        return convex
    }
}