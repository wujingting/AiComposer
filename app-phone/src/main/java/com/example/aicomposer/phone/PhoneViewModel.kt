package com.example.aicomposer.phone

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicomposer.ai.AestheticModel
import com.example.aicomposer.ai.AestheticResult
import com.example.aicomposer.ai.CropSuggestion
import com.example.aicomposer.ai.FramingReference
import com.example.aicomposer.analysis.AnalysisResult
import com.example.aicomposer.analysis.CompositionAdvice
import com.example.aicomposer.analysis.CropPlan
import com.example.aicomposer.camera.CameraKind
import com.example.aicomposer.camera.CameraState
import com.example.aicomposer.coach.Cfg
import com.example.aicomposer.coach.CoachReport
import com.example.aicomposer.i18n.tr
import com.example.aicomposer.pipeline.AutoShutterGate
import com.example.aicomposer.pipeline.CoachPipeline
import com.example.aicomposer.pipeline.CropMode
import com.example.aicomposer.pipeline.GuideState
import com.example.aicomposer.pipeline.PerfMonitor
import com.example.aicomposer.pipeline.Settings
import com.example.aicomposer.pipeline.SettingsStore
import com.example.aicomposer.pipeline.aestheticStatus
import com.example.aicomposer.pipeline.combinedScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class PhoneUiState(
    val hasPermission: Boolean = false,
    val cameraReady: Boolean = false,
    val message: String = "",
    /** Size of the last analyzed (upright) frame; the overlay maps normalized coordinates with it. */
    val frameW: Int = 3,
    val frameH: Int = 4,
    val camera: CameraState = CameraState(kind = CameraKind.PHONE),
    val info: PhoneCameraInfo = PhoneCameraInfo(),
    val analysis: AnalysisResult? = null,
    val composition: CompositionAdvice? = null,
    val report: CoachReport? = null,
    val guide: GuideState = GuideState(),
    val segmentationOk: Boolean = true,
    val autoShutter: Boolean = false,
    val autoThreshold: Int = 80,
    val ignoredRules: Set<String> = emptySet(),
    val ruleNames: Map<String, String> = emptyMap(),
    val perf: PerfMonitor.Stats = PerfMonitor.Stats(),
    val configStatus: String = "",
    val aiStatus: String = tr("正在加载美学模型…", "美的評価モデルを読み込み中…", "Loading the aesthetic model…"),
    val aiAvailable: Boolean = false,
    val aesthetic: AestheticResult? = null,
    val crop: CropSuggestion? = null,
    val framingReference: FramingReference? = null,
    val cropBusy: Boolean = false,
    val shooting: Boolean = false,
) {
    val combinedScore: Int? get() = combinedScore(composition, aesthetic)

    /**
     * Focal length in "APS-C millimeters", the unit the rules use (they were written for the a6000).
     * A phone main camera (~26mm full-frame equivalent) is about 17mm here; 2x zoom about 35mm.
     */
    val focalApsc: Int get() = ((info.focalEq35 ?: 26f) / 1.5f).roundToInt().coerceAtLeast(8)
}

class PhoneViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(PhoneUiState())
    val ui: StateFlow<PhoneUiState> = _ui.asStateFlow()

    private val pipeline = CoachPipeline(app)
    private val shutterGate = AutoShutterGate()
    private val settingsStore = SettingsStore(app)
    private var cropJob: Job? = null
    @Volatile private var latest: Bitmap? = null
    @Volatile private var aesthetic: AestheticModel? = null
    @Volatile private var camera: PhoneCamera? = null

    init {
        val cfgStatus = Cfg.load(app)
        settingsStore.load().let { st ->
            _ui.update {
                it.copy(
                    configStatus = cfgStatus, autoThreshold = st.autoThreshold,
                    ignoredRules = st.ignoredRules, ruleNames = st.ruleNames,
                )
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val m = AestheticModel.load(getApplication())
            aesthetic = m
            _ui.update { it.copy(aiAvailable = m != null, aiStatus = aestheticStatus(m)) }
        }
        startLoops()
    }

    // --- camera wiring (the camera itself lives in the UI, bound to the activity lifecycle) ---

    fun setPermission(granted: Boolean) = _ui.update {
        it.copy(
            hasPermission = granted,
            message = if (granted) "" else tr("需要相机权限才能取景", "撮影するにはカメラの権限が必要です", "Camera permission is needed to show the viewfinder"),
        )
    }

    fun attach(cam: PhoneCamera) {
        latest = null
        camera = cam
        pipeline.reset()
        _ui.update { it.copy(cameraReady = true) }
    }

    fun detach(cam: PhoneCamera) {
        if (camera !== cam) return
        camera = null
        latest = null
        cropJob?.cancel()
        // Keep the finished thumbnail while CameraX rebinds on rotation.
        _ui.update { it.copy(cameraReady = false, cropBusy = false, crop = null) }
    }

    /** Called on the camera thread for every analysis frame (already upright). */
    fun onFrame(bmp: Bitmap) {
        latest = bmp
    }

    fun onCameraInfo(info: PhoneCameraInfo) = _ui.update {
        it.copy(
            info = info,
            camera = it.camera.copy(
                shutter = info.exposureNs?.let(::formatExposure),
                iso = info.iso?.toString(),
                evIndex = info.evIndex, evMin = info.evMin, evMax = info.evMax, evStepEv = info.evStep,
            ),
        )
    }

    fun onCameraError(msg: String) = _ui.update {
        it.copy(message = tr("相机启动失败：$msg", "カメラを起動できません：$msg", "Could not start the camera: $msg"))
    }

    // --- loops: analysis (~4/s) and AI aesthetics (~1/s), same pipeline as the Sony app ---

    private fun startLoops() {
        viewModelScope.launch(Dispatchers.Default) {
            var last: Bitmap? = null
            while (isActive) {
                val bmp = latest
                if (bmp != null && bmp !== last) {
                    last = bmp
                    val s = _ui.value
                    val out = pipeline.process(
                        CoachPipeline.Input(
                            frame = bmp, camera = s.camera, focalMm = s.focalApsc,
                            cropMode = CropMode.OFF, cropPlan = CropPlan(), autoKeep = 1f,
                            aesthetic = s.aesthetic, ignoredRules = s.ignoredRules,
                            zoomLongEndMm = null, adviseParameters = false,
                        )
                    )
                    if (out != null) {
                        _ui.update {
                            it.copy(
                                frameW = bmp.width, frameH = bmp.height,
                                analysis = out.analysis, composition = out.composition, report = out.report, guide = out.guide,
                                segmentationOk = out.segmentationOk, perf = out.perf,
                            )
                        }
                        if (s.autoShutter && camera != null) {
                            val score = _ui.value.combinedScore ?: out.composition.score
                            if (shutterGate.update(score, s.autoThreshold, out.rawItems)) takePicture(auto = true)
                        } else {
                            shutterGate.reset()
                        }
                    }
                }
                delay(_ui.value.perf.intervalMs)
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            var last: Bitmap? = null
            var tick = 0
            while (isActive) {
                val m = aesthetic
                val bmp = latest
                val hot = _ui.value.perf.level >= 2
                if (m != null && bmp != null && bmp !== last && !_ui.value.cropBusy && !hot) {
                    last = bmp
                    try {
                        val r = pipeline.aestheticStep(m, bmp, tick++)
                        _ui.update { it.copy(aesthetic = r) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                delay(1000)
            }
        }
    }

    // --- actions ---

    fun takePicture(auto: Boolean = false) {
        val cam = camera ?: return
        if (_ui.value.shooting) return
        shutterGate.lastShotAt = System.currentTimeMillis()
        _ui.update { it.copy(shooting = true) }
        viewModelScope.launch(Dispatchers.Main) {
            cam.takePicture { ok, where ->
                val m = when {
                    !ok -> tr("拍摄失败：$where", "撮影に失敗しました：$where", "Capture failed: $where")
                    auto -> tr("自动快门：已保存到 $where", "自動シャッター：$where に保存しました", "Auto shutter: saved to $where")
                    else -> tr("已保存到 $where", "$where に保存しました", "Saved to $where")
                }
                _ui.update { it.copy(shooting = false, message = m) }
            }
        }
    }

    fun findBestCrop() {
        val m = aesthetic ?: return
        val bmp = latest ?: return
        if (_ui.value.cropBusy) return
        cropJob?.cancel()
        _ui.update { it.copy(cropBusy = true, crop = null) }
        cropJob = viewModelScope.launch {
            try {
                val reference = withContext(Dispatchers.Default) { m.searchFramingReference(bmp) }
                _ui.update { it.copy(framingReference = reference, crop = reference.suggestion, cropBusy = false) }
                delay(10_000)
                _ui.update { it.copy(crop = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) return@launch
                _ui.update {
                    it.copy(cropBusy = false, message = tr("AI 构图分析失败：${e.message}", "AI 構図分析に失敗しました：${e.message}", "AI framing failed: ${e.message}"))
                }
            }
        }
    }

    fun dismissFramingReference() {
        cropJob?.cancel()
        _ui.update { it.copy(framingReference = null, crop = null, cropBusy = false) }
    }

    fun focusAt(x: Float, y: Float) = camera?.focusAt(x, y)
    fun setZoom(r: Float) = camera?.setZoomRatio(r)
    fun setExposureIndex(i: Int) = camera?.setExposureIndex(i)
    fun setAutoShutter(on: Boolean) = _ui.update { it.copy(autoShutter = on) }
    fun setThreshold(v: Int) = updateAndSave { it.copy(autoThreshold = v) }

    /** Mark "this is intentional": ignore this rule in the current scene. */
    fun ignoreRule(id: String, name: String) = updateAndSave {
        val scene = it.analysis?.scene?.name ?: "GENERAL"
        it.copy(ignoredRules = it.ignoredRules + "$scene:$id", ruleNames = it.ruleNames + (id to name))
    }

    fun restoreRule(key: String) = updateAndSave { it.copy(ignoredRules = it.ignoredRules - key) }

    private fun updateAndSave(f: (PhoneUiState) -> PhoneUiState) {
        _ui.update(f)
        val s = _ui.value
        settingsStore.save(
            Settings(cropMode = CropMode.OFF, autoThreshold = s.autoThreshold, ignoredRules = s.ignoredRules, ruleNames = s.ruleNames)
        )
    }

    override fun onCleared() {
        pipeline.close()
        aesthetic?.close()
        super.onCleared()
    }

    companion object {
        /** Exposure time in the Sony notation the rules parse: "1/120" or "0.5\"". */
        fun formatExposure(ns: Long): String {
            val t = ns / 1e9
            return if (t >= 0.3) String.format(java.util.Locale.ROOT, "%.1f\"", t) else "1/${(1 / t).roundToInt()}"
        }
    }
}
