package com.example.aicomposer.analysis

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Detailed face information (ML Kit). */
data class FaceInfo(
    val rect: NRect,
    val yaw: Float,           // left/right head turn; positive = face turned towards the right of the frame
    val roll: Float,          // head tilt
    val leftEyeOpen: Float?,  // eye-open probability 0..1
    val rightEyeOpen: Float?,
    val smile: Float?,        // smile probability 0..1
    val eyes: List<P>,        // eye positions (normalized)
)

/** Body pose landmarks (ML Kit Pose); only confidently in-frame points. key = PoseLandmark type. */
data class PoseInfo(val points: Map<Int, P>) {
    val bounds: NRect?
        get() {
            if (points.size < 4) return null
            val xs = points.values.map { it.x }
            val ys = points.values.map { it.y }
            val padX = (xs.max() - xs.min()) * 0.15f
            val padY = (ys.max() - ys.min()) * 0.1f
            return NRect(xs.min() - padX, ys.min() - padY * 2, xs.max() + padX, ys.max() + padY)
        }

    /**
     * Whether the landmarks form a believable human skeleton. ML Kit Pose always returns a pose, and on scenes
     * without people (blossoms, foliage, textures) it scatters confident-looking points that would otherwise be
     * taken as "a person". Checks: both shoulders, plus a head or both hips, in anatomically sane positions.
     * @param aspect frame width / height, so distances are measured in the same unit on both axes
     */
    fun plausible(aspect: Float): Boolean {
        fun pt(id: Int) = points[id]?.let { P(it.x * aspect, it.y) }
        fun dist(a: P, b: P) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
        fun mid(a: P, b: P) = P((a.x + b.x) / 2, (a.y + b.y) / 2)
        val ls = pt(L_SHOULDER) ?: return false
        val rs = pt(R_SHOULDER) ?: return false
        val shoulders = mid(ls, rs)
        val sw = dist(ls, rs)
        if (sw < 0.02f) return false

        val head = HEAD.mapNotNull { pt(it) }
        val lh = pt(L_HIP)
        val rh = pt(R_HIP)
        val hips = if (lh != null && rh != null) mid(lh, rh) else null
        if (head.isEmpty() && hips == null) return false

        // Head: above the shoulders, at a distance comparable to the shoulder width
        if (head.isNotEmpty()) {
            val hc = P(head.map { it.x }.average().toFloat(), head.map { it.y }.average().toFloat())
            val d = dist(hc, shoulders)
            if (hc.y > shoulders.y + sw * 0.2f) return false
            if (d < sw * 0.2f || d > sw * 2.5f) return false
        }
        // Torso: hips below the shoulders, torso longer than wide but not absurdly so (side views shrink the width)
        if (hips != null) {
            val torso = dist(shoulders, hips)
            val hw = dist(lh!!, rh!!)
            val width = max(sw, hw)
            if (hips.y < shoulders.y) return false
            if (torso < width * 0.7f || torso > width * 6f) return false
            if (hw > sw * 2.5f) return false
        }
        // Limbs attach near the body: an elbow / knee far from its shoulder / hip is noise
        val limbs = listOf(L_ELBOW to L_SHOULDER, R_ELBOW to R_SHOULDER, L_KNEE to L_HIP, R_KNEE to R_HIP)
        val torsoLen = hips?.let { dist(shoulders, it) } ?: (sw * 1.5f)
        for ((j, root) in limbs) {
            val a = pt(j) ?: continue
            val b = pt(root) ?: continue
            if (dist(a, b) > torsoLen * 1.6f + sw) return false
        }
        return true
    }

    companion object {
        // ML Kit PoseLandmark type ids (kept as plain ints so this class stays free of ML Kit)
        private const val L_SHOULDER = 11
        private const val R_SHOULDER = 12
        private const val L_ELBOW = 13
        private const val R_ELBOW = 14
        private const val L_HIP = 23
        private const val R_HIP = 24
        private const val L_KNEE = 25
        private const val R_KNEE = 26
        /** Nose, eyes, ears. */
        private val HEAD = listOf(0, 2, 5, 7, 8)
    }
}

