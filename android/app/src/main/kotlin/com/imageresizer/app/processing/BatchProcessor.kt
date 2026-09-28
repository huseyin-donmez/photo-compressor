package com.imageresizer.app.processing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.imageresizer.app.session.AndroidImageSession
import com.imageresizer.app.session.InputSniffer
import com.imageresizer.app.store.UsageStore
import com.imageresizer.core.BatchPlan
import com.imageresizer.core.DiskOutput
import com.imageresizer.core.Entitlement
import com.imageresizer.core.OutputFormat
import com.imageresizer.core.ResizeError
import com.imageresizer.core.ResizeParameters
import com.imageresizer.core.ResizeReport
import com.imageresizer.core.SourceFormat
import com.imageresizer.core.TargetSizeResizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

enum class ItemStatus { PENDING, RUNNING, DONE, FAILED, BLOCKED, CANCELLED }

data class ItemState(
    val uri: String,
    val name: String,
    /** Header-only size probe; null = unknown (treated as "needs work" for planning). */
    val size: Long?,
    val status: ItemStatus = ItemStatus.PENDING,
    val outputName: String? = null,
    val outputUri: String? = null,
    val bytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val passThrough: Boolean? = null,
    val error: String? = null,
)

data class BatchState(
    val targetBytes: Long,
    val items: List<ItemState>,
    val running: Boolean,
    /** True when the run is waiting for a credit (limit screen shown). */
    val pausedForCredits: Boolean,
) {
    val doneCount: Int get() = items.count { it.status == ItemStatus.DONE }
}

/**
 * Batch driver. Lives in the Application scope so it survives rotation and
 * navigation (foreground-only in v1, but lifecycle-independent by construction —
 * a future WorkManager driver can reuse [persist]/[load] per-item state).
 *
 * Credit rules (docs/ALGORITHM.md): pass-through and failures never charge;
 * exactly one credit per successful re-encode, checked BEFORE the work starts
 * when the input is known to exceed the target.
 */
