package com.example.aicomposer.coach

import com.example.aicomposer.analysis.NRect
import com.example.aicomposer.analysis.P
import com.example.aicomposer.analysis.SceneType
import com.example.aicomposer.i18n.I18n
import com.example.aicomposer.i18n.Lang
import com.example.aicomposer.i18n.bare
import com.example.aicomposer.i18n.cap
import com.example.aicomposer.i18n.tr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Frame sides, used in "cut off at the ... edge" style messages. */
internal enum class Side {
    LEFT, RIGHT, TOP, BOTTOM;

    val label: String
        get() = when (this) {
            LEFT -> tr("左", "左", "left")
            RIGHT -> tr("右", "右", "right")
            TOP -> tr("上", "上", "top")
            BOTTOM -> tr("下", "下", "bottom")
        }
}

/** Join short phrases: "A、B" in CJK, "A and B" in English. */
internal fun joinAnd(parts: List<String>): String =
    parts.joinToString(if (I18n.lang == Lang.EN) " and " else "、")

object CompositionRules {
    private val C = Category.COMPOSITION

    /** Translates "where the subject must move in the frame" into "where to turn the camera". */
    internal fun cameraMove(dx: Float, dy: Float, tol: Float = 0.04f): String {
        val h = when {
            dx < -tol -> tr("往右转", "右に振る", "pan right")   // subject must go left -> camera turns right
            dx > tol -> tr("往左转", "左に振る", "pan left")
            else -> null
        }
        val v = when {
            dy < -tol -> tr("往下压", "下に向ける", "tilt down")   // subject must go up -> camera tilts down
            dy > tol -> tr("往上抬", "上に向ける", "tilt up")
            else -> null
        }
        val parts = listOfNotNull(h, v)
        if (parts.isEmpty()) return ""
        return tr("相机" + parts.joinToString("、"), "カメラを" + parts.joinToString("、"), joinAnd(parts))
    }

    /** 1. Subject position: thirds points; symmetric / centered compositions are not penalized by the rule of thirds. */
    val placement = Rule { c ->
        val name = tr("主体位置", "被写体の位置", "Subject position")
        val m = c.a.mainSubject
            ?: return@Rule item("placement", name, C, Severity.INFO, tr(
                "没有明确的主体：靠近一点，或者想清楚这张照片的重点是什么",
                "はっきりした被写体がありません：近づくか、この写真の主役を決めましょう",
                "No clear subject: move closer, or decide what this photo is about",
            ), short = tr("没有明确主体", "主役がいない", "No clear subject"))
        val r = c.toWin(m.rect)
        if (r.area > 0.35f) return@Rule ok("placement", name, C, tr("主体占满画面", "被写体が画面いっぱい", "Subject fills the frame"))
        val cx = r.cx
        val sym = c.a.features?.symmetry ?: 0f
        if (sym > Cfg.f("symmetry.min") && abs(cx - 0.5f) < 0.1f) {
            return@Rule if (abs(cx - 0.5f) < 0.03f) ok("placement", name, C, tr("对称/居中构图，对得很准", "対称・日の丸構図で、中心がぴったり", "Symmetric / centered composition, nicely aligned"))
            else {
                val mv = cameraMove(0.5f - cx, 0f, 0.01f)
                item("placement", name, C, Severity.WARN,
                    tr("对称构图要对准中线：${mv}一点", "対称構図は中心線に合わせましょう：${mv}（少しだけ）", "Align a symmetric composition with the center line: $mv a little"),
                    8f, move = P(0.5f - cx, 0f),
                    short = tr("对称构图要对准中线", "対称は中心線に合わせる", "Center the symmetry"))
            }
        }
        val t = c.comp.target ?: return@Rule null
        val tw = c.toWin(t)
        val d = hypot(tw.x - cx, tw.y - r.cy)
        val penalty = min(d / 0.3f, 1f) * Cfg.f("placement.max_penalty")
        when {
            d < Cfg.f("placement.ok_dist") -> ok("placement", name, C, tr("主体在三分点上", "被写体が三分割の交点にあります", "Subject is on a thirds point"))
            else -> {
                val mv = cameraMove(tw.x - cx, tw.y - r.cy)
                item(
                    "placement", name, C, if (d < Cfg.f("placement.warn_dist")) Severity.INFO else Severity.WARN,
                    tr("把${m.label}放到三分点：$mv", "${m.label}を三分割の交点へ：$mv", "Put ${m.label} on a thirds point: $mv"),
                    penalty,
                    move = P(tw.x - cx, tw.y - r.cy),
                    short = tr("${m.label}放到三分点", "${m.label}を三分割点へ", "${m.label.bare()} to a thirds point"),
                )
            }
        }
    }

