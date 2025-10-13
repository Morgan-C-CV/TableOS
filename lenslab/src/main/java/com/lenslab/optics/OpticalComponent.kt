package com.lenslab.optics

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

interface OpticalComponent {
    fun draw(canvas: Canvas, paint: Paint)
    fun bounds(): RectF
}

data class Segment(val a: Vector2, val b: Vector2) {
    fun direction(): Vector2 = (b - a).normalized()
}

data class Ray(val origin: Vector2, val dir: Vector2)

object OpticsMath {
    fun intersectRaySegment(ray: Ray, seg: Segment): Pair<Float, Vector2>? {
        val p = ray.origin
        val r = ray.dir
        val q = seg.a
        val s = seg.b - seg.a

        val rxs = r.x * s.y - r.y * s.x
        if (rxs == 0f) return null

        val qmp = q - p
        val t = (qmp.x * s.y - qmp.y * s.x) / rxs
        val u = (qmp.x * r.y - qmp.y * r.x) / rxs
        if (t > 1e-3f && u in 0f..1f) {
            val point = p + r * t
            return t to point
        }
        return null
    }

    fun reflect(dir: Vector2, seg: Segment): Vector2 {
        var n = seg.direction().perp().normalized() // normal vector
        // Ensure normal points against incident direction
        if (dir.dot(n) > 0f) n = Vector2(-n.x, -n.y)
        val dot = dir.dot(n)
        return (dir - n * (2f * dot)).normalized()
    }

    // Snell's law refraction. Returns null if total internal reflection occurs.
    fun refract(dir: Vector2, normalIn: Vector2, n1: Float, n2: Float): Vector2? {
        var n = normalIn.normalized()
        val i = dir.normalized()
        if (i.dot(n) > 0f) n = Vector2(-n.x, -n.y)
        val eta = n1 / n2
        val cosi = -i.dot(n)
        val k = 1f - eta * eta * (1f - cosi * cosi)
        if (k < 0f) {
            return null // Total internal reflection
        }
        val t = i * eta + n * (eta * cosi - kotlin.math.sqrt(k))
        return t.normalized()
    }

    // Intersect a ray with a circle (center c, radius r). Returns (t, point) for nearest t>0.
    fun intersectRayCircle(ray: Ray, c: Vector2, r: Float): Pair<Float, Vector2>? {
        val p = ray.origin
        val d = ray.dir
        val m = p - c
        val b = m.dot(d)
        val cval = m.dot(m) - r * r
        // If ray origin outside circle (cval>0) and ray pointing away (b>0) -> no hit
        if (cval > 0f && b > 0f) return null
        val discr = b * b - cval
        if (discr < 0f) return null
        var t = -b - kotlin.math.sqrt(discr)
        if (t < 1e-3f) {
            t = -b + kotlin.math.sqrt(discr)
        }
        if (t < 1e-3f) return null
        val point = p + d * t
        return t to point
    }

    fun buildPath(points: List<Vector2>): Path {
        val path = Path()
        if (points.isNotEmpty()) {
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
        }
        return path
    }
}