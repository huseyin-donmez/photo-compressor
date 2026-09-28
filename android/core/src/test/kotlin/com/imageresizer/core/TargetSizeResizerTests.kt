package com.imageresizer.core

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetSizeResizerTests {
    private fun runResizer(
        session: ResizeSession,
        target: Long,
        params: ResizeParameters = ResizeParameters.standard,
    ): ResizeResult = TargetSizeResizer(params).run(session, target)

    // MARK: - Pass-through

    @Test
    fun passThroughWithoutEncoding() {
        val session = FakeSession(
            FakeSession.Config(
                width = 4000, height = 3000, byteSize = 5_000,
                passThroughData = ByteArray(5_000),
            ),
        )
        val result = runResizer(session, 100_000)
        assertEquals(ResizeReport.Outcome.PASS_THROUGH, result.report.outcome)
        assertEquals(0, session.encodeCount, "pass-through must not re-encode")
        assertEquals(0, result.report.encodesUsed)
        assertNull(result.report.outputFormat, "original container is preserved")
        assertEquals(5_000L, result.report.bytes)
    }

    @Test
    fun passThroughGrewBeyondTargetFallsBackToEncode() {
        // Stripping could theoretically grow the file → must fall into the pipeline.
        val session = FakeSession(
            FakeSession.Config(
                width = 4000, height = 3000, byteSize = 100_000,
                passThroughData = ByteArray(100_001),
            ),
        )
        val result = runResizer(session, 100_000)
        assertEquals(ResizeReport.Outcome.PROCESSED, result.report.outcome)
        assertGt(session.encodeCount, 0)
        assertLe(result.report.bytes, 100_000L)
    }

    @Test
    fun passThroughUnavailableFallsBackToEncode() {
        val session = FakeSession(
            FakeSession.Config(
                width = 4000, height = 3000, byteSize = 90_000,
                passThroughAvailable = false,
            ),
        )
        val result = runResizer(session, 100_000)
        assertEquals(ResizeReport.Outcome.PROCESSED, result.report.outcome)
        assertLe(result.report.bytes, 100_000L)
    }

    // MARK: - Quality search

    @Test
    fun qualitySearchFindsHighestFeasibleQuality() {
        val session = FakeSession(FakeSession.Config(width = 1000, height = 800, byteSize = 10_000_000))
        // Target ≈ what quality 0.60 produces at full resolution.
        val target = session.modelBytes(1000, 800, 0.60).toLong()
        val result = runResizer(session, target)

        assertEquals(ResizeReport.Outcome.PROCESSED, result.report.outcome)
        assertLe(result.report.bytes, target)
        val quality = requireNotNull(result.report.quality) { "processed result must report quality" }
        // Within the 0.03 tolerance of the true optimum, never below the floor.
        assertLe(quality, 0.60 + 0.031)
        assertGe(quality, 0.30)
        // Resolution fully preserved — reduction only happens if quality fails.
        assertEquals(1000, result.report.pixelWidth)
        assertEquals(800, result.report.pixelHeight)
        assertLe(result.report.encodesUsed, 5)
    }

    // MARK: - Resolution reduction

    @Test
    fun resolutionReducedWhenQualityFloorCannotReachTarget() {
        // Full-res bytes at the quality floor (~0.10×pixels) vastly exceed target,
        // but a ~0.4× reduction at floor quality would satisfy it → controlled step.
        val session = FakeSession(FakeSession.Config(width = 4000, height = 3000, byteSize = 40_000_000))
        val target = 300_000L
        val result = runResizer(session, target)

        assertEquals(ResizeReport.Outcome.PROCESSED, result.report.outcome)
        assertLe(result.report.bytes, target)
        val pixelRatio = (result.report.pixelWidth * result.report.pixelHeight).toDouble() /
            (4000.0 * 3000.0)
        assertLt(pixelRatio, 0.6, "quality floor alone should be insufficient")
        assertGt(pixelRatio, 0.05, "but reduction must stay controlled")
        assertLe(result.report.encodesUsed, 24)
    }

    @Test
    fun forcedFitMeetsPathologicalTarget() {
        val session = FakeSession(FakeSession.Config(width = 4000, height = 3000, byteSize = 40_000_000))
        val target = 1_500L // below anything quality alone can do
        val result = runResizer(session, target)

        assertEquals(ResizeReport.Outcome.PROCESSED, result.report.outcome)
        assertLe(result.report.bytes, target)
        assertGe(
            min(result.report.pixelWidth, result.report.pixelHeight),
            ResizeParameters.standard.absoluteMinEdge,
        )
        assertGe(
            result.report.quality ?: 0.0,
            ResizeParameters.standard.absoluteQualityFloor - 0.001,
        )
    }

    @Test
    fun targetBelowOneKBIsInfeasible() {
        val session = FakeSession(FakeSession.Config(width = 4000, height = 3000, byteSize = 40_000_000))
        assertFailsWith<ResizeError.TargetInfeasible> { runResizer(session, 500) }
    }

    // MARK: - Hard guarantee (property test)

    @Test
    fun hardGuaranteeOverRandomizedInputs() {
        for (iteration in 0 until 200) {
            val w = Random.nextInt(64, 2001)
            val h = Random.nextInt(64, 2001)
            val floor = Random.nextDouble(0.02, 0.09)
            val slope = Random.nextDouble(0.3, 0.8)
            val config = FakeSession.Config(
                width = w, height = h,
                byteSize = Random.nextLong(1_000, 50_000_001),
                modelFloor = floor,
                modelSlope = slope,
            )
            val session = FakeSession(config)

            val reference = w.toDouble() * h.toDouble() * (floor + slope * 0.95 * 0.95)
            val target = (Random.nextDouble(0.02, 0.9) * reference).toLong()

            try {
                val result = runResizer(session, target)
                assertLe(result.report.bytes, target, "iteration $iteration: ${w}×$h target $target")
                assertGe(result.report.pixelWidth, 1, "iteration $iteration")
                assertGe(result.report.pixelHeight, 1, "iteration $iteration")
                val q = result.report.quality
                if (result.report.outcome == ResizeReport.Outcome.PROCESSED && q != null) {
                    assertGe(q, 0.10 - 0.001, "iteration $iteration: quality floor")
                    assertLe(q, 0.95 + 0.001, "iteration $iteration: quality ceiling")
                }
                assertLe(session.encodeCount, 24 + 8, "iteration $iteration: encode budget exceeded")
            } catch (e: ResizeError.TargetInfeasible) {
                // Legal only for sub-1 KB targets (product rule) or when even the
                // smallest encode forced-fit can produce misses the target.
                val floorBytes = reachableFloorBytes(w, h, floor, slope)
                assertTrue(
                    target < 1024 || target.toDouble() < floorBytes,
                    "iteration $iteration: should not be infeasible " +
                        "(target $target, smallest reachable ${floorBytes.toLong()})",
                )
            }
        }
    }

    // MARK: - Memory cap & decode behavior

    @Test
    fun decodeNeverExceedsMemoryBudget() {
        val params = ResizeParameters.standard.copy(maxDecodePixels = 4_000_000)
        val session = FakeSession(FakeSession.Config(width = 4000, height = 3000, byteSize = 12_000_000))
        runResizer(session, 500_000, params)

        assertTrue(session.decodedLongestEdges.isNotEmpty())
        // The first (full-resolution) decode must already respect the budget.
        val first = session.decodedLongestEdges[0]
        val scale = first / 4000.0
        assertLe(4000.0 * 3000.0 * scale * scale, 4_000_000 * 1.05)
    }

    // MARK: - Cancellation

    @Test
    fun cancellationThrowsBetweenIterations() {
        var calls = 0
        val session = FakeSession(FakeSession.Config(width = 4000, height = 3000, byteSize = 40_000_000))
        val resizer = TargetSizeResizer(ResizeParameters.standard) {
            calls += 1
            calls > 2 // let header checks pass, then cancel mid-run
        }
        // Target must clear the 1 KB product minimum or targetInfeasible wins first.
        assertFailsWith<ResizeError.Cancelled> { resizer.run(session, 2_000) }
    }

    // MARK: - Unsupported input

    @Test
    fun unsupportedFormatThrowsBeforeAnyWork() {
        val session = FakeSession(
            FakeSession.Config(width = 4000, height = 3000, format = SourceFormat.TIFF, byteSize = 12_000_000),
        )
        val e = assertFailsWith<ResizeError.UnsupportedFormat> { runResizer(session, 5_000_000) }
        assertEquals(SourceFormat.TIFF, e.format)
        assertEquals(0, session.encodeCount)
        assertEquals(0, session.passThroughCalls)
    }

    // MARK: - Alpha PNG

    @Test
    fun alphaPNGKeepsPNGAndReducesPixelsOnly() {
        val session = FakeSession(
            FakeSession.Config(
                width = 1000, height = 1000, format = SourceFormat.PNG,
                hasAlpha = true, byteSize = 4_000_000,
                modelFloor = 2.0, // full-res PNG ≈ 4 MB > 1 MB target
                modelSlope = 2.0,
                qualityAffectsSize = false, // PNG is lossless: only pixels can shrink
            ),
        )
        val result = runResizer(session, 1_000_000)

        assertEquals(ResizeReport.Outcome.PROCESSED, result.report.outcome)
        assertEquals(OutputFormat.PNG, result.report.outputFormat)
        assertLe(result.report.bytes, 1_000_000L)
        assertLt(result.report.pixelWidth, 1000, "must reduce pixels to fit")
    }
}