    /** 2. Subject cut off by the frame edge. */
    val edgeCut = Rule { c ->
        val name = tr("主体完整", "被写体の切れ", "Subject complete")
        val m = c.a.mainSubject ?: return@Rule null
        val r = c.toWin(m.rect)
        if (r.area > 0.35f) return@Rule null
        val cut = mutableListOf<Side>()
        if (r.l < 0.01f) cut += Side.LEFT
        if (r.r > 0.99f) cut += Side.RIGHT
        if (r.t < 0.01f) cut += Side.TOP
        if (r.b > 0.99f) cut += Side.BOTTOM
        if (cut.isEmpty()) ok("edge_cut", name, C, tr("主体没有被切到", "被写体は切れていません", "Subject is not cut off"))
        else {
            val sides = joinAnd(cut.map { it.label })
            item("edge_cut", name, C, Severity.WARN, tr(
                "${m.label}在${sides}边被切到了：要么完整放进来，要么大胆裁掉一部分",
                "${m.label}が${sides}端で切れています：全体を入れるか、思い切って大きく切りましょう",
                "${m.label.cap()} is cut off at the $sides edge: either fit it in completely or crop boldly",
            ), short = tr("${m.label}在${sides}边被切", "${m.label}が${sides}端で切れている", "${m.label.bare()} cut off at $sides"))
        }
    }

    /** 3. Subject size. */
    val subjectSize = Rule { c ->
        val name = tr("主体大小", "被写体の大きさ", "Subject size")
        val m = c.a.mainSubject ?: return@Rule null
        val area = c.toWin(m.rect).area
        when {
            area < Cfg.f("size.small_area") -> item("size", name, C, Severity.WARN, tr(
                "${m.label}太小，看不清是拍什么：走近一点",
                "${m.label}が小さすぎて何を撮ったのか分かりません：近づきましょう",
                "${m.label.cap()} is too small to tell what the photo is about: move closer",
            ), short = tr("${m.label}太小，走近点", "${m.label}が小さい：近づく", "${m.label.bare()} too small: get closer"))
            area > Cfg.f("size.big_area") && c.a.scene != SceneType.CLOSEUP ->
                item("size", name, C, Severity.INFO, tr(
                    "${m.label}塞满了画面，有点挤：退后一步留点呼吸空间",
                    "${m.label}が画面に詰まって窮屈です：一歩下がって余白を作りましょう",
                    "${m.label.cap()} fills the frame and feels cramped: step back to give it some room",
                ), short = tr("太挤，退后一步", "窮屈：一歩下がる", "Too cramped: step back"))
            else -> ok("size", name, C, tr("主体大小合适", "被写体の大きさは適切", "Subject size is good"))
        }
    }

