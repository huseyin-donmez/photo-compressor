package com.imageresizer.core

import java.io.ByteArrayOutputStream

/**
 * Byte-level metadata stripping for the pass-through path — no re-encode, no pixel decode.
 *
 * JPEG: walks the marker segments. XMP APP1 segments are dropped (segments are
 * self-delimiting, splicing is always safe); the EXIF GPS IFD is neutralized *in
 * place* — the IFD0 pointer entry is retagged as an unknown tag and every byte the
 * GPS IFD references is zeroed. Because nothing moves, no TIFF offset (MakerNote,
 * thumbnail, Exif SubIFD) can break.
 *
 * Other containers: byte-copy only when there is provably nothing to strip.
 * Callers fall back to the re-encode pipeline otherwise (e.g. HEIC with GPS today) —
 * privacy is never traded for a free pass-through.
 *
 * MUST behave identically to ios/ImageResizerCore/PassThroughStripper.swift.
 * Public: the platform codec in :app performs the pass-through strip.
 */
object PassThroughStripper {
    sealed class Outcome {
        /** Nothing to strip; original bytes. */
        class Unchanged(val bytes: ByteArray) : Outcome()

        /** GPS/XMP removed; bytes are still a valid image of the same format. */
        class Stripped(val bytes: ByteArray) : Outcome()

        /** Cannot strip safely at byte level → caller must fall back to re-encoding. */
        object Unsupported : Outcome()
    }

