package ai.appdna.sdk.screens

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.core.AudienceRuleEvaluator
import ai.appdna.sdk.core.ConditionEvaluator
import java.util.concurrent.locks.ReentrantLock

internal class ScreenManager private constructor() {
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

    fun updateIndex(index: ScreenIndex) { lock.lock(); try { screenIndex = index } finally { lock.unlock() } }
    fun cacheScreen(id: String, config: ScreenConfig) { lock.lock(); try { screenCache[id] = config } finally { lock.unlock() } }
    fun getCachedScreen(id: String): ScreenConfig? { lock.lock(); try { return screenCache[id] } finally { lock.unlock() } }
    fun cacheFlow(id: String, config: FlowConfig) { lock.lock(); try { flowCache[id] = config } finally { lock.unlock() } }
    fun getCachedFlow(id: String): FlowConfig? { lock.lock(); try { return flowCache[id] } finally { lock.unlock() } }

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
        try {
            if (config == null) {
                callback?.invoke(ScreenResult(screenId = screenId, dismissed = true, error = ScreenError.SCREEN_NOT_FOUND))
                return
            }
            // In production, launch ScreenActivity with config
            AppDNA.track("screen_presented", mapOf("screen_id" to screenId, "screen_name" to config.name, "presentation" to config.presentation))
            callback?.invoke(ScreenResult(screenId = screenId))
        } finally {
            lock.lock()
            try { nestingDepth-- } finally { lock.unlock() }
        }
    }

    fun showFlow(flowId: String, callback: ((FlowResult) -> Unit)? = null) {
        val config = getCachedFlow(flowId)
        if (config == null) {
            callback?.invoke(FlowResult(flowId = flowId, error = ScreenError.SCREEN_NOT_FOUND))
            return
        }
        AppDNA.track("flow_started", mapOf("flow_id" to flowId, "flow_name" to config.name))
        callback?.invoke(FlowResult(flowId = flowId))
    }

    fun dismissScreen() { /* Dismiss current activity */ }

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

    fun enableNavigationInterception(forScreens: List<String>? = null) { interceptionEnabled = true; interceptionFilter = forScreens }
    fun disableNavigationInterception() { interceptionEnabled = false; interceptionFilter = null }

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
