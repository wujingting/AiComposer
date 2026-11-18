package com.example.aicomposer.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.example.aicomposer.ai.AestheticModel
import com.example.aicomposer.ai.AestheticResult
import com.example.aicomposer.analysis.AnalysisResult
import com.example.aicomposer.analysis.AutoCropper
import com.example.aicomposer.analysis.CompositionAdvice
import com.example.aicomposer.analysis.CompositionAdvisor
import com.example.aicomposer.analysis.CropPlan
import com.example.aicomposer.analysis.FrameAnalyzer
import com.example.aicomposer.analysis.NRect
import com.example.aicomposer.analysis.ParameterAdvice
import com.example.aicomposer.analysis.ParameterAdvisor
import com.example.aicomposer.analysis.SceneType
import com.example.aicomposer.camera.CameraState
import com.example.aicomposer.coach.Category
import com.example.aicomposer.coach.CheckItem
import com.example.aicomposer.coach.CoachEngine
import com.example.aicomposer.coach.CoachInput
import com.example.aicomposer.coach.CoachReport
import com.example.aicomposer.coach.Optics
import com.example.aicomposer.coach.Part
import com.example.aicomposer.coach.Severity
import com.example.aicomposer.coach.TemporalFilter
import com.example.aicomposer.i18n.tr
import kotlinx.coroutines.CancellationException

enum class CropMode {
    OFF, AUTO, MANUAL;

    /** Display name in the current UI language. */
    val label: String
        get() = when (this) {
            OFF -> tr("关", "オフ", "Off")
            AUTO -> tr("自动", "自動", "Auto")
            MANUAL -> tr("手动", "手動", "Manual")
        }
}

/** The crop plan actually in use for a crop mode. */
fun effectivePlan(mode: CropMode, manualPlan: CropPlan, autoKeep: Float): CropPlan = when (mode) {
    CropMode.OFF -> CropPlan()
    CropMode.AUTO -> CropPlan(autoKeep, manualPlan.aspect)
    CropMode.MANUAL -> manualPlan
}

/**
 * Combined score for auto shutter: the composition part, averaged with the aesthetic score when the model is
 * available. Light and technical problems are handled separately (see [AutoShutterGate]).
 */
fun combinedScore(composition: CompositionAdvice?, aesthetic: AestheticResult?): Int? {
    val c = composition?.score ?: return null
    val a = aesthetic?.norm ?: return c
    return (c + a) / 2
}

/** Status line for the aesthetic model, shown in the UI. */
fun aestheticStatus(model: AestheticModel?): String {
    val err = AestheticModel.lastError
    return when {
        model != null -> tr("美学模型已加载（CLIP ViT-B/32 + LAION）", "美的評価モデルを読み込みました（CLIP ViT-B/32 + LAION）", "Aesthetic model loaded (CLIP ViT-B/32 + LAION)")
        err != null -> tr("美学模型加载失败：$err", "美的評価モデルの読み込みに失敗：$err", "Failed to load the aesthetic model: $err")
        else -> tr(
            "未安装美学模型：按 README 运行 tools/export_clip_model.py，再重新编译安装 App",
            "美的評価モデルが未インストール：README の手順で tools/export_clip_model.py を実行し、App を再ビルドしてください",
            "Aesthetic model not installed: run tools/export_clip_model.py as described in the README, then rebuild the app",
        )
    }
}

/**
 * The per-frame coaching pipeline shared by every app:
 *   ML Kit + classic features -> (CLIP scene override) -> auto crop -> composition -> exposure advice
 *   -> all rules -> temporal smoothing -> grouping and scoring.
 * Apps only provide frames and camera settings, and show the [Output].
 */
class CoachPipeline(context: Context) : AutoCloseable {
    val analyzer = FrameAnalyzer()
    private val temporal = TemporalFilter()
    private val guide = DirectionGuide()
    private val perfMonitor = PerfMonitor(context)

    data class Input(
        val frame: Bitmap,
        val camera: CameraState,
        /** Focal length in APS-C millimeters (the a6000's frame of reference; phone apps convert). */
        val focalMm: Int,
        val cropMode: CropMode,
        val cropPlan: CropPlan,
        val autoKeep: Float,
        val aesthetic: AestheticResult?,
        val ignoredRules: Set<String>,
        /** Long end of a zoom lens (mm). When a crop is needed below it, suggest zooming first. null = no such hint. */
        val zoomLongEndMm: Int? = null,
        /** Whether to compute exposure parameter advice (needs a camera with adjustable settings). */
        val adviseParameters: Boolean = true,
        /** An independent photo rather than a liveview frame: run every model, no motion / history. */
        val still: Boolean = false,
        /** CLIP point of interest for a still photo (see [AestheticModel.findInterest]); liveview uses the background loop. */
        val interest: NRect? = null,
    )

