package com.lenslab.optics

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class Vector2(val x: Float, val y: Float) {
    fun length(): Float = hypot(x.toDouble(), y.toDouble()).toFloat()
    fun normalized(): Vector2 {
        val len = length()
        return if (len == 0f) Vector2(0f, 0f) else Vector2(x / len, y / len)
    }
    operator fun plus(o: Vector2) = Vector2(x + o.x, y + o.y)
    operator fun minus(o: Vector2) = Vector2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vector2(x * s, y * s)
    fun dot(o: Vector2): Float = x * o.x + y * o.y
    fun perp(): Vector2 = Vector2(-y, x)
    fun angle(): Float = atan2(y, x)
    companion object {
        fun fromAngle(rad: Float): Vector2 = Vector2(cos(rad), sin(rad))
    }
}