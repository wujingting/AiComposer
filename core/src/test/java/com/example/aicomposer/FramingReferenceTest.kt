package com.example.aicomposer

import com.example.aicomposer.ai.CropPixels
import com.example.aicomposer.ai.pixelBounds
import com.example.aicomposer.ai.recommendedCropIndex
import com.example.aicomposer.analysis.CropPlan
import com.example.aicomposer.analysis.NRect
import org.junit.Assert.assertEquals
import org.junit.Test

class FramingReferenceTest {
    @Test
    fun freeFramingKeepsFullFrameForMarginalGain() {
        assertEquals(0, recommendedCropIndex(listOf(5f, 5.14f, 5.1f), CropPlan()))
        assertEquals(1, recommendedCropIndex(listOf(5f, 5.16f, 5.1f), CropPlan()))
    }

    @Test
    fun plannedCropCanChooseOffCenterEvenForMarginalGain() {
        val scores = MutableList(25) { 5f }
        scores[12] = 5.02f // centered crop
        scores[18] = 5.04f
        assertEquals(18, recommendedCropIndex(scores, CropPlan(keep = 0.7f)))
    }

    @Test
    fun fractionOfOddSizedFrameUsesTheScoredPixelBounds() {
        val rect = NRect(0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(CropPixels(25, 18, 51, 37), rect.pixelBounds(103, 75))
        assertEquals(CropPixels(0, 0, 103, 75), NRect(0f, 0f, 1f, 1f).pixelBounds(103, 75))
    }
}
