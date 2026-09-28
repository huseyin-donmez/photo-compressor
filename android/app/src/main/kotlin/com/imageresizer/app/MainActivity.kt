package com.imageresizer.app

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.imageresizer.app.ui.AppRoot
import com.imageresizer.app.ui.AppTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as ResizerApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Cold start from a share (ACTION_SEND*): append to the selection.
        receiveShare(intent)

        // API 28 only: legacy shared-storage write for the gallery publish path
        // (scoped storage covers 29+ without any permission).
        if (Build.VERSION.SDK_INT == 28 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE,
            )
        }

        // UMP consent + warm ad load need an Activity. Best effort (never throws).
        app.ads.prepare(this)

        setContent {
            AppTheme {
                AppRoot(
                    activity = this,
                    processor = app.processor,
                    usageStore = app.usageStore,
                    ads = app.ads,
                    billing = app.billing,
                    incomingShares = app.incomingShares,
                )
            }
        }
    }

    // singleTask reuse: sharing into the running app lands here without a new
    // instance (the current selection survives).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }

    private fun receiveShare(intent: Intent?) {
        val uris = intent?.imageUris().orEmpty()
        if (uris.isNotEmpty()) {
            app.incomingShares.value =
                (app.incomingShares.value + uris).distinctBy { it.toString() }
        }
    }

    /** URIs carried by ACTION_SEND / ACTION_SEND_MULTIPLE (clip + extras + data). */
    @Suppress("DEPRECATION")
    private fun Intent.imageUris(): List<Uri> {
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return emptyList()
        }
        val found = mutableListOf<Uri>()
        clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let(found::add)
            }
        }
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            val list = if (Build.VERSION.SDK_INT >= 33) {
                getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            list?.let { for (item in it) if (item != null) found.add(item) }
        } else {
            val single = if (Build.VERSION.SDK_INT >= 33) {
                getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                getParcelableExtra(Intent.EXTRA_STREAM)
            }
            single?.let(found::add)
            // Some senders put the single image Uri in data instead.
            val uri = data
            if (uri != null &&
                (uri.scheme == ContentResolver.SCHEME_CONTENT ||
                    uri.scheme == ContentResolver.SCHEME_FILE)
            ) {
                found.add(uri)
            }
        }
        return found.distinctBy { it.toString() }
    }

    private companion object {
        const val REQUEST_STORAGE = 100
    }
}
