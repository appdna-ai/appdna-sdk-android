package ai.appdna.sdk.onboarding

import android.app.Activity
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.core.AudienceRule
import ai.appdna.sdk.core.AudienceRuleEvaluator
import ai.appdna.sdk.core.AudienceRuleSet
import ai.appdna.sdk.events.EventTracker

/**
 * Manages onboarding flow presentation, state, and event tracking (Android).
 * Mirrors the iOS OnboardingFlowManager behavior.
 */
internal class OnboardingFlowManager(
    private val remoteConfigManager: RemoteConfigManager,
    private val eventTracker: EventTracker
) {

    /**
     * Present an onboarding flow. Returns false if config is unavailable.
     */
    fun present(
        activity: Activity,
        flowId: String? = null,
        listener: AppDNAOnboardingDelegate? = null
    ): Boolean {
        // SPEC-070-A F.13: when no explicit flowId is supplied, evaluate every
        // flow's audience_rules against current userTraits and pick the
        // highest-priority match. Falls back to the active flow when nothing
        // matches. Mirrors iOS OnboardingFlowManager.swift:121-137.
        val flow = resolveFlow(flowId)
        if (flow == null) {
            Log.warning("Onboarding flow not found -- flowId: ${flowId ?: "active"}")
            return false
        }

        // Track flow started
        eventTracker.track("onboarding_flow_started", mapOf(
            "flow_id" to flow.id,
            "flow_version" to flow.version
        ))

        val startTime = System.currentTimeMillis()

        // Notify delegate of flow start
        listener?.onOnboardingStarted(flowId = flow.id)

        // Launch the OnboardingActivity
        OnboardingActivity.launch(
            context = activity,
            flow = flow,
            delegate = listener,
            eventTracker = eventTracker,
            onStepViewed = { stepId, stepIndex ->
                eventTracker.track("onboarding_step_viewed", mapOf(
                    "flow_id" to flow.id,
                    "step_id" to stepId,
                    "step_index" to stepIndex,
                    "step_type" to (flow.steps.getOrNull(stepIndex)?.type?.value ?: "unknown")
                ))
                listener?.onOnboardingStepChanged(flowId = flow.id, stepId = stepId, stepIndex = stepIndex, totalSteps = flow.steps.size)
            },
            onStepCompleted = { stepId, stepIndex, data ->
                eventTracker.track("onboarding_step_completed", mapOf(
                    "flow_id" to flow.id,
                    "step_id" to stepId,
                    "step_index" to stepIndex,
                    "selection_data" to (data ?: emptyMap<String, Any>())
                ))
            },
            onStepSkipped = { stepId, stepIndex ->
                eventTracker.track("onboarding_step_skipped", mapOf(
                    "flow_id" to flow.id,
                    "step_id" to stepId,
                    "step_index" to stepIndex
                ))
            },
            onFlowCompleted = { responses ->
                val durationMs = System.currentTimeMillis() - startTime
                eventTracker.track("onboarding_flow_completed", mapOf(
                    "flow_id" to flow.id,
                    "total_steps" to flow.steps.size,
                    "total_duration_ms" to durationMs,
                    "responses" to responses
                ))
                // SPEC-088: Persist onboarding responses for cross-module access
                ai.appdna.sdk.core.SessionDataStore.instance?.setOnboardingResponses(responses)
                listener?.onOnboardingCompleted(flowId = flow.id, responses = responses)
            },
            onFlowDismissed = { lastStepId, lastStepIndex ->
                eventTracker.track("onboarding_flow_dismissed", mapOf(
                    "flow_id" to flow.id,
                    "last_step_id" to lastStepId,
                    "last_step_index" to lastStepIndex
                ))
                listener?.onOnboardingDismissed(flowId = flow.id, atStep = lastStepIndex)
            }
        )

        return true
    }

    // MARK: - Private

    /**
     * SPEC-070-A F.13: resolve a flow given an optional explicit id.
     *
     * - Explicit id → look up directly via [RemoteConfigManager.getOnboardingFlow].
     * - No id + non-empty userTraits → evaluate every flow's `audience_rules`
     *   and pick the highest-priority match.
     * - Otherwise → fall back to the active flow (`getOnboardingFlow(null)`).
     *
     * Mirrors iOS `Onboarding/OnboardingFlowManager.swift:115-145`.
     */
    private fun resolveFlow(flowId: String?): OnboardingFlowConfig? {
        if (flowId != null) {
            return remoteConfigManager.getOnboardingFlow(flowId)
        }

        val userTraits = AppDNA.getUserTraits()
        if (userTraits.isNotEmpty()) {
            val matches = remoteConfigManager.getAllOnboardingFlows().values
                .mapNotNull { flow ->
                    val (matched, priority) = evaluateAudienceRules(flow.audience_rules, userTraits)
                    if (matched) flow to priority else null
                }
                .sortedByDescending { it.second }

            val best = matches.firstOrNull()
            if (best != null) {
                Log.info("[Onboarding] Audience-matched flow: ${best.first.id} (priority: ${best.second})")
                return best.first
            }
        }

        return remoteConfigManager.getOnboardingFlow(null)
    }

    /**
     * Evaluate `audience_rules` against [traits].
     *
     * Accepts both shapes used by the console / iOS:
     *   - `[ {trait, operator, value}, ... ]`  (raw rule list — AND across rules)
     *   - `{ priority, conditions: [...], match_mode: "all" | "any" }`
     *     (mirrors [AudienceRuleSet])
     *
     * Returns `(matched, priority)` so the caller can sort highest-priority first.
     * `null` rules → `(false, 0)` so flows without targeting only become the fallback,
     * never an audience match. Mirrors iOS AudienceRuleEvaluator semantics.
     */
    @Suppress("UNCHECKED_CAST")
    private fun evaluateAudienceRules(rules: Any?, traits: Map<String, Any>): Pair<Boolean, Int> {
        return when (rules) {
            null -> false to 0
            is List<*> -> {
                val list = rules.mapNotNull { (it as? Map<String, Any?>)?.let(AudienceRule.Companion::fromMap) }
                if (list.isEmpty()) false to 0
                else AudienceRuleEvaluator.evaluate(list, traits) to 0
            }
            is Map<*, *> -> {
                val mp = rules as Map<String, Any?>
                val priority = (mp["priority"] as? Number)?.toInt() ?: 0
                val ruleSet = AudienceRuleSet.fromMap(mp)
                val matched = AudienceRuleEvaluator.evaluate(ruleSet, traits)
                matched to priority
            }
            else -> false to 0
        }
    }
}
