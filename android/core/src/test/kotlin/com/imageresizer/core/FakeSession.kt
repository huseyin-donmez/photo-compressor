package com.imageresizer.core

import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Deterministic in-memory codec for pure algorithm tests.
 * Size model: `bytes = pixels × (floor + slope × q²)` — monotonic in quality,
 * linear in pixel count, like a real codec. Mirrors the iOS FakeSession.
 */
class FakeSession(val config: Config) : ResizeSession {
    class Config(
        val width: Int,
        val height: Int,
        val format: SourceFormat = SourceFormat.JPEG,
        val hasAlpha: Boolean = false,
        val byteSize: Long,
        /** Bytes per pixel at quality 0 and the quality-squared slope. */
        val modelFloor: Double = 0.05,
        val modelSlope: Double = 0.55,
        /** PNG-like codecs have no quality axis. */
        val qualityAffectsSize: Boolean = true,
        /** What the metadata-only strip returns (null = platform cannot strip). */
        val passThroughData: ByteArray? = null,
        val passThroughAvailable: Boolean = true,
    )

    var encodeCount = 0
        private set
    var decodeCount = 0
        private set
    var passThroughCalls = 0
        private set
    val decodedLongestEdges = mutableListOf<Int>()

    override fun readHeader(): ImageHeader = ImageHeader(
        pixelWidth = config.width,
        pixelHeight = config.height,
        format = config.format,
        hasAlpha = config.hasAlpha,
        byteSize = config.byteSize,
    )

    override fun passThroughStripMetadata(): ByteArray? {
        passThroughCalls += 1
        if (!config.passThroughAvailable) return null
        return config.passThroughData ?: ByteArray(config.byteSize.toInt())
    }

    override fun decode(longestEdge: Int): ResizableImage {
        decodeCount += 1
        decodedLongestEdges += longestEdge
        val longest = max(config.width, config.height).toDouble()
        val scale = min(1.0, longestEdge / longest)
        val w = max(1, (config.width * scale).roundToInt())
        val h = max(1, (config.height * scale).roundToInt())
        return FakeImage(w, h)
    }

    override fun encodeFinal(image: ResizableImage, quality: Double): ByteArray {
        encodeCount += 1
        val q = if (config.qualityAffectsSize) quality else 1.0
        val bytesPerPixel = config.modelFloor + config.modelSlope * q * q
        val pixels = image.pixelWidth.toDouble() * image.pixelHeight.toDouble()
        return ByteArray(max(1, (pixels * bytesPerPixel).toLong().toInt()))
    }

    /** Expected size under the model (for assertions). */
    fun modelBytes(width: Int, height: Int, quality: Double): Int {
        val q = if (config.qualityAffectsSize) quality else 1.0
        val bpp = config.modelFloor + config.modelSlope * q * q
        return (width.toDouble() * height.toDouble() * bpp).toInt()
    }
}

class FakeImage(override val pixelWidth: Int, override val pixelHeight: Int) : ResizableImage

/** Locates the repo's shared fixture directory, wherever the test runs from. */
internal fun fixture(name: String): File {
    val dir = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "testdata") }
        .firstOrNull { it.isDirectory }
        ?: error("testdata/ not found above ${System.getProperty("user.dir")} — run python3 scripts/make_fixtures.py")
    return File(dir, name).also {
        require(it.isFile) { "missing fixture $it — run python3 scripts/make_fixtures.py" }
    }
}
