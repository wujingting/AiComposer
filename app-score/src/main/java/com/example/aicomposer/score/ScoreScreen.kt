package com.example.aicomposer.score

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aicomposer.ai.AestheticResult
import com.example.aicomposer.i18n.tr
import com.example.aicomposer.ui.ChecklistSection
import com.example.aicomposer.ui.Choice
import com.example.aicomposer.ui.CoachColors
import com.example.aicomposer.ui.CoachOverlay
import com.example.aicomposer.ui.CoachType
import com.example.aicomposer.ui.Note
import com.example.aicomposer.ui.OverlayLegend
import com.example.aicomposer.ui.RecognitionDetailsToggle
import com.example.aicomposer.ui.secondaryButtonColors
import com.example.aicomposer.ui.Section
import java.util.Locale

/** At most this many photos per pick (the system picker's own limit may be lower). */
private const val MAX_PICK = 100

@Composable
fun ScoreScreen(vm: ScoreViewModel) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { vm.add(it) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    val detail = s.detail
    if (detail != null) {
        BackHandler { vm.close() }
        DetailScreen(s, detail, vm)
    } else {
        ListScreen(s, vm, pick)
    }
}

// ---------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------

@Composable
private fun ListScreen(s: ScoreUiState, vm: ScoreViewModel, pick: () -> Unit) {
    Column(Modifier.fillMaxSize().background(CoachColors.PanelBg)) {
        Column(
            Modifier.fillMaxWidth().background(CoachColors.StripBg).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("AI 照片打分", "AI 写真採点", "AI Photo Score"), fontSize = CoachType.Big, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (s.photos.isNotEmpty()) {
                    OutlinedButton(colors = secondaryButtonColors(), onClick = vm::clear, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(tr("清空", "クリア", "Clear"), maxLines = 1)
                    }
                }
                Button(onClick = pick, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(tr("选择照片", "写真を選ぶ", "Pick photos"), maxLines = 1)
                }
            }
            Text(statusLine(s), fontSize = CoachType.Body, color = if (s.modelReady && !s.aiAvailable) CoachColors.Warn else CoachColors.Muted)
            if (s.photos.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("排序", "並び順", "Sort"), fontSize = CoachType.Body, color = CoachColors.Muted)
                    Choice(tr("分数", "スコア", "Score"), s.sort == SortOrder.SCORE) { vm.setSort(SortOrder.SCORE) }
                    Choice(tr("添加顺序", "追加順", "Added"), s.sort == SortOrder.ADDED) { vm.setSort(SortOrder.ADDED) }
                }
            }
        }

        if (s.photos.isEmpty()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tr(
                        "从相册选几张照片，AI 会按美学分（约 1~10）排序，标出最好的 20%（★ 精选）和连拍里的相似照片。\n点开一张照片，可以看到它的构图体检清单和 AI 建议的裁剪。\n也可以在相册里选好照片，点「分享」→ 本 App。",
                        "アルバムから写真を選ぶと、AI が美的評価（約 1〜10）で並べ替え、上位 20%（★ セレクト）と連写の類似写真を表示します。\n写真を開くと、構図のチェックリストと AI のトリミング提案が見られます。\nアルバムで写真を選んで「共有」→ このアプリ、でも追加できます。",
                        "Pick some photos and the AI sorts them by aesthetic score (about 1–10), marking the best 20% (★ Best) and near-duplicate burst shots.\nOpen a photo to see its composition checklist and the AI's suggested crop.\nYou can also select photos in your gallery and Share them to this app.",
                    ),
                    fontSize = 16.sp, lineHeight = 25.sp, color = CoachColors.Paper,
                )
                Button(onClick = pick, modifier = Modifier.heightIn(min = 52.dp)) { Text(tr("选择照片", "写真を選ぶ", "Pick photos")) }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(s.sorted, key = { it.id }) { p -> PhotoCard(p) { vm.open(p.id) } }
            }
        }
    }
}

