package com.example.aicomposer.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicomposer.ai.AestheticResult
import com.example.aicomposer.analysis.AnalysisResult
import com.example.aicomposer.analysis.CompositionAdvice
import com.example.aicomposer.analysis.CropPlan
import com.example.aicomposer.analysis.NRect
import com.example.aicomposer.analysis.SceneType
import com.example.aicomposer.coach.CheckItem
import com.example.aicomposer.coach.CoachReport
import com.example.aicomposer.coach.Part
import com.example.aicomposer.coach.Severity
import com.example.aicomposer.i18n.tr
import com.example.aicomposer.pipeline.CameraTurn
import com.example.aicomposer.pipeline.CropMode
import com.example.aicomposer.pipeline.GuideState
import com.example.aicomposer.pipeline.PerfMonitor
import com.example.aicomposer.pipeline.effectivePlan
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

fun sevColor(s: Severity): Color = when (s) {
    Severity.ERROR -> CoachColors.Bad
    Severity.WARN -> CoachColors.Warn
    Severity.INFO -> CoachColors.Paper
    Severity.TIP -> CoachColors.Tip
    Severity.OK -> CoachColors.Good
    Severity.IGNORED -> CoachColors.Muted
}

/** Color for a 0..100 score against the auto-shutter threshold. */
fun scoreColor(score: Int, threshold: Int = 80): Color = when {
    score >= threshold -> CoachColors.Good
    score >= 60 -> CoachColors.Paper
    else -> CoachColors.Warn
}

// ---------------------------------------------------------------------------
// Viewfinder overlay
// ---------------------------------------------------------------------------

/**
 * Graphic overlay for a frame of size imgW × imgH shown with ContentScale.Fit (letterboxed, centered):
 * crop window, thirds grid, detection boxes, subject -> target arrow, text boxes, point of interest,
 * pose landmarks, AI framing and spirit level. No text, so it never hides the image.
 *
 * With [guide] (liveview only) it also draws the big direction arrows on the image edges and gives one short
 * vibration each time the framing falls into place.
 */
