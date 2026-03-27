package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Manages push token capture, storage, backend registration, and delivery tracking.
 */
internal class PushTokenManager(
    private val context: Context,
    private val storage: LocalStorage,
    private val eventTracker: EventTracker,
    private val apiClient: ApiClient? = null
) {
    companion object {
        private const val KEY_PUSH_TOKEN = "push_token"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Current push token from local storage. */
    var currentToken: String?
        get() = storage.getString(KEY_PUSH_TOKEN)
        private set(value) {
            if (value != null) storage.setString(KEY_PUSH_TOKEN, value)
        }

    /** Delegate for push notification lifecycle events. */
    var pushListener: ai.appdna.sdk.AppDNAPushDelegate? = null

    /**
     * Request push notification permission (stub — Android 13+ runtime permission
     * must be requested from the hosting Activity).
     */
    fun requestPermission() {
        Log.info("Push permission request: on Android, request POST_NOTIFICATIONS permission from your Activity")
    }

    /**
     * Store the push token, send a registration event, and register with backend.
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

        // Register token with backend (POST /api/v1/push/token)
        registerTokenWithBackend(token)
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

    /**
     * Track that a push notification was delivered.
     */
    fun trackDelivered(pushId: String) {
        eventTracker.track("push_delivered", mapOf("push_id" to pushId))
        scope.launch {
            try {
                val body = JSONObject().apply { put("push_id", pushId) }
                apiClient?.post("/api/v1/push/delivered", body.toString())
            } catch (e: Exception) {
                Log.warning("Failed to track push delivered: ${e.message}")
            }
        }
    }

    /**
     * Track that a push notification was tapped.
     */
    fun trackTapped(pushId: String, action: String? = null) {
        val props = mutableMapOf("push_id" to pushId)
        action?.let { props["action"] = it }
        eventTracker.track("push_tapped", props)
        scope.launch {
            try {
                val body = JSONObject().apply { put("push_id", pushId) }
                apiClient?.post("/api/v1/push/tapped", body.toString())
            } catch (e: Exception) {
                Log.warning("Failed to track push tapped: ${e.message}")
            }
        }
    }

    /**
     * Called when FCM token refreshes — re-register.
     */
    fun onNewToken(token: String) {
        setPushToken(token)
    }

    /**
     * Proactively fetch FCM token and register with backend.
     */
    fun registerToken() {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    if (token != null) setPushToken(token)
                } else {
                    Log.warning("Failed to get FCM token: ${task.exception?.message}")
                }
            }
        } catch (e: Exception) {
            Log.warning("Firebase not available: ${e.message}")
        }
    }

    private fun registerTokenWithBackend(token: String) {
        scope.launch {
            try {
                val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                val appVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                } catch (_: Exception) { "" }

                val body = JSONObject().apply {
                    put("token", token)
                    put("platform", "android")
                    put("device_id", deviceId ?: "")
                    put("app_version", appVersion)
                    put("sdk_version", AppDNA.sdkVersion)
                    put("os_version", Build.VERSION.RELEASE)
                }
                apiClient?.post("/api/v1/push/token", body.toString())
                Log.info("Push token registered with backend")
            } catch (e: Exception) {
                Log.warning("Failed to register push token with backend: ${e.message}")
            }
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
