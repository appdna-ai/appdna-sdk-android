package ai.appdna.sdk

import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.EventDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * SPEC-070-B AC-35 — resilience behavioral fixtures (packages/sdk-shared-fixtures/resilience/).
 *
 * These are the upload/queue SURVIVAL contracts, and every one of them lives below the bridge:
 * a wrapper cannot observe an HTTP status, a `Retry-After` header, or a prune decision. So — exactly
 * like `events` — the category is native-only and iOS + Android assert the SAME fixture table.
 *
 * Each `contract` maps to a PURE seam, which is why this can be a table test rather than an HTTP mock:
 *   - transient_status → [ApiClient.TRANSIENT_STATUS_CODES]  (iOS: APIClient.transientStatusCodes)
 *   - retry_after      → [ApiClient.parseRetryAfter]         (iOS: APIClient.parseRetryAfter)
 *   - stale_horizon    → [EventDatabase.isStale]             (iOS: EventStore.isStale)
 *
 * A fixture whose `contract` this runner does not know FAILS. It is never skipped — a silently
 * skipped resilience fixture is the coverage theater AC-35 exists to remove.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResilienceFixtureTest {

    /** Constructed only to reach `parseRetryAfter`; it performs no I/O. */
    private val api = ApiClient(apiKey = "adn_test_placeholder", environment = Environment.SANDBOX)

    @Test
    fun resilienceFixtures() {
        val fixtures = loadResilienceFixtures()
        assertTrue(
            "No resilience fixtures found — the category must not silently vanish",
            fixtures.isNotEmpty(),
        )

        val seenContracts = mutableSetOf<String>()
        for ((id, json) in fixtures) {
            val block = json.getJSONObject("resilience")
            val contract = block.getString("contract")
            seenContracts.add(contract)
            val cases = block.getJSONArray("cases")

            when (contract) {
                "transient_status" -> {
                    for (i in 0 until cases.length()) {
                        val c = cases.getJSONObject(i)
                        val status = c.getInt("status")
                        val expected = c.getBoolean("transient")
                        assertEquals(
                            "[$id] HTTP $status transient?",
                            expected,
                            status in ApiClient.TRANSIENT_STATUS_CODES,
                        )
                    }
                }

                "retry_after" -> {
                    for (i in 0 until cases.length()) {
                        val c = cases.getJSONObject(i)
                        // `isNull` distinguishes a JSON null (header absent) from the string "null".
                        val header: String? = if (c.isNull("header")) null else c.getString("header")
                        val expected: Long? = if (c.isNull("seconds")) null else c.getLong("seconds")
                        assertEquals(
                            "[$id] Retry-After ${header?.let { "\"$it\"" } ?: "(absent)"}",
                            expected,
                            api.parseRetryAfter(header),
                        )
                    }
                }

                "stale_horizon" -> {
                    val horizon = block.getLong("horizon_ms")
                    // A fixed `now` keeps the table exact: age is what varies, not the wall clock.
                    val now = 1_800_000_000_000L
                    for (i in 0 until cases.length()) {
                        val c = cases.getJSONObject(i)
                        val ageMs = c.getLong("age_ms")
                        val expected = c.getBoolean("stale")
                        assertEquals(
                            "[$id] age ${ageMs}ms stale?",
                            expected,
                            EventDatabase.isStale(tsMs = now - ageMs, nowMs = now, horizonMs = horizon),
                        )
                    }
                }

                else -> fail("[$id] unknown resilience contract '$contract' — this runner must assert it, never skip it")
            }
        }

        // Every contract the schema defines must actually be exercised, or a fixture could be deleted
        // and this suite would still go green on the survivors.
        assertEquals(
            "every resilience contract must be covered by a fixture",
            setOf("transient_status", "retry_after", "stale_horizon"),
            seenContracts,
        )
    }

    private fun loadResilienceFixtures(): List<Pair<String, JSONObject>> {
        val dir = fixturesRoot()?.let { File(it, "resilience") } ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".fixture.json") }
            .orEmpty()
            .sortedBy { it.path }
            .mapNotNull { file ->
                val json = JSONObject(file.readText(Charsets.UTF_8))
                if (json.getString("category") != "resilience") return@mapNotNull null
                val platforms = json.optJSONArray("platforms")
                val applies = (0 until (platforms?.length() ?: 0))
                    .any { platforms!!.getString(it) == "android" }
                if (!applies) return@mapNotNull null
                json.getString("id") to json
            }
    }

    private fun fixturesRoot(): File? {
        System.getenv("APPDNA_SDK_FIXTURES_DIR")?.let { val f = File(it); if (f.isDirectory) return f }
        var here: File? = File(".").canonicalFile
        repeat(10) {
            val candidate = File(here, "packages/sdk-shared-fixtures")
            if (candidate.isDirectory) return candidate
            here = here?.parentFile
        }
        val codespace = File("/workspaces/appdna-ai/packages/sdk-shared-fixtures")
        if (codespace.isDirectory) return codespace
        return null
    }
}
