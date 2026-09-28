package com.imageresizer.app.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.media.ExifInterface as PlatformExif
import androidx.exifinterface.media.ExifInterface
import com.imageresizer.core.FormatStrategy
import com.imageresizer.core.ImageHeader
import com.imageresizer.core.OutputFormat
import com.imageresizer.core.PassThroughStripper
import com.imageresizer.core.ResizableImage
import com.imageresizer.core.ResizeError
import com.imageresizer.core.ResizeSession
import com.imageresizer.core.SourceFormat
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Decoded bitmap held for encoding. */
class AndroidImage(val bitmap: Bitmap) : ResizableImage {
    override val pixelWidth: Int get() = bitmap.width
    override val pixelHeight: Int get() = bitmap.height
}

/**
 * The production Android codec: header-only reads, bounds decoding with orientation
 * baked in, native JPEG/PNG/WebP encoding, byte-level GPS stripping for pass-through.
 * Mirrors ios/ImageResizerCore/ImageIOResizeSession.swift (docs/ALGORITHM.md).
 *
 * @param inputFile a local copy of the source (content-URI grants are temporary).
 * @param workDir scratch dir for encode verification (session-private).
 */
class AndroidImageSession(
    @Suppress("unused") private val context: Context,
    private val inputFile: File,
    private val workDir: File,
) : ResizeSession {

    companion object {
        /** Re-encode paths only — pass-through adds nothing (docs/ALGORITHM.md). */
        const val SOFTWARE_TAG = "ImageResizer 1.0"

        private val gpsTags = listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
        )

        /**
         * Metadata preserved on re-encode — iOS copies the whole EXIF + TIFF
         * dictionaries minus Orientation/GPS; this whitelist is Android's equivalent
         * (androidx exposes known tags, not raw IFD passthrough).
         */
        private val preservedTags = listOf(
            // TIFF
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_X_RESOLUTION,
            ExifInterface.TAG_Y_RESOLUTION,
            ExifInterface.TAG_RESOLUTION_UNIT,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_SENSING_METHOD,
            // EXIF
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_BRIGHTNESS_VALUE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_LENS_SPECIFICATION,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_PIXEL_X_DIMENSION,
            ExifInterface.TAG_PIXEL_Y_DIMENSION,
        )
    }

    private val byteSize: Long = inputFile.length()
    private var cachedHeader: ImageHeader? = null
    private var cachedOrientation: Int? = null
    private var sourceExif: ExifInterface? = null
    private var sourceExifLoaded = false

    // MARK: - Header (no pixel decode)

    override fun readHeader(): ImageHeader {
        cachedHeader?.let { return it }

        val format = InputSniffer.format(inputFile)
        if (format == SourceFormat.OTHER || format == SourceFormat.TIFF || format == SourceFormat.RAW) {
            // Unsupported (docs/ALGORITHM.md matrix): run() throws UnsupportedFormat
            // before touching dimensions, so dims stay zero — no decode attempted.
            return ImageHeader(0, 0, format, false, byteSize).also { cachedHeader = it }
        }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(inputFile.path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw ResizeError.Corrupted

        return ImageHeader(
            pixelWidth = opts.outWidth,
            pixelHeight = opts.outHeight,
            format = format,
            hasAlpha = InputSniffer.hasAlpha(inputFile, format),
            byteSize = byteSize,
        ).also { cachedHeader = it }
    }

    // MARK: - Pass-through (no re-encode)

    override fun passThroughStripMetadata(): ByteArray? {
        val header = readHeader()
        val gpsPresent = hasGps()
        val bytes = inputFile.readBytes()
        if (bytes.size.toLong() != byteSize) return null

        return when (val outcome = PassThroughStripper.strip(bytes, header.format, gpsPresent)) {
            is PassThroughStripper.Outcome.Unsupported ->
                null // → caller re-encodes, which strips GPS for sure
            is PassThroughStripper.Outcome.Unchanged ->
                // GPS reported but nothing strippable at byte level → pipeline decides.
                if (gpsPresent) null else bytes
            is PassThroughStripper.Outcome.Stripped ->
                if (strippedIsClean(outcome.bytes)) outcome.bytes else null
        }
    }

    /** Fail-safe for byte surgery: the result must still parse and expose no GPS. */
    private fun strippedIsClean(data: ByteArray): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return false
        return try {
            val exif = ExifInterface(data.inputStream())
            gpsTags.none { exif.getAttribute(it) != null }
        } catch (_: Exception) {
            false
        }
    }

    private fun hasGps(): Boolean = try {
        val exif = ExifInterface(inputFile.path)
        gpsTags.any { exif.getAttribute(it) != null }
    } catch (_: Exception) {
        false
    }

    // MARK: - Bounds decode (orientation baked)

    override fun decode(longestEdge: Int): ResizableImage {
        try {
            val header = readHeader()
            val rawLongest = max(header.pixelWidth, header.pixelHeight).coerceAtLeast(1)

            // Sampled decode so the result stays ≥ the exact target edge (quality:
            // scale down from a resolution at-or-above target, never upscale past source).
            val ratio = rawLongest.toDouble() / max(1, longestEdge)
            var sample = 1
            while (sample * 2 <= ratio) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            var bitmap = BitmapFactory.decodeFile(inputFile.path, opts)
                ?: throw ResizeError.Corrupted

            // Exact edge (iOS thumbnail = exactly min(source, longestEdge) pixels).
            val decodedLongest = max(bitmap.width, bitmap.height).coerceAtLeast(1)
            val targetLongest = min(rawLongest, longestEdge).coerceAtLeast(1)
            if (decodedLongest != targetLongest) {
                val k = targetLongest.toDouble() / decodedLongest
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    max(1, (bitmap.width * k).roundToInt()),
                    max(1, (bitmap.height * k).roundToInt()),
                    true,
                )
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }

            // Bake EXIF orientation into pixels — output is always upright.
            val baked = applyOrientation(bitmap, readOrientation())
            if (baked !== bitmap) bitmap.recycle()
            return AndroidImage(baked)
        } catch (oom: OutOfMemoryError) {
            throw ResizeError.OutOfMemory
        }
    }

    private fun readOrientation(): Int {
        cachedOrientation?.let { return it }
        val orientation = try {
            PlatformExif(inputFile.path)
                .getAttributeInt(
                    PlatformExif.TAG_ORIENTATION,
                    PlatformExif.ORIENTATION_NORMAL,
                )
        } catch (_: Exception) {
            PlatformExif.ORIENTATION_NORMAL
        }
        cachedOrientation = orientation
        return orientation
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            PlatformExif.ORIENTATION_NORMAL -> return bitmap
            PlatformExif.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            PlatformExif.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            PlatformExif.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            PlatformExif.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            PlatformExif.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            PlatformExif.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            PlatformExif.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (oom: OutOfMemoryError) {
            throw ResizeError.OutOfMemory
        }
    }

    // MARK: - Encode

    override fun encodeFinal(image: ResizableImage, quality: Double): ByteArray {
        val bitmap = (image as? AndroidImage)?.bitmap ?: throw ResizeError.EncodeFailed
        val header = readHeader()
        val format = FormatStrategy.outputFormat(header.format, header.hasAlpha)
            ?: throw ResizeError.UnsupportedFormat(header.format)
        val compressFormat = compressFormatFor(format) ?: throw ResizeError.EncodeFailed
        val level = (quality.coerceIn(0.0, 1.0) * 100).roundToInt()

        val temp = File.createTempFile("enc", ".tmp", workDir)
        try {
            temp.outputStream().use { out ->
                if (!bitmap.compress(compressFormat, level, out)) {
                    throw ResizeError.EncodeFailed
                }
            }
            writePreservedExif(temp, bitmap.width, bitmap.height)
            // Returned bytes are final: what the search measures is what gets written.
            return temp.readBytes()
        } catch (oom: OutOfMemoryError) {
            throw ResizeError.OutOfMemory
        } catch (e: ResizeError) {
            throw e
        } catch (_: IOException) {
            throw ResizeError.EncodeFailed
        } finally {
            temp.delete()
        }
    }

    private fun compressFormatFor(format: OutputFormat): Bitmap.CompressFormat? = when (format) {
        OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
        OutputFormat.PNG -> Bitmap.CompressFormat.PNG // lossless; quality axis unused
        OutputFormat.WEBP ->
            if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
            else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        OutputFormat.HEIC -> null // Android's FormatStrategy never selects HEIC (no encoder)
    }

    /**
     * Merge the preserved metadata whitelist onto freshly encoded pixels.
     * GPS and Orientation are structurally never written (Orientation is baked;
     * GPS is absent from the whitelist). On any failure we fall back to the bare
     * pixels — same policy as iOS's metadata-conflict fallbacks.
     */
    private fun writePreservedExif(file: File, width: Int, height: Int) {
        try {
            val output = ExifInterface(file.path)
            output.setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE_TAG)
            output.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            output.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            loadSourceExif()?.let { source ->
                for (tag in preservedTags) {
                    val value = source.getAttribute(tag) ?: continue
                    output.setAttribute(tag, value)
                }
            }
            output.saveAttributes()
        } catch (_: Exception) {
            // Bare pixels beat no output (iOS parity: final fallback has no metadata).
        }
    }

    private fun loadSourceExif(): ExifInterface? {
        if (!sourceExifLoaded) {
            sourceExifLoaded = true
            sourceExif = try {
                ExifInterface(inputFile.path)
            } catch (_: Exception) {
                null
            }
        }
        return sourceExif
    }
}
