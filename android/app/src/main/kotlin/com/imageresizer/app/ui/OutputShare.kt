package com.imageresizer.app.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Building share intents for batch outputs.
 *
 * API 28's legacy publish returns `file://` URIs; those throw
 * FileUriExposedException when they leave the process, so they are routed
 * through the FileProvider declared in the manifest (29+ outputs are
 * MediaStore `content://` URIs and pass straight through).
 */
internal object OutputShare {

    fun shareable(context: Context, uri: Uri): Uri {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return uri
        val path = uri.path ?: return uri
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        } catch (_: Exception) {
            uri
        }
    }

    /** Chooser for one or many image outputs, or null when there is nothing to share. */
    fun intent(context: Context, uris: List<Uri>): Intent? {
        if (uris.isEmpty()) return null
        val targets = uris.map { shareable(context, it) }
        val target = if (targets.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, targets[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(targets))
        }
        target.type = "image/*"
        target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(target, "Share resized photos")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Launch handler for a single saved output (tap-to-open in the gallery). */
    fun viewIntent(context: Context, uri: Uri, mime: String): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(shareable(context, uri), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
