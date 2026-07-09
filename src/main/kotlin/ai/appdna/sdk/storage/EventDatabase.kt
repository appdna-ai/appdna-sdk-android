package ai.appdna.sdk.storage

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ai.appdna.sdk.Log
import org.json.JSONObject

/**
 * Redirects the event database into `noBackupFilesDir`, the Android equivalent of
 * iOS's `isExcludedFromBackup`.
 *
 * The pending-event table holds whatever properties and traits the host chose to
 * send. The default `databases/` directory is copied into Google cloud backup and
 * device-to-device transfer; `noBackupFilesDir` is not.
 *
 * A library cannot set `android:fullBackupContent` / `android:dataExtractionRules`
 * itself — those are application-level attributes, and declaring them here would
 * fail the manifest merger for every host that already declares its own. Redirecting
 * the file is the only mechanism that works without host cooperation.
 *
 * Migrates any pre-existing database out of `databases/` exactly once, then leaves
 * the old location empty. Idempotent and self-healing: a partial move is retried on
 * the next launch, and a failed move simply leaves the SDK reading the old path.
 */
private class NoBackupContext(base: Context, dbName: String) : ContextWrapper(base) {

    init {
        migrateOutOfBackedUpStorage(base, dbName)
    }

    override fun getDatabasePath(name: String): File = File(noBackupFilesDir, name)

    private companion object {
        /** SQLite sidecars that must travel with the main file. */
        private val SUFFIXES = listOf("", "-journal", "-wal", "-shm")

        fun migrateOutOfBackedUpStorage(base: Context, dbName: String) {
            try {
                val dest = File(base.noBackupFilesDir, dbName)
                if (dest.exists()) return // already migrated
                val src = base.getDatabasePath(dbName)
                if (!src.exists()) return // fresh install — nothing to move
                base.noBackupFilesDir.mkdirs()
                for (suffix in SUFFIXES) {
                    val from = File(src.parentFile, dbName + suffix)
                    if (!from.exists()) continue
                    val to = File(base.noBackupFilesDir, dbName + suffix)
                    if (!from.renameTo(to)) {
                        from.copyTo(to, overwrite = true)
                        from.delete()
                    }
                }
                Log.info("Migrated event database out of backed-up storage")
            } catch (e: Exception) {
                // Never block SDK startup on the migration. Worst case the DB stays
                // in databases/ and is backed up, exactly as before this change.
                Log.warning("Event database backup-exclusion migration failed: ${e.message}")
            }
        }
    }
}

/**
 * SPEC-067: SQLite-based event persistence replacing SharedPreferences.
 * Provides atomic row-level operations that survive process kills.
 * Enforces both event count cap (10K) and disk quota (5 MB).
 *
 * Stored in `noBackupFilesDir` — see [NoBackupContext].
 */