@Composable
fun CoachOverlay(
    imgW: Int,
    imgH: Int,
    analysis: AnalysisResult?,
    composition: CompositionAdvice?,
    aiCrop: NRect?,
    modifier: Modifier = Modifier,
    guide: GuideState? = null,
) {
    val turn = guide?.turn ?: CameraTurn.NONE
    if (guide != null) InPlaceHaptic(guide.inPlaceCount)

    // Arrows fade in quickly and out a bit slower; remember the last direction so the fade-out keeps pointing
    val kept = remember { IntArray(2) }
    if (turn.dx != 0) kept[0] = turn.dx
    if (turn.dy != 0) kept[1] = turn.dy
    val ax by animateFloatAsState(if (turn.dx != 0) 1f else 0f, tween(if (turn.dx != 0) 150 else 260), label = "arrowX")
    val ay by animateFloatAsState(if (turn.dy != 0) 1f else 0f, tween(if (turn.dy != 0) 150 else 260), label = "arrowY")
    val description = turnDescription(turn)

    Canvas(if (description != null) modifier.semantics { contentDescription = description } else modifier) {
        val scale = min(size.width / imgW, size.height / imgH)
        val dw = imgW * scale
        val dh = imgH * scale
        val ox = (size.width - dw) / 2f
        val oy = (size.height - dh) / 2f
        fun px(x: Float) = ox + x * dw
        fun py(y: Float) = oy + y * dh

        // Planned crop: darken outside the crop window, white frame marks the expected final image
        val win = composition?.cropRect
        if (win != null) {
            val dim = Color(0x88000000)
            drawRect(dim, Offset(px(0f), py(0f)), Size(dw, win.t * dh))
            drawRect(dim, Offset(px(0f), py(win.b)), Size(dw, (1f - win.b) * dh))
            drawRect(dim, Offset(px(0f), py(win.t)), Size(win.l * dw, win.h * dh))
            drawRect(dim, Offset(px(win.r), py(win.t)), Size((1f - win.r) * dw, win.h * dh))
            drawRect(Color.White, Offset(px(win.l), py(win.t)), Size(win.w * dw, win.h * dh), style = Stroke(3f))
        }

        // Thirds grid (drawn inside the crop window in crop mode)
        val gl = win?.l ?: 0f
        val gt = win?.t ?: 0f
        val gw = win?.w ?: 1f
        val gh = win?.h ?: 1f
        val grid = Color.White.copy(alpha = 0.45f)
        for (i in 1..2) {
            val x = gl + gw * i / 3f
            val y = gt + gh * i / 3f
            drawLine(grid, Offset(px(x), py(gt)), Offset(px(x), py(gt + gh)), 1.5f)
            drawLine(grid, Offset(px(gl), py(y)), Offset(px(gl + gw), py(y)), 1.5f)
        }

        // Detection boxes
        analysis?.subjects?.forEach { b ->
            val main = b == analysis.mainSubject
            drawRect(
                color = if (b.isFace) CoachColors.Signal else Color(0xFF5CE1E6),
                topLeft = Offset(px(b.rect.l), py(b.rect.t)),
                size = Size(b.rect.w * dw, b.rect.h * dh),
                style = Stroke(if (main) 4f else 2f),
            )
        }

        // Subject -> target thirds point
        composition?.let { c ->
            val sc = c.subjectCenter
            val tg = c.target
            if (sc != null && tg != null) {
                val from = Offset(px(sc.x), py(sc.y))
                val to = Offset(px(tg.x), py(tg.y))
                drawLine(CoachColors.Good, from, to, 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                drawCircle(CoachColors.Good, 16f, to, style = Stroke(3f))
                drawCircle(Color.White, 6f, from)
            }
        }

        // Recognized text (thin white boxes)
        analysis?.texts?.forEach { t ->
            drawRect(
                color = Color.White.copy(alpha = 0.7f),
                topLeft = Offset(px(t.rect.l), py(t.rect.t)),
                size = Size(t.rect.w * dw, t.rect.h * dh),
                style = Stroke(1.5f),
            )
        }

        // CLIP point of interest (orange dashed)
        analysis?.interest?.let { r ->
            drawRect(
                color = CoachColors.Warn,
                topLeft = Offset(px(r.l), py(r.t)),
                size = Size(r.w * dw, r.h * dh),
                style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
            )
        }

        // Pose landmarks
        analysis?.pose?.points?.values?.forEach { p ->
            drawCircle(Color(0xFF80DEEA), 4f, Offset(px(p.x), py(p.y)))
        }

        // AI suggested framing
        if (aiCrop != null && aiCrop.w < 0.99f) {
            drawRect(
                color = CoachColors.Ai,
                topLeft = Offset(px(aiCrop.l), py(aiCrop.t)),
                size = Size(aiCrop.w * dw, aiCrop.h * dh),
                style = Stroke(4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 12f))),
            )
        }

        // Spirit level
        analysis?.tiltDeg?.let { t ->
            val cx = px(0.5f)
            val cy = py(0.5f)
            val len = dw * 0.22f
            val rad = Math.toRadians(t.toDouble())
            val dx = (cos(rad) * len).toFloat()
            val dy = (sin(rad) * len).toFloat()
            drawLine(Color.White.copy(alpha = 0.5f), Offset(cx - len, cy), Offset(cx + len, cy), 2f)
            drawLine(if (abs(t) < 1f) CoachColors.Good else CoachColors.Warn, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), 4f)
        }

        // Direction arrows: on the edge of the image the camera should turn towards
        val s = (min(dw, dh) * 0.18f).coerceIn(44.dp.toPx(), 84.dp.toPx())
        val m = 10.dp.toPx() + s / 2f
        if (ax > 0.01f && kept[0] != 0) {
            val right = kept[0] > 0
            directionArrow(Offset(if (right) ox + dw - m else ox + m, oy + dh / 2f), if (right) 0f else 180f, s, ax)
        }
        if (ay > 0.01f && kept[1] != 0) {
            val down = kept[1] > 0
            directionArrow(Offset(ox + dw / 2f, if (down) oy + dh - m else oy + m), if (down) 90f else 270f, s, ay)
        }
    }
}

/** A chunky arrow of length [s] centered on [center], pointing at [angleDeg] (0 = right, 90 = down). */
private fun DrawScope.directionArrow(center: Offset, angleDeg: Float, s: Float, alpha: Float) {
    val half = s / 2f
    val shaft = s * 0.17f
    val head = s * 0.46f
    val neck = half - s * 0.48f
    val path = Path().apply {
        moveTo(-half, -shaft)
        lineTo(neck, -shaft)
        lineTo(neck, -head)
        lineTo(half, 0f)
        lineTo(neck, head)
        lineTo(neck, shaft)
        lineTo(-half, shaft)
        close()
    }
    withTransform({
        translate(center.x, center.y)
        rotate(angleDeg, Offset.Zero)
    }) {
        // Dark outline first so the yellow reads on a bright sky as well as on shadows
        drawPath(path, CoachColors.Ink.copy(alpha = 0.85f * alpha), style = Stroke(width = s * 0.12f, join = StrokeJoin.Round))
        drawPath(path, CoachColors.Signal.copy(alpha = alpha))
    }
}

