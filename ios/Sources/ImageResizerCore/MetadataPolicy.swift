import Foundation
import ImageIO

/// GPS/location is ALWAYS stripped. Orientation is baked into pixels at decode time,
/// so encoded files carry no (or upright) orientation. Everything else useful is kept.
public enum MetadataPolicy {
    public static let softwareTag = "ImageResizer 1.0"

    /// ImageIO exposes "Software" as a TIFF tag (0x0131), not a top-level key.
    private static func softwareMerged(into tiff: [CFString: Any]?) -> [CFString: Any] {
        var dict = tiff ?? [:]
        dict[kCGImagePropertyTIFFSoftware] = softwareTag
        return dict
    }

    /// Metadata for a fresh encode. Pixels already carry the correct orientation
    /// and the destination embeds the image's color profile by itself.
    static func encodeProperties(original: [CFString: Any]?,
                                 outputWidth: Int,
                                 outputHeight: Int) -> [CFString: Any] {
        var out: [CFString: Any] = [:]
        guard let original else {
            out[kCGImagePropertyTIFFDictionary] = softwareMerged(into: nil)
            return out
        }

        if var exif = original[kCGImagePropertyExifDictionary] as? [CFString: Any] {
            exif.removeValue(forKey: "Orientation" as CFString)
            exif[kCGImagePropertyExifPixelXDimension] = outputWidth
            exif[kCGImagePropertyExifPixelYDimension] = outputHeight
            out[kCGImagePropertyExifDictionary] = exif
        }
        var tiff = original[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
        tiff.removeValue(forKey: kCGImagePropertyTIFFOrientation)
        out[kCGImagePropertyTIFFDictionary] = softwareMerged(into: tiff)
        // Top-level Orientation intentionally omitted → readers see upright.
        return out
    }
}
