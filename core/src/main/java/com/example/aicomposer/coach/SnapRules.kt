package com.example.aicomposer.coach

import com.example.aicomposer.analysis.FrameAnalyzer
import com.example.aicomposer.analysis.SceneType
import com.example.aicomposer.i18n.cap
import com.example.aicomposer.i18n.tr
import kotlin.math.abs

/**
 * Everyday snapshot rules: the most common problems when shooting interesting things in daily life —
 * too far away, too many things and no focal point, flat images; plus tips on light and shadow,
 * leading lines, timing and animals.
 */
object SnapRules {
    private val C = Category.COMPOSITION

    private fun isSnap(c: CoachInput) =
        c.a.scene == SceneType.GENERAL || c.a.scene == SceneType.CLOSEUP || c.a.scene == SceneType.ACTION

    /** 1. Several equally eye-catching things, no clear focal point. */
    val simplicity = Rule { c ->
        val name = tr("画面重点", "主役", "Focal point")
        val f = c.a.features ?: return@Rule null
        if (c.a.faces.size >= 2) return@Rule null // group shots have several subjects by nature
        val n = f.saliency.blobs()
        if (n >= Cfg.d("snap.max_blobs")) item("simplicity", name, C, Severity.WARN, tr(
            "画面里有 $n 处同样抢眼的东西，看不出你想拍哪个：靠近主角、换个角度把多余的东西排除出去",
            "画面に同じくらい目立つものが $n つあり、何を撮りたいのか分かりません：主役に近づくか、角度を変えて余計なものを外しましょう",
            "There are $n equally eye-catching things and it is unclear which one you mean: get closer to the main one or change the angle to leave the rest out",
        ), short = tr("$n 处抢眼，没有重点", "目立つものが $n つ：主役不明", "$n things compete for attention"))
        else ok("simplicity", name, C, tr("画面重点明确", "主役がはっきりしています", "Clear focal point"))
    }

    /** 2. Get closer ("if your pictures aren't good enough, you're not close enough"). */
    val getCloser = Rule { c ->
        val name = tr("距离", "距離", "Distance")
        if (!isSnap(c)) return@Rule null
        val m = c.a.mainSubject ?: return@Rule null
        val area = c.toWin(m.rect).area
        if (area < Cfg.f("snap.closer_area")) item("closer", name, C, Severity.INFO, tr(
            "${m.label}在画面里太小：再靠近一点，让有趣的东西成为主角",
            "${m.label}が画面の中で小さすぎます：もう少し近づいて、面白いものを主役にしましょう",
            "${m.label.cap()} is too small in the frame: get closer and make the interesting thing the star",
        ), short = tr("再靠近${m.label}一点", "${m.label}にもっと近づく", "Get closer to ${m.label}"))
        else ok("closer", name, C, tr("距离合适", "距離は適切", "Good distance"))
    }

    /** 3. Flat colors and contrast, the image lacks punch. */
    val flatness = Rule { c ->
        val name = tr("颜色与反差", "色とコントラスト", "Color and contrast")
        val f = c.a.features ?: return@Rule null
        if (c.a.scene == SceneType.NIGHT) return@Rule null
        if (f.saturation < Cfg.f("snap.flat_saturation") && f.lumaStd < Cfg.f("snap.flat_contrast")) {
            item("flat", name, Category.LIGHT, Severity.INFO, tr(
                "颜色和明暗反差都很平：找有颜色对比或者有光影的角度，或者干脆拍成黑白",
                "色も明暗差も平坦です：色の対比や光と影のある角度を探すか、いっそモノクロに",
                "Colors and contrast are both flat: look for an angle with color contrast or light and shadow, or go black and white",
            ), short = tr("颜色和反差太平", "色も明暗も平坦", "Flat color and contrast"))
        } else ok("flat", name, Category.LIGHT, tr("画面有颜色或明暗变化", "色や明暗に変化があります", "There is variation in color or tone"))
    }

    /** 4. Strong light and shadow: suggest making use of it. */
    val lightAndShadow = Rule { c ->
        val f = c.a.features ?: return@Rule null
        if (c.a.faces.isNotEmpty()) return@Rule null // hard light on people has its own rule
        if (f.lumaStd > Cfg.f("snap.shadow_contrast") && c.a.luma.highlightClip < 0.05f) {
            item("light_shadow", tr("光影", "光と影", "Light and shadow"), Category.LIGHT, Severity.TIP, tr(
                "光影很强烈：曝光补偿 -0.7EV 让阴影更浓、高光更有质感，影子本身也可以当主角",
                "光と影が強い：露出補正 -0.7EV で影を深く、ハイライトに質感を。影そのものを主役にするのも手",
                "Strong light and shadow: -0.7 EV deepens the shadows and gives the highlights texture; the shadows themselves can be the subject",
            ), short = tr("光影强：试试 -0.7EV", "光と影：-0.7EV で深く", "Strong shadows: try -0.7 EV"))
        } else null
    }

