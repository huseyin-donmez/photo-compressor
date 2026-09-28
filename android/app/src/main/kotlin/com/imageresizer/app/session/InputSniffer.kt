package com.imageresizer.app.session

import com.imageresizer.core.SourceFormat
import java.io.File

/**
 * Header-only format detection + alpha detection from container bytes.
 * Mirrors ios/ImageResizerCore/ImageIOResizeSession (magic bytes / byte parsers —
 * the Android equivalent of ImageIO's type + property reads).
 *
 * Bias: when unsure about alpha, prefer "has alpha" → lossless PNG output.
 */
object InputSniffer {

    // MARK: - Format (magic bytes, ≤ 64 bytes of head)

    fun format(file: File): SourceFormat {
        val head = file.inputStream().use { input ->
            val buffer = ByteArray(64)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
        return sniff(head)
    }

    fun sniff(b: ByteArray): SourceFormat {
        if (b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) {
            return SourceFormat.JPEG // SOI
        }
        if (b.size >= 8 && b.copyOfRange(0, 8).contentEquals(pngSignature)) return SourceFormat.PNG
        if (b.size >= 6 && (startsWithAscii(b, "GIF87a") || startsWithAscii(b, "GIF89a"))) {
            return SourceFormat.GIF
        }
        if (b.size >= 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()) return SourceFormat.BMP
        if (b.size >= 12 && startsWithAscii(b, "RIFF") && asciiAt(b, 8, "WEBP")) return SourceFormat.WEBP
        if (b.size >= 16 && asciiAt(b, 4, "ftyp")) return heifFamily(b)
        if (b.size >= 4 &&
            ((b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte() &&
                b[2] == 0x2A.toByte() && b[3] == 0.toByte()) ||
                (b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte() &&
                    b[2] == 0.toByte() && b[3] == 0x2A.toByte()))
        ) {
            // TIFF-family. Canon RAW (CR2) nests "CR" at offset 8; both TIFF and RAW are
            // unsupported (docs/ALGORITHM.md matrix), the label is only for reporting.
            return if (b.size >= 10 && asciiAt(b, 8, "CR")) SourceFormat.RAW else SourceFormat.TIFF
        }
        return SourceFormat.OTHER // avif, pdf, movie, garbage → unsupported
    }

    /** ftyp box: brands 8..11 (major) then compatible brands from 16, 4 bytes each. */
    private fun heifFamily(b: ByteArray): SourceFormat {
        var hasAvif = false
        var hasHeic = false
        var offset = 8
        while (offset + 4 <= b.size) {
            val brand = brandAt(b, offset)
            if (brand == "avif" || brand == "avis") hasAvif = true
            if (brand == "heic" || brand == "heix" || brand == "hevc" || brand == "hevx" ||
                brand == "heim" || brand == "heis" || brand == "mif1" || brand == "msf1"
            ) hasHeic = true
            offset += 4
        }
        // AVIF shares the generic "mif1" brand with HEIF — AVIF must never map to HEIC
        // (it is unsupported, not convertible to HEIC on Android either).
        return when {
            hasAvif -> SourceFormat.OTHER
            hasHeic -> SourceFormat.HEIC
            else -> SourceFormat.OTHER
        }
    }

    private fun brandAt(b: ByteArray, offset: Int): String =
        if (offset + 4 <= b.size) String(b, offset, 4, Charsets.US_ASCII) else ""

    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private fun startsWithAscii(b: ByteArray, s: String): Boolean = asciiAt(b, 0, s)

    private fun asciiAt(b: ByteArray, offset: Int, s: String): Boolean {
        if (offset + s.length > b.size) return false
        for (i in s.indices) if (b[offset + i] != s[i].code.toByte()) return false
        return true
    }

    // MARK: - Alpha (container bytes are authoritative)

    fun hasAlpha(file: File, format: SourceFormat): Boolean = when (format) {
        // Format-specific byte parsers are authoritative — decoders over-report
        // GIF alpha and never expose a simple flag for PNG/WebP headers.
        SourceFormat.PNG -> pngHasAlpha(file.readBytes())
        SourceFormat.WEBP -> webpHasAlpha(file.readBytes())
        SourceFormat.GIF -> gifHasAlpha(file.readBytes())
        else -> false // JPEG/BMP have none; Android's matrix ignores alpha for HEIC/WebP
    }

    /// PNG: IHDR color type (+ tRNS scan for palettes). Correct by construction.
    fun pngHasAlpha(data: ByteArray): Boolean {
        if (data.size <= 33) return false
        if (!data.copyOfRange(0, 8).contentEquals(pngSignature)) return false

        // signature(8) + len(4) + "IHDR"(4) + width(4) + height(4) + bitDepth(1)
        val colorType = data[25].toInt() and 0xFF
        if (colorType == 4 || colorType == 6) return true
        if (colorType != 3) return false // 0/2: no alpha

        // Palette: transparency lives in an optional tRNS chunk before IDAT.
        val idat = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte())
        val trns = byteArrayOf('t'.code.toByte(), 'R'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte())
        var offset = 8
        while (offset + 8 <= data.size) {
            var length = 0
            for (i in 0..3) length = length shl 8 or (data[offset + i].toInt() and 0xFF)
            if (length < 0) return false
            val type = data.copyOfRange(offset + 4, offset + 8)
            if (type.contentEquals(idat)) return false
            if (type.contentEquals(trns)) return true
            offset += 12 + length
        }
        return false
    }

