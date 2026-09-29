package com.imageresizer.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imageresizer.app.MainActivity
import com.imageresizer.app.R
import com.imageresizer.app.ads.AdServing
import com.imageresizer.app.billing.BillingService
import com.imageresizer.app.processing.BatchProcessor
import com.imageresizer.app.processing.BatchState
import com.imageresizer.app.processing.ItemState
import com.imageresizer.app.processing.ItemStatus
import com.imageresizer.app.processing.outputMimeFor
import com.imageresizer.app.store.UsageStore
import com.imageresizer.core.UsageState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Root UI: one screen for pick/target/start, one for the batch run.
 * All long-lived state (credits, batch, billing) lives outside — rotation just
 * re-reads the flows.
 */
@Composable
fun AppRoot(
    activity: MainActivity,
    processor: BatchProcessor,
    usageStore: UsageStore,
    ads: AdServing,
    billing: BillingService,
    incomingShares: MutableStateFlow<List<Uri>>,
) {
    val batch by processor.state.collectAsState()
    val usage by usageStore.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Selection survives rotation; restore/save as URI strings (Bundle-safe).
    var selected by rememberSaveable(
        stateSaver = Saver<List<Uri>, List<String>>(
            save = { list -> list.map { it.toString() } },
            restore = { list -> list.map(Uri::parse) },
        ),
    ) { mutableStateOf(emptyList()) }
    var targetUnit by rememberSaveable { mutableStateOf(TargetSizes.defaultUnit) }
    var targetValue by rememberSaveable { mutableStateOf(TargetSizes.defaultValue) }
    var showLimit by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var awaitingRestore by remember { mutableStateOf(false) }

    // Photos shared to us from other apps append to the selection (deduped,
    // same rule as picking in waves); the flow is cleared once applied.
    LaunchedEffect(Unit) {
        incomingShares.collect { shared ->
            if (shared.isNotEmpty()) {
                selected = (selected + shared).distinctBy { it.toString() }
                incomingShares.value = emptyList()
            }
        }
    }

    // Billing listener: the local premium flag flips from Play's answer, and a
    // premium flip unblocks a paused batch immediately.
    DisposableEffect(Unit) {
        val listener = object : BillingService.Listener {
            override fun onEntitlementChanged(isPremium: Boolean) {
                usageStore.setPremium(isPremium)
                if (isPremium) {
                    processor.resume()
                    notice = null
                } else if (awaitingRestore) {
                    notice = "No purchases found"
                }
                awaitingRestore = false
            }

            override fun onBillingUnavailable(message: String) {
                notice = message.ifBlank { "Billing unavailable" }
                awaitingRestore = false
            }
        }
        billing.setListener(listener)
        // First query (also the "Restore" entry point) now that someone listens.
        billing.queryEntitlement()
        onDispose { billing.setListener(null) }
    }

    LaunchedEffect(batch?.pausedForCredits) {
        showLimit = batch?.pausedForCredits == true
    }

    fun watchAd() {
        if (usage.isPremium) return
        scope.launch {
            val granted = ads.showRewarded(activity)
            if (granted) {
                usageStore.grantRewardedAd()
                processor.resume()
                notice = "+5 credits added"
            } else {
                notice = "Ad not available right now — try again"
            }
        }
    }

    // Picking again appends (the photo picker can cap a single session, so 500+
    // batches are built by picking in waves — deduped by URI).
    val appendPicked: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) selected = (selected + uris).distinctBy { it.toString() }
    }
    val pickLarge = remember {
        ActivityResultContracts.PickMultipleVisualMedia(500)
    }
    val pickFallback = remember {
        ActivityResultContracts.PickMultipleVisualMedia()
    }
    val largeLauncher = rememberLauncherForActivityResult(pickLarge, appendPicked)
    val fallbackLauncher = rememberLauncherForActivityResult(pickFallback, appendPicked)

    val targetBytes = TargetSizes.bytes(targetUnit, targetValue)
    val activeBatch: BatchState? =
        batch?.takeIf { it.items.isNotEmpty() }

    Scaffold(
        // Free tier only: the lifetime unlock removes the banner (and all ads).
        bottomBar = {
            if (!usage.isPremium) {
                BannerAd()
            }
        },
    ) { padding ->
        if (activeBatch == null) {
            HomeScreen(
                modifier = Modifier.padding(padding),
                targetUnit = targetUnit,
                targetValue = targetValue,
                targetBytes = targetBytes,
                onTargetChange = { unit, value ->
                    targetUnit = unit
                    targetValue = value
                },
                selected = selected,
                usage = usage,
                onPick = {
                    val request = PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                    )
                    try {
                        largeLauncher.launch(request)
                    } catch (_: Exception) {
                        fallbackLauncher.launch(request)
                    }
                },
                onClearSelection = { selected = emptyList() },
                onRemovePhoto = { uri -> selected = selected.filterNot { it == uri } },
                onResize = {
                    if (selected.isNotEmpty()) {
                        processor.start(selected.map { it.toString() }, targetBytes)
                    }
                },
                onWatchAd = ::watchAd,
                onUnlock = { billing.purchase(activity) },
                onRestore = {
                    awaitingRestore = true
                    billing.queryEntitlement()
                },
            )
        } else {
            BatchScreen(
                modifier = Modifier.padding(padding),
                state = activeBatch,
                onDone = { processor.clear() },
                onDoneFinished = {
                    // Task-complete → home is the natural interstitial moment
                    // (free tier only; "Stop here" aborts skip it).
                    processor.clear()
                    if (!usage.isPremium) {
                        scope.launch { ads.showInterstitial(activity) }
                    }
                },
                onResume = { processor.resume() },
                onCancel = { processor.cancel() },
                onGetCredits = { showLimit = true },
            )
        }
    }

    if (showLimit && activeBatch?.pausedForCredits == true) {
        LimitDialog(
            usage = usage,
            onWatchAd = ::watchAd,
            onUnlock = { billing.purchase(activity) },
            onRestore = {
                awaitingRestore = true
                billing.queryEntitlement()
            },
            onDismiss = { showLimit = false },
        )
    }

    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } },
            text = { Text(message) },
        )
    }
}

