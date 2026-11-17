package com.example.aicomposer.analysis

import com.example.aicomposer.coach.Cfg
import com.example.aicomposer.i18n.cap
import com.example.aicomposer.i18n.tr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Decides automatically whether to plan a crop, and how much to keep.
 *
 * Idea: the subject should have a "right size" in the final image.
 *   - People: face height about 15% of the final image height (roughly a half-body shot)
 *   - Other subjects: about 12% of the final image area
 * If the subject is smaller than that, the lens is not long enough and we crop:
 * keep = current size / right size.
 *
 * Limits:
 *   - The subject may not fill more than 60% of the final image (too cramped)
 *   - Crop to 50% at most (fewer pixels than that is too little)
 *   - No crop for landscapes or when there is no subject
 *   - Ignore changes below 8% so the white frame does not jump around
 */
object AutoCropper {
    private val MIN_KEEP get() = Cfg.f("autocrop.min_keep")
    private val FACE_TARGET_H get() = Cfg.f("autocrop.face_h")
    private val OBJECT_TARGET_AREA get() = Cfg.f("autocrop.object_area")
    private const val MAX_FILL = 0.6f

    data class Decision(val keep: Float, val reason: String)

    fun decide(a: AnalysisResult, prevKeep: Float): Decision {
        val m = a.mainSubject
            ?: return Decision(
                prevKeep,
                if (prevKeep < 0.99f) tr("暂时没看到主体：保持上次的裁剪", "被写体が見当たりません：前回のトリミングを維持", "No subject right now: keeping the previous crop")
                else tr("没有明确主体：不裁剪", "はっきりした被写体がありません：トリミングなし", "No clear subject: no crop"),
            )
        if (a.scene == SceneType.LANDSCAPE && !m.isFace) {
            return Decision(1f, tr("风景：保留完整画面", "風景：画面全体を使います", "Landscape: keeping the full frame"))
        }

        val want = if (m.isFace) m.rect.h / FACE_TARGET_H else sqrt(m.rect.area / OBJECT_TARGET_AREA)
        val fit = max(m.rect.w, m.rect.h) / MAX_FILL
        val target = max(want, fit).coerceIn(MIN_KEEP, 1f)

        val snapped = if (target > 0.9f) 1f else (target * 20f).roundToInt() / 20f // 5% steps
        val keep = if (abs(snapped - prevKeep) < 0.08f) prevKeep else snapped

        val minPct = (MIN_KEEP * 100).roundToInt()
        val keepPct = (keep * 100).roundToInt()
        val reason = when {
            keep >= 0.99f -> tr(
                "${m.label}大小合适：不需要裁剪",
                "${m.label}の大きさは適切：トリミング不要",
                "${m.label.cap()} is a good size: no crop needed",
            )
            target <= MIN_KEEP + 0.001f && want < MIN_KEEP -> tr(
                "${m.label}太小：已经裁到极限 $minPct%，建议走近一点",
                "${m.label}が小さすぎます：トリミングは限界の $minPct%。もう少し近づきましょう",
                "${m.label.cap()} is too small: already at the $minPct% crop limit, move closer",
            )
            else -> tr(
                "${m.label}偏小（镜头不够长）：建议保留 $keepPct%",
                "${m.label}が小さめ（焦点距離不足）：$keepPct% を残すトリミングを推奨",
                "${m.label.cap()} is a bit small (lens too short): keep $keepPct%",
            )
        }
        return Decision(keep, reason)
    }
}
