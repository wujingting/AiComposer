package com.example.aicomposer.coach

import com.example.aicomposer.analysis.FrameAnalyzer
import com.example.aicomposer.analysis.SceneType
import com.example.aicomposer.i18n.tr
import kotlin.math.abs
import kotlin.math.roundToInt

object LightRules {
    private val C = Category.LIGHT

    /** 1. Backlight: subject much darker than the frame overall. */
    val backlight = Rule { c ->
        val name = tr("逆光", "逆光", "Backlight")
        val l = c.a.luma
        val subj = c.a.faces.maxByOrNull { it.rect.area }?.rect ?: c.a.mainSubject?.rect ?: return@Rule null
        val sm = c.a.features?.lumaStats(subj)?.first ?: l.subjectMean ?: return@Rule null
        if (sm < l.mean * Cfg.f("backlight.ratio") && l.highlightClip > Cfg.f("backlight.min_clip")) {
            item("backlight", name, C, Severity.WARN, tr(
                "逆光，主体偏暗：曝光补偿 +1EV、用闪光灯/反光板补光，或者转个方向让光从侧面来",
                "逆光で被写体が暗い：露出補正 +1EV、フラッシュやレフ板で補光、または向きを変えて横から光を当てましょう",
                "Backlit, the subject is dark: +1 EV exposure compensation, fill with flash or a reflector, or turn so the light comes from the side",
            ), short = tr("逆光，主体偏暗", "逆光で被写体が暗い", "Backlit: subject too dark"))
        } else ok("backlight", name, C, tr("主体亮度正常", "被写体の明るさは正常", "Subject brightness is fine"))
    }

    /** 2. Clipped highlights / crushed shadows. */
    val clipping = Rule { c ->
        val name = tr("过曝/死黑", "白飛び・黒つぶれ", "Clipping")
        val l = c.a.luma
        val hi = (l.highlightClip * 100).roundToInt()
        val lo = (l.shadowClip * 100).roundToInt()
        when {
            l.highlightClip > Cfg.f("clip.highlight_warn") -> item("clipping", name, C, Severity.WARN, tr(
                "$hi% 的画面过曝（白成一片没有细节）：曝光补偿往负调，或者避开天空/灯光",
                "画面の $hi% が白飛び（真っ白でディテールなし）：露出補正をマイナスに、または空や照明を避けましょう",
                "$hi% of the frame is blown out (pure white, no detail): lower the exposure compensation or avoid the sky / lights",
            ), short = tr("$hi% 的画面过曝", "画面の $hi% が白飛び", "$hi% blown out"))
            l.shadowClip > Cfg.f("clip.shadow_info") && c.a.scene != SceneType.NIGHT -> item("clipping", name, C, Severity.INFO, tr(
                "$lo% 的画面黑成一片：如果暗部有重要内容，曝光补偿往正调",
                "画面の $lo% が黒つぶれ：暗部に大事なものがあれば露出補正をプラスに",
                "$lo% of the frame is pure black: raise the exposure compensation if something important is in the shadows",
            ), short = tr("$lo% 的画面死黑", "画面の $lo% が黒つぶれ", "$lo% crushed to black"))
            else -> ok("clipping", name, C, tr("高光和暗部都有细节", "明部も暗部もディテールあり", "Highlights and shadows both have detail"))
        }
    }

    /** 3. Overexposed face. */
    val faceClip = Rule { c ->
        val name = tr("脸部过曝", "顔の白飛び", "Face overexposed")
        val f = c.a.features ?: return@Rule null
        val face = c.a.faces.maxByOrNull { it.rect.area } ?: return@Rule null
        if (f.clipFraction(face.rect) > Cfg.f("face_clip.fraction")) item("face_clip", name, C, Severity.ERROR, tr(
            "脸上有一块过曝了：曝光补偿 -0.7EV，或者让人背对阳光/走到阴影里",
            "顔の一部が白飛びしています：露出補正 -0.7EV、または太陽を背にするか日陰へ",
            "Part of the face is blown out: -0.7 EV exposure compensation, or have the person turn their back to the sun / move into shade",
        ), short = tr("脸上过曝了", "顔が白飛び", "Face blown out"))
        else ok("face_clip", name, C, tr("脸部曝光正常", "顔の露出は正常", "Face exposure is fine"))
    }

    /** 4. Contrast exceeds the camera's dynamic range. */
    val dynamicRange = Rule { c ->
        val name = tr("光比", "明暗差", "Dynamic range")
        val l = c.a.luma
        if (l.highlightClip > Cfg.f("dr.highlight") && l.shadowClip > Cfg.f("dr.shadow")) item("dynamic_range", name, C, Severity.WARN, tr(
            "亮部和暗部差太多，相机装不下：开 DRO / 自动 HDR，或者拍 RAW 后期拉回",
            "明暗差が大きすぎてカメラで収まりません：DRO／オート HDR を使うか、RAW で撮って後で補正しましょう",
            "Too much contrast between highlights and shadows for the camera: turn on DRO / Auto HDR, or shoot RAW and recover later",
        ), short = tr("明暗差太大", "明暗差が大きすぎる", "Too much contrast"))
        else ok("dynamic_range", name, C, tr("光比在相机能力范围内", "明暗差はカメラの範囲内", "Contrast is within the camera's range"))
    }