private fun statusLine(s: ScoreUiState): String {
    if (!s.modelReady || !s.aiAvailable) return s.aiStatus
    val n = s.photos.size
    val best = s.photos.count { it.picked }
    val dup = s.photos.count { it.duplicateOf != null }
    return if (s.pending > 0) tr(
        "正在打分… ${n - s.pending}/$n",
        "採点中… ${n - s.pending}/$n",
        "Scoring… ${n - s.pending}/$n",
    ) else if (n == 0) s.aiStatus else tr(
        "$n 张 · ★ 精选 $best 张 · 相似 $dup 张",
        "$n 枚 · ★ セレクト $best 枚 · 類似 $dup 枚",
        "$n photos · ★ best $best · near-duplicates $dup",
    )
}

@Composable
private fun PhotoCard(p: ScoredPhoto, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).background(CoachColors.Card).clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            val thumb = p.thumb
            if (thumb != null) {
                Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else if (p.error == null) {
                CircularProgressIndicator(Modifier.width(28.dp))
            }
            val a = p.aesthetic
            if (a != null) {
                Text(
                    String.format(Locale.ROOT, "%.2f", a.score),
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = CoachType.Hint, fontWeight = FontWeight.Bold, color = scoreColor(a),
                )
            }
            val badge = when {
                p.picked -> tr("★ 精选", "★ セレクト", "★ Best") to CoachColors.Good
                p.duplicateOf != null -> tr("相似", "類似", "Similar") to CoachColors.Muted
                else -> null
            }
            if (badge != null) {
                Text(
                    badge.first,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(badge.second, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = CoachType.Caption, fontWeight = FontWeight.Bold, color = CoachColors.Ink,
                )
            }
        }
        Text(
            p.error ?: p.aesthetic?.let {
                (it.scene?.label ?: "") + tr(" · 构图 ", " · 構図 ", " · composition ") + "${(it.compositionProb * 100).toInt()}%"
            } ?: tr("等待打分…", "採点待ち…", "Waiting…"),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            fontSize = CoachType.Caption, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (p.error != null) CoachColors.Warn else CoachColors.Muted,
        )
    }
}

private fun scoreColor(a: AestheticResult): Color = when {
    a.score >= 6.0f -> CoachColors.Good
    a.score >= 5.0f -> CoachColors.Paper
    else -> CoachColors.Warn
}

// ---------------------------------------------------------------------------
// Detail
// ---------------------------------------------------------------------------

