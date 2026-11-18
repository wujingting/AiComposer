package com.example.aicomposer.analysis

import com.example.aicomposer.i18n.tr

/** Rectangle in normalized coordinates (0..1). */
data class NRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    val cx get() = (l + r) / 2f
    val cy get() = (t + b) / 2f
    val area get() = w * h
}

data class P(val x: Float, val y: Float)

/**
 * Planned crop in post-processing.
 * keep:   fraction of the frame width kept after cropping (1 = no crop, 0.6 = keep 60%)
 * aspect: aspect ratio of the final image (width / height), 3:2 = 1.5
 */
data class CropPlan(val keep: Float = 1f, val aspect: Float = 1.5f) {
    val active: Boolean get() = keep < 0.99f || kotlin.math.abs(aspect - 1.5f) > 0.05f

    /** Normalized width and height of the crop window inside the frame. */
    fun size(frameAspect: Float): Pair<Float, Float> {
        var cw = keep
        var ch = keep * frameAspect / aspect
        if (ch > 1f) { cw /= ch; ch = 1f }
        return cw to ch
    }

    /** Magnification relative to the full frame (shake and noise are magnified by this much). */
    fun magnification(frameAspect: Float): Float {
        val (cw, ch) = size(frameAspect)
        return 1f / kotlin.math.sqrt(cw * ch)
    }

    /** Megapixels left after cropping the a6000's 24 MP sensor. */
    fun megapixels(frameAspect: Float, sensorMp: Float = 24f): Float {
        val (cw, ch) = size(frameAspect)
        return sensorMp * cw * ch
    }
}

/** What kind of thing a subject is. Used for logic; the display name comes from [Subject.label]. */
enum class SubjectKind {
    PERSON, CLOTHING, FOOD, HOME_GOOD, PLACE, PLANT,
    /** Generic subject (saliency, segmentation, unclassified object). */
    GENERIC,
    /** The region CLIP considers most interesting. */
    INTEREST,
    /** A sign or other text block; [Subject.text] holds the recognized text. */
    TEXT,
}

data class Subject(
    val rect: NRect,
    val kind: SubjectKind,
    val isFace: Boolean,
    val text: String? = null,
    /**
     * Inferred from color contrast / segmentation / CLIP rather than recognized by a detector (face, body,
     * object, text). Advice that depends on where the subject is gets softened for such subjects.
     */
    val guessed: Boolean = false,
    /** Display name from a detector that knows what it is (e.g. "电车"); overrides the kind's generic name. */
    val name: String? = null,
    /** Detector confidence 0..1, when known. */
    val score: Float? = null,
) {
    val isText: Boolean get() = kind == SubjectKind.TEXT

    /** Display name in the current UI language. */
    val label: String
        get() = if (name != null && kind != SubjectKind.TEXT) name else when (kind) {
            SubjectKind.PERSON -> tr("人物", "人物", "the person")
            SubjectKind.CLOTHING -> tr("服饰", "服飾", "the clothing")
            SubjectKind.FOOD -> tr("食物", "料理", "the food")
            SubjectKind.HOME_GOOD -> tr("物品", "物", "the object")
            SubjectKind.PLACE -> tr("场所", "場所", "the place")
            SubjectKind.PLANT -> tr("植物", "植物", "the plant")
            SubjectKind.GENERIC -> tr("主体", "被写体", "the subject")
            SubjectKind.INTEREST -> tr("看点", "見どころ", "the point of interest")
            SubjectKind.TEXT -> tr("文字「${text.orEmpty()}」", "文字「${text.orEmpty()}」", "the text \"${text.orEmpty()}\"")
        }
}

/** A recognized text block. angle = tilt of the text line in degrees. */
data class TextInfo(val rect: NRect, val text: String, val angle: Float)

fun NRect.contains(x: Float, y: Float) = x in l..r && y in t..b

fun NRect.shrink(k: Float): NRect {
    val dw = w * (1 - k) / 2
    val dh = h * (1 - k) / 2
    return NRect(l + dw, t + dh, r - dw, b - dh)
}

data class LumaStats(
    val mean: Float,            // 0..1
    val highlightClip: Float,   // fraction of clipped highlights
    val shadowClip: Float,      // fraction of crushed shadows
    val subjectMean: Float?,    // mean brightness of the subject region
)

enum class SceneType {
    PORTRAIT, LANDSCAPE, NIGHT, ACTION, CLOSEUP, GENERAL;

    /** Display name in the current UI language. */
    val label: String
        get() = when (this) {
            PORTRAIT -> tr("人像", "ポートレート", "Portrait")
            LANDSCAPE -> tr("风景", "風景", "Landscape")
            NIGHT -> tr("夜景/暗光", "夜景・暗所", "Night / low light")
            ACTION -> tr("运动/动体", "動体", "Action")
            CLOSEUP -> tr("特写/微距", "クローズアップ・マクロ", "Close-up / macro")
            GENERAL -> tr("日常随拍", "スナップ", "Everyday snapshot")
        }

    /** One-word name for tight spots (the score column of the hint strip). */
    val shortLabel: String
        get() = when (this) {
            PORTRAIT -> tr("人像", "人物", "Portrait")
            LANDSCAPE -> tr("风景", "風景", "Landscape")
            NIGHT -> tr("夜景", "夜景", "Night")
            ACTION -> tr("动体", "動体", "Action")
            CLOSEUP -> tr("特写", "接写", "Close-up")
            GENERAL -> tr("随拍", "スナップ", "Snapshot")
        }
}

data class AnalysisResult(
    val subjects: List<Subject>,
    val mainSubject: Subject?,
    val labels: List<String>,
    val scene: SceneType,
    val tiltDeg: Float?,        // positive = horizon lower on the right
    val motion: Float,          // whole-frame change 0..1
    val subjectMotion: Float?,  // change inside the subject region 0..1
    val luma: LumaStats,
    val frameAspect: Float = 1.5f, // liveview width / height
    val faces: List<FaceInfo> = emptyList(),
    val pose: PoseInfo? = null,
    val mask: Mask? = null,
    val features: Features? = null,
    val subjectVelocity: P? = null, // how far the subject moved since the last analysis (normalized)
    val texts: List<TextInfo> = emptyList(),
    val interest: NRect? = null,    // region CLIP finds most interesting
)

data class CompositionAdvice(
    val score: Int,
    val subjectCenter: P?,
    val target: P?,
    val hints: List<String>,
    val cropRect: NRect? = null, // expected final frame when a crop is planned
)

data class ParameterAdvice(
    val scene: SceneType,
    val mode: String,
    val fNumber: String?,       // value that can be sent to the camera, e.g. "2.8"
    val shutter: String?,       // e.g. "1/250"
    val iso: String?,           // e.g. "400" or "AUTO"
    val evIndex: Int?,
    val evText: String,
    val reasons: List<String>,
    val warnings: List<String>,
)
