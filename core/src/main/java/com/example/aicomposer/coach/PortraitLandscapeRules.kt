package com.example.aicomposer.coach

import com.example.aicomposer.analysis.FrameAnalyzer
import com.example.aicomposer.analysis.P
import com.example.aicomposer.analysis.SceneType
import com.example.aicomposer.i18n.tr
import com.google.mlkit.vision.pose.PoseLandmark
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

object PortraitRules {
    private val C = Category.PORTRAIT

    /** 1. Closed eyes (anyone). */
    val eyesClosed = Rule { c ->
        val name = tr("闭眼", "目つぶり", "Closed eyes")
        if (c.a.faces.isEmpty()) return@Rule null
        val closed = c.a.faces.count { f ->
            val l = f.leftEyeOpen
            val r = f.rightEyeOpen
            l != null && r != null && l < Cfg.f("eyes.closed_prob") && r < Cfg.f("eyes.closed_prob") && f.rect.h > 0.04f
        }
        if (closed > 0) item("eyes", name, C, Severity.ERROR,
            if (c.a.faces.size > 1) tr(
                "有 $closed 个人闭眼了：多拍几张，或者喊「一二三」在三之后再按",
                "$closed 人が目を閉じています：何枚か撮るか、「いち、に、さん」の後にシャッターを",
                "$closed people have their eyes closed: take a few more, or count \"one, two, three\" and shoot right after three",
            )
            else tr("闭眼了：再拍一张", "目を閉じています：もう一枚撮りましょう", "Eyes closed: take another one"),
            short = if (c.a.faces.size > 1) tr("$closed 人闭眼", "$closed 人が目を閉じた", "$closed people blinked")
            else tr("闭眼了", "目を閉じた", "Eyes closed"))
        else ok("eyes", name, C, tr("眼睛都睁着", "全員目を開けています", "Eyes are open"))
    }

    /** 2. Expression. */
    val expression = Rule { c ->
        val name = tr("表情", "表情", "Expression")
        val face = c.a.faces.maxByOrNull { it.rect.area } ?: return@Rule null
        val s = face.smile ?: return@Rule null
        if (face.rect.h < 0.06f) return@Rule null
        if (s < Cfg.f("smile.min")) item("smile", name, C, Severity.TIP, tr(
            "表情比较严肃：想要自然的笑容，可以跟对方聊两句再拍",
            "表情が硬めです：自然な笑顔が欲しければ、少し話しかけてから撮りましょう",
            "The expression is rather serious: for a natural smile, chat a little before shooting",
        ), short = tr("表情比较严肃", "表情が硬め", "Serious expression"))
        else ok("smile", name, C, tr("表情自然", "表情は自然", "Natural expression"))
    }

    /** 3. Headroom. */
    val headroom = Rule { c ->
        val name = tr("头顶空间", "頭上の余白", "Headroom")
        val face = c.a.faces.maxByOrNull { it.rect.area } ?: return@Rule null
        val r = c.toWin(face.rect)
        val headTop = r.t - r.h * 0.25f // there is hair above the face box
        val fullBody = c.a.pose?.points?.containsKey(PoseLandmark.LEFT_ANKLE) == true ||
            c.a.pose?.points?.containsKey(PoseLandmark.RIGHT_ANKLE) == true
        when {
            headTop < 0.01f && r.h < 0.5f -> item("headroom", name, C, Severity.WARN, tr(
                "头顶被切或者太贴边：相机稍微往上抬，或者退后一点",
                "頭が切れているか端に近すぎます：カメラを少し上に向けるか、少し下がりましょう",
                "The top of the head is cut off or too close to the edge: tilt up a little or step back",
            ), move = P(0f, 0.06f), short = tr("头顶被切", "頭が切れている", "Top of the head cut off"))
            !fullBody && r.h > 0.08f && headTop > 0.3f -> item("headroom", name, C, Severity.INFO, tr(
                "头顶上空了太多：相机往下压一点，或者走近一点",
                "頭上の余白が多すぎます：カメラを少し下に向けるか、近づきましょう",
                "Too much space above the head: tilt down a little or move closer",
            ), move = P(0f, -0.06f), short = tr("头顶空太多", "頭上が空きすぎ", "Too much headroom"))
            else -> ok("headroom", name, C, tr("头顶空间合适", "頭上の余白は適切", "Headroom is good"))
        }
    }

