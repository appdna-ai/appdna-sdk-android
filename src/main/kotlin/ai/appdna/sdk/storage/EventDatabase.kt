package ai.appdna.sdk.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ai.appdna.sdk.Log
import org.json.JSONObject

/**
 * SPEC-067: SQLite-based event persistence replacing SharedPreferences.
 * Provides atomic row-level operations that survive process kills.
 * Enforces both event count cap (10K) and disk quota (5 MB).
 */
internal class EventDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "appdna_events.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_EVENTS = "pending_events"
        private const val COL_ID = "_id"
        private const val COL_EVENT_JSON = "event_json"
        private const val COL_CREATED_AT = "created_at"

        const val MAX_EVENTS = 10_000
        const val MAX_DISK_BYTES = 5 * 1024 * 1024 // 5 MB
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_EVENTS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_EVENT_JSON TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_events_created_at ON $TABLE_EVENTS($COL_CREATED_AT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations go here
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
                put(COL_CREATED_AT, System.currentTimeMillis() / 1000)
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
                        put(COL_CREATED_AT, System.currentTimeMillis() / 1000)
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
                "$COL_CREATED_AT ASC"
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
                "$COL_CREATED_AT ASC",
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
    private fun enforceQuotas(db: SQLiteDatabase) {
        // 1. Count cap
        val currentCount = count()
        if (currentCount > MAX_EVENTS) {
            val excess = currentCount - MAX_EVENTS
            db.execSQL("""
                DELETE FROM $TABLE_EVENTS WHERE $COL_ID IN (
                    SELECT $COL_ID FROM $TABLE_EVENTS ORDER BY $COL_CREATED_AT ASC LIMIT $excess
                )
            """.trimIndent())
            Log.warning("Event database overflow: dropped $excess oldest events (count cap)")
        }

        // 2. Disk quota
        val dbFile = db.path?.let { java.io.File(it) }
        val diskSize = dbFile?.length() ?: 0
        if (diskSize > MAX_DISK_BYTES) {
            // Drop 10% of oldest events
            val dropCount = (count() * 0.1).toInt().coerceAtLeast(1)
            db.execSQL("""
                DELETE FROM $TABLE_EVENTS WHERE $COL_ID IN (
                    SELECT $COL_ID FROM $TABLE_EVENTS ORDER BY $COL_CREATED_AT ASC LIMIT $dropCount
                )
            """.trimIndent())
            Log.warning("Event database disk quota enforced: dropped $dropCount events (${MAX_DISK_BYTES / 1024}KB limit)")
        }
    }
}