/**
 * Smallest encode `TargetSizeResizer.forcedFit` can produce under the
 * FakeSession model: simulate from the largest possible start scale (1.0 — the
 * real start scale is ≤ 1, keeping this bound on the safe side), stepping
 * ×`forcedFitStep` and stopping at the `absoluteMinEdge` hard floor with
 * aspect preserved (so the floor is 64 px on the SHORT edge, not a 64² box —
 * an elongated 64×2000 source cannot shrink at all). Includes per-dimension
 * rounding slack so the bound never underestimates the reachable floor.
 * Mirrors the iOS test helper (behavior contract: docs/ALGORITHM.md).
 */
private fun reachableFloorBytes(w: Int, h: Int, floor: Double, slope: Double): Double {
    val p = ResizeParameters.standard
    var scale = 1.0
    var minPixels = Double.MAX_VALUE
    for (attempt in 0 until p.maxForcedFitAttempts) {
        scale *= p.forcedFitStep
        var fw = w * scale
        var fh = h * scale
        val minEdge = min(fw, fh)
        val belowHardFloor = minEdge < p.absoluteMinEdge
        if (belowHardFloor && minEdge > 0.0) {
            val k = p.absoluteMinEdge / minEdge
            fw = min(fw * k, w.toDouble()) // never upscale past the source
            fh = min(fh * k, h.toDouble())
        }
        // decode() rounds the longest edge → ≤ 1 px slack per dimension.
        minPixels = min(minPixels, fw * fh + (w + h + 1))
        if (belowHardFloor) break // mirrors forcedFit's early break
    }
    val bpp = floor + slope * p.absoluteQualityFloor * p.absoluteQualityFloor
    // infeasible ⟺ even the smallest attempt exceeds target × safetyMargin.
    return (minPixels * bpp + 1) / p.safetyMargin
}
