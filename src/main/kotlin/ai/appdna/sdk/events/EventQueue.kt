package ai.appdna.sdk.events

import ai.appdna.sdk.Log
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.network.ConnectivityMonitor
import ai.appdna.sdk.network.EventUploadResult
import ai.appdna.sdk.storage.EventDatabase
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Manages in-memory + disk event queue with automatic flushing.
 *
 * SPEC-067: Adaptive batch sizing, deflate compression, SQLite persistence.
 * SPEC-070-A A.16: Differentiated retry policy (4xx drop, 5xx/429 retry-with-backoff,
 *   pause after 5 consecutive failures).
 * SPEC-070-A A.17: In-memory cap of 1000 events; oldest are evicted to disk.
 * SPEC-070-A A.18: Observe ProcessLifecycle ON_STOP → flush + schedule background uploader.
 *
 * Behavioural parity with iOS `Events/EventQueue.swift`.
 */
internal class EventQueue(
    private val apiClient: ApiClient,
    private val eventDatabase: EventDatabase,
    private val connectivityMonitor: ConnectivityMonitor?,
    private val batchSize: Int,
    private val flushInterval: Long, // seconds
    private val context: Context? = null,
    private val backgroundUploadScheduler: BackgroundUploadScheduler? = null
) {
    private val queue = CopyOnWriteArrayList<JSONObject>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null

    /** SPEC-070-A A.16: Track in-flight retry/backoff state. */
    private val flushMutex = Mutex()
    @Volatile private var consecutiveFailures = 0
    @Volatile private var paused = false

    /** SPEC-070-A A.18: Lifecycle observer registered on ProcessLifecycleOwner.get(). */
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    /** SPEC-067: Returns adaptive batch size based on network conditions. */
    private val effectiveBatchSize: Int
        get() = connectivityMonitor?.adaptiveBatchSize ?: batchSize

    init {
        // Load persisted events from SQLite
        val stored = eventDatabase.loadAll()
        for (json in stored) {
            try {
                queue.add(JSONObject(json))
            } catch (e: Exception) {
                // SPEC-070-A G.11: surface JSON parse errors instead of dropping
                // them silently. A bad row in SQLite usually means the writer
                // changed the envelope shape — we want to know about it.
                Log.warning { "Skipping persisted event with malformed JSON: ${e.message}" }
            }
        }
        if (stored.isNotEmpty()) {
            Log.info("Loaded ${stored.size} persisted events from SQLite")
        }

        // SPEC-070-A A.17: Trim in-memory cap if SQLite returned more than maxInMemoryEvents.
        // Disk still has them all; in-memory is just a sliding window of the most recent.
        trimInMemoryCap()

        // Start auto-flush timer
        startAutoFlush()

        // SPEC-070-A A.18: Wire ProcessLifecycleOwner so app backgrounding flushes
        // the queue + schedules the WorkManager uploader for whatever remains.
        registerLifecycleObserver()
    }

    /**
     * Secondary constructor for backward compatibility during migration.
     */
    constructor(
        apiClient: ApiClient,
        storage: LocalStorage,
        batchSize: Int,
        flushInterval: Long
    ) : this(apiClient, EventDatabase(storage.context), null, batchSize, flushInterval, null, null)

    /**
     * Add an event to the queue.
     */
    fun enqueue(event: JSONObject) {
        // SPEC-070-A A.17: Cap in-memory queue. Disk persistence happens regardless,
        // so evicted events are not lost — just dropped from RAM.
        if (queue.size >= MAX_IN_MEMORY_EVENTS) {
            // Evict the oldest entries down to (cap - 1) so the new event fits.
            val excess = queue.size - MAX_IN_MEMORY_EVENTS + 1
            for (i in 0 until excess) {
                if (queue.isNotEmpty()) queue.removeAt(0)
            }
            Log.debug("In-memory event cap hit ($MAX_IN_MEMORY_EVENTS) — evicted $excess oldest events from RAM (still on disk)")
        }
        queue.add(event)
        // SPEC-067: Persist to SQLite (truth-of-record; survives process death)
        eventDatabase.insertEvent(event.toString())

        // SPEC-067: Check adaptive threshold
        val currentBatchSize = effectiveBatchSize
        if (currentBatchSize > 0 && queue.size >= currentBatchSize) {
            flush()
        }
    }

    /**
     * Force flush all queued events.
     * SPEC-070-A A.16: Manual flush also resets the "paused after N failures" gate.
     */
    fun flush() {
        scope.launch {
            // Manual flush implies user/UI intent — give the server another chance.
            paused = false
            consecutiveFailures = 0
            performFlushWithRetry()
        }
    }

    /**
     * SPEC-424 STEP-1a (CL-7): purge ALL pending events (in-memory + on-disk) WITHOUT uploading —
     * called when analytics consent is revoked so queued-but-unsent events are never transmitted.
     * A server-side consent gate is defeated if the SDK later flushes events captured while consent
     * was true, so revoke must drop them at the source.
     */
    fun clear() {
        queue.clear()
        eventDatabase.clearAll()
        Log.info("Event queue purged — analytics consent revoked")
    }

    /** SPEC-070-A A.18: Public hook for foreground events to retry the queue. */
    internal fun onAppForeground() {
        // Reset the pause gate on foreground (matches iOS "until next session" semantics).
        paused = false
        consecutiveFailures = 0
        flush()
    }

    /** SPEC-070-A A.18: Background hook — flush in-flight then hand off to WorkManager. */
    internal fun onAppBackground() {
        scope.launch {
            performFlushWithRetry()
            // Even if some events remain, hand off to WorkManager so they upload while suspended.
            backgroundUploadScheduler?.scheduleIfNeeded()
        }
    }

    private suspend fun performFlushWithRetry() {
        flushMutex.withLock {
            performFlushLocked()
        }
    }

    private suspend fun performFlushLocked() {
        if (paused) {
            Log.debug("Event flush paused after $consecutiveFailures consecutive failures — waiting for next foreground/manual flush")
            return
        }
        if (queue.isEmpty()) return

        val currentBatchSize = effectiveBatchSize
        if (currentBatchSize == 0) {
            Log.debug("No network — skipping flush, ${queue.size} events queued")
            return
        }

        val takeCount = currentBatchSize.coerceAtMost(queue.size)
        val batch = ArrayList(queue.take(takeCount))
        val batchArray = JSONArray()
        for (event in batch) batchArray.put(event)

        // Snapshot SQLite IDs BEFORE upload to avoid TOCTOU race
        val dbBatch = eventDatabase.loadBatch(batch.size)
        val dbIds = dbBatch.map { it.first }

        val body = JSONObject().apply { put("batch", batchArray) }.toString()

        // SPEC-070-A A.16 retry policy:
        // initial + up to MAX_RETRIES retries; backoff [1s, 2s, 4s] with ±25% jitter.
        var attempt = 0
        while (attempt <= MAX_RETRIES) {
            val result = apiClient.postEventsBatch("/api/v1/ingest/events", body)
            when (result) {
                is EventUploadResult.Success -> {
                    queue.removeAll(batch.toSet())
                    eventDatabase.removeByIds(dbIds)
                    consecutiveFailures = 0
                    Log.debug("Flushed ${batch.size} events (deflate compressed)")
                    return
                }
                is EventUploadResult.ClientError -> {
                    // 4xx (except 429): payload/auth is broken — don't retry, drop the batch
                    // so the queue doesn't loop forever.
                    Log.error("Dropping batch of ${batch.size} events after HTTP ${result.statusCode} (4xx — retry won't help)")
                    queue.removeAll(batch.toSet())
                    eventDatabase.removeByIds(dbIds)
                    // 4xx is a permanent failure, not a transient one — count toward pause
                    bumpFailureCounter()
                    return
                }
                is EventUploadResult.TransientFailure -> {
                    attempt++
                    if (attempt > MAX_RETRIES) {
                        Log.warning("Flush exhausted ${MAX_RETRIES} retries; will try again on next flush cycle.")
                        bumpFailureCounter()
                        return
                    }
                    val baseMs = RETRY_DELAYS_MS[(attempt - 1).coerceAtMost(RETRY_DELAYS_MS.size - 1)]
                    val jitterRange = (baseMs * JITTER_PCT).toLong()
                    val jittered = baseMs + Random.nextLong(-jitterRange, jitterRange + 1)
                    Log.debug("Flush failed (transient HTTP=${result.statusCode}), retrying in ${jittered}ms (attempt $attempt/$MAX_RETRIES)")
                    delay(jittered.coerceAtLeast(0L))
                }
            }
        }
    }

    private fun bumpFailureCounter() {
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            paused = true
            Log.warning("Too many consecutive flush failures ($consecutiveFailures). Event uploads paused until next foreground/manual flush.")
        }
    }

    private fun trimInMemoryCap() {
        while (queue.size > MAX_IN_MEMORY_EVENTS && queue.isNotEmpty()) {
            queue.removeAt(0)
        }
    }

    private fun startAutoFlush() {
        flushJob = scope.launch {
            while (isActive) {
                delay(flushInterval * 1000)
                performFlushWithRetry()
            }
        }
    }

    private fun registerLifecycleObserver() {
        if (context == null) return
        // ProcessLifecycleOwner must be observed on the main thread.
        // We post to the main thread so the observer is registered safely
        // regardless of which thread constructed EventQueue.
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val obs = object : DefaultLifecycleObserver {
                        override fun onStop(owner: LifecycleOwner) {
                            // SPEC-070-A A.18: app entered background — flush + schedule WorkManager
                            onAppBackground()
                        }

                        override fun onStart(owner: LifecycleOwner) {
                            // Foreground — re-arm flush (resets pause counter).
                            onAppForeground()
                        }
                    }
                    ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
                    lifecycleObserver = obs
                } catch (e: Throwable) {
                    Log.warning("Could not register ProcessLifecycleOwner observer: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.warning("Lifecycle observer scheduling failed: ${e.message}")
        }
    }

    fun shutdown() {
        flushJob?.cancel()
        // SPEC-070-A finalization (Lens C P0) — cancel the scope itself so
        // any other launched coroutines (retry backoff, app-foreground flush)
        // are torn down too. Without this, SDK shutdown leaks IO threads.
        try { scope.cancel() } catch (_: Throwable) {}
        // Detach lifecycle observer (must run on main thread).
        val obs = lifecycleObserver
        if (obs != null) {
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        ProcessLifecycleOwner.get().lifecycle.removeObserver(obs)
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
            lifecycleObserver = null
        }
        // Events are already persisted to SQLite on enqueue
    }

    companion object {
        /** SPEC-070-A A.16: Max retries per batch. */
        const val MAX_RETRIES = 3
        /** SPEC-070-A A.16: Base backoff schedule before jitter. */
        val RETRY_DELAYS_MS: LongArray = longArrayOf(1000L, 2000L, 4000L)
        /** SPEC-070-A A.16: ±25 % jitter, matches the prompt requirement. */
        const val JITTER_PCT = 0.25
        /** SPEC-070-A A.16: Pause uploads after this many back-to-back batch failures. */
        const val MAX_CONSECUTIVE_FAILURES = 5
        /** SPEC-070-A A.17: Cap in-memory copy at 1000 events (matches iOS EventQueue.swift `maxInMemoryEvents`). */
        const val MAX_IN_MEMORY_EVENTS = 1000
    }
}

/**
 * SPEC-070-A A.18: Indirection for scheduling background uploads from EventQueue
 * without forcing a hard dependency on `background.EventUploadWorker`. Wired by
 * AppDNA.configure().
 */
internal interface BackgroundUploadScheduler {
    fun scheduleIfNeeded()
}
