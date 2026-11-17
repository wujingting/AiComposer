package com.example.aicomposer.phone

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** What the phone camera currently reports. Anything unknown is null. */
data class PhoneCameraInfo(
    val exposureNs: Long? = null,
    val iso: Int? = null,
    val zoomRatio: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val evIndex: Int = 0,
    val evMin: Int = 0,
    val evMax: Int = 0,
    /** EV per exposure-compensation index (e.g. 1/3 or 1/6). */
    val evStep: Double = 1.0 / 3.0,
    /** 35mm-equivalent focal length at the current zoom, or null if the camera does not say. */
    val focalEq35: Float? = null,
)

/**
 * Binds the back camera with CameraX: Preview (shown in [previewView]), ImageAnalysis (upright frames for the
 * coach, about [frameIntervalMs] apart) and ImageCapture (saved to Pictures/AiComposer). Both preview and
 * analysis use 4:3 so the overlay drawn over the FIT_CENTER preview lines up with the analyzed frame.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class PhoneCamera(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrame: (Bitmap) -> Unit,
    private val onInfo: (PhoneCameraInfo) -> Unit,
    private val onError: (String) -> Unit,
    private val frameIntervalMs: Long = 120,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastInfoAt = 0L
    @Volatile private var baseEq35: Float? = null
    @Volatile private var stopped = false

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            try {
                bind(future.get())
            } catch (e: Exception) {
                onError(e.message ?: e.javaClass.simpleName)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun selector(maxSize: Size?): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .apply {
            if (maxSize != null) setResolutionStrategy(
                ResolutionStrategy(maxSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
        }
        .build()

    private fun bind(p: ProcessCameraProvider) {
        provider = p
        p.unbindAll()

        val previewBuilder = Preview.Builder().setResolutionSelector(selector(null))
        // Read the actual exposure time and ISO of each preview frame (for the shutter / ISO rules)
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val now = System.currentTimeMillis()
                if (now - lastInfoAt < 500) return
                lastInfoAt = now
                publishInfo(result.get(CaptureResult.SENSOR_EXPOSURE_TIME), result.get(CaptureResult.SENSOR_SENSITIVITY))
            }
        })
        val preview = previewBuilder.build()

        // Small frames are enough for the coach (the Sony liveview is 640×424)
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(selector(Size(640, 480)))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(executor) { proxy ->
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameAt >= frameIntervalMs) {
                    lastFrameAt = now
                    onFrame(upright(proxy.toBitmap(), proxy.imageInfo.rotationDegrees))
                }
            } catch (_: Exception) {
            } finally {
                proxy.close()
            }
        }

        val capture = ImageCapture.Builder()
            .setResolutionSelector(selector(null))
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageCapture = capture

        val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
        camera = cam
        preview.setSurfaceProvider(previewView.surfaceProvider)
        baseEq35 = equivalentFocal(cam)
        cam.cameraInfo.zoomState.observe(owner) { publishInfo(null, null) }
        publishInfo(null, null)
    }

    @Volatile private var lastExposureNs: Long? = null
    @Volatile private var lastIso: Int? = null

    private fun publishInfo(exposureNs: Long?, iso: Int?) {
        val cam = camera ?: return
        if (exposureNs != null) lastExposureNs = exposureNs
        if (iso != null) lastIso = iso
        val zoom = cam.cameraInfo.zoomState.value
        val ev = cam.cameraInfo.exposureState
        val ratio = zoom?.zoomRatio ?: 1f
        onInfo(
            PhoneCameraInfo(
                exposureNs = lastExposureNs,
                iso = lastIso,
                zoomRatio = ratio,
                minZoom = zoom?.minZoomRatio ?: 1f,
                maxZoom = zoom?.maxZoomRatio ?: 1f,
                evIndex = ev.exposureCompensationIndex,
                evMin = ev.exposureCompensationRange.lower,
                evMax = ev.exposureCompensationRange.upper,
                evStep = ev.exposureCompensationStep.toDouble().takeIf { it > 0 } ?: (1.0 / 3.0),
                focalEq35 = baseEq35?.let { it * ratio },
            )
        )
    }

    /** 35mm-equivalent focal length of the lens at zoom 1x, from the Camera2 characteristics. */
    private fun equivalentFocal(cam: Camera): Float? = try {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focal != null && sensor != null && sensor.width > 0f) {
            val diag = sqrt(sensor.width * sensor.width + sensor.height * sensor.height)
            focal * 43.27f / diag // 43.27mm = full-frame diagonal
        } else null
    } catch (_: Exception) {
        null
    }

    /**
     * Rotates the analysis frame upright. The source bitmap from proxy.toBitmap() is ours alone (a new bitmap
     * per frame), so recycle it right away instead of leaving ~1 MB per frame for the garbage collector.
     */
    private fun upright(bmp: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    fun setZoomRatio(r: Float) {
        camera?.cameraControl?.setZoomRatio(r)
    }

    fun setExposureIndex(i: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(i)?.addListener({ publishInfo(null, null) }, ContextCompat.getMainExecutor(context))
    }

    /** Focus and meter on a point of the preview (view coordinates). */
    fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    /** Takes a photo into Pictures/AiComposer. [done] gets (success, message) on the main thread. */
    @SuppressLint("InlinedApi")
    fun takePicture(done: (Boolean, String) -> Unit) {
        val capture = imageCapture ?: return done(false, "camera not ready")
        val name = "AIC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AiComposer")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = done(true, "Pictures/AiComposer/$name.jpg")
            override fun onError(exception: ImageCaptureException) = done(false, exception.message ?: "error ${exception.imageCaptureError}")
        })
    }

    fun stop() {
        stopped = true
        camera?.cameraInfo?.zoomState?.removeObservers(owner)
        provider?.unbindAll()
        camera = null
        imageCapture = null
        executor.shutdown()
    }
}