    /** 5. Clear diagonal lines: suggest using them as leading lines. */
    val leadingLines = Rule { c ->
        val f = c.a.features ?: return@Rule null
        if (f.diagonalRatio > Cfg.f("snap.diagonal_ratio")) {
            item("leading_lines", tr("引导线", "導線", "Leading lines"), C, Severity.TIP, tr(
                "画面里有明显的斜线（路、栏杆、影子？）：让它从画面角落延伸向主体，视线会被带过去",
                "画面にはっきりした斜めの線（道、手すり、影？）があります：画面の隅から被写体へ伸びるように配置すると視線が導かれます",
                "There are strong diagonal lines (road, railing, shadows?): let them run from a corner towards the subject to lead the eye",
            ), short = tr("用斜线把视线引向主体", "斜めの線で視線を導く", "Use the lines to lead the eye"))
        } else null
    }

    /** 6. Something is moving: timing. */
    val timing = Rule { c ->
        val v = c.a.subjectVelocity ?: return@Rule null
        if (!isSnap(c) || abs(v.x) + abs(v.y) < 0.015f) return@Rule null
        item("timing", tr("抓拍时机", "シャッターチャンス", "Timing"), C, Severity.TIP, tr(
            "有东西在动：先构好图等它走进来，到三分点时再按（可以打开自动快门）",
            "動いているものがあります：先に構図を決めて待ち、三分割の交点に来たらシャッター（自動シャッターも使えます）",
            "Something is moving: compose first and wait for it to walk in, then shoot when it hits a thirds point (auto shutter can help)",
        ), short = tr("等它走到三分点再拍", "三分割点に来たら撮る", "Shoot when it hits a third"))
    }

    /** 7. Animals. */
    val animal = Rule { c ->
        if (c.a.labels.none { it in FrameAnalyzer.ANIMAL_WORDS }) return@Rule null
        item("animal", tr("拍动物", "動物撮影", "Animals"), C, Severity.TIP, tr(
            "拍动物：蹲到和它眼睛一样的高度，对焦在眼睛上，开连拍，它的视线前方多留点空间",
            "動物撮影：目の高さまでしゃがみ、目にピントを合わせ、連写で。視線の先に余白を",
            "Animals: get down to eye level, focus on the eyes, use burst, and leave room in front of its gaze",
        ), short = tr("蹲到动物眼睛高度", "動物の目の高さで", "Get down to its eye level"))
    }

    /** 8. Low aesthetic score without an obvious problem: try another viewpoint. */
    val tryAngle = Rule { c ->
        val a = c.aesthetic ?: return@Rule null
        if (!isSnap(c) || a.norm >= 40) return@Rule null
        item("angle", tr("视角", "視点", "Viewpoint"), C, Severity.TIP, tr(
            "试试换个高度：蹲低贴近地面，或者举高往下拍，日常的东西换个视角就会变有趣",
            "高さを変えてみましょう：しゃがんで地面すれすれから、または高く掲げて見下ろすと、日常のものも面白くなります",
            "Try a different height: crouch down near the ground or hold the camera high and shoot down; everyday things get interesting from a new viewpoint",
        ), short = tr("换个高度试试", "高さを変えてみる", "Try a different height"))
    }

    /** 9. Text (signs, slogans) cut off by the frame edge. */
    val textCut = Rule { c ->
        val name = tr("文字完整", "文字の切れ", "Text complete")
        val main = c.a.texts.maxByOrNull { it.rect.area } ?: return@Rule null
        if (main.rect.area < 0.01f) return@Rule null
        val r = c.toWin(main.rect)
        val txt = main.text.take(10)
        if (r.l < 0.01f || r.r > 0.99f || r.t < 0.01f || r.b > 0.99f) {
            item("text_cut", name, C, Severity.WARN, tr(
                "文字「$txt」被画面边缘切到了：招牌、标语要完整才看得懂",
                "文字「$txt」が画面の端で切れています：看板や標語は全体が入っていないと読めません",
                "The text \"$txt\" is cut off by the frame edge: signs and slogans need to be complete to be readable",
            ), short = tr("文字被边缘切到", "文字が端で切れている", "Text cut off at the edge"))
        } else ok("text_cut", name, C, tr("文字完整", "文字は切れていません", "Text is complete"))
    }

    /** 10. Text shot at an angle. */
    val textTilt = Rule { c ->
        val name = tr("文字角度", "文字の傾き", "Text angle")
        val main = c.a.texts.maxByOrNull { it.rect.area } ?: return@Rule null
        if (main.rect.area < 0.01f) return@Rule null
        val a = main.angle
        if (abs(a) > Cfg.f("text.tilt_deg") && abs(a) < 45f) {
            val deg = abs(a).toInt()
            item("text_tilt", name, C, Severity.INFO, tr(
                "文字斜了约 ${deg}°：正对着拍、或者转一下相机让文字水平（除非是故意的斜构图）",
                "文字が約 ${deg}° 傾いています：正面から撮るか、カメラを回して文字を水平に（意図的な斜め構図でなければ）",
                "The text is tilted by about ${deg}°: shoot it straight on or rotate the camera to level it (unless the tilt is intentional)",
            ), short = tr("文字斜了 ${deg}°", "文字が ${deg}° 傾く", "Text tilted ${deg}°"))
        } else ok("text_tilt", name, C, tr("文字是正的", "文字はまっすぐです", "Text is level"))
    }

    val all = listOf(
        simplicity, getCloser, flatness, lightAndShadow, leadingLines, timing, animal, tryAngle, textCut, textTilt,
    )
}
