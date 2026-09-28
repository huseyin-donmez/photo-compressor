package com.imageresizer.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The entire numeric contract of the resize algorithm.
 * MUST stay identical to `docs/ALGORITHM.md` and the iOS `ResizeParameters`.
 */
data class ResizeParameters(
    /** Encoded bytes must be ≤ `target × safetyMargin`. */
    val safetyMargin: Double = 0.97,
    /** First quality tried. */
    val initialQuality: Double = 0.80,
    /** Never search above this. */
    val qualityCeiling: Double = 0.95,
    /** Preferred quality floor; below this we reduce resolution instead. */
    val qualityFloor: Double = 0.30,
    /** Used only at the resolution floor, then in the forced-fit path. */
    val absoluteQualityFloor: Double = 0.10,
    /** Stop the bisection when `hi − lo ≤ tolerance`. */
    val qualityTolerance: Double = 0.03,
    /** Bisection iterations per resolution level (≤ 24 encodes/image on normal paths). */
    val maxBisectIterations: Int = 5,
    /** First reduction ratio bounds: `clamp(√(targetEnc/sizeAtFloor) × 0.9, min, max)`. */
    val firstReductionMin: Double = 0.40,
    val firstReductionMax: Double = 0.85,
    val firstReductionSafety: Double = 0.9,
    /** Subsequent reduction steps multiply the current scale by this. */
    val subsequentReductionFactor: Double = 0.70,
    /** Voluntary reduction stops at the resolution floor:
     *  longest edge ≥ [floorLongestEdge] AND min edge ≥ [floorMinEdge]. */
    val floorLongestEdge: Int = 1024,
    val floorMinEdge: Int = 512,
    /** Hard floor for the forced-fit path. */
    val absoluteMinEdge: Int = 64,
    /** Forced-fit: scale ×[forcedFitStep] per attempt, ≤ [maxForcedFitAttempts] attempts. */
    val forcedFitStep: Double = 0.75,
    val maxForcedFitAttempts: Int = 8,
    /** Decode-time memory cap: pixels will never exceed this (bounds-decode). */
    val maxDecodePixels: Long = 60_000_000,
    /** Safety valve for the resolution loop. */
    val maxLevels: Int = 8,
) {
    /** Scale applied to full resolution so decoding fits the memory budget. */
    fun memoryScale(width: Int, height: Int): Double {
        val pixels = width.toDouble() * height.toDouble()
        val cap = maxDecodePixels.toDouble()
        if (pixels <= cap) return 1.0
        return sqrt(cap / pixels)
    }

    /** Largest scale that still satisfies BOTH floor constraints (≤ 1). */
    fun resolutionFloorScale(width: Int, height: Int): Double {
        val longest = max(width, height).toDouble()
        val shortest = min(width, height).toDouble()
        if (longest <= 0.0 || shortest <= 0.0) return 1.0
        val byLongest = floorLongestEdge / longest
        val byShortest = floorMinEdge / shortest
        return min(1.0, max(byLongest, byShortest))
    }

    fun targetWithMargin(targetBytes: Long): Long =
        (targetBytes.toDouble() * safetyMargin).toLong() // truncates toward zero = floor for ≥ 0

    companion object {
        val standard = ResizeParameters()
    }
}

/** Product-level constants (no backend, no settings surface). */
object ProductRules {
    const val initialFreeCredits = 5
    const val rewardedAdGrantCount = 5
    const val minimumTargetBytes: Long = 100L * 1024
}
