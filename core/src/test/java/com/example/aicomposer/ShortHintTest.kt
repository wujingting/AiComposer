package com.example.aicomposer

import com.example.aicomposer.analysis.CompositionAdvice
import com.example.aicomposer.analysis.CropPlan
import com.example.aicomposer.analysis.NRect
import com.example.aicomposer.analysis.P
import com.example.aicomposer.analysis.SceneType
import com.example.aicomposer.analysis.Subject
import com.example.aicomposer.analysis.SubjectKind
import com.example.aicomposer.camera.CameraKind
import com.example.aicomposer.camera.CameraState
import com.example.aicomposer.coach.CoachEngine
import com.example.aicomposer.coach.CoachInput
import com.example.aicomposer.coach.CompositionRules
import com.example.aicomposer.coach.Severity
import com.example.aicomposer.i18n.I18n
import com.example.aicomposer.i18n.Lang
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every problem / tip has a short phrase for the hint strip, in all three languages, short enough for one line. */
class ShortHintTest {
    @After
    fun reset() { I18n.lang = Lang.EN }

    private val person = Subject(NRect(0.0f, 0.30f, 0.12f, 0.80f), SubjectKind.PERSON, false)
    private val tiny = Subject(NRect(0.45f, 0.45f, 0.5f, 0.5f), SubjectKind.GENERIC, false)

    /** Inputs chosen to trigger as many different rules as possible without image features. */
    private fun inputs(): List<CoachInput> = listOf(
        // No subject, tilted, dark, slow shutter, high ISO
        TestData.input(
            TestData.result(tilt = 4f, luma = TestData.luma(mean = 0.1f, hi = 0.08f, lo = 0.2f)),
            cam = CameraState(shutter = "1/8", iso = "12800", fNumber = "5.6", exposureMode = "Manual Exposure"),
        ),
        // Person cut off at the left, off the thirds point
        TestData.input(
            TestData.result(main = person),
            comp = CompositionAdvice(50, P(0.07f, 0.55f), P(0.333f, 0.333f), emptyList()),
        ),
        // Tiny subject, bright frame
        TestData.input(TestData.result(main = tiny, luma = TestData.luma(mean = 0.9f, hi = 0.3f))),
        // Close face: eyes closed, looking away, head cut, wide angle, thin depth of field, on a Sony
        TestData.input(
            TestData.result(
                faces = listOf(TestData.face(0.05f, 0.0f, 0.45f, yaw = 35f, eyesOpen = 0.05f, smile = 0.01f)),
                scene = SceneType.PORTRAIT,
            ),
            cam = CameraState(fNumber = "1.8"), focal = 35,
        ),
        // Group, two rows, not enough depth of field; on a phone
        TestData.input(
            TestData.result(
                faces = listOf(TestData.face(0.2f, 0.2f, 0.3f, eyesOpen = 0.05f), TestData.face(0.6f, 0.5f, 0.1f)),
                motion = 0.3f,
            ),
            cam = CameraState(kind = CameraKind.PHONE, fNumber = "1.8"), focal = 17,
        ),
        // Planned crop down to few pixels; action scene
        TestData.input(
            TestData.result(main = tiny, scene = SceneType.ACTION),
            plan = CropPlan(0.4f, 1.5f),
        ),
        // Night
        TestData.input(TestData.result(scene = SceneType.NIGHT, luma = TestData.luma(mean = 0.05f))),
    )

    /** Display width: CJK counts double. */
    private fun width(s: String): Int = s.fold(0) { n, c -> n + if (c.code >= 0x2E80) 2 else 1 }

    @Test
    fun everyProblemAndTip_hasAShortPhrase_thatFitsOneLine() {
        var checked = 0
        for (lang in Lang.entries) {
            I18n.lang = lang
            for (input in inputs()) {
                for (item in CoachEngine.evaluate(input)) {
                    if (item.severity == Severity.OK || item.severity == Severity.IGNORED) continue
                    checked++
                    val s = item.short
                    assertNotNull("${item.id} ($lang) has no short phrase: ${item.message}", s)
                    assertTrue("${item.id} ($lang) short phrase too long: \"$s\"", width(s!!) <= 30)
                    assertTrue("${item.id} ($lang) short is not shorter than the message", s.length < item.message.length)
                }
            }
        }
        assertTrue("too few rules triggered ($checked)", checked >= 3 * 20)
    }

    @Test
    fun brief_prefersShort_andFallsBackToMessage() {
        val item = CompositionRules.tilt.check(TestData.input(TestData.result(tilt = 3f)))!!
        assertEquals("Horizon dips right 3.0°", item.brief)
        assertEquals(item.message, item.copy(short = null).brief)
    }

    @Test
    fun shortPhrases_followLanguage() {
        val input = TestData.input(TestData.result(main = person))
        val shorts = Lang.entries.map { I18n.lang = it; CompositionRules.edgeCut.check(input)!!.short }
        assertEquals(listOf("人物在左边被切", "人物が左端で切れている", "Person cut off at left"), shorts)
    }
}