/** Spoken description of the arrows for TalkBack. */
private fun turnDescription(t: CameraTurn): String? {
    if (t.none) return null
    val parts = listOfNotNull(
        when (t.dx) {
            1 -> tr("往右转", "右に振る", "pan right")
            -1 -> tr("往左转", "左に振る", "pan left")
            else -> null
        },
        when (t.dy) {
            1 -> tr("往下压", "下に向ける", "tilt down")
            -1 -> tr("往上抬", "上に向ける", "tilt up")
            else -> null
        },
    )
    return tr("相机" + parts.joinToString("、"), "カメラを" + parts.joinToString("、"), "Camera: " + parts.joinToString(" and "))
}

/**
 * One short vibration each time [count] goes up (the framing fell into place). The pipeline already limits how
 * often that happens (guide.cooldown_ms); the first value seen (e.g. after rotation) never vibrates.
 */
@Composable
fun InPlaceHaptic(count: Int) {
    val view = LocalView.current
    val seen = remember { intArrayOf(count) }
    LaunchedEffect(count) {
        if (count > seen[0]) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.VIRTUAL_KEY
            )
        }
        seen[0] = count
    }
}

// ---------------------------------------------------------------------------
// Hint strip
// ---------------------------------------------------------------------------

/** Score column of the hint strip. */
data class Headline(val score: Int, val color: Color, val caption: String, val scene: String?)

/** Score column of the hint strip, or null when there is no analysis yet. */
fun coachHeadline(
    composition: CompositionAdvice?,
    aesthetic: AestheticResult?,
    combined: Int?,
    threshold: Int,
    scene: SceneType?,
): Headline? {
    val c = composition ?: return null
    val total = combined ?: c.score
    return Headline(
        score = total,
        color = scoreColor(total, threshold),
        caption = if (aesthetic != null) tr("综合", "総合", "Overall") else tr("构图", "構図", "Composition"),
        scene = scene?.shortLabel,
    )
}

/** The hint lines (short phrases) with their severity color. Priority: zoom > AI framing > most severe problems. */
fun hintLines(zoomHint: String?, aiCropHint: String?, report: CoachReport?): List<Pair<String, Color>> = buildList {
    zoomHint?.let { add(it to CoachColors.Tip) }
    aiCropHint?.let { add(it to CoachColors.Ai) }
    report?.problems?.forEach { add(it.brief to sevColor(it.severity)) }
}.take(3)

/**
 * Big score on the left + 3 short hint lines, each with a severity bar. Fixed height, so nothing below jumps
 * when the hints change. Hints are in paper white (most legible in sunlight); the bar carries the severity.
 * Before the first analysis, [placeholder] fills the hint area.
 */
