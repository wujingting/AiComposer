package com.example.aicomposer.score

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Camera settings read from a photo's EXIF. Anything missing is null. */
data class PhotoExif(
    val exposureS: Double? = null,
    val iso: Int? = null,
    val fNumber: Double? = null,
    val focalEq35: Int? = null,
    val model: String? = null,
) {
    /** Shutter speed in the notation the rules parse: "1/120" or "0.5\"". */
    val shutterText: String?
        get() = exposureS?.takeIf { it > 0 }?.let {
            if (it >= 0.3) String.format(Locale.ROOT, "%.1f\"", it) else "1/${(1 / it).roundToInt()}"
        }

    /** One-line summary for the UI. */
    val summary: String
        get() = listOfNotNull(
            model,
            shutterText,
            fNumber?.let { String.format(Locale.ROOT, "f/%.1f", it) },
            iso?.let { "ISO $it" },
            focalEq35?.let { "${it}mm" },
        ).joinToString("   ")
}

object PhotoLoader {
    /**
     * Decodes a photo upright with its long side at most about [maxSide] pixels (power-of-two subsampling
     * while decoding, then an exact downscale), so even 24 MP photos never need much memory.
     */
    fun load(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("cannot decode image")

        val longSide = max(bmp.width, bmp.height)
        if (longSide > maxSide) {
            val k = maxSide.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * k).roundToInt(), (bmp.height * k).roundToInt(), true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        val m = orientationMatrix(context, uri) ?: return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    fun exif(context: Context, uri: Uri): PhotoExif = try {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val e = ExifInterface(s)
            PhotoExif(
                exposureS = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0).takeIf { it > 0 },
                iso = e.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1).takeIf { it > 0 },
                fNumber = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0).takeIf { it > 0 },
                focalEq35 = e.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, -1).takeIf { it > 0 },
                model = e.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
            )
        } ?: PhotoExif()
    } catch (_: Exception) {
        PhotoExif()
    }

    private fun orientationMatrix(context: Context, uri: Uri): Matrix? = try {
        val o = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
            ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { postRotate(270f); postScale(-1f, 1f) }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