    /** 5. Hard / overhead light: strong contrast on the face. */
    val hardLight = Rule { c ->
        val name = tr("脸部光线", "顔の光", "Light on the face")
        val f = c.a.features ?: return@Rule null
        val face = c.a.faces.filter { it.rect.h > 0.06f }.maxByOrNull { it.rect.area } ?: return@Rule null
        val (mean, std) = f.lumaStats(face.rect) ?: return@Rule null
        if (std > Cfg.f("hard_light.std") && mean > 0.15f) item("hard_light", name, C, Severity.WARN, tr(
            "脸上明暗反差很大（硬光/顶光），会有难看的阴影：换到树荫、建筑阴影下，或者用反光板补一下暗部",
            "顔の明暗差が大きく（硬い光・トップライト）、汚い影が出ます：木陰や建物の陰に移るか、レフ板で暗部を起こしましょう",
            "Strong contrast on the face (hard / overhead light) gives ugly shadows: move into the shade of a tree or building, or fill the shadows with a reflector",
        ), short = tr("脸上有硬光阴影", "顔に硬い影", "Harsh shadows on the face"))
        else ok("hard_light", name, C, tr("脸上的光比较柔和", "顔の光は柔らかめ", "The light on the face is fairly soft"))
    }

    /** 6. Color cast / mixed lighting. */
    val whiteBalance = Rule { c ->
        val name = tr("白平衡", "ホワイトバランス", "White balance")
        val f = c.a.features ?: return@Rule null
        val rb = (f.meanR + 0.01f) / (f.meanB + 0.01f)
        val mixed = abs(f.warmLeft - f.warmRight) / ((f.warmLeft + f.warmRight) / 2f)
        when {
            mixed > Cfg.f("wb.mixed") -> item("wb", name, C, Severity.INFO, tr(
                "画面左右颜色不一样（混合光源，比如窗光+室内灯）：拍 RAW 方便后期分别调色，或者关掉其中一种灯",
                "画面の左右で色が違います（窓の光＋室内灯などのミックス光）：RAW で撮って後で個別に調整するか、どちらかの光を消しましょう",
                "Left and right have different colors (mixed light, e.g. window + indoor lamps): shoot RAW to correct separately, or switch one light off",
            ), short = tr("混合光源，左右偏色", "ミックス光で色がばらつく", "Mixed light colors"))
            rb > Cfg.f("wb.warm") && c.a.labels.none { it == "Sunset" } -> item("wb", name, C, Severity.INFO, tr(
                "画面偏黄/偏暖（室内灯？）：如果不是想要的氛围，白平衡设成「白炽灯」或者拍 RAW",
                "画面が黄色っぽい・暖色寄り（室内灯？）：狙った雰囲気でなければ WB を「電球」にするか RAW で撮影",
                "The image looks yellow / warm (indoor lights?): if that is not the mood you want, set white balance to \"Incandescent\" or shoot RAW",
            ), short = tr("画面偏黄", "黄色っぽい", "Colors too warm"))
            rb < Cfg.f("wb.cool") -> item("wb", name, C, Severity.INFO, tr(
                "画面偏蓝/偏冷（阴天、阴影里？）：白平衡设成「阴天」或「阴影」会更自然",
                "画面が青っぽい・寒色寄り（曇りや日陰？）：WB を「曇天」か「日陰」にすると自然になります",
                "The image looks blue / cool (overcast or shade?): set white balance to \"Cloudy\" or \"Shade\" for more natural colors",
            ), short = tr("画面偏蓝", "青っぽい", "Colors too cool"))
            else -> ok("wb", name, C, tr("颜色正常", "色は正常", "Colors look normal"))
        }
    }

    /** 7. Snow / beach: tends to come out grey. */
    val brightScene = Rule { c ->
        if (c.a.luma.mean > 0.65f && c.a.labels.any { it in FrameAnalyzer.BRIGHT_WORDS }) {
            item("bright_scene", tr("亮场景", "明るいシーン", "Bright scene"), C, Severity.INFO, tr(
                "雪地/沙滩相机容易测光偏暗、拍成灰色：曝光补偿 +0.7EV",
                "雪や砂浜はカメラが暗めに測光してグレーに写りがち：露出補正 +0.7EV",
                "On snow / beaches the camera tends to underexpose and make it grey: +0.7 EV exposure compensation",
            ), short = tr("雪地/沙滩：+0.7EV", "雪・砂浜：+0.7EV", "Snow or beach: +0.7 EV"))
        } else null
    }

    /** 8. Low light. */
    val lowLight = Rule { c ->
        if (c.a.scene == SceneType.NIGHT && !c.cam.isSony) item("low_light", tr("暗光", "暗所", "Low light"), C, Severity.TIP, tr(
            "暗光：手机拿稳或靠着东西，按下后别马上移动；有人物时可以用闪光灯",
            "暗所：スマホをしっかり構えるか何かに寄りかかり、撮影後すぐ動かさない。人物がいればフラッシュも",
            "Low light: hold the phone steady or brace it, and do not move right after tapping; use flash for people",
        ), short = tr("暗光：拿稳手机", "暗所：しっかり構える", "Low light: hold steady"))
        else if (c.a.scene == SceneType.NIGHT) item("low_light", tr("暗光", "暗所", "Low light"), C, Severity.TIP, tr(
            "暗光：开大光圈、靠着东西或用三脚架、拍 RAW；有人物时可以用闪光灯",
            "暗所：絞りを開ける、何かに寄りかかるか三脚を使う、RAW で撮る。人物がいればフラッシュも",
            "Low light: open the aperture, lean on something or use a tripod, shoot RAW; use flash for people",
        ), short = tr("暗光：开大光圈、拿稳", "暗所：絞りを開けて安定", "Low light: open up, brace"))
        else null
    }

    val all = listOf(backlight, clipping, faceClip, dynamicRange, hardLight, whiteBalance, brightScene, lowLight)
}
