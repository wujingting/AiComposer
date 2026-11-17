package com.example.aicomposer

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicomposer.ai.AestheticModel
import com.example.aicomposer.ai.AestheticResult
import com.example.aicomposer.ai.CropSuggestion
import com.example.aicomposer.analysis.AnalysisResult
import com.example.aicomposer.analysis.CompositionAdvice
import com.example.aicomposer.analysis.CropPlan
import com.example.aicomposer.analysis.ParameterAdvice
import com.example.aicomposer.camera.CameraState
import com.example.aicomposer.coach.Cfg
import com.example.aicomposer.coach.CoachReport
import com.example.aicomposer.i18n.tr
import com.example.aicomposer.net.CameraNetwork
import com.example.aicomposer.pipeline.AutoShutterGate
import com.example.aicomposer.pipeline.CoachPipeline
import com.example.aicomposer.pipeline.CropMode
import com.example.aicomposer.pipeline.GuideState
import com.example.aicomposer.pipeline.PerfMonitor
import com.example.aicomposer.pipeline.Settings
import com.example.aicomposer.pipeline.SettingsStore
import com.example.aicomposer.pipeline.aestheticStatus
import com.example.aicomposer.pipeline.combinedScore
import com.example.aicomposer.pipeline.effectivePlan
import com.example.aicomposer.sony.LiveviewStream
import com.example.aicomposer.sony.SonyCameraApi
import com.example.aicomposer.sony.SsdpDiscovery
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
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** The a6000 kit lens used here is a 35-50mm zoom; below the long end, suggest zooming before cropping. */
private const val ZOOM_LONG_END_MM = 50