    /** 4. Lead room: leave space in front of where the face looks / the subject moves. */
    val leadRoom = Rule { c ->
        val face = c.a.faces.maxByOrNull { it.rect.area }
        if (face != null && abs(face.yaw) > Cfg.f("lead_room.min_yaw")) {
            val name = tr("视线留白", "視線の先の余白", "Look room")
            val r = c.toWin(face.rect)
            val right = face.yaw > 0
            val ahead = if (right) 1f - r.cx else r.cx
            val behind = 1f - ahead
            return@Rule if (ahead < behind - Cfg.f("lead_room.margin")) {
                val dir = if (right) Side.RIGHT.label else Side.LEFT.label
                val mv = cameraMove(if (right) -0.1f else 0.1f, 0f)
                item("lead_room", name, C, Severity.WARN, tr(
                    "人物朝${dir}看，但${dir}边空间更少：${mv}，让视线前方多留空间",
                    "人物は${dir}を向いていますが、${dir}側の余白が少ない：${mv}。視線の先に余白を作りましょう",
                    "The person looks $dir but there is less space on the $dir: $mv to leave room in front of the gaze",
                ), move = P(if (right) -0.1f else 0.1f, 0f),
                    short = tr("视线前方多留空间", "視線の先に余白を", "Leave room for the gaze"))
            } else ok("lead_room", name, C, tr("视线前方留了空间", "視線の先に余白があります", "There is room in front of the gaze"))
        }
        val v = c.a.subjectVelocity
        val m = c.a.mainSubject
        if (v != null && m != null && abs(v.x) > 0.015f && c.a.scene == SceneType.ACTION) {
            val name = tr("运动方向留白", "進行方向の余白", "Lead room")
            val r = c.toWin(m.rect)
            val right = v.x > 0
            val ahead = if (right) 1f - r.cx else r.cx
            return@Rule if (ahead < 0.45f) {
                val dir = if (right) Side.RIGHT.label else Side.LEFT.label
                item("lead_room", name, C, Severity.WARN, tr(
                    "${m.label}往${dir}移动，前方空间不够：跟拍时让主体留在后半边",
                    "${m.label}は${dir}へ移動中で、前方の余白が足りません：追い写しでは被写体を後ろ半分に",
                    "${m.label.cap()} is moving $dir with too little room ahead: keep it in the trailing half when panning",
                ), move = P(if (right) -0.1f else 0.1f, 0f),
                    short = tr("运动前方多留空间", "進行方向に余白を", "Leave room ahead"))
            } else ok("lead_room", name, C, tr("运动方向前方留了空间", "進行方向に余白があります", "There is room ahead of the motion"))
        }
        null
    }

