package com.example.aicomposer.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.aicomposer.analysis.NRect
import com.example.aicomposer.analysis.SubjectKind
import com.example.aicomposer.coach.Cfg
import com.example.aicomposer.i18n.tr
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One detected object. [rect] is normalized to the image; [cls] is the COCO class index. */
data class Detection(val rect: NRect, val cls: Int, val score: Float) {
    val kind: SubjectKind get() = CocoClasses.kind(cls)
    /** Display name in the current UI language, e.g. "电车" / "電車" / "the train". */
    val label: String get() = CocoClasses.label(cls)
}

/**
 * General object detector: YOLOX (Megvii, Apache-2.0) trained on COCO's 80 classes (person, vehicles, animals,
 * food, furniture...), run with ONNX Runtime. Much stronger than ML Kit's object detector, which only knows
 * 5 coarse categories and misses e.g. trains, cars and animals.
 *
 * The model is not in Git: `python tools/get_detector_model.py` puts it in core/src/main/assets/.
 * Without it the analyzer falls back to ML Kit.
 */
class ObjectDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {
    private val inputName = session.inputNames.first()
    /** Square input size read from the model (640 for yolox_s/m, 416 for tiny/nano). */
    private val size: Int = (session.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo)
        ?.shape?.getOrNull(3)?.toInt()?.takeIf { it > 0 } ?: 640
    /** Runs share the read lock (ORT sessions allow concurrent runs); [close] takes the write lock. */
    private val runLock = ReentrantReadWriteLock()
    private var closed = false // guarded by runLock

