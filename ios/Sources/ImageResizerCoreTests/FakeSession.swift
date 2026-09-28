import Foundation
import ImageResizerCore

/// Deterministic in-memory codec for pure algorithm tests.
/// Size model: `bytes = pixels × (floor + slope × q²)` — monotonic in quality,
/// linear in pixel count, like a real codec.
final class FakeSession: ResizeSession {
    struct Config {
        var width: Int
        var height: Int
        var format: SourceFormat = .jpeg
        var hasAlpha: Bool = false
        var byteSize: Int64
        /// Bytes per pixel at quality 0 and the quality-squared slope.
        var modelFloor: Double = 0.05
        var modelSlope: Double = 0.55
        /// PNG-like codecs have no quality axis.
        var qualityAffectsSize: Bool = true
        /// What the metadata-only re-wrap returns (nil = platform cannot re-wrap).
        var passThroughData: Data? = nil
        var passThroughAvailable: Bool = true
    }

    let config: Config
    private(set) var encodeCount = 0
    private(set) var decodeCount = 0
    private(set) var passThroughCalls = 0
    private(set) var decodedLongestEdges: [Int] = []

    init(_ config: Config) { self.config = config }

    func readHeader() throws -> ImageHeader {
        ImageHeader(pixelWidth: config.width, pixelHeight: config.height,
                    format: config.format, hasAlpha: config.hasAlpha,
                    byteSize: config.byteSize)
    }

    func passThroughStripMetadata() throws -> Data? {
        passThroughCalls += 1
        guard config.passThroughAvailable else { return nil }
        return config.passThroughData ?? Data(count: Int(config.byteSize))
    }

    func decode(longestEdge: Int) throws -> any ResizableImage {
        decodeCount += 1
        decodedLongestEdges.append(longestEdge)
        let longest = Double(max(config.width, config.height))
        let scale = min(1.0, Double(longestEdge) / longest)
        let w = max(1, Int((Double(config.width) * scale).rounded()))
        let h = max(1, Int((Double(config.height) * scale).rounded()))
        return FakeImage(width: w, height: h)
    }

    func encodeFinal(_ image: any ResizableImage, quality: Double) throws -> Data {
        encodeCount += 1
        let pixels = Double(image.pixelWidth) * Double(image.pixelHeight)
        let q = config.qualityAffectsSize ? quality : 1.0
        let bytesPerPixel = config.modelFloor + config.modelSlope * q * q
        return Data(count: max(1, Int(pixels * bytesPerPixel)))
    }

    /// Expected size under the model (for assertions).
    func modelBytes(width: Int, height: Int, quality: Double) -> Int {
        let q = config.qualityAffectsSize ? quality : 1.0
        let bpp = config.modelFloor + config.modelSlope * q * q
        return Int(Double(width) * Double(height) * bpp)
    }
}

final class FakeImage: ResizableImage {
    let pixelWidth: Int
    let pixelHeight: Int
    init(width: Int, height: Int) {
        self.pixelWidth = width
        self.pixelHeight = height
    }
}
