package com.example.aicomposer

import com.example.aicomposer.ai.CocoClasses
import com.example.aicomposer.ai.YoloxCodec
import com.example.aicomposer.analysis.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class YoloxCodecTest {
    private val size = 64 // 8×8 + 4×4 + 2×2 = 84 rows
    private val stride = 85

    /** Raw output with every score at zero, plus [cells] = (row, gridX, gridY, step, class, objectness, boxPx). */
    private fun raw(vararg cells: List<Number>): FloatArray {
        val out = FloatArray(84 * stride)
        for (c in cells) {
            val o = c[0].toInt() * stride
            out[o] = 0.5f; out[o + 1] = 0.5f                     // box center at the middle of the cell
            out[o + 2] = ln(c[6].toFloat() / c[3].toFloat())      // w = exp(v) * step
            out[o + 3] = out[o + 2]
            out[o + 4] = c[5].toFloat()
            out[o + 5 + c[4].toInt()] = 0.9f
        }
        return out
    }

    @Test
    fun decodesBoxIntoImageCoordinates() {
        // Row 9 of the stride-8 grid = cell (1, 1): center (12, 12) px, box 16 px; image 128×128 scaled by 0.5
        val dets = YoloxCodec.decode(raw(listOf(9, 1, 1, 8, 6, 0.9f, 16)), size, 0.5f, 128, 128, 0.35f, 0.45f)
        assertEquals(1, dets.size)
        val d = dets[0]
        assertEquals(6, d.cls)
        assertEquals(0.81f, d.score, 1e-4f)
        assertEquals(0.0625f, d.rect.l, 1e-4f) // (12 - 8) / 0.5 / 128
        assertEquals(0.3125f, d.rect.r, 1e-4f) // (12 + 8) / 0.5 / 128
    }

    @Test
    fun suppressesOverlapsOfTheSameClassOnly() {
        val a = listOf(9, 1, 1, 8, 6, 0.9f, 16)
        val b = listOf(64, 0, 0, 16, 6, 0.6f, 24) // same class, IoU 0.64 with a -> dropped
        val c = listOf(18, 2, 2, 8, 0, 0.8f, 16)  // a person overlapping it -> kept
        val dets = YoloxCodec.decode(raw(a, b, c), size, 0.5f, 128, 128, 0.35f, 0.45f)
        assertEquals(listOf(6, 0), dets.map { it.cls })
    }

    @Test
    fun dropsLowConfidence() {
        assertTrue(YoloxCodec.decode(raw(listOf(9, 1, 1, 8, 6, 0.3f, 16)), size, 0.5f, 128, 128, 0.35f, 0.45f).isEmpty())
    }

    @Test
    fun preprocessIsBgrWithGrayPadding() {
        val px = intArrayOf(0xFF102030.toInt()) // 1×1 pixel, R=0x10 G=0x20 B=0x30
        val x = YoloxCodec.preprocess(px, 1, 1, 2)
        assertEquals(0x30.toFloat(), x[0]); assertEquals(0x20.toFloat(), x[4]); assertEquals(0x10.toFloat(), x[8])
        assertEquals(114f, x[1])
    }

    @Test
    fun classTable() {
        assertEquals(80, CocoClasses.count)
        assertEquals(SubjectKind.PERSON, CocoClasses.kind(0))
    }
}