    data class Output(
        val analysis: AnalysisResult,
        val composition: CompositionAdvice,
        val params: ParameterAdvice?,
        val report: CoachReport,
        /** Raw (not temporally smoothed) rule results of this frame; used by [AutoShutterGate]. */
        val rawItems: List<CheckItem>,
        val zoomHint: String?,
        val autoKeep: Float,
        val autoReason: String?,
        val perf: PerfMonitor.Stats,
        val segmentationOk: Boolean,
        /** Direction arrow for the viewfinder and the "framing in place" counter (liveview only). */
        val guide: GuideState = GuideState(),
    )

    /** Forget the temporal history (e.g. after reconnecting or switching cameras). */
    fun reset() {
        temporal.reset()
        guide.reset()
    }

    /** Analyze one frame. Returns null if the analysis failed. */
    suspend fun process(input: Input): Output? {
        val t0 = System.currentTimeMillis()
        val raw = try {
            analyzer.analyze(input.frame, input.still, input.interest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        // Use CLIP scene recognition when it is more reliable (action and low light still come from the motion / brightness rules)
        val ai = input.aesthetic
        val r = if (ai?.scene != null && ai.sceneConf > 0.45f &&
            raw.scene != SceneType.ACTION && raw.scene != SceneType.NIGHT
        ) raw.copy(scene = ai.scene) else raw

        // Auto crop: decide how much to keep from the subject size
        val decision = if (input.cropMode == CropMode.AUTO) AutoCropper.decide(r, input.autoKeep) else null
        val autoKeep = decision?.keep ?: input.autoKeep
        val plan = effectivePlan(input.cropMode, input.cropPlan, autoKeep)

        val comp0 = CompositionAdvisor.advise(r, plan)
        // Needing a crop means the lens is too short: use the long end first
        val zoomHint = input.zoomLongEndMm?.let { longEnd ->
            if (plan.keep < 0.95f && input.focalMm < longEnd) tr(
                "先拉到 ${longEnd}mm，设置里也选 ${longEnd}mm",
                "${longEnd}mm へズームし、設定も ${longEnd}mm に",
                "Zoom to ${longEnd}mm, then set ${longEnd}mm",
            ) else null
        }
        val params = if (input.adviseParameters) {
            // Group shots: aperture needed for depth of field
            val dofF = Optics.groupRequiredF(r.faces, input.focalMm)
            ParameterAdvisor.advise(r, input.camera, input.focalMm, plan, dofF)
        } else null

        // Coaching engine: run all rules -> temporal smoothing -> grouping and scoring
        val rawItems = CoachEngine.evaluate(
            CoachInput(r, comp0, plan, input.camera, params, input.focalMm, input.aesthetic),
            input.ignoredRules,
        )
        val report = CoachEngine.report(temporal.filter(rawItems))
        val comp = comp0.copy(
            score = report.part(Part.COMPOSITION),
            hints = report.problems.take(3).map { it.message },
        )
        return Output(
            analysis = r, composition = comp, params = params, report = report, rawItems = rawItems,
            zoomHint = zoomHint, autoKeep = autoKeep, autoReason = decision?.reason,
            perf = perfMonitor.record(System.currentTimeMillis() - t0),
            segmentationOk = analyzer.segmentationAvailable,
            guide = if (input.still) GuideState() else guide.update(report, comp),
        )
    }

    /**
     * One step of the AI aesthetics loop: score the frame and, every other call, look for the
     * "point of interest" and hand it to the analyzer for subject selection.
     */
    fun aestheticStep(model: AestheticModel, frame: Bitmap, tick: Int): AestheticResult {
        val r = model.analyze(frame)
        if (tick % 2 == 0 && model.supportsInterest) {
            val interest = model.findInterest(frame)
            analyzer.interest = interest?.let { it.rect to System.currentTimeMillis() }
        }
        return r
    }

    override fun close() {
        analyzer.close()
    }
}

/**
 * Auto-shutter decision. Fires when the score has stayed at or above the threshold for [streakNeeded] analyses
 * in a row and the last shot was at least [minIntervalMs] ago. A photo-ruining flaw in this very frame
 * (closed eyes, blur, not enough depth of field — portrait or focus ERRORs) blocks it; light problems do not
 * (fixable in post).
 */
class AutoShutterGate(private val streakNeeded: Int = 3, private val minIntervalMs: Long = 4000) {
    private var streak = 0
    @Volatile var lastShotAt = 0L

    fun reset() { streak = 0 }

    /** Returns true when a photo should be taken now. */
    fun update(score: Int, threshold: Int, rawItems: List<CheckItem>, now: Long = System.currentTimeMillis()): Boolean {
        val blocked = rawItems.any {
            it.severity == Severity.ERROR && (it.category == Category.PORTRAIT || it.category == Category.FOCUS)
        }
        streak = if (score >= threshold && !blocked) streak + 1 else 0
        if (streak >= streakNeeded && now - lastShotAt > minIntervalMs) {
            streak = 0
            return true
        }
        return false
    }
}
