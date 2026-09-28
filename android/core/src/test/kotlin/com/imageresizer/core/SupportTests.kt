package com.imageresizer.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportTests {
    @Test
    fun formatStrategyMatrix() {
        assertEquals(OutputFormat.JPEG, FormatStrategy.outputFormat(SourceFormat.JPEG, hasAlpha = false))
        // Android has no native HEIC encoder → JPEG (docs/ALGORITHM.md matrix).
        assertEquals(OutputFormat.JPEG, FormatStrategy.outputFormat(SourceFormat.HEIC, hasAlpha = false))
        // Photo PNG without alpha converts to JPEG (preserve resolution is priority #1).
        assertEquals(OutputFormat.JPEG, FormatStrategy.outputFormat(SourceFormat.PNG, hasAlpha = false))
        // Alpha must stay lossless.
        assertEquals(OutputFormat.PNG, FormatStrategy.outputFormat(SourceFormat.PNG, hasAlpha = true))
        // Android encodes WebP natively: container preserved either way.
        assertEquals(OutputFormat.WEBP, FormatStrategy.outputFormat(SourceFormat.WEBP, hasAlpha = false))
        assertEquals(OutputFormat.WEBP, FormatStrategy.outputFormat(SourceFormat.WEBP, hasAlpha = true))
        assertEquals(OutputFormat.JPEG, FormatStrategy.outputFormat(SourceFormat.GIF, hasAlpha = false))
        assertEquals(OutputFormat.PNG, FormatStrategy.outputFormat(SourceFormat.GIF, hasAlpha = true))
        assertEquals(OutputFormat.JPEG, FormatStrategy.outputFormat(SourceFormat.BMP, hasAlpha = false))
    }

    @Test
    fun formatStrategyUnsupportedInputs() {
        assertNull(FormatStrategy.outputFormat(SourceFormat.TIFF, hasAlpha = false))
        assertNull(FormatStrategy.outputFormat(SourceFormat.RAW, hasAlpha = false))
        assertNull(FormatStrategy.outputFormat(SourceFormat.OTHER, hasAlpha = false))
    }

    @Test
    fun resolutionFloorScaleSatisfiesBothConstraints() {
        val p = ResizeParameters.standard
        // 4000×3000 → 0.256 gives exactly 1024×768: longest ≥1024, min ≥512.
        val scale = p.resolutionFloorScale(4000, 3000)
        assertEquals(1024.0 / 4000.0, scale, absoluteTolerance = 0.0001)
        assertGe((4000 * scale).toInt(), 1024)
        assertGe((3000 * scale).toInt(), 512)

        // Small images are already below the floor → no voluntary reduction.
        assertEquals(1.0, p.resolutionFloorScale(800, 600))
        assertEquals(1.0, p.resolutionFloorScale(640, 480))
    }

    @Test
    fun memoryScale() {
        val p = ResizeParameters.standard.copy(maxDecodePixels = 4_000_000)
        assertEquals(1.0, p.memoryScale(1000, 1000))
        val scale = p.memoryScale(4000, 3000)
        assertEquals(
            kotlin.math.sqrt(4_000_000.0 / 12_000_000.0),
            scale,
            absoluteTolerance = 0.0001,
        )
        assertLe(4000.0 * 3000.0 * scale * scale, 4_000_001.0)
    }

    @Test
    fun targetWithMargin() {
        assertEquals(4_850_000L, ResizeParameters.standard.targetWithMargin(5_000_000))
        assertEquals(
            (100 * 1024 * 0.97).toLong(),
            ResizeParameters.standard.targetWithMargin(100 * 1024.toLong()),
        )
    }

    // MARK: - Entitlement / free model C

    @Test
    fun entitlementPremiumIsUnlimited() {
        val state = UsageState(remainingFreeCredits = 0, isPremium = true)
        assertEquals(BatchPlan.Unlimited, Entitlement.plan(500, state))
        // Credits never move for premium.
        Entitlement.consumeCredit(state)
        assertEquals(0, state.remainingFreeCredits)
    }

    @Test
    fun entitlementPlanAllCreditsCoverBatch() {
        val state = UsageState(remainingFreeCredits = 5)
        assertEquals(BatchPlan.Allowed(3), Entitlement.plan(3, state))
        assertEquals(BatchPlan.Allowed(5), Entitlement.plan(5, state))
        assertEquals(
            BatchPlan.Allowed(0),
            Entitlement.plan(0, state),
            "all-pass-through batches are free",
        )
    }

    @Test
    fun entitlementPlanPartialBatchStopsAtCreditLimit() {
        val state = UsageState(remainingFreeCredits = 3)
        assertEquals(BatchPlan.Partial(3), Entitlement.plan(20, state))
    }

    @Test
    fun entitlementPlanBlockedWhenNoCredits() {
        val state = UsageState(remainingFreeCredits = 0)
        assertEquals(BatchPlan.Blocked, Entitlement.plan(1, state))
    }

    @Test
    fun entitlementConsumePerSuccessOnly() {
        val state = UsageState(remainingFreeCredits = 2)
        Entitlement.consumeCredit(state)
        assertEquals(1, state.remainingFreeCredits)
        Entitlement.consumeCredit(state)
        Entitlement.consumeCredit(state) // extra success (shouldn't happen) clamps
        assertEquals(0, state.remainingFreeCredits)
    }

    @Test
    fun entitlementRewardedAdGrantsFiveAndNeverForPremium() {
        val state = UsageState(remainingFreeCredits = 0)
        Entitlement.grantRewardedAd(state)
        assertEquals(ProductRules.rewardedAdGrantCount, state.remainingFreeCredits)

        val premium = UsageState(remainingFreeCredits = 0, isPremium = true)
        Entitlement.grantRewardedAd(premium)
        assertEquals(0, premium.remainingFreeCredits)
    }

    // MARK: - Disk verification (hard guarantee)

    @Test
    fun diskOutputRejectsOversizedDataAndDeletesFile() {
        val dir = Files.createTempDirectory("disk-output").toFile()
        try {
            val target = File(dir, "out.jpg")
            val e = assertFailsWithResizeError {
                DiskOutput.writeVerified(ByteArray(2_000), target, targetBytes = 1_000)
            }
            assertTrue(e is ResizeError.TargetExceeded, "expected TargetExceeded, got $e")
            assertTrue(!target.exists(), "oversized file must be deleted")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun diskOutputWritesAndReportsRealSize() {
        val dir = Files.createTempDirectory("disk-output").toFile()
        try {
            val target = File(dir, "out.jpg")
            val size = DiskOutput.writeVerified(ByteArray(1_000), target, targetBytes = 2_000)
            assertEquals(1_000L, size)
            assertTrue(target.exists())
            assertEquals(1_000L, target.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun assertFailsWithResizeError(block: () -> Unit): ResizeError {
        try {
            block()
        } catch (e: ResizeError) {
            return e
        }
        throw AssertionError("expected a ResizeError to be thrown")
    }
}