    /** 4. Eyes near the upper third line (half-body and close-up shots). */
    val eyeLine = Rule { c ->
        val name = tr("眼睛位置", "目の位置", "Eye line")
        val face = c.a.faces.maxByOrNull { it.rect.area } ?: return@Rule null
        val r = c.toWin(face.rect)
        if (r.h < 0.12f) return@Rule null
        val eyeY = face.eyes.takeIf { it.isNotEmpty() }?.let { e -> c.toWin(P(e.map { it.x }.average().toFloat(), e.map { it.y }.average().toFloat())).y }
            ?: (r.t + r.h * 0.4f)
        if (abs(eyeY - 1 / 3f) > Cfg.f("eye_line.tolerance")) item("eye_line", name, C, Severity.INFO,
            if (eyeY > 1 / 3f) tr(
                "半身/特写时眼睛放在上三分线附近最稳：相机往下压一点",
                "バストアップやアップでは目を上の三分割線付近に置くと安定します：カメラを少し下に向けて",
                "For half-body and close-up shots the eyes work best near the upper third line: tilt down a little",
            ) else tr(
                "半身/特写时眼睛放在上三分线附近最稳：相机往上抬一点",
                "バストアップやアップでは目を上の三分割線付近に置くと安定します：カメラを少し上に向けて",
                "For half-body and close-up shots the eyes work best near the upper third line: tilt up a little",
            ),
            move = P(0f, 1 / 3f - eyeY),
            short = tr("眼睛放到上三分线", "目を上の三分割線へ", "Eyes to the upper third"))
        else ok("eye_line", name, C, tr("眼睛在上三分线附近", "目が上の三分割線付近にあります", "Eyes are near the upper third line"))
    }

    /** 5. Frame edge cutting through a joint (pose detection). */
    val jointCut = Rule { c ->
        val name = tr("关节裁切", "関節での切れ", "Cut at a joint")
        val pts = c.a.pose?.points ?: return@Rule null
        val knee = tr("膝盖", "膝", "knees")
        val ankle = tr("脚踝", "足首", "ankles")
        val wrist = tr("手腕", "手首", "wrists")
        val elbow = tr("手肘", "肘", "elbows")
        val joints = mapOf(
            PoseLandmark.LEFT_KNEE to knee, PoseLandmark.RIGHT_KNEE to knee,
            PoseLandmark.LEFT_ANKLE to ankle, PoseLandmark.RIGHT_ANKLE to ankle,
            PoseLandmark.LEFT_WRIST to wrist, PoseLandmark.RIGHT_WRIST to wrist,
            PoseLandmark.LEFT_ELBOW to elbow, PoseLandmark.RIGHT_ELBOW to elbow,
        )
        val w = c.win
        for ((type, joint) in joints) {
            val p = pts[type] ?: continue
            val d = min(min(abs(p.x - w.l), abs(p.x - w.r)), min(abs(p.y - w.t), abs(p.y - w.b)))
            if (d < Cfg.f("joint_cut.distance")) {
                return@Rule item("joint_cut", name, C, Severity.WARN, tr(
                    "画面边缘正好切在$joint 上，会显得「断肢」：多留一点，或者裁到两个关节中间（比如大腿/小腿中段）",
                    "画面の端がちょうど${joint}で切れていて不自然です：もう少し入れるか、関節の間（太もも・すねの中ほど）で切りましょう",
                    "The frame edge cuts right through the $joint, which looks amputated: include a bit more, or crop between joints (mid-thigh / mid-calf)",
                ), short = tr("边缘切在${joint}上", "${joint}で切れている", "Edge cuts at the $joint"))
            }
        }
        ok("joint_cut", name, C, tr("没有在关节处裁切", "関節で切れていません", "No cut at a joint"))
    }

