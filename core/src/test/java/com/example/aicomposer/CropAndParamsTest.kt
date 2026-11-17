package com.example.aicomposer

import com.example.aicomposer.analysis.AutoCropper
import com.example.aicomposer.analysis.CropPlan
import com.example.aicomposer.analysis.NRect
import com.example.aicomposer.analysis.ParameterAdvisor
import com.example.aicomposer.analysis.SceneType
import com.example.aicomposer.analysis.Subject
import com.example.aicomposer.analysis.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropAndParamsTest {
    @Test
    fun cropPlan_sameAspect_keepsProportion() {
        val (w, h) = CropPlan(0.6f, 1.5f).size(1.5f)
        assertEquals(0.6f, w, 1e-4f)
        assertEquals(0.6f, h, 1e-4f)
        assertEquals(1 / 0.6f, CropPlan(0.6f, 1.5f).magnification(1.5f), 1e-3f)
    }

    @Test
    fun cropPlan_squareFromFull_isLimitedByHeight() {
        val (w, h) = CropPlan(1f, 1f).size(1.5f)
        assertEquals(1f, h, 1e-4f)
        assertEquals(1 / 1.5f, w, 1e-3f)
    }

    @Test
    fun cropPlan_defaultIsInactive() {
        assertFalse(CropPlan().active)
        assertTrue(CropPlan(0.7f).active)
    }

    @Test
    fun parseShutter_handlesSonyFormats() {
        assertEquals(1 / 250.0, ParameterAdvisor.parseShutter("1/250")!!, 1e-9)
        assertEquals(0.8, ParameterAdvisor.parseShutter("0.8\"")!!, 1e-9)
        assertEquals(30.0, ParameterAdvisor.parseShutter("30\"")!!, 1e-9)
        assertNull(ParameterAdvisor.parseShutter("BULB"))
    }

    @Test
    fun requiredShutter_cropMakesItStricter() {
        val a = TestData.result()
        val normal = ParameterAdvisor.requiredShutter(a, 50, CropPlan())
        val cropped = ParameterAdvisor.requiredShutter(a, 50, CropPlan(0.5f))
        assertTrue(cropped < normal)
    }

    @Test
    fun autoCropper_smallFace_suggestsCrop() {
        val face = Subject(NRect(0.45f, 0.3f, 0.48f, 0.35f), SubjectKind.PERSON, true) // face height 5%
        val d = AutoCropper.decide(TestData.result(main = face, scene = SceneType.PORTRAIT), 1f)
        assertTrue("should suggest a crop, got ${d.keep}", d.keep < 0.9f)
    }

    @Test
    fun autoCropper_noSubject_keepsFull() {
        assertEquals(1f, AutoCropper.decide(TestData.result(), 1f).keep, 1e-4f)
    }
}
