package com.example.aicomposer.analysis

import com.example.aicomposer.i18n.I18n
import com.example.aicomposer.i18n.cap
import com.example.aicomposer.i18n.tr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Composition score + hints: rule of thirds, subject touching the edge, headroom, horizon.
 *
 * Planned-crop mode: first place an "expected final image" crop window in the frame so the
 * subject lands on one of its thirds points, then score everything inside that window.
 * Camera-move hints also aim at making that window fit and work well.
 */
/**
 * The body a face belongs to: a detected person (YOLOX) or the pose, whichever contains the face center.
 * null when there is no face subject or no body around it.
 */
fun AnalysisResult.bodyOf(face: Subject): NRect? {
    if (!face.isFace) return null
    val cx = face.rect.cx; val cy = face.rect.cy
    return subjects.filter { it.kind == SubjectKind.PERSON && !it.isFace && it.rect.contains(cx, cy) }
        .maxByOrNull { it.rect.area }?.rect
        ?: pose?.bounds?.takeIf { it.contains(cx, cy) }
}

/**
 * A half-body or full-body shot of a person (the body takes a fair share of the frame), as opposed to a
 * head shot. Then a centered person is as valid as one on a thirds line, and the height is judged by the
 * eye line instead of a thirds point.
 */
fun AnalysisResult.isPeopleShot(m: Subject? = mainSubject): Boolean =
    m != null && (bodyOf(m)?.area ?: 0f) >= 0.15f

object CompositionAdvisor {
    private val thirds = listOf(P(1 / 3f, 1 / 3f), P(2 / 3f, 1 / 3f), P(1 / 3f, 2 / 3f), P(2 / 3f, 2 / 3f))
    private val FULL = NRect(0f, 0f, 1f, 1f)

