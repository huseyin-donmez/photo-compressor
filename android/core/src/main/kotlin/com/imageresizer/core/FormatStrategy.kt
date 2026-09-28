package com.imageresizer.core

/**
 * Automatic input→output format rules (Android side of docs/ALGORITHM.md).
 * No user-facing format option exists anywhere.
 */
object FormatStrategy {
    /**
     * Returns null when the input format is unsupported (skip item, no credit).
     * Android column of the matrix: HEIC→JPEG (no native HEIC encoder),
     * WebP→WebP (native encoder, container preserved).
     */
    fun outputFormat(source: SourceFormat, hasAlpha: Boolean): OutputFormat? =
        when (source) {
            // Native JPEG encoder.
            SourceFormat.JPEG -> OutputFormat.JPEG

            // Alpha must stay lossless (PNG). No-alpha photo PNGs convert to JPEG to
            // preserve resolution — priority #1.
            SourceFormat.PNG -> if (hasAlpha) OutputFormat.PNG else OutputFormat.JPEG

            // No native HEIC encoder (androidx.heifwriter rejected).
            SourceFormat.HEIC -> OutputFormat.JPEG

            // Native WebP encoder: container preserved either way.
            SourceFormat.WEBP -> OutputFormat.WEBP

            // First frame only; alpha forces the lossless container.
            SourceFormat.GIF, SourceFormat.BMP ->
                if (hasAlpha) OutputFormat.PNG else OutputFormat.JPEG

            SourceFormat.TIFF, SourceFormat.RAW, SourceFormat.OTHER -> null
        }
}
