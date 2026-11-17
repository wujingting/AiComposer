package com.example.aicomposer

import com.example.aicomposer.coach.Category
import com.example.aicomposer.coach.CheckItem
import com.example.aicomposer.coach.CoachEngine
import com.example.aicomposer.coach.Part
import com.example.aicomposer.coach.Severity
import com.example.aicomposer.coach.TemporalFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupingTemporalTest {
    private fun warn(id: String, name: String = id, cat: Category = Category.COMPOSITION) =
        CheckItem(id, name, cat, Severity.WARN, "problem $id")

    private fun ok(id: String, cat: Category = Category.COMPOSITION) =
        CheckItem(id, id, cat, Severity.OK, "passed $id")

    @Test
    fun sameRootCause_onlyCountedOnce() {
        val out = CoachEngine.mergeGroups(listOf(warn("clutter"), warn("separation"), warn("size")))
        assertEquals(1, out.count { it.penalty > 0f })
        assertEquals(2, out.count { it.mergedInto != null })
    }

    @Test
    fun differentGroups_bothCounted() {
        val out = CoachEngine.mergeGroups(listOf(warn("clutter"), warn("tilt")))
        assertEquals(2, out.count { it.penalty > 0f })
    }

    @Test
    fun partScores_areSeparated() {
        val rep = CoachEngine.report(listOf(warn("tilt"), ok("clipping", Category.LIGHT)))
        assertTrue(rep.part(Part.COMPOSITION) < 100)
        assertEquals(100, rep.part(Part.LIGHT))
        assertEquals(100, rep.part(Part.TECH))
    }

    @Test
    fun temporal_oneFrameBlip_isNotReported() {
        val f = TemporalFilter()
        f.filter(listOf(ok("tilt")), 0)
        f.filter(listOf(ok("tilt")), 250)
        val out = f.filter(listOf(warn("tilt")), 500).first()
        assertEquals(Severity.OK, out.severity) // seen only once: not reported, show the last passed result
    }

    @Test
    fun temporal_persistentProblem_isReported_andHasHysteresis() {
        val f = TemporalFilter()
        f.filter(listOf(warn("tilt")), 0)
        f.filter(listOf(warn("tilt")), 250)
        val shown = f.filter(listOf(warn("tilt")), 500).first()
        assertEquals(Severity.WARN, shown.severity)
        // problem gone for one frame: still in hysteresis, keep showing it
        val still = f.filter(listOf(ok("tilt")), 750).first()
        assertEquals(Severity.WARN, still.severity)
        // removed only after it stays gone
        f.filter(listOf(ok("tilt")), 1000)
        f.filter(listOf(ok("tilt")), 1250)
        f.filter(listOf(ok("tilt")), 1500)
        val gone = f.filter(listOf(ok("tilt")), 1750).first()
        assertEquals(Severity.OK, gone.severity)
    }

    @Test
    fun temporal_newProblemWithoutHistory_isHidden() {
        val f = TemporalFilter()
        val out = f.filter(listOf(warn("tilt")), 0)
        assertTrue(out.isEmpty()) // first frame and no passed result yet: show nothing
    }

    @Test
    fun report_problemsSortMergedLast() {
        val rep = CoachEngine.report(listOf(warn("clutter"), warn("separation"), warn("tilt")))
        assertNull(rep.problems.first().mergedInto)
        assertNotNull(rep.problems.last().mergedInto)
    }
}