@Composable
fun HintStrip(headline: Headline?, lines: List<Pair<String, Color>>, placeholder: String = "") {
    Row(
        Modifier.fillMaxWidth().background(CoachColors.StripBg).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(76.dp).height(HINT_ROW * 3), verticalArrangement = Arrangement.Center) {
            Text(
                headline?.score?.toString() ?: "–",
                color = headline?.color ?: CoachColors.Muted,
                fontSize = CoachType.Score,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Black,
                style = TabularNums,
                maxLines = 1,
            )
            if (headline != null) {
                Text(headline.caption, color = CoachColors.Muted, fontSize = CoachType.Caption, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                headline.scene?.let {
                    Text(it, color = CoachColors.Paper, fontSize = CoachType.Caption, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            if (headline == null && lines.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(HINT_ROW * 3), contentAlignment = Alignment.CenterStart) {
                    Text(placeholder, color = CoachColors.Muted, fontSize = CoachType.Body, lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            } else {
                for (i in 0 until 3) {
                    val line = lines.getOrNull(i)
                    HintRow(line?.first.orEmpty(), line?.second, first = i == 0)
                }
            }
        }
    }
}

private val HINT_ROW = 28.dp

private val TabularNums = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun HintRow(text: String, bar: Color?, first: Boolean) {
    Row(Modifier.fillMaxWidth().height(HINT_ROW), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(4.dp).height(18.dp)
                .background(bar ?: Color.Transparent, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(10.dp))
        FitText(
            text,
            color = CoachColors.Paper,
            weight = if (first) FontWeight.Bold else FontWeight.Medium,
            sizes = listOf(CoachType.Hint, 15.sp, 14.sp),
            modifier = Modifier.weight(1f),
        )
    }
}

/** One line of text that steps down through [sizes] until it fits, and only then ellipsizes. */
@Composable
private fun FitText(text: String, color: Color, weight: FontWeight, sizes: List<TextUnit>, modifier: Modifier) {
    var step by remember(text) { mutableIntStateOf(0) }
    Text(
        text,
        color = color,
        fontSize = sizes[step],
        fontWeight = weight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow && step < sizes.lastIndex) step++ },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Basic building blocks
// ---------------------------------------------------------------------------

/** Guide / Settings switch, full width so both targets are large. */
@Composable
fun TabBar(tab: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(tr("指导", "ガイド", "Guide"), tr("设置", "設定", "Settings")).forEachIndexed { i, label ->
            Choice(label, tab == i, Modifier.weight(1f)) { onSelect(i) }
        }
    }
}

/**
 * Chip used for choices (selected = signal yellow) and for small secondary buttons (pass [role] = Button).
 * At least 40dp tall so it is easy to hit with a thumb outdoors.
 */
@Composable
fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, role: Role = Role.RadioButton, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 40.dp)
            .background(if (selected) CoachColors.Signal else CoachColors.Chip, RoundedCornerShape(8.dp))
            .selectable(selected = selected, role = role, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = CoachType.Body,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) CoachColors.Ink else CoachColors.Paper,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** A row of [Choice] chips that wraps onto a second line instead of clipping in the narrow landscape panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(CoachColors.Card, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = CoachType.Title, fontWeight = FontWeight.SemiBold, color = CoachColors.Paper)
        content()
    }
}

/** Secondary explanatory text (still large enough to read outdoors). */
@Composable
fun Note(text: String, color: Color = CoachColors.Muted) {
    Text(text, fontSize = CoachType.Caption, lineHeight = 18.sp, color = color)
}

@Composable
fun CheckLine(item: CheckItem, onIgnore: (() -> Unit)?) {
    val merged = item.mergedInto != null
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).alpha(if (merged) 0.6f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().padding(vertical = 2.dp).background(sevColor(item.severity), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(
                item.name + (if (item.severity == Severity.TIP) tr(" · 建议", " · ヒント", " · tip") else ""),
                fontSize = CoachType.Body,
                fontWeight = FontWeight.SemiBold,
                color = sevColor(item.severity),
            )
            Text(item.message, fontSize = CoachType.Body, lineHeight = 21.sp, color = CoachColors.Paper)
            item.mergedInto?.let {
                Note(tr("和「$it」同一原因，只扣一次分", "「$it」と同じ原因のため減点は 1 回のみ", "Same cause as \"$it\", counted once"))
            }
        }
        if (onIgnore != null) {
            Spacer(Modifier.width(8.dp))
            Choice(tr("故意的", "意図的", "Intentional"), false, role = Role.Button, onClick = onIgnore)
        }
    }
}

// ---------------------------------------------------------------------------
// Sections used by all apps
// ---------------------------------------------------------------------------

/** Full checklist; with [onIgnore], every problem gets an "Intentional" button. */
@Composable
fun ChecklistSection(report: CoachReport, onIgnore: ((CheckItem) -> Unit)? = null) {
    val n = report.problems.count { it.mergedInto == null }
    Section(tr("体检清单", "チェックリスト", "Checklist")) {
        // Part scores live here (the hint strip only shows the overall score)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            PartScore(tr("总分", "総合", "Total"), report.score, big = true)
            Part.entries.forEach { PartScore(it.label, report.part(it)) }
        }
        Note(if (n == 0) tr("没有发现问题", "問題は見つかりません", "No problems found") else tr("$n 个问题", "問題 $n 件", if (n == 1) "1 problem" else "$n problems"))
        report.problems.forEach { item -> CheckLine(item, onIgnore?.let { f -> { f(item) } }) }
        report.tips.forEach { CheckLine(it, null) }
        if (report.passed.isNotEmpty()) {
            Text(
                "✓ " + report.passed.joinToString(tr("、", "、", ", ")) { it.name },
                fontSize = CoachType.Caption, lineHeight = 18.sp, color = CoachColors.Good,
            )
        }
    }
}

