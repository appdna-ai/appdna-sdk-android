package ai.appdna.sdk.screens

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.core.AudienceRuleEvaluator
import ai.appdna.sdk.core.ConditionEvaluator
import java.util.concurrent.locks.ReentrantLock

// SPEC-070-A B.6 — visibility relaxed from `internal` to public so hosts can
// register an `AppDNAScreenDelegate` via `ScreenManager.shared.setDelegate(...)`.
// The class itself remains opaque — only the delegate-setter and a couple of
// existing fan-out methods (`showScreen`/`showFlow`) form the public API.
class ScreenManager private constructor() {
    companion object { val shared = ScreenManager() }

    private var screenIndex: ScreenIndex? = null
    private val screenCache = mutableMapOf<String, ScreenConfig>()
    private val flowCache = mutableMapOf<String, FlowConfig>()
    private var nestingDepth = 0
    private val maxNestingDepth = 5
    private val lock = ReentrantLock()
    private val autoTriggerEngine = AutoTriggerEngine()
    @Volatile private var interceptionEnabled = false
    @Volatile private var interceptionFilter: List<String>? = null

    /**
     * SPEC-070-A B.6 — server-driven screen delegate. Hosts register via
     * [setDelegate]. Read fresh on every callback so a delegate registered
     * after the first showScreen() call still receives subsequent events.
     */
    @Volatile private var screenDelegate: AppDNAScreenDelegate? = null

    /** SPEC-070-A B.6 — register the host's screen delegate. */
    fun setDelegate(delegate: AppDNAScreenDelegate?) {
        screenDelegate = delegate
    }

    internal fun updateIndex(index: ScreenIndex) { lock.lock(); try { screenIndex = index } finally { lock.unlock() } }
    internal fun cacheScreen(id: String, config: ScreenConfig) { lock.lock(); try { screenCache[id] = config } finally { lock.unlock() } }
    internal fun getCachedScreen(id: String): ScreenConfig? { lock.lock(); try { return screenCache[id] } finally { lock.unlock() } }
    internal fun cacheFlow(id: String, config: FlowConfig) { lock.lock(); try { flowCache[id] = config } finally { lock.unlock() } }
    internal fun getCachedFlow(id: String): FlowConfig? { lock.lock(); try { return flowCache[id] } finally { lock.unlock() } }

    fun showScreen(screenId: String, callback: ((ScreenResult) -> Unit)? = null) {
        // Acquire lock once for the entire nesting check + config lookup + decrement
        val config: ScreenConfig?
        lock.lock()
        try {
            if (nestingDepth >= maxNestingDepth) {
                callback?.invoke(ScreenResult(screenId = screenId, dismissed = true, error = ScreenError.NESTING_DEPTH_EXCEEDED))
                return
            }
            nestingDepth++
            config = screenCache[screenId]
        } finally {
            lock.unlock()
        }
        val startTime = System.currentTimeMillis()
        try {
            if (config == null) {
                val errResult = ScreenResult(screenId = screenId, dismissed = true, error = ScreenError.SCREEN_NOT_FOUND)
                callback?.invoke(errResult)
                // SPEC-070-A G.8 — emit `screen_dismissed` even on the not-found
                // path so analytics shows the failed surface. Mirrors iOS error
                // dispatch shape.
                AppDNA.track("screen_dismissed", mapOf(
                    "screen_id" to screenId,
                    "duration_ms" to 0,
                    "error" to "screen_not_found",
                ))
                fireOnScreenDismissed(screenId, errResult)
                return
            }
            // In production, launch ScreenActivity with config
            AppDNA.track("screen_presented", mapOf("screen_id" to screenId, "screen_name" to config.name, "presentation" to config.presentation))
            // SPEC-070-A B.6 — fire onScreenPresented to host delegate.
            // Mirrors iOS Screens/ScreenManager.swift:185.
            fireOnScreenPresented(screenId)

            val result = ScreenResult(screenId = screenId)
            callback?.invoke(result)

            // SPEC-070-A G.8 — `screen_dismissed` matches iOS
            // ScreenManager.swift:192 emitted on the dismiss completion
            // path. Even though Android's current implementation completes
            // synchronously without a real Activity launch, we emit the
            // event so downstream analytics + delegate fan-out has full
            // parity. When ScreenActivity ships (later phase), this can
            // be moved to the actual dismiss callback.
            val durationMs = (System.currentTimeMillis() - startTime).toInt()
            AppDNA.track("screen_dismissed", mapOf(
                "screen_id" to screenId,
                "screen_name" to config.name,
                "duration_ms" to durationMs,
            ))
            // SPEC-070-A B.6 — fire onScreenDismissed delegate.
            fireOnScreenDismissed(
                screenId,
                ScreenResult(screenId = screenId, dismissed = true, durationMs = durationMs),
            )
        } finally {
            lock.lock()
            try { nestingDepth-- } finally { lock.unlock() }
        }
    }

