import Foundation

/// Automatic input→output format rules (iOS side of docs/ALGORITHM.md).
/// No user-facing format option exists anywhere.
public enum FormatStrategy {
    /// Returns `nil` when the input format is unsupported (skip item, no credit).
    public static func outputFormat(source: SourceFormat, hasAlpha: Bool) -> OutputFormat? {
        switch source {
        case .jpeg:
            return .jpeg
        case .png:
            // Alpha must stay lossless (PNG). No-alpha photo PNGs convert to JPEG to
            // preserve resolution — priority #1.
            return hasAlpha ? .png : .jpeg
        case .heic:
            return .heic
        case .webp:
            // iOS has no WebP encoder (libwebp rejected): preserve alpha via PNG.
            return hasAlpha ? .png : .jpeg
        case .gif, .bmp:
            return hasAlpha ? .png : .jpeg
        case .tiff, .raw, .other:
            return nil
        }
    }
}
