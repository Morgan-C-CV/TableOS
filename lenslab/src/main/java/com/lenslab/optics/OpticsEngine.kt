package com.lenslab.optics

import android.graphics.Path
import android.graphics.RectF

class OpticsEngine {
    data class TraceResult(val points: List<Vector2>)

    fun trace(
        ray: Ray,
        mirrors: List<PlaneMirror>,
        prisms: List<TriangularPrism>,
        convexLenses: List<ConvexLens>,
        concaveLenses: List<ConcaveLens>,
        bounds: RectF,
        maxBounces: Int = 12
    ): TraceResult {
        val points = mutableListOf<Vector2>()
        points.add(ray.origin)

        var currentRay = ray
        var bounces = 0
        var currentMedium = 1.0f // air
        var insidePrism: TriangularPrism? = null
        var insideLens: Any? = null // ConvexLens 或 ConcaveLens
        while (bounces <= maxBounces) {
            var minT = Float.MAX_VALUE
            var hitPoint: Vector2? = null
            var hitMirror: PlaneMirror? = null
            var hitPrismBoundary: Pair<TriangularPrism, Segment>? = null
            var hitLensSurface: Triple<Any, Vector2, Float>? = null // (lens, circleCenter, radius)

            for (m in mirrors) {
                val seg = m.segment()
                val hit = OpticsMath.intersectRaySegment(currentRay, seg)
                if (hit != null) {
                    val (t, point) = hit
                    if (t < minT) {
                        minT = t
                        hitPoint = point
                        hitMirror = m
                    }
                }
            }

            // Check intersections with prism boundaries
            for (p in prisms) {
                for (seg in p.segments()) {
                    val hit = OpticsMath.intersectRaySegment(currentRay, seg)
                    if (hit != null) {
                        val (t, point) = hit
                        if (t < minT) {
                            minT = t
                            hitPoint = point
                            hitMirror = null
                            hitPrismBoundary = p to seg
                        }
                    }
                }
            }

            // Check intersections with convex lens surfaces (restrict to valid arc near lens thickness)
            for (lens in convexLenses) {
                val u = Vector2.fromAngle(lens.angleRad).normalized()
                val v = u.perp().normalized()
                val halfAperture = lens.aperture / 2f
                for ((c, r) in lens.surfaces()) {
                    val hit = OpticsMath.intersectRayCircle(currentRay, c, r)
                    if (hit != null) {
                        val (t, point) = hit
                        // 1) 限制在有效孔径范围（沿 v）
                        val offsetCenter = point - lens.center
                        val projV = offsetCenter.dot(v)
                        if (kotlin.math.abs(projV) > halfAperture) continue

                        // 2) 限制在正确的圆弧侧（沿 u），避免命中圆的远侧导致“透镜外折射”
                        val centerDiffU = (lens.center - c).dot(u) // >0 => left surface center, <0 => right surface center
                        val offsetU = (point - c).dot(u)
                        val isValidArc = if (centerDiffU > 0f) {
                            // left surface of convex lens should be near +u side of its circle
                            offsetU >= 0f
                        } else {
                            // right surface of convex lens should be near -u side of its circle
                            offsetU <= 0f
                        }
                        if (!isValidArc) continue

                        if (t < minT) {
                            minT = t
                            hitPoint = point
                            hitMirror = null
                            hitPrismBoundary = null
                            hitLensSurface = Triple(lens as Any, c, r)
                        }
                    }
                }
            }

            // Check intersections with concave lens surfaces (restrict to valid arc near lens thickness)
            for (lens in concaveLenses) {
                val u = Vector2.fromAngle(lens.angleRad).normalized()
                val v = u.perp().normalized()
                val halfAperture = lens.aperture / 2f
                for ((c, r) in lens.surfaces()) {
                    val hit = OpticsMath.intersectRayCircle(currentRay, c, r)
                    if (hit != null) {
                        val (t, point) = hit
                        // 1) 孔径限制（沿 v）
                        val offsetCenter = point - lens.center
                        val projV = offsetCenter.dot(v)
                        if (kotlin.math.abs(projV) > halfAperture) continue

                        // 2) 正确的圆弧侧（沿 u）：凹透镜左面为 -u，右面为 +u
                        val centerDiffU = (lens.center - c).dot(u) // <0 => left surface center, >0 => right surface center（凹）
                        val offsetU = (point - c).dot(u)
                        val isValidArc = if (centerDiffU > 0f) {
                            // right surface of concave lens should be near +u side of its circle
                            offsetU >= 0f
                        } else {
                            // left surface of concave lens should be near -u side of its circle
                            offsetU <= 0f
                        }
                        if (!isValidArc) continue

                        if (t < minT) {
                            minT = t
                            hitPoint = point
                            hitMirror = null
                            hitPrismBoundary = null
                            hitLensSurface = Triple(lens as Any, c, r)
                        }
                    }
                }
            }

            if (hitPoint != null && hitMirror != null) {
                points.add(hitPoint)
                val newDir = OpticsMath.reflect(currentRay.dir, hitMirror.segment())
                currentRay = Ray(hitPoint, newDir)
                bounces++
                continue
            }

            if (hitPoint != null && hitPrismBoundary != null) {
                val (p, seg) = hitPrismBoundary
                points.add(hitPoint)
                val normal = seg.direction().perp().normalized()
                val entering = (insidePrism == null)
                val n1 = if (entering) currentMedium else p.refractiveIndex
                val n2 = if (entering) p.refractiveIndex else 1.0f
                val refracted = OpticsMath.refract(currentRay.dir, normal, n1, n2)
                if (refracted != null) {
                    currentRay = Ray(hitPoint, refracted)
                    currentMedium = n2
                    insidePrism = if (entering) p else null
                } else {
                    // Total internal reflection: reflect
                    val newDir = OpticsMath.reflect(currentRay.dir, seg)
                    currentRay = Ray(hitPoint, newDir)
                }
                bounces++
                continue
            }

            if (hitPoint != null && hitLensSurface != null) {
                val (lensAny, circleCenter, _) = hitLensSurface
                points.add(hitPoint)
                val normal = (hitPoint - circleCenter).normalized()
                val entering = (insideLens == null)
                val nLens = when (lensAny) {
                    is ConvexLens -> lensAny.refractiveIndex
                    is ConcaveLens -> lensAny.refractiveIndex
                    else -> 1.5f
                }
                val n1 = if (entering) currentMedium else nLens
                val n2 = if (entering) nLens else 1.0f
                val refracted = OpticsMath.refract(currentRay.dir, normal, n1, n2)
                if (refracted != null) {
                    currentRay = Ray(hitPoint, refracted)
                    currentMedium = n2
                    insideLens = if (entering) lensAny else null
                } else {
                    // 全反射：使用镜面反射近似
                    // 将法线方向映射为线段法线替代：直接用圆面法线反射
                    val rdir = currentRay.dir
                    val n = normal
                    val dot = rdir.dot(n)
                    val newDir = (rdir - n * (2f * dot)).normalized()
                    currentRay = Ray(hitPoint, newDir)
                }
                bounces++
                continue
            }

            // No mirror hit: intersect with canvas bounds to terminate
            val boundsSegs = listOf(
                Segment(Vector2(bounds.left, bounds.top), Vector2(bounds.right, bounds.top)),
                Segment(Vector2(bounds.right, bounds.top), Vector2(bounds.right, bounds.bottom)),
                Segment(Vector2(bounds.right, bounds.bottom), Vector2(bounds.left, bounds.bottom)),
                Segment(Vector2(bounds.left, bounds.bottom), Vector2(bounds.left, bounds.top))
            )
            var minTBounds = Float.MAX_VALUE
            var endPoint: Vector2? = null
            for (seg in boundsSegs) {
                val hit = OpticsMath.intersectRaySegment(currentRay, seg)
                if (hit != null) {
                    val (t, point) = hit
                    if (t < minTBounds) {
                        minTBounds = t
                        endPoint = point
                    }
                }
            }
            points.add(endPoint ?: (currentRay.origin + currentRay.dir * 1000f))
            break
        }

        return TraceResult(points)
    }
}