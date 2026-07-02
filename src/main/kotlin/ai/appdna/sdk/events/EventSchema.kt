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
 * SPEC-070-A G.10: `environment` ("production" / "sandbox") is included in the envelope so
 *   ingest can route test-key traffic to the sandbox dataset.
 * SPEC-070-A G.15: `device.os` is emitted as a 3-part dotted string (e.g. `"14.0.0"`),
 *   matching iOS `UIDevice.current.systemVersion` semantics.
 * SPEC-070-A G.16: `device.locale` is emitted as a BCP-47 language tag
 *   (`Locale.toLanguageTag()`, e.g. `"zh-Hans-CN"`) — was previously the legacy
 *   `Locale.toString()` which produces `zh_CN_#Hans` style strings.
 * SPEC-070-A G.17: optional `context.screen` field for SPEC-086 zero-code attribution.
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
        experimentExposures: List<ExperimentExposure>? = null,
        environment: String? = null,
        screen: String? = null,
        pushId: String? = null
    ): JSONObject {
        return JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("event_id", UUID.randomUUID().toString().lowercase())
            put("event_name", eventName)
            put("ts_ms", System.currentTimeMillis())
            // SPEC-070-A G.10: tag every event with the active SDK environment
            // so ingest can fan test-key traffic into the sandbox dataset.
            if (environment != null) {
                put("environment", environment)
            }

            put("user", JSONObject().apply {
                put("anon_id", identity.anonId)
                if (identity.userId != null) put("user_id", identity.userId)
            })

            put("device", JSONObject().apply {
                put("platform", "android")
                // SPEC-070-C D4: SDK-wrapper attribution (native|flutter|react_native).
                put("framework", ai.appdna.sdk.AppDNA.framework)
                // SPEC-070-A G.15: emit `device.os` as a 3-part dotted string so it
                // matches the iOS shape `"<major>.<minor>.<patch>"`. Android
                // `Build.VERSION.RELEASE` is just the major (e.g. "14") on most
                // releases — pad to "<major>.0.0" when the OEM string lacks dots.
                put("os", normalizeOsVersion(Build.VERSION.RELEASE))
                put("app_version", appVersion)
                put("sdk_version", SDK_VERSION)
                if (ai.appdna.sdk.AppDNA.currentBundleVersion > 0) {
                    put("bundle_version", ai.appdna.sdk.AppDNA.currentBundleVersion)
                }
                // SPEC-070-A G.16: emit BCP-47 language tag (e.g. "zh-Hans-CN")
                // instead of the legacy `Locale.toString()` form ("zh_CN_#Hans"),
                // so the field round-trips with iOS `Locale.identifier` exactly.
                put("locale", Locale.getDefault().toLanguageTag())
                put("country", Locale.getDefault().country)
            })

            put("context", JSONObject().apply {
                put("session_id", sessionId)
                // SPEC-070-A G.17: optional screen name for zero-code attribution.
                if (screen != null) {
                    put("screen", screen)
                }
                // SPEC-070-A H.7: most-recently-received push id, propagated
                // for the rolling 30-minute attribution window so subsequent
                // events can be tied back to the push that triggered them.
                // Mirrors iOS `EventContext.push_id`.
                if (pushId != null) {
                    put("push_id", pushId)
                }
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

    /**
     * SPEC-070-A G.15: Pad `Build.VERSION.RELEASE` to a 3-part `<major>.<minor>.<patch>`
     * dotted string so the wire-format matches iOS `UIDevice.current.systemVersion`.
     *
     * Examples:
     *   "14"        → "14.0.0"
     *   "14.0"      → "14.0.0"
     *   "14.0.1"    → "14.0.1"
     *   "11-rc1"    → "11-rc1.0.0"  (best-effort; very rare in practice)
     */
    internal fun normalizeOsVersion(raw: String?): String {
        val v = (raw ?: "").trim()
        if (v.isEmpty()) return "0.0.0"
        val dots = v.count { it == '.' }
        return when (dots) {
            0 -> "$v.0.0"
            1 -> "$v.0"
            else -> v
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
