package ai.appdna.sdk

import ai.appdna.sdk.events.ClientSeqCounter
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventSchema
import ai.appdna.sdk.storage.EventDatabase
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * SPEC-428 — event-pipeline behavioral fixtures (packages/sdk-shared-fixtures/events/).
 *
 * The event pipeline (EventDatabase eviction, ClientSeqCounter monotonicity, DroppedEventsCounter,
 * event_id-stable redelivery) is NATIVE-owned per ADR-001, so iOS + Android assert these fixtures in
 * full; the Flutter/RN thin wrappers forward `track()` to native and defer these guarantees to the
 * native runners.
 *
 * Replays each fixture's `pipeline.steps` against the REAL `EventDatabase` + `EventSchema.buildEnvelope`
 * (assigns `client_seq`) + `DroppedEventsCounter`, with a mock server "sink" that dedups by `event_id`,
 * then asserts the observable output. Mirrors the iOS `EventPipelineFixtureTests`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventPipelineFixtureTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private data class Step(val op: String, val name: String?, val count: Int?, val ms: Int?)
    private data class Expect(
        val ingestedCount: Int?,
        val droppedMin: Int?,
        val noDup: Boolean,
        val monotonic: Boolean,
    )
    private data class Fixture(val id: String, val cap: Int?, val steps: List<Step>, val expect: Expect)

    @Test
    fun eventPipelineFixtures() {
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        val fixtures = loadEventFixtures()
        assertTrue("No Android event-pipeline fixtures found (packages/sdk-shared-fixtures/events)", fixtures.isNotEmpty())
        for (f in fixtures) runPipeline(f)
        println("[EventPipeline] asserted ${fixtures.size} fixtures")
    }

    private fun resetCounters() {
        ctx.getSharedPreferences("appdna_client_seq", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("appdna_dropped_events", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun makeEvent(name: String): String =
        EventSchema.buildEnvelope(
            eventName = name,
            properties = null,
            identity = DeviceIdentity("spec428-anon", null, null),
            sessionId = "spec428-session",
            appVersion = "1.0",
            analyticsConsent = true,
        ).toString()

    private fun runPipeline(f: Fixture) {
        resetCounters()
        val cap = f.cap ?: 10_000
        val dbName = "spec428_${f.id}.db"
        var db = EventDatabase(ctx, cap, dbName)
        db.clearAll()
        resetCounters()

        var online = true
        val ingestedIds = mutableSetOf<String>()
        val ingested = mutableListOf<JSONObject>()

        fun flush() {
            if (!online) return
            val pending = db.loadAll().map { JSONObject(it) }
            for (e in pending) {
                if (ingestedIds.add(e.getString("event_id"))) ingested.add(e) // server dedups by event_id
            }
            db.removeByEventIds(pending.map { it.getString("event_id") }.toSet())
        }

        for (s in f.steps) {
            when (s.op) {
                "track", "track_before_configure" -> {
                    val n = s.count ?: 1
                    val base = s.name ?: "evt"
                    for (i in 0 until n) db.insertEvent(makeEvent(if (n > 1) "${base}_$i" else base))
                }
                "flush" -> flush()
                "go_offline" -> online = false
                "go_online" -> online = true
                "restart" -> db = EventDatabase(ctx, cap, dbName) // persistence survives (SQLite + prefs)
                "redeliver" -> flush()
                "advance_time_ms" -> {}
                else -> fail("[${f.id}] unknown pipeline op: ${s.op}")
            }
        }

        f.expect.ingestedCount?.let { assertEquals("[${f.id}] ingested_count", it, ingested.size) }
        f.expect.droppedMin?.let {
            val dropped = DroppedEventsCounter.getAndReset()
            assertTrue("[${f.id}] dropped_events_min (got $dropped)", dropped >= it)
        }
        if (f.expect.noDup) {
            assertEquals("[${f.id}] no_duplicate_event_id", ingested.map { it.getString("event_id") }.toSet().size, ingested.size)
        }
        if (f.expect.monotonic) {
            val seqs = ingested.map { it.getJSONObject("context").getLong("client_seq") }
            assertEquals("[${f.id}] every ingested event carries a client_seq", ingested.size, seqs.size)
            val sorted = seqs.sorted()
            for (i in 1 until sorted.size) {
                assertTrue("[${f.id}] client_seq strictly increasing (no equal/inversion)", sorted[i] > sorted[i - 1])
            }
        }

        db.clearAll()
        resetCounters()
    }

    private fun loadEventFixtures(): List<Fixture> {
        val eventsDir = fixturesRoot()?.let { File(it, "events") }
            ?: error("Could not locate packages/sdk-shared-fixtures/events")
        val files = eventsDir.listFiles { file -> file.name.endsWith(".fixture.json") } ?: emptyArray()
        return files.sortedBy { it.name }.mapNotNull { file ->
            val j = JSONObject(file.readText())
            if (j.getString("category") != "events") return@mapNotNull null
            val plats = j.getJSONArray("platforms")
            val hasAndroid = (0 until plats.length()).any { plats.getString(it) == "android" }
            if (!hasAndroid) return@mapNotNull null
            val p = j.getJSONObject("pipeline")
            val cfg = p.optJSONObject("config")
            val stepsArr = p.getJSONArray("steps")
            val steps = (0 until stepsArr.length()).map {
                val s = stepsArr.getJSONObject(it)
                Step(
                    op = s.getString("op"),
                    name = if (s.has("name")) s.getString("name") else null,
                    count = if (s.has("count")) s.getInt("count") else null,
                    ms = if (s.has("ms")) s.getInt("ms") else null,
                )
            }
            val ex = p.getJSONObject("expect")
            Fixture(
                id = j.getString("id"),
                cap = if (cfg != null && cfg.has("max_events")) cfg.getInt("max_events") else null,
                steps = steps,
                expect = Expect(
                    ingestedCount = if (ex.has("ingested_count")) ex.getInt("ingested_count") else null,
                    droppedMin = if (ex.has("dropped_events_min")) ex.getInt("dropped_events_min") else null,
                    noDup = ex.optBoolean("no_duplicate_event_id", false),
                    monotonic = ex.optBoolean("monotonic_client_seq", false),
                ),
            )
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
