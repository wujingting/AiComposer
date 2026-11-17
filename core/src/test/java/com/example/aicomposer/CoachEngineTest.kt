package com.example.aicomposer

import com.example.aicomposer.analysis.NRect
import com.example.aicomposer.analysis.P
import com.example.aicomposer.analysis.SceneType
import com.example.aicomposer.analysis.Subject
import com.example.aicomposer.analysis.SubjectKind
import com.example.aicomposer.coach.Category
import com.example.aicomposer.coach.CheckItem
import com.example.aicomposer.coach.CoachEngine
import com.example.aicomposer.coach.CompositionRules
import com.example.aicomposer.coach.LightRules
import com.example.aicomposer.coach.PortraitRules
import com.example.aicomposer.coach.Severity
import com.example.aicomposer.camera.CameraState
import com.example.aicomposer.coach.FocusRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.example.aicomposer.i18n.I18n
import com.example.aicomposer.i18n.Lang
import org.junit.Before
import org.junit.Test

class CoachEngineTest {
    @Before
    fun english() { I18n.lang = Lang.EN }

    @Test
    fun tilt_threeDegrees_isWarning() {
        val item = CompositionRules.tilt.check(TestData.input(TestData.result(tilt = 3f)))!!
        assertEquals(Severity.WARN, item.severity)
        assertTrue(item.message.contains("lower on the right"))
    }

    @Test
    fun tilt_halfDegree_isOk() {
        assertEquals(Severity.OK, CompositionRules.tilt.check(TestData.input(TestData.result(tilt = 0.5f)))!!.severity)
    }

    @Test
    fun eyesClosed_isError() {
        val faces = listOf(TestData.face(0.4f, 0.3f, 0.2f, eyesOpen = 0.1f))
        assertEquals(Severity.ERROR, PortraitRules.eyesClosed.check(TestData.input(TestData.result(faces = faces)))!!.severity)
    }

    @Test
    fun backlight_darkSubjectWithBrightSky_isWarning() {
        val subj = Subject(NRect(0.4f, 0.4f, 0.6f, 0.8f), SubjectKind.PERSON, false)
        val a = TestData.result(main = subj, luma = TestData.luma(mean = 0.6f, hi = 0.05f, subj = 0.2f))
        assertEquals(Severity.WARN, LightRules.backlight.check(TestData.input(a))!!.severity)
    }

    @Test
    fun slowShutter_isError() {
        val cam = CameraState(shutter = "1/15")
        val item = FocusRules.shutter.check(TestData.input(TestData.result(), cam = cam, focal = 50))!!
        assertEquals(Severity.ERROR, item.severity)
    }

    @Test
    fun conflict_lowerPriorityIsDemoted() {
        val lead = CheckItem("lead_room", "Look room", Category.COMPOSITION, Severity.WARN, "go right", move = P(-0.1f, 0f))
        val place = CheckItem("placement", "Subject position", Category.COMPOSITION, Severity.WARN, "go left", move = P(0.2f, 0f))
        val out = CoachEngine.resolveConflicts(listOf(place, lead))
        assertEquals(Severity.WARN, out.first { it.id == "lead_room" }.severity)
        val demoted = out.first { it.id == "placement" }
        assertEquals(Severity.TIP, demoted.severity)
        assertEquals(0f, demoted.penalty, 0f)
    }

    @Test
    fun conflict_sameDirectionBothKept() {
        val a = CheckItem("lead_room", "Look room", Category.COMPOSITION, Severity.WARN, "", move = P(-0.1f, 0f))
        val b = CheckItem("placement", "Subject position", Category.COMPOSITION, Severity.WARN, "", move = P(-0.2f, 0f))
        assertTrue(CoachEngine.resolveConflicts(listOf(a, b)).all { it.severity == Severity.WARN })
    }

    @Test
    fun ignoredRule_doesNotCostPoints() {
        val a = TestData.result(tilt = 5f, scene = SceneType.GENERAL)
        val normal = CoachEngine.run(TestData.input(a))
        val ignored = CoachEngine.run(TestData.input(a), setOf("GENERAL:tilt"))
        assertTrue(ignored.score > normal.score)
        assertEquals(Severity.IGNORED, ignored.items.first { it.id == "tilt" }.severity)
    }

    @Test
    fun score_decreasesWithProblems() {
        val clean = CoachEngine.run(TestData.input(TestData.result()))
        val bad = CoachEngine.run(TestData.input(TestData.result(tilt = 6f, luma = TestData.luma(hi = 0.1f))))
        assertTrue(clean.score > bad.score)
        assertTrue(bad.score in 1..99)
    }
}