    /** 6. Facial distortion when shooting close at the wide end. */
    val distortion = Rule { c ->
        val name = tr("人脸变形", "顔のゆがみ", "Facial distortion")
        val face = c.a.faces.maxByOrNull { it.rect.area } ?: return@Rule null
        if (c.focalMm < 45 && face.rect.h > 0.3f && !c.cam.isSony) item("distortion", name, C, Severity.WARN, tr(
            "广角近拍人脸会变形（鼻子显大）：切到 2 倍（或长焦镜头），再退后一步",
            "広角で顔に近づくとゆがみます（鼻が大きく見える）：2 倍（または望遠レンズ）に切り替えて一歩下がりましょう",
            "Shooting a face up close at wide angle distorts it (the nose looks big): switch to 2x (or the telephoto lens) and step back",
        ), short = tr("广角近拍，脸会变形", "広角で顔がゆがむ", "Wide angle distorts the face"))
        else if (c.focalMm < 45 && face.rect.h > 0.3f) item("distortion", name, C, Severity.WARN, tr(
            "${c.focalMm}mm 近拍人脸会变形（鼻子显大）：拉到 50mm 端再退后一步",
            "${c.focalMm}mm で顔に近づくとゆがみます（鼻が大きく見える）：50mm 側にズームして一歩下がりましょう",
            "Shooting a face up close at ${c.focalMm}mm distorts it (the nose looks big): zoom to 50mm and step back",
        ), short = tr("${c.focalMm}mm 近拍，脸会变形", "${c.focalMm}mm で顔がゆがむ", "${c.focalMm}mm distorts the face"))
        else null
    }

    /** 7. Head tilt. */
    val headRoll = Rule { c ->
        val name = tr("头部角度", "頭の傾き", "Head tilt")
        val face = c.a.faces.maxByOrNull { it.rect.area } ?: return@Rule null
        if (abs(face.roll) > 15f && face.rect.h > 0.06f) item("roll", name, C, Severity.TIP, tr(
            "头歪得比较明显：如果不是刻意摆的姿势，可以提醒一下",
            "頭がかなり傾いています：意図したポーズでなければ声をかけましょう",
            "The head is noticeably tilted: if it is not an intended pose, let them know",
        ), short = tr("头歪得明显", "頭が傾いている", "Head is tilted"))
        else null
    }

    val all = listOf(eyesClosed, expression, headroom, eyeLine, jointCut, distortion, headRoll)
}

object LandscapeRules {
    private val C = Category.LANDSCAPE

    private fun isLandscape(c: CoachInput) = c.a.scene == SceneType.LANDSCAPE

    /** 1. Horizon in the dead center. */
    val horizonCenter = Rule { c ->
        val name = tr("地平线位置", "地平線の位置", "Horizon position")
        if (!isLandscape(c)) return@Rule null
        val y = c.a.features?.horizonY ?: return@Rule null
        val wy = (y - c.win.t) / c.win.h
        if (abs(wy - 0.5f) < Cfg.f("horizon.center_band")) item("horizon_pos", name, C, Severity.WARN, tr(
            "地平线在正中间，画面比较平淡：天空好看就把地平线放到下三分线，地面好看就放到上三分线",
            "地平線が真ん中にあり単調です：空がきれいなら地平線を下の三分割線へ、地上がきれいなら上の三分割線へ",
            "The horizon is in the dead center, which looks flat: put it on the lower third if the sky is the star, or the upper third if the ground is",
        ), short = tr("地平线在正中间", "地平線が真ん中", "Horizon in the dead center"))
        else ok("horizon_pos", name, C, tr("地平线没有压在正中间", "地平線は中央を避けています", "The horizon is not in the dead center"))
    }

    /** 2. Share of sky. */
    val skyRatio = Rule { c ->
        val name = tr("天空比例", "空の割合", "Sky ratio")
        if (!isLandscape(c)) return@Rule null
        val y = c.a.features?.horizonY ?: return@Rule null
        val wy = (y - c.win.t) / c.win.h
        when {
            wy > Cfg.f("sky.max") -> item("sky", name, C, Severity.INFO, tr(
                "天空占了 80% 以上：除非云很精彩，否则相机往下压一点",
                "空が 80% 以上を占めています：雲が見事でなければカメラを少し下に",
                "Sky takes up more than 80%: unless the clouds are spectacular, tilt down a little",
            ), short = tr("天空太多", "空が多すぎ", "Too much sky"))
            wy < Cfg.f("sky.min") -> item("sky", name, C, Severity.INFO, tr(
                "几乎没有天空：确定要这样吗？地面不够精彩时可以留点天空",
                "空がほとんどありません：意図通りですか？地上が単調なら空も少し入れましょう",
                "Almost no sky: is that intended? Leave some sky if the ground is not interesting enough",
            ), short = tr("几乎没有天空", "空がほとんどない", "Almost no sky"))
            else -> ok("sky", name, C, tr("天空和地面比例合适", "空と地上の比率は適切", "Good balance of sky and ground"))
        }
    }