class BatchProcessor(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val usageStore: UsageStore,
) {
    private val prefs = appContext.getSharedPreferences("batch", Context.MODE_PRIVATE)
    private val workDir = File(appContext.cacheDir, "work").apply { mkdirs() }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<BatchState?> = _state

    private var job: Job? = null
    private var resumeSignal: CompletableDeferred<Unit>? = null

    val isActive: Boolean get() = job?.isActive == true

    /** Preflight + start. Returns false if a batch is already running. */
    fun start(uris: List<String>, targetBytes: Long): Boolean {
        if (isActive) return false
        val items = uris.map(::probeItem)
        val neededWork = items.count { it.size == null || it.size > targetBytes }
        val blocked = Entitlement.plan(neededWork, usageStore.current) is BatchPlan.Blocked
        _state.value = BatchState(
            targetBytes = targetBytes,
            items = items,
            running = false,
            pausedForCredits = blocked,
        )
        persist()
        if (!blocked) launchRun()
        return true
    }

    /** After a credit grant / purchase / restore: unblock and continue. */
    fun resume() {
        if (_state.value?.pausedForCredits != true) return
        resumeSignal?.let { it.complete(Unit); return }
        if (isActive) return
        launchRun()
    }

    fun cancel() {
        // Cancelling the job also interrupts an in-flight credit wait (await is
        // cancellable); the run loop marks BLOCKED/RUNNING items CANCELLED.
        job?.cancel()
    }

    /**
     * Leave the batch screen. If a run is active (including paused mid-run) the
     * job is cancelled first and the state is wiped once it has unwound, so a
     * cancelled loop can never keep writing items nobody is watching.
     */
    fun clear() {
        val active = job
        if (active != null && active.isActive) {
            active.cancel()
            scope.launch {
                active.join()
                _state.value = null
                prefs.edit().remove(KEY_BATCH).apply()
            }
            return
        }
        _state.value = null
        prefs.edit().remove(KEY_BATCH).apply()
    }

    // MARK: - Run loop

    private fun launchRun() {
        job = scope.launch {
            try {
                mutate { it.copy(running = true, pausedForCredits = false) }
                val snapshot = _state.value ?: return@launch
                for (index in snapshot.items.indices) {
                    val item = _state.value!!.items[index]
                    if (item.status == ItemStatus.DONE ||
                        item.status == ItemStatus.FAILED ||
                        item.status == ItemStatus.CANCELLED
                    ) continue

                    ensureCreditFor(index, snapshot.targetBytes)

                    mutateAt(index) { it.copy(status = ItemStatus.RUNNING) }
                    val result = withContext(Dispatchers.IO) {
                        processOne(_state.value!!.items[index], snapshot.targetBytes)
                    }
                    mutateAt(index) { result }

                    // Exactly one credit per successful re-encode, never for
                    // pass-through or failures (grandfathered if credits ran out
                    // on a surprise re-encode that passed the preflight gate).
                    if (result.status == ItemStatus.DONE &&
                        result.passThrough == false &&
                        !usageStore.current.isPremium &&
                        usageStore.current.remainingFreeCredits > 0
                    ) {
                        usageStore.consumeCredit()
                    }
                }
                mutate { it.copy(running = false, pausedForCredits = false) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    mutate { st ->
                        st.copy(
                            running = false,
                            pausedForCredits = false,
                            items = st.items.map {
                                if (it.status == ItemStatus.RUNNING || it.status == ItemStatus.BLOCKED) {
                                    it.copy(status = ItemStatus.CANCELLED, error = "Cancelled")
                                } else it
                            },
                        )
                    }
                    persist()
                }
                throw e
            }
        }
    }

    /**
     * Credit gate for one item: when the input is known to exceed the target it
     * WILL need a re-encode → stop here (BatchPlan.Partial semantics) and wait
     * for the limit screen before any work happens.
     */
    private suspend fun ensureCreditFor(index: Int, targetBytes: Long) {
        val item = _state.value!!.items[index]
        val needsWork = item.size == null || item.size > targetBytes
        if (!needsWork || usageStore.current.isPremium ||
            usageStore.current.remainingFreeCredits > 0
        ) return

        mutateAt(index) { it.copy(status = ItemStatus.BLOCKED) }
        mutate { it.copy(running = false, pausedForCredits = true) }
        val signal = CompletableDeferred<Unit>()
        resumeSignal = signal
        try {
            signal.await()
        } finally {
            resumeSignal = null
        }
        if (!scope.isActive) throw CancellationException("cancelled while blocked")
        mutate { it.copy(running = true, pausedForCredits = false) }
    }

    // MARK: - One item

    private suspend fun processOne(item: ItemState, targetBytes: Long): ItemState {
        val uri = Uri.parse(item.uri)
        val ext = item.name.substringAfterLast('.', "dat").lowercase()
        val input = File(workDir, "in_${item.uri.hashCode()}.$ext")
        val verified = File(workDir, "verified.tmp")
        try {
            val src = appContext.contentResolver.openInputStream(uri)
                ?: return item.failed("Could not read input")
            src.use { ins -> input.outputStream().use { out -> ins.copyTo(out) } }

            val session = AndroidImageSession(appContext, input, workDir)
            val params = ResizeParameters.standard.copy(maxDecodePixels = decodeBudgetPixels())
            val result = TargetSizeResizer(params).run(session, targetBytes)

            // Hard guarantee on a real file, then publish the exact verified bytes.
            DiskOutput.writeVerified(result.data, verified, targetBytes)
            // Pass-through keeps the original container — sniff the actual bytes;
            // re-encode output comes from the format strategy.
            val extOut = when (result.report.outcome) {
                ResizeReport.Outcome.PASS_THROUGH -> when (InputSniffer.sniff(result.data)) {
                    SourceFormat.PNG -> "png"
                    SourceFormat.WEBP -> "webp"
                    SourceFormat.GIF -> "gif"
                    SourceFormat.BMP -> "bmp"
                    SourceFormat.HEIC -> "heic"
                    else -> "jpg"
                }
                ResizeReport.Outcome.PROCESSED -> when (result.report.outputFormat) {
                    OutputFormat.PNG -> "png"
                    OutputFormat.WEBP -> "webp"
                    OutputFormat.HEIC -> "heic"
                    OutputFormat.JPEG, null -> "jpg"
                }
            }
            val base = item.name.substringBeforeLast('.', item.name)
            val outName = "${base}_resized.$extOut"
            val galleryUri = OutputPublisher.publish(
                appContext, outName, outputMimeFor(extOut), verified.readBytes(),
            ) ?: return item.failed("Could not save to gallery")

            return item.copy(
                status = ItemStatus.DONE,
                outputName = outName,
                outputUri = galleryUri.toString(),
                bytes = verified.length(),
                width = result.report.pixelWidth,
                height = result.report.pixelHeight,
                passThrough = result.report.outcome == ResizeReport.Outcome.PASS_THROUGH,
                error = null,
            )
        } catch (e: ResizeError.Cancelled) {
            return item.copy(status = ItemStatus.CANCELLED, error = mapResizeError(e))
        } catch (e: ResizeError) {
            return item.failed(mapResizeError(e))
        } catch (oom: OutOfMemoryError) {
            return item.failed("Not enough memory")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return item.failed(e.message ?: "Failed")
        } finally {
            input.delete()
            verified.delete()
        }
    }

    private fun ItemState.failed(message: String): ItemState =
        copy(status = ItemStatus.FAILED, error = message)

    // MARK: - Helpers

    /** `0.30 × maxMemory / 4 bytes-per-pixel`, capped at 60 MP (docs/ALGORITHM.md). */
    private fun decodeBudgetPixels(): Long {
        val byMemory = (Runtime.getRuntime().maxMemory() / 4.0 * 0.30).toLong()
        return min(60_000_000L, max(1_000_000L, byMemory))
    }

    private fun probeItem(uriString: String): ItemState {
        val uri = Uri.parse(uriString)
        var name = "image"
        var size: Long? = null
        // Best effort: a persistable grant lets a restored batch resume after a
        // process death/reboot (photo pickers may decline — then the item fails
        // with "Could not read input" instead of silently corrupting).
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // Temporary grant: still fully usable while this session is alive.
        }
        try {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameCol >= 0 && !cursor.isNull(nameCol)) {
                        name = cursor.getString(nameCol) ?: name
                    }
                    if (sizeCol >= 0 && !cursor.isNull(sizeCol)) {
                        size = cursor.getLong(sizeCol).takeIf { it > 0 }
                    }
                }
            }
        } catch (_: Exception) {
            // Unknown size → conservative (counts as needing work for planning).
        }
        return ItemState(uri = uriString, name = name, size = size)
    }

    private fun mutate(transform: (BatchState) -> BatchState) {
        _state.update { s -> s?.let(transform) }
        persist()
    }

    private fun mutateAt(index: Int, transform: (ItemState) -> ItemState) {
        mutate { st ->
            if (index !in st.items.indices) st
            else st.copy(items = st.items.mapIndexed { i, item -> if (i == index) transform(item) else item })
        }
    }

    // MARK: - Persistence (per-item, so a background driver can resume)

    private fun persist() {
        val snapshot = _state.value ?: return
        val items = JSONArray()
        for (item in snapshot.items) {
            items.put(
                JSONObject().apply {
                    put("uri", item.uri)
                    put("name", item.name)
                    put("size", item.size ?: -1L)
                    put("status", item.status.name)
                    put("outputName", item.outputName ?: JSONObject.NULL)
                    put("outputUri", item.outputUri ?: JSONObject.NULL)
                    put("bytes", item.bytes ?: -1L)
                    put("width", item.width ?: -1)
                    put("height", item.height ?: -1)
                    put("passThrough", item.passThrough ?: JSONObject.NULL)
                    put("error", item.error ?: JSONObject.NULL)
                },
            )
        }
        val root = JSONObject().apply {
            put("target", snapshot.targetBytes)
            put("paused", snapshot.pausedForCredits)
            put("items", items)
        }
        prefs.edit().putString(KEY_BATCH, root.toString()).apply()
    }

    private fun load(): BatchState? = try {
        val raw = prefs.getString(KEY_BATCH, null) ?: return null
        val root = JSONObject(raw)
        val items = root.getJSONArray("items")
        val parsed = (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            val status = runCatching { ItemStatus.valueOf(o.getString("status")) }
                .getOrDefault(ItemStatus.PENDING)
            ItemState(
                uri = o.getString("uri"),
                name = o.getString("name"),
                size = o.optLong("size", -1L).takeIf { it > 0 },
                // An interrupted RUN never resumes by itself — back to pending.
                status = if (status == ItemStatus.RUNNING) ItemStatus.PENDING else status,
                outputName = o.optString("outputName", null).takeIf { it != "null" },
                outputUri = o.optString("outputUri", null).takeIf { it != "null" },
                bytes = o.optLong("bytes", -1L).takeIf { it >= 0 },
                width = o.optInt("width", -1).takeIf { it > 0 },
                height = o.optInt("height", -1).takeIf { it > 0 },
                passThrough = if (o.isNull("passThrough")) null else o.getBoolean("passThrough"),
                error = o.optString("error", null).takeIf { it != "null" },
            )
        }
        BatchState(
            targetBytes = root.getLong("target"),
            items = parsed,
            running = false,
            pausedForCredits = root.optBoolean("paused", false),
        )
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val KEY_BATCH = "batch_state"
    }
}
