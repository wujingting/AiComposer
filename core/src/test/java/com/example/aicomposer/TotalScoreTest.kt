package com.example.aicomposer

import com.example.aicomposer.ai.AestheticResult
import com.example.aicomposer.coach.Category
import com.example.aicomposer.coach.CheckItem
import com.example.aicomposer.coach.CoachEngine
import com.example.aicomposer.coach.Part
import com.example.aicomposer.coach.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TotalScoreTest {
    private fun parts(c: Int, l: Int, t: Int) = mapOf(Part.COMPOSITION to c, Part.LIGHT to l, Part.TECH to t)
    private fun aes(norm: Int) = AestheticResult(4f + norm / 40f, norm, 0.5f, null, 0f, 0L)

    @Test
    fun weightedMeanInsteadOfProduct() {
        // Cherry blossom photo: composition 56, light 100, technical 82 used to give 46 (the product)
        assertEquals(74, CoachEngine.total(parts(56, 100, 82), emptyList(), null))
    }

    @Test
    fun blendsTheAestheticScore() {
        // Girl with cats: 60 / 77 / 100 and aesthetic 5.98 (norm 79): 0.6 × 74.25 + 0.4 × 79 = 76
        assertEquals(76, CoachEngine.total(parts(60, 77, 100), emptyList(), aes(79)))
    }

    @Test
    fun ruiningProblemCapsTheTotal() {
        val blur = CheckItem("shake", "Shake", Category.FOCUS, Severity.ERROR, "blurry", 25f)
        assertEquals(60, CoachEngine.total(parts(100, 100, 66), listOf(blur), aes(100)))
    }

    @Test
    fun perfectStaysPerfect() {
        assertEquals(100, CoachEngine.total(parts(100, 100, 100), emptyList(), aes(100)))
        assertTrue(CoachEngine.total(parts(100, 100, 100), emptyList(), aes(0)) < 100)
    }
}
