package com.example.aicomposer

import com.example.aicomposer.analysis.GrayFrame
import com.example.aicomposer.analysis.Saliency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaliencyTest {
    /** Builds a 160×106 grey image with a red square at the given position. */
    private fun image(squares: List<Pair<Int, Int>>, size: Int = 16): Pair<GrayFrame, IntArray> {
        val w = 160; val h = 106
        val rgb = IntArray(w * h) { 0xFF808080.toInt() }
        for ((sx, sy) in squares) for (y in sy until sy + size) for (x in sx until sx + size) {
            rgb[y * w + x] = 0xFFE02020.toInt()
        }
        val gray = IntArray(w * h) { i ->
            val c = rgb[i]
            (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return GrayFrame(w, h, gray) to rgb
    }

    @Test
    fun singleRedSquare_isTheSubject() {
        val (g, rgb) = image(listOf(100 to 30))
        val sal = Saliency.compute(g, rgb)
        val (rect, _) = sal.subject()!!
        assertTrue("subject center should be near the square, got ${rect.cx}", rect.cx in 0.6f..0.75f)
        assertEquals(1, sal.blobs())
    }

    @Test
    fun manySquares_areCountedAsManyBlobs() {
        val (g, rgb) = image(listOf(10 to 10, 70 to 10, 130 to 10, 40 to 70, 100 to 70))
        assertTrue(Saliency.compute(g, rgb).blobs() >= 3)
    }

    @Test
    fun centroid_followsSquare() {
        val (g, rgb) = image(listOf(10 to 40))
        val c = Saliency.compute(g, rgb).centroid()
        assertNotNull(c)
        assertTrue(c!!.x < 0.4f)
    }
}
