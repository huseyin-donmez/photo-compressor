import Foundation

/// The core algorithm: quality bisection → controlled resolution reduction →
/// forced fit, always ending with `bytes ≤ target`.
///
/// Priority: 1 preserve resolution → 2 optimize quality → 3 reduce resolution →
/// 4 verify size. Pseudo-code: docs/ALGORITHM.md.
public struct TargetSizeResizer {
    public var parameters: ResizeParameters
    /// Cooperative cancellation, checked between levels and between encodes.
    public var isCancelled: (() -> Bool)?

    public init(parameters: ResizeParameters = .standard,
                isCancelled: (() -> Bool)? = nil) {
        self.parameters = parameters
        self.isCancelled = isCancelled
    }

    public func run(session: ResizeSession, targetBytes: Int64) throws -> ResizeResult {
        // Product rule: minimum target is 1 KB (docs/ALGORITHM.md).
        guard targetBytes >= 1024 else { throw ResizeError.targetInfeasible }
        let p = parameters
        let targetEnc = p.targetWithMargin(targetBytes)

        let header = try session.readHeader()
        guard let format = FormatStrategy.outputFormat(source: header.format,
                                                       hasAlpha: header.hasAlpha) else {
            throw ResizeError.unsupportedFormat(header.format)
        }

        // 1. Already within target → zero-work pass-through (no credit consumed).
        if header.byteSize <= targetBytes {
            try checkCancelled()
            if let data = try session.passThroughStripMetadata(),
               Int64(data.count) <= targetBytes {
                let report = ResizeReport(outcome: .passThrough, outputFormat: nil,
                                          bytes: Int64(data.count),
                                          pixelWidth: header.pixelWidth,
                                          pixelHeight: header.pixelHeight,
                                          quality: nil, encodesUsed: 0, levelsUsed: 0)
                return ResizeResult(report: report, data: data)
            }
        }

        var scale = p.memoryScale(width: header.pixelWidth, height: header.pixelHeight)
        let floorScale = p.resolutionFloorScale(width: header.pixelWidth,
                                                height: header.pixelHeight)
        var qFloor = p.qualityFloor
        var estimateUsed = false
        var encodes = 0
        var levels = 0

        // 2–3. Quality search at full (memory-capped) resolution, then reductions.
        while levels < p.maxLevels {
            try checkCancelled()
            levels += 1

            let image = try session.decode(longestEdge: longestEdge(header: header, scale: scale))
            var levelHigh = p.qualityCeiling
            if qFloor == p.absoluteQualityFloor {
                // Quality above the standard floor was already proven infeasible at
                // this scale (monotonicity), so tighten the bracket to save encodes.
                levelHigh = p.qualityFloor
            }
            let outcome = try bisect(session: session, image: image,
                                     low: qFloor, high: levelHigh,
                                     seed: p.initialQuality, targetEnc: targetEnc,
                                     encodes: &encodes)

            if let best = outcome.best {
                return finish(reportFor: best, image: image, format: format,
                              levels: levels, encodes: encodes)
            }

            // Level failed → decide: widen quality at floor, reduce resolution, or stop.
            let atFloor = scale <= floorScale + 0.0005
            var nextScale: Double? = nil
            if !atFloor {
                if let floorBytes = outcome.smallestFailingBytes, floorBytes > 0,
                   !estimateUsed {
                    // Analytic first step from the measured size at the quality floor.
                    let ratio = (Double(targetEnc) / Double(floorBytes)).squareRoot()
                        * p.firstReductionSafety
                    let clamped = min(max(ratio, p.firstReductionMin), p.firstReductionMax)
                    nextScale = scale * clamped
                    estimateUsed = true
                } else {
                    nextScale = scale * p.subsequentReductionFactor
                }
                nextScale = max(nextScale ?? 0, floorScale)
                if nextScale ?? 1 >= scale {
                    nextScale = nil // no progress possible → treat as at floor
                }
            }

            if let next = nextScale {
                scale = next
                continue
            }
            if qFloor == p.qualityFloor {
                qFloor = p.absoluteQualityFloor // widen quality at the resolution floor
                continue
            }
            break // even the absolute quality floor failed → forced fit
        }

        // 4. Forced fit: hard target wins over everything.
        if let result = try forcedFit(session: session, header: header, startScale: scale,
                                      targetEnc: targetEnc, format: format,
                                      levels: &levels, encodes: &encodes) {
            return result
        }
        throw ResizeError.targetInfeasible
    }

