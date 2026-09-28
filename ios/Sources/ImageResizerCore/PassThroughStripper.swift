import Foundation

/// Byte-level metadata stripping for the pass-through path — no re-encode, no pixel decode.
///
/// JPEG: walks the marker segments. XMP APP1 segments are dropped (segments are
/// self-delimiting, splicing is always safe); the EXIF GPS IFD is neutralized *in
/// place* — the IFD0 pointer entry is retagged as an unknown tag and every byte the
/// GPS IFD references is zeroed. Because nothing moves, no TIFF offset (MakerNote,
/// thumbnail, Exif SubIFD) can break.
///
/// Other containers: byte-copy only when there is provably nothing to strip.
/// Callers fall back to the re-encode pipeline otherwise (e.g. HEIC with GPS today) —
/// privacy is never traded for a free pass-through.
enum PassThroughStripper {
    enum Outcome {
        /// Nothing to strip; original bytes.
        case unchanged(Data)
        /// GPS/XMP removed; bytes are still a valid image of the same format.
        case stripped(Data)
        /// Cannot strip safely at byte level → caller must fall back to re-encoding.
        case unsupported
    }

    private static let exifSignature: [UInt8] = [0x45, 0x78, 0x69, 0x66, 0x00, 0x00] // "Exif\0\0"
    private static let xmpApp1Signature = Array("http://ns.adobe.com/xap/1.0/\0".utf8)
    private static let xmpExtensionSignature = Array("http://ns.adobe.com/xmp/extension/\0".utf8)
    /// XMP inside non-JPEG containers (PNG iTXt, WebP XMP chunk, HEIC mime item).
    private static let xmpPacketMarkers = [Data("<?xpacket".utf8), Data("<x:xmpmeta".utf8)]

    static func strip(bytes: Data, format: SourceFormat, gpsPresent: Bool) -> Outcome {
        if format == .jpeg {
            guard let out = stripJPEG(bytes) else { return .unsupported }
            return out == bytes ? .unchanged(bytes) : .stripped(out)
        }
        if gpsPresent || containsXMP(bytes) { return .unsupported }
        return .unchanged(bytes)
    }

    // MARK: - JPEG marker walk

    private static func stripJPEG(_ bytes: Data) -> Data? {
        guard bytes.count >= 4, bytes[0] == 0xFF, bytes[1] == 0xD8 else { return nil }
        var out = Data(capacity: bytes.count)
        out.append(bytes[0..<2]) // SOI
        var i = 2
        while i < bytes.count {
            guard bytes[i] == 0xFF else { return nil }
            var j = i
            while j < bytes.count, bytes[j] == 0xFF { j += 1 } // fill bytes
            guard j < bytes.count else { return nil }
            let marker = bytes[j]

            // Markers without a length field: TEM, RST, repeated SOI.
            if marker == 0x01 || marker == 0xD8 || (0xD0...0xD7).contains(marker) {
                out.append(bytes[i..<(j + 1)])
                i = j + 1
                continue
            }
            // EOI / SOS: everything from here is not segment-structured.
            if marker == 0xD9 || marker == 0xDA {
                out.append(bytes[i...])
                return out
            }
            guard j + 2 < bytes.count else { return nil }
            let segLen = Int(bytes[j + 1]) << 8 | Int(bytes[j + 2])
            guard segLen >= 2 else { return nil }
            let segEnd = j + 1 + segLen // the length field counts itself
            guard segEnd <= bytes.count else { return nil }

            if marker == 0xE1 { // APP1
                let payloadStart = j + 3
                let payloadCount = segEnd - payloadStart
                if payloadCount >= exifSignature.count,
                   matches(bytes, at: payloadStart, signature: exifSignature) {
                    guard let fixed = stripGPS(inTIFF: Data(bytes[(payloadStart + 6)..<segEnd]))
                    else { return nil }
                    // Keep the "Exif\0\0" signature; only the TIFF blob is rewritten.
                    out.append(bytes[i..<(payloadStart + 6)])
                    out.append(fixed)
                    i = segEnd
                    continue
                }
                if matches(bytes, at: payloadStart, signature: xmpApp1Signature)
                    || matches(bytes, at: payloadStart, signature: xmpExtensionSignature) {
                    i = segEnd // drop XMP APP1 entirely
                    continue
                }
            }
            out.append(bytes[i..<segEnd])
            i = segEnd
        }
        return out
    }

    // MARK: - EXIF GPS neutralization (strictly length-neutral)