// MARK: - Home

@Composable
private fun HomeScreen(
    modifier: Modifier,
    targetUnit: SizeUnit,
    targetValue: Int,
    targetBytes: Long,
    onTargetChange: (SizeUnit, Int) -> Unit,
    selected: List<Uri>,
    usage: UsageState,
    onPick: () -> Unit,
    onClearSelection: () -> Unit,
    onRemovePhoto: (Uri) -> Unit,
    onResize: () -> Unit,
    onWatchAd: () -> Unit,
    onUnlock: () -> Unit,
    onRestore: () -> Unit,
) {
    val selectedCount = selected.size
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
            Text("Photo Compressor", style = MaterialTheme.typography.headlineMedium)
        }
        Text(
            "Compress photos under a size limit. Everything runs on your device — " +
                "photos are never uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SizePicker(
            unit = targetUnit,
            value = targetValue,
            onChange = onTargetChange,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Output will be at most ${formatBytes(targetBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (selectedCount == 0) "No photos selected" else "$selectedCount selected",
                modifier = Modifier.weight(1f),
            )
            if (selectedCount > 0) {
                TextButton(onClick = onClearSelection) { Text("Clear") }
            }
            OutlinedButton(onClick = onPick) { Text("Choose photos") }
        }

        if (selectedCount > 0) {
            ThumbnailRow(uris = selected, onRemove = onRemovePhoto)
        }

        Button(
            onClick = onResize,
            enabled = selectedCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (selectedCount == 0) "Pick photos first" else "Resize $selectedCount photos")
        }

        Spacer(Modifier.height(8.dp))

        // Free tier: credits + both monetization doors.
        if (usage.isPremium) {
            Text("Lifetime unlocked — ads removed", color = MaterialTheme.colorScheme.primary)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Credits: ${usage.remainingFreeCredits}",
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onWatchAd) { Text("Watch ad +5") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUnlock) { Text("Unlock lifetime — $5") }
                TextButton(onClick = onRestore) { Text("Restore") }
            }
        }
        Text(
            "1 credit is used per photo that actually needs re-encoding. " +
                "Photos already under the limit are always free.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Batch

@Composable
private fun BatchScreen(
    modifier: Modifier,
    state: BatchState,
    onDone: () -> Unit,
    onDoneFinished: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onGetCredits: () -> Unit,
) {
    val terminal = state.items.count {
        it.status == ItemStatus.DONE || it.status == ItemStatus.FAILED ||
            it.status == ItemStatus.CANCELLED
    }
    val hasPending = state.items.any {
        it.status == ItemStatus.PENDING || it.status == ItemStatus.RUNNING ||
            it.status == ItemStatus.BLOCKED
    }
    val finished = !state.running && !state.pausedForCredits && !hasPending
    val done = state.items.count { it.status == ItemStatus.DONE }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (finished) "Done" else "Resizing…",
            style = MaterialTheme.typography.headlineSmall,
        )

        if (finished) {
            Text(
                "$done of ${state.items.size} photos saved under " +
                    "${formatBytes(state.targetBytes)}.",
            )
            state.items.filter { it.status == ItemStatus.FAILED }.forEach { item ->
                Text(
                    "${item.name}: ${item.error ?: "failed"}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val context = LocalContext.current
            val outputs = state.items.mapNotNull { item ->
                if (item.status == ItemStatus.DONE) item.outputUri?.let(Uri::parse) else null
            }
            if (outputs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            OutputShare.intent(context, outputs)?.let { shareIntent ->
                                try {
                                    context.startActivity(shareIntent)
                                } catch (_: Exception) {
                                    // No receiving app — extremely rare behind a chooser.
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (outputs.size == 1) "Share" else "Share ${outputs.size}")
                    }
                    Button(onClick = onDoneFinished, modifier = Modifier.weight(1f)) {
                        Text("Done")
                    }
                }
            } else {
                Button(onClick = onDoneFinished, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
            }
        } else {
            LinearProgressIndicator(
                progress = {
                    if (state.items.isEmpty()) 0f else terminal.toFloat() / state.items.size
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("$terminal of ${state.items.size}")
            if (state.pausedForCredits) {
                Text(
                    "Out of credits for the remaining photos.",
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onGetCredits) { Text("Get credits") }
                    OutlinedButton(onClick = onDone) { Text("Stop here") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.running) {
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    } else {
                        Button(onClick = onResume) { Text("Resume") }
                        OutlinedButton(onClick = onDone) { Text("Stop here") }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.items) { item -> ItemRow(item) }
        }
    }
}

@Composable
private fun ItemRow(item: ItemState) {
    val context = LocalContext.current
    // Saved outputs open in the gallery (FileProvider-routed on API 28).
    val outputUri = if (item.status == ItemStatus.DONE) {
        item.outputUri?.let(Uri::parse)
    } else {
        null
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (outputUri != null) {
                    Modifier.clickable {
                        val mime = outputMimeFor(
                            item.outputName?.substringAfterLast('.', "jpg") ?: "jpg",
                        )
                        try {
                            context.startActivity(
                                OutputShare.viewIntent(context, outputUri, mime),
                            )
                        } catch (_: Exception) {
                            // No viewer for this type — silently stay on the list.
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            val (line, color) = when (item.status) {
                ItemStatus.PENDING -> "Waiting" to null
                ItemStatus.RUNNING -> "Resizing…" to null
                ItemStatus.BLOCKED -> "Needs a credit" to MaterialTheme.colorScheme.primary
                ItemStatus.CANCELLED -> "Cancelled" to MaterialTheme.colorScheme.onSurfaceVariant
                ItemStatus.FAILED ->
                    (item.error ?: "Failed") to MaterialTheme.colorScheme.error
                ItemStatus.DONE -> buildString {
                    append(
                        if (item.passThrough == true) "Saved (original kept) · "
                        else "Saved (re-encoded) · ",
                    )
                    if (item.width != null && item.height != null) {
                        append("${item.width}×${item.height} · ")
                    }
                    item.bytes?.let { append(formatBytes(it)) }
                } to MaterialTheme.colorScheme.primary
            }
            Text(
                line,
                color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// MARK: - Limit dialog (rewarded ad / purchase / restore)

@Composable
private fun LimitDialog(
    usage: UsageState,
    onWatchAd: () -> Unit,
    onUnlock: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Out of credits") },
        text = {
            Text(
                "You have ${usage.remainingFreeCredits} credits left. " +
                    "Watch an ad for 5 more, or unlock lifetime for $5 to remove limits " +
                    "and ads.",
            )
        },
        confirmButton = { TextButton(onClick = onWatchAd) { Text("Watch ad +5") } },
        dismissButton = {
            Row {
                TextButton(onClick = onUnlock) { Text("Unlock $5") }
                TextButton(onClick = onRestore) { Text("Restore") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        },
    )
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
