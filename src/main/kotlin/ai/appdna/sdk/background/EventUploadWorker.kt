package ai.appdna.sdk.background

import android.content.Context
import ai.appdna.sdk.Log
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.EventDatabase
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * SPEC-067: WorkManager-based background event upload.
 * Ensures queued events are delivered when the app is backgrounded or killed.
 */
internal class EventUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.info("Background event upload started")

        // SPEC-070-A H.24: own the EventDatabase handle for exactly the duration
        // of this run via try/finally so the SQLite file is always closed —
        // including on exception paths. Previously each WorkManager run leaked
        // a SQLiteOpenHelper.
        val eventDatabase = EventDatabase(applicationContext)
        try {
            return@withContext doUploadInner(eventDatabase)
        } finally {
            try { eventDatabase.close() } catch (_: Throwable) { /* close best-effort */ }
        }
    }

    private suspend fun doUploadInner(eventDatabase: EventDatabase): Result {
        // SPEC-428 CL-9/D4: single upload owner — if the in-process flush holds the claim, skip so
        // the same rows are never POSTed by both paths. Released in the finally below.
        if (!ai.appdna.sdk.events.EventUploadCoordinator.tryAcquire()) {
            Log.debug("Background upload skipped — in-process flush is active")
            return Result.success()
        }
        try {
        val pendingCount = eventDatabase.count()
        if (pendingCount == 0) {
            Log.debug("No pending events for background upload")
            return Result.success()
        }

        // Load a batch from SQLite
        val batch = eventDatabase.loadBatch(100)
        if (batch.isEmpty()) {
            return Result.success()
        }

        // Build batch JSON
        val batchArray = JSONArray()
        for ((_, eventJson) in batch) {
            try {
                batchArray.put(JSONObject(eventJson))
            } catch (_: Exception) {}
        }

        val body = JSONObject().apply {
            put("batch", batchArray)
        }.toString()

        // SPEC-070-A A.15: Compress with deflate (parity with iOS APIClient.deflateCompress)
        val compressed = ApiClient.deflateCompress(body.toByteArray(Charsets.UTF_8))
        Log.debug("Background upload: ${batch.size} events, ${body.length} → ${compressed.size} bytes")

        // SPEC-070-A A.1 + A.12b: API key required; baseUrl required (no host fallback).
        val apiKey = inputData.getString(KEY_API_KEY) ?: run {
            Log.error("Background upload: missing API key — refusing to upload")
            return Result.failure()
        }
        val baseUrl = inputData.getString(KEY_BASE_URL) ?: run {
            Log.error("Background upload: missing baseUrl input data — refusing to upload (set KEY_BASE_URL via scheduleIfNeeded)")
            return Result.failure()
        }

        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url("$baseUrl/api/v1/ingest/events")
                .header("x-api-key", apiKey) // SPEC-070-A A.1
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "deflate") // SPEC-070-A A.15
                .header("Accept-Encoding", "gzip, deflate")
                .post(compressed.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                if (response.isSuccessful) {
                    eventDatabase.removeByIds(batch.map { it.first })
                    Log.info("Background upload successful: ${batch.size} events")

                    val remaining = eventDatabase.count()
                    if (remaining > 0) {
                        Log.debug("$remaining events still pending after background upload")
                    }

                    return Result.success()
                } else if (code == 429 || code in 500..599) {
                    Log.warning("Background upload transient failure (HTTP $code) — will retry")
                    return Result.retry()
                } else if (code in 400..499) {
                    Log.error("Background upload rejected (HTTP $code) — dropping batch (retry won't help)")
                    eventDatabase.removeByIds(batch.map { it.first })
                    return Result.failure()
                } else {
                    Log.warning("Background upload unexpected status (HTTP $code) — will retry")
                    return Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.error("Background upload error: ${e.message}")
            return Result.retry()
        }
        } finally {
            ai.appdna.sdk.events.EventUploadCoordinator.release() // SPEC-428 CL-9
        }
    }

    companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_BASE_URL = "base_url"
        private const val UNIQUE_WORK_NAME = "ai.appdna.sdk.eventUpload"

        /**
         * Schedule a background upload if there are pending events.
         */
        fun scheduleIfNeeded(
            context: Context,
            apiKey: String,
            baseUrl: String,
            eventDatabase: EventDatabase
        ) {
            val pendingCount = eventDatabase.count()
            if (pendingCount == 0) return

            val inputData = Data.Builder()
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_BASE_URL, baseUrl)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val uploadWork = OneTimeWorkRequestBuilder<EventUploadWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    uploadWork
                )

            Log.info("Scheduled background upload for $pendingCount pending events")
        }
    }
}
