package ai.appdna.sdk.paywalls

import android.app.Activity
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.PurchaseCancelledException
import ai.appdna.sdk.PurchaseFailedException
import ai.appdna.sdk.PurchasePendingException
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.events.EventTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages paywall presentation, purchase flow, restore flow, and event tracking (Android).
 * Mirrors iOS [Paywalls/PaywallManager.swift].
 *
 * SPEC-070-A C.3 / C.5 / C.6 — owns:
 *   - Plan-tap → BillingModule.purchase → purchase events + delegate fires
 *     + post_purchase.on_success dispatch.
 *   - Plan-tap failure → purchase_failed event + delegate fire +
 *     post_purchase.on_failure dispatch.
 *   - Restore-tap → restore lifecycle (started/completed/failed) on
 *     [AppDNAPaywallDelegate].
 *   - handlePostPurchaseSuccess (4 actions) + handlePostPurchaseFailure
 *     (3 actions) parity with iOS.
 */
internal class PaywallManager(
    private val remoteConfigManager: RemoteConfigManager,
    private val eventTracker: EventTracker,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Present a paywall. Must be called with a valid Activity.
     */
    fun present(
        activity: Activity,
        id: String,
        context: PaywallContext? = null,
        listener: AppDNAPaywallDelegate? = null,
    ) {
        val config = remoteConfigManager.getPaywallConfig(id)
        if (config == null) {
            Log.error("Paywall config not found for id: $id")
            listener?.onPaywallPurchaseFailed(
                paywallId = id,
                error = IllegalStateException("Paywall config not found"),
            )
            return
        }

        // Track view event
        eventTracker.track(
            "paywall_view",
            mapOf(
                "paywall_id" to id,
                "placement" to (context?.placement ?: "unknown"),
            ),
        )

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
                eventTracker.track(
                    "paywall_close",
                    mapOf(
                        "paywall_id" to id,
                        "dismiss_reason" to reason.value,
                    ),
                )
                listener?.onPaywallDismissed(paywallId = id)
            },
            onPlanSelected = { plan, metadata ->
                // SPEC-070-A B.5 — onPaywallAction(CTA_TAPPED) for the CTA tap.
                listener?.onPaywallAction(paywallId = id, action = PaywallAction.CTA_TAPPED)
                // SPEC-070-A C.6 — invoke BillingModule + dispatch post-purchase.
                handlePurchase(
                    activity = activity,
                    paywallId = id,
                    plan = plan,
                    config = config,
                    metadata = metadata,
                    listener = listener,
                )
            },
            // AC-037: Wire delegate promo code callback
            onPromoCodeSubmit = if (listener != null) {
                { code, completion ->
                    listener.onPromoCodeSubmit(paywallId = id, code = code, completion = completion)
                }
            } else null,
            // SPEC-070-A C.3 — wire restore lifecycle
            onRestore = {
                handleRestore(paywallId = id, listener = listener)
            },
        )
    }

    // MARK: - Purchase flow (SPEC-070-A C.6)

    /**
     * Mirrors iOS `PaywallManager.handlePurchase`. Calls
     * [AppDNA.billing.purchase] and on success dispatches
     * [handlePostPurchaseSuccess]; on failure dispatches
     * [handlePostPurchaseFailure].
     */
    private fun handlePurchase(
        activity: Activity,
        paywallId: String,
        plan: PaywallPlan,
        config: PaywallConfig,
        metadata: Map<String, Any>,
        listener: AppDNAPaywallDelegate?,
    ) {
        listener?.onPaywallPurchaseStarted(paywallId = paywallId, productId = plan.product_id)
        // AC-038: include toggle states + promo_code in event metadata
        val purchaseProps = mutableMapOf<String, Any>(
            "paywall_id" to paywallId,
            "product_id" to plan.product_id,
        )
        purchaseProps.putAll(metadata)
        eventTracker.track("purchase_started", purchaseProps)

        scope.launch {
            try {
                val tx = AppDNA.billing.purchase(activity, plan.product_id)
                // SPEC-070-A G.6 — purchase_completed includes price + currency + paywall_id
                val completedProps = mutableMapOf<String, Any>(
                    "paywall_id" to paywallId,
                    "product_id" to tx.productId,
                    "transaction_id" to tx.transactionId,
                )
                purchaseProps["price"]?.let { completedProps["price"] = it }
                purchaseProps["currency"]?.let { completedProps["currency"] = it }
                eventTracker.track("purchase_completed", completedProps)

                listener?.onPaywallPurchaseCompleted(
                    paywallId = paywallId,
                    productId = tx.productId,
                    transaction = tx,
                )
                handlePostPurchaseSuccess(
                    config = config.post_purchase?.on_success,
                    paywallId = paywallId,
                    listener = listener,
                )
            } catch (e: PurchaseCancelledException) {
                eventTracker.track(
                    "purchase_cancelled",
                    mapOf(
                        "paywall_id" to paywallId,
                        "product_id" to plan.product_id,
                    ),
                )
                listener?.onPaywallPurchaseFailed(paywallId = paywallId, error = e)
                handlePostPurchaseFailure(
                    config = config.post_purchase?.on_failure,
                    paywallId = paywallId,
                    listener = listener,
                )
            } catch (e: PurchasePendingException) {
                eventTracker.track(
                    "purchase_pending",
                    mapOf(
                        "paywall_id" to paywallId,
                        "product_id" to plan.product_id,
                    ),
                )
                listener?.onPaywallPurchaseFailed(paywallId = paywallId, error = e)
                handlePostPurchaseFailure(
                    config = config.post_purchase?.on_failure,
                    paywallId = paywallId,
                    listener = listener,
                )
            } catch (e: Exception) {
                eventTracker.track(
                    "purchase_failed",
                    mapOf(
                        "paywall_id" to paywallId,
                        "product_id" to plan.product_id,
                        "error" to (e.message ?: "unknown"),
                    ),
                )
                val purchaseError = if (e is PurchaseFailedException) e else Exception(e.message, e)
                listener?.onPaywallPurchaseFailed(paywallId = paywallId, error = purchaseError)
                handlePostPurchaseFailure(
                    config = config.post_purchase?.on_failure,
                    paywallId = paywallId,
                    listener = listener,
                )
            }
        }
    }

    // MARK: - Post-purchase actions (SPEC-070-A C.5)

    /**
     * Mirrors iOS `PaywallManager.handlePostPurchaseSuccess` (4 actions:
     * dismiss / show_message / deep_link / next_step). The delegate's
     * `onPaywallDismissed` fires for every branch except `dismiss` (the
     * Activity's own onDismiss already fires there); deep_link / next_step
     * additionally fire `onPostPurchaseDeepLink` / `onPostPurchaseNextStep`.
     */
    private fun handlePostPurchaseSuccess(
        config: PostPurchaseSuccessConfig?,
        paywallId: String,
        listener: AppDNAPaywallDelegate?,
    ) {
        if (config == null) return // legacy: delegate-only behavior
        val delayMs = (config.delay_ms ?: 2000).toLong()
        scope.launch {
            when (config.action) {
                "dismiss" -> {
                    delay(delayMs)
                    // PaywallActivity onBackPressed already fires onDismiss;
                    // host can call activity.finish() via delegate observation.
                }
                "show_message" -> {
                    // Surface confetti + lottie overlay through the companion
                    // slot watched by the live PaywallActivity composition
                    // (mirrors iOS NotificationCenter `.paywallPurchaseSuccess`).
                    PaywallActivity.postPurchaseOverlay = PostPurchaseOverlayState(
                        message = config.message,
                        confetti = config.confetti ?: false,
                        lottieUrl = config.lottie_url,
                    )
                    delay(delayMs)
                    listener?.onPaywallDismissed(paywallId = paywallId)
                }
                "deep_link" -> {
                    delay(delayMs)
                    listener?.onPaywallDismissed(paywallId = paywallId)
                    config.deep_link_url?.let { url ->
                        listener?.onPostPurchaseDeepLink(paywallId = paywallId, url = url)
                    }
                }
                "next_step" -> {
                    delay(delayMs)
                    listener?.onPaywallDismissed(paywallId = paywallId)
                    listener?.onPostPurchaseNextStep(paywallId = paywallId)
                }
                else -> { /* unknown action — no-op */ }
            }
        }
    }

    /**
     * Mirrors iOS `PaywallManager.handlePostPurchaseFailure`. 3 actions:
     * `show_error`, `retry`, `dismiss`. Surface via the same companion slot
     * the success path uses but with `confetti=false` so the overlay only
     * shows the message text. (Listener gets the actual purchase error via
     * `onPaywallPurchaseFailed` already fired in handlePurchase.)
     */
    private fun handlePostPurchaseFailure(
        config: PostPurchaseFailureConfig?,
        paywallId: String,
        listener: AppDNAPaywallDelegate?,
    ) {
        if (config == null) return
        when (config.action) {
            "dismiss" -> {
                listener?.onPaywallDismissed(paywallId = paywallId)
            }
            "show_error", "retry" -> {
                PaywallActivity.postPurchaseOverlay = PostPurchaseOverlayState(
                    message = config.message ?: "Payment failed. Please try again.",
                    confetti = false,
                    lottieUrl = null,
                )
                // Retry-vs-show_error UX divergence handled host-side via the
                // existing onPaywallPurchaseFailed delegate; the overlay only
                // surfaces the message text.
            }
            else -> { /* unknown action — no-op */ }
        }
    }

    // MARK: - Restore flow (SPEC-070-A C.3)

    /**
     * Mirrors iOS `PaywallManager.handleRestore`. Fires
     * [AppDNAPaywallDelegate.onPaywallRestoreStarted] before kicking off
     * [AppDNA.billing.restorePurchases]; on completion fires
     * `onPaywallRestoreCompleted(productIds)`; on failure fires
     * `onPaywallRestoreFailed(error)`.
     */
    private fun handleRestore(paywallId: String, listener: AppDNAPaywallDelegate?) {
        listener?.onPaywallRestoreStarted(paywallId = paywallId)
        scope.launch {
            try {
                // Reach the underlying NativeBillingManager via the
                // BillingModule's `internal var manager` (same Kotlin
                // module so internal visibility applies). The public
                // `BillingModule.restorePurchases()` is fire-and-forget
                // (Unit); for the delegate completion we want the typed
                // `List<Entitlement>` so we can pass productIds.
                val nativeMgr = AppDNA.billing.manager
                    ?: throw IllegalStateException("Billing bridge not configured")
                val entitlements = nativeMgr.restorePurchases()
                val productIds = entitlements.map { it.productId }
                eventTracker.track(
                    "purchase_restored",
                    mapOf(
                        "paywall_id" to paywallId,
                        "restored_count" to productIds.size,
                    ),
                )
                listener?.onPaywallRestoreCompleted(paywallId = paywallId, productIds = productIds)
                Log.info("Restore completed for paywall $paywallId with ${productIds.size} products")
            } catch (e: Exception) {
                eventTracker.track(
                    "purchase_restore_failed",
                    mapOf(
                        "paywall_id" to paywallId,
                        "error" to (e.message ?: "unknown"),
                    ),
                )
                listener?.onPaywallRestoreFailed(paywallId = paywallId, error = e)
                Log.error("Restore failed: ${e.message}")
            }
        }
    }
}
