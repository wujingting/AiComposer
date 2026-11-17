package com.example.aicomposer.pipeline

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Picks the best photos of a set, the same way as the PC picker (tools/photo_picker):
 * greedy in score order; a photo whose CLIP embedding is at least [similarity] similar to an already
 * picked one is a near-duplicate (burst shot) and is skipped; stop when [topPercent] of the photos are picked.
 */
object PhotoRanking {
    data class Item(val id: Int, val score: Float, val embedding: FloatArray?)

    data class Result(
        /** Picked ids, best first. */
        val picks: List<Int>,
        /** id of a near-duplicate -> id of the picked photo it resembles. */
        val duplicateOf: Map<Int, Int>,
    )

    fun rank(items: List<Item>, topPercent: Float = 20f, similarity: Float = 0.94f): Result {
        if (items.isEmpty()) return Result(emptyList(), emptyMap())
        val quota = max(1, (items.size * topPercent / 100f).roundToInt())
        // Every photo is compared with all better, non-duplicate photos (not only the picks), so burst
        // shots are marked as similar even when none of them makes it into the top percent.
        val kept = mutableListOf<Item>()
        val picks = mutableListOf<Item>()
        val dups = mutableMapOf<Int, Int>()
        for (it in items.sortedByDescending { it.score }) {
            val e = it.embedding
            val dup = if (e == null) null else kept.firstOrNull { p -> p.embedding?.let { dot(e, it) >= similarity } == true }
            if (dup != null) {
                dups[it.id] = dup.id
                continue
            }
            kept += it
            if (picks.size < quota) picks += it
        }
        return Result(picks.map { it.id }, dups)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s
    }
}
