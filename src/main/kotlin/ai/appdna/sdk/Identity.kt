package ai.appdna.sdk

import ai.appdna.sdk.storage.LocalStorage
import org.json.JSONObject
import java.util.UUID

/**
 * Device identity: anonymous + optional identified user.
 */
data class DeviceIdentity(
    val anonId: String,
    val userId: String? = null,
    val traits: Map<String, Any>? = null
)

/**
 * Manages anonymous and identified user identity.
 * Persists identity to SharedPreferences (via LocalStorage).
 */
internal class IdentityManager(private val storage: LocalStorage) {
    private var _anonId: String
    private var _userId: String? = null
    private var _traits: Map<String, Any>? = null

    val currentIdentity: DeviceIdentity
        get() = DeviceIdentity(anonId = _anonId, userId = _userId, traits = _traits)

    var sessionId: String = UUID.randomUUID().toString()
        private set

    init {
        // Load or generate anonymous ID
        _anonId = storage.getString("anon_id") ?: run {
            val id = UUID.randomUUID().toString()
            storage.setString("anon_id", id)
            id
        }
        _userId = storage.getString("user_id")
        // SPEC-088: Load persisted traits so they survive app restart
        _traits = loadTraits()
    }

    fun identify(userId: String, traits: Map<String, Any>?) {
        _userId = userId
        _traits = traits
        storage.setString("user_id", userId)
        // SPEC-088: Persist traits to SharedPreferences
        persistTraits(traits)
        Log.info("Identified user: $userId")
    }

    fun reset() {
        _userId = null
        _traits = null
        storage.remove("user_id")
        storage.remove("user_traits")
        sessionId = UUID.randomUUID().toString()
        Log.info("Identity reset")
    }

    fun newSession() {
        sessionId = UUID.randomUUID().toString()
    }

    // SPEC-088: Trait persistence helpers

    private fun persistTraits(traits: Map<String, Any>?) {
        if (traits == null) {
            storage.remove("user_traits")
            return
        }
        try {
            val json = JSONObject()
            for ((key, value) in traits) {
                json.put(key, value)
            }
            storage.setString("user_traits", json.toString())
        } catch (_: Exception) {
            Log.warning("Failed to persist user traits")
        }
    }

    private fun loadTraits(): Map<String, Any>? {
        val json = storage.getString("user_traits") ?: return null
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, Any>()
            for (key in obj.keys()) {
                val value = obj.get(key)
                if (value != JSONObject.NULL) {
                    map[key] = value
                }
            }
            map
        } catch (_: Exception) {
            null
        }
    }
}
