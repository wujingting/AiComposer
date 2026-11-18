package com.example.aicomposer.analysis

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.aicomposer.ai.CocoClasses
import com.example.aicomposer.ai.Detection
import com.example.aicomposer.ai.ObjectDetector
import com.example.aicomposer.ai.YoloxCodec
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlin.math.atan2
import kotlinx.coroutines.tasks.await
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Runs every analysis on one liveview frame. Models used (all on-device):
 *   - Face detection (landmarks, eye-open / smile probability, head angles)
 *   - Object detection: YOLOX (80 COCO classes, see [ObjectDetector]) when installed, otherwise ML Kit
 *   - Image labeling (scene)
 *   - Pose detection (33 landmarks, used e.g. by the "cropped at a joint" rule)
 *   - Subject segmentation (generic foreground, finds a subject even without faces/objects;
 *     needs Google Play services, the model is downloaded on first use)
 *   - Text recognition (Japanese + Latin: signs, slogans)
 *   - CLIP point of interest (computed by the ViewModel about every 2 s and injected via [interest])
 * Plus classic image features (see Features.kt).
 */
class FrameAnalyzer {
    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.05f)
            .build()
    )
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build()
    )
    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
    )
    private val segmenter = runCatching {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
        )
    }.getOrNull()

    private val textRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private var lastTexts: List<TextInfo> = emptyList()

    /** YOLOX detector (set by [com.example.aicomposer.pipeline.CoachPipeline]); null = not installed, use ML Kit. */
    @Volatile
    var detector: ObjectDetector? = null
    /** Last YOLOX result; null when it has not run. */
    private var lastDetections: List<Detection>? = null

    /** CLIP point-of-interest region and its timestamp; stale after 3 s. Written by the ViewModel. */
    @Volatile
    var interest: Pair<NRect, Long>? = null

    private var prevGray: GrayFrame? = null
    private var frameCount = 0
    private var lastLabels: List<String> = emptyList()
    private var lastPose: PoseInfo? = null
    private var lastMask: Mask? = null
    private var segmentationBroken = false
    private var prevMainCenter: P? = null
    private val sceneHistory = ArrayDeque<SceneType>()

    /** Whether subject segmentation works (not on phones without Google Play services). */
    val segmentationAvailable: Boolean get() = segmenter != null && !segmentationBroken

    /**
     * Analyze one frame. For a liveview stream (still = false) the slower models run every few frames and
     * motion / scene are compared with the previous frames. For an independent photo (still = true) every
     * model runs and nothing is carried over from the previous call.
     * @param stillInterest CLIP point of interest computed for this photo (still = true only)
     */
    suspend fun analyze(bmp: Bitmap, still: Boolean = false, stillInterest: NRect? = null): AnalysisResult {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val image = InputImage.fromBitmap(bmp, 0)
        val n = frameCount++
        if (still) {
            prevGray = null; prevMainCenter = null; sceneHistory.clear()
            lastLabels = emptyList(); lastTexts = emptyList(); lastPose = null; lastMask = null; lastDetections = null
        }
        fun due(every: Int, phase: Int) = still || n % every == phase

        // --- Models ---
        val faces = runCatching { faceDetector.process(image).await() }.getOrDefault(emptyList())
        val det = detector
        // YOLOX is slower than ML Kit: in the liveview it runs every third frame and its boxes are reused in between
        if (det != null && (due(3, 2) || lastDetections == null)) {
            lastDetections = runCatching { det.detect(bmp) }.getOrElse { lastDetections }
        }
        val detections = if (det != null) lastDetections else null
        val objects = if (detections != null) emptyList()
        else runCatching { objectDetector.process(image).await() }.getOrDefault(emptyList())
        if (due(4, 0)) {
            lastLabels = runCatching { labeler.process(image).await().map { it.text } }.getOrDefault(lastLabels)
        }
        if (due(4, 1)) {
            lastTexts = runCatching {
                textRecognizer.process(image).await().textBlocks.mapNotNull { b ->
                    val box = b.boundingBox ?: return@mapNotNull null
                    val cp = b.cornerPoints
                    val angle = if (cp != null && cp.size >= 2) {
                        Math.toDegrees(atan2((cp[1].y - cp[0].y).toDouble(), (cp[1].x - cp[0].x).toDouble())).toFloat()
                    } else 0f
                    TextInfo(box.norm(w, h), b.text.replace("\n", " "), angle)
                }.filter { it.rect.area > 0.001f }
            }.getOrDefault(lastTexts)
        }
        if (due(2, 0)) {
            lastPose = runCatching {
                val pose = poseDetector.process(image).await()
                val pts = pose.allPoseLandmarks
                    .filter { it.inFrameLikelihood > 0.5f }
                    .associate { it.landmarkType to P(it.position.x / w, it.position.y / h) }
                // ML Kit always returns some pose: keep it only if it looks like a real human skeleton
                if (pts.size >= 6) PoseInfo(pts).takeIf { it.plausible(w / h) } else null
            }.getOrNull()
        }
        if (due(6, 0) && segmenter != null && !segmentationBroken) {
            val small = Bitmap.createScaledBitmap(bmp, 256, max(1, (256 * h / w).roundToInt()), true)
            try {
                val res = segmenter.process(InputImage.fromBitmap(small, 0)).await()
                val buf = res.foregroundConfidenceMask
                if (buf != null) {
                    buf.rewind()
                    val arr = FloatArray(small.width * small.height)
                    buf.get(arr, 0, minOf(arr.size, buf.remaining()))
                    lastMask = Mask(small.width, small.height, arr)
                }
            } catch (e: Exception) {
                segmentationBroken = true // model unavailable (no Play services, download failed...): stop trying
            } finally {
                if (small !== bmp) small.recycle()
            }
        }

        val faceInfos = faces.map { f ->
            FaceInfo(
                rect = f.boundingBox.norm(w, h),
                yaw = f.headEulerAngleY,
                roll = f.headEulerAngleZ,
                leftEyeOpen = f.leftEyeOpenProbability,
                rightEyeOpen = f.rightEyeOpenProbability,
                smile = f.smilingProbability,
                eyes = listOfNotNull(
                    f.getLandmark(FaceLandmark.LEFT_EYE)?.position,
                    f.getLandmark(FaceLandmark.RIGHT_EYE)?.position,
                ).map { P(it.x / w, it.y / h) },
            )
        }

        // --- Subjects ---
        val faceSubjects = faceInfos.map { Subject(it.rect, SubjectKind.PERSON, true) }
        val objectSubjects = (
            detections?.map { d -> Subject(d.rect, d.kind, false, name = d.label, score = d.score) }
                ?: objects.map { o -> Subject(o.boundingBox.norm(w, h), kindOf(o.labels.firstOrNull()?.text), false) }
            ).filter { it.rect.area < 0.9f && it.rect.area > 0.002f }
        // A body without a visible face must also sit on the segmented foreground (when that mask exists)
        val poseSubject = lastPose
            ?.takeIf { pose -> faceInfos.isNotEmpty() || (lastMask?.let { poseOnMask(pose, it) } != false && poseHasPerson(pose, detections)) }
            ?.bounds?.let { Subject(it.clampUnit(), SubjectKind.PERSON, false) }
        val maskSubject = lastMask?.foreground()?.let { (r, area) ->
            if (area in 0.02f..0.7f) Subject(r, SubjectKind.GENERIC, false, guessed = true) else null
        }
        // --- Classic features (incl. saliency) ---
        val features = FeatureExtractor.compute(bmp)
        val salientSubject = features.saliency.subject()?.let { (r, area) ->
            if (area in 0.01f..0.6f) Subject(r, SubjectKind.GENERIC, false, guessed = true) else null
        }

        val textSubject = lastTexts.maxByOrNull { it.rect.area }
            ?.takeIf { it.rect.area >= 0.01f }
            ?.let { Subject(it.rect, SubjectKind.TEXT, false, text = it.text.take(8)) }
        // Liveview: the CLIP point of interest from the background loop; a photo: the one computed for it
        val freshInterest = if (still) stillInterest else interest?.takeIf { System.currentTimeMillis() - it.second < 3000 }?.first
        val salientBlobs = features.saliency.blobRects().take(5).map { Subject(it, SubjectKind.GENERIC, false, guessed = true) }

        val subjects = faceSubjects + objectSubjects
        val main = pickMain(
            faceSubjects, poseSubject, objectSubjects,
            generic = listOfNotNull(textSubject, maskSubject) + salientBlobs,
            fallback = textSubject ?: maskSubject ?: salientSubject,
            interest = freshInterest,
        )
        val gray = features.gray
        val luma = ImageStats.luma(gray, main?.rect)
        val (motion, subjectMotion) = ImageStats.motion(prevGray, gray, main?.rect)
        prevGray = gray

        val center = main?.let { P(it.rect.cx, it.rect.cy) }
        val prev = prevMainCenter
        val velocity = if (center != null && prev != null && hypot(center.x - prev.x, center.y - prev.y) < 0.2f) {
            P(center.x - prev.x, center.y - prev.y)
        } else null
        prevMainCenter = center

        val rawScene = classify(luma, faceSubjects, main, motion, subjectMotion, lastLabels)
        val scene = smooth(rawScene)

        return AnalysisResult(
            subjects = subjects + listOfNotNull(if (main != null && main !in subjects) main else null),
            mainSubject = main,
            labels = lastLabels,
            scene = scene,
            tiltDeg = features.tilt,
            motion = motion,
            subjectMotion = subjectMotion,
            luma = luma,
            frameAspect = w / h,
            faces = faceInfos,
            pose = lastPose,
            mask = lastMask,
            features = features,
            subjectVelocity = velocity,
            texts = lastTexts,
            interest = freshInterest,
        )
    }

    fun close() {
        objectDetector.close()
        faceDetector.close()
        labeler.close()
        poseDetector.close()
        textRecognizer.close()
        segmenter?.close()
    }

    /**
     * Subject priority: face > pose (people whose face is not visible) > object > generic candidates.
     * Among generic candidates (text, segmented foreground, salient regions):
     *   with a CLIP point of interest, pick the one inside it, otherwise use the region itself;
     *   without one: text > segmented foreground > most salient region.
     */
    private fun pickMain(
        faces: List<Subject>, pose: Subject?, objects: List<Subject>,
        generic: List<Subject>, fallback: Subject?, interest: NRect?,
    ): Subject? {
        faces.maxByOrNull { it.rect.area }?.let { return it }
        if (pose != null && pose.rect.area > 0.02f) return pose
        objects.maxByOrNull { s ->
            val d = hypot(s.rect.cx - 0.5f, s.rect.cy - 0.5f)
            // Bigger, more central, more certain wins; people count double (they are usually the point of the photo)
            s.rect.area * (1f - 0.8f * d) * (s.score ?: 1f) * (if (s.kind == SubjectKind.PERSON) 2f else 1f)
        }?.let { return it }
        if (interest != null) {
            generic.firstOrNull { interest.contains(it.rect.cx, it.rect.cy) }?.let {
                return if (it.kind == SubjectKind.GENERIC) it.copy(kind = SubjectKind.INTEREST) else it
            }
            return Subject(interest.shrink(0.6f), SubjectKind.INTEREST, false, guessed = true)
        }
        return fallback
    }

    /** When YOLOX ran, a faceless body must overlap a detected person. */
    private fun poseHasPerson(pose: PoseInfo, detections: List<Detection>?): Boolean {
        if (detections == null) return true
        val b = pose.bounds ?: return false
        return detections.any { it.cls == CocoClasses.PERSON && YoloxCodec.iou(it.rect, b) > 0.2f }
    }

    /** Share of torso / head landmarks lying on the foreground mask is at least half. */
    private fun poseOnMask(pose: PoseInfo, mask: Mask): Boolean {
        val core = pose.points.filterKeys { it <= 12 || it in 23..24 }.values
        if (core.isEmpty()) return false
        return core.count { mask.at(it.x, it.y) >= 0.3f } * 2 >= core.size
    }

    private fun classify(
        luma: LumaStats,
        faces: List<Subject>,
        main: Subject?,
        motion: Float,
        subjectMotion: Float?,
        labels: List<String>,
    ): SceneType {
        if (luma.mean < 0.18f) return SceneType.NIGHT
        if (subjectMotion != null && subjectMotion > 0.08f && subjectMotion > motion * 1.5f) return SceneType.ACTION
        if ((faces.maxOfOrNull { it.rect.area } ?: 0f) > 0.01f) return SceneType.PORTRAIT
        if (main != null && main.rect.area > 0.3f) return SceneType.CLOSEUP
        if (main != null && main.rect.area > 0.12f && labels.any { it in CLOSEUP_WORDS }) return SceneType.CLOSEUP
        if (labels.any { it in LANDSCAPE_WORDS }) return SceneType.LANDSCAPE
        return SceneType.GENERAL
    }

    private fun smooth(s: SceneType): SceneType {
        sceneHistory.addLast(s)
        while (sceneHistory.size > 8) sceneHistory.removeFirst()
        return sceneHistory.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: s
    }

    private fun Rect.norm(w: Float, h: Float) = NRect(left / w, top / h, right / w, bottom / h)

    private fun NRect.clampUnit() = NRect(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))

    private fun kindOf(label: String?): SubjectKind = when (label) {
        "Fashion good" -> SubjectKind.CLOTHING
        "Food" -> SubjectKind.FOOD
        "Home good" -> SubjectKind.HOME_GOOD
        "Place" -> SubjectKind.PLACE
        "Plant" -> SubjectKind.PLANT
        else -> SubjectKind.GENERIC
    }

    companion object {
        val LANDSCAPE_WORDS = setOf(
            "Sky", "Mountain", "Lake", "Beach", "Sunset", "Cloud", "River", "Waterfall",
            "Sea", "Ocean", "Field", "Forest", "Tree", "Snow", "Building", "Skyscraper",
            "Bridge", "Tower", "City", "Horizon", "Desert", "Canyon",
        )
        val CLOSEUP_WORDS = setOf(
            "Food", "Flower", "Plant", "Petal", "Insect", "Dessert", "Cuisine", "Drink", "Butterfly",
        )
        val BRIGHT_WORDS = setOf("Snow", "Beach", "Desert")
        val ANIMAL_WORDS = setOf("Cat", "Dog", "Bird", "Pet", "Animal", "Fish", "Horse", "Rabbit", "Duck", "Insect", "Butterfly")
        val ARCHITECTURE_WORDS = setOf("Building", "Skyscraper", "Tower", "Architecture", "Bridge", "Church", "Temple")
    }
}
