package ai.appdna.sdk.events

import ai.appdna.sdk.Log
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.LocalStorage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages in-memory + disk event queue with automatic flushing.
 */
internal class EventQueue(
    private val apiClient: ApiClient,
    private val storage: LocalStorage,
    private val batchSize: Int,
    private val flushInterval: Long // seconds
) {
    private val queue = CopyOnWriteArrayList<JSONObject>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null
    private val maxStoredEvents = 10_000

    init {
        // Load persisted events
        val stored = storage.getEventQueue()
        for (json in stored) {
            try {
                queue.add(JSONObject(json))
            } catch (_: Exception) {}
        }

        // Start auto-flush timer
        startAutoFlush()
    }

    /**
     * Add an event to the queue.
     */
    fun enqueue(event: JSONObject) {
        if (queue.size >= maxStoredEvents) {
            queue.removeAt(0)
        }
        queue.add(event)
        persistQueue()

        if (queue.size >= batchSize) {
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

        val batch = ArrayList(queue)
        val batchArray = JSONArray()
        for (event in batch) {
            batchArray.put(event)
        }

        val body = JSONObject().apply {
            put("events", batchArray)
        }.toString()

        val result = apiClient.post("/api/v1/ingest/events", body)
        if (result != null) {
            queue.removeAll(batch.toSet())
            persistQueue()
            Log.debug("Flushed ${batch.size} events")
        }
    }

    private fun persistQueue() {
        val events = queue.map { it.toString() }
        storage.setEventQueue(events)
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
        persistQueue()
    }
}