    /** 5. Merger: a strong vertical line right above the head (pole, tree trunk, pillar). */
    val merger = Rule { c ->
        val name = tr("穿头", "串刺し", "Head merger")
        val f = c.a.features ?: return@Rule null
        val face = c.a.faces.filter { it.rect.h > 0.05f }.maxByOrNull { it.rect.area } ?: return@Rule null
        val g = f.detail
        val fr = face.rect
        val x0 = ((fr.l + fr.w * 0.15f) * g.w).toInt().coerceIn(1, g.w - 2)
        val x1 = ((fr.r - fr.w * 0.15f) * g.w).toInt().coerceIn(x0 + 1, g.w - 1)
        val y0 = ((fr.t - fr.h * 1.0f) * g.h).toInt().coerceIn(1, g.h - 2)
        val y1 = (fr.t * g.h).toInt().coerceIn(y0 + 1, g.h - 1)
        if (x1 - x0 < 4 || y1 - y0 < 4) return@Rule null
        val cols = FloatArray(x1 - x0)
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * g.w + x
            val gx = abs(g.px[i + 1] - g.px[i - 1]).toFloat()
            val gy = abs(g.px[i + g.w] - g.px[i - g.w]).toFloat()
            if (gx > 30f && gx > gy * 2f) cols[x - x0] += 1f
        }
        val rows = (y1 - y0).toFloat()
        val peak = cols.max() / rows      // share of pixels in one column that are vertical edges
        val mean = cols.average().toFloat() / rows
        if (peak > Cfg.f("merger.peak") && peak > mean * Cfg.f("merger.ratio")) {
            item("merger", name, C, Severity.WARN, tr(
                "头顶上方有竖线（电线杆/树干？），看起来像从头上长出来：往旁边挪一步或换个角度",
                "頭の上に縦の線（電柱・木の幹？）があり、頭から生えているように見えます：横に一歩ずれるか角度を変えましょう",
                "A vertical line (pole / tree trunk?) above the head looks like it grows out of it: step aside or change the angle",
            ), short = tr("头上有竖线穿过", "頭から線が生えている", "A line grows out of the head"))
        } else ok("merger", name, C, tr("头顶背景干净", "頭上の背景はすっきり", "Background above the head is clean"))
    }

    /** 6. Busy background around the subject. */
    val clutter = Rule { c ->
        val name = tr("背景杂乱", "背景のごちゃつき", "Busy background")
        val f = c.a.features ?: return@Rule null
        val m = c.a.mainSubject ?: return@Rule null
        val r = m.rect
        if (r.area > 0.5f) return@Rule null
        val ring = NRect(
            (r.l - r.w * 0.6f).coerceAtLeast(c.win.l), (r.t - r.h * 0.6f).coerceAtLeast(c.win.t),
            (r.r + r.w * 0.6f).coerceAtMost(c.win.r), (r.b + r.h * 0.6f).coerceAtMost(c.win.b),
        )
        val e = f.edgeMean(ring, exclude = r) ?: return@Rule null
        when {
            e > Cfg.f("clutter.warn") -> item("clutter", name, C, Severity.WARN, tr(
                "主体周围背景很乱，抢视线：开大光圈虚化、靠近主体，或者换个干净的背景",
                "被写体の周りの背景がごちゃごちゃして目を引きます：絞りを開けてぼかす、近づく、またはすっきりした背景に",
                "The background around the subject is busy and distracting: open the aperture to blur it, move closer, or find a cleaner background",
            ), short = tr("背景太乱", "背景がごちゃごちゃ", "Busy background"))
            e > Cfg.f("clutter.info") -> item("clutter", name, C, Severity.INFO, tr(
                "背景有点杂：可以开大光圈或换个更简洁的角度",
                "背景が少しごちゃついています：絞りを開けるか、すっきりした角度に",
                "The background is a bit busy: open the aperture or find a simpler angle",
            ), short = tr("背景有点杂", "背景がややうるさい", "Background a bit busy"))
            else -> ok("clutter", name, C, tr("背景比较干净", "背景はすっきり", "The background is fairly clean"))
        }
    }

    /** 7. Subject does not separate from the background (brightness and color too similar). */
    val separation = Rule { c ->
        val name = tr("主体突出", "被写体の際立ち", "Subject separation")
        val f = c.a.features ?: return@Rule null
        val m = c.a.mainSubject ?: return@Rule null
        val r = m.rect
        if (r.area > 0.5f) return@Rule null
        val ring = NRect(
            (r.l - r.w * 0.5f).coerceAtLeast(0f), (r.t - r.h * 0.5f).coerceAtLeast(0f),
            (r.r + r.w * 0.5f).coerceAtMost(1f), (r.b + r.h * 0.5f).coerceAtMost(1f),
        )
        val inner = NRect(r.l + r.w * 0.2f, r.t + r.h * 0.2f, r.r - r.w * 0.2f, r.b - r.h * 0.2f)
        val a = f.colorMean(inner) ?: return@Rule null
        val b = f.colorMean(ring, exclude = r) ?: return@Rule null
        val diff = abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])
        if (diff < Cfg.f("separation.min_diff")) item("separation", name, C, Severity.WARN, tr(
            "${m.label}和背景的颜色、亮度太接近，不突出：换个反差大的背景，或用光线把主体打亮",
            "${m.label}と背景の色・明るさが近すぎて目立ちません：コントラストの強い背景にするか、光で被写体を明るく",
            "${m.label.cap()} is too close to the background in color and brightness: pick a contrasting background or light the subject",
        ), short = tr("${m.label}和背景混在一起", "${m.label}が背景に埋もれる", "${m.label.bare()} does not stand out"))
        else ok("separation", name, C, tr("主体和背景有区分", "被写体と背景が分離しています", "Subject stands apart from the background"))
    }

    /** 8. Eye-catching bright patches at the frame edges. */
    val edgeDistraction = Rule { c ->
        val name = tr("边缘干扰", "端の邪魔もの", "Edge distraction")
        val f = c.a.features ?: return@Rule null
        val w = c.win
        val bw = w.w * 0.07f
        val bh = w.h * 0.07f
        val strips = listOf(
            Side.TOP to NRect(w.l, w.t, w.r, w.t + bh),
            Side.BOTTOM to NRect(w.l, w.b - bh, w.r, w.b),
            Side.LEFT to NRect(w.l, w.t, w.l + bw, w.b),
            Side.RIGHT to NRect(w.r - bw, w.t, w.r, w.b),
        )
        val level = (max(0.85f, c.a.luma.mean + 0.3f) * 255).roundToInt().coerceAtMost(254)
        val bad = strips.filter { (_, r) -> f.clipFraction(r, level) > Cfg.f("edge.bright_fraction") }.map { it.first }
        // Sky along the top edge is normal for landscapes
        val filtered = if (c.a.scene == SceneType.LANDSCAPE) bad - Side.TOP else bad
        if (filtered.isNotEmpty()) {
            val sides = joinAnd(filtered.map { it.label })
            item("edge_distraction", name, C, Severity.INFO, tr(
                "画面${sides}边有很亮的东西，会把视线拉出画面：收紧一点构图或换角度",
                "画面の${sides}端に明るいものがあり、視線が外へ逃げます：構図を少し詰めるか角度を変えましょう",
                "Something bright at the $sides edge pulls the eye out of the frame: tighten the composition or change the angle",
            ), short = tr("${sides}边有亮东西", "${sides}端に明るいもの", "Bright spot at $sides edge"))
        } else ok("edge_distraction", name, C, tr("画面边缘没有抢眼的东西", "画面の端に目立つものはありません", "Nothing distracting at the edges"))
    }

    /** 9. Visual weight too far to one side. */
    val balance = Rule { c ->
        val name = tr("画面平衡", "画面のバランス", "Balance")
        val ct = c.a.mask?.centroid() ?: c.a.features?.saliency?.centroid() ?: return@Rule null
        val p = c.toWin(ct)
        if (abs(p.x - 0.5f) > Cfg.f("balance.offset")) {
            val side = if (p.x < 0.5f) Side.LEFT.label else Side.RIGHT.label
            item("balance", name, C, Severity.INFO, tr(
                "视觉重心太偏${side}，另一侧显得空：在另一侧找点东西平衡，或者收紧构图",
                "視覚的な重心が${side}に寄りすぎて反対側が空いています：反対側に何か入れるか、構図を詰めましょう",
                "The visual weight is too far $side and the other side feels empty: balance it with something on the other side or tighten the framing",
            ), short = tr("重心太偏${side}", "重心が${side}に寄りすぎ", "Weight leans $side"))
        } else ok("balance", name, C, tr("画面左右比较平衡", "左右のバランスは良好", "Left and right are fairly balanced"))
    }

    /** 10. Tilted horizon. */
    val tilt = Rule { c ->
        val name = tr("水平", "水平", "Level")
        val t = c.a.tiltDeg ?: return@Rule null
        val deg = String.format(Locale.ROOT, "%.1f", abs(t))
        val tail = if (c.plan.active) tr("（裁剪时也能顺便转正）", "（トリミング時に回転補正も可）", " (you can also straighten it when cropping)") else ""
        when {
            abs(t) < Cfg.f("tilt.ok_deg") -> ok("tilt", name, C, tr("水平线是平的", "水平線はまっすぐです", "The horizon is level"))
            else -> item(
                "tilt", name, C, if (abs(t) < Cfg.f("tilt.warn_deg")) Severity.INFO else Severity.WARN,
                if (t > 0) tr(
                    "水平线右低左高 $deg°：相机右侧往下压一点$tail",
                    "水平線が右下がり $deg°：カメラの右側を少し下げて$tail",
                    "Horizon is lower on the right by $deg°: lower the right side of the camera a little$tail",
                ) else tr(
                    "水平线左低右高 $deg°：相机左侧往下压一点$tail",
                    "水平線が左下がり $deg°：カメラの左側を少し下げて$tail",
                    "Horizon is lower on the left by $deg°: lower the left side of the camera a little$tail",
                ),
                min(abs(t) * Cfg.f("tilt.penalty_per_deg") * (if (c.plan.active) 0.5f else 1f), 20f),
                short = if (t > 0) tr("水平线右低 $deg°", "水平線が右下がり $deg°", "Horizon dips right $deg°")
                else tr("水平线左低 $deg°", "水平線が左下がり $deg°", "Horizon dips left $deg°"),
            )
        }
    }

    val all = listOf(placement, edgeCut, subjectSize, leadRoom, merger, clutter, separation, edgeDistraction, balance, tilt)
}
