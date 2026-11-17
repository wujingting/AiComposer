package com.example.aicomposer

import com.example.aicomposer.coach.ExposureRule
import com.example.aicomposer.coach.Severity
import com.example.aicomposer.camera.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.example.aicomposer.i18n.I18n
import com.example.aicomposer.i18n.Lang
import org.junit.Before
import org.junit.Test

class ExposureRuleTest {
    @Before
    fun english() { I18n.lang = Lang.EN }

    private val manual = CameraState(
        exposureMode = "Manual",
        fNumber = "5.6", fNumberCandidates = listOf("1.8", "2.8", "4.0", "5.6", "8.0", "11", "16"),
        shutter = "1/500", iso = "100",
    )

    @Test
    fun midGray_isOk() {
        // middle grey 18% is about 0.46 in sRGB
        val item = ExposureRule.exposure.check(TestData.input(TestData.result(luma = TestData.luma(mean = 0.46f))))!!
        assertEquals(Severity.OK, item.severity)
    }

    @Test
    fun dark_manual_slowsShutterFirst_thenAperture_thenIso() {
        val a = TestData.result(luma = TestData.luma(mean = 0.12f)) // much too dark
        val item = ExposureRule.exposure.check(TestData.input(a, cam = manual, focal = 50))!!
        assertTrue(item.severity == Severity.WARN)
        val m = item.message
        assertTrue(m, m.contains("slow the shutter"))
        val iShutter = m.indexOf("slow the shutter")
        val iAperture = m.indexOf("open the aperture")
        assertTrue("shutter should come before aperture: $m", iAperture == -1 || iShutter < iAperture)
    }

    @Test
    fun dark_manual_neverSlowerThanSafeShutter() {
        val a = TestData.result(luma = TestData.luma(mean = 0.05f))
        val m = ExposureRule.exposure.check(TestData.input(a, cam = manual, focal = 50))!!.message
        // safe shutter at 50mm is about 1/75; must not suggest something slower like 1/30
        assertTrue(m, !m.contains("1/30") && !m.contains("1/15"))
    }

    @Test
    fun bright_manual_lowersIsoFirst() {
        val cam = manual.copy(iso = "800", shutter = "1/125")
        val a = TestData.result(luma = TestData.luma(mean = 0.85f))
        val m = ExposureRule.exposure.check(TestData.input(a, cam = cam))!!.message
        assertTrue(m, m.contains("lower ISO to"))
        assertTrue(m.indexOf("lower ISO to") < (m.indexOf("speed the shutter").takeIf { it >= 0 } ?: Int.MAX_VALUE))
    }

    @Test
    fun dark_autoIso_suggestsRaisingIsoLimit() {
        val cam = CameraState(exposureMode = "Aperture", fNumber = "2.8", shutter = "1/60", iso = "AUTO")
        val m = ExposureRule.exposure.check(TestData.input(TestData.result(luma = TestData.luma(mean = 0.15f)), cam = cam))!!.message
        assertTrue(m, m.contains("ISO AUTO"))
    }
}