    fun showFlow(flowId: String, callback: ((FlowResult) -> Unit)? = null) {
        val config = getCachedFlow(flowId)
        if (config == null) {
            val errResult = FlowResult(flowId = flowId, error = ScreenError.SCREEN_NOT_FOUND)
            callback?.invoke(errResult)
            // SPEC-070-A G.8 — `flow_abandoned` covers the not-found bailout
            // (no flow ran to completion). Mirrors iOS shape.
            AppDNA.track("flow_abandoned", mapOf(
                "flow_id" to flowId,
                "error" to "flow_not_found",
            ))
            fireOnFlowCompleted(flowId, errResult)
            return
        }
        AppDNA.track("flow_started", mapOf("flow_id" to flowId, "flow_name" to config.name))
        val result = FlowResult(flowId = flowId)
        callback?.invoke(result)
        // SPEC-070-A G.8 — `flow_completed` matches iOS
        // ScreenManager.swift:222 (the completed-vs-abandoned split is
        // driven by the FlowResult.completed flag — `flow_abandoned` for
        // false, `flow_completed` for true). Without a real FlowManager
        // running we treat the synchronous bailout as `flow_abandoned`
        // to avoid false positives.
        val eventName = if (result.completed) "flow_completed" else "flow_abandoned"
        AppDNA.track(eventName, mapOf(
            "flow_id" to flowId,
            "flow_name" to config.name,
        ))
        // SPEC-070-A B.6 — fire onFlowCompleted delegate (fires on both
        // completed and abandoned paths — host reads result.completed).
        fireOnFlowCompleted(flowId, result)
    }

    fun dismissScreen() { /* Dismiss current activity */ }

    // SPEC-070-A B.6 — main-thread delegate fan-out helpers. All read the
    // delegate fresh on every call so hosts can register/unregister
    // dynamically without missing events.