    /** 3. Empty foreground. */
    val foreground = Rule { c ->
        val name = tr("前景", "前景", "Foreground")
        if (!isLandscape(c)) return@Rule null
        val f = c.a.features ?: return@Rule null
        val w = c.win
        val bottom = com.example.aicomposer.analysis.NRect(w.l, w.t + w.h * 2 / 3f, w.r, w.b)
        val e = f.edgeMean(bottom) ?: return@Rule null
        if (e < Cfg.f("foreground.min_edge")) item("foreground", name, C, Severity.INFO, tr(
            "前景空荡荡的：找块石头、花草、栏杆放在画面下方当前景，画面会更有层次",
            "前景が空っぽです：岩や草花、柵などを画面下に入れると奥行きが出ます",
            "The foreground is empty: put a rock, flowers or a railing at the bottom of the frame for depth",
        ), short = tr("前景空荡荡", "前景が空っぽ", "Empty foreground"))
        else ok("foreground", name, C, tr("有前景", "前景があります", "There is a foreground"))
    }

    /** 4. Verticals: camera tilted (same direction) or converging from shooting up (opposite directions). */
    val verticals = Rule { c ->
        val name = tr("竖线", "縦線", "Verticals")
        val f = c.a.features ?: return@Rule null
        val l = f.vertLeft
        val r = f.vertRight
        val arch = c.a.labels.any { it in FrameAnalyzer.ARCHITECTURE_WORDS }
        if (l == null && r == null) return@Rule null
        if (l != null && r != null && l * r < 0 && abs(l) > Cfg.f("verticals.deg") && abs(r) > Cfg.f("verticals.deg")) {
            return@Rule item("verticals", name, C, if (arch) Severity.WARN else Severity.INFO, tr(
                "竖线向上（或向下）汇聚，这是仰拍/俯拍造成的梯形变形：退后一点、相机端平再拍，或者后期做透视校正",
                "縦線が上（または下）に向かって集まっています。あおり・俯瞰による台形ゆがみです：少し下がってカメラを水平に構えるか、後で遠近補正を",
                "Verticals converge upwards (or downwards) from shooting up/down: step back and hold the camera level, or correct perspective later",
            ), short = tr("竖线汇聚（梯形）", "縦線が台形にゆがむ", "Converging verticals"))
        }
        val avg = listOfNotNull(l, r).average().toFloat()
        if (abs(avg) > Cfg.f("verticals.deg")) {
            val deg = String.format(Locale.ROOT, "%.1f", abs(avg))
            item("verticals", name, C, if (arch) Severity.WARN else Severity.INFO, tr(
                "竖线整体歪了约 ${deg}°：相机没有端正，拍建筑时竖线要竖直",
                "縦線全体が約 ${deg}° 傾いています：カメラがまっすぐではありません。建物は縦線を垂直に",
                "Verticals lean by about ${deg}°: the camera is not straight; keep verticals vertical for architecture",
            ), short = tr("竖线歪了 ${deg}°", "縦線が ${deg}° 傾く", "Verticals lean ${deg}°"))
        } else ok("verticals", name, C, tr("竖线是直的", "縦線はまっすぐです", "Verticals are straight"))
    }

    val all = listOf(horizonCenter, skyRatio, foreground, verticals)
}

object AestheticRules {
    private val C = Category.AESTHETIC

    val aestheticScore = Rule { c ->
        val name = tr("AI 美学", "AI 美的評価", "AI aesthetics")
        val a = c.aesthetic ?: return@Rule null
        val s = String.format(Locale.ROOT, "%.1f", a.score)
        when {
            a.norm < Cfg.d("aesthetic.low_norm") -> item("aesthetic", name, C, Severity.INFO, tr(
                "AI 美学分偏低（$s）：换个角度、靠近一点，或点「AI 构图」看看",
                "AI の美的評価が低め（$s）：角度を変える、近づく、または「AI 構図」を試してみましょう",
                "AI aesthetic score is low ($s): try another angle, move closer, or tap \"AI framing\"",
            ), short = tr("AI 美学分偏低（$s）", "AI 美的評価が低め（$s）", "Low AI aesthetic score ($s)"))
            a.norm >= 70 -> ok("aesthetic", name, C, tr("AI 美学分不错（$s）", "AI の美的評価は良好（$s）", "AI aesthetic score is good ($s)"))
            else -> ok("aesthetic", name, C, tr("AI 美学分一般（$s）", "AI の美的評価はふつう（$s）", "AI aesthetic score is average ($s)"))
        }
    }

    val all = listOf(aestheticScore)
}
