package com.example.aicomposer.coach

import com.example.aicomposer.ai.AestheticResult
import com.example.aicomposer.analysis.AnalysisResult
import com.example.aicomposer.analysis.CompositionAdvice
import com.example.aicomposer.analysis.CropPlan
import com.example.aicomposer.analysis.NRect
import com.example.aicomposer.analysis.P
import com.example.aicomposer.analysis.ParameterAdvice
import com.example.aicomposer.i18n.tr
import com.example.aicomposer.camera.CameraState

/** Severity. weight = base penalty. */
enum class Severity(val weight: Float) {
    OK(0f),
    TIP(0f),
    INFO(4f),
    WARN(12f),
    ERROR(25f),
    IGNORED(0f);

    /** Display name in the current UI language. */
    val label: String
        get() = when (this) {
            OK -> "✓"
            TIP -> tr("建议", "ヒント", "Tip")
            INFO -> tr("提示", "参考", "Note")
            WARN -> tr("注意", "注意", "Warning")
            ERROR -> tr("问题", "問題", "Problem")
            IGNORED -> tr("已忽略", "無視中", "Ignored")
        }
}

enum class Category {
    COMPOSITION, LIGHT, FOCUS, PORTRAIT, LANDSCAPE, AESTHETIC;

    /** Display name in the current UI language. */
    val label: String
        get() = when (this) {
            COMPOSITION -> tr("构图", "構図", "Composition")
            LIGHT -> tr("光线/曝光", "光・露出", "Light / exposure")
            FOCUS -> tr("对焦/参数", "ピント・設定", "Focus / settings")
            PORTRAIT -> tr("人像", "ポートレート", "Portrait")
            LANDSCAPE -> tr("风景/建筑", "風景・建築", "Landscape / architecture")
            AESTHETIC -> tr("美学", "美しさ", "Aesthetics")
        }
}

/** Result of one check. */
data class CheckItem(
    val id: String,
    val name: String,
    val category: Category,
    val severity: Severity,
    val message: String,
    val penalty: Float = Cfg.weight(severity),
    /** Direction (dx, dy) this advice wants the subject to move in the frame; used to detect conflicts between rules. */
    val move: P? = null,
    /** When merged with other problems that share a root cause, the name of the item it was merged into. */
    val mergedInto: String? = null,
    /**
     * One-glance version of [message] for the hint strip ("Subject cut off at left"): a few words, no advice
     * steps. The full advice stays in [message] and is shown in the checklist.
     */
    val short: String? = null,
) {
    /** What the hint strip shows: the short phrase, or the full message for items without one. */
    val brief: String get() = short ?: message
}

/** Score parts. */
enum class Part {
    COMPOSITION, LIGHT, TECH;

    /** Display name in the current UI language. */
    val label: String
        get() = when (this) {
            COMPOSITION -> tr("构图", "構図", "Composition")
            LIGHT -> tr("光线", "光", "Light")
            TECH -> tr("技术", "技術", "Technical")
        }
}

fun Category.part(): Part? = when (this) {
    Category.COMPOSITION, Category.PORTRAIT, Category.LANDSCAPE -> Part.COMPOSITION
    Category.LIGHT -> Part.LIGHT
    Category.FOCUS -> Part.TECH
    Category.AESTHETIC -> null
}

data class CoachReport(
    val score: Int,
    val items: List<CheckItem>,
    val parts: Map<Part, Int> = emptyMap(),
) {
    /** Items with a problem, sorted by penalty, largest first. */
    val problems: List<CheckItem>
        get() = items.filter { it.severity == Severity.INFO || it.severity == Severity.WARN || it.severity == Severity.ERROR }
            .sortedWith(compareByDescending<CheckItem> { it.mergedInto == null }.thenByDescending { it.penalty }.thenByDescending { it.severity.ordinal })
    val tips: List<CheckItem> get() = items.filter { it.severity == Severity.TIP }
    val passed: List<CheckItem> get() = items.filter { it.severity == Severity.OK }
    val ignored: List<CheckItem> get() = items.filter { it.severity == Severity.IGNORED }

    fun part(p: Part): Int = parts[p] ?: 100
}

/** Everything a rule can use. */
data class CoachInput(
    val a: AnalysisResult,
    val comp: CompositionAdvice,
    val plan: CropPlan,
    val cam: CameraState,
    val params: ParameterAdvice?,
    val focalMm: Int,
    val aesthetic: AestheticResult?,
) {
    /** Evaluation window: the crop window when a crop is planned, otherwise the whole frame. */
    val win: NRect = comp.cropRect ?: NRect(0f, 0f, 1f, 1f)

    fun toWin(r: NRect) = NRect(
        (r.l - win.l) / win.w, (r.t - win.t) / win.h,
        (r.r - win.l) / win.w, (r.b - win.t) / win.h,
    )

    fun toWin(p: P) = P((p.x - win.l) / win.w, (p.y - win.t) / win.h)
}

/** A rule. Returns null when it does not apply. */
fun interface Rule {
    fun check(c: CoachInput): CheckItem?
}

/** Helpers to build results quickly. */
internal fun ok(id: String, name: String, cat: Category, msg: String) = CheckItem(id, name, cat, Severity.OK, msg)
internal fun item(
    id: String, name: String, cat: Category, sev: Severity, msg: String,
    penalty: Float = Cfg.weight(sev), move: P? = null, short: String? = null,
) = CheckItem(id, name, cat, sev, msg, penalty, move, short = short)
