package com.example.aicomposer.coach

/**
 * Temporal smoothing: only report persistent problems so hints do not flicker.
 *
 *  - Looks at all analysis results in the last temporal.window_ms milliseconds
 *  - A rule starts being reported once its "problem" ratio is >= temporal.show
 *  - Once reported, it disappears only when the ratio < temporal.hide (hysteresis, avoids jitter around the threshold)
 *  - With too few frames in the window (< 2) the previous decision is kept
 *  - Problems not yet confirmed: show the rule's last "passed" result; if it never passed, show nothing yet
 *
 * Note: auto-shutter's "blocking problem" check uses the raw result of the current frame (e.g. eyes closed right now), not this filter.
 */
class TemporalFilter {
    private data class Frame(val time: Long, val items: Map<String, CheckItem>)

    private val history = ArrayDeque<Frame>()
    private val shown = mutableSetOf<String>()

    private fun isProblem(i: CheckItem) =
        i.severity == Severity.INFO || i.severity == Severity.WARN || i.severity == Severity.ERROR

    fun filter(items: List<CheckItem>, now: Long = System.currentTimeMillis()): List<CheckItem> {
        history.addLast(Frame(now, items.associateBy { it.id }))
        val window = Cfg.d("temporal.window_ms").toLong()
        while (history.isNotEmpty() && now - history.first().time > window) history.removeFirst()

        val show = Cfg.d("temporal.show")
        val hide = Cfg.d("temporal.hide")
        val enough = history.size >= 2

        return items.mapNotNull { item ->
            val id = item.id
            // Ignored rules and "tip" items need no smoothing
            if (item.severity == Severity.IGNORED || item.severity == Severity.TIP) return@mapNotNull item

            val frames = history.mapNotNull { it.items[id] }
            val ratio = if (frames.isEmpty()) 0.0 else frames.count { isProblem(it) }.toDouble() / frames.size
            val wasShown = id in shown
            val confirmed = when {
                !enough -> wasShown || !isProblem(item)
                wasShown -> ratio >= hide
                else -> ratio >= show
            }

            if (isProblem(item)) {
                if (confirmed) {
                    shown += id
                    item
                } else {
                    // Not confirmed yet: show the last passed result
                    history.reversed().firstNotNullOfOrNull { f -> f.items[id]?.takeIf { it.severity == Severity.OK } }
                }
            } else {
                if (wasShown && confirmed) {
                    // Problem just disappeared but still in hysteresis: keep showing the last problem
                    history.reversed().firstNotNullOfOrNull { f -> f.items[id]?.takeIf { isProblem(it) } } ?: item.also { shown -= id }
                } else {
                    shown -= id
                    item
                }
            }
        }
    }

    fun reset() {
        history.clear()
        shown.clear()
    }
}
