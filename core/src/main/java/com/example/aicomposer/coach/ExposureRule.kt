package com.example.aicomposer.coach

import com.example.aicomposer.analysis.FrameAnalyzer
import com.example.aicomposer.analysis.ParameterAdvisor
import com.example.aicomposer.analysis.SceneType
import com.example.aicomposer.camera.CameraKind
import com.example.aicomposer.i18n.I18n
import com.example.aicomposer.i18n.Lang
import com.example.aicomposer.i18n.tr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Overall exposure: when the frame is too dark / too bright, say exactly which dial to turn and how far.
 *
 * Requires the camera setting "Live View Display -> Setting Effect: ON" so the liveview brightness matches the shot.
 *
 * Too dark (needs more light), in this order:
 *   1. Slower shutter — but not slower than the "safe shutter" (shake, moving subjects)
 *   2. Wider aperture — up to the lens's widest aperture
 *   3. Higher ISO — up to the ISO limit (lower when a crop is planned)
 *   4. Still not enough -> tripod / flash / find more light
 * Too bright (needs less light):
 *   1. ISO down to 100   2. Faster shutter (up to 1/4000)   3. Smaller aperture (softens from diffraction past f/11)   4. ND filter
 *
 * In automatic modes (P/A/S) the camera adjusts by itself; if the frame is still too dark / bright it has hit a
 * limit, so the advice depends on the mode.
 */
object ExposureRule {
    private val C = Category.LIGHT

    private fun fmtT(t: Double) = if (t >= 0.3) String.format(Locale.ROOT, "%.1f\"", t) else "1/${(1 / t).roundToInt()}"
    private fun fmtF(f: Double) = if (f >= 10) f.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", f)
    private fun fmtEv(ev: Double) = String.format(Locale.ROOT, "%.1f", abs(ev))

    /** Separator between steps: "，" in CJK, "; " in English. */
    private val stepSep: String get() = if (I18n.lang == Lang.EN) "; " else tr("，", "、", "; ")

    /** How many stops the liveview brightness is off from middle grey (18%). Positive = brighter. */
    fun deltaEv(mean: Float): Double {
        val lin = max(mean.toDouble(), 0.01).pow(2.2)
        return (ln(lin / 0.18) / ln(2.0)).coerceIn(-4.0, 4.0)
    }

    val exposure = Rule { c ->
        val name = tr("整体曝光", "全体の露出", "Overall exposure")
        val l = c.a.luma
        val delta = deltaEv(l.mean)
        val bright = c.a.labels.any { it in FrameAnalyzer.BRIGHT_WORDS }
        val underTh = if (c.a.scene == SceneType.NIGHT) Cfg.d("exposure.night_under_ev") else Cfg.d("exposure.under_ev")
        val overTh = Cfg.d("exposure.over_ev")

        val tooDark = delta < -underTh
        // Snow / beach is bright anyway; large clipped areas are handled by the clipping rule
        val tooBright = delta > overTh && !bright
        if (!tooDark && !tooBright) return@Rule ok("exposure", name, C, tr("整体亮度合适", "全体の明るさは適切", "Overall brightness is good"))

        val stops = -delta // positive = needs more light
        val sev = if (abs(delta) > 1.5) Severity.WARN else Severity.INFO
        val e = fmtEv(delta)
        val head = if (tooDark) tr("画面偏暗约 $e 档", "画面が約 $e 段暗い", "About $e stops too dark")
        else tr("画面偏亮约 $e 档", "画面が約 $e 段明るい", "About $e stops too bright")

        val mode = c.cam.exposureMode ?: ""
        val f = c.cam.fNumber?.toDoubleOrNull()
        val t = ParameterAdvisor.parseShutter(c.cam.shutter)
        val iso = c.cam.iso?.toDoubleOrNull()

        val msg = if (f != null && t != null && iso != null && mode.startsWith("Manual", ignoreCase = true)) {
            manualAdvice(c, stops, f, t, iso)
        } else {
            autoAdvice(c, tooDark, mode, f, iso)
        }
        item("exposure", name, C, sev, tr("$head：$msg", "$head：$msg", "$head: $msg"), short = head)
    }

