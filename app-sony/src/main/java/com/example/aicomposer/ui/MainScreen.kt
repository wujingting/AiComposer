package com.example.aicomposer.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aicomposer.ConnState
import com.example.aicomposer.MainViewModel
import com.example.aicomposer.UiState
import com.example.aicomposer.analysis.ParameterAdvisor
import com.example.aicomposer.i18n.tr

/** Width of the side panel in landscape. */
private val PANEL_WIDTH = 340.dp

@Composable
fun MainScreen(vm: MainViewModel) {
    val s by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Layout follows the phone orientation: landscape = viewfinder left + panel right; portrait = viewfinder top + panel bottom
    // Panel from top to bottom: hint strip (fixed height) -> Guide / Settings tab (scrollable) -> action buttons (pinned at the bottom, never move)
    val cfg = LocalConfiguration.current
    if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            Viewfinder(s, vm, Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(PANEL_WIDTH).fillMaxHeight().background(CoachColors.PanelBg)) {
                Hints(s)
                TabBar(tab) { tab = it }
                PanelContent(s, vm, tab, Modifier.weight(1f))
                ActionBar(s, vm)
            }
        }
    } else {
        // Viewfinder fills the width at the frame's aspect ratio (a6000 liveview is 3:2), at most 45% of the screen height
        val aspect = if (s.frameW > 0) s.frameW.toFloat() / s.frameH else 1.5f
        val vfHeight = minOf(cfg.screenWidthDp / aspect, cfg.screenHeightDp * 0.45f).dp
        Column(Modifier.fillMaxSize().background(CoachColors.PanelBg)) {
            Viewfinder(s, vm, Modifier.fillMaxWidth().height(vfHeight).background(Color.Black))
            Hints(s)
            TabBar(tab) { tab = it }
            PanelContent(s, vm, tab, Modifier.weight(1f))
            ActionBar(s, vm)
        }
    }
}

@Composable
private fun Hints(s: UiState) {
    val headline = coachHeadline(s.composition, s.aesthetic, s.combinedScore, s.autoThreshold, s.params?.scene)
    HintStrip(
        headline,
        hintLines(s.zoomHint, s.crop?.let { it.short ?: it.hint }, s.report),
        placeholder = s.message.ifEmpty { tr("未连接相机", "カメラ未接続", "Camera not connected") },
    )
}

/** Bottom action bar: button positions are fixed and never move with the hints */
@Composable
private fun ActionBar(s: UiState, vm: MainViewModel) {
    val connected = s.conn == ConnState.CONNECTED
    val pad = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
    val tall = Modifier.heightIn(min = 52.dp)
    Column(
        Modifier.fillMaxWidth().background(CoachColors.StripBg).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.takePicture() }, enabled = connected, contentPadding = pad, modifier = Modifier.weight(1f).then(tall)) {
                Text(tr("拍照", "撮影", "Shoot"), maxLines = 1)
            }
            Button(
                onClick = { vm.applyAdvice() }, enabled = connected && s.params != null,
                contentPadding = pad, modifier = Modifier.weight(1f).then(tall),
            ) { Text(tr("应用建议", "提案を適用", "Apply"), maxLines = 1) }
            OutlinedButton(
                colors = secondaryButtonColors(),
                onClick = { vm.findBestCrop() }, enabled = connected && s.aiAvailable && !s.cropBusy,
                contentPadding = pad, modifier = Modifier.weight(1f).then(tall),
            ) { Text(if (s.cropBusy) tr("分析中…", "分析中…", "Working…") else tr("AI构图", "AI 構図", "AI framing"), maxLines = 1) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.aiAvailable) tr("自动快门（综合 ≥ ${s.autoThreshold}）", "自動シャッター（総合 ≥ ${s.autoThreshold}）", "Auto shutter (overall ≥ ${s.autoThreshold})")
                else tr("自动快门（构图 ≥ ${s.autoThreshold}）", "自動シャッター（構図 ≥ ${s.autoThreshold}）", "Auto shutter (composition ≥ ${s.autoThreshold})"),
                Modifier.weight(1f),
                fontSize = CoachType.Body,
                maxLines = 2,
                lineHeight = 19.sp,
            )
            Switch(checked = s.autoShutter, onCheckedChange = { vm.setAutoShutter(it) }, enabled = connected)
            Box(Modifier.width(112.dp).padding(start = 8.dp)) {
                when (s.conn) {
                    ConnState.DISCONNECTED, ConnState.ERROR ->
                        Button(onClick = { vm.connect() }, contentPadding = pad, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("连接相机", "接続", "Connect"), maxLines = 1)
                        }
                    ConnState.CONNECTING ->
                        OutlinedButton(colors = secondaryButtonColors(), onClick = {}, enabled = false, contentPadding = pad, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("连接中…", "接続中…", "Connecting…"), maxLines = 1)
                        }
                    ConnState.CONNECTED ->
                        OutlinedButton(colors = secondaryButtonColors(), onClick = { vm.disconnect() }, contentPadding = pad, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("断开", "切断", "Disconnect"), maxLines = 1)
                        }
                }
            }
        }
    }
}

