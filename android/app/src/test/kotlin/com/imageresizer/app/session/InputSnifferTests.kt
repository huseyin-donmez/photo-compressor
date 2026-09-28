package com.imageresizer.app.session

import com.imageresizer.core.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Header-sniffing tests against the same golden fixtures the iOS suite and
 * android:core stripper tests use (testdata/). These cover the pure, JVM-safe
 * part of the Android codec — the decode/encode paths need a device/emulator.
 *
 * Expected values are ground truth recorded from the fixture files themselves
 * (PIL header inspection), not "whatever the code returns".
 */
class InputSnifferTests {

    // MARK: - Format (magic bytes)

    @Test
    fun sniffsFormatsFromFixtureBytes() {
        val expectations = mapOf(
            "small_photo.jpg" to SourceFormat.JPEG,
            "corrupt.jpg" to SourceFormat.JPEG, // magic present; decode fails later
            "flat_graphic.png" to SourceFormat.PNG,
            "photo_png_noalpha.png" to SourceFormat.PNG,
            "photo.heic" to SourceFormat.HEIC,
            "portrait_orient6.heic" to SourceFormat.HEIC,
            "test.webp" to SourceFormat.WEBP,
            "transparent.webp" to SourceFormat.WEBP,
            "animated.gif" to SourceFormat.GIF,
            "empty.jpg" to SourceFormat.OTHER, // zero bytes
        )
        for ((name, expected) in expectations) {
            assertEquals(name, expected, InputSniffer.sniff(fixture(name).readBytes()))
        }
    }

    @Test
    fun formatReadsTheFileItself() {
        assertEquals(SourceFormat.JPEG, InputSniffer.format(fixture("small_photo.jpg")))
        assertEquals(SourceFormat.HEIC, InputSniffer.format(fixture("photo.heic")))
        assertEquals(SourceFormat.OTHER, InputSniffer.format(fixture("empty.jpg")))
    }

    @Test
    fun unknownAndRawContainersAreOtherOrTiffRaw() {
        // Plain TIFF header → TIFF (unsupported, reported as such).
        val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(SourceFormat.TIFF, InputSniffer.sniff(tiff))
        // Canon CR2 nests "CR" at offset 8 → RAW label (also unsupported).
        val cr2 = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x43, 0x52)
        assertEquals(SourceFormat.RAW, InputSniffer.sniff(cr2))
        // Random garbage → OTHER (skipped as unsupported, never decoded).
        assertEquals(SourceFormat.OTHER, InputSniffer.sniff(ByteArray(64) { 0x37 }))
        // PDF magic → OTHER.
        assertEquals(SourceFormat.OTHER, InputSniffer.sniff("%PDF-1.7\n".toByteArray()))
    }

    @Test
    fun avifNeverMapsToHeic() {
        // AVIF shares the generic mif1 brand with HEIF; it must stay unsupported.
        fun ftyp(vararg brands: String): ByteArray {
            val out = ArrayList<Byte>()
            val body = ArrayList<Byte>()
            "ftyp".forEach { body += it.code.toByte() }
            brands.forEach { b -> b.forEach { body += it.code.toByte() } }
            val size = (4 + body.size)
            out += ((size ushr 24) and 0xFF).toByte()
            out += ((size ushr 16) and 0xFF).toByte()
            out += ((size ushr 8) and 0xFF).toByte()
            out += (size and 0xFF).toByte()
            out += body
            return out.toByteArray()
        }
        assertEquals(SourceFormat.OTHER, InputSniffer.sniff(ftyp("avif", "mif1", "avif")))
        assertEquals(SourceFormat.OTHER, InputSniffer.sniff(ftyp("mif1", "mif1", "avif")))
        assertEquals(SourceFormat.HEIC, InputSniffer.sniff(ftyp("heic", "mif1", "miaf", "heic")))
    }

    // MARK: - Alpha (container bytes; ground truth from PIL)

    @Test
    fun pngAlphaFollowsColorTypeAndTransparency() {
        assertTrue("RGBA fixture", InputSniffer.pngHasAlpha(fixture("transparent_noise.png").readBytes()))
        assertFalse("RGB fixture", InputSniffer.pngHasAlpha(fixture("photo_png_noalpha.png").readBytes()))
        assertFalse("RGB graphic", InputSniffer.pngHasAlpha(fixture("flat_graphic.png").readBytes()))
    }

    @Test
    fun webpAlphaFollowsContainerFlags() {
        assertTrue(
            "VP8X alpha flag set",
            InputSniffer.webpHasAlpha(fixture("transparent.webp").readBytes()),
        )
        assertFalse(
            "lossy VP8, no alpha",
            InputSniffer.webpHasAlpha(fixture("test.webp").readBytes()),
        )
    }

    @Test
    fun gifTransparencyReadsTheGraphicControlExtension() {
        // Ground truth: animated.gif has NO transparency flag on any frame.
        assertFalse(InputSniffer.gifHasAlpha(fixture("animated.gif").readBytes()))
        // Synthetic GIF89a with a GCE that sets the transparent-color flag.
        val withGce = buildGif(withTransparencyFlag = true)
        assertTrue(InputSniffer.gifHasAlpha(withGce))
        val withoutGce = buildGif(withTransparencyFlag = false)
        assertFalse(InputSniffer.gifHasAlpha(withoutGce))
        // Unsure → true (safe guess: lossless PNG output).
        assertTrue(InputSniffer.gifHasAlpha("GIF89a".toByteArray()))
    }

    @Test
    fun hasAlphaDispatchesByFormat() {
        assertFalse(InputSniffer.hasAlpha(fixture("small_photo.jpg"), SourceFormat.JPEG))
        assertTrue(InputSniffer.hasAlpha(fixture("transparent_noise.png"), SourceFormat.PNG))
        assertFalse(InputSniffer.hasAlpha(fixture("photo_png_noalpha.png"), SourceFormat.PNG))
        assertTrue(InputSniffer.hasAlpha(fixture("transparent.webp"), SourceFormat.WEBP))
        assertFalse(InputSniffer.hasAlpha(fixture("animated.gif"), SourceFormat.GIF))
    }

    // MARK: - Helpers

    /** Minimal GIF89a: logical screen, optional GCE, one image descriptor. */
    private fun buildGif(withTransparencyFlag: Boolean): ByteArray {
        val out = ArrayList<Byte>()
        "GIF89a".forEach { out += it.code.toByte() }
        out += 2.toByte(); out += 0.toByte() // width
        out += 2.toByte(); out += 0.toByte() // height
        out += 0.toByte() // packed: no global color table
        out += 0.toByte() // background index
        out += 0.toByte() // pixel aspect
        if (withTransparencyFlag) {
            out += 0x21.toByte(); out += 0xF9.toByte() // extension introducer + GCE label
            out += 4.toByte() // block size
            out += 0x01.toByte() // packed: transparency flag set
            out += 0.toByte(); out += 0.toByte() // delay
            out += 0.toByte() // transparent color index
            out += 0.toByte() // block terminator
        }
        out += 0x2C.toByte() // image descriptor → parser must stop here with the flag read
        return out.toByteArray()
    }

    /** Same repository-relative locator as android:core's test kit. */
    private fun fixture(name: String): File {
        val dir = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "testdata") }
            .firstOrNull { it.isDirectory }
            ?: error("testdata/ not found above user.dir — run python3 scripts/make_fixtures.py")
        return File(dir, name).also {
            require(it.isFile) { "missing fixture $it — run python3 scripts/make_fixtures.py" }
        }
    }
}
