package com.example.aicomposer.ai

import com.example.aicomposer.i18n.tr
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.aicomposer.analysis.CropPlan
import com.example.aicomposer.analysis.NRect
import com.example.aicomposer.analysis.SceneType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Result of one aesthetic analysis. */
data class AestheticResult(
    val score: Float,          // LAION aesthetic score (roughly 1..10, liveview frames usually 4..7)
    val norm: Int,             // mapped to 0..100 so it can be combined with the composition score
    val compositionProb: Float, // probability CLIP considers it "well composed" 0..1
    val scene: SceneType?,
    val sceneConf: Float,
    val millis: Long,
    /** L2-normalized CLIP image embedding (512 values); dot product of two = similarity, used to find near-duplicates. */
    val embedding: FloatArray? = null,
)

/** Region CLIP considers most interesting. */
data class InterestResult(val rect: NRect, val margin: Float, val millis: Long)

/** Best composition found by the AI. */
data class CropSuggestion(
    val rect: NRect,
    val score: Float,
    val baseScore: Float,
    val hint: String,
    /** One-glance version of [hint] for the hint strip. */
    val short: String? = null,
)

/**
 * CLIP ViT-B/32 image encoder (ONNX) + LAION aesthetic linear head.
 * The same embedding is also used for zero-shot scene classification and "well / poorly composed".
 * The model files are produced by tools/export_clip_model.py and live in assets; load() returns null without them.
 */
class AestheticModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val meta: Meta,
) : AutoCloseable {

    private class Meta(
        val size: Int,
        val mean: FloatArray,
        val std: FloatArray,
        val logitScale: Float,
        val headW: FloatArray,
        val headB: Float,
        val scenes: Map<SceneType, FloatArray>,
        val compGood: FloatArray,
        val compBad: FloatArray,
        val interestPos: FloatArray?,
        val interestNeg: FloatArray?,
    )

    /** Whether point-of-interest detection is supported (older exported models lack it; rerun the export script). */
    val supportsInterest: Boolean get() = meta.interestPos != null && meta.interestNeg != null

    private val inputName = session.inputNames.first()

    /** Aesthetic analysis of the whole frame. */
    fun analyze(frame: Bitmap): AestheticResult {
        val t0 = System.currentTimeMillis()
        val e = embed(listOf(frame)).first()
        val s = score(e)

        val sceneLogits = meta.scenes.mapValues { (_, v) -> meta.logitScale * dot(v, e) }
        val probs = softmax(sceneLogits.values.toFloatArray())
        val keys = sceneLogits.keys.toList()
        val best = probs.indices.maxBy { probs[it] }

        val comp = softmax(floatArrayOf(meta.logitScale * dot(meta.compGood, e), meta.logitScale * dot(meta.compBad, e)))[0]

        return AestheticResult(
            score = s,
            norm = normalize(s),
            compositionProb = comp,
            scene = keys.getOrNull(best),
            sceneConf = probs[best],
            millis = System.currentTimeMillis() - t0,
            embedding = e,
        )
    }

    /**
     * Takes 19 candidate crops in the frame (full frame + 85% + 70%, each at 3×3 positions),
     * scores each one, and the best one is the "AI suggested composition".
     */
    fun searchBestCrop(frame: Bitmap, plan: CropPlan = CropPlan()): CropSuggestion {
        if (plan.active) return searchPlannedCrop(frame, plan)
        val cands = mutableListOf(NRect(0f, 0f, 1f, 1f))
        for (scale in listOf(0.85f, 0.7f)) {
            for (iy in 0..2) for (ix in 0..2) {
                val l = (1 - scale) * ix / 2f
                val t = (1 - scale) * iy / 2f
                cands += NRect(l, t, l + scale, t + scale)
            }
        }
        val scores = scoreCrops(frame, cands)
        val base = scores[0]
        val bestIdx = scores.indices.maxBy { scores[it] }
        val bestRect = cands[bestIdx]
        val gain = scores[bestIdx] - base
        val hint = if (bestIdx == 0 || gain < 0.15f) {
            tr("AI：当前构图已经不错（${fmt(base)} 分）", "AI：今の構図で十分良いです（${fmt(base)} 点）", "AI: the current composition is already good (${fmt(base)})")
        } else {
            val dirs = mutableListOf<String>()
            val dx = bestRect.cx - 0.5f
            val dy = bestRect.cy - 0.5f
            if (dx < -0.05f) dirs += tr("往左转", "左に振る", "pan left") else if (dx > 0.05f) dirs += tr("往右转", "右に振る", "pan right")
            if (dy < -0.05f) dirs += tr("往上抬", "上に向ける", "tilt up") else if (dy > 0.05f) dirs += tr("往下压", "下に向ける", "tilt down")
            val move = if (dirs.isEmpty()) "" else tr("相机" + dirs.joinToString("、") + "，", "カメラを" + dirs.joinToString("、") + "、", dirs.joinToString(" and ").replaceFirstChar { it.uppercaseChar() } + ", ")
            val zoom = if (bestRect.w < 0.8f) tr("靠近一点（或拉长焦）", "もう少し近づく（またはズームイン）", "get closer (or zoom in)") else tr("稍微靠近一点", "少しだけ近づく", "get a little closer")
            tr("AI：按紫色框构图更好：$move$zoom（${fmt(base)} → ${fmt(scores[bestIdx])} 分）", "AI：紫の枠の構図がおすすめ：$move$zoom（${fmt(base)} → ${fmt(scores[bestIdx])} 点）", "AI: the purple frame composes better: $move$zoom (${fmt(base)} → ${fmt(scores[bestIdx])})")
        }
        val short = if (bestIdx == 0 || gain < 0.15f) tr("AI：现在的构图就不错", "AI：今の構図で十分", "AI: framing is already good")
        else tr("AI：按紫框构图 ${fmt(base)}→${fmt(scores[bestIdx])}", "AI：紫の枠へ ${fmt(base)}→${fmt(scores[bestIdx])}", "AI: use the purple frame ${fmt(base)}→${fmt(scores[bestIdx])}")
        return CropSuggestion(bestRect, scores[bestIdx], base, hint, short)
    }

    /**
     * Planned-crop mode: the crop size is fixed (by the plan); search 5×5 positions for the best aesthetic score.
     * The result means "crop to the purple frame afterwards". If the best position touches the frame edge, the camera should turn that way a bit to leave slack.
     */
    private fun searchPlannedCrop(frame: Bitmap, plan: CropPlan): CropSuggestion {
        val (cw, ch) = plan.size(frame.width.toFloat() / frame.height)
        val cands = mutableListOf<NRect>()
        for (iy in 0..4) for (ix in 0..4) {
            val l = (1 - cw) * ix / 4f
            val t = (1 - ch) * iy / 4f
            cands += NRect(l, t, l + cw, t + ch)
        }
        val scores = scoreCrops(frame, cands)
        val centerIdx = 12 // center of the 5×5 grid
        val bestIdx = scores.indices.maxBy { scores[it] }
        val best = cands[bestIdx]
        val dirs = mutableListOf<String>()
        if (cw < 0.98f) {
            if (best.l < 0.01f) dirs += tr("往左转", "左に振る", "pan left") else if (best.r > 0.99f) dirs += tr("往右转", "右に振る", "pan right")
        }
        if (ch < 0.98f) {
            if (best.t < 0.01f) dirs += tr("往上抬", "上に向ける", "tilt up") else if (best.b > 0.99f) dirs += tr("往下压", "下に向ける", "tilt down")
        }
        val tail = if (dirs.isEmpty()) tr("拍完按紫框裁剪即可", "撮影後に紫の枠でトリミングすれば OK", "Just crop to the purple frame afterwards")
        else tr("紫框贴边了，相机${dirs.joinToString("、")}一点留出余量", "紫の枠が端に接しています。カメラを少し動かして余裕を残しましょう（${dirs.joinToString("、")}）", "The purple frame touches the edge: ${dirs.joinToString(" and ")} a little to leave some slack")
        val hint = tr("AI：最佳裁剪位置 ${fmt(scores[bestIdx])} 分（居中裁剪 ${fmt(scores[centerIdx])} 分）。$tail", "AI：最適なトリミング位置 ${fmt(scores[bestIdx])} 点（中央トリミング ${fmt(scores[centerIdx])} 点）。$tail", "AI: best crop position ${fmt(scores[bestIdx])} (centered crop ${fmt(scores[centerIdx])}). $tail")
        val short = if (dirs.isEmpty()) tr("AI：拍完按紫框裁剪", "AI：撮影後に紫の枠で切る", "AI: crop to the purple frame later")
        else tr("AI：紫框贴边，留点余量", "AI：紫の枠が端に接している", "AI: purple frame touches the edge")
        return CropSuggestion(best, scores[bestIdx], scores[centerIdx], hint, short)
    }

    /**
     * Point-of-interest detection: split the frame into 3×3 overlapping half-size regions and ask CLIP how much each "looks like something interesting"
     * (similarity to "interesting" prompts − similarity to "boring background" prompts).
     * Only when the best score exceeds the median by enough is there a clear point of interest.
     */
    fun findInterest(frame: Bitmap): InterestResult? {
        val pos = meta.interestPos ?: return null
        val neg = meta.interestNeg ?: return null
        val t0 = System.currentTimeMillis()
        val cands = (0..2).flatMap { iy -> (0..2).map { ix ->
            NRect(ix * 0.25f, iy * 0.25f, ix * 0.25f + 0.5f, iy * 0.25f + 0.5f)
        } }
        val scores = embedCrops(frame, cands).map { e -> dot(pos, e) - dot(neg, e) }
        val best = scores.indices.maxBy { scores[it] }
        val median = scores.sorted()[scores.size / 2]
        val margin = scores[best] - median
        if (margin < com.example.aicomposer.coach.Cfg.f("interest.min_margin")) return null
        return InterestResult(cands[best], margin, System.currentTimeMillis() - t0)
    }

    private fun scoreCrops(frame: Bitmap, cands: List<NRect>): List<Float> =
        embedCrops(frame, cands).map { score(it) }

    private fun embedCrops(frame: Bitmap, cands: List<NRect>): List<FloatArray> {
        val w = frame.width
        val h = frame.height
        val crops = cands.map { r ->
            val x = (r.l * w).toInt().coerceIn(0, w - 1)
            val y = (r.t * h).toInt().coerceIn(0, h - 1)
            val cw = (r.w * w).toInt().coerceIn(1, w - x)
            val ch = (r.h * h).toInt().coerceIn(1, h - y)
            Bitmap.createBitmap(frame, x, y, cw, ch)
        }
        return try {
            crops.chunked(9).flatMap { batch -> embed(batch) }
        } finally {
            crops.forEach { if (it !== frame) it.recycle() } // a full-frame crop may return the original bitmap, which must not be recycled
        }
    }

    /** Returns the L2-normalized image embedding. */
    private fun embed(images: List<Bitmap>): List<FloatArray> {
        val n = images.size
        val s = meta.size
        val buf = FloatBuffer.allocate(n * 3 * s * s)
        images.forEach { preprocess(it, buf) }
        buf.rewind()
        OnnxTensor.createTensor(env, buf, longArrayOf(n.toLong(), 3, s.toLong(), s.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<FloatArray>
                return out.map { l2(it) }
            }
        }
    }

    /** CLIP preprocessing: scale the short side to 224 -> center crop -> normalize, written as CHW. */
    private fun preprocess(bmp: Bitmap, buf: FloatBuffer) {
        val s = meta.size
        val scale = s.toFloat() / min(bmp.width, bmp.height)
        val sw = max(s, (bmp.width * scale).roundToInt())
        val sh = max(s, (bmp.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val px = IntArray(s * s)
        scaled.getPixels(px, 0, s, (sw - s) / 2, (sh - s) / 2, s, s)
        if (scaled !== bmp) scaled.recycle()

        val base = buf.position()
        val plane = s * s
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            buf.put(base + i, (r - meta.mean[0]) / meta.std[0])
            buf.put(base + plane + i, (g - meta.mean[1]) / meta.std[1])
            buf.put(base + 2 * plane + i, (b - meta.mean[2]) / meta.std[2])
        }
        buf.position(base + 3 * plane)
    }

    private fun score(e: FloatArray) = dot(meta.headW, e) + meta.headB

    override fun close() {
        session.close()
    }

    companion object {
        private const val TAG = "AestheticModel"
        const val MODEL_ASSET = "clip_image.onnx"
        const val META_ASSET = "clip_meta.json"

        /** Maps the aesthetic score to 0..100. Liveview scores run low, so 4.0..6.5 maps to 0..100; adjust after real shooting. */
        const val NORM_LOW = 4.0f
        const val NORM_HIGH = 6.5f
        fun normalize(s: Float): Int =
            ((s - NORM_LOW) / (NORM_HIGH - NORM_LOW) * 100f).roundToInt().coerceIn(0, 100)

        /** Why the last load() failed (only set when the model files exist but loading threw), shown in the UI. */
        @Volatile var lastError: String? = null
            private set

        fun load(context: Context): AestheticModel? = try {
            lastError = null
            val names = context.assets.list("")?.toSet().orEmpty()
            if (MODEL_ASSET !in names || META_ASSET !in names) {
                null
            } else {
                val meta = parseMeta(context.assets.open(META_ASSET).bufferedReader().use { it.readText() })
                // Never read the ~96 MB model into the Java heap (readBytes() peaks at several times the file
                // size and throws OutOfMemoryError on phones with a small heap). Stream it to a file once and
                // let ONNX Runtime load it natively from there.
                val modelFile = modelFile(context)
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    // 2 threads by default: leaves cores for ML Kit, the UI and the camera; "ai.threads" in coach_config.json
                    setIntraOpNumThreads(com.example.aicomposer.coach.Cfg.d("ai.threads").toInt().coerceIn(1, 8))
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                AestheticModel(env, env.createSession(modelFile.absolutePath, opts), meta)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load the aesthetic model", e)
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }

        /**
         * The model asset copied to app-private storage. Copied again when the app is installed or updated
         * (the APK may carry a new model), detected through the package's lastUpdateTime.
         */
        private fun modelFile(context: Context): File {
            val dir = File(context.noBackupFilesDir, "model").apply { mkdirs() }
            val file = File(dir, MODEL_ASSET)
            val stamp = File(dir, "$MODEL_ASSET.stamp")
            val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            val upToDate = file.exists() && stamp.exists() && stamp.readText() == version
            if (!upToDate) {
                val tmp = File(dir, "$MODEL_ASSET.tmp")
                context.assets.open(MODEL_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("cannot move model file into place")
                stamp.writeText(version)
            }
            return file
        }

        private fun parseMeta(text: String): Meta {
            val o = JSONObject(text)
            val scenesObj = o.getJSONObject("scenes")
            val scenes = mutableMapOf<SceneType, FloatArray>()
            for (key in scenesObj.keys()) {
                val type = runCatching { SceneType.valueOf(key) }.getOrNull() ?: continue
                scenes[type] = scenesObj.getJSONArray(key).toFloats()
            }
            val head = o.getJSONObject("head")
            val comp = o.getJSONObject("composition")
            return Meta(
                size = o.optInt("image_size", 224),
                mean = o.getJSONArray("mean").toFloats(),
                std = o.getJSONArray("std").toFloats(),
                logitScale = o.optDouble("logit_scale", 100.0).toFloat(),
                headW = head.getJSONArray("w").toFloats(),
                headB = head.getDouble("b").toFloat(),
                scenes = scenes,
                compGood = comp.getJSONArray("good").toFloats(),
                compBad = comp.getJSONArray("bad").toFloats(),
                interestPos = o.optJSONObject("interest")?.optJSONArray("pos")?.toFloats(),
                interestNeg = o.optJSONObject("interest")?.optJSONArray("neg")?.toFloats(),
            )
        }

        private fun JSONArray.toFloats() = FloatArray(length()) { getDouble(it).toFloat() }

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in 0 until min(a.size, b.size)) s += a[i] * b[i]
            return s
        }

        private fun l2(v: FloatArray): FloatArray {
            var s = 0f
            for (x in v) s += x * x
            val n = sqrt(s).coerceAtLeast(1e-6f)
            return FloatArray(v.size) { v[it] / n }
        }

        private fun softmax(x: FloatArray): FloatArray {
            val m = x.max()
            val e = FloatArray(x.size) { exp(x[it] - m) }
            val sum = e.sum()
            return FloatArray(x.size) { e[it] / sum }
        }

        private fun fmt(v: Float) = String.format(java.util.Locale.ROOT, "%.2f", v)
    }
}