internal class EventDatabase(
    context: Context,
    // SPEC-428: injectable so the shared behavioral fixtures (events/ category) drive eviction at a
    // small cap with an isolated db. Production callers use the defaults.
    private val maxEvents: Int = MAX_EVENTS,
    dbName: String = DATABASE_NAME,
) : SQLiteOpenHelper(NoBackupContext(context, dbName), dbName, null, DATABASE_VERSION) {

    init {
        // SPEC-428 CL-1/D2: wire the dropped-events counter to this app context.
        ai.appdna.sdk.events.DroppedEventsCounter.init(context)
    }

    companion object {
        private const val DATABASE_NAME = "appdna_events.db"
        private const val DATABASE_VERSION = 2 // SPEC-428 STEP-7: v2 adds idx_events_created_at on upgrade
        private const val TABLE_EVENTS = "pending_events"
        private const val COL_ID = "_id"
        private const val COL_EVENT_JSON = "event_json"
        private const val COL_CREATED_AT = "created_at"

        const val MAX_EVENTS = 10_000
        const val MAX_DISK_BYTES = 5 * 1024 * 1024 // 5 MB
        // SPEC-428 CL-2/D5: client redelivery horizon (7d, tracking SPEC-426's server dedup window).
        const val REDELIVERY_HORIZON_MS = 7L * 24 * 60 * 60 * 1000
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_EVENTS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_EVENT_JSON TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_created_at ON $TABLE_EVENTS($COL_CREATED_AT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // SPEC-428 STEP-7: v1 installs never ran onCreate again, so they lack idx_events_created_at → a
        // full-scan on the ORDER BY created_at, _id ordering query. Create it idempotently on upgrade.
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_created_at ON $TABLE_EVENTS($COL_CREATED_AT)")
        }
    }

    /**
     * Insert an event JSON string into the database.
     * Enforces count and disk quotas after insert.
     */
    fun insertEvent(eventJson: String) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_EVENT_JSON, eventJson)
                put(COL_CREATED_AT, System.currentTimeMillis()) // SPEC-428 CL-6: milliseconds, not seconds
            }
            db.insert(TABLE_EVENTS, null, values)
            enforceQuotas(db)
        } catch (e: Exception) {
            Log.error("Failed to insert event: ${e.message}")
        }
    }

    /**
     * Insert multiple events in a single transaction.
     */
    fun insertEvents(events: List<String>) {
        if (events.isEmpty()) return
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (eventJson in events) {
                    val values = ContentValues().apply {
                        put(COL_EVENT_JSON, eventJson)
                        put(COL_CREATED_AT, System.currentTimeMillis()) // SPEC-428 CL-6: milliseconds, not seconds
                    }
                    db.insert(TABLE_EVENTS, null, values)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            enforceQuotas(db)
        } catch (e: Exception) {
            Log.error("Failed to insert events batch: ${e.message}")
        }
    }

    /**
     * Load all pending events ordered by creation time (oldest first).
     */
    fun loadAll(): List<String> {
        val events = mutableListOf<String>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_EVENTS, arrayOf(COL_EVENT_JSON),
                null, null, null, null,
                "$COL_CREATED_AT ASC, $COL_ID ASC"
            )
            cursor.use {
                while (it.moveToNext()) {
                    events.add(it.getString(0))
                }
            }
        } catch (e: Exception) {
            Log.error("Failed to load events: ${e.message}")
        }
        return events
    }

    /**
     * Load up to [limit] oldest pending events.
     */
    fun loadBatch(limit: Int): List<Pair<Long, String>> {
        val events = mutableListOf<Pair<Long, String>>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_EVENTS, arrayOf(COL_ID, COL_EVENT_JSON),
                null, null, null, null,
                "$COL_CREATED_AT ASC, $COL_ID ASC",
                limit.toString()
            )
            cursor.use {
                while (it.moveToNext()) {
                    events.add(it.getLong(0) to it.getString(1))
                }
            }
        } catch (e: Exception) {
            Log.error("Failed to load event batch: ${e.message}")
        }
        return events
    }

    /**
     * Remove events by their database IDs after successful upload.
     */
    fun removeByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        try {
            val db = writableDatabase
            val placeholders = ids.joinToString(",") { "?" }
            db.delete(TABLE_EVENTS, "$COL_ID IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
        } catch (e: Exception) {
            Log.error("Failed to remove events: ${e.message}")
        }
    }

    /**
     * SPEC-428 CL-4/D3 — CONTENT-ADDRESSED removal: delete exactly the rows whose event's `event_id`
     * was in the UPLOADED batch, never a re-derived "globally-oldest N". After a CL-1 in-memory
     * eviction the in-mem batch diverges from the DB's oldest N, so deleting the oldest N would drop
     * never-uploaded rows (LOSS) and leave the uploaded rows behind (DUP on the next flush).
     */
    fun removeByEventIds(eventIds: Set<String>) {
        if (eventIds.isEmpty()) return
        try {
            val db = writableDatabase
            val toDelete = mutableListOf<Long>()
            val cursor = db.query(TABLE_EVENTS, arrayOf(COL_ID, COL_EVENT_JSON), null, null, null, null, null)
            cursor.use {
                while (it.moveToNext()) {
                    val eid = runCatching { org.json.JSONObject(it.getString(1)).getString("event_id") }.getOrNull()
                    if (eid != null && eid in eventIds) toDelete.add(it.getLong(0))
                }
            }
            removeByIds(toDelete)
        } catch (e: Exception) {
            Log.error("Failed to remove events by event_id: ${e.message}")
        }
    }

    /**
     * SPEC-428 CL-2/D5: drop events past the redelivery horizon so NO consumer — the in-process flush OR
     * the WorkManager uploader that fires hours/days later — re-sends an event past the server dedup window
     * (double-count). This lives at the STORE so both paths are covered. Counted (CL-1). Returns # dropped.
     */
    fun pruneStale(horizonMs: Long = REDELIVERY_HORIZON_MS): Int {
        val now = System.currentTimeMillis()
        var pruned = 0
        var lost = 0
        try {
            val db = writableDatabase
            // Wrap the scan+delete in ONE transaction so concurrent prunes SERIALIZE: the in-process flush
            // and the background Worker hold SEPARATE EventDatabase handles on the same file, so without this
            // both could count the same 7-day-stale rows. beginTransaction takes the write lock — the second
            // caller blocks until the first commits, then its scan sees the rows gone and counts 0. This is
            // what makes pruneStale the true SINGLE count source on Android (as it already is on iOS).
            db.beginTransaction()
            try {
                val delIds = mutableListOf<Long>()
                db.query(TABLE_EVENTS, arrayOf(COL_ID, COL_EVENT_JSON), null, null, null, null, null).use { c ->
                    while (c.moveToNext()) {
                        runCatching {
                            val obj = org.json.JSONObject(c.getString(1))
                            if (now - obj.optLong("ts_ms", now) > horizonMs) {
                                delIds.add(c.getLong(0))
                                // SPEC-428 STEP-4: meta-aware — a stale `_sdk_events_dropped` carries N drops.
                                lost += if (obj.optString("event_name") == "_sdk_events_dropped")
                                    obj.optJSONObject("properties")?.optInt("count", 0) ?: 0
                                else 1
                            }
                        }
                    }
                }
                if (delIds.isNotEmpty()) { removeByIds(delIds); pruned = delIds.size }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (pruned > 0) {
                ai.appdna.sdk.events.DroppedEventsCounter.increment(lost)
                Log.warning("Pruned $pruned events past the redelivery horizon (loss metric +$lost)")
            }
        } catch (e: Exception) {
            Log.error("pruneStale failed: ${e.message}")
        }
        return pruned
    }

    /**
     * SPEC-424 STEP-1a (CL-7): purge ALL persisted events WITHOUT uploading — analytics consent was
     * revoked, so queued-but-unsent events must never be transmitted.
     */
    fun clearAll() {
        try {
            writableDatabase.delete(TABLE_EVENTS, null, null)
        } catch (e: Exception) {
            Log.error("Failed to clear events: ${e.message}")
        }
    }

    /**
     * Get the count of pending events.
     */
    fun count(): Int {
        return try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_EVENTS", null)
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Migrate existing SharedPreferences event data to SQLite.
     * Called once on first launch after upgrade.
     */
    fun migrateFromSharedPreferences(storage: LocalStorage) {
        val existing = storage.getEventQueue()
        if (existing.isEmpty()) return

        Log.info("Migrating ${existing.size} events from SharedPreferences to SQLite")
        insertEvents(existing)

        // Only clear SP after successful SQLite write
        storage.setEventQueue(emptyList())
        Log.info("SharedPreferences event migration complete")
    }

    // MARK: - Private

    /**
     * Enforce event count cap and disk quota.
     */
    // SPEC-428 STEP-4: sum the TRUE loss over the oldest `limit` rows (the eviction set) — count 1 per
    // normal event, but the carried N for a `_sdk_events_dropped` META event, so evicting the meta before
    // delivery re-adds (and re-emits) the N drops it represented instead of under-counting them to 1.
    private fun evictionLoss(db: SQLiteDatabase, limit: Int): Int {
        var loss = 0
        db.query(TABLE_EVENTS, arrayOf(COL_EVENT_JSON), null, null, null, null,
            "$COL_CREATED_AT ASC, $COL_ID ASC", limit.toString()).use { c ->
            while (c.moveToNext()) {
                loss += try {
                    val obj = org.json.JSONObject(c.getString(0))
                    if (obj.optString("event_name") == "_sdk_events_dropped")
                        obj.optJSONObject("properties")?.optInt("count", 0) ?: 0
                    else 1
                } catch (e: Exception) { 1 }
            }
        }
        return loss
    }

    private fun enforceQuotas(db: SQLiteDatabase) {
        // 1. Count cap
        val currentCount = count()
        if (currentCount > maxEvents) {
            val excess = currentCount - maxEvents
            val loss = evictionLoss(db, excess) // recover meta-carried drops BEFORE the DELETE
            db.execSQL("""
                DELETE FROM $TABLE_EVENTS WHERE $COL_ID IN (
                    SELECT $COL_ID FROM $TABLE_EVENTS ORDER BY $COL_CREATED_AT ASC, $COL_ID ASC LIMIT $excess
                )
            """.trimIndent())
            ai.appdna.sdk.events.DroppedEventsCounter.increment(loss) // SPEC-428 CL-1/D2/STEP-4
            Log.warning("Event database overflow: dropped $excess oldest events (count cap; loss metric +$loss)")
        }

        // 2. Disk quota
        val dbFile = db.path?.let { java.io.File(it) }
        val diskSize = dbFile?.length() ?: 0
        if (diskSize > MAX_DISK_BYTES) {
            // Drop 10% of oldest events
            val dropCount = (count() * 0.1).toInt().coerceAtLeast(1)
            val loss = evictionLoss(db, dropCount)
            db.execSQL("""
                DELETE FROM $TABLE_EVENTS WHERE $COL_ID IN (
                    SELECT $COL_ID FROM $TABLE_EVENTS ORDER BY $COL_CREATED_AT ASC, $COL_ID ASC LIMIT $dropCount
                )
            """.trimIndent())
            ai.appdna.sdk.events.DroppedEventsCounter.increment(loss) // SPEC-428 CL-1/D2/STEP-4
            Log.warning("Event database disk quota enforced: dropped $dropCount events (${MAX_DISK_BYTES / 1024}KB limit; loss +$loss)")
        }
    }
}
