package ai.appdna.sdk.messages

// TODO(SPEC-070-A A.9 wiring):
//   1. AppDNA.kt:318-322 `track()` must call `messageManager?.onEvent(event, properties)`
//      AFTER `eventTracker.track(...)` and BEFORE `surveyManager?.onEvent(...)` —
//      iOS ordering: events → messages → surveys (so message veto can race
//      with survey eligibility correctly).
//   2. AppDNA.kt:304-311 `reset()` must call `messageManager?.resetSession()`
//      so in-session frequency counters + presentation queue are cleared
//      alongside other identity state.
//   3. AppDNA.kt configure() must instantiate `messageManager` AFTER
//      `remoteConfigManager` + `eventTracker` are non-null:
//          this.messageManager = MessageManager(
//              context = appContext,
//              configProvider = { remoteCfg.getActiveMessages() },
//              renderer = InAppMessageRenderer.shared,
//              frequencyStore = SessionDataStore.instance!!,
//          )
//      `RemoteConfigManager.getActiveMessages()` does not exist yet —
//      sibling subagent adds it as part of A.9 wiring (parses the `messages`
//      Firestore doc into Map<String, MessageConfig> and date-range filters
//      out inactive entries; iOS equivalent at `RemoteConfigManager.swift`).

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.AppDNAInAppMessageDelegate
import ai.appdna.sdk.Log
import ai.appdna.sdk.core.SessionDataStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SPEC-070-A A.9 — Manages in-app message trigger evaluation, frequency
 * tracking, and presentation. Mirrors iOS
 * `InAppMessaging/MessageManager.swift:6-267`.
 *
 * Architectural split (do not change):
 * - `MessageManager` owns: trigger evaluation, condition matching,
 *   frequency tracking, candidate selection, queue gate, delegate veto,
 *   `in_app_message_*` analytics events, CTA action routing.
 * - `InAppMessageView` (composable in `InAppMessageRenderer.kt`) owns:
 *   actual rendering of banner/modal/fullscreen/tooltip variants.
 *
 * `MessageManager` hosts the `InAppMessageView` inside a `ComponentDialog`
 * — same approach `PendingMessageListener` uses — so journey-delivered and
 * trigger-delivered messages both land in the same renderer.
 *
 * NOTE: this class deliberately does NOT touch the renderer ctor — it
 * uses the existing `@Composable InAppMessageView` function directly.
 * The `renderer` constructor parameter (kept on the public API for
 * symmetry with iOS DI) is currently unused as a delivery handle; it
 * only exists so a sample app or test can subclass / replace the
 * presentation pipeline.
 */
