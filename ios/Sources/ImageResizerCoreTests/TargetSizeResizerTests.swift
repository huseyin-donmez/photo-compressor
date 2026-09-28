import Foundation
import ImageResizerCore

private func runResizer(_ session: FakeSession, target: Int64,
                        params: ResizeParameters = .standard) throws -> ResizeResult {
    try TargetSizeResizer(parameters: params).run(session: session, targetBytes: target)
}

// MARK: - Pass-through

func testPassThroughWithoutEncoding() throws {
    let session = FakeSession(.init(width: 4000, height: 3000, byteSize: 5_000,
                                    passThroughData: Data(count: 5_000)))
    let result = try runResizer(session, target: 100_000)
    XCTAssertEqual(result.report.outcome, .passThrough)
    XCTAssertEqual(session.encodeCount, 0, "pass-through must not re-encode")
    XCTAssertEqual(result.report.encodesUsed, 0)
    XCTAssertNil(result.report.outputFormat, "original container is preserved")
    XCTAssertEqual(result.report.bytes, 5_000)
}

func testPassThroughGrewBeyondTargetFallsBackToEncode() throws {
    // Rewrap adding metadata could exceed the target → must fall into the pipeline.
    let session = FakeSession(.init(width: 4000, height: 3000, byteSize: 100_000,
                                    passThroughData: Data(count: 100_001)))
    let result = try runResizer(session, target: 100_000)
    XCTAssertEqual(result.report.outcome, .processed)
    XCTAssertGreaterThan(session.encodeCount, 0)
    XCTAssertLessThanOrEqual(result.report.bytes, 100_000)
}

func testPassThroughUnavailableFallsBackToEncode() throws {
    var config = FakeSession.Config(width: 4000, height: 3000, byteSize: 90_000)
    config.passThroughAvailable = false
    let session = FakeSession(config)
    let result = try runResizer(session, target: 100_000)
    XCTAssertEqual(result.report.outcome, .processed)
    XCTAssertLessThanOrEqual(result.report.bytes, 100_000)
}

// MARK: - Quality search

func testQualitySearchFindsHighestFeasibleQuality() throws {
    let session = FakeSession(.init(width: 1000, height: 800, byteSize: 10_000_000))
    // Target ≈ what quality 0.60 produces at full resolution.
    let target = Int64(session.modelBytes(width: 1000, height: 800, quality: 0.60))
    let result = try runResizer(session, target: target)

    XCTAssertEqual(result.report.outcome, .processed)
    XCTAssertLessThanOrEqual(result.report.bytes, target)
    guard let quality = result.report.quality else {
        return XCTFail("processed result must report quality")
    }
    // Within the 0.03 tolerance of the true optimum, never below the floor.
    XCTAssertLessThanOrEqual(quality, 0.60 + 0.031)
    XCTAssertGreaterThanOrEqual(quality, 0.30)
    // Resolution fully preserved — reduction only happens if quality fails.
    XCTAssertEqual(result.report.pixelWidth, 1000)
    XCTAssertEqual(result.report.pixelHeight, 800)
    XCTAssertLessThanOrEqual(result.report.encodesUsed, 5)
}

// MARK: - Resolution reduction

func testResolutionReducedWhenQualityFloorCannotReachTarget() throws {
    // Full-res bytes at the quality floor (~0.10×pixels) vastly exceed target,
    // but a ~0.4× reduction at floor quality would satisfy it → controlled step.
    let session = FakeSession(.init(width: 4000, height: 3000, byteSize: 40_000_000))
    let target: Int64 = 300_000
    let result = try runResizer(session, target: target)

    XCTAssertEqual(result.report.outcome, .processed)
    XCTAssertLessThanOrEqual(result.report.bytes, target)
    let pixelRatio = Double(result.report.pixelWidth * result.report.pixelHeight)
        / Double(4000 * 3000)
    XCTAssertLessThan(pixelRatio, 0.6, "quality floor alone should be insufficient")
    XCTAssertGreaterThan(pixelRatio, 0.05, "but reduction must stay controlled")
    XCTAssertLessThanOrEqual(result.report.encodesUsed, 24)
}

