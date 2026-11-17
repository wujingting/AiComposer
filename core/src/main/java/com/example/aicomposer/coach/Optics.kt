package com.example.aicomposer.coach

import com.example.aicomposer.analysis.FaceInfo

/**
 * Optics (a6000: APS-C, sensor 23.5×15.6mm).
 * Estimates the shooting distance from the face height in the frame, then the depth of field.
 */
object Optics {
    const val SENSOR_H_MM = 15.6
    const val COC_MM = 0.02        // APS-C circle of confusion
    const val FACE_H_M = 0.20      // approximate real height of a face detection box (forehead to chin)

    /** Estimated distance to the face (meters). faceH = face box height / frame height. */
    fun faceDistance(faceH: Float, focalMm: Int): Double =
        focalMm * FACE_H_M / (faceH.coerceAtLeast(0.005f) * SENSOR_H_MM)

    /** Depth of field (near, far) in meters; far may be infinity. */
    fun dof(focalMm: Int, n: Double, s: Double): Pair<Double, Double> {
        val f = focalMm / 1000.0
        val c = COC_MM / 1000.0
        val h = f * f / (n * c) + f
        val near = s * (h - f) / (h + s - 2 * f)
        val far = if (s >= h) Double.POSITIVE_INFINITY else s * (h - f) / (h - s)
        return near to far
    }

    fun dofDepth(focalMm: Int, n: Double, s: Double): Double {
        val (a, b) = dof(focalMm, n, s)
        return b - a
    }

    private val STOPS = listOf(1.8, 2.0, 2.8, 3.5, 4.0, 5.6, 8.0, 11.0, 16.0)

    /** Smallest aperture that keeps near..far in focus. Focus at their harmonic mean (needs the least stopping down). */
    fun requiredF(focalMm: Int, near: Double, far: Double): Double? {
        val s = 2 * near * far / (near + far)
        return STOPS.firstOrNull { n ->
            val (dn, df) = dof(focalMm, n, s)
            dn <= near && df >= far
        }
    }

    /** Several people: distance range of all faces (plus 15cm margin front and back), returns (near, far). */
    fun groupRange(faces: List<FaceInfo>, focalMm: Int): Pair<Double, Double>? {
        val big = faces.filter { it.rect.h > 0.03f }
        if (big.size < 2) return null
        val d = big.map { faceDistance(it.rect.h, focalMm) }
        return (d.min() - 0.15).coerceAtLeast(0.3) to d.max() + 0.15
    }

    /** Aperture needed for a group shot; null for a single person or when it cannot be computed. */
    fun groupRequiredF(faces: List<FaceInfo>, focalMm: Int): Double? {
        val (near, far) = groupRange(faces, focalMm) ?: return null
        return requiredF(focalMm, near, far)
    }
}
