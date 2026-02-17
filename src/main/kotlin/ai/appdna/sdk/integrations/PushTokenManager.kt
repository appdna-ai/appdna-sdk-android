package ai.appdna.sdk.integrations

import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.storage.LocalStorage
import java.security.MessageDigest

/**
 * Manages push token capture, storage, and forwarding via events.
 */
internal class PushTokenManager(
    private val storage: LocalStorage,
    private val eventTracker: EventTracker
) {
    companion object {
        private const val KEY_PUSH_TOKEN = "push_token"
    }

    /**
     * Store the push token and send a registration event if it has changed.
     */
    fun setPushToken(token: String) {
        val previousToken = storage.getString(KEY_PUSH_TOKEN)
        storage.setString(KEY_PUSH_TOKEN, token)

        if (token != previousToken) {
            val hashedToken = sha256(token)
            eventTracker.track("push_token_registered", mapOf(
                "token_hash" to hashedToken,
                "platform" to "android"
            ))
            Log.info("Push token registered (hash: ${hashedToken.take(12)}...)")
        }
    }

    /**
     * Track push permission status.
     */
    fun setPushPermission(granted: Boolean) {
        if (granted) {
            eventTracker.track("push_permission_granted")
        } else {
            eventTracker.track("push_permission_denied")
        }
        Log.info("Push permission: ${if (granted) "granted" else "denied"}")
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
