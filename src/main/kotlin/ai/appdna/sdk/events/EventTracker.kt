package ai.appdna.sdk.events

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.Log
import org.json.JSONObject

/**
 * Builds event envelopes from current identity and queues them.
 *
 * SPEC-070-A A.14: Each tracked event is decorated with the current
 * `experiment_exposures` list (sourced from a lazy provider to break the
 * EventTracker ↔ ExperimentManager construction cycle).
 */
internal class EventTracker(
    private val identityManager: IdentityManager,
    private val appVersion: String
) {
    private var eventQueue: EventQueue? = null
    private var analyticsConsent = true

    /**
     * Lazy supplier of current experiment exposures. Wired by [AppDNA.configure]
     * after both EventTracker and ExperimentManager are constructed. Returning
     * null/empty list omits the field from the envelope (iOS parity).
     */
    private var exposureProvider: (() -> List<ExperimentExposure>)? = null

    fun setEventQueue(queue: EventQueue) {
        this.eventQueue = queue
    }

    /**
     * SPEC-070-A A.14: Wire the exposure source (typically `ExperimentManager.getExposures()`).
     * Setting this to null disables the field (used in tests).
     */
    fun setExperimentExposureProvider(provider: (() -> List<ExperimentExposure>)?) {
        this.exposureProvider = provider
    }

    fun setConsent(analytics: Boolean) {
        analyticsConsent = analytics
    }

    /** Whether analytics consent is currently granted. */
    val isConsentGranted: Boolean get() = analyticsConsent

    /**
     * Track an event. If consent is false, the event is silently dropped.
     */
    fun track(event: String, properties: Map<String, Any>? = null) {
        if (!analyticsConsent) {
            Log.debug("Event '$event' dropped — analytics consent is false")
            return
        }

        val exposures = try {
            exposureProvider?.invoke()
        } catch (e: Exception) {
            // Never let an exposure read break event tracking
            Log.warning("Exposure provider threw: ${e.message}")
            null
        }

        val envelope = EventSchema.buildEnvelope(
            eventName = event,
            properties = properties,
            identity = identityManager.currentIdentity,
            sessionId = identityManager.sessionId,
            appVersion = appVersion,
            analyticsConsent = analyticsConsent,
            experimentExposures = exposures
        )

        eventQueue?.enqueue(envelope)
        Log.debug("Tracked event: $event")
    }
}