func testForcedFitMeetsPathologicalTarget() throws {
    let session = FakeSession(.init(width: 4000, height: 3000, byteSize: 40_000_000))
    let target: Int64 = 1_500 // below anything quality alone can do
    let result = try runResizer(session, target: target)

    XCTAssertEqual(result.report.outcome, .processed)
    XCTAssertLessThanOrEqual(result.report.bytes, target)
    XCTAssertGreaterThanOrEqual(min(result.report.pixelWidth, result.report.pixelHeight),
                                ResizeParameters.standard.absoluteMinEdge)
    XCTAssertGreaterThanOrEqual(result.report.quality ?? 0,
                                ResizeParameters.standard.absoluteQualityFloor - 0.001)
}

func testTargetBelowOneKBIsInfeasible() {
    let session = FakeSession(.init(width: 4000, height: 3000, byteSize: 40_000_000))
    XCTAssertThrowsError(try runResizer(session, target: 500)) { error in
        XCTAssertEqual(error as? ResizeError, ResizeError.targetInfeasible)
    }
}

// MARK: - Hard guarantee (property test)

func testHardGuaranteeOverRandomizedInputs() throws {
    var generator = SystemRandomNumberGenerator()
    for iteration in 0..<200 {
        let w = Int.random(in: 64...2000, using: &generator)
        let h = Int.random(in: 64...2000, using: &generator)
        let floor = Double.random(in: 0.02...0.09, using: &generator)
        let slope = Double.random(in: 0.3...0.8, using: &generator)
        var config = FakeSession.Config(width: w, height: h,
                                        byteSize: Int64.random(in: 1_000...50_000_000,
                                                               using: &generator))
        config.modelFloor = floor
        config.modelSlope = slope
        let session = FakeSession(config)

        let reference = Double(w) * Double(h) * (floor + slope * 0.95 * 0.95)
        let target = Int64(Double.random(in: 0.02...0.9, using: &generator) * reference)

        do {
            let result = try runResizer(session, target: target)
            XCTAssertLessThanOrEqual(result.report.bytes, target,
                                     "iteration \(iteration): \(w)×\(h) target \(target)")
            XCTAssertGreaterThanOrEqual(result.report.pixelWidth, 1)
            XCTAssertGreaterThanOrEqual(result.report.pixelHeight, 1)
            if result.report.outcome == .processed, let q = result.report.quality {
                XCTAssertGreaterThanOrEqual(q, 0.10 - 0.001)
                XCTAssertLessThanOrEqual(q, 0.95 + 0.001)
            }
            XCTAssertLessThanOrEqual(session.encodeCount, 24 + 8,
                                     "iteration \(iteration): encode budget exceeded")
            // Assertions record failures; abort this iteration to keep going.
            if !RunContext.shared.failures.isEmpty { return }
        } catch ResizeError.targetInfeasible {
            // Legal only for sub-1 KB targets (product rule) or when even the
            // smallest encode forced-fit can produce misses the target.
            let floorBytes = reachableFloorBytes(w: w, h: h,
                                                 floor: floor, slope: slope)
            XCTAssert(target < 1024 || Double(target) < floorBytes,
                      "iteration \(iteration): should not be infeasible " +
                      "(target \(target), smallest reachable \(Int(floorBytes)))")
            if !RunContext.shared.failures.isEmpty { return }
        }
    }
}

