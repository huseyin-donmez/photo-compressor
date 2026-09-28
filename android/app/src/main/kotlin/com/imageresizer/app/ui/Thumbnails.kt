package com.imageresizer.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long edge of a decoded thumbnail, in px. */
private const val THUMB_TARGET_PX = 192

/** Bounded LRU for decoded thumbnails (access order, synchronized). */
internal class ThumbnailCache(private val maxBytes: Long = 8L * 1024 * 1024) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var usedBytes = 0L

    fun get(key: String): Bitmap? = synchronized(lock) { entries[key] }

    fun put(key: String, bitmap: Bitmap) {
        synchronized(lock) {
            entries.remove(key)?.let { usedBytes -= it.allocationByteCount }
            entries[key] = bitmap
            usedBytes += bitmap.allocationByteCount
            while (usedBytes > maxBytes && entries.size > 1) {
                val eldestKey = entries.entries.iterator().next().key
                entries.remove(eldestKey)?.let { usedBytes -= it.allocationByteCount }
            }
        }
    }
}

/**
 * Decode a small, EXIF-rotation-baked preview of [uri] — sampled twice (power-of-2
 * then exact) so a batch of 500 selections stays within the 8 MB cache. Null if
 * the source is unreadable (deleted, unsupported codec, permission gone).
 */
internal fun decodeThumbnail(context: Context, uri: Uri): Bitmap? {
    try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= THUMB_TARGET_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        // inSampleSize only takes powers of two — finish the downscale exactly.
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge > THUMB_TARGET_PX * 2) {
            val scale = (THUMB_TARGET_PX * 2).toFloat() / edge
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                maxOf(1, (bitmap.width * scale).toInt()),
                maxOf(1, (bitmap.height * scale).toInt()),
                true,
            )
            if (scaled != bitmap) bitmap.recycle()
            bitmap = scaled
        }
        return rotateForExif(context, uri, bitmap)
    } catch (_: Exception) {
        return null
    }
}

private fun rotateForExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = try {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bitmap
    val rotated = Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height,
        Matrix().apply { postRotate(degrees) }, true,
    )
    if (rotated != bitmap) bitmap.recycle()
    return rotated
}

/**
 * Horizontal strip of the current selection above the resize button. Tapping a
 * preview (or its × badge) removes that photo from the selection.
 */
@Composable
internal fun ThumbnailRow(
    uris: List<Uri>,
    onRemove: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cache = remember { ThumbnailCache() }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(uris, key = { it.toString() }) { uri ->
            ThumbnailItem(uri = uri, cache = cache, onRemove = onRemove)
        }
    }
}

@Composable
private fun ThumbnailItem(
    uri: Uri,
    cache: ThumbnailCache,
    onRemove: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val key = uri.toString()
    var bitmap by remember(uri) { mutableStateOf(cache.get(key)) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(key) {
        if (bitmap == null && !failed) {
            val decoded = withContext(Dispatchers.IO) { decodeThumbnail(context, uri) }
            if (decoded != null) {
                cache.put(key, decoded)
                bitmap = decoded
            } else {
                failed = true
            }
        }
    }

    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { onRemove(uri) },
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        when {
            current != null -> Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "Selected photo — tap to remove",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            failed -> Text("?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("×", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}
