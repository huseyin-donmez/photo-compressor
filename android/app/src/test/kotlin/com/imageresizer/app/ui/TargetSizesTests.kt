package com.imageresizer.app.ui

import com.imageresizer.core.ProductRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure math for the size wheel: byte conversion, the 100 KB contract floor, and
 * the KB/MB conversion snap. The composable wheel itself needs a device.
 */
class TargetSizesTests {

    @Test
    fun everyWheelValueIsAValidTarget() {
        assertTrue(TargetSizes.kbValues.isNotEmpty())
        assertTrue(TargetSizes.mbValues.isNotEmpty())
        for (unit in SizeUnit.entries) {
            for (value in TargetSizes.values(unit)) {
                assertTrue(
                    "$value ${unit.name} below the contract minimum",
                    TargetSizes.bytes(unit, value) >= ProductRules.minimumTargetBytes,
                )
            }
        }
    }

    @Test
    fun kbWheelStartsAtTheContractMinimum() {
        assertEquals(
            ProductRules.minimumTargetBytes,
            TargetSizes.bytes(SizeUnit.KB, TargetSizes.kbValues.first()),
        )
    }

    @Test
    fun byteConversion() {
        assertEquals(1024L, TargetSizes.bytes(SizeUnit.KB, 1))
        assertEquals(1024L * 1024, TargetSizes.bytes(SizeUnit.MB, 1))
        assertEquals(5L * 1024 * 1024, TargetSizes.bytes(SizeUnit.MB, 5))
        assertEquals(100L * 1024, TargetSizes.bytes(SizeUnit.KB, 100))
    }

    @Test
    fun defaultSelectionIsFiveMegabytes() {
        assertEquals(SizeUnit.MB, TargetSizes.defaultUnit)
        assertEquals(5, TargetSizes.defaultValue)
        assertTrue(TargetSizes.values(TargetSizes.defaultUnit).contains(TargetSizes.defaultValue))
        assertEquals(
            5L * 1024 * 1024,
            TargetSizes.bytes(TargetSizes.defaultUnit, TargetSizes.defaultValue),
        )
    }

    @Test
    fun unitSwitchAlwaysLandsOnAValidValue() {
        for (value in TargetSizes.kbValues) {
            val snapped = TargetSizes.nearest(SizeUnit.MB, TargetSizes.bytes(SizeUnit.KB, value))
            assertTrue(
                "$value KB snapped to invalid $snapped MB",
                TargetSizes.mbValues.contains(snapped),
            )
        }
        for (value in TargetSizes.mbValues) {
            val snapped = TargetSizes.nearest(SizeUnit.KB, TargetSizes.bytes(SizeUnit.MB, value))
            assertTrue(
                "$value MB snapped to invalid $snapped KB",
                TargetSizes.kbValues.contains(snapped),
            )
        }
    }

    @Test
    fun snapPicksTheClosestValueAndRoundTripsInPlace() {
        assertEquals(2, TargetSizes.nearest(SizeUnit.MB, (2.4 * 1024 * 1024).toLong()))
        assertEquals(500, TargetSizes.nearest(SizeUnit.KB, 600L * 1024))
        assertEquals(1000, TargetSizes.nearest(SizeUnit.KB, 900L * 1024))

        // An exact wheel value must survive snapping within its own unit.
        for (value in TargetSizes.kbValues) {
            assertEquals(
                value,
                TargetSizes.nearest(SizeUnit.KB, TargetSizes.bytes(SizeUnit.KB, value)),
            )
        }
        for (value in TargetSizes.mbValues) {
            assertEquals(
                value,
                TargetSizes.nearest(SizeUnit.MB, TargetSizes.bytes(SizeUnit.MB, value)),
            )
        }
    }
}
