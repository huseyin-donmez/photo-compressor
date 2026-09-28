import Foundation

/// An already-decoded (and dimension-capped) image held for encoding.
public protocol ResizableImage: AnyObject {
    var pixelWidth: Int { get }
    var pixelHeight: Int { get }
}

/// Seam between the pure algorithm and a platform codec.
/// One session processes exactly one input file.
public protocol ResizeSession: AnyObject {
    /// Header-only read: dimensions, format, alpha, file size. Must not decode pixels.
    func readHeader() throws -> ImageHeader

    /// Re-wrap the original encoded bytes with sensitive metadata stripped,
    /// WITHOUT re-encoding. Return `nil` when the platform cannot do this
    /// (caller then falls back to the normal re-encode pipeline).
    func passThroughStripMetadata() throws -> Data?

    /// Bounds-decoded image, longest edge ≤ `longestEdge`, EXIF orientation baked in.
    func decode(longestEdge: Int) throws -> any ResizableImage

    /// Encode at `quality` (0…1) AND merge preserved (GPS-stripped) metadata.
    /// The returned bytes are final: what the search measures is what gets written.
    func encodeFinal(_ image: any ResizableImage, quality: Double) throws -> Data
}
