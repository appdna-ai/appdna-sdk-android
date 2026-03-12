package ai.appdna.sdk

import ai.appdna.sdk.background.EventUploadWorker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.network.ConnectivityMonitor
import ai.appdna.sdk.storage.EventDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * SPEC-067: Tests for SDK Scale Layer 1 optimizations.
 */
class ScaleLayer1Test {

    // MARK: - Gzip Compression

    @Test
    fun `gzip compress produces valid gzip data`() {
        val original = "Hello, this is a test string for gzip compression"
        val compressed = ApiClient.gzipCompress(original.toByteArray(Charsets.UTF_8))

        assertTrue("Compressed data should be non-empty", compressed.isNotEmpty())

        // Verify it's valid gzip by decompressing
        val decompressed = gzipDecompress(compressed)
        assertEquals(original, String(decompressed, Charsets.UTF_8))
    }

    @Test
    fun `gzip compress empty data`() {
        val compressed = ApiClient.gzipCompress(ByteArray(0))
        // Should produce valid (small) gzip output for empty input
        assertTrue("Even empty data should produce gzip output", compressed.isNotEmpty())
    }

    @Test
    fun `gzip achieves at least 5x compression on event batch`() {
        // Create a batch of 50 realistic events
        val events = JSONArray()
        for (i in 0 until 50) {
            events.put(JSONObject().apply {
                put("schema_version", 1)
                put("event_id", "550e8400-e29b-41d4-a716-${String.format("%012d", i)}")
                put("event_name", if (i % 3 == 0) "screen_view" else "button_tap")
                put("ts_ms", System.currentTimeMillis() + i * 1000)
                put("user", JSONObject().put("anon_id", "test-anon"))
                put("device", JSONObject().apply {
                    put("platform", "android")
                    put("os", "14.0")
                    put("app_version", "1.0.0")
                    put("sdk_version", "1.0.0")
                    put("locale", "en_US")
                    put("country", "US")
                })
                put("context", JSONObject().put("session_id", "sess-test"))
                put("properties", JSONObject().apply {
                    put("screen", "home")
                    put("action", "tap")
                    put("value", i * 10)
                })
                put("privacy", JSONObject().put("consent", JSONObject().put("analytics", true)))
            })
        }

        val payload = JSONObject().put("batch", events).toString()
        val originalBytes = payload.toByteArray(Charsets.UTF_8)
        val compressed = ApiClient.gzipCompress(originalBytes)

        val ratio = originalBytes.size.toDouble() / compressed.size.coerceAtLeast(1)
        assertTrue(
            "Compression ratio should be at least 5x, got ${String.format("%.1f", ratio)}x",
            ratio >= 5.0
        )
    }

    @Test
    fun `gzip roundtrip preserves data integrity`() {
        val events = JSONArray()
        for (i in 0 until 100) {
            events.put(JSONObject().apply {
                put("event_name", "event_$i")
                put("ts_ms", System.currentTimeMillis())
            })
        }
        val original = JSONObject().put("batch", events).toString()

        val compressed = ApiClient.gzipCompress(original.toByteArray(Charsets.UTF_8))
        val decompressed = String(gzipDecompress(compressed), Charsets.UTF_8)

        assertEquals(original, decompressed)
    }

    // MARK: - Config TTL

    @Test
    fun `default config TTL is one hour`() {
        val options = AppDNAOptions()
        assertEquals("Default config TTL should be 3600 seconds", 3600L, options.configTTL)
    }

    @Test
    fun `custom config TTL preserved`() {
        val options = AppDNAOptions(configTTL = 600L)
        assertEquals(600L, options.configTTL)
    }

    // MARK: - Batch Size

    @Test
    fun `default batch size is 20`() {
        val options = AppDNAOptions()
        assertEquals("Default batch size should remain 20", 20, options.batchSize)
    }

    // MARK: - Disk Quota (EventDatabase constants)

    @Test
    fun `event database max events is 10K`() {
        assertEquals("Max events should be 10,000", 10_000, EventDatabase.MAX_EVENTS)
    }

    @Test
    fun `event database disk quota is 5 MB`() {
        assertEquals("Disk quota should be 5 MB", 5 * 1024 * 1024, EventDatabase.MAX_DISK_BYTES)
    }

    // MARK: - ConnectivityMonitor (enum values)

    @Test
    fun `connectivity monitor has correct connection types`() {
        val types = ConnectivityMonitor.ConnectionType.values()
        assertEquals("Should have 3 connection types", 3, types.size)
        assertTrue(types.contains(ConnectivityMonitor.ConnectionType.WIFI))
        assertTrue(types.contains(ConnectivityMonitor.ConnectionType.CELLULAR))
        assertTrue(types.contains(ConnectivityMonitor.ConnectionType.NONE))
    }

    // MARK: - Background Upload (EventUploadWorker constants)

    @Test
    fun `event upload worker has required input keys`() {
        assertEquals("api_key", EventUploadWorker.KEY_API_KEY)
        assertEquals("base_url", EventUploadWorker.KEY_BASE_URL)
    }

    // MARK: - Helpers

    private fun gzipDecompress(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }
}
