package com.imageresizer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Byte-level pass-through strip tests against the shared golden fixtures in
 * testdata/ — the same files the iOS suite validates, so both platforms prove
 * the identical privacy contract (docs/ALGORITHM.md, Metadata policy).
 */
class PassThroughStripperTests {
    @Test
    fun gpsCoordinatesArePhysicallyZeroedInOutgoingBytes() {
        val input = fixture("portrait_orient6.jpg").readBytes()
        assertTrue(
            gpsLatitudeBytesPresent(input),
            "fixture must contain raw GPS bytes — otherwise this check is vacuous",
        )

        val outcome = PassThroughStripper.strip(input, SourceFormat.JPEG, gpsPresent = true)
        assertTrue(
            outcome is PassThroughStripper.Outcome.Stripped,
            "GPS-bearing JPEG must go through byte surgery, got $outcome",
        )
        val stripped = (outcome as PassThroughStripper.Outcome.Stripped).bytes

        // Strictly length-neutral: no TIFF offset can shift (MakerNote, thumbnail…).
        assertEquals(input.size, stripped.size, "strip must be length-neutral")
        // Still a JPEG…
        assertEquals(0xFF.toByte(), stripped[0])
        assertEquals(0xD8.toByte(), stripped[1])
        // …with the EXIF header intact (only the GPS IFD was neutralized)…
        assertTrue(
            PassThroughStripper.indexOf(stripped, "Exif\u0000\u0000".toByteArray()) >= 0,
            "other EXIF metadata must survive the strip",
        )
        // …and the coordinates physically gone, not merely hidden from a decoder.
        assertFalse(
            gpsLatitudeBytesPresent(stripped),
            "GPS coordinates must be physically zeroed, not just hidden from the decoder",
        )
    }

    @Test
    fun cleanJpegPassesThroughUntouched() {
        val input = fixture("small_photo.jpg").readBytes()
        val outcome = PassThroughStripper.strip(input, SourceFormat.JPEG, gpsPresent = false)
        assertTrue(outcome is PassThroughStripper.Outcome.Unchanged, "got $outcome")
        assertTrue((outcome as PassThroughStripper.Outcome.Unchanged).bytes === input)
    }

    @Test
    fun nonJpegWithGpsRefusesByteStrip() {
        // HEIC-with-GPS always re-encodes in v1 — privacy beats free pass-through.
        val outcome = PassThroughStripper.strip(
            fixture("portrait_orient6.heic").readBytes(),
            SourceFormat.HEIC,
            gpsPresent = true,
        )
        assertTrue(outcome is PassThroughStripper.Outcome.Unsupported, "got $outcome")
    }

    @Test
    fun provablyCleanContainersCopyBytes() {
        val heic = PassThroughStripper.strip(
            fixture("photo.heic").readBytes(),
            SourceFormat.HEIC,
            gpsPresent = false,
        )
        assertTrue(heic is PassThroughStripper.Outcome.Unchanged, "got $heic")

        val png = PassThroughStripper.strip(
            fixture("flat_graphic.png").readBytes(),
            SourceFormat.PNG,
            gpsPresent = false,
        )
        assertTrue(png is PassThroughStripper.Outcome.Unchanged, "got $png")
    }

    @Test
    fun corruptJpegHeaderRefusesStrip() {
        val outcome = PassThroughStripper.strip(
            byteArrayOf(0x00, 0x01, 0x02, 0x03),
            SourceFormat.JPEG,
            gpsPresent = false,
        )
        assertTrue(outcome is PassThroughStripper.Outcome.Unsupported, "got $outcome")
    }

    /**
     * Raw-byte hunt for the GPS latitude rationals 48/1, 51/1, 30/1 (48°51'30"N),
     * in either TIFF byte order. Physical presence/absence in the file itself —
     * decoder property tables can lie; bytes cannot.
     */
    private fun gpsLatitudeBytesPresent(data: ByteArray): Boolean {
        val values = longArrayOf(48, 1, 51, 1, 30, 1)
        for (littleEndian in booleanArrayOf(false, true)) {
            val needle = ByteArray(values.size * 4)
            for ((index, v) in values.withIndex()) {
                if (littleEndian) {
                    needle[index * 4] = (v and 0xFF).toByte()
                    needle[index * 4 + 1] = (v shr 8 and 0xFF).toByte()
                    needle[index * 4 + 2] = (v shr 16 and 0xFF).toByte()
                    needle[index * 4 + 3] = (v shr 24 and 0xFF).toByte()
                } else {
                    needle[index * 4] = (v shr 24 and 0xFF).toByte()
                    needle[index * 4 + 1] = (v shr 16 and 0xFF).toByte()
                    needle[index * 4 + 2] = (v shr 8 and 0xFF).toByte()
                    needle[index * 4 + 3] = (v and 0xFF).toByte()
                }
            }
            if (PassThroughStripper.indexOf(data, needle) >= 0) return true
        }
        return false
    }
}
