package com.example.aicomposer.analysis

import com.example.aicomposer.i18n.tr
import com.example.aicomposer.camera.CameraState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Exposure parameter advice:
 *  1. Aperture from the scene type
 *  2. "Slowest acceptable shutter" from focal length (handheld safe speed) and motion
 *  3. Exposure compensation from the brightness distribution (backlight, clipping, snow)
 *  4. Scene brightness estimated from the current settings + liveview brightness, then a
 *     suitable ISO (skipped when ISO is AUTO)
 */
object ParameterAdvisor {
    private val STD_F = listOf("1.4", "1.8", "2.0", "2.8", "3.5", "4.0", "4.5", "5.0", "5.6",
        "6.3", "7.1", "8.0", "9.0", "10", "11", "13", "16", "22")
    private val STD_SHUTTER = listOf("1/4000", "1/3200", "1/2500", "1/2000", "1/1600", "1/1250",
        "1/1000", "1/800", "1/640", "1/500", "1/400", "1/320", "1/250", "1/200", "1/160", "1/125",
        "1/100", "1/80", "1/60", "1/50", "1/40", "1/30", "1/25", "1/20", "1/15", "1/13", "1/10",
        "1/8", "1/6", "1/5", "1/4", "0.3\"", "0.4\"", "0.5\"", "0.6\"", "0.8\"", "1\"", "1.3\"",
        "1.6\"", "2\"", "2.5\"", "3.2\"", "4\"", "5\"", "6\"", "8\"", "10\"", "13\"", "15\"",
        "20\"", "25\"", "30\"")
    private val STD_ISO = listOf("100", "125", "160", "200", "250", "320", "400", "500", "640",
        "800", "1000", "1250", "1600", "2000", "2500", "3200", "4000", "5000", "6400", "8000",
        "10000", "12800", "16000", "25600")
    private const val FASTEST = 1.0 / 4000

    fun parseShutter(s: String?): Double? {
        val t = s?.trim()?.removeSuffix("\"") ?: return null
        return if (t.contains("/")) {
            val parts = t.split("/")
            val a = parts.getOrNull(0)?.toDoubleOrNull()
            val b = parts.getOrNull(1)?.toDoubleOrNull()
            if (a != null && b != null && b != 0.0) a / b else null
        } else t.toDoubleOrNull()
    }

    fun formatEv(v: Double): String = String.format(Locale.ROOT, "%+.1f EV", v)

    /** Effective focal length after the planned crop. */
    fun effectiveFocal(a: AnalysisResult, focalMm: Int, plan: CropPlan): Double =
        focalMm * (if (plan.active) plan.magnification(a.frameAspect).toDouble() else 1.0)

    /** ISO ceiling after the planned crop. */
    fun maxIsoFor(a: AnalysisResult, plan: CropPlan): Int {
        val mag = if (plan.active) plan.magnification(a.frameAspect).toDouble() else 1.0
        return snapIsoLimit(com.example.aicomposer.coach.Cfg.d("iso.max_base") / (mag * mag))
    }

    /** Slowest acceptable shutter (seconds): the stricter of handheld shake and subject motion. */
    fun requiredShutter(a: AnalysisResult, focalMm: Int, plan: CropPlan): Double {
        val handheld = 1.0 / (1.5 * effectiveFocal(a, focalMm, plan))
        return min(handheld, motionShutter(a))
    }

    private fun motionShutter(a: AnalysisResult): Double {
        val motionLevel = a.subjectMotion ?: a.motion
        return when {
            a.scene == SceneType.ACTION && motionLevel > 0.15f -> 1.0 / 1000
            a.scene == SceneType.ACTION -> 1.0 / 500
            motionLevel > 0.06f -> 1.0 / 250
            a.scene == SceneType.PORTRAIT -> 1.0 / 125
            else -> 1.0
        }
    }

