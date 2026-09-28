package com.imageresizer.core

/** Format of the source file as identified from container metadata (no decode). */
enum class SourceFormat { JPEG, PNG, HEIC, WEBP, GIF, BMP, TIFF, RAW, OTHER }

/**
 * Container the app is allowed to write. Decided automatically, never by the user.
 * HEIC exists only for contract parity with iOS — the Android column of the
 * output-format matrix (docs/ALGORITHM.md) never selects it: no native encoder.
 */
enum class OutputFormat { JPEG, PNG, HEIC, WEBP }

/** Header-only description of an input file. Reading this must never decode pixels. */
data class ImageHeader(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val format: SourceFormat,
    val hasAlpha: Boolean,
    val byteSize: Long,
) {
    val pixelCount: Long get() = pixelWidth.toLong() * pixelHeight.toLong()
}

/** Error taxonomy — see docs/ALGORITHM.md. A usage credit is consumed only on success. */
sealed class ResizeError(message: String) : Exception(message) {
    data class UnsupportedFormat(val format: SourceFormat) :
        ResizeError("unsupported format: $format")

    object Corrupted : ResizeError("corrupted input")
    object OutOfMemory : ResizeError("out of memory")
    object StorageFull : ResizeError("storage full")
    object EncodeFailed : ResizeError("encode failed")
    object OutputSaveFailed : ResizeError("output save failed")

    data class TargetExceeded(val measured: Long, val target: Long) :
        ResizeError("output is $measured bytes, target is $target")

    object TargetInfeasible : ResizeError("target infeasible")
    object Cancelled : ResizeError("cancelled")
}

data class ResizeReport(
    val outcome: Outcome,
    /** null for pass-through (original container preserved as-is). */
    val outputFormat: OutputFormat?,
    val bytes: Long,
    val pixelWidth: Int,
    val pixelHeight: Int,
    /** Quality used for the winning encode; null for pass-through. */
    val quality: Double?,
    val encodesUsed: Int,
    val levelsUsed: Int,
) {
    enum class Outcome { PASS_THROUGH, PROCESSED }
}

class ResizeResult(val report: ResizeReport, val data: ByteArray)
