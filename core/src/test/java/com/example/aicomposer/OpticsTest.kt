package com.example.aicomposer

import com.example.aicomposer.coach.Optics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticsTest {
    @Test
    fun faceDistance_50mm_faceTenthOfFrame_isAboutSixMeters() {
        // 50 × 0.2 / (0.1 × 15.6) ≈ 6.4m
        assertEquals(6.4, Optics.faceDistance(0.1f, 50), 0.1)
    }

    @Test
    fun dof_50mm_f18_at2m_isAboutTenCentimeters() {
        val depth = Optics.dofDepth(50, 1.8, 2.0)
        assertTrue("depth of field should be 8~15cm, got $depth", depth in 0.08..0.15)
    }

    @Test
    fun dof_widensWhenStoppingDown() {
        assertTrue(Optics.dofDepth(50, 8.0, 3.0) > Optics.dofDepth(50, 1.8, 3.0) * 3)
    }

    @Test
    fun dof_farIsInfinityBeyondHyperfocal() {
        val (_, far) = Optics.dof(35, 16.0, 10.0)
        assertTrue(far.isInfinite())
    }

    @Test
    fun requiredF_twoRowsNeedsSmallerAperture() {
        val f = Optics.requiredF(50, 1.85, 3.15)
        assertNotNull(f)
        assertTrue("1.3m front to back needs at least f/8, got $f", f!! >= 8.0)
    }

    @Test
    fun groupRange_singleFaceIsNull() {
        assertNull(Optics.groupRange(listOf(TestData.face(0.4f, 0.3f, 0.1f)), 50))
    }
}
