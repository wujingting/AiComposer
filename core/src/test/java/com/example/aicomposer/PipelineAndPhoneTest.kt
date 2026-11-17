package com.example.aicomposer

import com.example.aicomposer.analysis.CompositionAdvice
import com.example.aicomposer.analysis.CropPlan
import com.example.aicomposer.analysis.SceneType
import com.example.aicomposer.camera.CameraKind
import com.example.aicomposer.camera.CameraState
import com.example.aicomposer.coach.Category
import com.example.aicomposer.coach.CheckItem
import com.example.aicomposer.coach.ExposureRule
import com.example.aicomposer.coach.FocusRules
import com.example.aicomposer.coach.LightRules
import com.example.aicomposer.coach.PortraitRules
import com.example.aicomposer.coach.Severity
import com.example.aicomposer.i18n.I18n
import com.example.aicomposer.i18n.Lang
import com.example.aicomposer.pipeline.AutoShutterGate
import com.example.aicomposer.pipeline.CropMode
import com.example.aicomposer.pipeline.PhotoRanking
import com.example.aicomposer.pipeline.combinedScore
import com.example.aicomposer.pipeline.effectivePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PipelineAndPhoneTest {
    @Before
    fun english() { I18n.lang = Lang.EN }

    private val phone = CameraState(kind = CameraKind.PHONE)

    // --- pipeline helpers ---

    @Test
    fun effectivePlan_followsMode() {
        val manual = CropPlan(0.6f, 1f)
        assertEquals(CropPlan(), effectivePlan(CropMode.OFF, manual, 0.7f))
        assertEquals(CropPlan(0.7f, 1f), effectivePlan(CropMode.AUTO, manual, 0.7f))
        assertEquals(manual, effectivePlan(CropMode.MANUAL, manual, 0.7f))
    }

    @Test
    fun combinedScore_averagesWithAestheticOnlyWhenAvailable() {
        val comp = CompositionAdvice(80, null, null, emptyList())
        assertNull(combinedScore(null, null))
        assertEquals(80, combinedScore(comp, null))
    }

    @Test
    fun autoShutter_needsAStreak_respectsInterval_andIsBlockedByPortraitErrors() {
        val gate = AutoShutterGate(streakNeeded = 3, minIntervalMs = 4000)
        val none = emptyList<CheckItem>()
        assertFalse(gate.update(90, 80, none, now = 10_000))
        assertFalse(gate.update(90, 80, none, now = 10_250))
        assertTrue(gate.update(90, 80, none, now = 10_500))
        gate.lastShotAt = 10_500

        // Streak again, but too soon after the last shot
        repeat(3) { assertFalse(gate.update(90, 80, none, now = 11_000L + it)) }

        // Closed eyes in this very frame blocks it, even long after the last shot
        val eyes = listOf(CheckItem("eyes", "Closed eyes", Category.PORTRAIT, Severity.ERROR, ""))
        repeat(5) { assertFalse(gate.update(95, 80, eyes, now = 30_000L + it)) }
        // A light problem does not block
        val light = listOf(CheckItem("face_clip", "x", Category.LIGHT, Severity.ERROR, ""))
        assertFalse(gate.update(95, 80, light, now = 40_000))
        assertFalse(gate.update(95, 80, light, now = 40_001))
        assertTrue(gate.update(95, 80, light, now = 40_002))
        // Below the threshold resets the streak
        gate.lastShotAt = 0
        assertFalse(gate.update(95, 80, none, now = 50_000))
        assertFalse(gate.update(70, 80, none, now = 50_001))
        assertFalse(gate.update(95, 80, none, now = 50_002))
    }

    // --- phone variants of camera-specific advice ---

    @Test
    fun exposure_onPhone_suggestsExposureCompensation_notIsoOrAperture() {
        val a = TestData.result(luma = TestData.luma(mean = 0.12f))
        val m = ExposureRule.exposure.check(TestData.input(a, cam = phone, focal = 17))!!.message
        assertTrue(m, m.contains("exposure compensation"))
        assertFalse(m, m.contains("ISO"))
        assertFalse(m, m.contains("aperture"))
    }

    @Test
    fun distortion_onPhone_suggestsZoomingNot50mm() {
        val face = TestData.face(0.3f, 0.2f, 0.45f)
        val a = TestData.result(faces = listOf(face), scene = SceneType.PORTRAIT)
        val phoneMsg = PortraitRules.distortion.check(TestData.input(a, cam = phone, focal = 17))!!.message
        val sonyMsg = PortraitRules.distortion.check(TestData.input(a, focal = 35))!!.message
        assertTrue(phoneMsg, phoneMsg.contains("2x"))
        assertTrue(sonyMsg, sonyMsg.contains("50mm"))
    }

    @Test
    fun eyeAfTip_isSonyOnly() {
        val face = TestData.face(0.3f, 0.2f, 0.2f)
        val a = TestData.result(faces = listOf(face), scene = SceneType.PORTRAIT)
        assertNull(FocusRules.afMode.check(TestData.input(a, cam = phone.copy(fNumber = "1.8"))))
        assertTrue(FocusRules.afMode.check(TestData.input(a, cam = CameraState(fNumber = "1.8")))!!.message.contains("Eye AF"))
    }

    @Test
    fun depthOfField_isSkippedWithoutAperture_asOnPhones() {
        val face = TestData.face(0.3f, 0.2f, 0.2f)
        val a = TestData.result(faces = listOf(face), scene = SceneType.PORTRAIT)
        assertNull(FocusRules.depthOfField.check(TestData.input(a, cam = phone)))
    }

    @Test
    fun lowLight_onPhone_doesNotTalkAboutAperture() {
        val a = TestData.result(scene = SceneType.NIGHT)
        val m = LightRules.lowLight.check(TestData.input(a, cam = phone))!!.message
        assertFalse(m, m.contains("aperture"))
    }

    @Test
    fun exposure_onTakenPhoto_suggestsEditingAndNextTime() {
        val a = TestData.result(luma = TestData.luma(mean = 0.12f))
        val m = ExposureRule.exposure.check(TestData.input(a, cam = CameraState(kind = CameraKind.PHOTO)))!!.message
        assertTrue(m, m.contains("editing"))
        assertFalse(m, m.contains("Settings"))
    }

    @Test
    fun ranking_picksTopPercent_andSkipsNearDuplicates() {
        fun v(vararg x: Float): FloatArray { val n = kotlin.math.sqrt(x.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(x.size) { x[it] / n } }
        val items = listOf(
            PhotoRanking.Item(1, 7.0f, v(1f, 0.01f, 0f)),
            PhotoRanking.Item(2, 6.9f, v(1f, 0.02f, 0f)), // burst twin of 1
            PhotoRanking.Item(3, 6.0f, v(0f, 1f, 0f)),
            PhotoRanking.Item(4, 5.0f, v(0f, 0f, 1f)),
            PhotoRanking.Item(5, 4.0f, null),
        )
        val r = PhotoRanking.rank(items, topPercent = 40f)
        assertEquals(listOf(1, 3), r.picks)
        assertEquals(mapOf(2 to 1), r.duplicateOf)
        assertTrue(PhotoRanking.rank(emptyList()).picks.isEmpty())

        // A burst pair below the quota is still marked as similar
        val low = items + listOf(PhotoRanking.Item(6, 3.0f, v(0f, 0f, 1f)))
        val r2 = PhotoRanking.rank(low, topPercent = 10f)
        assertEquals(listOf(1), r2.picks)
        assertEquals(mapOf(2 to 1, 6 to 4), r2.duplicateOf)
    }

    @Test
    fun phoneEvStep_overridesSonyMapping() {
        assertEquals(1.0 / 3.0, CameraState(evStep = 1).evStepSize, 1e-9)
        assertEquals(0.5, CameraState(evStep = 2).evStepSize, 1e-9)
        assertEquals(1.0 / 6.0, phone.copy(evStepEv = 1.0 / 6.0, evIndex = 3).evStepSize, 1e-9)
        assertEquals(0.5, phone.copy(evStepEv = 1.0 / 6.0, evIndex = 3).evValue!!, 1e-9)
    }
}