@Composable
private fun DetailScreen(s: ScoreUiState, d: Detail, vm: ScoreViewModel) {
    val photo = s.photos.firstOrNull { it.id == d.id } ?: return
    val cfg = LocalConfiguration.current
    if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            DetailImage(d, s.showDetails, Modifier.weight(1f).fillMaxHeight())
            DetailPanel(s, photo, d, vm, Modifier.width(360.dp).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize().background(CoachColors.PanelBg)) {
            DetailImage(d, s.showDetails, Modifier.fillMaxWidth().height((cfg.screenHeightDp * 0.45f).dp))
            DetailPanel(s, photo, d, vm, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun DetailImage(d: Detail, showDetails: Boolean, modifier: Modifier) {
    val img = d.image
    Column(
        modifier.background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (img != null) {
            Box(Modifier.weight(1f, fill = false).aspectRatio(img.width.toFloat() / img.height)) {
                Image(img, tr("照片", "写真", "Photo"), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                CoachOverlay(
                    img.width, img.height, d.analysis, d.composition, d.crop?.rect, Modifier.fillMaxSize(),
                    showDetails = showDetails,
                )
            }
            OverlayLegend(showDetails = showDetails, liveView = false, modifier = Modifier.fillMaxWidth())
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun DetailPanel(s: ScoreUiState, p: ScoredPhoto, d: Detail, vm: ScoreViewModel, modifier: Modifier) {
    Column(modifier.background(CoachColors.PanelBg)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RecognitionDetailsToggle(checked = s.showDetails, onCheckedChange = vm::setShowDetails)
            Section(tr("AI 美学", "AI 美的評価", "AI aesthetics")) {
                val a = p.aesthetic
                if (a != null) {
                    val rank = s.sorted.indexOfFirst { it.id == p.id } + 1
                    Text(
                        tr("美学 ", "美的評価 ", "Aesthetic ") + String.format(Locale.ROOT, "%.2f", a.score) +
                            tr("（第 $rank / ${s.photos.size} 名）", "（${s.photos.size} 枚中 $rank 位）", " (#$rank of ${s.photos.size})"),
                        fontSize = CoachType.Big, fontWeight = FontWeight.Bold, color = scoreColor(a),
                    )
                    Text(
                        tr("构图好 ", "良い構図 ", "Well composed ") + "${(a.compositionProb * 100).toInt()}%   " +
                            tr("场景：", "シーン：", "Scene: ") + (a.scene?.label ?: "-"),
                        fontSize = CoachType.Body,
                    )
                    when {
                        p.picked -> Note(tr("★ 精选（这组里最好的 20%）", "★ セレクト（このセットの上位 20%）", "★ Best (top 20% of this set)"), CoachColors.Good)
                        p.duplicateOf != null -> Note(tr("和另一张入选照片很像（连拍？）", "選ばれた別の写真とよく似ています（連写？）", "Very similar to another picked photo (burst?)"))
                    }
                } else {
                    Text(p.error ?: tr("等待打分…", "採点待ち…", "Waiting…"), fontSize = CoachType.Body, color = CoachColors.Warn)
                }
                if (p.exif.summary.isNotEmpty()) Note(p.exif.summary)
            }

            d.crop?.let { c ->
                Section(tr("AI 构图", "AI 構図", "AI framing")) {
                    val text = if (c.rect.w >= 0.99f) tr("现在的构图已经不错，不需要裁剪", "今の構図で十分良く、トリミングは不要です", "The current framing is already good; no crop needed")
                    else if (s.showDetails) tr(
                        "紫框是 AI 建议的裁剪：美学分 ${fmt(c.baseScore)} → ${fmt(c.score)}。后期按紫框裁剪即可",
                        "紫の枠が AI のトリミング提案です：美的評価 ${fmt(c.baseScore)} → ${fmt(c.score)}。編集時にこの枠でトリミングしてください",
                        "The purple frame is the AI's suggested crop: aesthetic ${fmt(c.baseScore)} → ${fmt(c.score)}. Crop to it when editing",
                    ) else tr(
                        "建议裁剪后的美学分 ${fmt(c.baseScore)} → ${fmt(c.score)}",
                        "推奨トリミング後の美的評価 ${fmt(c.baseScore)} → ${fmt(c.score)}",
                        "Aesthetic score with the suggested crop: ${fmt(c.baseScore)} → ${fmt(c.score)}",
                    )
                    Text(text, fontSize = CoachType.Body, lineHeight = 21.sp, color = CoachColors.Ai)
                }
            }

            when {
                d.analyzing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                    Text(tr("正在分析构图…", "構図を分析中…", "Analyzing composition…"), fontSize = CoachType.Body, color = CoachColors.Muted)
                }
                d.report != null -> ChecklistSection(d.report)
                d.error != null -> Text(d.error, fontSize = CoachType.Body, color = CoachColors.Warn)
            }
        }
        Row(
            Modifier.fillMaxWidth().background(CoachColors.StripBg).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(colors = secondaryButtonColors(), onClick = vm::close, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(tr("返回", "戻る", "Back"), maxLines = 1) }
            Button(onClick = vm::findBestCrop, enabled = s.aiAvailable && !d.cropBusy && d.image != null, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Text(if (d.cropBusy) tr("分析中…", "分析中…", "Working…") else tr("AI构图", "AI 構図", "AI framing"), maxLines = 1)
            }
        }
    }
}

private fun fmt(v: Float) = String.format(Locale.ROOT, "%.2f", v)
