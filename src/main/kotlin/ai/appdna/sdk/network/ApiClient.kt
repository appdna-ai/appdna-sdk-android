package ai.appdna.sdk.network

import ai.appdna.sdk.Environment
import ai.appdna.sdk.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for SDK API communication with retry and backoff.
 */
internal class ApiClient(
    private val apiKey: String,
    private val environment: Environment
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val retryDelays = longArrayOf(1000L, 2000L, 4000L)

    /**
     * POST a JSON body to the given path with retry.
     */
    suspend fun post(path: String, body: String): String? {
        return withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (attempt in 0..retryDelays.size) {
                try {
                    val request = Request.Builder()
                        .url("${environment.baseUrl}$path")
                        .header("Authorization", "Bearer $apiKey")
                        .header("Content-Type", "application/json")
                        .post(body.toRequestBody(jsonMediaType))
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        return@withContext response.body?.string()
                    }
                    Log.warning("API request failed (${response.code}): $path")
                } catch (e: IOException) {
                    lastError = e
                    Log.warning("API request error: ${e.message}")
                }

                if (attempt < retryDelays.size) {
                    delay(retryDelays[attempt])
                }
            }
            Log.error("API request exhausted retries: $path — ${lastError?.message}")
            null
        }
    }

    /**
     * GET a JSON response from the given path.
     */
    suspend fun get(path: String): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${environment.baseUrl}$path")
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.string()?.let { JSONObject(it) }
                } else {
                    Log.warning("GET failed (${response.code}): $path")
                    null
                }
            } catch (e: IOException) {
                Log.error("GET error: ${e.message}")
                null
            }
        }
    }
}
