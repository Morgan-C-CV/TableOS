package com.lenslab.fit

data class FitResult(
    val type: String, // "convex" | "concave" | "unknown"
    val angleRad: Float,
    val aperture: Float,
    val thickness: Float,
    val curvatureRadius: Float,
)