    /** M mode (all settings known): work out where each dial should go, in order. */
    private fun manualAdvice(c: CoachInput, need: Double, f0: Double, t0: Double, iso0: Double): String {
        val steps = mutableListOf<String>()
        var remain = need
        var f = f0; var t = t0; var iso = iso0
        val fList = c.cam.fNumberCandidates.mapNotNull { it.toDoubleOrNull() }
        val minF = fList.minOrNull() ?: min(f0, 3.5)
        val maxF = min(fList.maxOrNull() ?: 16.0, 16.0)
        val maxIso = ParameterAdvisor.maxIsoFor(c.a, c.plan).toDouble()
        val tSafe = ParameterAdvisor.requiredShutter(c.a, c.focalMm, c.plan)

        if (remain > 0) {
            // 1. Slower shutter (not slower than the safe shutter)
            val canT = log2(tSafe / t).coerceAtLeast(0.0)
            val useT = min(remain, canT)
            if (useT > 0.15) {
                t *= 2.0.pow(useT); remain -= useT
                steps += tr("快门放慢到 ${fmtT(t)}", "シャッターを ${fmtT(t)} まで遅く", "slow the shutter to ${fmtT(t)}")
            }
            // 2. Wider aperture
            val canF = (2 * log2(f / minF)).coerceAtLeast(0.0)
            val useF = min(remain, canF)
            if (useF > 0.15) {
                f /= 2.0.pow(useF / 2); remain -= useF
                steps += tr("光圈开到 f/${fmtF(f)}", "絞りを f/${fmtF(f)} まで開ける", "open the aperture to f/${fmtF(f)}")
            }
            // 3. Higher ISO
            val canI = log2(maxIso / iso).coerceAtLeast(0.0)
            val useI = min(remain, canI)
            if (useI > 0.15) {
                iso *= 2.0.pow(useI); remain -= useI
                steps += tr("ISO 提到 ${iso.roundToInt()}", "ISO を ${iso.roundToInt()} に上げる", "raise ISO to ${iso.roundToInt()}")
            }
            if (remain > 0.3) {
                val r = fmtEv(remain)
                val mi = maxIso.roundToInt()
                steps += tr(
                    "还差约 $r 档：用三脚架放慢快门、开闪光灯、找更亮的地方，或者接受 ISO 超过 $mi",
                    "まだ約 $r 段足りません：三脚でシャッターを遅くする、フラッシュを使う、明るい場所に移る、または ISO $mi 超えを許容",
                    "still about $r stops short: use a tripod for a slower shutter, use flash, find more light, or accept ISO above $mi",
                )
            } else if (useT <= 0.15 && canT <= 0.15) {
                steps.add(0, tr(
                    "快门已经是安全快门的底线（${fmtT(tSafe)}），不能再慢",
                    "シャッターは既に手ブレ限界（${fmtT(tSafe)}）でこれ以上遅くできません",
                    "the shutter is already at the safe limit (${fmtT(tSafe)}) and cannot go slower",
                ))
            }
        } else {
            remain = -remain
            // 1. ISO down to 100
            val useI = min(remain, log2(iso / 100.0).coerceAtLeast(0.0))
            if (useI > 0.15) {
                iso /= 2.0.pow(useI); remain -= useI
                steps += tr("ISO 降到 ${iso.roundToInt()}", "ISO を ${iso.roundToInt()} に下げる", "lower ISO to ${iso.roundToInt()}")
            }
            // 2. Faster shutter
            val useT = min(remain, log2(t / (1.0 / 4000)).coerceAtLeast(0.0))
            if (useT > 0.15) {
                t /= 2.0.pow(useT); remain -= useT
                steps += tr("快门加快到 ${fmtT(t)}", "シャッターを ${fmtT(t)} まで速く", "speed the shutter up to ${fmtT(t)}")
            }
            // 3. Smaller aperture
            val useF = min(remain, (2 * log2(maxF / f)).coerceAtLeast(0.0))
            if (useF > 0.15) {
                f *= 2.0.pow(useF / 2); remain -= useF
                steps += tr("光圈收到 f/${fmtF(f)}", "絞りを f/${fmtF(f)} まで絞る", "stop down to f/${fmtF(f)}") +
                    if (f > 11) tr("（f/11 以上会因为衍射变软）", "（f/11 以上は回折で甘くなります）", " (past f/11 diffraction softens the image)") else ""
            }
            if (remain > 0.3) {
                val r = fmtEv(remain)
                steps += tr("还差约 $r 档：需要 ND 减光镜", "まだ約 $r 段多い：ND フィルターが必要です", "still about $r stops over: you need an ND filter")
            }
        }
        return steps.joinToString(stepSep)
    }

