package ai.appdna.sdk.network

import ai.appdna.sdk.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Guards the transient-vs-permanent upload policy and `Retry-After` parsing.
 *
 * Context: iOS treated the whole `400..<500` range as permanent, so a single HTTP
 * 429 — the expected response under load — halted every event upload until app
 * restart. Android already retried 429 but dropped the batch on 408, and neither
 * platform honored `Retry-After`.
 */
class RetryPolicyTest {

    private val client = ApiClient(apiKey = "adn_test_key", environment = Environment.PRODUCTION)

    // ── Retry-After: delta-seconds ────────────────────────────────────────────

    @Test
    fun `parses delta-seconds`() {
        assertEquals(30L, client.parseRetryAfter("30"))
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertEquals(30L, client.parseRetryAfter("  30  "))
    }

    @Test
    fun `caps an excessive delta so a bad header cannot park the queue`() {
        assertEquals(ApiClient.MAX_RETRY_AFTER_SECONDS, client.parseRetryAfter("99999"))
    }

    @Test
    fun `rejects zero, negative, and unparseable values`() {
        assertNull(client.parseRetryAfter("0"))
        assertNull(client.parseRetryAfter("-5"))
        assertNull(client.parseRetryAfter("soon"))
        assertNull(client.parseRetryAfter(""))
        assertNull(client.parseRetryAfter(null))
    }

    // ── Retry-After: HTTP-date (RFC 9110 permits both forms) ──────────────────

    @Test
    fun `parses a future HTTP-date into a positive delta`() {
        val future = Date(System.currentTimeMillis() + 45_000)
        val parsed = client.parseRetryAfter(httpDate(future))
        assertTrue("expected a positive delta, got $parsed", parsed != null && parsed in 1..60)
    }

    @Test
    fun `rejects a past HTTP-date`() {
        val past = Date(System.currentTimeMillis() - 60_000)
        assertNull(client.parseRetryAfter(httpDate(past)))
    }

    @Test
    fun `caps a far-future HTTP-date`() {
        val farFuture = Date(System.currentTimeMillis() + 10L * 60 * 60 * 1000)
        assertEquals(ApiClient.MAX_RETRY_AFTER_SECONDS, client.parseRetryAfter(httpDate(farFuture)))
    }

    // ── TransientFailure carries the hint ─────────────────────────────────────

    @Test
    fun `TransientFailure defaults its retry hint to null`() {
        assertNull(EventUploadResult.TransientFailure(503).retryAfterSeconds)
    }

    @Test
    fun `TransientFailure can carry a retry hint`() {
        assertEquals(12L, EventUploadResult.TransientFailure(429, 12L).retryAfterSeconds)
    }

    private fun httpDate(d: Date): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(d)
}
