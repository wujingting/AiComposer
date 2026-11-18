package com.example.aicomposer

import com.example.aicomposer.analysis.CompositionAdvisor
import com.example.aicomposer.analysis.NRect
import com.example.aicomposer.analysis.Subject
import com.example.aicomposer.analysis.SubjectKind
import com.example.aicomposer.analysis.isPeopleShot
import com.example.aicomposer.coach.CoachEngine
import com.example.aicomposer.coach.CoachInput
import com.example.aicomposer.coach.Severity
import com.example.aicomposer.analysis.CropPlan
import com.example.aicomposer.camera.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A person sitting in the middle with a cat on each side (centered half-body shot). */
class PeopleShotTest {
    private val face = Subject(NRect(0.38f, 0.15f, 0.62f, 0.38f), SubjectKind.PERSON, true)
    private val body = Subject(NRect(0.07f, 0.05f, 0.79f, 1.0f), SubjectKind.PERSON, false, name = "人物", score = 0.9f)
    private val catL = Subject(NRect(0.05f, 0.55f, 0.38f, 0.90f), SubjectKind.GENERIC, false, name = "猫", score = 0.9f)
    private val catR = Subject(NRect(0.67f, 0.49f, 0.94f, 0.84f), SubjectKind.GENERIC, false, name = "猫", score = 0.9f)

    private fun items(withBody: Boolean): Map<String, com.example.aicomposer.coach.CheckItem> {
        val subjects = listOf(face, catL, catR) + if (withBody) listOf(body) else emptyList()
        val a = TestData.result(main = face).copy(subjects = subjects)
        val comp = CompositionAdvisor.advise(a)
        return CoachEngine.evaluate(CoachInput(a, comp, CropPlan(), CameraState(), null, 50, null)).associateBy { it.id }
    }

    @Test
    fun centeredHalfBodyIsFine() {
        val a = TestData.result(main = face).copy(subjects = listOf(face, body))
        assertTrue(a.isPeopleShot())
        assertEquals(Severity.OK, items(withBody = true).getValue("placement").severity)
    }

    @Test
    fun clutterLooksAroundTheWholeBody() {
        // The body fills more than half the frame: nothing around it to judge
        assertNull(items(withBody = true)["clutter"])
    }

    @Test
    fun headShotStillUsesThirdsPoints() {
        val a = TestData.result(main = face)
        assertFalse(a.isPeopleShot())
        assertTrue(items(withBody = false).getValue("placement").severity != Severity.OK)
    }
}