/** Viewfinder image + graphic overlays (text hints live in the hint strip so they do not cover the image) */
@Composable
private fun Viewfinder(s: UiState, vm: MainViewModel, modifier: Modifier) {
    Box(modifier) {
        if (s.frameW > 0) {
            LiveImage(vm, Modifier.fillMaxSize())
            CoachOverlay(s.frameW, s.frameH, s.analysis, s.composition, s.crop?.rect, Modifier.fillMaxSize(), guide = s.guide)
        } else {
            // Scrollable so the whole text is readable even when the viewfinder area is short (portrait)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    tr(
                        "使用步骤：\n" +
                            "1. 相机：MENU → 应用程序 → 智能遥控内嵌\n" +
                            "2. 手机连上相机的 Wi-Fi（DIRECT-xxxx），提示无互联网时选「保持连接」\n" +
                            "3. 点「连接相机」\n" +
                            "提示：相机打开「实时取景显示 → 设置效果：开」，参数建议更准",
                        "使い方：\n" +
                            "1. カメラ：MENU → アプリケーション → スマートリモコン内蔵\n" +
                            "2. スマホをカメラの Wi-Fi（DIRECT-xxxx）に接続。「インターネットなし」と出たら接続を維持\n" +
                            "3. 「接続」をタップ\n" +
                            "ヒント：カメラの「ライブビュー表示 → 設定効果反映：入」で設定の提案がより正確に",
                        "How to use:\n" +
                            "1. Camera: MENU → Application → Smart Remote Embedded\n" +
                            "2. Join the camera's Wi-Fi (DIRECT-xxxx); if it warns about no internet, stay connected\n" +
                            "3. Tap \"Connect\"\n" +
                            "Tip: set \"Live View Display → Setting Effect: ON\" on the camera for better settings advice",
                    ),
                    fontSize = CoachType.Body,
                    lineHeight = 22.sp,
                )
            }
        }
    }
}

/** The only composable that reads the liveview frame flow: a new frame recomposes just this image. */
@Composable
private fun LiveImage(vm: MainViewModel, modifier: Modifier) {
    val frame by vm.frame.collectAsStateWithLifecycle()
    frame?.let {
        Image(
            bitmap = it,
            contentDescription = tr("实时取景", "ライブビュー", "Liveview"),
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}

@Composable
private fun PanelContent(s: UiState, vm: MainViewModel, tab: Int, modifier: Modifier) {
    // Each tab remembers its own scroll position
    key(tab) {
        Column(
            modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (tab == 0) GuideTab(s, vm) else SettingsTab(s, vm)
        }
    }
}

/** "Guide" tab: content that changes in real time */
@Composable
private fun GuideTab(s: UiState, vm: MainViewModel) {
    if (s.message.isNotEmpty()) Note(s.message)

    s.report?.let { rep -> ChecklistSection(rep) { vm.ignoreRule(it.id, it.name) } }

    s.params?.let { p ->
        Section(tr("建议参数 · ", "おすすめ設定 · ", "Suggested settings · ") + p.scene.label) {
            Text(
                "${p.mode}\nf/${p.fNumber ?: "-"}   ${p.shutter ?: "-"}   ISO ${p.iso ?: "-"}   ${p.evText}",
                fontSize = CoachType.Big,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            p.reasons.forEach { Note("• $it") }
            p.warnings.forEach { Note("⚠ $it", CoachColors.Warn) }
        }
    }

    val cam = s.camera
    Section(tr("相机当前", "カメラの現在値", "Camera now")) {
        Text(
            "${cam.exposureMode ?: "-"}   f/${cam.fNumber ?: "-"}   ${cam.shutter ?: "-"}   " +
                "ISO ${cam.iso ?: "-"}   ${cam.evValue?.let { ParameterAdvisor.formatEv(it) } ?: ""}",
            fontSize = CoachType.Hint,
        )
    }

    AestheticSection(s.aesthetic, s.aiStatus, s.aiAvailable, s.segmentationOk, s.perf, s.configStatus)
}

/** "Settings" tab: does not change with the viewfinder, so controls stay in place */
@Composable
private fun SettingsTab(s: UiState, vm: MainViewModel) {
    AutoShutterSection(s.autoThreshold) { vm.setThreshold(it) }

    Section(tr("镜头焦距（用于防抖计算）", "レンズの焦点距離（手ブレ計算用）", "Lens focal length (for shake calculation)")) {
        ChoiceRow {
            listOf(35, 50).forEach { f -> Choice("${f}mm", s.focalLength == f) { vm.setFocal(f) } }
        }
        Text("${s.focalLength} mm", fontSize = CoachType.Hint)
        Slider(value = s.focalLength.toFloat(), onValueChange = { vm.setFocal(it.toInt()) }, valueRange = 10f..300f)
    }

    CropSection(
        s.cropMode, s.cropPlan, s.autoKeep, s.autoReason, s.analysis, s.focalLength,
        onMode = vm::setCropMode, onKeep = vm::setCropKeep, onAspect = vm::setCropAspect,
    )

    IgnoredRulesSection(s.ignoredRules, s.ruleNames) { vm.restoreRule(it) }
}
