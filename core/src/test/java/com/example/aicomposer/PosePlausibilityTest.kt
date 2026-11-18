package com.example.aicomposer

import com.example.aicomposer.analysis.P
import com.example.aicomposer.analysis.PoseInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosePlausibilityTest {
    private val aspect = 4f / 3f

    @Test
    fun standingPersonIsAccepted() {
        val pose = PoseInfo(
            mapOf(
                0 to P(0.50f, 0.20f),                       // nose
                11 to P(0.46f, 0.30f), 12 to P(0.54f, 0.30f), // shoulders
                13 to P(0.44f, 0.42f), 14 to P(0.56f, 0.42f), // elbows
                23 to P(0.47f, 0.52f), 24 to P(0.53f, 0.52f), // hips
                25 to P(0.47f, 0.70f), 26 to P(0.53f, 0.70f), // knees
            )
        )
        assertTrue(pose.plausible(aspect))
    }

    @Test
    fun scatteredPointsOnFoliageAreRejected() {
        // Like the false detection on cherry blossoms: points spread wide and flat across the bottom of the frame
        val pose = PoseInfo(
            mapOf(
                0 to P(0.30f, 0.82f), 2 to P(0.65f, 0.76f),
                11 to P(0.45f, 0.78f), 12 to P(0.58f, 0.84f),
                13 to P(0.33f, 0.90f), 14 to P(0.62f, 0.93f),
                23 to P(0.52f, 0.80f), 24 to P(0.40f, 0.92f),
            )
        )
        assertFalse(pose.plausible(aspect))
    }

    @Test
    fun noShouldersIsRejected() {
        val pose = PoseInfo(mapOf(0 to P(0.5f, 0.2f), 23 to P(0.47f, 0.5f), 24 to P(0.53f, 0.5f), 25 to P(0.47f, 0.7f), 26 to P(0.53f, 0.7f), 27 to P(0.5f, 0.9f)))
        assertFalse(pose.plausible(aspect))
    }
}