@Composable
private fun PartScore(label: String, value: Int, big: Boolean = false) {
    Column {
        Text(
            value.toString(),
            fontSize = if (big) 24.sp else CoachType.Hint,
            fontWeight = FontWeight.Bold,
            color = scoreColor(value),
            style = TabularNums,
        )
        Text(label, fontSize = CoachType.Caption, color = CoachColors.Muted, maxLines = 1)
    }
}

/** AI aesthetics result + model / performance status. */
@Composable
fun AestheticSection(
    aesthetic: AestheticResult?,
    aiStatus: String,
    aiAvailable: Boolean,
    segmentationOk: Boolean,
    perf: PerfMonitor.Stats,
    configStatus: String,
) {
    Section(tr("AI 美学", "AI 美的評価", "AI aesthetics")) {
        if (aesthetic != null) {
            Text(
                tr("美学 ", "美的評価 ", "Aesthetic ") + "${String.format(Locale.ROOT, "%.2f", aesthetic.score)} (${aesthetic.norm}/100)   " +
                    tr("构图好 ", "良い構図 ", "Well composed ") + "${(aesthetic.compositionProb * 100).toInt()}%",
                fontSize = CoachType.Hint,
                fontWeight = FontWeight.Bold,
                style = TabularNums,
            )
            Text(
                tr("CLIP 场景：", "CLIP シーン：", "CLIP scene: ") +
                    "${aesthetic.scene?.label ?: "-"} (${(aesthetic.sceneConf * 100).toInt()}%)   ${aesthetic.millis}ms",
                fontSize = CoachType.Body,
                color = CoachColors.Muted,
            )
        } else {
            Text(aiStatus, fontSize = CoachType.Body, color = if (aiAvailable) CoachColors.Muted else CoachColors.Warn)
        }
        Note(
            tr("本地模型：人脸、物体、场景标签、人体姿态、主体分割", "端末内モデル：顔、物体、シーンラベル、姿勢、被写体セグメンテーション", "On-device models: faces, objects, scene labels, pose, subject segmentation") +
                if (segmentationOk) "" else tr("（分割不可用：需要 Google Play 服务）", "（セグメンテーション不可：Google Play 開発者サービスが必要）", " (segmentation unavailable: needs Google Play services)"),
        )
        Note(
            tr("分析 ", "分析 ", "Analysis ") + "${perf.analysisMs}ms, ${String.format(Locale.ROOT, "%.1f", perf.fps)}" +
                tr(" 次/秒，手机温度 ", " 回/秒、端末温度 ", "/s, phone temperature ") + perf.thermal +
                when (perf.level) {
                    2 -> tr("，已暂停美学分析", "、美的評価を一時停止中", ", aesthetics paused")
                    1 -> tr("，已降频", "、処理頻度を低下中", ", throttled")
                    else -> ""
                },
            color = if (perf.level > 0) CoachColors.Warn else CoachColors.Muted,
        )
        if (configStatus.isNotEmpty()) Note(tr("规则配置：", "ルール設定：", "Rule config: ") + configStatus)
    }
}

/** Auto-shutter trigger threshold slider. */
@Composable
fun AutoShutterSection(threshold: Int, onChange: (Int) -> Unit) {
    Section(tr("自动快门", "自動シャッター", "Auto shutter")) {
        Text(tr("触发分数 ≥ $threshold", "作動スコア ≥ $threshold", "Trigger score ≥ $threshold"), fontSize = CoachType.Body)
        Slider(value = threshold.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = 50f..95f)
    }
}

/**
 * Compact auto-shutter switch for the action bar: the switch with a short label under it. The trigger rule
 * ("overall ≥ 80") is spoken by TalkBack and shown in the Settings tab.
 */
@Composable
fun AutoShutterToggle(checked: Boolean, enabled: Boolean, threshold: Int, aiAvailable: Boolean, onChange: (Boolean) -> Unit) {
    val rule = if (aiAvailable) tr("自动快门：综合 ≥ $threshold", "自動シャッター：総合 ≥ $threshold", "Auto shutter: overall ≥ $threshold")
    else tr("自动快门：构图 ≥ $threshold", "自動シャッター：構図 ≥ $threshold", "Auto shutter: composition ≥ $threshold")
    Column(
        Modifier.semantics(mergeDescendants = true) { contentDescription = rule },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(tr("自动快门", "自動", "Auto"), fontSize = CoachType.Caption, color = CoachColors.Muted, maxLines = 1)
    }
}

