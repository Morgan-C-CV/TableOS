package com.lenslab.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.lenslab.optics.*
import com.lenslab.fit.LensOverlayParser
import com.lenslab.fit.FitResult
import com.lenslab.fit.MirrorOverlayParser
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

class OpticsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { NONE, ADD_EMITTER, ADD_PLANE_MIRROR, ADD_TRIANGULAR_PRISM, ADD_CONVEX_LENS, ADD_CONCAVE_LENS }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val engine = OpticsEngine()

    private val emitters = mutableListOf<LaserEmitter>()
    private val mirrors = mutableListOf<PlaneMirror>()
    private val prisms = mutableListOf<TriangularPrism>()
    private val convexLenses = mutableListOf<ConvexLens>()
    private val concaveLenses = mutableListOf<ConcaveLens>()
    private val customLenses = mutableListOf<CustomLens>()

    private var mode: Mode = Mode.NONE
    private var selected: OpticalComponent? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val TAG = "OpticsView"
    private val HANDLE_HIT_RADIUS = 24f
    private val MIRROR_HIT_TOLERANCE = 16f
    private val FOCUS_HIT_RADIUS = 22f
    private var showHelpers: Boolean = false
    private val lastTracePoints = mutableListOf<List<Vector2>>()
    private var draggingFocusLens: Any? = null 

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            Log.d(TAG, "onSingleTapConfirmed: x=${e.x}, y=${e.y}")
            val tapped = pickComponent(e.x, e.y)
            tapped?.let {
                when (it) {
                    is LaserEmitter -> {
                        Log.d(TAG, "Hit LaserEmitter at ${it.position}, rotate +step")
                        it.rotate(0.08667f)
                    }
                    is PlaneMirror -> {
                        val handle = mirrorHandle(it)
                        val dx = e.x - handle.x
                        val dy = e.y - handle.y
                        val dist = kotlin.math.sqrt((dx*dx + dy*dy).toDouble()).toFloat()
                        Log.d(TAG, "Hit PlaneMirror center=${it.center}, angleRad=${it.angleRad}, handle=${handle}, tapDistToHandle=${dist}, rotate +step")
                        it.rotate(0.08667f)
                    }
                    is TriangularPrism -> {
                        Log.d(TAG, "Hit TriangularPrism center=${it.center}, angleRad=${it.angleRad}, rotate +step")
                        it.rotate(0.08667f)
                    }
                    is ConvexLens -> {
                        Log.d(TAG, "Hit ConvexLens center=${it.center}, angleRad=${it.angleRad}, rotate +step")
                        it.rotate(0.08667f)
                    }
                    is ConcaveLens -> {
                        Log.d(TAG, "Hit ConcaveLens center=${it.center}, angleRad=${it.angleRad}, rotate +step")
                        it.rotate(0.08667f)
                    }
                }
                invalidate()
                return true
            }
            var minDist = Float.MAX_VALUE
            var nearestCenter: Vector2? = null
            for (m in mirrors) {
                val h = mirrorHandle(m)
                val dx = e.x - h.x
                val dy = e.y - h.y
                val d = kotlin.math.sqrt((dx*dx + dy*dy).toDouble()).toFloat()
                if (d < minDist) { minDist = d; nearestCenter = m.center }
            }
            Log.d(TAG, "No component hit. Nearest handleDist=${minDist}, nearestMirrorCenter=${nearestCenter}")
            return false
        }
    })

    fun setMode(newMode: Mode) {
        mode = newMode
    }
    fun setShowHelpers(enabled: Boolean) {
        showHelpers = enabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFFFFFFFF.toInt())

        // Draw components
        for (m in mirrors) m.draw(canvas, paint)
        for (p in prisms) p.draw(canvas, paint)
        for (l in convexLenses) l.draw(canvas, paint)
        for (l in concaveLenses) l.draw(canvas, paint)
        for (c in customLenses) c.draw(canvas, paint)
        for (e in emitters) e.draw(canvas, paint)

        // Draw rays
        paint.style = Paint.Style.STROKE
        paint.color = 0xFFFF0000.toInt()
        paint.strokeWidth = 3f
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        lastTracePoints.clear()
        for (e in emitters) {
            val res = engine.trace(e.emitRay(), mirrors, prisms, convexLenses, concaveLenses, bounds)
            lastTracePoints.add(res.points)
            val path = OpticsMath.buildPath(res.points)
            canvas.drawPath(path, paint)
        }

        if (showHelpers) {
            drawHelpers(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                Log.d(TAG, "ACTION_DOWN: x=$x, y=$y, mode=$mode")
                // 在辅助线开启时，优先检测是否命中某个透镜焦点
                if (showHelpers) {
                    // 检测凸透镜焦点
                    for (l in convexLenses) {
                        val u = Vector2.fromAngle(l.angleRad).normalized()
                        val fMag = l.curvatureRadius / (2f * (l.refractiveIndex - 1f))
                        val f1 = Vector2(l.center.x + u.x * fMag, l.center.y + u.y * fMag)
                        val f2 = Vector2(l.center.x - u.x * fMag, l.center.y - u.y * fMag)
                        val d1 = kotlin.math.sqrt(((x - f1.x)*(x - f1.x) + (y - f1.y)*(y - f1.y)).toDouble()).toFloat()
                        val d2 = kotlin.math.sqrt(((x - f2.x)*(x - f2.x) + (y - f2.y)*(y - f2.y)).toDouble()).toFloat()
                        if (d1 <= FOCUS_HIT_RADIUS || d2 <= FOCUS_HIT_RADIUS) {
                            draggingFocusLens = l
                            invalidate()
                            return true
                        }
                    }
                    // 检测凹透镜焦点（使用同样的正值焦距距离）
                    for (l in concaveLenses) {
                        val u = Vector2.fromAngle(l.angleRad).normalized()
                        val fMag = l.curvatureRadius / (2f * (l.refractiveIndex - 1f))
                        val f1 = Vector2(l.center.x + u.x * fMag, l.center.y + u.y * fMag)
                        val f2 = Vector2(l.center.x - u.x * fMag, l.center.y - u.y * fMag)
                        val d1 = kotlin.math.sqrt(((x - f1.x)*(x - f1.x) + (y - f1.y)*(y - f1.y)).toDouble()).toFloat()
                        val d2 = kotlin.math.sqrt(((x - f2.x)*(x - f2.x) + (y - f2.y)*(y - f2.y)).toDouble()).toFloat()
                        if (d1 <= FOCUS_HIT_RADIUS || d2 <= FOCUS_HIT_RADIUS) {
                            draggingFocusLens = l
                            invalidate()
                            return true
                        }
                    }
                }
                if (mode == Mode.ADD_EMITTER) {
                    emitters.add(LaserEmitter(Vector2(x, y), 0f))
                    mode = Mode.NONE
                    invalidate()
                    return true
                } else if (mode == Mode.ADD_PLANE_MIRROR) {
                    mirrors.add(PlaneMirror(Vector2(x, y), 200f, 0f))
                    mode = Mode.NONE
                    invalidate()
                    return true
                } else if (mode == Mode.ADD_TRIANGULAR_PRISM) {
                    prisms.add(TriangularPrism(Vector2(x, y), sideLength = 160f, angleRad = 0f, refractiveIndex = 1.5f))
                    mode = Mode.NONE
                    invalidate()
                    return true
                } else if (mode == Mode.ADD_CONVEX_LENS) {
                    convexLenses.add(ConvexLens(Vector2(x, y), aperture = 200f, thickness = 70f, curvatureRadius = 220f, angleRad = 0f, refractiveIndex = 1.5f))
                    mode = Mode.NONE
                    invalidate()
                    return true
                } else if (mode == Mode.ADD_CONCAVE_LENS) {
                    concaveLenses.add(ConcaveLens(Vector2(x, y), aperture = 200f, thickness = 70f, curvatureRadius = 220f, angleRad = 0f, refractiveIndex = 1.5f))
                    mode = Mode.NONE
                    invalidate()
                    return true
                }

                selected = pickComponent(x, y)
                Log.d(TAG, "Selected component=${selected?.javaClass?.simpleName}")
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                draggingFocusLens?.let { lens ->
                    when (lens) {
                        is ConvexLens -> {
                            val u = Vector2.fromAngle(lens.angleRad).normalized()
                            val proj = kotlin.math.abs((Vector2(x, y) - lens.center).dot(u))
                            val halfA = lens.aperture / 2f
                            val minR = kotlin.math.max(halfA + lens.thickness * 0.5f, halfA + 9f)
                            val newR = kotlin.math.max(minR, proj * 2f * (lens.refractiveIndex - 1f))
                            lens.curvatureRadius = newR
                        }
                        is ConcaveLens -> {
                            val u = Vector2.fromAngle(lens.angleRad).normalized()
                            val proj = kotlin.math.abs((Vector2(x, y) - lens.center).dot(u))
                            val halfA = lens.aperture / 2f
                            val minR = kotlin.math.max(halfA + lens.thickness * 0.9f, halfA + 9f)
                            val newR = kotlin.math.max(minR, proj * 2f * (lens.refractiveIndex - 1f))
                            lens.curvatureRadius = newR
                        }
                    }
                    lastTouchX = x
                    lastTouchY = y
                    invalidate()
                    return true
                }
                selected?.let {
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY
                    when (it) {
                        is LaserEmitter -> it.position = Vector2(it.position.x + dx, it.position.y + dy)
                        is PlaneMirror -> it.center = Vector2(it.center.x + dx, it.center.y + dy)
                        is TriangularPrism -> it.center = Vector2(it.center.x + dx, it.center.y + dy)
                        is ConvexLens -> it.center = Vector2(it.center.x + dx, it.center.y + dy)
                        is ConcaveLens -> it.center = Vector2(it.center.x + dx, it.center.y + dy)
                    }
                    lastTouchX = x
                    lastTouchY = y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selected = null
                draggingFocusLens = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun addCustomLens(custom: CustomLens) {
        customLenses.add(custom)
        invalidate()
    }

    fun importFittedLensOverlayFromAsset(assetPath: String, scaleToView: Boolean = true, drawOverlay: Boolean = true): Boolean {
        return try {
            context.assets.open(assetPath).use { inp ->
                val bmp = BitmapFactory.decodeStream(inp)
                val path = LensOverlayParser.extractBlueContourPath(bmp, downsample = 2)
                val finalPath = Path(path)
                var s = 1f
                if (scaleToView && bmp.width > 0 && bmp.height > 0 && width > 0 && height > 0) {
                    val sx = width.toFloat() / bmp.width.toFloat()
                    val sy = height.toFloat() / bmp.height.toFloat()
                    s = kotlin.math.min(sx, sy) // 使用等比缩放，避免形变导致与物理参数不一致
                    val m = Matrix()
                    m.setScale(s, s)
                    finalPath.transform(m)
                }
                // 可选：绘制蓝色轮廓可视叠加
                if (drawOverlay) {
                    addCustomLens(CustomLens(finalPath))
                }
                // Try to estimate fitted params directly from PNG and add a physical lens
                val est = LensOverlayParser.estimateFitFromBitmap(bmp, downsample = 1)
                if (est != null) {
                    val (res, centerBmp) = est
                    val cx = centerBmp.x * s
                    val cy = centerBmp.y * s
                    val resScaled = FitResult(
                        type = res.type,
                        angleRad = res.angleRad,
                        aperture = res.aperture * s,
                        thickness = res.thickness * s,
                        curvatureRadius = res.curvatureRadius * s,
                    )
                    addFittedLensFromParams(resScaled, center = Vector2(cx, cy))
                } else {
                    // Fallback: try parsing as a plane mirror
                    val mir = MirrorOverlayParser.estimatePlaneMirrorFromBitmap(bmp, downsample = 2)
                    if (mir != null) {
                        val cx = mir.center.x * s
                        val cy = mir.center.y * s
                        mirrors.add(PlaneMirror(Vector2(cx, cy), length = mir.length * s, angleRad = mir.angleRad))
                        // Optional decoration: overlay main line segment in grey
                        mir.decorPath?.let { p ->
                            val m = Matrix(); m.setScale(s, s)
                            val scaled = Path(p); scaled.transform(m)
                            addCustomLens(CustomLens(scaled, strokeWidth = 2.5f, color = 0xFF9E9E9E.toInt()))
                        }
                        invalidate()
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import fitted lens overlay from asset '$assetPath'", e)
            false
        }
    }

    fun importPlaneMirrorFromAsset(assetPath: String, scaleToView: Boolean = true): Boolean {
        return try {
            context.assets.open(assetPath).use { inp ->
                val bmp = BitmapFactory.decodeStream(inp)
                var s = 1f
                if (scaleToView && bmp.width > 0 && bmp.height > 0 && width > 0 && height > 0) {
                    val sx = width.toFloat() / bmp.width.toFloat()
                    val sy = height.toFloat() / bmp.height.toFloat()
                    s = kotlin.math.min(sx, sy)
                }
                val mir = MirrorOverlayParser.estimatePlaneMirrorFromBitmap(bmp, downsample = 2)
                if (mir != null) {
                    val cx = mir.center.x * s
                    val cy = mir.center.y * s
                    mirrors.add(PlaneMirror(Vector2(cx, cy), length = mir.length * s, angleRad = mir.angleRad))
                    mir.decorPath?.let { p ->
                        val m = Matrix(); m.setScale(s, s)
                        val scaled = Path(p); scaled.transform(m)
                        addCustomLens(CustomLens(scaled, strokeWidth = 2.5f, color = 0xFF9E9E9E.toInt()))
                    }
                    invalidate()
                    return true
                } else {
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import plane mirror from asset '$assetPath'", e)
            false
        }
    }

    fun addFittedLensFromParams(res: FitResult, center: Vector2 = Vector2(width * 0.5f, height * 0.5f), refractiveIndex: Float = 1.5f) {
        val angle = res.angleRad
        val lens = when (res.type) {
            "convex" -> ConvexLens(center, aperture = res.aperture, thickness = res.thickness, curvatureRadius = res.curvatureRadius, angleRad = angle, refractiveIndex = refractiveIndex)
            "concave" -> ConcaveLens(center, aperture = res.aperture, thickness = res.thickness, curvatureRadius = res.curvatureRadius, angleRad = angle, refractiveIndex = refractiveIndex)
            else -> ConvexLens(center, aperture = res.aperture, thickness = res.thickness, curvatureRadius = res.curvatureRadius, angleRad = angle, refractiveIndex = refractiveIndex)
        }
        if (lens is ConvexLens) {
            convexLenses.add(lens)
        } else if (lens is ConcaveLens) {
            concaveLenses.add(lens)
        }
        invalidate()
    }

    fun importFittedLensParamsFromAsset(jsonAssetPath: String): Boolean {
        return try {
            context.assets.open(jsonAssetPath).use { inp ->
                val txt = inp.reader().readText()
                val obj = JSONObject(txt)
                val res = FitResult(
                    type = obj.optString("type", "unknown"),
                    angleRad = obj.optDouble("angleRad", 0.0).toFloat(),
                    aperture = obj.optDouble("aperture", 160.0).toFloat(),
                    thickness = obj.optDouble("thickness", 60.0).toFloat(),
                    curvatureRadius = obj.optDouble("curvatureRadius", 200.0).toFloat(),
                )
                val cx = width * 0.5f
                val cy = height * 0.5f
                addFittedLensFromParams(res, center = Vector2(cx, cy))
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import fitted lens params from asset '$jsonAssetPath'", e)
            false
        }
    }

    private fun pickComponent(x: Float, y: Float): OpticalComponent? {
        val pt = Vector2(x, y)
        // 1) 优先命中平面镜的旋转手柄
        var handleHit: PlaneMirror? = null
        var minHandleDist = Float.MAX_VALUE
        for (m in mirrors) {
            val h = mirrorHandle(m)
            val dx = x - h.x
            val dy = y - h.y
            val d = kotlin.math.sqrt((dx*dx + dy*dy).toDouble()).toFloat()
            if (d < minHandleDist) { minHandleDist = d; handleHit = m }
        }
        if (minHandleDist <= HANDLE_HIT_RADIUS && handleHit != null) {
            Log.d(TAG, "pickComponent: handle HIT (dist=$minHandleDist) => PlaneMirror")
            return handleHit
        }

        // 2) 命中镜面线段（带容差）
        var mirrorHit: PlaneMirror? = null
        var minMirrorDist = Float.MAX_VALUE
        for (m in mirrors) {
            val seg = m.segment()
            val d = distancePointToSegment(pt, seg)
            if (d < minMirrorDist) { minMirrorDist = d; mirrorHit = m }
        }
        if (minMirrorDist <= MIRROR_HIT_TOLERANCE && mirrorHit != null) {
            Log.d(TAG, "pickComponent: segment HIT (dist=$minMirrorDist) => PlaneMirror")
            return mirrorHit
        }

        // 3) 退回到边界矩形命中（主要用于发射器）
        val hitTest: (RectF) -> Boolean = { r -> r.contains(x, y) }
        var closest: OpticalComponent? = null
        var minDistSq = Float.MAX_VALUE
        for (c in mirrors as List<OpticalComponent> + prisms as List<OpticalComponent> + convexLenses as List<OpticalComponent> + concaveLenses as List<OpticalComponent> + emitters as List<OpticalComponent>) {
            val b = c.bounds()
            if (hitTest(b)) {
                val cx = (b.left + b.right) / 2f
                val cy = (b.top + b.bottom) / 2f
                val d = ((cx - x) * (cx - x) + (cy - y) * (cy - y))
                if (d < minDistSq) {
                    minDistSq = d
                    closest = c
                }
            }
        }
        Log.d(TAG, "pickComponent at ($x,$y) => ${closest?.javaClass?.simpleName} (minCenterDistSq=$minDistSq), minHandleDist=$minHandleDist, minMirrorDist=$minMirrorDist")
        return closest
    }

    private fun mirrorHandle(m: PlaneMirror): Vector2 {
        val dir = Vector2.fromAngle(m.angleRad).normalized()
        val n = dir.perp().normalized()
        return Vector2(m.center.x + n.x * 18f, m.center.y + n.y * 18f)
    }

    private fun distancePointToSegment(p: Vector2, seg: Segment): Float {
        val a = seg.a
        val b = seg.b
        val ab = b - a
        val ap = p - a
        val abLenSq = ab.x * ab.x + ab.y * ab.y
        if (abLenSq == 0f) return kotlin.math.sqrt((ap.x * ap.x + ap.y * ap.y).toDouble()).toFloat()
        var t = (ap.x * ab.x + ap.y * ab.y) / abLenSq
        if (t < 0f) t = 0f
        if (t > 1f) t = 1f
        val closest = Vector2(a.x + ab.x * t, a.y + ab.y * t)
        val dx = p.x - closest.x
        val dy = p.y - closest.y
        return kotlin.math.sqrt((dx*dx + dy*dy).toDouble()).toFloat()
    }

    private fun drawHelpers(canvas: Canvas) {
        // 使用灰色虚线与细线宽
        paint.style = Paint.Style.STROKE
        paint.color = 0xFF9E9E9E.toInt() // Grey 500
        paint.strokeWidth = 2.5f

        // 平面镜法线：在被光线照射到的位置绘制法线
        // 使用虚线效果绘制法线
        val dashEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        paint.pathEffect = dashEffect
        for (m in mirrors) {
            val seg = m.segment()
            var bestPt: Vector2? = null
            var bestDist = Float.MAX_VALUE
            var bestPrev: Vector2? = null
            for (pts in lastTracePoints) {
                for (idx in 0 until pts.size) {
                    val pt = pts[idx]
                    val d = distancePointToSegment(pt, seg)
                    if (d < MIRROR_HIT_TOLERANCE && d < bestDist) {
                        bestDist = d
                        bestPt = pt
                        bestPrev = if (idx > 0) pts[idx - 1] else null
                    }
                }
            }
            if (bestPt != null) {
                val dir = Vector2.fromAngle(m.angleRad).normalized()
                val n = dir.perp().normalized()
                val L = 60f
                val plus = Vector2(bestPt.x + n.x * L, bestPt.y + n.y * L)
                val minus = Vector2(bestPt.x - n.x * L, bestPt.y - n.y * L)
                val path = Path()
                val end = if (bestPrev != null) {
                    val dPlus = (bestPrev.x - plus.x) * (bestPrev.x - plus.x) + (bestPrev.y - plus.y) * (bestPrev.y - plus.y)
                    val dMinus = (bestPrev.x - minus.x) * (bestPrev.x - minus.x) + (bestPrev.y - minus.y) * (bestPrev.y - minus.y)
                    if (dPlus < dMinus) plus else minus
                } else {
                    plus
                }
                path.moveTo(bestPt.x, bestPt.y)
                path.lineTo(end.x, end.y)
                canvas.drawPath(path, paint)
            }
        }

        // 三棱镜法线：在光线照射到边的位置绘制外法线
        for (p in prisms) {
            val segs = p.segments()
            val L = 48f
            for (s in segs) {
                var hitPt: Vector2? = null
                var bestDist = Float.MAX_VALUE
                for (pts in lastTracePoints) {
                    for (pt in pts) {
                        val d = distancePointToSegment(pt, s)
                        if (d < MIRROR_HIT_TOLERANCE && d < bestDist) {
                            bestDist = d
                            hitPt = pt
                        }
                    }
                }
                if (hitPt != null) {
                    var n = (s.b - s.a).perp().normalized()
                    val toCenter = p.center - hitPt
                    if ((n.x * toCenter.x + n.y * toCenter.y) > 0f) {
                        // 指向中心则翻转，确保向外
                        n = Vector2(-n.x, -n.y)
                    }
                    // 贯穿边缘：以命中点为中心，沿法线两侧各延伸 L
                    val a = Vector2(hitPt.x - n.x * L, hitPt.y - n.y * L)
                    val b = Vector2(hitPt.x + n.x * L, hitPt.y + n.y * L)
                    val path = Path()
                    path.moveTo(a.x, a.y)
                    path.lineTo(b.x, b.y)
                    canvas.drawPath(path, paint)
                }
            }
        }

        // 透镜焦点与主光轴（两焦点连线以灰色虚线表现）
        // 先绘制主光轴为虚线，再绘制焦点为实心圆点
        fun drawLensFoci(center: Vector2, angleRad: Float, curvatureRadius: Float, n: Float, isConvex: Boolean) {
            val u = Vector2.fromAngle(angleRad).normalized()
            val f = if (isConvex) {
                curvatureRadius / (2f * (n - 1f))
            } else {
                -curvatureRadius / (2f * (n - 1f))
            }
            val f1 = Vector2(center.x + u.x * f, center.y + u.y * f)
            val f2 = Vector2(center.x - u.x * f, center.y - u.y * f)
            // 主光轴：两焦点连线（灰色虚线）
            paint.pathEffect = dashEffect
            val axisPath = Path()
            axisPath.moveTo(f1.x, f1.y)
            axisPath.lineTo(f2.x, f2.y)
            canvas.drawPath(axisPath, paint)
            // 焦点圆点：恢复为实线
            paint.pathEffect = null
            val r = 6f
            canvas.drawCircle(f1.x, f1.y, r, paint)
            canvas.drawCircle(f2.x, f2.y, r, paint)
        }

        var i = 0
        while (i < convexLenses.size) {
            val l = convexLenses[i]
            drawLensFoci(l.center, l.angleRad, l.curvatureRadius, l.refractiveIndex, true)
            i++
        }
        var j = 0
        while (j < concaveLenses.size) {
            val l = concaveLenses[j]
            drawLensFoci(l.center, l.angleRad, l.curvatureRadius, l.refractiveIndex, false)
            j++
        }
    }
}