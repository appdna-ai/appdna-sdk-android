package ai.appdna.sdk.background

import android.content.Context
import ai.appdna.sdk.Log
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.EventDatabase
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

        val eventDatabase = EventDatabase(applicationContext)

        val pendingCount = eventDatabase.count()
        if (pendingCount == 0) {
            Log.debug("No pending events for background upload")
            return@withContext Result.success()
        }

        // Load a batch from SQLite
        val batch = eventDatabase.loadBatch(100)
        if (batch.isEmpty()) {
            return@withContext Result.success()
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

        // Compress and send
        val compressed = ApiClient.gzipCompress(body.toByteArray(Charsets.UTF_8))
        Log.debug("Background upload: ${batch.size} events, ${body.length} → ${compressed.size} bytes")

        // Use a simple OkHttp request since we don't have ApiClient DI here
        // The worker reads apiKey and environment from shared preferences
        val apiKey = inputData.getString(KEY_API_KEY) ?: run {
            Log.error("Background upload: missing API key")
            return@withContext Result.failure()
        }
        val baseUrl = inputData.getString(KEY_BASE_URL) ?: "https://api.appdna.ai"

        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url("$baseUrl/api/v1/ingest/events")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .header("Accept-Encoding", "gzip")
                .post(compressed.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                eventDatabase.removeByIds(batch.map { it.first })
                Log.info("Background upload successful: ${batch.size} events")

                // Check if more events remain
                val remaining = eventDatabase.count()
                if (remaining > 0) {
                    Log.debug("$remaining events still pending after background upload")
                }

                return@withContext Result.success()
            } else {
                Log.warning("Background upload failed: ${response.code}")
                return@withContext Result.retry()
            }
        } catch (e: Exception) {
            Log.error("Background upload error: ${e.message}")
            return@withContext Result.retry()
        }
    }

    private fun ByteArray.toRequestBody(mediaType: okhttp3.MediaType): okhttp3.RequestBody {
        return okhttp3.RequestBody.create(mediaType, this)
    }

    private fun String.toMediaType(): okhttp3.MediaType {
        return okhttp3.MediaType.parse(this)!!
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
