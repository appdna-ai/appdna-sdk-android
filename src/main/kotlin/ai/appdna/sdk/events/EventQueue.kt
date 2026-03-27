package ai.appdna.sdk.events

import ai.appdna.sdk.Log
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.network.ConnectivityMonitor
import ai.appdna.sdk.storage.EventDatabase
import ai.appdna.sdk.storage.LocalStorage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages in-memory + disk event queue with automatic flushing.
 * SPEC-067: Adaptive batch sizing, gzip compression, SQLite persistence.
 */
internal class EventQueue(
    private val apiClient: ApiClient,
    private val eventDatabase: EventDatabase,
    private val connectivityMonitor: ConnectivityMonitor?,
    private val batchSize: Int,
    private val flushInterval: Long // seconds
) {
    private val queue = CopyOnWriteArrayList<JSONObject>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null

    /** SPEC-067: Returns adaptive batch size based on network conditions. */
    private val effectiveBatchSize: Int
        get() = connectivityMonitor?.adaptiveBatchSize ?: batchSize

    init {
        // Load persisted events from SQLite
        val stored = eventDatabase.loadAll()
        for (json in stored) {
            try {
                queue.add(JSONObject(json))
            } catch (_: Exception) {}
        }
        if (stored.isNotEmpty()) {
            Log.info("Loaded ${stored.size} persisted events from SQLite")
        }

        // Start auto-flush timer
        startAutoFlush()
    }

    /**
     * Secondary constructor for backward compatibility during migration.
     */
    constructor(
        apiClient: ApiClient,
        storage: LocalStorage,
        batchSize: Int,
        flushInterval: Long
    ) : this(apiClient, EventDatabase(storage.context), null, batchSize, flushInterval) {
        // This constructor exists for backward compat; prefer the primary constructor
    }

    /**
     * Add an event to the queue.
     */
    fun enqueue(event: JSONObject) {
        queue.add(event)
        // SPEC-067: Persist to SQLite
        eventDatabase.insertEvent(event.toString())

        // SPEC-067: Check adaptive threshold
        val currentBatchSize = effectiveBatchSize
        if (currentBatchSize > 0 && queue.size >= currentBatchSize) {
            flush()
        }
    }

    /**
     * Force flush all queued events.
     */
    fun flush() {
        scope.launch { performFlush() }
    }

    private suspend fun performFlush() {
        if (queue.isEmpty()) return

        // SPEC-067: Skip flush if no network
        val currentBatchSize = effectiveBatchSize
        if (currentBatchSize == 0) {
            Log.debug("No network — skipping flush, ${queue.size} events queued")
            return
        }

        // Take a batch up to adaptive size
        val batch = ArrayList(queue.take(currentBatchSize.coerceAtMost(queue.size)))
        val batchArray = JSONArray()
        for (event in batch) {
            batchArray.put(event)
        }

        // Snapshot SQLite IDs BEFORE upload to avoid TOCTOU race
        val dbBatch = eventDatabase.loadBatch(batch.size)
        val dbIds = dbBatch.map { it.first }

        val body = JSONObject().apply {
            put("batch", batchArray)
        }.toString()

        // SPEC-067: Use gzip-compressed POST
        val result = apiClient.postCompressed("/api/v1/ingest/events", body)
        if (result != null) {
            queue.removeAll(batch.toSet())
            // Remove the exact IDs we snapshotted before upload
            eventDatabase.removeByIds(dbIds)
            Log.debug("Flushed ${batch.size} events (gzip compressed)")
        }
    }

    private fun startAutoFlush() {
        flushJob = scope.launch {
            while (isActive) {
                delay(flushInterval * 1000)
                performFlush()
            }
        }
    }

    fun shutdown() {
        flushJob?.cancel()
        // Events are already persisted to SQLite on enqueue
    }
}
