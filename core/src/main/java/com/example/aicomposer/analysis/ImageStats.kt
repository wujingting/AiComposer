package com.example.aicomposer.analysis

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Downscaled grayscale image used for brightness, motion and horizon analysis. */
class GrayFrame(val w: Int, val h: Int, val px: IntArray)

object ImageStats {

    fun toGray(bmp: Bitmap, targetW: Int = 160): GrayFrame {
        val h = max(1, (bmp.height.toFloat() * targetW / bmp.width).roundToInt())
        val small = Bitmap.createScaledBitmap(bmp, targetW, h, true)
        val argb = IntArray(targetW * h)
        small.getPixels(argb, 0, targetW, 0, 0, targetW, h)
        if (small !== bmp) small.recycle()
        val g = IntArray(argb.size)
        for (i in argb.indices) {
            val c = argb[i]
            val r = (c shr 16) and 0xFF
            val gg = (c shr 8) and 0xFF
            val b = c and 0xFF
            g[i] = (r * 299 + gg * 587 + b * 114) / 1000
        }
        return GrayFrame(targetW, h, g)
    }

    fun luma(f: GrayFrame, subject: NRect?): LumaStats {
        var sum = 0L
        var hi = 0
        var lo = 0
        for (v in f.px) {
            sum += v
            if (v >= 250) hi++
            if (v <= 5) lo++
        }
        val n = f.px.size.toFloat()
        return LumaStats(
            mean = sum / n / 255f,
            highlightClip = hi / n,
            shadowClip = lo / n,
            subjectMean = subject?.let { regionMean(f, it) },
        )
    }

    private fun bounds(f: GrayFrame, r: NRect): IntArray {
        val x0 = (r.l * f.w).toInt().coerceIn(0, f.w - 1)
        val x1 = (r.r * f.w).toInt().coerceIn(x0 + 1, f.w)
        val y0 = (r.t * f.h).toInt().coerceIn(0, f.h - 1)
        val y1 = (r.b * f.h).toInt().coerceIn(y0 + 1, f.h)
        return intArrayOf(x0, x1, y0, y1)
    }

    private fun regionMean(f: GrayFrame, r: NRect): Float {
        val (x0, x1, y0, y1) = bounds(f, r)
        var sum = 0L
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            sum += f.px[y * f.w + x]; n++
        }
        return if (n == 0) 0f else sum.toFloat() / n / 255f
    }

    /** Returns (whole-frame change, subject-region change), both 0..1. */
    fun motion(prev: GrayFrame?, cur: GrayFrame, region: NRect?): Pair<Float, Float?> {
        if (prev == null || prev.w != cur.w || prev.h != cur.h) return 0f to null
        var sum = 0L
        for (i in cur.px.indices) sum += abs(cur.px[i] - prev.px[i])
        val global = sum.toFloat() / cur.px.size / 255f
        val local = region?.let {
            val (x0, x1, y0, y1) = bounds(cur, it)
            var s = 0L
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val i = y * cur.w + x
                s += abs(cur.px[i] - prev.px[i]); n++
            }
            if (n == 0) null else s.toFloat() / n / 255f
        }
        return global to local
    }

    /**
     * Estimates the tilt (degrees) of the dominant "near-horizontal straight line" in the frame.
     * Positive = lower on the right. Returns null when there is no clear enough horizontal line.
     * Method: histogram of gradient directions, only edges within ±15°, take the peak.
     */
    fun estimateTilt(f: GrayFrame): Float? {
        val w = f.w
        val h = f.h
        val p = f.px
        val bins = FloatArray(61) // -15°..+15°, step 0.5°
        var totalWeight = 0f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = (p[y * w + x + 1] - p[y * w + x - 1]).toFloat()
                val gy = (p[(y + 1) * w + x] - p[(y - 1) * w + x]).toFloat()
                val mag = sqrt(gx * gx + gy * gy)
                if (mag < 40f) continue
                totalWeight += mag
                // Edge direction is perpendicular to the gradient: direction vector (gy, -gx)
                var theta = Math.toDegrees(atan2(-gx.toDouble(), gy.toDouble())).toFloat()
                if (theta > 90f) theta -= 180f
                if (theta <= -90f) theta += 180f
                if (abs(theta) > 15f) continue
                val bin = ((theta + 15f) * 2f).roundToInt().coerceIn(0, 60)
                bins[bin] += mag
            }
        }
        if (totalWeight <= 0f) return null
        var best = -1
        var bestVal = 0f
        for (i in 0..60) {
            val v = bins[i] + (if (i > 0) bins[i - 1] else 0f) + (if (i < 60) bins[i + 1] else 0f)
            if (v > bestVal) { bestVal = v; best = i }
        }
        if (best < 0) return null
        if (bestVal / totalWeight < 0.12f) return null        // not prominent enough
        if (bestVal < w * 40f * 1.5f) return null              // line too short / too weak
        var s = 0f
        var ws = 0f
        for (i in max(0, best - 1)..min(60, best + 1)) {
            s += bins[i] * (i / 2f - 15f)
            ws += bins[i]
        }
        return if (ws > 0f) s / ws else null
    }
}