/// Smallest encode `TargetSizeResizer.forcedFit` can produce under the
/// FakeSession model: simulate from the largest possible start scale (1.0 — the
/// real start scale is ≤ 1, keeping this bound on the safe side), stepping
/// ×`forcedFitStep` and stopping at the `absoluteMinEdge` hard floor with
/// aspect preserved (so the floor is 64 px on the SHORT edge, not a 64² box —
/// an elongated 64×2000 source cannot shrink at all). Includes per-dimension
/// rounding slack so the bound never underestimates the reachable floor.
private func reachableFloorBytes(w: Int, h: Int, floor: Double,
                                 slope: Double) -> Double {
    let p = ResizeParameters.standard
    var scale = 1.0
    var minPixels = Double.infinity
    for _ in 0..<p.maxForcedFitAttempts {
        scale *= p.forcedFitStep
        var fw = Double(w) * scale
        var fh = Double(h) * scale
        let minEdge = min(fw, fh)
        let belowHardFloor = minEdge < Double(p.absoluteMinEdge)
        if belowHardFloor, minEdge > 0 {
            let k = Double(p.absoluteMinEdge) / minEdge
            fw = min(fw * k, Double(w)) // never upscale past the source
            fh = min(fh * k, Double(h))
        }
        // decode() rounds the longest edge → ≤ 1 px slack per dimension.
        minPixels = min(minPixels, fw * fh + Double(w + h + 1))
        if belowHardFloor { break } // mirrors forcedFit's early break
    }
    let bpp = floor + slope * p.absoluteQualityFloor * p.absoluteQualityFloor
    // infeasible ⟺ even the smallest attempt exceeds target × safetyMargin.
    return (minPixels * bpp + 1) / p.safetyMargin
}

// MARK: - Memory cap & decode behavior

func testDecodeNeverExceedsMemoryBudget() throws {
    var params = ResizeParameters.standard
    params.maxDecodePixels = 4_000_000 // 4 MP budget for a 12 MP image
    let session = FakeSession(.init(width: 4000, height: 3000, byteSize: 12_000_000))
    _ = try runResizer(session, target: 500_000, params: params)

    XCTAssertFalse(session.decodedLongestEdges.isEmpty)
    // The first (full-resolution) decode must already respect the budget.
    let first = session.decodedLongestEdges[0]
    let scale = Double(first) / 4000.0
    XCTAssertLessThanOrEqual(Double(4000 * 3000) * scale * scale, 4_000_000 * 1.05)
}

// MARK: - Cancellation

func testCancellationThrowsBetweenIterations() {
    var calls = 0
    let session = FakeSession(.init(width: 4000, height: 3000, byteSize: 40_000_000))
    let resizer = TargetSizeResizer(parameters: .standard) {
        calls += 1
        return calls > 2 // let header checks pass, then cancel mid-run
    }
    // Target must clear the 1 KB product minimum or targetInfeasible wins first.
    XCTAssertThrowsError(try resizer.run(session: session, targetBytes: 2_000)) {
        XCTAssertEqual($0 as? ResizeError, .cancelled)
    }
}

// MARK: - Unsupported input

func testUnsupportedFormatThrowsBeforeAnyWork() {
    let session = FakeSession(.init(width: 4000, height: 3000, format: .tiff,
                                    byteSize: 12_000_000))
    XCTAssertThrowsError(try runResizer(session, target: 5_000_000)) {
        XCTAssertEqual($0 as? ResizeError, .unsupportedFormat(.tiff))
    }
    XCTAssertEqual(session.encodeCount, 0)
    XCTAssertEqual(session.passThroughCalls, 0)
}

// MARK: - Alpha PNG

func testAlphaPNGKeepsPNGAndReducesPixelsOnly() throws {
    var config = FakeSession.Config(width: 1000, height: 1000, format: .png,
                                    hasAlpha: true, byteSize: 4_000_000)
    config.qualityAffectsSize = false // PNG is lossless: only pixels can shrink
    config.modelFloor = 2.0 // full-res PNG ≈ 4 MB > 1 MB target
    config.modelSlope = 2.0
    let session = FakeSession(config)
    let result = try runResizer(session, target: 1_000_000)

    XCTAssertEqual(result.report.outcome, .processed)
    XCTAssertEqual(result.report.outputFormat, .png)
    XCTAssertLessThanOrEqual(result.report.bytes, 1_000_000)
    XCTAssertLessThan(result.report.pixelWidth, 1000, "must reduce pixels to fit")
}