    fun advise(
        a: AnalysisResult,
        cam: CameraState,
        focalMm: Int,
        plan: CropPlan = CropPlan(),
        minApertureForDof: Double? = null, // smallest aperture needed for depth of field (group shots etc.)
    ): ParameterAdvice {
        val reasons = mutableListOf<String>()
        val warns = mutableListOf<String>()

        // Planned crop: the larger the magnification, the more visible shake and noise become
        val effFocal = effectiveFocal(a, focalMm, plan)
        val maxIso = maxIsoFor(a, plan)
        if (plan.active) {
            val mp = plan.megapixels(a.frameAspect)
            val eff = effFocal.roundToInt()
            val ff = (effFocal * 1.5).roundToInt()
            reasons += tr(
                "预留裁剪：等效焦距 $focalMm→${eff}mm（全画幅等效 ${ff}mm），ISO 上限降到 $maxIso",
                "トリミング前提：実効焦点距離 $focalMm→${eff}mm（35mm 換算 ${ff}mm）、ISO 上限を $maxIso に",
                "Planned crop: effective focal length $focalMm→${eff}mm (${ff}mm full-frame equivalent), ISO limit lowered to $maxIso",
            )
            if (mp < 8f) {
                val mps = String.format(Locale.ROOT, "%.1f", mp)
                warns += tr(
                    "裁剪后只剩约 $mps 百万像素，大尺寸打印会不够",
                    "トリミング後は約 $mps メガピクセル。大きなプリントには足りません",
                    "Only about $mps MP left after cropping, not enough for large prints",
                )
            }
        }

        val fList = cam.fNumberCandidates.ifEmpty { STD_F }
            .mapNotNull { s -> s.toDoubleOrNull()?.let { s to it } }
        val shutterList = cam.shutterCandidates.ifEmpty { STD_SHUTTER }
            .mapNotNull { s -> parseShutter(s)?.let { s to it } }
        val isoList = cam.isoCandidates.ifEmpty { STD_ISO }
            .mapNotNull { s -> s.toDoubleOrNull()?.let { s to it } }
        val minF = fList.minOfOrNull { it.second } ?: 3.5
        val maxF = fList.maxOfOrNull { it.second } ?: 22.0

        val modeA = tr("A 档", "A モード", "A mode")
        // 1. Aperture
        val (fTarget, mode) = when (a.scene) {
            SceneType.PORTRAIT -> {
                reasons += tr("人像：开大光圈虚化背景、突出人物", "ポートレート：絞りを開けて背景をぼかし、人物を際立たせる", "Portrait: open the aperture to blur the background and make the person stand out")
                minF to modeA
            }
            SceneType.LANDSCAPE -> {
                reasons += tr("风景：f/8 左右画质最锐、前后都清楚", "風景：f/8 前後が最もシャープで、手前から奥までピントが合う", "Landscape: around f/8 is sharpest and keeps near and far in focus")
                8.0 to modeA
            }
            SceneType.NIGHT -> {
                reasons += tr("暗光：开大光圈尽量多进光", "暗所：絞りを開けてできるだけ光を取り込む", "Low light: open the aperture to gather as much light as possible")
                minF to tr("A 档（有三脚架用 M 档）", "A モード（三脚があれば M モード）", "A mode (M mode with a tripod)")
            }
            SceneType.ACTION -> {
                reasons += tr("动体：先保证快门速度，光圈 f/4 左右兼顾对焦容错", "動体：まずシャッタースピードを確保。絞りは f/4 前後でピントの余裕も", "Action: secure the shutter speed first; around f/4 leaves some focus tolerance")
                max(minF, 4.0) to tr("S 档", "S モード", "S mode")
            }
            SceneType.CLOSEUP -> {
                reasons += tr("特写：f/4~5.6 让主体完整清晰、背景适度虚化", "クローズアップ：f/4〜5.6 で被写体全体をシャープに、背景は適度にぼかす", "Close-up: f/4–5.6 keeps the whole subject sharp with a moderately blurred background")
                max(minF, 4.0) to modeA
            }
            SceneType.GENERAL -> {
                reasons += tr("一般场景：f/5.6 最稳妥", "一般的なシーン：f/5.6 が無難", "General scene: f/5.6 is the safe choice")
                max(minF, 5.6) to tr("A 档 / P 档", "A モード / P モード", "A mode / P mode")
            }
        }
        var f = snapUp(fTarget, fList) ?: fTarget
        if (minApertureForDof != null && minApertureForDof > f) {
            f = snapUp(minApertureForDof, fList) ?: minApertureForDof
            val fs = trimF(f)
            reasons += tr(
                "景深：需要收到 f/$fs，才能让前后的人都清楚",
                "被写界深度：前後の人全員にピントを合わせるには f/$fs まで絞る必要があります",
                "Depth of field: stop down to f/$fs to keep people in front and behind sharp",
            )
        }

        // 2. Slowest acceptable shutter
        val handheld = 1.0 / (1.5 * effFocal)
        val motionT = motionShutter(a)
        val tSlowest = min(handheld, motionT)
        reasons += if (motionT < handheld) {
            val s = snapShutterLabel(motionT, shutterList)
            tr("画面里有运动：快门不慢于 $s", "画面内に動きがあります：シャッターは $s より速く", "There is motion in the frame: shutter no slower than $s")
        } else {
            val s = snapShutterLabel(handheld, shutterList)
            val mm = effFocal.roundToInt()
            if (plan.active) tr(
                "${mm}mm（含裁剪）手持：快门不慢于 $s 防抖",
                "${mm}mm（トリミング込み）手持ち：手ブレ防止にシャッターは $s より速く",
                "${mm}mm (incl. crop) handheld: shutter no slower than $s to avoid shake",
            ) else tr(
                "${mm}mm 手持：快门不慢于 $s 防抖",
                "${mm}mm 手持ち：手ブレ防止にシャッターは $s より速く",
                "${mm}mm handheld: shutter no slower than $s to avoid shake",
            )
        }

        // 3. Exposure compensation
        var ev = 0.0
        val l = a.luma
        val subj = l.subjectMean
        if (subj != null && subj < l.mean * 0.6f && l.highlightClip > 0.03f) {
            ev = 1.0
            reasons += tr("主体比背景暗很多（逆光）：+1 EV 把主体提亮", "被写体が背景よりかなり暗い（逆光）：+1 EV で被写体を明るく", "Subject much darker than the background (backlight): +1 EV to brighten it")
        } else if (l.highlightClip > 0.05f) {
            ev = -0.7
            val pct = (l.highlightClip * 100).roundToInt()
            reasons += tr("高光有 $pct% 过曝：-0.7 EV 保住亮部细节", "ハイライトの $pct% が白飛び：-0.7 EV で明部のディテールを残す", "$pct% of the highlights are clipped: -0.7 EV to keep highlight detail")
        } else if (l.mean > 0.65f && a.labels.any { it in FrameAnalyzer.BRIGHT_WORDS }) {
            ev = 0.7
            reasons += tr("雪地/沙滩：相机容易拍灰，+0.7 EV", "雪・砂浜：カメラが暗めに写しがちなので +0.7 EV", "Snow / beach: the camera tends to render it grey, +0.7 EV")
        } else if (a.scene == SceneType.NIGHT) {
            ev = -0.7
            reasons += tr("夜景：-0.7 EV 保留夜晚氛围、压住灯光过曝", "夜景：-0.7 EV で夜の雰囲気を残し、明かりの白飛びを抑える", "Night: -0.7 EV keeps the night mood and stops lights from clipping")
        }
        val evIndex = (ev / cam.evStepSize).roundToInt().let { idx ->
            if (cam.evMax > cam.evMin) idx.coerceIn(cam.evMin, cam.evMax) else idx
        }
        val evText = formatEv(evIndex * cam.evStepSize)

        // 4. Estimate ISO (requires concrete current aperture, shutter and ISO values)
        val curF = cam.fNumber?.toDoubleOrNull()
        val curT = parseShutter(cam.shutter)
        val curIso = cam.iso?.toDoubleOrNull()
        var shutterOut: String? = snapShutter(tSlowest, shutterList)
        var isoOut: String? = "AUTO"

        if (curF != null && curT != null && curIso != null) {
            // How many stops the liveview is off from middle grey (18%) -> the scene's "correct" exposure value
            val lin = max(l.mean.toDouble(), 0.01).pow(2.2)
            val delta = (ln(lin / 0.18) / ln(2.0)).coerceIn(-3.0, 3.0)
            val currentEv = log2(curF * curF / curT) - log2(curIso / 100.0)
            val targetEv = currentEv + delta - ev

            var t = tSlowest
            var iso = isoFor(f, t, targetEv)
            if (iso < 100) {
                // Plenty of light: ISO 100 with a faster shutter
                iso = 100.0
                t = f * f / 2.0.pow(targetEv)
                if (t < FASTEST) {
                    t = FASTEST
                    val needF = sqrt(t * 2.0.pow(targetEv))
                    f = snapUp(needF, fList) ?: needF
                    if (needF > maxF) {
                        warns += tr("光线太强：建议加 ND 减光镜", "光が強すぎます：ND フィルターの使用をおすすめします", "Too much light: use an ND filter")
                    } else {
                        val fs = trimF(f)
                        reasons += tr("光线很强：收小到 f/$fs 防止过曝", "光が強い：f/$fs まで絞って露出オーバーを防ぐ", "Very bright: stop down to f/$fs to avoid overexposure")
                    }
                }
            }
            if (iso > maxIso && f > minF + 0.05 && minApertureForDof == null) {
                f = minF
                iso = max(100.0, isoFor(f, t, targetEv))
                val fs = trimF(f)
                reasons += tr("光线不够：光圈开到最大 f/$fs", "光が足りません：絞りを開放 f/$fs に", "Not enough light: open up to the widest aperture f/$fs")
            }
            if (iso > maxIso) {
                val need = iso.roundToInt()
                warns += tr(
                    "光线不足：需要 ISO $need，噪点会多；可用三脚架放慢快门",
                    "光量不足：ISO $need が必要でノイズが増えます。三脚を使えばシャッターを遅くできます",
                    "Not enough light: needs ISO $need, which is noisy; a tripod lets you use a slower shutter",
                )
            }
            iso = iso.coerceIn(100.0, 25600.0)
            shutterOut = snapShutter(t, shutterList)
            isoOut = snapNearest(iso, isoList)
        } else {
            reasons += tr(
                "相机 ISO 是 AUTO：无法精确推算 ISO，建议保持 AUTO（上限 $maxIso）。想要更准的建议，可以把 ISO 设成具体数值",
                "カメラの ISO が AUTO のため ISO を正確に推定できません。AUTO のまま（上限 $maxIso）をおすすめします。より正確な提案が必要なら ISO を数値で設定してください",
                "Camera ISO is AUTO, so ISO cannot be estimated precisely; keep AUTO (limit $maxIso). Set a fixed ISO value for more accurate advice",
            )
        }

        val fOut = fList.firstOrNull { abs(it.second - f) < 0.01 }?.first ?: trimF(f)
        return ParameterAdvice(a.scene, mode, fOut, shutterOut, isoOut, evIndex, evText, reasons, warns)
    }

