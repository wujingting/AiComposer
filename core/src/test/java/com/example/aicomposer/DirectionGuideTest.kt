package com.example.aicomposer

import com.example.aicomposer.analysis.CompositionAdvice
import com.example.aicomposer.analysis.P
import com.example.aicomposer.coach.Category
import com.example.aicomposer.coach.CheckItem
import com.example.aicomposer.coach.CoachReport
import com.example.aicomposer.coach.Severity
import com.example.aicomposer.pipeline.CameraTurn
import com.example.aicomposer.pipeline.DirectionGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionGuideTest {
    private fun mover(id: String, move: P, sev: Severity = Severity.WARN) =
        CheckItem(id, id, Category.COMPOSITION, sev, "", move = move)

    private fun report(vararg items: CheckItem) = CoachReport(80, items.toList())

    private val withSubject = CompositionAdvice(80, P(0.5f, 0.5f), null, emptyList())
    private val noSubject = CompositionAdvice(80, null, null, emptyList())

    // --- direction ---

    @Test
    fun cameraTurnsOppositeToTheSubjectMove() {
        // Subject must go left -> pan right; subject must go up -> tilt down (arrow points down)
        assertEquals(CameraTurn(dx = 1, dy = 0), DirectionGuide.turnFor(P(-0.1f, 0f)))
        assertEquals(CameraTurn(dx = -1, dy = 0), DirectionGuide.turnFor(P(0.1f, 0f)))
        assertEquals(CameraTurn(dx = 0, dy = 1), DirectionGuide.turnFor(P(0f, -0.1f)))
        assertEquals(CameraTurn(dx = 0, dy = -1), DirectionGuide.turnFor(P(0f, 0.1f)))
        assertEquals(CameraTurn(dx = 1, dy = -1), DirectionGuide.turnFor(P(-0.2f, 0.2f)))
    }

    @Test
    fun smallOffsets_showNoArrow() {
        assertTrue(DirectionGuide.turnFor(P(0.02f, -0.02f)).none)
        assertTrue(DirectionGuide.turnFor(null).none)
    }

    @Test
    fun hysteresis_keepsArrowBetweenHideAndShow_onlyIfAlreadyShown() {
        val between = P(-0.04f, 0f) // guide.hide 0.03 < 0.04 < guide.show 0.05
        assertTrue(DirectionGuide.turnFor(between, CameraTurn.NONE).none)
        assertEquals(CameraTurn(1, 0), DirectionGuide.turnFor(between, CameraTurn(1, 0)))
        // An arrow in the other direction does not count as "already shown"
        assertTrue(DirectionGuide.turnFor(between, CameraTurn(-1, 0)).none)
        assertTrue(DirectionGuide.turnFor(P(-0.02f, 0f), CameraTurn(1, 0)).none)
    }

    @Test
    fun wantedMove_followsEnginePriority_andSkipsTips() {
        val place = mover("placement", P(0.2f, 0f))
        val lead = mover("lead_room", P(-0.1f, 0f))
        assertEquals(P(-0.1f, 0f), DirectionGuide.wantedMove(report(place, lead), withSubject))
        // A demoted (TIP) mover is not followed
        val demoted = mover("lead_room", P(-0.1f, 0f), Severity.TIP)
        assertEquals(P(0.2f, 0f), DirectionGuide.wantedMove(report(place, demoted), withSubject))
        assertNull(DirectionGuide.wantedMove(report(mover("tilt", P(0f, 0f)).copy(move = null)), withSubject))
    }

    @Test
    fun wantedMove_withoutReport_usesSubjectToTarget() {
        val comp = CompositionAdvice(50, P(0.5f, 0.5f), P(0.333f, 0.333f), emptyList())
        val m = DirectionGuide.wantedMove(null, comp)!!
        assertEquals(-0.167f, m.x, 0.001f)
        assertEquals(-0.167f, m.y, 0.001f)
    }

    // --- "in place" vibration ---

    private val off = report(mover("placement", P(-0.15f, 0f)))
    private val inPlace = report()

    @Test
    fun guidedThenInPlace_countsOnce_afterSettling() {
        val g = DirectionGuide()
        var t = 0L
        repeat(4) { assertEquals(0, g.update(off, withSubject, t).inPlaceCount); t += 250 } // arrow for 1 s
        assertEquals(CameraTurn(1, 0), g.update(off, withSubject, t).turn)
        t += 250
        assertEquals(0, g.update(inPlace, withSubject, t).inPlaceCount) // just arrived: not settled yet
        t += 250
        assertEquals(0, g.update(inPlace, withSubject, t).inPlaceCount)
        t += 250
        assertEquals(1, g.update(inPlace, withSubject, t).inPlaceCount) // settled 500 ms >= 400 ms
        // Staying in place does not buzz again
        repeat(10) { t += 250; assertEquals(1, g.update(inPlace, withSubject, t).inPlaceCount) }
    }

    @Test
    fun shortFlicker_doesNotBuzz() {
        val g = DirectionGuide()
        var t = 0L
        g.update(off, withSubject, t) // one frame with an arrow (shorter than guide.min_guided_ms)
        repeat(8) { t += 250; assertEquals(0, g.update(inPlace, withSubject, t).inPlaceCount) }
    }

    @Test
    fun subjectLeavingTheFrame_isNotInPlace() {
        val g = DirectionGuide()
        var t = 0L
        repeat(5) { g.update(off, withSubject, t); t += 250 }
        repeat(8) { assertEquals(0, g.update(inPlace, noSubject, t).inPlaceCount); t += 250 }
    }

    @Test
    fun cooldown_limitsBuzzing() {
        val g = DirectionGuide()
        var t = 0L
        fun cycle(): Int {
            repeat(4) { g.update(off, withSubject, t); t += 250 }
            var n = 0
            repeat(3) { n = g.update(inPlace, withSubject, t).inPlaceCount; t += 250 }
            return n
        }
        assertEquals(1, cycle())   // t ≈ 1.6 s
        assertEquals(1, cycle())   // second alignment within 4 s: no buzz
        repeat(3) { cycle() }      // time passes
        assertTrue(cycle() >= 2)   // after the cooldown it buzzes again
    }
}
