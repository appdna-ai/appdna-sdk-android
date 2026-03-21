package ai.appdna.sdk.paywalls

import android.app.Activity
import ai.appdna.sdk.Log
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.events.EventTracker

/**
 * Manages paywall presentation and event tracking (Android).
 * Mirrors the iOS PaywallManager behavior.
 */
internal class PaywallManager(
    private val remoteConfigManager: RemoteConfigManager,
    private val eventTracker: EventTracker
) {

    /**
     * Present a paywall. Must be called with a valid Activity.
     */
    fun present(
        activity: Activity,
        id: String,
        context: PaywallContext? = null,
        listener: AppDNAPaywallDelegate? = null
    ) {
        val config = remoteConfigManager.getPaywallConfig(id)
        if (config == null) {
            Log.error("Paywall config not found for id: $id")
            listener?.onPaywallPurchaseFailed(
                paywallId = id,
                error = IllegalStateException("Paywall config not found")
            )
            return
        }

        // Track view event
        eventTracker.track("paywall_view", mapOf(
            "paywall_id" to id,
            "placement" to (context?.placement ?: "unknown")
        ))

        // Launch the PaywallActivity
        PaywallActivity.launch(
            context = activity,
            paywallId = id,
            config = config,
            paywallContext = context,
            onAppear = {
                listener?.onPaywallPresented(paywallId = id)
            },
            onDismiss = { reason ->
                eventTracker.track("paywall_close", mapOf(
                    "paywall_id" to id,
                    "dismiss_reason" to reason.value
                ))
                listener?.onPaywallDismissed(paywallId = id)
            },
            onPlanSelected = { plan, metadata ->
                listener?.onPaywallPurchaseStarted(paywallId = id, productId = plan.product_id)
                // AC-038: Include toggle states in purchase event metadata
                val props = mutableMapOf<String, Any>(
                    "paywall_id" to id,
                    "product_id" to plan.product_id
                )
                props.putAll(metadata)
                eventTracker.track("purchase_started", props)
            },
            // AC-037: Wire delegate promo code callback
            onPromoCodeSubmit = if (listener != null) { code, completion ->
                listener.onPromoCodeSubmit(paywallId = id, code = code, completion = completion)
            } else null
        )
    }
}