    fun detect(bmp: Bitmap): List<Detection> {
        val r = min(size.toFloat() / bmp.width, size.toFloat() / bmp.height)
        val sw = max(1, (bmp.width * r).roundToInt())
        val sh = max(1, (bmp.height * r).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val px = IntArray(sw * sh)
        scaled.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (scaled !== bmp) scaled.recycle()
        val input = YoloxCodec.preprocess(px, sw, sh, size)
        val raw = runLock.read {
            check(!closed) { "object detector is closed" }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
                session.run(mapOf(inputName to t)).use { res ->
                    val fb = (res[0] as OnnxTensor).floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
        }
        return YoloxCodec.decode(
            raw, size, r, bmp.width, bmp.height,
            minScore = Cfg.f("detector.min_score"), iouThreshold = 0.45f,
        )
    }

    /** Waits for any run in progress (another thread) before freeing the native session; later runs throw. */
    override fun close() = runLock.write {
        if (!closed) {
            closed = true
            session.close()
        }
    }

    companion object {
        private const val TAG = "ObjectDetector"
        const val MODEL_ASSET = "detector.onnx"

        @Volatile var lastError: String? = null
            private set

        /** Loads the model from the assets; null when it is not installed or cannot be loaded. */
        fun load(context: Context): ObjectDetector? = try {
            lastError = null
            if (MODEL_ASSET !in context.assets.list("").orEmpty()) null
            else {
                val env = OrtEnvironment.getEnvironment()
                val file = modelFile(context)
                val session = try {
                    // XNNPACK runs the convolutions several times faster on phone CPUs
                    env.createSession(file.absolutePath, options(xnnpack = true))
                } catch (e: Throwable) {
                    Log.w(TAG, "XNNPACK unavailable, using the default CPU provider", e)
                    env.createSession(file.absolutePath, options(xnnpack = false))
                }
                ObjectDetector(env, session)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load the object detector", e)
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }

        private fun options(xnnpack: Boolean) = OrtSession.SessionOptions().apply {
            val threads = Cfg.d("ai.threads").toInt().coerceIn(1, 8)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (xnnpack) {
                addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                setIntraOpNumThreads(1) // XNNPACK has its own thread pool
            } else {
                setIntraOpNumThreads(threads)
            }
        }

        /** The asset copied to app-private storage (re-copied after an app update), like the CLIP model. */
        private fun modelFile(context: Context): File {
            val dir = File(context.noBackupFilesDir, "model").apply { mkdirs() }
            val file = File(dir, MODEL_ASSET)
            val stamp = File(dir, "$MODEL_ASSET.stamp")
            val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            if (!(file.exists() && stamp.exists() && stamp.readText() == version)) {
                val tmp = File(dir, "$MODEL_ASSET.tmp")
                context.assets.open(MODEL_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("cannot move model file into place")
                stamp.writeText(version)
            }
            return file
        }
    }
}

/** YOLOX pre/post-processing, free of Android so it can be unit-tested. */
object YoloxCodec {
    private val STRIDES = intArrayOf(8, 16, 32)
    private const val PAD = 114f

    /**
     * ARGB pixels of the image already scaled to fit [size] -> CHW float input:
     * BGR order, 0..255 (no normalization), placed top-left on a gray (114) square.
     */
    fun preprocess(px: IntArray, w: Int, h: Int, size: Int): FloatArray {
        val plane = size * size
        val out = FloatArray(3 * plane) { PAD }
        for (y in 0 until min(h, size)) for (x in 0 until min(w, size)) {
            val c = px[y * w + x]
            val i = y * size + x
            out[i] = (c and 0xFF).toFloat()                         // B
            out[plane + i] = ((c shr 8) and 0xFF).toFloat()         // G
            out[2 * plane + i] = ((c shr 16) and 0xFF).toFloat()    // R
        }
        return out
    }

    /**
     * Raw output [N × (5 + classes)] (cx, cy, w, h as grid offsets / log sizes, objectness, class scores)
     * -> boxes normalized to the original image, after per-class non-maximum suppression, best first.
     * @param ratio the scale used in [preprocess] (input pixels per original pixel)
     */
    fun decode(
        raw: FloatArray, size: Int, ratio: Float, imgW: Int, imgH: Int,
        minScore: Float, iouThreshold: Float, classes: Int = 80,
    ): List<Detection> {
        val stride = 5 + classes
        val rows = STRIDES.sumOf { (size / it) * (size / it) }
        require(raw.size >= rows * stride) { "YOLOX output has ${raw.size} values, expected ${rows * stride}" }
        val cands = mutableListOf<Detection>()
        var row = 0
        for (s in STRIDES) {
            val n = size / s
            for (gy in 0 until n) for (gx in 0 until n) {
                val o = row * stride
                row++
                val obj = raw[o + 4]
                if (obj < minScore) continue
                var best = 0; var bestP = raw[o + 5]
                for (k in 1 until classes) if (raw[o + 5 + k] > bestP) { bestP = raw[o + 5 + k]; best = k }
                val score = obj * bestP
                if (score < minScore) continue
                val cx = (raw[o] + gx) * s
                val cy = (raw[o + 1] + gy) * s
                val bw = exp(raw[o + 2]) * s
                val bh = exp(raw[o + 3]) * s
                val l = ((cx - bw / 2) / ratio / imgW).coerceIn(0f, 1f)
                val t = ((cy - bh / 2) / ratio / imgH).coerceIn(0f, 1f)
                val r = ((cx + bw / 2) / ratio / imgW).coerceIn(0f, 1f)
                val b = ((cy + bh / 2) / ratio / imgH).coerceIn(0f, 1f)
                if (r > l && b > t) cands += Detection(NRect(l, t, r, b), best, score)
            }
        }
        val kept = mutableListOf<Detection>()
        for (d in cands.sortedByDescending { it.score }) {
            if (kept.none { it.cls == d.cls && iou(it.rect, d.rect) > iouThreshold }) kept += d
        }
        return kept
    }

    fun iou(a: NRect, b: NRect): Float {
        val w = min(a.r, b.r) - max(a.l, b.l)
        val h = min(a.b, b.b) - max(a.t, b.t)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (a.area + b.area - inter)
    }
}

/** The 80 COCO classes: subject kind and display names. */
object CocoClasses {
    const val PERSON = 0

    private class C(val zh: String, val ja: String, val en: String, val kind: SubjectKind = SubjectKind.GENERIC)

    private val P = SubjectKind.PERSON
    private val F = SubjectKind.FOOD
    private val H = SubjectKind.HOME_GOOD
    private val L = SubjectKind.PLANT

    private val ALL = listOf(
        C("人物", "人物", "person", P), C("自行车", "自転車", "bicycle"), C("汽车", "車", "car"),
        C("摩托车", "バイク", "motorcycle"), C("飞机", "飛行機", "airplane"), C("巴士", "バス", "bus"),
        C("电车", "電車", "train"), C("卡车", "トラック", "truck"), C("船", "船", "boat"),
        C("红绿灯", "信号機", "traffic light"), C("消防栓", "消火栓", "fire hydrant"), C("停车标志", "一時停止標識", "stop sign"),
        C("停车计时器", "パーキングメーター", "parking meter"), C("长椅", "ベンチ", "bench"), C("鸟", "鳥", "bird"),
        C("猫", "猫", "cat"), C("狗", "犬", "dog"), C("马", "馬", "horse"),
        C("羊", "羊", "sheep"), C("牛", "牛", "cow"), C("大象", "ゾウ", "elephant"),
        C("熊", "クマ", "bear"), C("斑马", "シマウマ", "zebra"), C("长颈鹿", "キリン", "giraffe"),
        C("背包", "リュック", "backpack", H), C("伞", "傘", "umbrella", H), C("手提包", "ハンドバッグ", "handbag", SubjectKind.CLOTHING),
        C("领带", "ネクタイ", "tie", SubjectKind.CLOTHING), C("行李箱", "スーツケース", "suitcase", H), C("飞盘", "フリスビー", "frisbee"),
        C("滑雪板", "スキー板", "skis"), C("单板滑雪板", "スノーボード", "snowboard"), C("球", "ボール", "ball"),
        C("风筝", "凧", "kite"), C("球棒", "バット", "baseball bat"), C("棒球手套", "グローブ", "baseball glove"),
        C("滑板", "スケートボード", "skateboard"), C("冲浪板", "サーフボード", "surfboard"), C("网球拍", "テニスラケット", "tennis racket"),
        C("瓶子", "ボトル", "bottle", H), C("酒杯", "ワイングラス", "wine glass", H), C("杯子", "カップ", "cup", H),
        C("叉子", "フォーク", "fork", H), C("刀", "ナイフ", "knife", H), C("勺子", "スプーン", "spoon", H),
        C("碗", "ボウル", "bowl", H), C("香蕉", "バナナ", "banana", F), C("苹果", "リンゴ", "apple", F),
        C("三明治", "サンドイッチ", "sandwich", F), C("橙子", "オレンジ", "orange", F), C("西兰花", "ブロッコリー", "broccoli", F),
        C("胡萝卜", "ニンジン", "carrot", F), C("热狗", "ホットドッグ", "hot dog", F), C("披萨", "ピザ", "pizza", F),
        C("甜甜圈", "ドーナツ", "donut", F), C("蛋糕", "ケーキ", "cake", F), C("椅子", "椅子", "chair", H),
        C("沙发", "ソファ", "couch", H), C("盆栽", "鉢植え", "potted plant", L), C("床", "ベッド", "bed", H),
        C("餐桌", "テーブル", "dining table", H), C("马桶", "トイレ", "toilet", H), C("电视", "テレビ", "tv", H),
        C("笔记本电脑", "ノートPC", "laptop", H), C("鼠标", "マウス", "mouse", H), C("遥控器", "リモコン", "remote", H),
        C("键盘", "キーボード", "keyboard", H), C("手机", "スマホ", "cell phone", H), C("微波炉", "電子レンジ", "microwave", H),
        C("烤箱", "オーブン", "oven", H), C("烤面包机", "トースター", "toaster", H), C("水槽", "シンク", "sink", H),
        C("冰箱", "冷蔵庫", "refrigerator", H), C("书", "本", "book", H), C("钟", "時計", "clock", H),
        C("花瓶", "花瓶", "vase", H), C("剪刀", "はさみ", "scissors", H), C("泰迪熊", "ぬいぐるみ", "teddy bear", H),
        C("吹风机", "ドライヤー", "hair drier", H), C("牙刷", "歯ブラシ", "toothbrush", H),
    )

    val count: Int get() = ALL.size

    fun kind(cls: Int): SubjectKind = ALL.getOrNull(cls)?.kind ?: SubjectKind.GENERIC

    fun label(cls: Int): String {
        val c = ALL.getOrNull(cls) ?: return tr("主体", "被写体", "the subject")
        return tr(c.zh, c.ja, "the ${c.en}")
    }
}
