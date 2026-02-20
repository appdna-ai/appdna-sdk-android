package ai.appdna.sdk

import ai.appdna.sdk.storage.LocalStorage
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
    }

    fun identify(userId: String, traits: Map<String, Any>?) {
        _userId = userId
        _traits = traits
        storage.setString("user_id", userId)
        Log.info("Identified user: $userId")
    }

    fun reset() {
        _userId = null
        _traits = null
        storage.remove("user_id")
        sessionId = UUID.randomUUID().toString()
        Log.info("Identity reset")
    }

    fun newSession() {
        sessionId = UUID.randomUUID().toString()
    }
}