data class UiState(
    val conn: ConnState = ConnState.DISCONNECTED,
    val message: String = "",
    /** Size of the liveview frames (0 = no frame yet). The frame itself is in [MainViewModel.frame]. */
    val frameW: Int = 0,
    val frameH: Int = 0,
    val camera: CameraState = CameraState(),
    val analysis: AnalysisResult? = null,
    val composition: CompositionAdvice? = null,
    val report: CoachReport? = null,
    val zoomHint: String? = null,
    val segmentationOk: Boolean = true,
    val params: ParameterAdvice? = null,
    val focalLength: Int = 35,
    val cropMode: CropMode = CropMode.AUTO,
    val cropPlan: CropPlan = CropPlan(),      // manual-mode keep ratio + aspect (the aspect is also used in auto mode)
    val autoKeep: Float = 1f,
    val autoReason: String = "",
    val guide: GuideState = GuideState(),
    val autoShutter: Boolean = false,
    val autoThreshold: Int = 80,
    val ignoredRules: Set<String> = emptySet(),
    val ruleNames: Map<String, String> = emptyMap(),
    val perf: PerfMonitor.Stats = PerfMonitor.Stats(),
    val configStatus: String = "",
    // AI aesthetics
    val aiStatus: String = tr("正在加载美学模型…", "美的評価モデルを読み込み中…", "Loading the aesthetic model…"),
    val aiAvailable: Boolean = false,
    val aesthetic: AestheticResult? = null,
    val crop: CropSuggestion? = null,
    val cropBusy: Boolean = false,
) {
    /** The crop plan actually in use. */
    val effectivePlan: CropPlan get() = effectivePlan(cropMode, cropPlan, autoKeep)

    /** Combined score for auto shutter. */
    val combinedScore: Int? get() = combinedScore(composition, aesthetic)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /**
     * The liveview frame (15–30 per second), kept out of [UiState] so a new frame only recomposes the image,
     * not the whole panel.
     */
    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    private val cameraNetwork = CameraNetwork(app)
    private val pipeline = CoachPipeline(app)
    private val shutterGate = AutoShutterGate()
    private var api: SonyCameraApi? = null
    private var client: OkHttpClient? = null
    private val jobs = mutableListOf<Job>()

    @Volatile private var latest: Bitmap? = null
    @Volatile private var aesthetic: AestheticModel? = null

    private val settingsStore = SettingsStore(app)

    init {
        // Load rule thresholds (coach_config.json on the phone)
        val cfgStatus = Cfg.load(app)
        _ui.update { it.copy(configStatus = cfgStatus) }
        settingsStore.load().let { st ->
            _ui.update {
                it.copy(
                    focalLength = st.focalLength,
                    cropMode = st.cropMode,
                    cropPlan = st.cropPlan,
                    autoThreshold = st.autoThreshold,
                    ignoredRules = st.ignoredRules,
                    ruleNames = st.ruleNames,
                )
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val m = AestheticModel.load(getApplication())
            aesthetic = m
            _ui.update { it.copy(aiAvailable = m != null, aiStatus = aestheticStatus(m)) }
        }
    }

    fun connect() {
        if (_ui.value.conn == ConnState.CONNECTING || _ui.value.conn == ConnState.CONNECTED) return
        _ui.update { it.copy(conn = ConnState.CONNECTING, message = tr("正在寻找相机 Wi-Fi…", "カメラの Wi-Fi を探しています…", "Looking for the camera's Wi-Fi…")) }
        viewModelScope.launch {
            try {
                val network = cameraNetwork.acquireWifi()
                val c = OkHttpClient.Builder()
                    .apply { network?.let { socketFactory(it.socketFactory) } }
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                client = c
                msg(tr("正在发现相机…", "カメラを検索中…", "Discovering the camera…"))
                val endpoint = SsdpDiscovery.discover(getApplication(), network, c)
                    ?: SsdpDiscovery.FALLBACK_ENDPOINT
                val a = SonyCameraApi(endpoint, c)
                api = a

                var apis = a.availableApis()
                if ("startRecMode" in apis) {
                    msg(tr("切换到拍摄模式…", "撮影モードに切り替え中…", "Switching to shooting mode…"))
                    a.startRecMode()
                    repeat(10) {
                        if ("startLiveview" in apis) return@repeat
                        delay(500)
                        apis = a.availableApis()
                    }
                }
                _ui.update { it.copy(camera = it.camera.copy(availableApis = apis)) }

                pipeline.reset()
                _ui.update { it.copy(conn = ConnState.CONNECTED, message = tr("已连接：$endpoint", "接続しました：$endpoint", "Connected: $endpoint")) }
                startLoops(a, c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cameraNetwork.release()
                _ui.update {
                    it.copy(
                        conn = ConnState.ERROR,
                        message = tr("连接失败：${e.message}\n请确认相机已打开「智能遥控内嵌」，手机已连上 DIRECT-xxxx", "接続に失敗しました：${e.message}\nカメラで「スマートリモコン内蔵」が起動していて、スマホが DIRECT-xxxx に接続されているか確認してください", "Connection failed: ${e.message}\nMake sure \"Smart Remote Embedded\" is running on the camera and the phone is connected to DIRECT-xxxx"),
                    )
                }
            }
        }
    }

    private fun startLoops(a: SonyCameraApi, c: OkHttpClient) {
        // 1. Liveview (reconnects automatically when it drops)
        jobs += viewModelScope.launch {
            val stream = LiveviewStream(c)
            while (isActive) {
                try {
                    val url = a.startLiveview()
                    stream.frames(url).collect { bmp ->
                        latest = bmp
                        _frame.value = bmp.asImageBitmap()
                        // UiState only changes when the frame size does (practically once per connection)
                        if (bmp.width != _ui.value.frameW || bmp.height != _ui.value.frameH) {
                            _ui.update { it.copy(frameW = bmp.width, frameH = bmp.height) }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    msg(tr("取景中断，重连中…（${e.message}）", "ライブビューが途切れました。再接続中…（${e.message}）", "Liveview dropped, reconnecting… (${e.message})"))
                }
                delay(1000)
            }
        }
        // 2. Analysis (about 4 times per second) — shared pipeline in core
        jobs += viewModelScope.launch(Dispatchers.Default) {
            var last: Bitmap? = null
            while (isActive) {
                val bmp = latest
                if (bmp != null && bmp !== last) {
                    last = bmp
                    val s = _ui.value
                    val out = pipeline.process(
                        CoachPipeline.Input(
                            frame = bmp, camera = s.camera, focalMm = s.focalLength,
                            cropMode = s.cropMode, cropPlan = s.cropPlan, autoKeep = s.autoKeep,
                            aesthetic = s.aesthetic, ignoredRules = s.ignoredRules,
                            zoomLongEndMm = ZOOM_LONG_END_MM,
                        )
                    )
                    if (out != null) {
                        _ui.update {
                            it.copy(
                                analysis = out.analysis, composition = out.composition, params = out.params,
                                report = out.report, zoomHint = out.zoomHint,
                                autoKeep = out.autoKeep, autoReason = out.autoReason ?: it.autoReason,
                                segmentationOk = out.segmentationOk, perf = out.perf, guide = out.guide,
                            )
                        }
                        if (s.autoShutter) {
                            val score = _ui.value.combinedScore ?: out.composition.score
                            if (shutterGate.update(score, s.autoThreshold, out.rawItems)) takePicture(auto = true)
                        } else {
                            shutterGate.reset()
                        }
                    }
                }
                delay(_ui.value.perf.intervalMs) // throttles automatically when too slow / too hot
            }
        }
        // 3. AI aesthetic score (about once per second)
        jobs += viewModelScope.launch(Dispatchers.Default) {
            var last: Bitmap? = null
            var tick = 0
            while (isActive) {
                val m = aesthetic
                val bmp = latest
                val hot = _ui.value.perf.level >= 2 // pause aesthetics when the phone overheats
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
        // 4. Read camera settings
        jobs += viewModelScope.launch {
            while (isActive) {
                try {
                    val cam = a.getEvent(_ui.value.camera)
                    _ui.update { it.copy(camera = cam) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                delay(1500)
            }
        }
    }

    fun takePicture(auto: Boolean = false) {
        val a = api ?: return
        shutterGate.lastShotAt = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                a.takePicture()
                msg(if (auto) tr("自动快门：已拍摄", "自動シャッター：撮影しました", "Auto shutter: photo taken") else tr("已拍摄", "撮影しました", "Photo taken"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                msg(tr("拍摄失败：${e.message}", "撮影に失敗しました：${e.message}", "Capture failed: ${e.message}"))
            }
        }
    }

    fun applyAdvice() {
        val a = api ?: return
        val p = _ui.value.params ?: return
        viewModelScope.launch {
            val avail = _ui.value.camera.availableApis
            val ok = mutableListOf<String>()
            val fail = mutableListOf<String>()

            suspend fun trySet(name: String, apiName: String, block: suspend () -> Unit) {
                if (apiName !in avail) { fail += name; return }
                try {
                    block(); ok += name
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    fail += name
                }
            }

            p.fNumber?.let { v -> trySet(tr("光圈 f/$v", "絞り f/$v", "aperture f/$v"), "setFNumber") { a.setFNumber(v) } }
            p.shutter?.let { v -> trySet(tr("快门 $v", "シャッター $v", "shutter $v"), "setShutterSpeed") { a.setShutterSpeed(v) } }
            p.iso?.let { v -> trySet("ISO $v", "setIsoSpeedRate") { a.setIso(v) } }
            p.evIndex?.let { v -> trySet(tr("曝光补偿 ${p.evText}", "露出補正 ${p.evText}", "exposure compensation ${p.evText}"), "setExposureCompensation") { a.setExposureCompensation(v) } }

            val sb = StringBuilder()
            if (ok.isNotEmpty()) sb.append(tr("已设置：", "設定しました：", "Set: ")).append(ok.joinToString(tr("，", "、", ", ")))
            if (fail.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(tr("未能设置：", "設定できませんでした：", "Could not set: ")).append(fail.joinToString(tr("，", "、", ", ")))
                sb.append(tr("\n（a6000 的拍摄模式由转盘决定：改光圈要 A/M 档，改快门要 S/M 档，建议用 ${p.mode}）", "\n（a6000 の撮影モードはモードダイヤルで決まります：絞りの変更は A/M、シャッターの変更は S/M が必要。おすすめは ${p.mode}）", "\n(On the a6000 the mode dial decides: aperture needs A/M, shutter needs S/M; recommended: ${p.mode})"))
            }
            msg(sb.toString())
        }
    }

    /** AI best framing: search 19 candidate crops in the frame for the highest aesthetic score. */
    fun findBestCrop() {
        val m = aesthetic ?: return
        val bmp = latest ?: return
        if (_ui.value.cropBusy) return
        _ui.update { it.copy(cropBusy = true) }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val c = m.searchBestCrop(bmp, _ui.value.effectivePlan)
                _ui.update { it.copy(crop = c, cropBusy = false) }
                delay(10_000) // clear the purple frame after 10 seconds
                _ui.update { if (it.crop === c) it.copy(crop = null) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(cropBusy = false) }
                msg(tr("AI 构图分析失败：${e.message}", "AI 構図分析に失敗しました：${e.message}", "AI framing failed: ${e.message}"))
            }
        }
    }

    fun setAutoShutter(on: Boolean) = _ui.update { it.copy(autoShutter = on) }
    fun setThreshold(v: Int) = updateAndSave { it.copy(autoThreshold = v) }
    fun setFocal(v: Int) = updateAndSave { it.copy(focalLength = v) }
    fun setCropMode(m: CropMode) = updateAndSave { it.copy(cropMode = m, crop = null) }
    fun setCropKeep(k: Float) = updateAndSave { it.copy(cropPlan = it.cropPlan.copy(keep = k), crop = null) }
    fun setCropAspect(a: Float) = updateAndSave { it.copy(cropPlan = it.cropPlan.copy(aspect = a), crop = null) }

    /** Mark "this is intentional": ignore this rule in the current scene. */
    fun ignoreRule(id: String, name: String, allScenes: Boolean = false) = updateAndSave {
        val scene = if (allScenes) "*" else (it.analysis?.scene?.name ?: "GENERAL")
        it.copy(ignoredRules = it.ignoredRules + "$scene:$id", ruleNames = it.ruleNames + (id to name))
    }

    fun restoreRule(key: String) = updateAndSave { it.copy(ignoredRules = it.ignoredRules - key) }

    private fun updateAndSave(f: (UiState) -> UiState) {
        _ui.update(f)
        val s = _ui.value
        settingsStore.save(
            Settings(
                focalLength = s.focalLength,
                cropMode = s.cropMode,
                cropKeep = s.cropPlan.keep,
                cropAspect = s.cropPlan.aspect,
                autoThreshold = s.autoThreshold,
                ignoredRules = s.ignoredRules,
                ruleNames = s.ruleNames,
            )
        )
    }

    fun disconnect() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        val a = api
        api = null
        latest = null
        _frame.value = null
        if (a != null) viewModelScope.launch { runCatching { a.stopLiveview() } }
        cameraNetwork.release()
        _ui.update {
            UiState(
                focalLength = it.focalLength,
                cropPlan = it.cropPlan,
                cropMode = it.cropMode,
                autoThreshold = it.autoThreshold,
                ignoredRules = it.ignoredRules,
                ruleNames = it.ruleNames,
                aiAvailable = it.aiAvailable,
                aiStatus = it.aiStatus,
                configStatus = it.configStatus,
                message = tr("已断开", "切断しました", "Disconnected"),
            )
        }
    }

    private fun msg(m: String) = _ui.update { it.copy(message = m) }

    override fun onCleared() {
        jobs.forEach { it.cancel() }
        cameraNetwork.release()
        pipeline.close()
        aesthetic?.close()
        super.onCleared()
    }
}
