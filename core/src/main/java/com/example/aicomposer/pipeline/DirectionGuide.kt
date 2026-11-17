package com.example.aicomposer.pipeline

import com.example.aicomposer.analysis.CompositionAdvice
import com.example.aicomposer.analysis.P
import com.example.aicomposer.coach.Cfg
import com.example.aicomposer.coach.CoachEngine
import com.example.aicomposer.coach.CoachReport
import com.example.aicomposer.coach.Severity
import kotlin.math.abs
import kotlin.math.sign

/**
 * Which way to turn the camera, as the arrow on the viewfinder points.
 * dx: +1 = pan right, -1 = pan left; dy: +1 = tilt down, -1 = tilt up (image y points down); 0 = no arrow.
 */
data class CameraTurn(val dx: Int = 0, val dy: Int = 0) {
    val none: Boolean get() = dx == 0 && dy == 0

    companion object {
        val NONE = CameraTurn()
    }
}

/** What the viewfinder shows: the arrow, and a counter that goes up once each time the framing falls into place. */
data class GuideState(val turn: CameraTurn = CameraTurn.NONE, val inPlaceCount: Int = 0)

/**
 * Direction arrows for the viewfinder.
 *
 * The direction comes from the rules that want the subject to move ([com.example.aicomposer.coach.CheckItem.move]),
 * after temporal smoothing, so the arrows agree with the hints and do not flicker. When several rules want a
 * move, the engine's move priority decides (the same order used for conflicts). Arrows show the camera move,
 * which is opposite to the subject move: the subject must go left in the frame -> turn the camera right.
 *
 * "In place": after an arrow has been shown for a while ([Cfg] guide.min_guided_ms) and then disappears and stays
 * away (guide.settle_ms) with the subject still in the frame, [GuideState.inPlaceCount] goes up by one; the UI
 * gives a short vibration for it. At most once per guide.cooldown_ms, so it never buzzes repeatedly.
 */
class DirectionGuide {
    private var turn = CameraTurn.NONE
    private var guidedSince: Long? = null
    private var guidedEnough = false
    private var settledSince: Long? = null
    private var lastBuzz = Long.MIN_VALUE / 2
    private var count = 0

    fun reset() {
        turn = CameraTurn.NONE
        guidedSince = null
        guidedEnough = false
        settledSince = null
    }

    fun update(report: CoachReport?, composition: CompositionAdvice?, now: Long = System.currentTimeMillis()): GuideState {
        val hasSubject = composition?.subjectCenter != null
        turn = turnFor(wantedMove(report, composition), turn)
        if (!turn.none) {
            val since = guidedSince ?: now.also { guidedSince = it }
            if (now - since >= Cfg.d("guide.min_guided_ms")) guidedEnough = true
            settledSince = null
        } else {
            guidedSince = null
            if (guidedEnough && hasSubject) {
                val since = settledSince ?: now.also { settledSince = it }
                if (now - since >= Cfg.d("guide.settle_ms")) {
                    if (now - lastBuzz >= Cfg.d("guide.cooldown_ms")) {
                        count++
                        lastBuzz = now
                    }
                    guidedEnough = false
                    settledSince = null
                }
            } else {
                // Subject left the frame: that is not "in place"
                guidedEnough = false
                settledSince = null
            }
        }
        return GuideState(turn, count)
    }

    companion object {
        /**
         * The subject move (frame coordinates, y down) the coach currently asks for, or null.
         * Uses the reported problems that carry a move; without a report, falls back to subject -> thirds target.
         */
        fun wantedMove(report: CoachReport?, composition: CompositionAdvice?): P? {
            if (report == null) {
                val s = composition?.subjectCenter ?: return null
                val t = composition.target ?: return null
                return P(t.x - s.x, t.y - s.y)
            }
            return report.problems
                .filter { it.move != null && it.severity != Severity.TIP && it.severity != Severity.IGNORED }
                .minByOrNull { CoachEngine.movePriority(it.id) }
                ?.move
        }

        /**
         * Camera turn for a wanted subject move, per axis with hysteresis: an axis turns on at guide.show and
         * off below guide.hide, so an offset hovering near the threshold does not make the arrow blink.
         */
        fun turnFor(move: P?, prev: CameraTurn = CameraTurn.NONE): CameraTurn {
            if (move == null) return CameraTurn.NONE
            val show = Cfg.f("guide.show")
            val hide = Cfg.f("guide.hide")
            fun axis(m: Float, was: Int): Int {
                val cam = -sign(m).toInt() // camera turns opposite to the subject move
                return when {
                    abs(m) >= show -> cam
                    was != 0 && was == cam && abs(m) >= hide -> cam
                    else -> 0
                }
            }
            return CameraTurn(axis(move.x, prev.dx), axis(move.y, prev.dy))
        }
    }
}