    private fun fireOnScreenPresented(screenId: String) {
        val delegate = screenDelegate ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { delegate.onScreenPresented(screenId) } catch (_: Throwable) { /* swallow */ }
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    private fun fireOnScreenDismissed(screenId: String, result: ScreenResult) {
        val delegate = screenDelegate ?: return
        val map = mutableMapOf<String, Any?>(
            "screen_id" to result.screenId,
            "dismissed" to result.dismissed,
            "responses" to result.responses,
            "last_action" to result.lastAction,
            "duration_ms" to result.durationMs,
        )
        result.error?.let { map["error"] = it.name }
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { delegate.onScreenDismissed(screenId, map) } catch (_: Throwable) { /* swallow */ }
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    private fun fireOnFlowCompleted(flowId: String, result: FlowResult) {
        val delegate = screenDelegate ?: return
        val map = mutableMapOf<String, Any?>(
            "flow_id" to result.flowId,
            "completed" to result.completed,
            "last_screen_id" to result.lastScreenId,
            "responses" to result.responses,
            "screens_viewed" to result.screensViewed,
            "duration_ms" to result.durationMs,
        )
        result.error?.let { map["error"] = it.name }
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { delegate.onFlowCompleted(flowId, map) } catch (_: Throwable) { /* swallow */ }
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    /**
     * SPEC-070-A B.6 + G.8 — public hook for SectionRegistry / Activity-based
     * action dispatch to fire the screen delegate's `onScreenAction` veto
     * hook AND emit the `screen_action` analytics event. Returns `false`
     * when the host vetoed the action — the caller MUST NOT execute the
     * default action handling in that case.
     *
     * [action] mirrors iOS `SectionAction` payload shape (`type` plus
     * type-specific fields).
     */
    fun handleScreenAction(screenId: String, action: Map<String, Any?>): Boolean {
        val actionType = (action["type"] as? String) ?: "unknown"
        // SPEC-070-A G.8 — emit screen_action with same shape as iOS
        // ScreenManager.swift:332.
        AppDNA.track("screen_action", mapOf(
            "screen_id" to screenId,
            "action_type" to actionType,
        ))
        // SPEC-070-A B.6 — veto hook: false from delegate prevents default
        // handling. Synchronous because the caller relies on the return
        // value to skip default behavior. Errors swallowed → treat as
        // "no veto" (allow).
        val delegate = screenDelegate ?: return true
        return try {
            delegate.onScreenAction(screenId, action)
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * SPEC-070-A G.8 — emit `screen_response_submitted` for form/submit
     * actions. Hosts call this when a screen submits a form payload to
     * the SDK. Mirrors iOS ScreenManager.swift:311.
     */
    fun trackScreenResponseSubmitted(screenId: String, fieldCount: Int) {
        AppDNA.track("screen_response_submitted", mapOf(
            "screen_id" to screenId,
            "field_count" to fieldCount,
        ))
    }

    fun screenForSlot(slotName: String): Pair<String, ScreenConfig>? {
        val index = screenIndex ?: return null
        val assignment = index.slots?.firstOrNull { it.slotName == slotName } ?: return null
        if (assignment.audienceRules != null) {
            val traits = AppDNA.getUserTraits()
            if (!AudienceRuleEvaluator.evaluate(assignment.audienceRules, traits)) return null
        }
        val config = getCachedScreen(assignment.screenId) ?: return null
        return assignment.screenId to config
    }

    fun evaluateTriggers(event: String, properties: Map<String, Any>?) {
        val index = screenIndex ?: return
        val entries = index.screens ?: return
        val traits = AppDNA.getUserTraits()
        val screenId = autoTriggerEngine.evaluate(entries, event, properties, traits, sessionCount = 1, daysSinceInstall = 0, currentScreenName = null)
        if (screenId != null) {
            if (!ai.appdna.sdk.core.PresentationCoordinator.shared.canPresent(
                ai.appdna.sdk.core.PresentationCoordinator.PresentationType.SCREEN, isAutoTriggered = true)) return
            showScreen(screenId)
        }
    }

    fun enableNavigationInterception(forScreens: List<String>? = null) {
        interceptionEnabled = true
        interceptionFilter = forScreens
        // SPEC-070-A A.10: register a marker hook per pattern so
        // `NavigationInterceptor.shared` knows which screens this host has
        // opted in for. The hook body is a no-op returning `Allow` —
        // server-driven `screen_index.interceptions[*]` evaluation is
        // already wired inside `NavigationInterceptor.evaluateInterceptions`
        // which calls back into `ScreenManager.evaluateInterceptions`. The
        // hook list serves as the host's allowlist; without these entries
        // the interceptor still fans out via Activity callbacks but won't
        // be aware of which Compose-only routes are eligible.
        val patterns = forScreens ?: listOf("*")
        for (pattern in patterns) {
            NavigationInterceptor.shared.registerHook(pattern) { _ ->
                InterceptionResult.Allow
            }
        }
    }
    fun disableNavigationInterception() {
        interceptionEnabled = false
        val patterns = interceptionFilter ?: listOf("*")
        for (pattern in patterns) {
            NavigationInterceptor.shared.unregisterHook(pattern)
        }
        interceptionFilter = null
    }

    fun evaluateInterceptions(screenName: String, timing: String) {
        if (!interceptionEnabled) return
        val filter = interceptionFilter
        if (filter != null && !filter.any { matchGlob(it, screenName) }) return
        val interceptions = screenIndex?.interceptions ?: return
        val traits = AppDNA.getUserTraits()
        for (interception in interceptions) {
            if (interception.timing != timing) continue
            if (!matchGlob(interception.triggerScreen, screenName)) continue
            if (interception.audienceRules != null && !AudienceRuleEvaluator.evaluate(interception.audienceRules, traits)) continue
            AppDNA.track("interception_triggered", mapOf("trigger_screen" to screenName, "timing" to timing, "screen_id" to interception.screenId))
            showScreen(interception.screenId)
            return
        }
    }

    private fun matchGlob(pattern: String, string: String): Boolean {
        val p = pattern.lowercase(); val s = string.lowercase()
        if (!p.contains("*")) return p == s
        if (p.startsWith("*") && p.endsWith("*")) return s.contains(p.drop(1).dropLast(1))
        if (p.startsWith("*")) return s.endsWith(p.drop(1))
        if (p.endsWith("*")) return s.startsWith(p.dropLast(1))
        return p == s
    }

    fun reset() { lock.lock(); try { screenIndex = null; screenCache.clear(); flowCache.clear(); nestingDepth = 0 } finally { lock.unlock() }; disableNavigationInterception() }
}