class MessageManager(
    private val context: Context,
    private val configProvider: () -> Map<String, MessageConfig>,
    @Suppress("unused") private val renderer: InAppMessageRenderer = InAppMessageRenderer.shared,
    private val frequencyStore: SessionDataStore,
) {

    /**
     * Public delegate. Hosted by `AppDNA.inAppMessages` namespace —
     * `AppDNAModules.kt InAppMessagesModule.setDelegate(...)` writes
     * through to this field.
     */
    @Volatile
    var delegate: AppDNAInAppMessageDelegate? = null

    private val isPresenting = AtomicBoolean(false)
    private val suppress = AtomicBoolean(false)
    private val frequencyTracker = MessageFrequencyTracker(frequencyStore)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Evaluate every active message against an event. Called from
     * `AppDNA.track()` after the event has been written to the queue
     * (see file-top wiring TODO).
     */
    fun onEvent(eventName: String, props: Map<String, Any?>) {
        if (isPresenting.get() || suppress.get()) {
            Log.debug("[Messages] Skipping '$eventName' — isPresenting=${isPresenting.get()}, suppress=${suppress.get()}")
            return
        }

        val messages = try {
            configProvider()
        } catch (e: Throwable) {
            Log.warning("[Messages] configProvider threw: ${e.message}")
            return
        }

        Log.debug("[Messages] Evaluating ${messages.size} active message(s) for '$eventName'")

        var filteredByEvent = 0
        var filteredByConditions = 0
        var filteredByFrequency = 0
        var filteredByDateRange = 0
        val candidates: MutableList<Pair<String, MessageConfig>> = mutableListOf()

        for ((id, config) in messages) {
            val rules = config.trigger_rules

            // 1. Event name match
            if (rules.event != eventName) {
                filteredByEvent++
                continue
            }
            // 2. Conditions evaluation
            if (!evaluateConditions(rules.conditions, props)) {
                filteredByConditions++
                continue
            }
            // 3. Frequency check
            if (!frequencyTracker.canShow(id, rules.frequency, rules.max_displays)) {
                filteredByFrequency++
                continue
            }
            // 4. Date range check
            if (!checkDateRange(config)) {
                filteredByDateRange++
                continue
            }
            candidates.add(id to config)
        }

        if (candidates.isEmpty()) {
            Log.debug("[Messages] No candidates for '$eventName' — filtered: event=$filteredByEvent, conditions=$filteredByConditions, frequency=$filteredByFrequency, dateRange=$filteredByDateRange")
            return
        }

        // 5. Sort by priority (highest first); tie-break by id for determinism.
        val winner = candidates.sortedWith(
            compareByDescending<Pair<String, MessageConfig>> { it.second.priority }.thenBy { it.first }
        ).first()

        // 6. Optional delay
        val delaySec = winner.second.trigger_rules.delay_seconds ?: 0
        val runnable = Runnable { present(winner.first, winner.second, eventName) }
        if (delaySec > 0) {
            mainHandler.postDelayed(runnable, delaySec * 1000L)
        } else {
            mainHandler.post(runnable)
        }
    }

    /**
     * Toggle session-wide suppression. While true, `onEvent` is a no-op.
     */
    fun suppressDisplay(suppress: Boolean) {
        this.suppress.set(suppress)
    }

    /**
     * Clear in-session frequency counters + queue gate. Called from
     * `AppDNA.reset()`.
     */
    fun resetSession() {
        frequencyTracker.resetSession()
        isPresenting.set(false)
    }

    // MARK: - Presentation

    private fun present(messageId: String, config: MessageConfig, triggerEvent: String) {
        // Re-check the gate inside the main-thread runnable in case a
        // previous async dispatch flipped it.
        if (!isPresenting.compareAndSet(false, true)) {
            Log.debug("[Messages] $messageId skipped — another message presenting")
            return
        }

        // SPEC-400 — `shouldShowMessage` veto. Run BEFORE any analytics
        // tracking or view construction so a vetoed message produces no
        // `in_app_message_shown` event.
        val host = delegate
        if (host != null && !host.shouldShowMessage(messageId)) {
            Log.debug("[Messages] $messageId vetoed by host shouldShowMessage")
            isPresenting.set(false)
            return
        }

        val activity = currentActivity()
        if (activity == null) {
            Log.warning("[Messages] No foreground activity for $messageId")
            isPresenting.set(false)
            return
        }

        // Record shown BEFORE rendering so concurrent `onEvent` calls
        // see the updated counter via `canShow`.
        frequencyTracker.recordShown(messageId, config.trigger_rules.frequency)

        AppDNA.track(
            "in_app_message_shown",
            mapOf(
                "message_id" to messageId,
                "message_type" to config.message_type.value,
                "trigger_event" to triggerEvent,
            ),
        )

        // SPEC-400 — fire onMessageShown
        mainHandler.post { delegate?.onMessageShown(messageId, triggerEvent) }

        try {
            val dialog = ComponentDialog(activity)
            val composeView = ComposeView(activity).apply {
                setViewTreeLifecycleOwner(dialog)
                setViewTreeSavedStateRegistryOwner(dialog)
                setContent {
                    InAppMessageView(
                        config = config,
                        onCTATap = {
                            val ctaActionType = config.content.cta_action?.type ?: "dismiss"
                            AppDNA.track(
                                "in_app_message_clicked",
                                mapOf("message_id" to messageId, "cta_action" to ctaActionType),
                            )
                            // SPEC-400 — onMessageAction with action type + cta data
                            val ctaData: Map<String, Any>? = config.content.cta_action?.url?.let { mapOf("url" to it) }
                            mainHandler.post { delegate?.onMessageAction(messageId, ctaActionType, ctaData) }

                            handleCTAAction(activity, config.content.cta_action)
                            dialog.dismiss()
                        },
                        onDismiss = {
                            AppDNA.track(
                                "in_app_message_dismissed",
                                mapOf("message_id" to messageId),
                            )
                            dialog.dismiss()
                        },
                    )
                }
            }
            dialog.setContentView(composeView)
            dialog.setOnDismissListener {
                isPresenting.set(false)
                mainHandler.post { delegate?.onMessageDismissed(messageId) }
            }
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
            }
            dialog.show()
        } catch (e: Throwable) {
            Log.warning("[Messages] present failed for $messageId: ${e.message}")
            isPresenting.set(false)
        }
    }

    private fun handleCTAAction(activity: Activity, action: CTAAction?) {
        if (action == null) return
        when (action.type) {
            "deep_link", "open_url" -> {
                val url = action.url ?: return
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    activity.startActivity(intent)
                } catch (e: Throwable) {
                    Log.warning("[Messages] open URL failed: ${e.message}")
                }
            }
            else -> Unit // dismiss / unknown handled by dialog dismiss
        }
    }

    // MARK: - Condition evaluation (mirrors MessageManager.swift:196-233)

    private fun evaluateConditions(conditions: List<TriggerCondition>?, properties: Map<String, Any?>): Boolean {
        if (conditions.isNullOrEmpty()) return true
        return conditions.all { condition ->
            val propValue = properties[condition.field] ?: return@all false
            val condValue = condition.value ?: return@all false
            evaluateOperator(condition.operator, propValue, condValue)
        }
    }

    private fun evaluateOperator(op: String, propValue: Any, condValue: Any): Boolean {
        return when (op) {
            "eq" -> propValue.toString() == condValue.toString()
            "gte" -> {
                val p = toDouble(propValue) ?: return false
                val c = toDouble(condValue) ?: return false
                p >= c
            }
            "lte" -> {
                val p = toDouble(propValue) ?: return false
                val c = toDouble(condValue) ?: return false
                p <= c
            }
            "gt" -> {
                val p = toDouble(propValue) ?: return false
                val c = toDouble(condValue) ?: return false
                p > c
            }
            "lt" -> {
                val p = toDouble(propValue) ?: return false
                val c = toDouble(condValue) ?: return false
                p < c
            }
            "contains" -> propValue.toString().contains(condValue.toString())
            else -> false
        }
    }

    private fun toDouble(value: Any): Double? = when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    // MARK: - Date range (yyyy-MM-dd, UTC, matching iOS `en_US_POSIX`)

    private fun checkDateRange(config: MessageConfig): Boolean {
        val now = Date()
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        config.start_date?.let { startStr ->
            val start = try { df.parse(startStr) } catch (_: Throwable) { null }
            if (start != null && now.before(start)) return false
        }
        config.end_date?.let { endStr ->
            val end = try { df.parse(endStr) } catch (_: Throwable) { null }
            if (end != null && now.after(end)) return false
        }
        return true
    }

    // MARK: - Activity lookup (reflection — mirrors PendingMessageListener)

    private fun currentActivity(): Activity? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentMethod = activityThread.getMethod("currentActivityThread")
            val thread = currentMethod.invoke(null)
            val activitiesField = activityThread.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val activities = activitiesField.get(thread) as? android.util.ArrayMap<Any, Any>
            activities?.values?.firstNotNullOfOrNull { record ->
                val pausedField = record.javaClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (!pausedField.getBoolean(record)) {
                    val activityField = record.javaClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    activityField.get(record) as? Activity
                } else null
            }
        } catch (e: Throwable) {
            Log.warning("[Messages] currentActivity reflection failed: ${e.message}")
            null
        }
    }
}