    /// WebP: VP8X alpha flag (0x10), or VP8L alpha-is-used bit.
    fun webpHasAlpha(data: ByteArray): Boolean {
        if (data.size <= 20) return false
        if (!asciiAt(data, 0, "RIFF") || !asciiAt(data, 8, "WEBP")) return false

        var offset = 12
        while (offset + 8 <= data.size) {
            if (asciiAt(data, offset, "VP8X")) { // flags byte, alpha = 0x10
                return (data[offset + 8].toInt() and 0x10) != 0
            }
            if (asciiAt(data, offset, "VP8L")) { // bit 28 of the 32-bit header
                if (offset + 12 > data.size || data[offset + 8] != 0x2F.toByte()) return false
                val bits = (data[offset + 9].toInt() and 0xFF) or
                    (data[offset + 10].toInt() and 0xFF) shl 8 or
                    (data[offset + 11].toInt() and 0xFF) shl 16 or
                    (data[offset + 12].toInt() and 0xFF) shl 24
                return ((bits ushr 28) and 1) == 1
            }
            if (asciiAt(data, offset, "VP8 ")) return false // lossy: no alpha
            var length = 0
            for (i in 4..7) length = length shl 8 or (data[offset + i].toInt() and 0xFF)
            if (length < 0) return false
            offset += 8 + length + (length and 1) // chunks are even-padded
        }
        return false
    }

    /// GIF: walk the container and read the transparent-color flag of the GCE
    /// preceding the first frame (decoders report alpha for every GIF).
    /// Unsure → true (safe guess: lossless PNG output).
    fun gifHasAlpha(data: ByteArray): Boolean {
        if (data.size < 14) return true
        if (!asciiAt(data, 0, "GIF8")) return true

        var offset = 13
        val packed = data[10].toInt() and 0xFF
        if (packed and 0x80 != 0) { // global color table present
            offset += 3 * (1 shl ((packed and 0x07) + 1))
        }

        var transparent = false
        while (offset < data.size) {
            when (data[offset].toInt() and 0xFF) {
                0x21 -> { // extension block
                    if (offset + 2 >= data.size) return true
                    if (data[offset + 1].toInt() and 0xFF == 0xF9) { // graphic control extension
                        // 0x21 0xF9 <len> <packed> <delay×2> <tIdx> 0x00
                        if (offset + 7 >= data.size || data[offset + 2].toInt() < 4) return true
                        transparent = (data[offset + 3].toInt() and 0x01) != 0
                    }
                    // skip sub-blocks until terminator
                    offset += 2
                    while (offset < data.size && data[offset] != 0.toByte()) {
                        offset += 1 + (data[offset].toInt() and 0xFF)
                    }
                    offset += 1
                }
                0x2C -> return transparent // image descriptor → first frame reached
                0x3B -> return true // trailer before any frame
                else -> return true // unknown structure → safe guess
            }
        }
        return true
    }
}