    // MARK: - Quality bisection

    private struct BisectOutcome {
        var best: (quality: Double, data: Data)?
        var smallestFailingBytes: Int?
    }

    private func bisect(session: ResizeSession, image: any ResizableImage,
                        low: Double, high: Double, seed: Double,
                        targetEnc: Int64, encodes: inout Int) throws -> BisectOutcome {
        let p = parameters
        var lo = low
        var hi = high
        var q = min(max(seed, lo), hi)
        var best: (quality: Double, data: Data)?
        var smallestFailing: Int?

        for _ in 0..<p.maxBisectIterations {
            try checkCancelled()
            let data = try session.encodeFinal(image, quality: q)
            encodes += 1
            if Int64(data.count) <= targetEnc {
                best = (q, data)
                lo = q
            } else {
                hi = q
                smallestFailing = min(smallestFailing ?? Int.max, data.count)
            }
            if hi - lo <= p.qualityTolerance { break }
            q = (lo + hi) / 2
        }
        return BisectOutcome(best: best, smallestFailingBytes: smallestFailing)
    }

    // MARK: - Forced fit (pathological targets)

    private func forcedFit(session: ResizeSession, header: ImageHeader,
                           startScale: Double, targetEnc: Int64, format: OutputFormat,
                           levels: inout Int, encodes: inout Int) throws -> ResizeResult? {
        let p = parameters
        var scale = startScale

        for _ in 0..<p.maxForcedFitAttempts {
            try checkCancelled()
            scale *= p.forcedFitStep

            var w = Double(header.pixelWidth) * scale
            var h = Double(header.pixelHeight) * scale
            let minEdge = min(w, h)
            let belowHardFloor = minEdge < Double(p.absoluteMinEdge)
            if belowHardFloor, minEdge > 0 {
                // Stop reducing at the hard floor — but never upscale past the source.
                let k = Double(p.absoluteMinEdge) / minEdge
                w = min(w * k, Double(header.pixelWidth))
                h = min(h * k, Double(header.pixelHeight))
            }

            let image = try session.decode(longestEdge: max(1, max(Int(w.rounded()),
                                                                   Int(h.rounded()))))
            levels += 1
            let data = try session.encodeFinal(image, quality: p.absoluteQualityFloor)
            encodes += 1
            if Int64(data.count) <= targetEnc {
                return finish(reportFor: (p.absoluteQualityFloor, data), image: image,
                              format: format, levels: levels, encodes: encodes)
            }
            if belowHardFloor { break } // cannot legally reduce further
        }
        return nil
    }

    // MARK: - Helpers

    private func finish(reportFor best: (quality: Double, data: Data),
                        image: any ResizableImage, format: OutputFormat,
                        levels: Int, encodes: Int) -> ResizeResult {
        let report = ResizeReport(outcome: .processed, outputFormat: format,
                                  bytes: Int64(best.data.count),
                                  pixelWidth: image.pixelWidth,
                                  pixelHeight: image.pixelHeight,
                                  quality: best.quality,
                                  encodesUsed: encodes, levelsUsed: levels)
        return ResizeResult(report: report, data: best.data)
    }

    private func longestEdge(header: ImageHeader, scale: Double) -> Int {
        let longest = Double(max(header.pixelWidth, header.pixelHeight)) * scale
        return max(1, Int(longest.rounded()))
    }

    private func checkCancelled() throws {
        if isCancelled?() == true { throw ResizeError.cancelled }
    }
}
