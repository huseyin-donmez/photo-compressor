package com.imageresizer.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The core algorithm: quality bisection → controlled resolution reduction →
 * forced fit, always ending with `bytes ≤ target`.
 *
 * Priority: 1 preserve resolution → 2 optimize quality → 3 reduce resolution →
 * 4 verify size. Pseudo-code: docs/ALGORITHM.md.
 * MUST behave identically to ios/ImageResizerCore/TargetSizeResizer.swift.
 */
class TargetSizeResizer(
    val parameters: ResizeParameters = ResizeParameters.standard,
    /** Cooperative cancellation, checked between levels and between encodes. */
    val isCancelled: (() -> Boolean)? = null,
) {
    /** Encode/level counters shared with the helpers (Swift's `inout`). */
    private class Progress {
        var encodes = 0
        var levels = 0
    }

    private class Winning(val quality: Double, val data: ByteArray)

    private class BisectOutcome(val best: Winning?, val smallestFailingBytes: Int?)

    fun run(session: ResizeSession, targetBytes: Long): ResizeResult {
        // Product rule: minimum target is 1 KB (docs/ALGORITHM.md).
        if (targetBytes < 1024) throw ResizeError.TargetInfeasible
        val p = parameters
        val targetEnc = p.targetWithMargin(targetBytes)

        val header = session.readHeader()
        val format = FormatStrategy.outputFormat(header.format, header.hasAlpha)
            ?: throw ResizeError.UnsupportedFormat(header.format)

        // 1. Already within target → zero-work pass-through (no credit consumed).
        if (header.byteSize <= targetBytes) {
            checkCancelled()
            val data = session.passThroughStripMetadata()
            if (data != null && data.size.toLong() <= targetBytes) {
                val report = ResizeReport(
                    outcome = ResizeReport.Outcome.PASS_THROUGH,
                    outputFormat = null,
                    bytes = data.size.toLong(),
                    pixelWidth = header.pixelWidth,
                    pixelHeight = header.pixelHeight,
                    quality = null,
                    encodesUsed = 0,
                    levelsUsed = 0,
                )
                return ResizeResult(report, data)
            }
        }

        var scale = p.memoryScale(header.pixelWidth, header.pixelHeight)
        val floorScale = p.resolutionFloorScale(header.pixelWidth, header.pixelHeight)
        var qFloor = p.qualityFloor
        var estimateUsed = false
        val progress = Progress()

        // 2–3. Quality search at full (memory-capped) resolution, then reductions.
        while (progress.levels < p.maxLevels) {
            checkCancelled()
            progress.levels += 1

            val image = session.decode(longestEdge = longestEdge(header, scale))
            var levelHigh = p.qualityCeiling
            if (qFloor == p.absoluteQualityFloor) {
                // Quality above the standard floor was already proven infeasible at
                // this scale (monotonicity), so tighten the bracket to save encodes.
                levelHigh = p.qualityFloor
            }
            val outcome = bisect(
                session, image, low = qFloor, high = levelHigh,
                seed = p.initialQuality, targetEnc = targetEnc, progress = progress,
            )

            outcome.best?.let { return finish(it, image, format, progress) }

            // Level failed → decide: widen quality at floor, reduce resolution, or stop.
            val atFloor = scale <= floorScale + 0.0005
            var nextScale: Double? = null
            if (!atFloor) {
                val failing = outcome.smallestFailingBytes
                val step = if (failing != null && failing > 0 && !estimateUsed) {
                    // Analytic first step from the measured size at the quality floor.
                    estimateUsed = true
                    val ratio = sqrt(targetEnc.toDouble() / failing) * p.firstReductionSafety
                    min(max(ratio, p.firstReductionMin), p.firstReductionMax)
                } else {
                    p.subsequentReductionFactor
                }
                nextScale = max(scale * step, floorScale)
                if (nextScale >= scale) nextScale = null // no progress → treat as at floor
            }

            if (nextScale != null) {
                scale = nextScale
                continue
            }
            if (qFloor == p.qualityFloor) {
                qFloor = p.absoluteQualityFloor // widen quality at the resolution floor
                continue
            }
            break // even the absolute quality floor failed → forced fit
        }

        // 4. Forced fit: hard target wins over everything.
        return forcedFit(session, header, scale, targetEnc, format, progress)
            ?: throw ResizeError.TargetInfeasible
    }

    // MARK: - Quality bisection

    private fun bisect(
        session: ResizeSession,
        image: ResizableImage,
        low: Double,
        high: Double,
        seed: Double,
        targetEnc: Long,
        progress: Progress,
    ): BisectOutcome {
        val p = parameters
        var lo = low
        var hi = high
        var q = min(max(seed, lo), hi)
        var best: Winning? = null
        var smallestFailing: Int? = null

        for (iter in 0 until p.maxBisectIterations) {
            checkCancelled()
            val data = session.encodeFinal(image, q)
            progress.encodes += 1
            if (data.size.toLong() <= targetEnc) {
                best = Winning(q, data)
                lo = q
            } else {
                hi = q
                smallestFailing = min(smallestFailing ?: Int.MAX_VALUE, data.size)
            }
            if (hi - lo <= p.qualityTolerance) break
            q = (lo + hi) / 2
        }
        return BisectOutcome(best, smallestFailing)
    }

    // MARK: - Forced fit (pathological targets)

    private fun forcedFit(
        session: ResizeSession,
        header: ImageHeader,
        startScale: Double,
        targetEnc: Long,
        format: OutputFormat,
        progress: Progress,
    ): ResizeResult? {
        val p = parameters
        var scale = startScale

        for (attempt in 0 until p.maxForcedFitAttempts) {
            checkCancelled()
            scale *= p.forcedFitStep

            var w = header.pixelWidth * scale
            var h = header.pixelHeight * scale
            val minEdge = min(w, h)
            val belowHardFloor = minEdge < p.absoluteMinEdge
            if (belowHardFloor && minEdge > 0.0) {
                // Stop reducing at the hard floor — but never upscale past the source.
                val k = p.absoluteMinEdge / minEdge
                w = min(w * k, header.pixelWidth.toDouble())
                h = min(h * k, header.pixelHeight.toDouble())
            }

            val image = session.decode(
                longestEdge = max(1, max(w.roundToInt(), h.roundToInt())),
            )
            progress.levels += 1
            val data = session.encodeFinal(image, p.absoluteQualityFloor)
            progress.encodes += 1
            if (data.size.toLong() <= targetEnc) {
                return finish(Winning(p.absoluteQualityFloor, data), image, format, progress)
            }
            if (belowHardFloor) return null // cannot legally reduce further
        }
        return null
    }

    // MARK: - Helpers

    private fun finish(
        win: Winning,
        image: ResizableImage,
        format: OutputFormat,
        progress: Progress,
    ): ResizeResult {
        val report = ResizeReport(
            outcome = ResizeReport.Outcome.PROCESSED,
            outputFormat = format,
            bytes = win.data.size.toLong(),
            pixelWidth = image.pixelWidth,
            pixelHeight = image.pixelHeight,
            quality = win.quality,
            encodesUsed = progress.encodes,
            levelsUsed = progress.levels,
        )
        return ResizeResult(report, win.data)
    }

    private fun longestEdge(header: ImageHeader, scale: Double): Int {
        val longest = max(header.pixelWidth, header.pixelHeight) * scale
        return max(1, longest.roundToInt())
    }

    private fun checkCancelled() {
        if (isCancelled?.invoke() == true) throw ResizeError.Cancelled
    }
}
