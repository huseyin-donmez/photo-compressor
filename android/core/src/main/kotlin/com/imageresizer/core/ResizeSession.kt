package com.imageresizer.core

/** An already-decoded (and dimension-capped) image held for encoding. */
interface ResizableImage {
    val pixelWidth: Int
    val pixelHeight: Int
}

/**
 * Seam between the pure algorithm and a platform codec.
 * One session processes exactly one input file.
 * Mirrors ios/ImageResizerCore/ResizeSession.swift — behavior must stay identical.
 */
interface ResizeSession {
    /** Header-only read: dimensions, format, alpha, file size. Must not decode pixels. */
    fun readHeader(): ImageHeader

    /**
     * The original encoded bytes with sensitive metadata stripped WITHOUT
     * re-encoding (byte-level surgery). Return null when the platform cannot do
     * this safely — the caller then falls back to the normal re-encode pipeline,
     * which strips GPS for sure.
     */
    fun passThroughStripMetadata(): ByteArray?

    /** Bounds-decoded image, longest edge ≤ [longestEdge], EXIF orientation baked in. */
    fun decode(longestEdge: Int): ResizableImage

    /**
     * Encode at [quality] (0…1) AND merge preserved (GPS-stripped) metadata.
     * The returned bytes are final: what the search measures is what gets written.
     */
    fun encodeFinal(image: ResizableImage, quality: Double): ByteArray
}
