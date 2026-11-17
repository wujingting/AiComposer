package com.example.aicomposer.score

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
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
import com.example.aicomposer.camera.CameraKind
import com.example.aicomposer.camera.CameraState
import com.example.aicomposer.coach.Cfg
import com.example.aicomposer.coach.CoachEngine
import com.example.aicomposer.coach.CoachReport
import com.example.aicomposer.i18n.tr
import com.example.aicomposer.pipeline.CoachPipeline
import com.example.aicomposer.pipeline.CropMode
import com.example.aicomposer.pipeline.PhotoRanking
import com.example.aicomposer.pipeline.aestheticStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/** One photo in the list. */
data class ScoredPhoto(
    val id: Int,
    val uri: Uri,
    val thumb: ImageBitmap? = null,
    val aesthetic: AestheticResult? = null,
    val exif: PhotoExif = PhotoExif(),
    val error: String? = null,
    val picked: Boolean = false,
    val duplicateOf: Int? = null,
) {
    val done: Boolean get() = aesthetic != null || error != null
}

/** The photo opened in the detail view, with the full coach analysis. */
data class Detail(
    val id: Int,
    val image: ImageBitmap? = null,
    val analysis: AnalysisResult? = null,
    val composition: CompositionAdvice? = null,
    val report: CoachReport? = null,
    val analyzing: Boolean = true,
    val crop: CropSuggestion? = null,
    val cropBusy: Boolean = false,
    val error: String? = null,
)

enum class SortOrder { SCORE, ADDED }

data class ScoreUiState(
    val aiStatus: String = tr("正在加载美学模型…", "美的評価モデルを読み込み中…", "Loading the aesthetic model…"),
    val aiAvailable: Boolean = false,
    val modelReady: Boolean = false,
    val photos: List<ScoredPhoto> = emptyList(),
    val sort: SortOrder = SortOrder.SCORE,
    val detail: Detail? = null,
) {
    val pending: Int get() = photos.count { !it.done }
    val sorted: List<ScoredPhoto>
        get() = when (sort) {
            SortOrder.ADDED -> photos
            SortOrder.SCORE -> photos.sortedWith(compareByDescending<ScoredPhoto> { it.aesthetic?.score ?: Float.NEGATIVE_INFINITY }.thenBy { it.id })
        }
}

class ScoreViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(ScoreUiState())
    val ui: StateFlow<ScoreUiState> = _ui.asStateFlow()

    @Volatile private var model: AestheticModel? = null
    private val pipeline = CoachPipeline(app)
    private val pipelineLock = Mutex()
    private val queue = Channel<Int>(Channel.UNLIMITED)
    private var nextId = 1
    private val seen = mutableSetOf<Uri>()
    private var detailJob: Job? = null

    init {
        Cfg.load(app)
        viewModelScope.launch(Dispatchers.IO) {
            val m = AestheticModel.load(getApplication())
            model = m
            _ui.update { it.copy(aiAvailable = m != null, modelReady = true, aiStatus = aestheticStatus(m)) }
            // Score the photos one at a time, in the order they were added
            for (id in queue) score(id)
        }
    }

    /** Add photos (from the picker or a Share intent); the same photo is not added twice. */
    fun add(uris: List<Uri>) {
        val fresh = uris.filter { seen.add(it) }
        if (fresh.isEmpty()) return
        val added = fresh.map { ScoredPhoto(nextId++, it) }
        _ui.update { it.copy(photos = it.photos + added) }
        added.forEach { queue.trySend(it.id) }
    }

    fun clear() {
        seen.clear()
        _ui.update { it.copy(photos = emptyList(), detail = null) }
    }

    fun setSort(s: SortOrder) = _ui.update { it.copy(sort = s) }

    private suspend fun score(id: Int) {
        val photo = _ui.value.photos.firstOrNull { it.id == id } ?: return // cleared meanwhile
        val ctx = getApplication<Application>()
        val updated = try {
            val bmp = PhotoLoader.load(ctx, photo.uri, 1024)
            val m = model
            val aes = m?.analyze(bmp)
            val thumb = Bitmap.createScaledBitmap(bmp, 320, (320f * bmp.height / bmp.width).roundToInt().coerceAtLeast(1), true)
            if (thumb !== bmp) bmp.recycle()
            photo.copy(
                thumb = thumb.asImageBitmap(), aesthetic = aes, exif = PhotoLoader.exif(ctx, photo.uri),
                error = if (m == null) tr("没有美学模型，无法打分", "美的評価モデルがないため採点できません", "No aesthetic model, cannot score") else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            photo.copy(error = tr("无法读取：", "読み込めません：", "Cannot read: ") + (e.message ?: e.javaClass.simpleName))
        }
        _ui.update { s -> s.copy(photos = rerank(s.photos.map { if (it.id == id) updated else it })) }
    }

    /** Recompute "best" picks and near-duplicates over every scored photo. */
    private fun rerank(photos: List<ScoredPhoto>): List<ScoredPhoto> {
        val r = PhotoRanking.rank(photos.mapNotNull { p -> p.aesthetic?.let { PhotoRanking.Item(p.id, it.score, it.embedding) } })
        val picked = r.picks.toSet()
        return photos.map { it.copy(picked = it.id in picked, duplicateOf = r.duplicateOf[it.id]) }
    }

    // --- detail view ---

    fun open(id: Int) {
        val photo = _ui.value.photos.firstOrNull { it.id == id } ?: return
        detailJob?.cancel()
        _ui.update { it.copy(detail = Detail(id)) }
        detailJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val ctx = getApplication<Application>()
                val bmp = PhotoLoader.load(ctx, photo.uri, 1280)
                _ui.update { s -> if (s.detail?.id == id) s.copy(detail = s.detail.copy(image = bmp.asImageBitmap())) else s }
                val exif = photo.exif
                val camera = CameraState(kind = CameraKind.PHOTO, shutter = exif.shutterText, iso = exif.iso?.toString())
                val focal = ((exif.focalEq35 ?: 26) / 1.5f).roundToInt().coerceAtLeast(8) // rules use APS-C millimeters
                // The ML Kit models run on a smaller copy, like the liveview; the shared analyzer is used one photo at a time
                val small = Bitmap.createScaledBitmap(bmp, 640, (640f * bmp.height / bmp.width).roundToInt().coerceAtLeast(1), true)
                val out = pipelineLock.withLock {
                    pipeline.reset()
                    pipeline.process(
                        CoachPipeline.Input(
                            frame = small, camera = camera, focalMm = focal,
                            cropMode = CropMode.OFF, cropPlan = CropPlan(), autoKeep = 1f,
                            aesthetic = photo.aesthetic, ignoredRules = emptySet(),
                            adviseParameters = false, still = true,
                        )
                    )
                }
                // One photo, no temporal smoothing: report the raw results of this frame
                val report = out?.let { CoachEngine.report(it.rawItems) }
                _ui.update { s ->
                    if (s.detail?.id != id) s else s.copy(
                        detail = s.detail.copy(
                            analysis = out?.analysis, composition = out?.composition, report = report, analyzing = false,
                            error = if (out == null) tr("分析失败", "分析に失敗しました", "Analysis failed") else null,
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.update { s ->
                    if (s.detail?.id != id) s else s.copy(detail = s.detail.copy(analyzing = false, error = e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    fun close() {
        detailJob?.cancel()
        _ui.update { it.copy(detail = null) }
    }

    /** AI framing on the opened photo: the crop with the best aesthetic score (purple frame), to crop when editing. */
    fun findBestCrop() {
        val d = _ui.value.detail ?: return
        val m = model ?: return
        val photo = _ui.value.photos.firstOrNull { it.id == d.id } ?: return
        if (d.cropBusy) return
        _ui.update { s -> s.copy(detail = s.detail?.copy(cropBusy = true)) }
        viewModelScope.launch(Dispatchers.Default) {
            val c = try {
                m.searchBestCrop(PhotoLoader.load(getApplication(), photo.uri, 1024))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            _ui.update { s -> if (s.detail?.id != d.id) s else s.copy(detail = s.detail.copy(crop = c, cropBusy = false)) }
        }
    }

    override fun onCleared() {
        queue.close()
        pipeline.close()
        model?.close()
        super.onCleared()
    }
}
