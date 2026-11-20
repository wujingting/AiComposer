package com.example.aicomposer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.aicomposer.i18n.tr

internal val DetectionCyan = Color(0xFF5CE1E6)
internal val PoseCyan = Color(0xFF80DEEA)

/** Single-line color key under the image. Longer translations and the detailed key can scroll. */
@Composable
fun OverlayLegend(showDetails: Boolean, liveView: Boolean, modifier: Modifier = Modifier) {
    val legend = buildAnnotatedString {
        fun entry(color: Color, label: String) {
            if (length > 0) append("   ")
            withStyle(SpanStyle(color = color)) { append(label) }
        }
        entry(CoachColors.Signal, tr("黄框 主体", "黄枠 主被写体", "Yellow · subject"))
        entry(CoachColors.Good, tr("绿虚线 目标", "緑の破線 目標", "Green · target"))
        append(tr("   水平仪 ", "   水準器 ", "   "))
        withStyle(SpanStyle(color = CoachColors.Good)) { append(tr("绿平", "緑 水平", "Green = level")) }
        append(" / ")
        withStyle(SpanStyle(color = CoachColors.Warn)) { append(tr("橙斜", "橙 傾き", "orange = tilted")) }
        if (liveView) {
            entry(CoachColors.Signal, tr("黄箭头 相机移动方向", "黄矢印 カメラを動かす方向", "Yellow arrows · camera direction"))
        }
        if (showDetails) {
            entry(DetectionCyan, tr("青框 其他识别对象", "水色の枠 ほかの検出対象", "Cyan boxes · other detections"))
            entry(Color.White, tr("白框 文字或预留裁剪", "白枠 文字・クロップ範囲", "White boxes · text or planned crop"))
            entry(Color.White, tr("白网格 三分线", "白い格子 三分割線", "White grid · thirds"))
            entry(CoachColors.Warn, tr("橙虚线 看点", "橙の破線 注目領域", "Orange dashes · point of interest"))
            entry(PoseCyan, tr("浅青点 姿态", "淡い水色の点 姿勢", "Pale cyan dots · pose"))
            entry(CoachColors.Ai, tr("紫虚线 AI 裁剪", "紫の破線 AIクロップ", "Purple dashes · AI crop"))
        }
    }
    Text(
        text = legend,
        modifier = modifier.fillMaxWidth().background(CoachColors.StripBg)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 3.dp),
        fontSize = CoachType.Caption,
        color = CoachColors.Paper,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
fun RecognitionDetailsToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tr("显示识别细节", "認識の詳細を表示", "Show recognition details"),
            modifier = Modifier.weight(1f),
            fontSize = CoachType.Body,
            color = CoachColors.Paper,
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}
