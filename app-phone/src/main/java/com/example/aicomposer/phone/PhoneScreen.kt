package com.example.aicomposer.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aicomposer.analysis.ParameterAdvisor
import com.example.aicomposer.i18n.tr
import com.example.aicomposer.ui.AestheticSection
import com.example.aicomposer.ui.AutoShutterSection
import com.example.aicomposer.ui.AutoShutterToggle
import com.example.aicomposer.ui.secondaryButtonColors
import com.example.aicomposer.ui.ChecklistSection
import com.example.aicomposer.ui.Choice
import com.example.aicomposer.ui.ChoiceRow
import com.example.aicomposer.ui.CoachType
import com.example.aicomposer.ui.Note
import com.example.aicomposer.ui.CoachColors
import com.example.aicomposer.ui.CoachOverlay
import com.example.aicomposer.ui.FramingReferenceCard
import com.example.aicomposer.ui.HintStrip
import com.example.aicomposer.ui.IgnoredRulesSection
import com.example.aicomposer.ui.Section
import com.example.aicomposer.ui.TabBar
import com.example.aicomposer.ui.coachHeadline
import com.example.aicomposer.ui.hintLines
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PhoneScreen(vm: PhoneViewModel) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setPermission(it) }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        vm.setPermission(granted)
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Same layout as the Sony app: landscape = viewfinder left + panel right; portrait = viewfinder top + panel bottom
    val cfg = LocalConfiguration.current
    if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            Viewfinder(s, vm, Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(340.dp).fillMaxHeight().background(CoachColors.PanelBg)) {
                Hints(s)
                TabBar(tab) { tab = it }
                PanelContent(s, vm, tab, Modifier.weight(1f))
                ActionBar(s, vm) { launcher.launch(Manifest.permission.CAMERA) }
            }
        }
    } else {
        // Portrait frames are 3:4 (tall); give the viewfinder at most 55% of the screen height
        val aspect = s.frameW.toFloat() / s.frameH
        val vfHeight = minOf(cfg.screenWidthDp / aspect, cfg.screenHeightDp * 0.55f).dp
        Column(Modifier.fillMaxSize().background(CoachColors.PanelBg)) {
            Viewfinder(s, vm, Modifier.fillMaxWidth().height(vfHeight).background(Color.Black))
            Hints(s)
            TabBar(tab) { tab = it }
            PanelContent(s, vm, tab, Modifier.weight(1f))
            ActionBar(s, vm) { launcher.launch(Manifest.permission.CAMERA) }
        }
    }
}

@Composable
private fun Hints(s: PhoneUiState) {
    val headline = coachHeadline(s.composition, s.aesthetic, s.combinedScore, s.autoThreshold, s.analysis?.scene)
    HintStrip(
        headline,
        hintLines(null, s.crop?.let { it.short ?: it.hint }, s.report),
        placeholder = s.message.ifEmpty { tr("正在启动相机…", "カメラを起動中…", "Starting the camera…") },
    )
}

/** Camera preview (FIT_CENTER, 4:3 like the analyzed frames) + the shared overlay. Tap to focus and meter. */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun Viewfinder(s: PhoneUiState, vm: PhoneViewModel, modifier: Modifier) {
    Box(modifier) {
        if (!s.hasPermission) {
            Text(
                tr("需要相机权限。点下方的「允许相机」。", "カメラの権限が必要です。下の「カメラを許可」をタップしてください。", "Camera permission is needed. Tap \"Allow camera\" below."),
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                fontSize = CoachType.Hint,
                lineHeight = 24.sp,
            )
            return@Box
        }
        val context = LocalContext.current
        val owner = LocalLifecycleOwner.current
        val previewView = remember {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE // TextureView: Compose overlays draw on top
            }
        }
        DisposableEffect(owner) {
            val cam = PhoneCamera(context, owner, previewView, vm::onFrame, vm::onCameraInfo, vm::onCameraError)
            previewView.setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_UP) vm.focusAt(e.x, e.y)
                true
            }
            cam.start()
            vm.attach(cam)
            onDispose {
                vm.detach(cam)
                cam.stop()
            }
        }
        AndroidView({ previewView }, Modifier.fillMaxSize())
        CoachOverlay(s.frameW, s.frameH, s.analysis, s.composition, s.crop?.rect, Modifier.fillMaxSize(), guide = s.guide)
        s.framingReference?.let { reference ->
            FramingReferenceCard(reference, vm::dismissFramingReference, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
    }
}

