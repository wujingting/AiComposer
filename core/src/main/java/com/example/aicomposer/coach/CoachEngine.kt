package com.example.aicomposer.coach

import com.example.aicomposer.i18n.tr
import com.example.aicomposer.analysis.P
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Photography coaching engine. Pipeline:
 *
 *   evaluate()  run all rules -> items the user marked as "intentional" cost nothing -> resolve direction conflicts
 *   (TemporalFilter: keep only persistent problems, see TemporalFilter.kt)
 *   report()    root-cause grouping (one penalty per group) -> total score and part scores
 *
 * score = 100 × e^(−penalty / score.scale). Default scale=60: penalty 12 -> 82; 25 -> 66; 50 -> 43.
 * New rules: write a Rule in any *Rules.kt and add it to that file's all list.
 */
object CoachEngine {
    private val rules: List<Rule> =
        CompositionRules.all + LightRules.all + ExposureRule.all + FocusRules.all +
            PortraitRules.all + LandscapeRules.all + SnapRules.all + AestheticRules.all

    /** Priority of move-type advice: earlier wins (advice expressing intent > generic composition rules). */
    private val MOVE_PRIORITY = listOf("lead_room", "headroom", "joint_cut", "edge_cut", "text_cut", "eye_line", "placement")

    /** Rank of a move-type rule (lower wins); rules not in the list come last. */
    fun movePriority(id: String): Int = MOVE_PRIORITY.indexOf(id).let { i -> if (i < 0) 99 else i }

    /**
     * Root-cause groups: problems in one group usually have the same cause, so only the worst one costs points.
     */
    val GROUPS: Map<String, String> = mapOf(
        // Subject does not stand out (too small, busy background, similar colors and imbalance often come together)
        "size" to "prominence", "closer" to "prominence", "simplicity" to "prominence", "clutter" to "prominence", "separation" to "prominence", "balance" to "prominence",
        // Where the subject sits in the frame
        "placement" to "position", "eye_line" to "position", "lead_room" to "position", "headroom" to "position",
        // Highlights / contrast
        "exposure" to "exposure", "clipping" to "exposure", "dynamic_range" to "exposure", "backlight" to "exposure", "face_clip" to "exposure",
        // Camera not level
        "tilt" to "camera_angle", "verticals" to "camera_angle", "text_tilt" to "camera_angle",
        // Will be blurry
        "shutter" to "sharpness", "shake" to "sharpness", "focus" to "sharpness",
    )

    /** Convenience: get a report in one step (no temporal smoothing). */
    fun run(c: CoachInput, ignored: Set<String> = emptySet()): CoachReport = report(evaluate(c, ignored))

    /**
     * @param ignored rules ignored by the user, as "scene:ruleId" (e.g. "PORTRAIT:placement"), or "*:ruleId" for all scenes
     */
    fun evaluate(c: CoachInput, ignored: Set<String> = emptySet()): List<CheckItem> {
        val scene = c.a.scene.name
        val raw = rules.mapNotNull { r -> runCatching { r.check(c) }.getOrNull() }
        val withIgnore = raw.map { item ->
            if (item.severity != Severity.OK && ("$scene:${item.id}" in ignored || "*:${item.id}" in ignored)) {
                item.copy(severity = Severity.IGNORED, penalty = 0f, move = null)
            } else item
        }
        return resolveConflicts(withIgnore)
    }

    /** Group de-duplication + scoring. */
    fun report(items: List<CheckItem>): CoachReport {
        val grouped = mergeGroups(items)
        val scale = Cfg.d("score.scale")
        fun score(list: List<CheckItem>): Int =
            (100 * exp(-list.sumOf { it.penalty.toDouble() } / scale)).roundToInt().coerceIn(0, 100)
        val parts = Part.entries.associateWith { p -> score(grouped.filter { it.category.part() == p }) }
        return CoachReport(score(grouped), grouped, parts)
    }

    /** Within a group only the item with the largest penalty is kept; the others get zero penalty and a note. */
    fun mergeGroups(items: List<CheckItem>): List<CheckItem> {
        val winners = items
            .filter { it.penalty > 0f && GROUPS[it.id] != null }
            .groupBy { GROUPS[it.id]!! }
            .mapValues { (_, list) -> list.maxBy { it.penalty } }
        return items.map { item ->
            val g = GROUPS[item.id] ?: return@map item
            val w = winners[g] ?: return@map item
            if (item.penalty > 0f && w.id != item.id) item.copy(penalty = 0f, mergedInto = w.name) else item
        }
    }

    /** Direction conflicts: the lower-priority item is demoted to a "tip" (no penalty), noting which one it yields to. */
    fun resolveConflicts(items: List<CheckItem>): List<CheckItem> {
        val movers = items
            .filter { it.move != null && it.penalty > 0f }
            .sortedBy { movePriority(it.id) }
        val kept = mutableListOf<CheckItem>()
        val demoted = mutableMapOf<String, CheckItem>()
        for (m in movers) {
            val conflict = kept.firstOrNull { k -> opposes(k.move!!, m.move!!) }
            if (conflict == null) {
                kept += m
            } else {
                demoted[m.id] = m.copy(
                    severity = Severity.TIP,
                    penalty = 0f,
                    message = m.message + tr("（和「${conflict.name}」方向相反，先按它来）", "（「${conflict.name}」と逆方向のため、そちらを優先）", " (opposite to \"${conflict.name}\", follow that one first)"),
                )
            }
        }
        return items.map { demoted[it.id] ?: it }
    }

    private fun opposes(a: P, b: P, tol: Float = 0.03f): Boolean =
        (a.x * b.x < 0 && abs(a.x) > tol && abs(b.x) > tol) ||
            (a.y * b.y < 0 && abs(a.y) > tol && abs(b.y) > tol)
}
