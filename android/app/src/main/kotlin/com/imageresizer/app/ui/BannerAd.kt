package com.imageresizer.app.ui

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.imageresizer.app.BuildConfig

/**
 * Anchored adaptive banner for the bottom of the scaffold — free users only
 * (premium removes it: the $5 unlock promises "ads removed"). Sized to the
 * window width (50–90dp tall), loaded once per composition, paused/resumed
 * with the activity and destroyed when it leaves the composition.
 */
@Composable
internal fun BannerAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val adView = remember(context) {
        AdView(context).apply {
            setAdUnitId(BuildConfig.BANNER_AD_UNIT_ID)
            setAdSize(anchoredAdaptiveSize(context))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            loadAd(AdRequest.Builder().build())
        }
    }
    DisposableEffect(lifecycleOwner, adView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> adView.pause()
                Lifecycle.Event.ON_RESUME -> adView.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            adView.destroy()
        }
    }
    AndroidView(
        factory = { adView },
        // Flush to the screen edge but clear of gesture navigation.
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
    )
}

/** Width-matched anchored adaptive size (orientation aware). */
private fun anchoredAdaptiveSize(context: Context): AdSize {
    val metrics = context.resources.displayMetrics
    val widthDp = (metrics.widthPixels / metrics.density).toInt().coerceAtLeast(320)
    return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
}