    private static func stripGPS(inTIFF tiff: Data) -> Data? {
        // Normalize into a fresh 0-based buffer: slices carry offset indices.
        var blob = Data(capacity: tiff.count)
        blob.append(contentsOf: tiff)
        guard blob.count >= 8 else { return nil }

        let little: Bool
        switch (blob[0], blob[1]) {
        case (0x49, 0x49): little = true // "II"
        case (0x4D, 0x4D): little = false // "MM"
        default: return nil
        }
        func readU16(_ o: Int) -> Int? {
            guard o >= 0, o + 2 <= blob.count else { return nil }
            return little ? Int(blob[o]) | Int(blob[o + 1]) << 8
                          : Int(blob[o]) << 8 | Int(blob[o + 1])
        }
        func readU32(_ o: Int) -> Int? {
            guard o >= 0, o + 4 <= blob.count else { return nil }
            return little
                ? Int(blob[o]) | Int(blob[o + 1]) << 8 | Int(blob[o + 2]) << 16
                    | Int(blob[o + 3]) << 24
                : Int(blob[o]) << 24 | Int(blob[o + 1]) << 16 | Int(blob[o + 2]) << 8
                    | Int(blob[o + 3])
        }
        func writeU16(_ o: Int, _ value: Int) {
            if little {
                blob[o] = UInt8(value & 0xFF)
                blob[o + 1] = UInt8(value >> 8 & 0xFF)
            } else {
                blob[o] = UInt8(value >> 8 & 0xFF)
                blob[o + 1] = UInt8(value & 0xFF)
            }
        }
        func writeU32(_ o: Int, _ value: Int) {
            if little {
                blob[o] = UInt8(value & 0xFF)
                blob[o + 1] = UInt8(value >> 8 & 0xFF)
                blob[o + 2] = UInt8(value >> 16 & 0xFF)
                blob[o + 3] = UInt8(value >> 24 & 0xFF)
            } else {
                blob[o] = UInt8(value >> 24 & 0xFF)
                blob[o + 1] = UInt8(value >> 16 & 0xFF)
                blob[o + 2] = UInt8(value >> 8 & 0xFF)
                blob[o + 3] = UInt8(value & 0xFF)
            }
        }
        func zero(_ offset: Int, _ count: Int) {
            guard offset >= 0, count > 0, offset + count <= blob.count else { return }
            blob.replaceSubrange(offset..<(offset + count),
                                 with: repeatElement(UInt8(0), count: count))
        }

        guard readU16(2) == 42, // TIFF magic
              let ifd0 = readU32(4), ifd0 >= 8,
              let entryCount = readU16(ifd0),
              ifd0 + 2 + 12 * entryCount + 4 <= blob.count
        else { return nil }

        for index in 0..<entryCount {
            let entry = ifd0 + 2 + 12 * index
            guard let tag = readU16(entry) else { return nil }
            guard tag == 0x8825 else { continue } // GPS IFD pointer
            guard readU16(entry + 2) == 4, // type LONG
                  readU32(entry + 4) == 1, // count 1
                  let gps = readU32(entry + 8), gps >= 8,
                  let gpsCount = readU16(gps),
                  gps + 2 + 12 * gpsCount + 4 <= blob.count
            else { return nil }

            // Zero every out-of-line value the GPS IFD references (lat/long rationals,
            // timestamps, processing method strings), then the directory itself.
            for gi in 0..<gpsCount {
                let ge = gps + 2 + 12 * gi
                guard let gType = readU16(ge + 2), let gCount = readU32(ge + 4),
                      let size = typeSize(gType, count: gCount) else { return nil }
                if size > 4 { // out-of-line: value field holds an offset
                    guard let valueOffset = readU32(ge + 8),
                          valueOffset + size <= blob.count else { return nil }
                    zero(valueOffset, size)
                }
            }
            zero(gps, 2 + 12 * gpsCount + 4)

            // Retag the pointer as a well-formed unknown entry so no reader follows
            // it as GPS — without deleting a single byte.
            writeU16(entry, 0xFFFF)
            writeU16(entry + 2, 4)
            writeU32(entry + 4, 1)
            writeU32(entry + 8, 0)
        }
        return blob
    }

    private static func typeSize(_ type: Int, count: Int) -> Int? {
        let unit: Int
        switch type {
        case 1, 2, 6, 7: unit = 1 // BYTE ASCII SBYTE UNDEFINED
        case 3, 8: unit = 2 // SHORT SSHORT
        case 4, 9, 11: unit = 4 // LONG SLONG FLOAT
        case 5, 10, 12: unit = 8 // RATIONAL SRATIONAL DOUBLE
        default: return nil
        }
        let total = unit * count
        return total > 0 ? total : nil
    }

    // MARK: - Helpers

    private static func containsXMP(_ bytes: Data) -> Bool {
        xmpPacketMarkers.contains { bytes.range(of: $0) != nil }
    }

    private static func matches(_ data: Data, at offset: Int, signature: [UInt8]) -> Bool {
        guard offset >= 0, offset + signature.count <= data.count else { return false }
        for (i, byte) in signature.enumerated() where data[offset + i] != byte {
            return false
        }
        return true
    }
}
