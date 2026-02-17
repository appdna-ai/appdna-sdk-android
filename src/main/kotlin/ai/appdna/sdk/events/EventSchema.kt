package ai.appdna.sdk.events

import ai.appdna.sdk.DeviceIdentity
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * SDK event envelope — matches iOS SDKEvent schema exactly.
 */
internal object EventSchema {
    const val SCHEMA_VERSION = 1
    const val SDK_VERSION = "0.2.0"

    /**
     * Build an event envelope JSON matching the iOS format.
     */
    fun buildEnvelope(
        eventName: String,
        properties: Map<String, Any>?,
        identity: DeviceIdentity,
        sessionId: String,
        appVersion: String,
        analyticsConsent: Boolean
    ): JSONObject {
        return JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("event_id", UUID.randomUUID().toString().lowercase())
            put("event_name", eventName)
            put("ts_ms", System.currentTimeMillis())

            put("user", JSONObject().apply {
                put("anon_id", identity.anonId)
                if (identity.userId != null) put("user_id", identity.userId)
            })

            put("device", JSONObject().apply {
                put("platform", "android")
                put("os", Build.VERSION.RELEASE)
                put("app_version", appVersion)
                put("sdk_version", SDK_VERSION)
                put("locale", Locale.getDefault().toString())
                put("country", Locale.getDefault().country)
            })

            put("context", JSONObject().apply {
                put("session_id", sessionId)
            })

            if (properties != null && properties.isNotEmpty()) {
                put("properties", JSONObject(properties))
            }

            put("privacy", JSONObject().apply {
                put("consent", JSONObject().apply {
                    put("analytics", analyticsConsent)
                })
            })
        }
    }
}
