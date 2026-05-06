package ai.appdna.sdk.messages

import ai.appdna.sdk.core.SessionDataStore

/**
 * Tracks in-app message display frequency. Mirrors iOS
 * `InAppMessaging/MessageFrequencyTracker.swift` (SPEC-070-A F.12).
 *
 * Three frequency modes are supported, all evaluated by [canShow]:
 *  - `once` — persisted across sessions; uses `${prefix}<id>.shown` flag.
 *  - `once_per_session` — in-memory only; cleared by [resetSession].
 *  - `every_time` — always returns `true`.
 *  - `max_times` — persisted counter; show until `count >= max_displays`.
 *
 * Persistence is delegated to [SessionDataStore] (already wired in Phase A);
 * keys are namespaced under `msg_freq.*` so they don't collide with onboarding
 * responses or other session data.
 */
internal class MessageFrequencyTracker {

    /** In-memory set for `once_per_session` tracking. */
    private val sessionShownIds: MutableSet<String> = mutableSetOf()

    /**
     * Returns true if [messageId] can be shown given its [frequency] rules.
     * Pass [maxDisplays] only for `max_times`; ignored otherwise.
     */
    fun canShow(messageId: String, frequency: String, maxDisplays: Int? = null): Boolean {
        return when (frequency) {
            "once" -> !hasBeenShown(messageId)
            "once_per_session" -> !sessionShownIds.contains(messageId)
            "max_times" -> {
                val max = maxDisplays ?: return true
                displayCount(messageId) < max
            }
            "every_time" -> true
            else -> true  // unknown mode → don't gate (matches iOS fallback semantics)
        }
    }

    /**
     * Record that a message was shown. No-ops for `every_time`.
     */
    fun recordShown(messageId: String, frequency: String) {
        when (frequency) {
            "once" -> {
                SessionDataStore.instance?.setSessionData(shownKey(messageId), true)
            }
            "once_per_session" -> {
                sessionShownIds.add(messageId)
            }
            "max_times" -> {
                val next = displayCount(messageId) + 1
                SessionDataStore.instance?.setSessionData(countKey(messageId), next)
            }
            // every_time / unknown → nothing to record
        }
    }

    /** Reset session-level (`once_per_session`) tracking — call on new session. */
    fun resetSession() {
        sessionShownIds.clear()
    }

    // MARK: - Private

    private fun hasBeenShown(messageId: String): Boolean {
        return (SessionDataStore.instance?.getSessionData(shownKey(messageId)) as? Boolean) == true
    }

    private fun displayCount(messageId: String): Int {
        return when (val v = SessionDataStore.instance?.getSessionData(countKey(messageId))) {
            is Number -> v.toInt()
            else -> 0
        }
    }

    companion object {
        private const val PREFIX = "msg_freq"
        private fun shownKey(id: String) = "$PREFIX.$id.shown"
        private fun countKey(id: String) = "$PREFIX.$id.count"
    }
}