/** Foreground confidence map from subject segmentation (0..1), w×h. */
class Mask(val w: Int, val h: Int, val data: FloatArray) {
    fun at(x: Float, y: Float): Float {
        val ix = (x * w).toInt().coerceIn(0, w - 1)
        val iy = (y * h).toInt().coerceIn(0, h - 1)
        return data[iy * w + ix]
    }

    /** Bounding box of the foreground and its area fraction. */
    fun foreground(threshold: Float = 0.5f): Pair<NRect, Float>? {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1; var n = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (data[y * w + x] >= threshold) {
                n++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (n == 0) return null
        return NRect(minX.toFloat() / w, minY.toFloat() / h, (maxX + 1).toFloat() / w, (maxY + 1).toFloat() / h) to
            n.toFloat() / (w * h)
    }

    /** Centroid of the foreground. */
    fun centroid(): P? {
        var sx = 0f; var sy = 0f; var s = 0f
        for (y in 0 until h) for (x in 0 until w) {
            val v = data[y * w + x]
            sx += v * x; sy += v * y; s += v
        }
        return if (s < 1f) null else P(sx / s / w, sy / s / h)
    }
}

/**
 * Low-level features extracted from one frame, shared by the rules.
 */
class Features(
    val gray: GrayFrame,      // 160px grayscale
    val rgb: IntArray,        // 160px color (ARGB)
    val edge: FloatArray,     // 160px gradient magnitude
    val detail: GrayFrame,    // 320px grayscale (sharpness, "something growing out of the head" check)
    val meanR: Float, val meanG: Float, val meanB: Float,
    val warmLeft: Float, val warmRight: Float, // R/B ratio of the left and right halves (mixed lighting)
    val tilt: Float?,         // horizon tilt in degrees, positive = lower on the right
    val horizonY: Float?,     // approximate horizon height (0..1)
    val vertLeft: Float?,     // deviation from vertical of vertical lines in the left half
    val vertRight: Float?,    // deviation from vertical of vertical lines in the right half
    val symmetry: Float,      // left/right symmetry 0..1
    val saliency: Saliency,   // saliency map (what "pops" most in the frame)
    val saturation: Float,    // mean saturation 0..1
    val lumaStd: Float,       // standard deviation of brightness over the frame (contrast)
    val diagonalRatio: Float, // share of diagonal lines among all strong edges
) {
    /** Mean gradient magnitude in a region (texture / clutter), optionally excluding one region. */
    fun edgeMean(r: NRect, exclude: NRect? = null): Float? {
        val g = gray
        val x0 = (r.l * g.w).toInt().coerceIn(0, g.w - 1)
        val x1 = (r.r * g.w).toInt().coerceIn(x0 + 1, g.w)
        val y0 = (r.t * g.h).toInt().coerceIn(0, g.h - 1)
        val y1 = (r.b * g.h).toInt().coerceIn(y0 + 1, g.h)
        var s = 0f; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            if (exclude != null) {
                val nx = (x + 0.5f) / g.w; val ny = (y + 0.5f) / g.h
                if (nx in exclude.l..exclude.r && ny in exclude.t..exclude.b) continue
            }
            s += edge[y * g.w + x]; n++
        }
        return if (n < 10) null else s / n
    }

