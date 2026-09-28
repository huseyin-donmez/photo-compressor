import Foundation

/// Format of the source file as identified from container metadata (no decode).
public enum SourceFormat: String, Sendable, Equatable, CaseIterable {
    case jpeg
    case png
    case heic
    case webp
    case gif
    case bmp
    case tiff
    case raw
    case other
}

/// Container the app is allowed to write. Decided automatically, never by the user.
public enum OutputFormat: String, Sendable, Equatable, CaseIterable {
    case jpeg
    case png
    case heic
    case webp
}

/// Header-only description of an input file. Reading this must never decode pixels.
public struct ImageHeader: Sendable, Equatable {
    public let pixelWidth: Int
    public let pixelHeight: Int
    public let format: SourceFormat
    public let hasAlpha: Bool
    public let byteSize: Int64

    public init(pixelWidth: Int, pixelHeight: Int, format: SourceFormat,
                hasAlpha: Bool, byteSize: Int64) {
        self.pixelWidth = pixelWidth
        self.pixelHeight = pixelHeight
        self.format = format
        self.hasAlpha = hasAlpha
        self.byteSize = byteSize
    }

    public var pixelCount: Int64 { Int64(pixelWidth) * Int64(pixelHeight) }
}

/// Error taxonomy — see docs/ALGORITHM.md. A usage credit is consumed only on success.
public enum ResizeError: Error, Sendable, Equatable {
    case unsupportedFormat(SourceFormat)
    case corrupted
    case outOfMemory
    case storageFull
    case encodeFailed
    case outputSaveFailed
    case targetExceeded(measured: Int64, target: Int64)
    case targetInfeasible
    case cancelled
}

public struct ResizeReport: Sendable, Equatable {
    public enum Outcome: String, Sendable, Equatable {
        /// Input was already ≤ target; original container preserved (GPS stripped). No credit.
        case passThrough
        /// Input was re-encoded to meet the target. Consumes exactly one credit.
        case processed
    }

    public let outcome: Outcome
    /// `nil` for pass-through (original container preserved as-is).
    public let outputFormat: OutputFormat?
    public let bytes: Int64
    public let pixelWidth: Int
    public let pixelHeight: Int
    /// Quality used for the winning encode; `nil` for pass-through.
    public let quality: Double?
    public let encodesUsed: Int
    public let levelsUsed: Int

    public init(outcome: Outcome, outputFormat: OutputFormat?, bytes: Int64,
                pixelWidth: Int, pixelHeight: Int, quality: Double?,
                encodesUsed: Int, levelsUsed: Int) {
        self.outcome = outcome
        self.outputFormat = outputFormat
        self.bytes = bytes
        self.pixelWidth = pixelWidth
        self.pixelHeight = pixelHeight
        self.quality = quality
        self.encodesUsed = encodesUsed
        self.levelsUsed = levelsUsed
    }
}

public struct ResizeResult: Sendable {
    public let report: ResizeReport
    public let data: Data

    public init(report: ResizeReport, data: Data) {
        self.report = report
        self.data = data
    }
}