/**
 * Companion shared instance for the existing renderer. Kept here as a
 * lightweight handle so the manager has a typed dependency to inject.
 * The renderer is implemented as a top-level `@Composable` so this class
 * carries no state.
 */
class InAppMessageRenderer private constructor() {
    companion object {
        val shared: InAppMessageRenderer = InAppMessageRenderer()
    }
}

/**
 * Per-message frequency tracker. Mirrors iOS
 * `MessageFrequencyTracker` (private to `InAppMessaging`).
 *
 * Frequency strings (matching `TriggerRules.frequency`):
 * - `once` — show once per install (persisted)
 * - `once_per_session` — show once per app session (in-memory only)
 * - `every_time` — no cap
 * - `max_times` — show up to `max_displays` total (persisted)
 *
 * Persisted counters live under a `messages_freq` namespace inside
 * `SessionDataStore.sessionData` so they survive process death without
 * a dedicated table.
 */
internal class MessageFrequencyTracker(
    private val store: SessionDataStore,
) {
    private val prefix = "messages_freq:"
    private val sessionShown = ConcurrentHashMap<String, Int>()

    fun canShow(messageId: String, frequency: String, maxDisplays: Int?): Boolean {
        return when (frequency) {
            "once" -> totalShown(messageId) == 0
            "once_per_session" -> (sessionShown[messageId] ?: 0) == 0
            "every_time" -> true
            "max_times" -> {
                val cap = maxDisplays ?: return true
                totalShown(messageId) < cap
            }
            else -> true
        }
    }

    fun recordShown(messageId: String, frequency: String) {
        sessionShown.merge(messageId, 1) { acc, inc -> acc + inc }
        when (frequency) {
            "once", "max_times" -> {
                val n = totalShown(messageId) + 1
                store.setSessionData(prefix + messageId, n)
            }
            else -> Unit
        }
    }

    fun resetSession() {
        sessionShown.clear()
    }

    private fun totalShown(messageId: String): Int {
        return (store.getSessionData(prefix + messageId) as? Number)?.toInt() ?: 0
    }
}
