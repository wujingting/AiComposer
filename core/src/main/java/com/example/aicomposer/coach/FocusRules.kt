package com.example.aicomposer.coach

import com.example.aicomposer.analysis.ParameterAdvisor
import com.example.aicomposer.analysis.SceneType
import com.example.aicomposer.i18n.cap
import com.example.aicomposer.i18n.tr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object FocusRules {
    private val C = Category.FOCUS

    private fun fmtShutter(t: Double) = if (t >= 0.3) String.format(Locale.ROOT, "%.1f\"", t) else "1/${(1 / t).roundToInt()}"
    private fun f1(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    /** 1. Is the current shutter fast enough? */
    val shutter = Rule { c ->
        val name = tr("快门速度", "シャッタースピード", "Shutter speed")
        val cur = ParameterAdvisor.parseShutter(c.cam.shutter) ?: return@Rule null
        val need = ParameterAdvisor.requiredShutter(c.a, c.focalMm, c.plan)
        val s = c.cam.shutter
        val n = fmtShutter(need)
        when {
            cur > need * Cfg.d("shutter.error_ratio") -> item("shutter", name, C, Severity.ERROR, tr(
                "快门 $s 太慢，大概率会糊：至少要 $n",
                "シャッター $s は遅すぎてブレる可能性大：最低 $n に",
                "Shutter $s is too slow and will likely blur: use at least $n",
            ), short = tr("快门 $s 太慢会糊", "シャッター $s は遅すぎ", "Shutter $s too slow"))
            cur > need * Cfg.d("shutter.warn_ratio") -> item("shutter", name, C, Severity.WARN, tr(
                "快门 $s 偏慢，有糊的风险：最好 $n 以上",
                "シャッター $s はやや遅くブレの恐れ：$n 以上がおすすめ",
                "Shutter $s is a bit slow and risks blur: $n or faster is better",
            ), short = tr("快门 $s 偏慢", "シャッター $s はやや遅い", "Shutter $s a bit slow"))
            else -> ok("shutter", name, C, tr("快门 $s 够快", "シャッター $s で十分", "Shutter $s is fast enough"))
        }
    }

    /** 2. ISO too high. */
    val iso = Rule { c ->
        val name = tr("ISO / 噪点", "ISO・ノイズ", "ISO / noise")
        val cur = c.cam.iso?.toIntOrNull() ?: return@Rule null
        val max = ParameterAdvisor.maxIsoFor(c.a, c.plan)
        when {
            cur > max * 2 -> item("iso", name, C, Severity.WARN, tr(
                "ISO $cur 噪点会很明显（建议不超过 $max）：开大光圈、放慢快门或者找更亮的地方",
                "ISO $cur ではノイズが目立ちます（$max 以下推奨）：絞りを開ける、シャッターを遅くする、明るい場所へ",
                "ISO $cur will be noticeably noisy (keep it at $max or below): open the aperture, slow the shutter, or find more light",
            ), short = tr("ISO $cur 噪点明显", "ISO $cur はノイズが目立つ", "ISO $cur: visible noise"))
            cur > max -> item("iso", name, C, Severity.INFO, tr(
                "ISO $cur 有点高（建议不超过 $max）",
                "ISO $cur はやや高め（$max 以下推奨）",
                "ISO $cur is a bit high (keep it at $max or below)",
            ), short = tr("ISO $cur 偏高", "ISO $cur はやや高い", "ISO $cur a bit high"))
            else -> ok("iso", name, C, tr("ISO $cur 没问题", "ISO $cur で問題なし", "ISO $cur is fine"))
        }
    }

    /** 3. Depth of field: everyone sharp in group shots; focus precision reminder for a single person at wide apertures. */
    val depthOfField = Rule { c ->
        val name = tr("景深", "被写界深度", "Depth of field")
        val n = c.cam.fNumber?.toDoubleOrNull() ?: return@Rule null
        val range = Optics.groupRange(c.a.faces, c.focalMm)
        if (range != null) {
            val (near, far) = range
            val need = Optics.requiredF(c.focalMm, near, far)
            val s = 2 * near * far / (near + far)
            val (dn, df) = Optics.dof(c.focalMm, n, s)
            return@Rule if (dn > near || df < far) {
                val gap = f1(far - near)
                val fix = need?.let { tr("收到 f/$it 以上", "f/$it 以上に絞りましょう", "stop down to f/$it or more") }
                    ?: tr("让大家站到同一排", "全員を同じ列に並ばせましょう", "have everyone stand in one row")
                item("dof", name, C, Severity.ERROR, tr(
                    "多人前后相差约 ${gap}m，f/$n 景深不够，会有人糊：$fix",
                    "前後の人の距離差が約 ${gap}m あり、f/$n では被写界深度が足りずボケる人が出ます：$fix",
                    "People are about ${gap}m apart front to back; f/$n is not enough depth of field and someone will be blurry: $fix",
                ), short = tr("景深不够，有人会糊", "被写界深度不足：ボケる人も", "Some faces will be blurry"))
            } else ok("dof", name, C, tr("f/$n 的景深能覆盖所有人", "f/$n の被写界深度で全員カバーできます", "f/$n has enough depth of field for everyone"))
        }
        val face = c.a.faces.filter { it.rect.h > 0.05f }.maxByOrNull { it.rect.area } ?: return@Rule null
        val d = Optics.faceDistance(face.rect.h, c.focalMm)
        val depth = Optics.dofDepth(c.focalMm, n, d)
        val cm = (depth * 100).roundToInt()
        val dist = f1(d)
        when {
            depth < Cfg.d("dof.side_face_depth") && abs(face.yaw) > 25f -> item("dof", name, C, Severity.WARN, tr(
                "侧脸 + 景深只有约 ${cm}cm，两只眼睛可能一只虚：对焦在靠近镜头的那只眼，或收到 f/2.8",
                "横顔で被写界深度は約 ${cm}cm、片目がボケるかもしれません：手前の目にピントを合わせるか、f/2.8 まで絞りましょう",
                "Profile view with only about ${cm}cm depth of field, one eye may be soft: focus on the eye nearer the lens, or stop down to f/2.8",
            ), short = tr("侧脸：对焦近侧眼睛", "横顔：手前の目にピント", "Profile: focus the near eye"))
            depth < Cfg.d("dof.thin_depth") -> item("dof", name, C, Severity.INFO, tr(
                "距离约 ${dist}m，景深只有约 ${cm}cm：一定要对准眼睛，拍的时候身体别前后晃",
                "距離約 ${dist}m で被写界深度は約 ${cm}cm：必ず目にピントを合わせ、撮影中は体を前後に動かさないように",
                "About ${dist}m away with only about ${cm}cm depth of field: focus precisely on the eyes and do not sway back and forth",
            ), short = tr("景深只有 ${cm}cm", "被写界深度は ${cm}cm だけ", "Only ${cm}cm in focus"))
            else -> ok("dof", name, C, tr(
                "距离约 ${dist}m，景深约 ${cm}cm，够用",
                "距離約 ${dist}m、被写界深度約 ${cm}cm で十分",
                "About ${dist}m away, about ${cm}cm depth of field, enough",
            ))
        }
    }

    /** 4. Is the focus on the subject? Compare subject and background sharpness. */
    val focusOnSubject = Rule { c ->
        val name = tr("焦点", "ピント", "Focus")
        val f = c.a.features ?: return@Rule null
        val m = c.a.mainSubject ?: return@Rule null
        if (m.rect.area < 0.01f || m.rect.area > 0.6f) return@Rule null
        val subj = f.sharpness(m.rect) ?: return@Rule null
        val bg = f.sharpness(c.win, exclude = m.rect) ?: return@Rule null
        if (bg > Cfg.f("focus.min_bg") && subj < bg * Cfg.f("focus.ratio")) item("focus", name, C, Severity.WARN, tr(
            "看起来清楚的是背景而不是${m.label}：检查对焦点（取景画面不一定是最终对焦，半按快门后再看）",
            "${m.label}ではなく背景にピントが合っているようです：フォーカス位置を確認（ライブビューは最終的なピントとは限らないので、半押し後に確認）",
            "The background looks sharper than ${m.label}: check the focus point (the liveview may not show final focus; check after a half-press)",
        ), short = tr("焦点跑到背景上了", "ピントが背景に合っている", "Focus is on the background"))
        else ok("focus", name, C, tr("主体比背景清楚", "被写体は背景よりシャープ", "${"the subject".cap()} is sharper than the background"))
    }

    /** 5. The whole frame is shaking. */
    val shake = Rule { c ->
        val sm = c.a.subjectMotion
        if (c.a.motion > Cfg.f("shake.motion") && c.a.scene != SceneType.ACTION && (sm == null || sm < c.a.motion * 1.3f)) {
            item("shake", tr("持机稳定", "手ブレ", "Camera shake"), C, Severity.INFO, tr(
                "整个画面在晃：拿稳一点，手肘夹紧身体或者靠着东西",
                "画面全体が揺れています：脇を締めるか、何かに寄りかかってしっかり構えましょう",
                "The whole frame is shaking: hold steadier, tuck your elbows in or lean on something",
            ), short = tr("画面在晃，拿稳", "揺れている：しっかり構える", "Shaking: hold steadier"))
        } else null
    }

    /** 6. Focus mode / burst advice. */
    val afMode = Rule { c ->
        val name = tr("对焦模式", "フォーカスモード", "Focus mode")
        val n = c.cam.fNumber?.toDoubleOrNull()
        when {
            // Phones focus automatically; the a6000-specific focus advice does not apply
            !c.cam.isSony -> if (c.a.scene == SceneType.ACTION) item("af_mode", name, C, Severity.TIP, tr(
                "动体：先点一下主体对焦，再多拍几张提高命中率",
                "動体：被写体をタップしてピントを合わせ、何枚か撮って歩留まりアップ",
                "Action: tap the subject to focus, then take several shots for a higher hit rate",
            ), short = tr("点主体对焦再多拍", "タップでピント→数枚撮る", "Tap to focus, shoot several")) else null
            c.a.scene == SceneType.ACTION -> item("af_mode", name, C, Severity.TIP, tr(
                "动体：对焦模式用 AF-C（连续对焦）+ 连拍，提高命中率",
                "動体：AF-C（コンティニュアス AF）＋連写で歩留まりアップ",
                "Action: use AF-C (continuous AF) + burst for a higher hit rate",
            ), short = tr("动体：AF-C + 连拍", "動体：AF-C＋連写", "Action: AF-C + burst"))
            c.a.faces.isNotEmpty() && n != null && n <= 2.8 -> item("af_mode", name, C, Severity.TIP, tr(
                "大光圈拍人：用眼部对焦（a6000 需要先把「眼部对焦」分配到自定义键，拍的时候按住）",
                "開放付近で人物撮影：瞳 AF を使いましょう（a6000 は「瞳 AF」をカスタムキーに割り当て、撮影時に押し続ける）",
                "People at a wide aperture: use Eye AF (on the a6000, assign \"Eye AF\" to a custom key and hold it while shooting)",
            ), short = tr("大光圈拍人：用眼部对焦", "開放で人物：瞳 AF を", "Wide aperture: use Eye AF"))
            else -> null
        }
    }

    /** 7. Enough pixels left after cropping? */
    val cropResolution = Rule { c ->
        val name = tr("裁剪后像素", "トリミング後の画素数", "Pixels after crop")
        if (!c.plan.active) return@Rule null
        val mp = f1(c.plan.megapixels(c.a.frameAspect).toDouble())
        if (c.plan.megapixels(c.a.frameAspect) < 8f) item("crop_res", name, C, Severity.WARN, tr(
            "预留裁剪后只剩约 $mp 百万像素：手机/网上看没问题，大尺寸打印会不够。能走近就走近",
            "トリミング後は約 $mp メガピクセル：スマホや Web なら問題ありませんが、大きなプリントには不足。近づけるなら近づきましょう",
            "Only about $mp MP left after the planned crop: fine for phones and the web, not enough for large prints. Move closer if you can",
        ), short = tr("裁剪后只剩 $mp 百万像素", "トリミング後 $mp MP のみ", "Only $mp MP after the crop"))
        else ok("crop_res", name, C, tr(
            "裁剪后约 $mp 百万像素，够用",
            "トリミング後約 $mp メガピクセルで十分",
            "About $mp MP after cropping, enough",
        ))
    }

    val all = listOf(shutter, iso, depthOfField, focusOnSubject, shake, afMode, cropResolution)
}
