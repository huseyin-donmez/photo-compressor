import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// The production codec: header-only reads, bounds decoding with orientation baked in,
/// native JPEG/PNG/HEIC encoding, byte-level GPS stripping for pass-through.
/// Zero third-party code.
public final class ImageIOResizeSession: ResizeSession {
    private let url: URL
    private let byteSize: Int64
    private let source: CGImageSource?

    private var cachedHeader: ImageHeader?
    private var cachedProperties: [CFString: Any]?

    public init(url: URL) {
        self.url = url
        self.source = CGImageSourceCreateWithURL(
            url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary)
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize)
            .map(Int64.init) ?? 0
        self.byteSize = size
    }

    // MARK: - Header (no pixel decode)

    public func readHeader() throws -> ImageHeader {
        if let cachedHeader { return cachedHeader }
        guard let source else { throw ResizeError.corrupted }
        guard let type = CGImageSourceGetType(source),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil)
                as? [CFString: Any] else {
            throw ResizeError.corrupted
        }
        let width = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue ?? 0
        let height = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue ?? 0
        guard width > 0, height > 0 else { throw ResizeError.corrupted }

        let format = Self.sourceFormat(type)
        let alpha = hasAlpha(properties: props, format: format)

        let header = ImageHeader(pixelWidth: width, pixelHeight: height,
                                 format: format, hasAlpha: alpha, byteSize: byteSize)
        cachedHeader = header
        cachedProperties = props
        return header
    }

    // MARK: - Pass-through (no re-encode)

    public func passThroughStripMetadata() throws -> Data? {
        guard source != nil else { throw ResizeError.corrupted }
        let header = try readHeader()
        let gpsPresent = (cachedProperties?[kCGImagePropertyGPSDictionary] != nil)
        guard let bytes = try? Data(contentsOf: url, options: .mappedIfSafe),
              Int64(bytes.count) == header.byteSize else {
            return nil
        }

        switch PassThroughStripper.strip(bytes: bytes, format: header.format,
                                         gpsPresent: gpsPresent) {
        case .unsupported:
            return nil // → caller re-encodes, which strips GPS for sure
        case .unchanged(let data):
            // GPS reported but nothing strippable at byte level → pipeline decides.
            return gpsPresent ? nil : data
        case .stripped(let data):
            return Self.strippedIsClean(data) ? data : nil
        }
    }

    /// Fail-safe for byte surgery: the result must still parse and must expose no GPS.
    private static func strippedIsClean(_ data: Data) -> Bool {
        guard let src = CGImageSourceCreateWithData(
                data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil)
                as? [CFString: Any],
              let width = props[kCGImagePropertyPixelWidth] as? NSNumber,
              width.intValue > 0 else {
            return false
        }
        return props[kCGImagePropertyGPSDictionary] == nil
    }

    // MARK: - Bounds decode

    public func decode(longestEdge: Int) throws -> any ResizableImage {
        guard let source else { throw ResizeError.corrupted }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            // Bakes EXIF orientation into the pixels — output is always upright.
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, longestEdge),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(
            source, 0, options as CFDictionary) else {
            throw ResizeError.corrupted
        }
        return ImageIOImage(cgImage: cgImage)
    }

    // MARK: - Encode

    public func encodeFinal(_ image: any ResizableImage, quality: Double) throws -> Data {
        guard let image = image as? ImageIOImage else { throw ResizeError.encodeFailed }
        let header = try readHeader()
        guard let format = FormatStrategy.outputFormat(source: header.format,
                                                       hasAlpha: header.hasAlpha) else {
            throw ResizeError.unsupportedFormat(header.format)
        }
        let uti = Self.identifier(for: format)

        var props = MetadataPolicy.encodeProperties(original: cachedProperties,
                                                    outputWidth: image.pixelWidth,
                                                    outputHeight: image.pixelHeight)
        if format != .png {
            // PNG is lossless; the quality axis does not apply.
            props[kCGImageDestinationLossyCompressionQuality] = min(max(quality, 0), 1)
        }

        if let data = Self.encode(image: image.cgImage, uti: uti, properties: props) {
            return data
        }
        // Metadata conflict fallbacks: fewer properties, then bare pixels.
        if let data = Self.encode(
            image: image.cgImage, uti: uti,
            properties: [kCGImagePropertyTIFFDictionary:
                            [kCGImagePropertyTIFFSoftware:
                                MetadataPolicy.softwareTag] as [CFString: Any]]) {
            return data
        }
        if let data = Self.encode(image: image.cgImage, uti: uti, properties: [:]) {
            return data
        }
        throw ResizeError.encodeFailed
    }

    private static func encode(image: CGImage, uti: String,
                               properties: [CFString: Any]) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, uti as CFString, 1, nil) else {
            return nil
        }
        CGImageDestinationAddImage(dest, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Data
    }

    // MARK: - Type mapping

    static func sourceFormat(_ type: CFString) -> SourceFormat {
        switch type as String {
        case UTType.jpeg.identifier: return .jpeg
        case UTType.png.identifier: return .png
        case UTType.heic.identifier, "public.heif", "public.heics": return .heic
        case UTType.webP.identifier: return .webp
        case UTType.gif.identifier: return .gif
        case UTType.bmp.identifier: return .bmp
        case UTType.tiff.identifier: return .tiff
        default: return .other // avif, raw, pdf, movie, unknown → unsupported
        }
    }

    static func identifier(for format: OutputFormat) -> String {
        switch format {
        case .jpeg: return UTType.jpeg.identifier
        case .png: return UTType.png.identifier
        case .heic: return UTType.heic.identifier
        case .webp: return UTType.webP.identifier
        }
    }

    // MARK: - Alpha detection (bias: when unsure, prefer "has alpha" → PNG output)

    private func hasAlpha(properties: [CFString: Any], format: SourceFormat) -> Bool {
        // Format-specific byte parsers are authoritative where ImageIO reports a
        // default instead of the container's real flag (GIF always claims alpha).
        switch format {
        case .png: return Self.pngHasAlpha(at: url)
        case .webp: return Self.webpHasAlpha(at: url)
        case .gif: return Self.gifHasAlpha(at: url)
        default: break
        }
        if let alpha = properties[kCGImagePropertyHasAlpha] as? Bool {
            return alpha
        }
        return false // JPEG has none; a transparent HEIC still re-encodes to HEIC
    }

    /// PNG fallback: IHDR color type (+ tRNS scan for palettes). Correct by construction,
    /// unlike guessing from missing properties.
    private static func pngHasAlpha(at url: URL) -> Bool {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe),
              data.count > 33 else { return false }
        let signature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
        guard (0..<8).allSatisfy({ data[$0] == signature[$0] }) else { return false }

        // signature(8) + len(4) + "IHDR"(4) + width(4) + height(4) + bitDepth(1)
        let colorType = data[25]
        if colorType == 4 || colorType == 6 { return true }
        guard colorType == 3 else { return false } // 0/2: no alpha

        // Palette: transparency lives in an optional tRNS chunk before IDAT.
        let idat: [UInt8] = [0x49, 0x44, 0x41, 0x54]
        let trns: [UInt8] = [0x74, 0x52, 0x4E, 0x53]
        var offset = 8
        while offset + 8 <= data.count {
            let length = Int(data[offset]) << 24 | Int(data[offset + 1]) << 16
                | Int(data[offset + 2]) << 8 | Int(data[offset + 3])
            guard length >= 0 else { return false }
            let type = [UInt8](data[(offset + 4)..<(offset + 8)])
            if type == idat { return false }
            if type == trns { return true }
            offset += 12 + length
        }
        return false
    }

    /// WebP fallback: VP8X alpha flag (0x10), or VP8L alpha-is-used bit.
    private static func webpHasAlpha(at url: URL) -> Bool {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe),
              data.count > 20 else { return false }
        guard data[0] == 0x52, data[1] == 0x49, data[2] == 0x46, data[3] == 0x46, // RIFF
              data[8] == 0x57, data[9] == 0x45, data[10] == 0x42, data[11] == 0x50 // WEBP
        else { return false }

        var offset = 12
        while offset + 8 <= data.count {
            let tag = [UInt8](data[offset..<(offset + 4)])
            if tag == [0x56, 0x50, 0x38, 0x58] { // VP8X: flags byte, alpha = 0x10
                let flags = data[offset + 8]
                return flags & 0x10 != 0
            }
            if tag == [0x56, 0x50, 0x38, 0x4C] { // VP8L: bit 28 of the 32-bit header
                guard offset + 12 <= data.count, data[offset + 8] == 0x2F else {
                    return false
                }
                let bits = UInt32(data[offset + 9]) | UInt32(data[offset + 10]) << 8
                    | UInt32(data[offset + 11]) << 16 | UInt32(data[offset + 12]) << 24
                return bits >> 28 & 1 == 1
            }
            if tag == [0x56, 0x50, 0x38, 0x20] { return false } // VP8 (lossy): no alpha
            let length = Int(data[offset + 4]) | Int(data[offset + 5]) << 8
                | Int(data[offset + 6]) << 16 | Int(data[offset + 7]) << 24
            guard length >= 0 else { return false }
            offset += 8 + length + (length & 1) // chunks are even-padded
        }
        return false
    }

    /// GIF fallback: ImageIO reports has-alpha for every GIF, so walk the container
    /// and read the transparent-color flag of the GCE preceding the first frame.
    private static func gifHasAlpha(at url: URL) -> Bool {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe),
              data.count >= 14 else { return true } // unsure → safe guess (PNG)
        // "GIF87a"/"GIF89a" (6) + logical screen descriptor (7) → block stream.
        guard data[0] == 0x47, data[1] == 0x49, data[2] == 0x46 else { return true }

        var offset = 13
        let packed = data[10]
        if packed & 0x80 != 0 { // global color table present
            offset += 3 * (1 << ((packed & 0x07) + 1))
        }

        var transparent = false
        while offset < data.count {
            switch data[offset] {
            case 0x21: // extension block
                guard offset + 2 < data.count else { return true }
                if data[offset + 1] == 0xF9 { // graphic control extension
                    // 0x21 0xF9 <len> <packed> <delay×2> <tIdx> 0x00
                    guard offset + 7 < data.count, data[offset + 2] >= 4 else { return true }
                    transparent = data[offset + 3] & 0x01 != 0
                }
                // skip sub-blocks until terminator
                offset += 2
                while offset < data.count, data[offset] != 0 {
                    offset += 1 + Int(data[offset])
                }
                offset += 1
            case 0x2C: // image descriptor → first frame reached
                return transparent
            case 0x3B: // trailer before any frame
                return true
            default:
                return true // unknown structure → safe guess
            }
        }
        return true
    }
}

final class ImageIOImage: ResizableImage {
    let cgImage: CGImage
    init(cgImage: CGImage) { self.cgImage = cgImage }
    var pixelWidth: Int { cgImage.width }
    var pixelHeight: Int { cgImage.height }
}