/** Bottom action bar: fixed positions, like the Sony app */
@Composable
private fun ActionBar(s: PhoneUiState, vm: PhoneViewModel, requestPermission: () -> Unit) {
    val ready = s.hasPermission && s.cameraReady
    val pad = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
    val tall = Modifier.heightIn(min = 52.dp)
    // One row (shoot, AI framing, auto-shutter switch) so the Guide panel above keeps as much height as possible
    Row(
        Modifier.fillMaxWidth().background(CoachColors.StripBg).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!s.hasPermission) {
            Button(onClick = requestPermission, contentPadding = pad, modifier = Modifier.weight(1f).then(tall)) {
                Text(tr("允许相机", "カメラを許可", "Allow camera"), maxLines = 1)
            }
        } else {
            Button(onClick = { vm.takePicture() }, enabled = ready && !s.shooting, contentPadding = pad, modifier = Modifier.weight(1f).then(tall)) {
                Text(tr("拍照", "撮影", "Shoot"), maxLines = 1)
            }
        }
        OutlinedButton(
            colors = secondaryButtonColors(),
            onClick = { vm.findBestCrop() }, enabled = ready && s.aiAvailable && !s.cropBusy,
            contentPadding = pad, modifier = Modifier.weight(1f).then(tall),
        ) { Text(if (s.cropBusy) tr("分析中…", "分析中…", "Working…") else tr("AI构图", "AI 構図", "AI framing"), maxLines = 1) }
        AutoShutterToggle(s.autoShutter, ready, s.autoThreshold, s.aiAvailable) { vm.setAutoShutter(it) }
    }
}

@Composable
private fun PanelContent(s: PhoneUiState, vm: PhoneViewModel, tab: Int, modifier: Modifier) {
    key(tab) {
        Column(
            modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (tab == 0) GuideTab(s, vm) else SettingsTab(s, vm)
        }
    }
}

@Composable
private fun GuideTab(s: PhoneUiState, vm: PhoneViewModel) {
    if (s.message.isNotEmpty()) Note(s.message)
    s.report?.let { rep -> ChecklistSection(rep) { vm.ignoreRule(it.id, it.name) } }

    val i = s.info
    Section(tr("相机当前", "カメラの現在値", "Camera now")) {
        Text(
            "${s.camera.shutter ?: "-"}   ISO ${s.camera.iso ?: "-"}   " +
                "${s.camera.evValue?.let { ParameterAdvisor.formatEv(it) } ?: ""}   " +
                String.format(Locale.ROOT, "%.1fx", i.zoomRatio) +
                (i.focalEq35?.let { tr("（约 ${it.roundToInt()}mm 等效）", "（35mm 換算 約 ${it.roundToInt()}mm）", " (≈${it.roundToInt()}mm equiv.)") } ?: ""),
            fontSize = CoachType.Hint,
        )
        Note(tr("点一下取景画面：对焦并按那里测光", "ライブビューをタップ：そこにピントと露出を合わせます", "Tap the viewfinder to focus and meter there"))
    }

    AestheticSection(s.aesthetic, s.aiStatus, s.aiAvailable, s.segmentationOk, s.perf, s.configStatus)
}

@Composable
private fun SettingsTab(s: PhoneUiState, vm: PhoneViewModel) {
    val i = s.info
    Section(tr("变焦", "ズーム", "Zoom")) {
        ChoiceRow {
            listOf(i.minZoom, 1f, 2f, 3f, 5f).filter { it in i.minZoom..i.maxZoom }.distinct().forEach { z ->
                Choice(String.format(Locale.ROOT, if (z < 1f) "%.1fx" else "%.0fx", z), abs(i.zoomRatio - z) < 0.05f) { vm.setZoom(z) }
            }
        }
    }

    Section(tr("曝光补偿", "露出補正", "Exposure compensation")) {
        val ev = s.camera.evValue ?: 0.0
        Text(ParameterAdvisor.formatEv(ev), fontSize = CoachType.Hint)
        if (i.evMax > i.evMin) {
            Slider(
                value = i.evIndex.toFloat(),
                onValueChange = { vm.setExposureIndex(it.roundToInt()) },
                valueRange = i.evMin.toFloat()..i.evMax.toFloat(),
                steps = (i.evMax - i.evMin - 1).coerceAtLeast(0),
            )
        } else {
            Note(tr("这台手机不支持曝光补偿", "この端末は露出補正に対応していません", "This phone does not support exposure compensation"))
        }
    }

    AutoShutterSection(s.autoThreshold) { vm.setThreshold(it) }
    IgnoredRulesSection(s.ignoredRules, s.ruleNames) { vm.restoreRule(it) }
}