    fun advise(a: AnalysisResult, plan: CropPlan = CropPlan()): CompositionAdvice {
        val hints = mutableListOf<String>()
        val m = a.mainSubject
        val cropping = plan.active

        // 1. Evaluation window: the whole frame, or the planned crop window
        val win = if (cropping) placeCrop(m, plan.size(a.frameAspect)) else FULL
        if (cropping) {
            val pct = (plan.keep * 100).roundToInt()
            hints += tr(
                "白框 = 预计裁剪后的成片（保留 $pct%）",
                "白枠 = トリミング後の仕上がり（$pct% を残す）",
                "White frame = expected final image after cropping (keeping $pct%)",
            )
        }

        fun toWin(r: NRect) = NRect(
            (r.l - win.l) / win.w, (r.t - win.t) / win.h,
            (r.r - win.l) / win.w, (r.b - win.t) / win.h,
        )
        fun toFrame(p: P) = P(win.l + p.x * win.w, win.t + p.y * win.h)

        // 2. Subject position (computed in window coordinates)
        var posScore: Float? = null
        var target: P? = null
        var center: P? = null
        if (m != null) {
            val r = toWin(m.rect)
            val c = P(r.cx, r.cy)
            center = P(m.rect.cx, m.rect.cy)
            val big = r.area > 0.35f
            if (big) {
                posScore = 85f
                hints += if (cropping) tr(
                    "${m.label}比裁剪框还大：这张不用裁这么多",
                    "${m.label}がトリミング枠より大きい：ここまで切る必要はありません",
                    "${m.label.cap()} is bigger than the crop window: no need to crop this much",
                )
                else tr(
                    "${m.label}很大：注意边缘不要切到关键部位",
                    "${m.label}が大きい：端で大事な部分を切らないように",
                    "${m.label.cap()} is large: make sure the edges do not cut off anything important",
                )
            } else {
                val people = a.isPeopleShot(m)
                val candidates = when {
                    // Left/right only: center line or a thirds line; the height is left to the eye-line rule
                    people -> listOf(1 / 3f, 0.5f, 2 / 3f).map { P(it, c.y) }
                    m.isFace -> thirds.filter { it.y < 0.5f }
                    else -> thirds
                }
                val t = candidates.minBy { dist(it, c) }
                target = toFrame(t)
                val d = dist(t, c)
                var ps = 100f * (1f - (d / 0.3f).coerceAtMost(1f))
                if (d > 0.06f) {
                    // Subject must go left -> turn the camera right; subject must go up -> tilt the camera down
                    val hx = when {
                        t.x < c.x - 0.04f -> tr("往右转", "右に振る", "pan right")
                        t.x > c.x + 0.04f -> tr("往左转", "左に振る", "pan left")
                        else -> null
                    }
                    val vy = when {
                        t.y < c.y - 0.04f -> tr("往下压", "下に向ける", "tilt down")
                        t.y > c.y + 0.04f -> tr("往上抬", "上に向ける", "tilt up")
                        else -> null
                    }
                    val move = listOfNotNull(hx, vy).joinToString(if (I18n.lang == com.example.aicomposer.i18n.Lang.EN) " and " else "、")
                    if (move.isNotEmpty()) {
                        hints += if (cropping) tr(
                            "裁剪框放不下理想构图：相机$move",
                            "トリミング枠に理想の構図が収まりません：カメラを$move",
                            "The crop window cannot fit the ideal composition: $move",
                        )
                        else if (people) tr(
                            "把${m.label}放到中线或三分线上：相机$move",
                            "${m.label}を中央か三分割線へ：カメラを$move",
                            "Put ${m.label} on the center or a thirds line: $move",
                        )
                        else tr(
                            "把${m.label}放到三分点：相机$move",
                            "${m.label}を三分割の交点へ：カメラを$move",
                            "Put ${m.label} on a thirds point: $move",
                        )
                    }
                }
                if (r.l < 0.02f || r.r > 0.98f || r.t < 0.02f || r.b > 0.98f) {
                    ps -= 15f
                    hints += if (cropping) tr(
                        "${m.label}贴到裁剪框边缘了",
                        "${m.label}がトリミング枠の端に接しています",
                        "${m.label.cap()} touches the edge of the crop window",
                    ) else tr(
                        "${m.label}贴到画面边缘了",
                        "${m.label}が画面の端に接しています",
                        "${m.label.cap()} touches the edge of the frame",
                    )
                }
                posScore = ps
            }
            if (m.isFace && r.t < 0.06f) {
                posScore = (posScore ?: 70f) - 10f
                hints += tr("头顶空间太少：相机稍微往上抬", "頭上の余白が少なすぎます：カメラを少し上へ", "Too little headroom: tilt the camera up a little")
            }
        } else {
            hints += tr(
                "没有检测到明确主体（可以靠近一点，或者手动构图）",
                "はっきりした被写体が見つかりません（近づくか、手動で構図を決めてください）",
                "No clear subject detected (move closer, or compose by hand)",
            )
        }

        // 3. Crop window too close to the frame edge: no slack for adjusting later
        if (cropping && win.w < 0.98f && win.h < 0.98f) {
            val margin = min(min(win.l, 1f - win.r), min(win.t, 1f - win.b))
            if (margin < 0.01f) posScore = (posScore ?: 70f) - 5f
        }

        // 4. Horizon (half the penalty when cropping, since it can be straightened then)
        var tiltScore: Float? = null
        a.tiltDeg?.let { t ->
            val perDeg = if (cropping) 10f else 20f
            tiltScore = (100f - abs(t) * perDeg).coerceAtLeast(30f)
            if (abs(t) >= 1f) {
                val deg = String.format(Locale.ROOT, "%.1f", abs(t))
                val tail = if (cropping) tr("（裁剪时也可以顺便转正）", "（トリミング時に回転補正も可）", " (you can also straighten it when cropping)") else ""
                hints += if (t > 0) tr(
                    "水平线右低左高 $deg°：相机右侧往下压一点$tail",
                    "水平線が右下がり $deg°：カメラの右側を少し下げて$tail",
                    "Horizon is lower on the right by $deg°: lower the right side of the camera a little$tail",
                )
                else tr(
                    "水平线左低右高 $deg°：相机左侧往下压一点$tail",
                    "水平線が左下がり $deg°：カメラの左側を少し下げて$tail",
                    "Horizon is lower on the left by $deg°: lower the left side of the camera a little$tail",
                )
            }
        }

        val p = posScore
        val ts = tiltScore
        val score = when {
            p != null && ts != null -> p * 0.7f + ts * 0.3f
            p != null -> p
            ts != null -> ts
            else -> 50f
        }
        return CompositionAdvice(
            score = score.roundToInt().coerceIn(0, 100),
            subjectCenter = center,
            target = target,
            hints = hints,
            cropRect = if (cropping) win else null,
        )
    }

    /**
     * Places the crop window: try putting the subject on each thirds point of the window, pick the
     * one that overflows the frame least, then push it back inside. Centered when there is no subject.
     */
    private fun placeCrop(m: Subject?, size: Pair<Float, Float>): NRect {
        val (cw, ch) = size
        val ideal = if (m != null && m.rect.w < cw * 0.8f && m.rect.h < ch * 0.8f) {
            val pts = if (m.isFace) thirds.filter { it.y < 0.5f } else thirds
            pts.map { t ->
                val l = m.rect.cx - t.x * cw
                val top = m.rect.cy - t.y * ch
                NRect(l, top, l + cw, top + ch)
            }.minBy { overflow(it) }
        } else {
            val cx = m?.rect?.cx ?: 0.5f
            val cy = m?.rect?.cy ?: 0.5f
            NRect(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
        }
        return clamp(ideal)
    }

    private fun overflow(r: NRect) =
        max(0f, -r.l) + max(0f, r.r - 1f) + max(0f, -r.t) + max(0f, r.b - 1f)

    private fun clamp(r: NRect): NRect {
        val dx = when {
            r.l < 0f -> -r.l
            r.r > 1f -> 1f - r.r
            else -> 0f
        }
        val dy = when {
            r.t < 0f -> -r.t
            r.b > 1f -> 1f - r.b
            else -> 0f
        }
        return NRect(r.l + dx, r.t + dy, r.r + dx, r.b + dy)
    }

    private fun dist(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)
}
