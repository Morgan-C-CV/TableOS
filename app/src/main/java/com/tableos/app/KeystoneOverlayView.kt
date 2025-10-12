package com.tableos.app

import android.content.Context
import android.graphics.*
import android.provider.Settings
import android.net.Uri
import android.util.AttributeSet
import android.view.View

class KeystoneOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: Array<Pair<Float, Float>>? = null // 归一化坐标
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK // 不透明黑色遮罩
        style = Paint.Style.FILL
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadConfig()
    }

    fun loadConfig() {
        val csv = readFromProvider() ?: run {
            try {
                Settings.System.getString(context.contentResolver, "tableos_keystone")
            } catch (_: Exception) { null }
        }
        points = parseCsv(csv)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pts = points ?: return
        // 覆盖整屏幕的遮罩
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        // 清除多边形区域以透出内容（形成变形显示区域）
        val path = Path().apply {
            val p0 = toPx(pts[0]); val p1 = toPx(pts[1]); val p2 = toPx(pts[2]); val p3 = toPx(pts[3])
            moveTo(p0.first, p0.second)
            lineTo(p1.first, p1.second)
            lineTo(p2.first, p2.second)
            lineTo(p3.first, p3.second)
            close()
        }
        canvas.drawPath(path, clearPaint)
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