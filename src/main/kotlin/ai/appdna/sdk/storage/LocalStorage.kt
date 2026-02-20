package ai.appdna.sdk.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences wrapper for persistent SDK data.
 */
internal class LocalStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)

    fun getString(key: String): String? = prefs.getString(key, null)

    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getInt(key: String, default: Int = 0): Int = prefs.getInt(key, default)

    fun setInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        prefs.getBoolean(key, default)

    fun setBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getLong(key: String, default: Long = 0L): Long = prefs.getLong(key, default)

    fun setLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /**
     * Get all stored event JSON strings (for offline queue).
     */
    fun getEventQueue(): List<String> {
        val json = prefs.getString("event_queue", null) ?: return emptyList()
        return json.split("\n").filter { it.isNotBlank() }
    }

    fun setEventQueue(events: List<String>) {
        prefs.edit().putString("event_queue", events.joinToString("\n")).apply()
    }
}