    /** Mean color (r, g, b) 0..1 of a region, optionally excluding one region. */
    fun colorMean(r: NRect, exclude: NRect? = null): FloatArray? {
        val g = gray
        val x0 = (r.l * g.w).toInt().coerceIn(0, g.w - 1)
        val x1 = (r.r * g.w).toInt().coerceIn(x0 + 1, g.w)
        val y0 = (r.t * g.h).toInt().coerceIn(0, g.h - 1)
        val y1 = (r.b * g.h).toInt().coerceIn(y0 + 1, g.h)
        var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            if (exclude != null) {
                val nx = (x + 0.5f) / g.w; val ny = (y + 0.5f) / g.h
                if (nx in exclude.l..exclude.r && ny in exclude.t..exclude.b) continue
            }
            val c = rgb[y * g.w + x]
            sr += ((c shr 16) and 0xFF); sg += ((c shr 8) and 0xFF); sb += (c and 0xFF); n++
        }
        return if (n < 10) null else floatArrayOf(sr / n / 255f, sg / n / 255f, sb / n / 255f)
    }

    /** Mean and standard deviation of brightness in a region (0..1). */
    fun lumaStats(r: NRect): Pair<Float, Float>? {
        val g = gray
        val x0 = (r.l * g.w).toInt().coerceIn(0, g.w - 1)
        val x1 = (r.r * g.w).toInt().coerceIn(x0 + 1, g.w)
        val y0 = (r.t * g.h).toInt().coerceIn(0, g.h - 1)
        val y1 = (r.b * g.h).toInt().coerceIn(y0 + 1, g.h)
        var s = 0.0; var s2 = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val v = g.px[y * g.w + x] / 255.0
            s += v; s2 += v * v; n++
        }
        if (n < 4) return null
        val m = s / n
        return m.toFloat() to sqrt(max(0.0, s2 / n - m * m)).toFloat()
    }

    /** Fraction of clipped pixels in a region. */
    fun clipFraction(r: NRect, level: Int = 250): Float {
        val g = gray
        val x0 = (r.l * g.w).toInt().coerceIn(0, g.w - 1)
        val x1 = (r.r * g.w).toInt().coerceIn(x0 + 1, g.w)
        val y0 = (r.t * g.h).toInt().coerceIn(0, g.h - 1)
        val y1 = (r.b * g.h).toInt().coerceIn(y0 + 1, g.h)
        var c = 0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            if (g.px[y * g.w + x] >= level) c++
            n++
        }
        return if (n == 0) 0f else c.toFloat() / n
    }

    /** Sharpness of a region (mean Laplacian, computed on the 320px image). */
    fun sharpness(r: NRect, exclude: NRect? = null): Float? {
        val g = detail
        val x0 = (r.l * g.w).toInt().coerceIn(1, g.w - 2)
        val x1 = (r.r * g.w).toInt().coerceIn(x0 + 1, g.w - 1)
        val y0 = (r.t * g.h).toInt().coerceIn(1, g.h - 2)
        val y1 = (r.b * g.h).toInt().coerceIn(y0 + 1, g.h - 1)
        var s = 0f; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            if (exclude != null) {
                val nx = (x + 0.5f) / g.w; val ny = (y + 0.5f) / g.h
                if (nx in exclude.l..exclude.r && ny in exclude.t..exclude.b) continue
            }
            val i = y * g.w + x
            val lap = 4 * g.px[i] - g.px[i - 1] - g.px[i + 1] - g.px[i - g.w] - g.px[i + g.w]
            s += abs(lap); n++
        }
        return if (n < 20) null else s / n
    }
}

object FeatureExtractor {