    /** Round the ISO limit down to a common step, minimum 800. */
    private fun snapIsoLimit(v: Double): Int =
        listOf(800, 1000, 1250, 1600, 2000, 2500, 3200).lastOrNull { it <= v } ?: 800

    private fun isoFor(f: Double, t: Double, targetEv: Double) =
        100.0 * 2.0.pow(log2(f * f / t) - targetEv)

    /** Smallest candidate aperture >= target; the largest one if all are smaller. */
    private fun snapUp(target: Double, list: List<Pair<String, Double>>): Double? =
        list.map { it.second }.filter { it >= target - 0.05 }.minOrNull() ?: list.maxOfOrNull { it.second }

    private fun <T> nearestLog(target: Double, list: List<Pair<T, Double>>): Pair<T, Double>? =
        list.filter { it.second > 0 }.minByOrNull { abs(log2(it.second / target)) }

    private fun snapNearest(v: Double, list: List<Pair<String, Double>>): String? =
        nearestLog(v, list)?.first

    private fun snapShutter(t: Double, list: List<Pair<String, Double>>): String? =
        nearestLog(t, list)?.first

    private fun snapShutterLabel(t: Double, list: List<Pair<String, Double>>): String =
        snapShutter(t, list) ?: "1/${(1 / t).roundToInt()}"

    // Locale.ROOT: this value can be sent to the camera, so it must always use "." as decimal separator
    private fun trimF(f: Double): String =
        if (f >= 10) f.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", f)
}
