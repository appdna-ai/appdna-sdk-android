package ai.appdna.sdk.events

import ai.appdna.sdk.DeviceIdentity
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * SDK event envelope — matches iOS SDKEvent schema exactly.
 *
 * SPEC-070-A A.6: `SDK_VERSION` is the live `AppDNA.sdkVersion` value (not a stale literal).
 * SPEC-070-A A.14: `experiment_exposures` is now part of the envelope `context` object.
 */
internal object EventSchema {
    const val SCHEMA_VERSION = 1

    /**
     * SDK version reported in the event envelope `device.sdk_version` field.
     * Sources directly from [ai.appdna.sdk.AppDNA.sdkVersion] so it cannot drift
     * (the previous hard-coded "1.0.3" literal was 27 versions out of date).
     */
    val SDK_VERSION: String
        get() = ai.appdna.sdk.AppDNA.sdkVersion

    /**
     * Build an event envelope JSON matching the iOS format.
     */
    fun buildEnvelope(
        eventName: String,
        properties: Map<String, Any>?,
        identity: DeviceIdentity,
        sessionId: String,
        appVersion: String,
        analyticsConsent: Boolean,
        experimentExposures: List<ExperimentExposure>? = null
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
                if (ai.appdna.sdk.AppDNA.currentBundleVersion > 0) {
                    put("bundle_version", ai.appdna.sdk.AppDNA.currentBundleVersion)
                }
                put("locale", Locale.getDefault().toString())
                put("country", Locale.getDefault().country)
            })

            put("context", JSONObject().apply {
                put("session_id", sessionId)
                // SPEC-070-A A.14: include experiment exposure context so the
                // BigQuery ETL can attribute events to the assigned variant.
                // Shape mirrors iOS Events/EventSchema.swift `EventContext.experiment_exposures`:
                //   [{ exp: <experimentId>, variant: <variantId> }, ...]
                if (!experimentExposures.isNullOrEmpty()) {
                    put("experiment_exposures", JSONArray().apply {
                        for (exposure in experimentExposures) {
                            put(JSONObject().apply {
                                put("exp", exposure.experimentId)
                                put("variant", exposure.variantId)
                            })
                        }
                    })
                }
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

/**
 * SPEC-070-A A.14: Single experiment-exposure tuple emitted in the event envelope's
 * `context.experiment_exposures` array.
 *
 * Sourced from [ai.appdna.sdk.config.ExperimentManager.getExposures] when an event is tracked.
 * Wire-format shape matches iOS `Events/EventSchema.swift` `ExperimentExposure { exp, variant }`.
 */
internal data class ExperimentExposure(
    val experimentId: String,
    val variantId: String
)
