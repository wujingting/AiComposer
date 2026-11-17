package com.example.aicomposer.analysis

import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Saliency detection (Achanta 2009 "frequency-tuned saliency"):
 * the farther a pixel's color (Lab space, slightly blurred) is from the mean color of the image, the more it "pops" and draws the eye.
 * Very cheap, runs on every frame, and works for any subject (signs, light spots, cats, a flower...).
 *
 * Also provides:
 *  - subject(): bounding box of the most salient blob (used as the subject when there is no face/object)
 *  - blobs(): how many "equally eye-catching" regions there are (is the frame simple?)
 *  - centroid(): visual center of mass
 */
class Saliency(val w: Int, val h: Int, val map: FloatArray) {

    private data class Blob(val rect: NRect, val area: Float, val strength: Float)

    private val blobList: List<Blob> by lazy { findBlobs() }

    fun subject(): Pair<NRect, Float>? = blobList.firstOrNull()?.let { it.rect to it.area }

    /** All salient regions (sorted by prominence). */
    fun blobRects(): List<NRect> = blobList.filter { it.area >= 0.005f }.map { it.rect }

    /** Number of regions with at least half the strength of the strongest one and at least 0.5% area. */
    fun blobs(): Int {
        val top = blobList.firstOrNull() ?: return 0
        return blobList.count { it.area >= 0.005f && it.strength >= top.strength * 0.5f }
    }

    fun centroid(): P? {
        var sx = 0f; var sy = 0f; var s = 0f
        for (y in 0 until h) for (x in 0 until w) {
            val v = map[y * w + x]
            sx += v * x; sy += v * y; s += v
        }
        return if (s <= 0f) null else P(sx / s / w, sy / s / h)
    }

    /** Adaptive threshold (2× mean) -> connected components -> sorted by "area × mean saliency". */
    private fun findBlobs(): List<Blob> {
        val mean = map.average().toFloat()
        val th = max(mean * 2f, 0.15f)
        val label = IntArray(w * h) { -1 }
        val out = mutableListOf<Blob>()
        val stack = ArrayDeque<Int>()
        var id = 0
        for (start in map.indices) {
            if (label[start] != -1 || map[start] < th) continue
            var minX = w; var minY = h; var maxX = 0; var maxY = 0; var n = 0; var sum = 0f
            stack.addLast(start); label[start] = id
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                val x = i % w; val y = i / w
                n++; sum += map[i]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                val nbrs = intArrayOf(i - 1, i + 1, i - w, i + w)
                for ((k, j) in nbrs.withIndex()) {
                    if (j < 0 || j >= map.size) continue
                    if ((k == 0 && x == 0) || (k == 1 && x == w - 1)) continue
                    if (label[j] == -1 && map[j] >= th) { label[j] = id; stack.addLast(j) }
                }
            }
            id++
            val area = n.toFloat() / (w * h)
            if (area < 0.002f) continue
            out += Blob(
                NRect(minX.toFloat() / w, minY.toFloat() / h, (maxX + 1).toFloat() / w, (maxY + 1).toFloat() / h),
                area, sum / n,
            )
        }
        return out.sortedByDescending { it.area * it.strength }
    }

    companion object {
        /** Uses the 160px color image, downscaled to 80px wide. */
        fun compute(gray: GrayFrame, rgb: IntArray): Saliency {
            val w = gray.w / 2
            val h = gray.h / 2
            val L = FloatArray(w * h); val A = FloatArray(w * h); val B = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var r = 0f; var g = 0f; var b = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val c = rgb[(y * 2 + dy) * gray.w + (x * 2 + dx)]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                }
                val lab = toLab(r / 4f / 255f, g / 4f / 255f, b / 4f / 255f)
                val i = y * w + x
                L[i] = lab[0]; A[i] = lab[1]; B[i] = lab[2]
            }
            val mL = L.average().toFloat(); val mA = A.average().toFloat(); val mB = B.average().toFloat()
            val bL = blur(blur(L, w, h), w, h)
            val bA = blur(blur(A, w, h), w, h)
            val bB = blur(blur(B, w, h), w, h)
            val sal = FloatArray(w * h)
            var mx = 0f
            for (i in sal.indices) {
                val dl = bL[i] - mL; val da = bA[i] - mA; val db = bB[i] - mB
                sal[i] = sqrt(dl * dl + da * da + db * db)
                if (sal[i] > mx) mx = sal[i]
            }
            if (mx > 0f) for (i in sal.indices) sal[i] /= mx
            return Saliency(w, h, sal)
        }

        private fun blur(src: FloatArray, w: Int, h: Int): FloatArray {
            val out = FloatArray(src.size)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f; var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h) { s += src[yy * w + xx]; n++ }
                }
                out[y * w + x] = s / n
            }
            return out
        }

        private fun lin(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

        private fun toLab(r0: Float, g0: Float, b0: Float): FloatArray {
            val r = lin(r0); val g = lin(g0); val b = lin(b0)
            val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f
            fun f(t: Float) = if (t > 0.008856f) cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f
            val fx = f(x); val fy = f(y); val fz = f(z)
            return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
        }
    }
}
