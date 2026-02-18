package ai.appdna.sdk.onboarding

import android.app.Activity
import ai.appdna.sdk.Log
import ai.appdna.sdk.config.RemoteConfigManager
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
        listener: AppDNAOnboardingListener? = null
    ): Boolean {
        // Resolve flow config
        val flow = remoteConfigManager.getOnboardingFlow(flowId)
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

        // Launch the OnboardingActivity
        OnboardingActivity.launch(
            context = activity,
            flow = flow,
            onStepViewed = { stepId, stepIndex ->
                eventTracker.track("onboarding_step_viewed", mapOf(
                    "flow_id" to flow.id,
                    "step_id" to stepId,
                    "step_index" to stepIndex,
                    "step_type" to flow.steps[stepIndex].type.value
                ))
                listener?.onboardingStepViewed(flowId = flow.id, stepId = stepId, stepIndex = stepIndex)
            },
            onStepCompleted = { stepId, stepIndex, data ->
                eventTracker.track("onboarding_step_completed", mapOf(
                    "flow_id" to flow.id,
                    "step_id" to stepId,
                    "step_index" to stepIndex,
                    "selection_data" to (data ?: emptyMap<String, Any>())
                ))
                listener?.onboardingStepCompleted(flowId = flow.id, stepId = stepId, data = data)
            },
            onStepSkipped = { stepId, stepIndex ->
                eventTracker.track("onboarding_step_skipped", mapOf(
                    "flow_id" to flow.id,
                    "step_id" to stepId,
                    "step_index" to stepIndex
                ))
                listener?.onboardingStepSkipped(flowId = flow.id, stepId = stepId)
            },
            onFlowCompleted = { responses ->
                val durationMs = System.currentTimeMillis() - startTime
                eventTracker.track("onboarding_flow_completed", mapOf(
                    "flow_id" to flow.id,
                    "total_steps" to flow.steps.size,
                    "total_duration_ms" to durationMs,
                    "responses" to responses
                ))
                listener?.onboardingFlowCompleted(flowId = flow.id, data = responses)
            },
            onFlowDismissed = { lastStepId, lastStepIndex ->
                eventTracker.track("onboarding_flow_dismissed", mapOf(
                    "flow_id" to flow.id,
                    "last_step_id" to lastStepId,
                    "last_step_index" to lastStepIndex
                ))
                listener?.onboardingFlowDismissed(flowId = flow.id, lastStepId = lastStepId)
            }
        )

        return true
    }
}
