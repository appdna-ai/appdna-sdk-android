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
import kotlinx.coroutines.cancel
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
     * SPEC-070-A I.13 — Present the highest-priority paywall whose
     * `placement` matches and whose audience rules evaluate true for the
     * current user.  Mirrors iOS
     * `Paywalls/PaywallManager.swift:28-58 presentByPlacement(...)`.
     *
     * Selection algorithm (must match iOS):
     *   1. filter `getAllPaywalls()` by `placement == placement`,
     *   2. sort descending by `audience_rules.priority` (default 0),
     *   3. pick first whose `audience_rules` evaluates true (no rules == match).
     *
     * Returns silently when no paywall matches — host can fall back to a
     * static experience or omit the placement.
     */
    fun presentByPlacement(
        activity: Activity,
        placement: String,
        context: PaywallContext? = null,
        listener: AppDNAPaywallDelegate? = null,
    ) {
        val all = remoteConfigManager.getAllPaywalls()
        val userTraits = AppDNA.getUserTraits()

        // Filter by placement, then sort by audience_rules.priority desc
        val candidates = all.values
            .filter { it.placement == placement }
            .sortedByDescending { pw ->
                val rules = pw.audience_rules
                @Suppress("UNCHECKED_CAST")
                val priority = (rules as? Map<String, Any?>)?.get("priority") as? Number
                priority?.toInt() ?: 0
            }

        // First candidate that matches audience rules wins. Iterate the
        // raw map/list payload and short-circuit when rules evaluate false.
        val match = candidates.firstOrNull { pw ->
            val rules = pw.audience_rules ?: return@firstOrNull true
            try {
                @Suppress("UNCHECKED_CAST")
                val ruleSet = (rules as? Map<String, Any?>)?.let {
                    ai.appdna.sdk.core.AudienceRuleSet.fromMap(it)
                }
                if (ruleSet == null) {
                    true
                } else {
                    ai.appdna.sdk.core.AudienceRuleEvaluator.evaluate(ruleSet, userTraits)
                }
            } catch (_: Throwable) {
                true
            }
        }

        if (match == null) {
            Log.warning("No paywall found for placement: $placement")
            return
        }
        present(activity = activity, id = match.id, context = context, listener = listener)
    }

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
                // SPEC-070-A finalization B5#P1 — fan out the FULL purchaseProps
                // metadata into purchase_completed, mirroring iOS PaywallManager.swift:208
                // which copies the entire dict. Previously only price+currency
                // were copied through, dropping provider and any plan-card-injected
                // metadata. Now `provider`, `price`, `currency`, plus any future
                // metadata key reaches the funnel.
                purchaseProps.forEach { (k, v) ->
                    if (k != "paywall_id" && k != "product_id") {
                        completedProps[k] = v
                    }
                }
                // Ensure provider is set even when caller didn't inject it.
                // BillingModule.purchase always routes through native Play
                // Billing on Android (RC/Adapty bridges fire purchase_completed
                // themselves with their own provider tag), so the native path
                // can default to "google_play".
                if (!completedProps.containsKey("provider")) {
                    completedProps["provider"] = "google_play"
                }
                // SPEC-070-A finalization B5 P1 — pull real price/currency
                // from the PriceResolver cache when caller didn't inject
                // them. NativeBillingManager populates the cache when the
                // host queries products before purchase; this is the same
                // resolver path the native handler uses. Mirrors iOS
                // PaywallManager which reads `result.priceFormatStyle` /
                // `product.price` directly.
                if (!completedProps.containsKey("price") || !completedProps.containsKey("currency")) {
                    val cached = try {
                        AppDNA.billing.manager?.priceResolver?.cachedPriceInfo(plan.product_id)
                    } catch (_: Throwable) { null }
                    if (cached != null) {
                        if (!completedProps.containsKey("price")) {
                            completedProps["price"] = cached.priceMicros / 1_000_000.0
                        }
                        if (!completedProps.containsKey("currency")) {
                            completedProps["currency"] = cached.currencyCode
                        }
                    }
                }
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
                    // SPEC-070-A finalization: matches iOS canonical
                    // `purchase_canceled` (single 'l'). NativeBillingManager.swift:166.
                    "purchase_canceled",
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
                    // SPEC-070-A finalization parity audit R6 — propagate
                    // retry_text and action so the failure overlay can render
                    // a labelled retry CTA. Mirrors iOS PaywallManager.swift:
                    // 309-313 + PaywallRenderer.swift:351-353. Without this,
                    // console-configured retry button text + retry-vs-dismiss
                    // action were silently dropped.
                    retryText = config.retry_text,
                    action = config.action,
                    allowDismiss = config.allow_dismiss ?: true,
                )
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
                // SPEC-401 R3 audit Lens A/B — clear the public
                // `skipNextAutoDismissOnRestore` flag on EVERY restore
                // terminal event (success-with-products, empty-success,
                // and the failure path below) so the one-shot flag can't
                // leak from one paywall presentation into the next.
                // Captured outside the early-return below.
                val hostRequestedSkip = AppDNA.paywall.skipNextAutoDismissOnRestore
                AppDNA.paywall.skipNextAutoDismissOnRestore = false
                // SPEC-401 Fix 1C — auto-dismiss the live PaywallActivity
                // when restore actually found entitlements. The delegate
                // forward above runs FIRST so a host that wants to handle
                // dismiss itself can flip `skipNextAutoDismissOnRestore`
                // synchronously inside its delegate body before we reach
                // this line. Empty restored array = "restore call worked
                // but user has no entitlements to restore" — leave paywall
                // up so user can either close manually or attempt a fresh
                // purchase.
                if (productIds.isNotEmpty() && !hostRequestedSkip) {
                    PaywallActivity.activeInstance(paywallId)?.dismissAfterRestore()
                }
            } catch (e: Exception) {
                eventTracker.track(
                    "purchase_restore_failed",
                    mapOf(
                        "paywall_id" to paywallId,
                        "error" to (e.message ?: "unknown"),
                    ),
                )
                listener?.onPaywallRestoreFailed(paywallId = paywallId, error = e)
                // SPEC-401 R3 audit — symmetric clear on failure too so
                // a host who set the flag for a restore that errored
                // doesn't carry the flag into the next paywall's restore.
                AppDNA.paywall.skipNextAutoDismissOnRestore = false
                Log.error("Restore failed: ${e.message}")
            }
        }
    }

    /**
     * SPEC-070-A audit Round 2 finding 6 — cancel the private scope so
     * pending paywall metadata fetches and post-purchase dispatches don't
     * outlive [AppDNA.shutdown]. Mirrors the equivalent tear-down already
     * present on EventQueue, BillingModule, etc.
     */
    internal fun shutdown() {
        scope.cancel()
    }
}
