package com.imageresizer.app.processing

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.imageresizer.core.ResizeError
import java.io.File
import java.io.IOException

/**
 * Publishes verified output bytes into the shared gallery.
 * The hard size guarantee happens BEFORE this (DiskOutput.writeVerified on a real
 * file) — this step copies the exact verified bytes, nothing is re-encoded here.
 */
object OutputPublisher {

    /** @return the gallery Uri, or null when the write failed. */
    fun publish(context: Context, displayName: String, mime: String, bytes: ByteArray): Uri? =
        try {
            if (Build.VERSION.SDK_INT >= 29) publishMediaStore(context, displayName, mime, bytes)
            else publishLegacy(context, displayName, mime, bytes)
        } catch (_: Exception) {
            null
        }

    private fun publishMediaStore(
        context: Context,
        displayName: String,
        mime: String,
        bytes: ByteArray,
    ): Uri? {
        val resolver: ContentResolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/ImageResizer",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("insert failed")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("openOutputStream failed")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun publishLegacy(
        context: Context,
        displayName: String,
        mime: String,
        bytes: ByteArray,
    ): Uri? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "ImageResizer",
        )
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("mkdirs failed")
        val target = uniqueFile(dir, displayName)
        target.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime), null)
        return Uri.fromFile(target)
    }

    /** API 28 gallery: files must not clobber each other. */
    private fun uniqueFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }
}

fun outputMimeFor(extension: String): String = when (extension) {
    "jpg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "heic" -> "image/heic"
    else -> "image/jpeg"
}

fun mapResizeError(e: ResizeError): String = when (e) {
    is ResizeError.UnsupportedFormat -> "Unsupported format, skipped"
    ResizeError.Corrupted -> "File is damaged"
    ResizeError.OutOfMemory -> "Not enough memory"
    ResizeError.StorageFull -> "Storage is full"
    ResizeError.EncodeFailed -> "Could not encode"
    ResizeError.OutputSaveFailed -> "Could not save"
    is ResizeError.TargetExceeded -> "Size guarantee violated (reported)"
    ResizeError.TargetInfeasible -> "Target too small for this photo"
    ResizeError.Cancelled -> "Cancelled"
}