    fun compute(bmp: Bitmap): Features {
        val (gray, rgb) = scaled(bmp, 160)
        val detail = scaled(bmp, 320).first

        var sr = 0L; var sg = 0L; var sb = 0L
        var satSum = 0f
        var lr = 0L; var lb = 0L; var rr = 0L; var rb = 0L
        val half = gray.w / 2
        for (i in rgb.indices) {
            val c = rgb[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            sr += r; sg += g; sb += b
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            if (mx > 0) satSum += (mx - mn).toFloat() / mx
            if (i % gray.w < half) { lr += r; lb += b } else { rr += r; rb += b }
        }
        val n = rgb.size.toFloat()
        val sat = satSum / n

        val edges = analyzeEdges(gray)
        return Features(
            gray = gray, rgb = rgb, edge = edges.mag, detail = detail,
            meanR = sr / n / 255f, meanG = sg / n / 255f, meanB = sb / n / 255f,
            warmLeft = (lr + 1f) / (lb + 1f), warmRight = (rr + 1f) / (rb + 1f),
            tilt = edges.tilt, horizonY = edges.horizonY,
            vertLeft = edges.vertLeft, vertRight = edges.vertRight,
            symmetry = symmetry(gray),
            saliency = Saliency.compute(gray, rgb),
            saturation = sat,
            lumaStd = lumaStd(gray),
            diagonalRatio = edges.diagonalRatio,
        )
    }

    private fun scaled(bmp: Bitmap, targetW: Int): Pair<GrayFrame, IntArray> {
        val h = max(1, (bmp.height.toFloat() * targetW / bmp.width).roundToInt())
        val small = Bitmap.createScaledBitmap(bmp, targetW, h, true)
        val argb = IntArray(targetW * h)
        small.getPixels(argb, 0, targetW, 0, 0, targetW, h)
        if (small !== bmp) small.recycle()
        val g = IntArray(argb.size)
        for (i in argb.indices) {
            val c = argb[i]
            g[i] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return GrayFrame(targetW, h, g) to argb
    }

    private class EdgeResult(
        val mag: FloatArray, val tilt: Float?, val horizonY: Float?,
        val vertLeft: Float?, val vertRight: Float?,
        val diagonalRatio: Float,
    )

    /**
     * One pass gives: gradient magnitude map, angle and height of the dominant near-horizontal line, and the tilt of vertical lines in each half.
     * Angle convention: image y points down, positive angle = line slopes down to the right.
     */
    private fun analyzeEdges(f: GrayFrame): EdgeResult {
        val w = f.w; val h = f.h; val p = f.px
        val mag = FloatArray(w * h)
        val bins = FloatArray(61)   // horizon: -15°..+15°, 0.5° per bin
        val binsY = FloatArray(61)
        var total = 0f
        var vlSum = 0f; var vlW = 0f; var vrSum = 0f; var vrW = 0f
        var diag = 0f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = (p[y * w + x + 1] - p[y * w + x - 1]).toFloat()
                val gy = (p[(y + 1) * w + x] - p[(y - 1) * w + x]).toFloat()
                val m = sqrt(gx * gx + gy * gy)
                mag[y * w + x] = m
                if (m < 40f) continue
                total += m
                var theta = Math.toDegrees(atan2(-gx.toDouble(), gy.toDouble())).toFloat()
                if (theta > 90f) theta -= 180f
                if (theta <= -90f) theta += 180f
                if (abs(theta) <= 15f) {
                    val bin = ((theta + 15f) * 2f).roundToInt().coerceIn(0, 60)
                    bins[bin] += m
                    binsY[bin] += m * y
                } else if (abs(theta) in 25f..65f) {
                    diag += m
                } else if (abs(theta) >= 80f) {
                    val d = if (theta > 0) theta - 90f else theta + 90f // deviation from vertical
                    if (x < w / 2) { vlSum += d * m; vlW += m } else { vrSum += d * m; vrW += m }
                }
            }
        }

        var tilt: Float? = null
        var horizonY: Float? = null
        if (total > 0f) {
            var best = -1; var bestVal = 0f
            for (i in 0..60) {
                val v = bins[i] + (if (i > 0) bins[i - 1] else 0f) + (if (i < 60) bins[i + 1] else 0f)
                if (v > bestVal) { bestVal = v; best = i }
            }
            if (best >= 0 && bestVal / total >= 0.12f && bestVal >= w * 40f * 1.5f) {
                var s = 0f; var ws = 0f; var sy = 0f
                for (i in max(0, best - 1)..min(60, best + 1)) {
                    s += bins[i] * (i / 2f - 15f); ws += bins[i]; sy += binsY[i]
                }
                if (ws > 0f) {
                    tilt = s / ws
                    horizonY = sy / ws / h
                }
            }
        }
        val minV = h * 40f * 1.5f
        return EdgeResult(
            mag, tilt, horizonY,
            if (vlW >= minV) vlSum / vlW else null,
            if (vrW >= minV) vrSum / vrW else null,
            if (total > 0f) diag / total else 0f,
        )
    }

    private fun lumaStd(f: GrayFrame): Float {
        var s = 0.0; var s2 = 0.0
        for (v in f.px) { val x = v / 255.0; s += x; s2 += x * x }
        val m = s / f.px.size
        return sqrt(max(0.0, s2 / f.px.size - m * m)).toFloat()
    }

    /** The smaller the difference to the mirrored image, the more symmetric. */
    private fun symmetry(f: GrayFrame): Float {
        var s = 0L
        for (y in 0 until f.h) for (x in 0 until f.w / 2) {
            s += abs(f.px[y * f.w + x] - f.px[y * f.w + (f.w - 1 - x)])
        }
        val diff = s.toFloat() / (f.h * (f.w / 2)) / 255f
        return (1f - diff * 4f).coerceIn(0f, 1f)
    }
}
