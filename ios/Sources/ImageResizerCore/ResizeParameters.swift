import Foundation

/// The entire numeric contract of the resize algorithm.
/// MUST stay identical to `docs/ALGORITHM.md` and the Android `ResizeParameters`.
public struct ResizeParameters: Sendable, Equatable {
    /// Encoded bytes must be ≤ `target × safetyMargin`.
    public var safetyMargin: Double = 0.97
    /// First quality tried.
    public var initialQuality: Double = 0.80
    /// Never search above this.
    public var qualityCeiling: Double = 0.95
    /// Preferred quality floor; below this we reduce resolution instead.
    public var qualityFloor: Double = 0.30
    /// Used only at the resolution floor, then in the forced-fit path.
    public var absoluteQualityFloor: Double = 0.10
    /// Stop the bisection when `hi − lo ≤ tolerance`.
    public var qualityTolerance: Double = 0.03
    /// Bisection iterations per resolution level (≤ 24 encodes/image on normal paths).
    public var maxBisectIterations: Int = 5
    /// First reduction ratio bounds: `clamp(√(targetEnc/sizeAtFloor) × 0.9, min, max)`.
    public var firstReductionMin: Double = 0.40
    public var firstReductionMax: Double = 0.85
    public var firstReductionSafety: Double = 0.9
    /// Subsequent reduction steps multiply the current scale by this.
    public var subsequentReductionFactor: Double = 0.70
    /// Voluntary reduction stops at the resolution floor:
    /// longest edge ≥ `floorLongestEdge` AND min edge ≥ `floorMinEdge`.
    public var floorLongestEdge: Int = 1024
    public var floorMinEdge: Int = 512
    /// Hard floor for the forced-fit path.
    public var absoluteMinEdge: Int = 64
    /// Forced-fit: scale ×0.75 per attempt, ≤ this many attempts.
    public var forcedFitStep: Double = 0.75
    public var maxForcedFitAttempts: Int = 8
    /// Decode-time memory cap: pixels will never exceed this (bounds-decode).
    public var maxDecodePixels: Int = 60_000_000
    /// Safety valve for the resolution loop.
    public var maxLevels: Int = 8

    public init() {}

    public static let standard = ResizeParameters()

    /// Scale applied to full resolution so decoding fits the memory budget.
    public func memoryScale(width: Int, height: Int) -> Double {
        let pixels = Double(width) * Double(height)
        let cap = Double(maxDecodePixels)
        guard pixels > cap else { return 1.0 }
        return (cap / pixels).squareRoot()
    }

    /// Largest scale that still satisfies BOTH floor constraints (≤ 1).
    public func resolutionFloorScale(width: Int, height: Int) -> Double {
        let longest = Double(max(width, height))
        let shortest = Double(min(width, height))
        guard longest > 0, shortest > 0 else { return 1.0 }
        let byLongest = Double(floorLongestEdge) / longest
        let byShortest = Double(floorMinEdge) / shortest
        return min(1.0, max(byLongest, byShortest))
    }

    public func targetWithMargin(_ targetBytes: Int64) -> Int64 {
        Int64((Double(targetBytes) * safetyMargin).rounded(.down))
    }
}

/// Product-level constants (no backend, no settings surface).
public enum ProductRules {
    public static let initialFreeCredits = 5
    public static let rewardedAdGrantCount = 5
    public static let minimumTargetBytes: Int64 = 100 * 1024
}