    /** Auto / semi-auto modes: the camera adjusts by itself, so too dark / bright means it has hit a limit. */
    private fun autoAdvice(c: CoachInput, tooDark: Boolean, mode: String, f: Double?, iso: Double?): String {
        if (c.cam.kind == CameraKind.PHOTO) return photoAdvice(tooDark)
        if (!c.cam.isSony) return phoneAdvice(tooDark)
        val maxIso = ParameterAdvisor.maxIsoFor(c.a, c.plan)
        val minF = c.cam.fNumberCandidates.mapNotNull { it.toDoubleOrNull() }.minOrNull()
        val ev = c.cam.evValue ?: 0.0
        val evs = ParameterAdvisor.formatEv(ev)
        return if (tooDark) {
            val tips = mutableListOf<String>()
            if (ev < -0.2) tips += tr("先检查曝光补偿：现在是 $evs", "まず露出補正を確認：現在 $evs", "check exposure compensation first: currently $evs")
            if (iso == null) tips += tr(
                "ISO 是 AUTO：在菜单里把 ISO AUTO 的上限提高到 $maxIso",
                "ISO が AUTO：メニューで ISO AUTO の上限を $maxIso に上げる",
                "ISO is AUTO: raise the ISO AUTO upper limit to $maxIso in the menu",
            )
            else if (iso < maxIso) {
                val target = maxIso.coerceAtMost((iso * 4).roundToInt())
                tips += tr("ISO 提到 $target 左右", "ISO を $target 前後に上げる", "raise ISO to about $target")
            }
            if (f != null && minF != null && f > minF + 0.1 && !mode.startsWith("Shutter", true)) {
                tips += tr("光圈开到 f/${fmtF(minF)}", "絞りを f/${fmtF(minF)} まで開ける", "open the aperture to f/${fmtF(minF)}")
            }
            if (mode.startsWith("Shutter", true)) {
                val s = fmtT(ParameterAdvisor.requiredShutter(c.a, c.focalMm, c.plan))
                tips += tr("S 档：快门可以适当放慢（不慢于 $s）", "S モード：シャッターを少し遅くできます（$s より遅くしない）", "S mode: you can slow the shutter a bit (not slower than $s)")
            }
            if (tips.isEmpty()) tips += tr(
                "参数都到头了：用三脚架、闪光灯或找更亮的地方",
                "設定は限界です：三脚、フラッシュ、または明るい場所へ",
                "all settings are at their limits: use a tripod, flash, or find more light",
            )
            tips += tr("也可以直接把曝光补偿往 + 调", "露出補正をプラスにしても OK", "or simply raise the exposure compensation")
            tips.joinToString(stepSep)
        } else {
            val tips = mutableListOf<String>()
            if (ev > 0.2) tips += tr("先检查曝光补偿：现在是 $evs", "まず露出補正を確認：現在 $evs", "check exposure compensation first: currently $evs")
            if (iso != null && iso > 100) tips += tr("ISO 降到 100", "ISO を 100 に下げる", "lower ISO to 100")
            if (f != null && f < 8 && !mode.startsWith("Shutter", true)) tips += tr("光圈收小一点", "絞りを少し絞る", "stop down a little")
            tips += tr(
                "或者把曝光补偿往 − 调；还是太亮（快门已到 1/4000）就需要 ND 减光镜",
                "または露出補正をマイナスに。それでも明るすぎる（シャッターが 1/4000 に達している）なら ND フィルターが必要",
                "or lower the exposure compensation; if still too bright (shutter already at 1/4000) you need an ND filter",
            )
            tips.joinToString(stepSep)
        }
    }

    /** A photo that is already taken: fix it in editing, or shoot it differently next time. */
    private fun photoAdvice(tooDark: Boolean): String = if (tooDark) tr(
        "后期提亮曝光和暗部（RAW 能救回更多）；下次拍类似场景时把曝光补偿往 + 调",
        "編集で露出とシャドウを持ち上げましょう（RAW ならより多く救えます）。次に似たシーンを撮るときは露出補正を + 側に",
        "brighten the exposure and shadows when editing (RAW recovers more); next time in a scene like this, raise the exposure compensation",
    ) else tr(
        "后期压暗曝光、拉回高光；下次拍类似场景时把曝光补偿往 − 调",
        "編集で露出を下げ、ハイライトを戻しましょう。次に似たシーンを撮るときは露出補正を − 側に",
        "lower the exposure and recover highlights when editing; next time in a scene like this, lower the exposure compensation",
    )

    /** Phone camera: exposure is automatic; the user can only shift it with exposure compensation or by metering on a spot. */
    private fun phoneAdvice(tooDark: Boolean): String = if (tooDark) tr(
        "在「设置」里把曝光补偿往 + 拖，或点一下画面里的主体让手机按它测光；还是太暗就靠着东西拍或找更亮的地方",
        "「設定」で露出補正を + 側へ、または画面の被写体をタップしてそこで測光。それでも暗ければ何かに寄りかかって撮るか、明るい場所へ",
        "drag exposure compensation towards + in Settings, or tap the subject so the phone meters on it; if still too dark, brace the phone or find more light",
    ) else tr(
        "在「设置」里把曝光补偿往 − 拖，或点一下画面里的亮处让手机按它测光",
        "「設定」で露出補正を − 側へ、または画面の明るい部分をタップしてそこで測光",
        "drag exposure compensation towards − in Settings, or tap a bright area so the phone meters on it",
    )

    val all = listOf(exposure)
}
