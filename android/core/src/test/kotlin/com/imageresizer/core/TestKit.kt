package com.imageresizer.core

import kotlin.test.assertTrue

/**
 * Comparison assertion helpers — kotlin.test ships equality asserts only,
 * while the mirrored XCTest suites assert ordering heavily.
 */
internal fun <T : Comparable<T>> assertLe(actual: T, expected: T, message: String? = null) {
    assertTrue(actual <= expected, describe(message, actual, "≤", expected))
}

internal fun <T : Comparable<T>> assertLt(actual: T, expected: T, message: String? = null) {
    assertTrue(actual < expected, describe(message, actual, "<", expected))
}

internal fun <T : Comparable<T>> assertGe(actual: T, expected: T, message: String? = null) {
    assertTrue(actual >= expected, describe(message, actual, "≥", expected))
}

internal fun <T : Comparable<T>> assertGt(actual: T, expected: T, message: String? = null) {
    assertTrue(actual > expected, describe(message, actual, ">", expected))
}

private fun <T> describe(message: String?, actual: T, op: String, expected: T): String {
    val detail = "expected $actual $op $expected"
    return if (message != null) "$message: $detail" else detail
}
