package ai.appdna.sdk.network

import ai.appdna.sdk.Environment
import ai.appdna.sdk.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * HTTP client for SDK API communication with retry and backoff.
 * SPEC-067: Supports gzip compression for request bodies and Accept-Encoding for responses.
 */
internal class ApiClient(
    private val apiKey: String,
    private val environment: Environment
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // SPEC-067: Add Accept-Encoding: gzip for response decompression (OkHttp handles this automatically)
        .addInterceptor(AcceptEncodingInterceptor())
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
     * SPEC-067: POST a JSON body with gzip compression for event ingestion.
     * Returns the response body string, or null on failure.
     */
    suspend fun postCompressed(path: String, body: String): String? {
        return withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (attempt in 0..retryDelays.size) {
                try {
                    val compressed = gzipCompress(body.toByteArray(Charsets.UTF_8))
                    val originalSize = body.toByteArray(Charsets.UTF_8).size
                    Log.debug("Compressed events: $originalSize → ${compressed.size} bytes (${String.format("%.1f", originalSize.toDouble() / compressed.size.coerceAtLeast(1))}x)")

                    val request = Request.Builder()
                        .url("${environment.baseUrl}$path")
                        .header("Authorization", "Bearer $apiKey")
                        .header("Content-Type", "application/json")
                        .header("Content-Encoding", "gzip")
                        .post(compressed.toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        return@withContext response.body?.string()
                    }
                    Log.warning("API compressed request failed (${response.code}): $path")
                } catch (e: IOException) {
                    lastError = e
                    Log.warning("API compressed request error: ${e.message}")
                }

                if (attempt < retryDelays.size) {
                    delay(retryDelays[attempt])
                }
            }
            Log.error("API compressed request exhausted retries: $path — ${lastError?.message}")
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

    // MARK: SPEC-067 — Gzip Compression

    companion object {
        /**
         * Compress data using gzip.
         */
        fun gzipCompress(data: ByteArray): ByteArray {
            val baos = ByteArrayOutputStream()
            GZIPOutputStream(baos).use { gzip ->
                gzip.write(data)
            }
            return baos.toByteArray()
        }
    }

    /**
     * SPEC-067: OkHttp interceptor that adds Accept-Encoding: gzip to all requests.
     * OkHttp automatically decompresses gzip responses when this header is present.
     */
    private class AcceptEncodingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .header("Accept-Encoding", "gzip")
                .build()
            return chain.proceed(request)
        }
    }
}
