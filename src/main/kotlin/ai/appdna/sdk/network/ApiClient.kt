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
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * HTTP client for SDK API communication with retry and backoff.
 * SPEC-067: Supports deflate compression for request bodies and Accept-Encoding for responses.
 * SPEC-070-A A.1: Uses x-api-key header to match iOS APIClient.
 * SPEC-070-A A.15: Switched compression from gzip to deflate to match iOS Network/APIClient.swift:143.
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
                        .header("x-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .post(body.toRequestBody(jsonMediaType))
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            return@withContext response.body?.string()
                        }
                        Log.warning("API request failed (${response.code}): $path")
                    }
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
                    val compressed = deflateCompress(body.toByteArray(Charsets.UTF_8))
                    val originalSize = body.toByteArray(Charsets.UTF_8).size
                    Log.debug("Compressed events: $originalSize → ${compressed.size} bytes (${String.format("%.1f", originalSize.toDouble() / compressed.size.coerceAtLeast(1))}x)")

                    val request = Request.Builder()
                        .url("${environment.baseUrl}$path")
                        .header("x-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .header("Content-Encoding", "deflate")
                        .post(compressed.toRequestBody("application/json".toMediaType()))
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            return@withContext response.body?.string()
                        }
                        Log.warning("API compressed request failed (${response.code}): $path")
                    }
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
                    .header("x-api-key", apiKey)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext response.body?.string()?.let { JSONObject(it) }
                    }
                    Log.warning("GET failed (${response.code}): $path")
                    null
                }
            } catch (e: IOException) {
                Log.error("GET error: ${e.message}")
                null
            }
        }
    }

    /**
     * SPEC-070-A A.16: Single-attempt event-batch upload with structured result.
     *
     * Unlike [postCompressed] (which performs its own internal retry loop), this method
     * makes ONE network attempt and returns an [EventUploadResult] so the caller
     * (EventQueue) can apply the SPEC-070-A retry policy:
     *   - 2xx → Success
     *   - 4xx (except 429) → ClientError (drop the batch — retrying won't help)
     *   - 5xx + 429 + network error → TransientFailure (retry with backoff)
     *
     * Mirrors the differentiation in iOS `APIClient.sendEvents` (Network/APIClient.swift:159).
     */
    suspend fun postEventsBatch(path: String, body: String): EventUploadResult {
        return withContext(Dispatchers.IO) {
            try {
                val compressed = deflateCompress(body.toByteArray(Charsets.UTF_8))
                val originalSize = body.toByteArray(Charsets.UTF_8).size
                Log.debug("Compressed events: $originalSize → ${compressed.size} bytes")

                val request = Request.Builder()
                    .url("${environment.baseUrl}$path")
                    .header("x-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .header("Content-Encoding", "deflate")
                    .post(compressed.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    when {
                        response.isSuccessful -> EventUploadResult.Success
                        code == 429 -> {
                            Log.warning("Event upload rate-limited (429): retrying.")
                            EventUploadResult.TransientFailure(code)
                        }
                        code in 400..499 -> {
                            Log.error("Event upload rejected (HTTP $code): dropping batch — retrying won't help.")
                            EventUploadResult.ClientError(code)
                        }
                        code in 500..599 -> {
                            Log.warning("Event upload server error (HTTP $code): retrying.")
                            EventUploadResult.TransientFailure(code)
                        }
                        else -> {
                            Log.warning("Event upload unexpected status (HTTP $code): retrying.")
                            EventUploadResult.TransientFailure(code)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.warning("Event upload network error: ${e.message}")
                EventUploadResult.TransientFailure(null)
            }
        }
    }

    // MARK: SPEC-067 — Deflate Compression (matches iOS COMPRESSION_ZLIB raw deflate, SPEC-070-A A.15)

    companion object {
        /**
         * Compress data using raw deflate (no zlib header/checksum) to match the iOS
         * `compression_encode_buffer(... COMPRESSION_ZLIB)` byte stream exactly.
         *
         * Java's `Deflater(level, nowrap=true)` produces RFC 1951 raw deflate output
         * (the same format iOS sends with Content-Encoding: deflate per SPEC-067).
         */
        fun deflateCompress(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, /* nowrap = */ true)
            val baos = ByteArrayOutputStream()
            DeflaterOutputStream(baos, deflater).use { it.write(data) }
            deflater.end()
            return baos.toByteArray()
        }
    }

    /**
     * SPEC-067: OkHttp interceptor that adds Accept-Encoding: gzip, deflate to all requests
     * (matching iOS APIClient.swift:195). OkHttp automatically decompresses gzip responses
     * when this header is present; deflate response bodies (rare on AppDNA backend) fall
     * through to caller decoding.
     */
    private class AcceptEncodingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .header("Accept-Encoding", "gzip, deflate")
                .build()
            return chain.proceed(request)
        }
    }
}

/**
 * SPEC-070-A A.16: Outcome of a single event-batch upload attempt.
 * Lets [EventQueue] apply the differentiated retry policy described in §3.1 P0.
 */
internal sealed class EventUploadResult {
    /** 2xx response — batch is delivered, drop from disk + memory. */
    object Success : EventUploadResult()
    /**
     * 4xx response (excluding 429) — payload or auth is malformed.
     * Retrying won't help; drop the batch immediately.
     */
    data class ClientError(val statusCode: Int) : EventUploadResult()
    /**
     * 5xx, 429, or network error — server may recover.
     * Retry with exponential backoff up to maxRetries.
     */
    data class TransientFailure(val statusCode: Int?) : EventUploadResult()
}