/** Planned-crop settings: mode, keep ratio, final aspect, and what auto mode decided. */
@Composable
fun CropSection(
    cropMode: CropMode,
    cropPlan: CropPlan,
    autoKeep: Float,
    autoReason: String,
    analysis: AnalysisResult?,
    focalMm: Int,
    onMode: (CropMode) -> Unit,
    onKeep: (Float) -> Unit,
    onAspect: (Float) -> Unit,
) {
    Section(tr("预留裁剪（镜头不够长时）", "トリミング前提（焦点距離が足りないとき）", "Planned crop (when the lens is too short)")) {
        ChoiceRow {
            CropMode.entries.forEach { m -> Choice(m.label, cropMode == m) { onMode(m) } }
        }
        if (cropMode == CropMode.MANUAL) {
            Text(tr("保留画面宽度", "残す画面の幅", "Width to keep"), fontSize = CoachType.Body)
            ChoiceRow {
                listOf(1f to tr("不裁", "なし", "None"), 0.8f to "80%", 0.7f to "70%", 0.6f to "60%", 0.5f to "50%").forEach { (k, label) ->
                    Choice(label, abs(cropPlan.keep - k) < 0.01f) { onKeep(k) }
                }
            }
        }
        if (cropMode != CropMode.OFF) {
            Text(tr("成片画幅", "仕上がりの縦横比", "Final aspect ratio"), fontSize = CoachType.Body)
            ChoiceRow {
                listOf(1.5f to "3:2", 4f / 3f to "4:3", 1f to "1:1", 0.8f to "4:5", 16f / 9f to "16:9").forEach { (a, label) ->
                    Choice(label, abs(cropPlan.aspect - a) < 0.01f) { onAspect(a) }
                }
            }
        }
        // Changing explanation text goes last so it does not move the controls above
        if (cropMode == CropMode.AUTO) {
            val pct = (autoKeep * 100).toInt()
            val keepText = if (autoKeep >= 0.99f) tr("不裁剪", "トリミングなし", "no crop") else tr("保留 $pct%", "$pct% を残す", "keep $pct%")
            Text(tr("自动：", "自動：", "Auto: ") + keepText, fontSize = CoachType.Hint, fontWeight = FontWeight.Bold)
            if (autoReason.isNotEmpty()) Note(autoReason)
        }
        analysis?.let { an ->
            val plan = effectivePlan(cropMode, cropPlan, autoKeep)
            if (plan.active) {
                val mp = plan.megapixels(an.frameAspect)
                val mag = plan.magnification(an.frameAspect)
                val eq = (focalMm * mag).toInt()
                val ff = (focalMm * mag * 1.5f).toInt()
                Note(
                    tr("裁剪后约 ", "トリミング後 約 ", "About ") + String.format(Locale.ROOT, "%.1f", mp) +
                        tr(" 百万像素，相当于 ", " メガピクセル、", " MP after cropping, equivalent to ") +
                        "${eq}mm" + tr("（全画幅 ${ff}mm）", "相当（35mm 換算 ${ff}mm）", " (${ff}mm full-frame)"),
                )
            }
        }
    }
}

/** Rules the user marked as intentional, with a Restore button each. */
@Composable
fun IgnoredRulesSection(ignoredRules: Set<String>, ruleNames: Map<String, String>, onRestore: (String) -> Unit) {
    if (ignoredRules.isEmpty()) return
    Section(tr("已忽略的规则（你标记为「故意的」）", "無視中のルール（「意図的」にしたもの）", "Ignored rules (marked as \"intentional\")")) {
        ignoredRules.sorted().forEach { key ->
            val (scene, id) = key.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val sceneName = if (scene == "*") tr("所有场景", "すべてのシーン", "All scenes")
            else runCatching { SceneType.valueOf(scene).label }.getOrDefault(scene)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ruleNames[id] ?: id}（$sceneName）".let { tr(it, it, "${ruleNames[id] ?: id} ($sceneName)") }, Modifier.weight(1f), fontSize = CoachType.Body)
                Choice(tr("恢复", "戻す", "Restore"), false, role = Role.Button) { onRestore(key) }
            }
        }
    }
}
