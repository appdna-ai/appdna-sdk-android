package ai.appdna.sdk.events

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.Log
import org.json.JSONObject

/**
 * Builds event envelopes from current identity and queues them.
 */
internal class EventTracker(
    private val identityManager: IdentityManager,
    private val appVersion: String
) {
    private var eventQueue: EventQueue? = null
    private var analyticsConsent = true

    fun setEventQueue(queue: EventQueue) {
        this.eventQueue = queue
    }

    fun setConsent(analytics: Boolean) {
        analyticsConsent = analytics
    }

    /**
     * Track an event. If consent is false, the event is silently dropped.
     */
    fun track(event: String, properties: Map<String, Any>? = null) {
        if (!analyticsConsent) {
            Log.debug("Event '$event' dropped — analytics consent is false")
            return
        }

        val envelope = EventSchema.buildEnvelope(
            eventName = event,
            properties = properties,
            identity = identityManager.currentIdentity,
            sessionId = identityManager.sessionId,
            appVersion = appVersion,
            analyticsConsent = analyticsConsent
        )

        eventQueue?.enqueue(envelope)
        Log.debug("Tracked event: $event")
    }
}