    private val exifSignature = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif\0\0"
    private val xmpApp1Signature = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private val xmpExtensionSignature = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.US_ASCII)

    /** XMP inside non-JPEG containers (PNG iTXt, WebP XMP chunk, HEIC mime item). */
    private val xmpPacketMarkers = listOf("<?xpacket".toByteArray(), "<x:xmpmeta".toByteArray())

    fun strip(bytes: ByteArray, format: SourceFormat, gpsPresent: Boolean): Outcome {
        if (format == SourceFormat.JPEG) {
            val out = stripJPEG(bytes) ?: return Outcome.Unsupported
            return if (out.contentEquals(bytes)) Outcome.Unchanged(bytes) else Outcome.Stripped(out)
        }
        if (gpsPresent || containsXMP(bytes)) return Outcome.Unsupported
        return Outcome.Unchanged(bytes)
    }

    // MARK: - JPEG marker walk

    private fun stripJPEG(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 2) // SOI
        var i = 2
        while (i < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return null
            var j = i
            while (j < bytes.size && bytes[j] == 0xFF.toByte()) j++ // fill bytes
            if (j >= bytes.size) return null
            val marker = bytes[j].toInt() and 0xFF

            // Markers without a length field: TEM, RST, repeated SOI.
            if (marker == 0x01 || marker == 0xD8 || marker in 0xD0..0xD7) {
                out.write(bytes, i, j + 1 - i)
                i = j + 1
                continue
            }
            // EOI / SOS: everything from here is not segment-structured.
            if (marker == 0xD9 || marker == 0xDA) {
                out.write(bytes, i, bytes.size - i)
                return out.toByteArray()
            }
            if (j + 2 >= bytes.size) return null
            val segLen = (bytes[j + 1].toInt() and 0xFF) shl 8 or (bytes[j + 2].toInt() and 0xFF)
            if (segLen < 2) return null
            val segEnd = j + 1 + segLen // the length field counts itself
            if (segEnd > bytes.size) return null

            if (marker == 0xE1) { // APP1
                val payloadStart = j + 3
                val payloadCount = segEnd - payloadStart
                if (payloadCount >= exifSignature.size &&
                    matches(bytes, payloadStart, exifSignature)
                ) {
                    val fixed = stripGPS(bytes.copyOfRange(payloadStart + 6, segEnd))
                        ?: return null
                    // Keep the "Exif\0\0" signature; only the TIFF blob is rewritten.
                    out.write(bytes, i, payloadStart + 6 - i)
                    out.write(fixed, 0, fixed.size)
                    i = segEnd
                    continue
                }
                if (matches(bytes, payloadStart, xmpApp1Signature) ||
                    matches(bytes, payloadStart, xmpExtensionSignature)
                ) {
                    i = segEnd // drop XMP APP1 entirely
                    continue
                }
            }
            out.write(bytes, i, segEnd - i)
            i = segEnd
        }
        return out.toByteArray()
    }

    // MARK: - EXIF GPS neutralization (strictly length-neutral)

    private fun stripGPS(tiff: ByteArray): ByteArray? {
        // Fresh 0-based buffer, mutated in place — nothing ever moves.
        val blob = tiff.copyOf()
        if (blob.size < 8) return null

        val little: Boolean = when (blob[0]) {
            0x49.toByte() -> if (blob[1] == 0x49.toByte()) true else return null // "II"
            0x4D.toByte() -> if (blob[1] == 0x4D.toByte()) false else return null // "MM"
            else -> return null
        }

        fun readU16(o: Int): Int? {
            if (o < 0 || o + 2 > blob.size) return null
            val b0 = blob[o].toInt() and 0xFF
            val b1 = blob[o + 1].toInt() and 0xFF
            return if (little) b0 or (b1 shl 8) else (b0 shl 8) or b1
        }

        fun readU32(o: Int): Long? {
            if (o < 0 || o + 4 > blob.size) return null
            val b0 = (blob[o].toLong() and 0xFF)
            val b1 = (blob[o + 1].toLong() and 0xFF)
            val b2 = (blob[o + 2].toLong() and 0xFF)
            val b3 = (blob[o + 3].toLong() and 0xFF)
            return if (little) {
                b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
        }

        fun writeU16(o: Int, value: Int) {
            if (little) {
                blob[o] = (value and 0xFF).toByte()
                blob[o + 1] = (value shr 8 and 0xFF).toByte()
            } else {
                blob[o] = (value shr 8 and 0xFF).toByte()
                blob[o + 1] = (value and 0xFF).toByte()
            }
        }

        fun writeU32(o: Int, value: Long) {
            if (little) {
                blob[o] = (value and 0xFF).toByte()
                blob[o + 1] = (value shr 8 and 0xFF).toByte()
                blob[o + 2] = (value shr 16 and 0xFF).toByte()
                blob[o + 3] = (value shr 24 and 0xFF).toByte()
            } else {
                blob[o] = (value shr 24 and 0xFF).toByte()
                blob[o + 1] = (value shr 16 and 0xFF).toByte()
                blob[o + 2] = (value shr 8 and 0xFF).toByte()
                blob[o + 3] = (value and 0xFF).toByte()
            }
        }

        fun zero(offset: Int, count: Int) {
            if (offset < 0 || count <= 0 || offset + count > blob.size) return
            blob.fill(0, offset, offset + count)
        }

        if (readU16(2) != 42) return null // TIFF magic
        val ifd0 = readU32(4)?.takeIf { it >= 8 } ?: return null
        val entryCount = readU16(ifd0.toInt())?.takeIf { it >= 0 } ?: return null
        if (ifd0 + 2 + 12 * entryCount + 4 > blob.size) return null

        for (index in 0 until entryCount) {
            val entry = ifd0 + 2 + 12 * index
            val tag = readU16(entry.toInt()) ?: return null
            if (tag != 0x8825) continue // GPS IFD pointer

            if (readU16(entry.toInt() + 2) != 4) return null // type LONG
            if (readU32(entry.toInt() + 4) != 1L) return null // count 1
            val gps = readU32(entry.toInt() + 8)?.takeIf { it >= 8 } ?: return null
            val gpsCount = readU16(gps.toInt())?.takeIf { it >= 0 } ?: return null
            if (gps + 2 + 12 * gpsCount + 4 > blob.size) return null

            // Zero every out-of-line value the GPS IFD references (lat/long rationals,
            // timestamps, processing method strings), then the directory itself.
            for (gi in 0 until gpsCount) {
                val ge = gps + 2 + 12 * gi
                val gType = readU16(ge.toInt() + 2) ?: return null
                val gCount = readU32(ge.toInt() + 4)?.takeIf { it <= Int.MAX_VALUE } ?: return null
                val size = typeSize(gType, count = gCount.toInt()) ?: return null
                if (size > 4) { // out-of-line: value field holds an offset
                    val valueOffset = readU32(ge.toInt() + 8) ?: return null
                    if (valueOffset + size > blob.size) return null
                    zero(valueOffset.toInt(), size)
                }
            }
            zero(gps.toInt(), 2 + 12 * gpsCount + 4)

            // Retag the pointer as a well-formed unknown entry so no reader follows
            // it as GPS — without deleting a single byte.
            writeU16(entry.toInt(), 0xFFFF)
            writeU16(entry.toInt() + 2, 4)
            writeU32(entry.toInt() + 4, 1L)
            writeU32(entry.toInt() + 8, 0L)
        }
        return blob
    }

    private fun typeSize(type: Int, count: Int): Int? {
        val unit = when (type) {
            1, 2, 6, 7 -> 1 // BYTE ASCII SBYTE UNDEFINED
            3, 8 -> 2 // SHORT SSHORT
            4, 9, 11 -> 4 // LONG SLONG FLOAT
            5, 10, 12 -> 8 // RATIONAL SRATIONAL DOUBLE
            else -> return null
        }
        // 64-bit intermediate like Swift's Int: a bogus huge count must NOT wrap
        // into a plausible small size — the caller's bounds check has to see it.
        val total = unit.toLong() * count
        if (total <= 0 || total > Int.MAX_VALUE) return null
        return total.toInt()
    }

    // MARK: - Helpers

    private fun containsXMP(bytes: ByteArray): Boolean =
        xmpPacketMarkers.any { indexOf(bytes, it) >= 0 }

    private fun matches(data: ByteArray, offset: Int, signature: ByteArray): Boolean {
        if (offset < 0 || offset + signature.size > data.size) return false
        for (i in signature.indices) {
            if (data[offset + i] != signature[i]) return false
        }
        return true
    }

    /** First index of [needle] in [haystack], or −1. */
    internal fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        if (needle.size > haystack.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
