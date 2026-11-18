package com.example.aicomposer

import com.example.aicomposer.analysis.NRect
import com.example.aicomposer.analysis.Subject
import com.example.aicomposer.analysis.SubjectKind
import com.example.aicomposer.coach.CoachEngine
import com.example.aicomposer.coach.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuessedSubjectTest {
    private fun sizeItem(guessed: Boolean) = CoachEngine.evaluate(
        TestData.input(TestData.result(main = Subject(NRect(0.4f, 0.4f, 0.45f, 0.45f), SubjectKind.GENERIC, false, guessed = guessed)))
    ).first { it.id == "size" }

    @Test
    fun recognizedSubjectKeepsFullPenalty() {
        val item = sizeItem(guessed = false)
        assertEquals(Severity.WARN, item.severity)
        assertEquals(12f, item.penalty, 0.01f)
    }

    @Test
    fun guessedSubjectIsSoftened() {
        val item = sizeItem(guessed = true)
        assertEquals(Severity.INFO, item.severity)
        assertTrue(item.penalty < 12f * 0.5f)
        assertTrue(item.message.startsWith("（") || item.message.startsWith("("))
    }
}
