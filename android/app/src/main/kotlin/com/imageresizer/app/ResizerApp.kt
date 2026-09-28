package com.imageresizer.app

import android.app.Application
import android.net.Uri
import com.imageresizer.app.ads.AdMobAds
import com.imageresizer.app.billing.PlayBilling
import com.imageresizer.app.processing.BatchProcessor
import com.imageresizer.app.store.UsageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manual composition root (no DI framework — docs/ALGORITHM.md): every long-lived
 * object is created once, here.
 *
 * [appScope] is Application-wide on purpose: the batch processor must outlive
 * rotation/navigation (foreground-only in v1, but lifecycle-independent so a
 * WorkManager driver can be swapped in later).
 */
class ResizerApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val usageStore by lazy { UsageStore(this) }
    val ads by lazy { AdMobAds(this) }
    val billing by lazy { PlayBilling(this) }
    val processor by lazy { BatchProcessor(appScope, this, usageStore) }

    /**
     * URIs shared *to* this app (ACTION_SEND*), waiting to be appended to the
     * selection by AppRoot; consumed (cleared) as soon as they are applied.
     */
    val incomingShares = MutableStateFlow<List<Uri>>(emptyList())

    override fun onCreate() {
        super.onCreate()
        // Local entitlement query on connect — restore = same query (no backend).
        billing.connect()
    }
}
