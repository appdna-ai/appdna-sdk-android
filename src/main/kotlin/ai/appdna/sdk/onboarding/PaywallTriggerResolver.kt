package ai.appdna.sdk.onboarding

/**
 * The `paywall_trigger` skip decision, lifted out of the `presentPaywallTriggerNode` closure in
 * `OnboardingFlowHost`.
 *
 * WHY: whether an already-subscribed (or runtime-locked) user sees a paywall, and where the flow
 * routes when they don't, is a pure function of the node's data — but it was buried inside a
 * Composable closure that also reaches for an Activity, Play Billing and the event tracker, so it
 * could not be exercised in a unit test. Verbatim move of the gate; the composable now performs
 * the tracking + routing the outcome describes.
 */
internal object PaywallTriggerResolver {

    sealed class PaywallTriggerOutcome {
        /** Node carries no `paywall_id` — the flow completes (mirrors iOS `presentPaywallTrigger`). */
        object CompleteFlow : PaywallTriggerOutcome()

        /** Show the paywall. */
        data class Present(val paywallId: String) : PaywallTriggerOutcome()

        /**
         * Skip the paywall. Caller tracks `onboarding_paywall_skip` with [reason], then routes via
         * `routeOutcome(target, defaultBehavior, reason)`.
         */
        data class Skip(
            val paywallId: String,
            val target: String?,
            val defaultBehavior: String,
            val reason: String,
        ) : PaywallTriggerOutcome()
    }

    const val REASON_RUNTIME_LOCKED = "sdk_runtime_locked"
    const val REASON_ALREADY_SUBSCRIBED = "user_already_subscribed"

    fun decide(
        triggerData: Map<String, Any?>?,
        hasActiveSubscription: Boolean,
        runtimeLocked: Boolean,
    ): PaywallTriggerOutcome {
        // SPEC-401-A R63 — only the two iOS sources (`paywall_id` / `paywallId`); deriving an id
        // from the node id itself pointed at paywalls that don't exist.
        val paywallId = (triggerData?.get("paywall_id") as? String)?.takeIf { it.isNotBlank() }
            ?: (triggerData?.get("paywallId") as? String)?.takeIf { it.isNotBlank() }
            ?: return PaywallTriggerOutcome.CompleteFlow

        // SPEC-403 resolver chain — on_subscribed_skip_target wins, falls back to
        // on_success_target (back-compat with SPEC-401 1.0.61 workaround flows), then to
        // "continue" (legacy edge) inside the caller's routeOutcome.
        val onSuccessTarget = (triggerData?.get("on_success_target") as? String)?.takeIf { it.isNotBlank() }
        val onSubscribedSkipTarget =
            (triggerData?.get("on_subscribed_skip_target") as? String)?.takeIf { it.isNotBlank() }
        val skipTarget = onSubscribedSkipTarget ?: onSuccessTarget

        // SPEC-404 — runtime lock skip: the backend has signalled locked mode (per-key suspended
        // day 20+ OR org cancelled), so every paywall_trigger auto-skips.
        if (runtimeLocked) {
            return PaywallTriggerOutcome.Skip(paywallId, skipTarget, "continue", REASON_RUNTIME_LOCKED)
        }

        // SPEC-401 Fix 1A — entitlement-aware skip gate. Default `true`: paywalls auto-skip for
        // already-subscribed users unless the author explicitly opts out (upsell paywalls).
        val skipIfSubscribed = (triggerData?.get("skip_if_subscribed") as? Boolean) ?: true
        if (skipIfSubscribed && hasActiveSubscription) {
            return PaywallTriggerOutcome.Skip(paywallId, skipTarget, "continue", REASON_ALREADY_SUBSCRIBED)
        }

        return PaywallTriggerOutcome.Present(paywallId)
    }
}
