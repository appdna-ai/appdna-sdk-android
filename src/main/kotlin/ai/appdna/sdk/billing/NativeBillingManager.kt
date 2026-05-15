package ai.appdna.sdk.billing

import android.app.Activity
import android.content.Context
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.storage.LocalStorage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.billingclient.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellableContinuation
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Options applied to a [NativeBillingManager.purchase] call.
 *
 * SPEC-070-A A.28 — mirrors iOS `PurchaseOptions` (AppDNA+Modules.swift:454).
 * The `appAccountToken` is forwarded to Google Play as the obfuscated account
 * ID (`BillingFlowParams.setObfuscatedAccountId`) so Play can correlate the
 * receipt with the app's user identity for fraud / chargeback investigations.
 */
data class PurchaseOptions(
    /** Optional offer token to select a specific Play subscription offer / trial. */
    val offerToken: String? = null,
    /**
     * Host-supplied UUID that uniquely identifies the buying user. Forwarded
     * to Play as `obfuscatedAccountId`. Use the same value you pass on iOS
     * (StoreKit `Product.PurchaseOption.appAccountToken`) so receipts line
     * up across stores.
     */
    val appAccountToken: UUID? = null,
)

/**
 * Entitlement model representing an active subscription or purchase.
 *
 * SPEC-070-A A.5: `@Keep` so R8/minify never drops this DTO. Hosts read
 * these fields reflectively when bridging to RN/Flutter channels and
 * via direct getters from the RevenueCat shim.
 */
@androidx.annotation.Keep
data class Entitlement(
    val productId: String,
    val store: String,
    val status: String,
    val expiresAt: String?,
    val isTrial: Boolean,
    val offerType: String?
)

/**
 * Result of a purchase operation.
 */
sealed class PurchaseResult {
    /** Purchase completed and verified. */
    data class Purchased(val entitlement: Entitlement) : PurchaseResult()
    /** User cancelled the purchase flow. */
    object Cancelled : PurchaseResult()
    /** Purchase is pending (e.g., awaiting parental approval or slow payment method). */
    object Pending : PurchaseResult()
    /** Unknown or unhandled result. */
    object Unknown : PurchaseResult()
    /** Purchase verification failed. */
    data class Failed(val error: String) : PurchaseResult()
}

/**
 * Simplified product information for display in paywalls and UI.
 */
data class ProductInfo(
    val id: String,
    val name: String,
    val description: String,
    val formattedPrice: String,
    val priceMicros: Long,
    val currencyCode: String,
    val offerToken: String?
)

/**
 * Orchestrates native Google Play Billing operations.
 *
 * This is the primary class that app developers interact with for:
 * - Initiating purchases (subscriptions and in-app products)
 * - Restoring purchases
 * - Querying product details and prices
 * - Managing entitlement state
 *
 * Internally delegates to:
 * - [BillingConnectionManager] for connection lifecycle
 * - [ReceiptVerifier] for server-side receipt verification
 * - [EntitlementCache] for local entitlement persistence
 * - [PriceResolver] for product price resolution
 *
 * Usage:
 * ```kotlin
 * val billing = NativeBillingManager(context, receiptVerifier, entitlementCache)
 * billing.initialize()
 *
 * // Purchase
 * val result = billing.purchase(activity, "premium_monthly")
 *
 * // Check entitlement
 * val isPremium = billing.entitlementCache.hasActiveSubscription
 * ```
 */
class NativeBillingManager internal constructor(
    private val context: Context,
    internal val receiptVerifier: ReceiptVerifier,
    internal val entitlementCache: EntitlementCache,
    internal val storage: LocalStorage? = null,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Cross-account-leak filter — keeps only the purchases that belong to
     * the currently-identified app user (per their `obfuscatedAccountId`).
     * See [EntitlementOwnerFilter] for the full decision matrix; this helper
     * is the BillingClient-specific glue that decodes
     * `Purchase.getAccountIdentifiers().getObfuscatedAccountId()` into a UUID
     * and routes through the shared filter.
     *
     * Called at every `queryPurchasesAsync` consumption site
     * (`reconcileSubscriptionState`, `refreshEntitlementCache`,
     * `restorePurchases`) so a previous user's purchases left on the device
     * never reach the entitlement cache for the currently-identified user.
     */
    private fun filterOwnedPurchases(
        purchases: List<Purchase>,
        expectedToken: UUID?,
        source: String,
    ): List<Purchase> {
        val kept = mutableListOf<Purchase>()
        for (purchase in purchases) {
            val rawToken = purchase.accountIdentifiers?.obfuscatedAccountId
            val purchaseToken = EntitlementOwnerFilter.parseObfuscatedAccountId(rawToken)
            when (EntitlementOwnerFilter.decide(purchaseToken, expectedToken)) {
                EntitlementOwnershipDecision.Grant,
                EntitlementOwnershipDecision.GrantAnonymousPolicy -> kept.add(purchase)
                EntitlementOwnershipDecision.GrantUntaggedMigration -> {
                    Log.info("$source: granting untagged historical purchase ${purchase.orderId ?: purchase.purchaseToken} to current user (migration-tolerant policy — server should claim ownership).")
                    kept.add(purchase)
                }
                EntitlementOwnershipDecision.DenyOtherUser -> {
                    Log.warning("$source: skipped purchase ${purchase.orderId ?: purchase.purchaseToken} — obfuscatedAccountId does not match the current user.")
                }
            }
        }
        return kept
    }

    /** Active purchase continuation — only one purchase flow at a time. */
    private var purchaseContinuation: CancellableContinuation<PurchaseResult>? = null

    /**
     * SPEC-402 C3 — product currently mid-purchase. Set by [purchase] before
     * launching the Play billing flow and cleared on terminal callbacks. Play's
     * `PurchasesUpdatedListener` does NOT surface the originally-requested
     * product on non-OK callbacks (USER_CANCELED, SERVICE_DISCONNECTED, …) so
     * we carry it through ourselves to keep `product_id` populated on every
     * `purchase_canceled` / `purchase_failed` analytic — matching iOS.
     */
    private var pendingProductId: String? = null

    /** Attribution context for the current purchase. */
    var currentPaywallId: String? = null
    var currentExperimentId: String? = null

    // SPEC-070-A A.20 — process-lifecycle observer that polls
    // `BillingClient.queryPurchasesAsync` on every foreground entry, diffs
    // against the snapshot persisted in [LocalStorage], and emits
    // `subscription_renewed` / `subscription_canceled` /
    // `subscription_renewal_failed` events (SPEC-402 C2: canonical single-l
    // spelling). Mirrors iOS `Billing.SubscriptionStatusObserver` (which is
    // StoreKit-driven).
    private val foregroundObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) {
            scope.launch { reconcileSubscriptionState() }
        }
    }
    private var foregroundObserverRegistered = false

    /**
     * Listener that handles purchase updates from Google Play.
     */
    private val purchaseUpdateListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    scope.launch {
                        handleSuccessfulPurchase(purchase)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.debug("Purchase cancelled by user")
                // SPEC-402 C4 — include `product_id` so the analytic matches
                // iOS canonical shape (Play's PurchasesUpdatedListener does
                // not surface the originally-requested product on cancel;
                // pendingProductId set by [purchase] before launch carries it).
                AppDNA.track("purchase_canceled", buildMap {
                    put("paywall_id", currentPaywallId ?: "")
                    pendingProductId?.let { put("product_id", it) }
                })
                resumePurchase(PurchaseResult.Cancelled)
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.info("Item already owned — triggering restore")
                scope.launch {
                    try {
                        val restored = restorePurchases()
                        val entitlement = restored.firstOrNull()
                        if (entitlement != null) {
                            resumePurchase(PurchaseResult.Purchased(entitlement))
                        } else {
                            resumePurchase(PurchaseResult.Unknown)
                        }
                    } catch (e: Exception) {
                        resumePurchase(PurchaseResult.Failed(e.message ?: "Restore failed"))
                    }
                }
            }
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> {
                Log.warning("Item not owned")
                resumePurchase(PurchaseResult.Failed("Item not owned"))
                trackPurchaseFailed("item_not_owned", billingResult.debugMessage, pendingProductId)
            }
            // SPEC-070-A finalization B-4 — explicit branches for every
            // BillingResponseCode that previously fell into the `else` ->
            // PurchaseResult.Unknown swallow path. Each code now surfaces a
            // typed `PurchaseResult.Failed("$reason")` so the host UI can
            // show actionable copy ("Network error, try again" vs "Product
            // not configured" vs "Service unavailable"), and emits a
            // `purchase_failed` analytic with the same reason for funnel
            // diagnostics. Mirrors iOS StoreKit 2's typed error throws
            // (Product.PurchaseError + StoreKitError cases) — without this
            // branch, a customer could not tell whether their purchase
            // failed because the network died, the productId was bogus,
            // or Google Play was unavailable.
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> {
                Log.warning("Purchase: service disconnected")
                resumePurchase(PurchaseResult.Failed("service_disconnected"))
                // SPEC-402 C3 — pendingProductId may be null here (this code
                // can fire during initial connection setup before any purchase
                // is in-flight); the helper omits the key when null.
                trackPurchaseFailed("service_disconnected", billingResult.debugMessage, pendingProductId)
            }
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> {
                Log.warning("Purchase: service unavailable (network)")
                resumePurchase(PurchaseResult.Failed("service_unavailable"))
                trackPurchaseFailed("service_unavailable", billingResult.debugMessage, pendingProductId)
            }
            BillingClient.BillingResponseCode.SERVICE_TIMEOUT -> {
                Log.warning("Purchase: service timeout")
                resumePurchase(PurchaseResult.Failed("service_timeout"))
                trackPurchaseFailed("service_timeout", billingResult.debugMessage, pendingProductId)
            }
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                // Device has no Play Store / not signed in / API too old.
                Log.warning("Purchase: billing unavailable on this device")
                resumePurchase(PurchaseResult.Failed("billing_unavailable"))
                trackPurchaseFailed("billing_unavailable", billingResult.debugMessage, pendingProductId)
            }
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> {
                // Product not configured for this app / region.
                Log.warning("Purchase: item unavailable")
                resumePurchase(PurchaseResult.Failed("item_unavailable"))
                trackPurchaseFailed("item_unavailable", billingResult.debugMessage, pendingProductId)
            }
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> {
                // Misconfigured request — productId/offer mismatch, etc.
                Log.warning("Purchase: developer error: ${billingResult.debugMessage}")
                resumePurchase(PurchaseResult.Failed("developer_error"))
                trackPurchaseFailed("developer_error", billingResult.debugMessage, pendingProductId)
            }
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> {
                Log.warning("Purchase: feature not supported")
                resumePurchase(PurchaseResult.Failed("feature_not_supported"))
                trackPurchaseFailed("feature_not_supported", billingResult.debugMessage, pendingProductId)
            }
            BillingClient.BillingResponseCode.NETWORK_ERROR -> {
                Log.warning("Purchase: network error")
                resumePurchase(PurchaseResult.Failed("network_error"))
                trackPurchaseFailed("network_error", billingResult.debugMessage, pendingProductId)
            }
            BillingClient.BillingResponseCode.ERROR -> {
                Log.warning("Purchase: generic billing error: ${billingResult.debugMessage}")
                resumePurchase(PurchaseResult.Failed("billing_error"))
                trackPurchaseFailed("billing_error", billingResult.debugMessage, pendingProductId)
            }
            else -> {
                // Unknown future code — log + report opaque failure rather
                // than silent Unknown.
                Log.warning("Purchase update: unknown code=${billingResult.responseCode}, msg=${billingResult.debugMessage}")
                resumePurchase(PurchaseResult.Failed("unknown_code_${billingResult.responseCode}"))
                trackPurchaseFailed("unknown_code_${billingResult.responseCode}", billingResult.debugMessage, pendingProductId)
            }
        }
    }

    /**
     * SPEC-070-A finalization R2 P1 (Lens C) — shared purchase-event property
     * builder used by RevenueCatBridge + AdaptyBridge so direct-purchase paths
     * emit the same shape as the native Play Billing path. Populates:
     * `product_id`, `provider`, `paywall_id`, `experiment_id`, and (when
     * cached) `price` + `currency` + `is_trial`. Mirrors the iOS pattern
     * where StoreKit2Bridge / RevenueCatBridge / AdaptyBridge call into
     * `NativeBillingManager.trackProperties(productId)`.
     *
     * Public so bridges in `integrations/` can reach it via
     * `AppDNA.billing.manager?.purchaseEventProps(...)`.
     */
    fun purchaseEventProps(productId: String, provider: String): Map<String, Any> {
        val props = mutableMapOf<String, Any>(
            "product_id" to productId,
            "provider" to provider,
            "paywall_id" to (currentPaywallId ?: ""),
            "experiment_id" to (currentExperimentId ?: ""),
        )
        priceResolver.cachedPriceInfo(productId)?.let { info ->
            props["price"] = info.priceMicros / 1_000_000.0
            props["currency"] = info.currencyCode
        }
        return props
    }

    /**
     * SPEC-070-A finalization B-4 — common helper used by every non-OK
     * response code branch above. Emits a `purchase_failed` analytic with
     * a typed `reason` field so funnel dashboards can split silent-fail
     * causes (network vs misconfigured product vs disconnected service).
     *
     * SPEC-402 C3 — accepts the originally-requested productId so the event
     * payload matches iOS (`Billing/NativeBillingManager.swift` always sets
     * `product_id` on purchase_failed). Most non-OK Play callbacks have no
     * purchase object in scope; only [pendingProductId] (set in [purchase]
     * before launching the billing flow) carries it through. Sites with no
     * pending purchase (e.g. SERVICE_DISCONNECTED during initial setup)
     * pass null and the field is omitted rather than fabricated.
     */
    private fun trackPurchaseFailed(
        reason: String,
        debugMessage: String?,
        productId: String? = null,
    ) {
        AppDNA.track("purchase_failed", buildMap {
            put("paywall_id", currentPaywallId ?: "")
            put("reason", reason)
            if (!debugMessage.isNullOrBlank()) put("debug_message", debugMessage)
            if (!productId.isNullOrBlank()) put("product_id", productId)
        })
    }

    /** Connection manager handling BillingClient lifecycle. */
    internal val connectionManager = BillingConnectionManager(
        context = context,
        purchasesUpdatedListener = purchaseUpdateListener
    )

    /** Price resolver for querying product details. */
    internal val priceResolver = PriceResolver(connectionManager)

    // -- Initialization --

    /**
     * Initialize the billing system. Must be called before any billing operations.
     * Typically called after AppDNA.configure() and onReady.
     */
    fun initialize() {
        Log.info("NativeBillingManager: initializing")
        connectionManager.initialize()
        registerForegroundObserver()
    }

    /**
     * SPEC-070-A A.20 — register a `ProcessLifecycleOwner` observer so that
     * every time the app comes back to the foreground we re-poll Play for
     * the current subscription state and diff against the last-known
     * snapshot. This is required because Android's
     * [PurchasesUpdatedListener] only fires while the user is *actively*
     * inside the billing flow — it does NOT fire when Play renews or
     * cancels the subscription server-side while the app is in the
     * background.
     */
    private fun registerForegroundObserver() {
        if (foregroundObserverRegistered) return
        // ProcessLifecycleOwner.get() must be called on the main thread.
        scope.launch {
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
                foregroundObserverRegistered = true
            } catch (e: Exception) {
                Log.warning("NativeBillingManager: failed to attach foreground observer: ${e.message}")
            }
        }
    }

    private fun unregisterForegroundObserver() {
        if (!foregroundObserverRegistered) return
        try {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
        } catch (_: Exception) { /* best-effort */ }
        foregroundObserverRegistered = false
    }

    /**
     * SPEC-070-A A.20 — diff the current Play subscription state against the
     * last persisted snapshot and emit lifecycle events.
     *
     * Logic mirrors iOS `SubscriptionStatusObserver`:
     *  - Product **previously active**, now MISSING from Play → if its
     *    persisted `expiresAt` was in the future we emit
     *    `subscription_renewal_failed` (likely billing retry / hold);
     *    otherwise it's a normal cancellation → `subscription_canceled`
     *    (SPEC-402 C2 — single-l US spelling).
     *  - Product **previously active** with `expiresAt` X, still active but
     *    `expiresAt` ≠ X → `subscription_renewed` (Play extended the period).
     *  - Product **new** since last snapshot → handled by the synchronous
     *    `PurchasesUpdatedListener` path; we do NOT double-emit here.
     *
     * Visible to tests as `internal`.
     */
    internal suspend fun reconcileSubscriptionState() {
        val client = connectionManager.awaitConnectedClient() ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        val result = try {
            client.queryPurchasesAsync(params)
        } catch (e: Exception) {
            Log.warning("reconcileSubscriptionState: queryPurchasesAsync threw: ${e.message}")
            return
        }
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.debug("reconcileSubscriptionState: query returned ${result.billingResult.responseCode}")
            return
        }

        // Build a snapshot of currently-active subs keyed by productId.
        // Cross-account-leak defence — only reconcile purchases that belong
        // to the currently-identified app user.
        val ownedPurchases = filterOwnedPurchases(
            result.purchasesList,
            AppAccountTokenResolver.tokenForCurrentUser(),
            "reconcileSubscriptionState",
        )
        val current = mutableMapOf<String, SubSnapshot>()
        for (purchase in ownedPurchases) {
            val productId = purchase.products.firstOrNull() ?: continue
            current[productId] = SubSnapshot(
                productId = productId,
                purchaseTime = purchase.purchaseTime,
                isAcknowledged = purchase.isAcknowledged,
                isAutoRenewing = purchase.isAutoRenewing,
            )

            // SPEC-070-A audit attempt 3 F1: verify + acknowledge any
            // background-resolved purchases the synchronous
            // PurchasesUpdatedListener never saw. Google Play auto-refunds
            // unacknowledged purchases after 3 days, so on every foreground
            // reconcile we must look for `PURCHASED && !isAcknowledged` and
            // run the full verify → acknowledge → entitlementCache.update
            // pipeline (mirroring iOS Billing/NativeBillingManager.swift:233-
            // 253 `Transaction.updates`-driven verify+finish flow).
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                !purchase.isAcknowledged
            ) {
                try {
                    val entitlement = receiptVerifier.verify(
                        purchaseToken = purchase.purchaseToken,
                        productId = productId,
                        platform = "android",
                        paywallId = null,
                        experimentId = null,
                    )
                    val ackParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    client.acknowledgePurchase(ackParams) { ackResult ->
                        if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            Log.warning(
                                "reconcileSubscriptionState: ack failed " +
                                    "for $productId: ${ackResult.debugMessage}",
                            )
                        }
                    }
                    entitlementCache.update(entitlement)
                } catch (e: Throwable) {
                    Log.warning(
                        "reconcileSubscriptionState: verify+ack for " +
                            "background-resolved $productId threw: ${e.message}",
                    )
                }
            }
        }

        val previous = loadLastSnapshot()
        diffAndEmit(previous, current)
        saveSnapshot(current)
    }

    /**
     * Compare previous and current snapshots. Public for testing — does no I/O.
     */
    internal fun diffAndEmit(
        previous: Map<String, SubSnapshot>,
        current: Map<String, SubSnapshot>,
    ) {
        // Vanished products → cancelled or renewal-failed.
        for ((productId, prev) in previous) {
            val now = current[productId]
            if (now == null) {
                // Heuristic for renewal failure: previously auto-renewing but
                // now absent from Play queryPurchases. Without StoreKit-style
                // notifications we can't be 100% sure; iOS calls this
                // "subscription_renewal_failed" and emits the same event.
                // SPEC-402 C2 — `subscription_canceled` (single-l US spelling)
                // matches iOS server-side notification convention + dbt bronze
                // CASE-rewrite in `stg_purchase_events.sql` (Part B). Historic
                // Android `subscription_cancelled` rows are backfilled via a
                // post-merge `dbt run --select stg_purchase_events --full-refresh`.
                val event = if (prev.isAutoRenewing) "subscription_renewal_failed" else "subscription_canceled"
                AppDNA.track(event, mapOf("product_id" to productId))
            }
        }

        // Renewed products → purchaseTime advanced.
        for ((productId, now) in current) {
            val prev = previous[productId] ?: continue
            if (now.purchaseTime > prev.purchaseTime) {
                AppDNA.track("subscription_renewed", mapOf(
                    "product_id" to productId,
                    "purchase_time" to now.purchaseTime,
                ))
            }
        }
    }

    private fun loadLastSnapshot(): Map<String, SubSnapshot> {
        val raw = storage?.getString(KEY_LAST_SUB_SNAPSHOT) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            val out = mutableMapOf<String, SubSnapshot>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pid = obj.optString("productId", "")
                if (pid.isEmpty()) continue
                out[pid] = SubSnapshot(
                    productId = pid,
                    purchaseTime = obj.optLong("purchaseTime", 0L),
                    isAcknowledged = obj.optBoolean("isAcknowledged", false),
                    isAutoRenewing = obj.optBoolean("isAutoRenewing", false),
                )
            }
            out
        } catch (e: Exception) {
            Log.debug("loadLastSnapshot: parse failure ${e.message}")
            emptyMap()
        }
    }

    private fun saveSnapshot(snapshot: Map<String, SubSnapshot>) {
        val storage = storage ?: return
        val arr = JSONArray()
        for ((_, s) in snapshot) {
            arr.put(JSONObject().apply {
                put("productId", s.productId)
                put("purchaseTime", s.purchaseTime)
                put("isAcknowledged", s.isAcknowledged)
                put("isAutoRenewing", s.isAutoRenewing)
            })
        }
        storage.setString(KEY_LAST_SUB_SNAPSHOT, arr.toString())
    }

    /** Lightweight snapshot of a single Play subscription. */
    internal data class SubSnapshot(
        val productId: String,
        val purchaseTime: Long,
        val isAcknowledged: Boolean,
        val isAutoRenewing: Boolean,
    )

    private companion object {
        // SPEC-070-A A.20 — snapshot key persisted in LocalStorage so the
        // foreground reconcile-and-diff survives process death.
        const val KEY_LAST_SUB_SNAPSHOT = "billing_last_sub_snapshot_v1"
    }

    // -- Purchase Flow --

    /**
     * Initiate a subscription or one-time-product purchase.
     *
     * SPEC-070-A A.27 — Queries both `SUBS` and `INAPP` product types in
     * parallel, picks whichever Play returns details for, and launches the
     * matching billing flow. Subscriptions take precedence to mirror iOS
     * (`Billing/NativeBillingManager.swift` queries StoreKit which returns
     * SUBS first).
     *
     * SPEC-070-A A.28 — When [options.appAccountToken] is supplied, plumbs
     * it through `BillingFlowParams.setObfuscatedAccountId` so the receipt
     * Play returns is correlatable with the host's user identity (and is
     * reflected in Google Play Console as the obfuscated account ID for
     * fraud / chargeback investigations).
     *
     * @param activity The Activity to launch the billing flow from.
     * @param productId The Google Play product ID to purchase.
     * @param options Optional purchase parameters (offer token, account token).
     * @return The result of the purchase operation.
     */
    suspend fun purchase(
        activity: Activity,
        productId: String,
        options: PurchaseOptions? = null,
    ): PurchaseResult {
        Log.info("Starting purchase for product: $productId")
        // SPEC-402 C3 — pin the requested productId so non-OK Play callbacks
        // (USER_CANCELED, SERVICE_DISCONNECTED, …) can attribute their
        // analytics back to it. Cleared in [resumePurchase] on any terminal.
        pendingProductId = productId
        // SPEC-070-A finalization parity — match iOS NativeBillingManager.swift:73-77
        // (product_id + paywall_id + experiment_id).
        AppDNA.track("purchase_started", mapOf(
            "product_id" to productId,
            "paywall_id" to (currentPaywallId ?: ""),
            "experiment_id" to (currentExperimentId ?: ""),
        ))

        val client = connectionManager.awaitConnectedClient()
            ?: return PurchaseResult.Failed("BillingClient not connected")

        // SPEC-070-A A.27 — query SUBS and INAPP in parallel; pick whichever
        // Play returns. Some products are configured as one-time entitlements
        // (e.g. lifetime unlock) and ProductType is required to match on Play.
        val resolved = coroutineScope {
            val subsDeferred = async { queryProductDetails(client, productId, BillingClient.ProductType.SUBS) }
            val inappDeferred = async { queryProductDetails(client, productId, BillingClient.ProductType.INAPP) }
            val subs = subsDeferred.await()
            val inapp = inappDeferred.await()
            // Prefer SUBS when both succeed (matches iOS ordering).
            subs ?: inapp
        } ?: return PurchaseResult.Failed("Product not found: $productId")

        val productDetails = resolved.productDetails
        val productType = resolved.productType

        // Resolve offer (only meaningful for SUBS).
        val selectedOffer = if (productType == BillingClient.ProductType.SUBS) {
            val token = options?.offerToken
            if (token != null) {
                productDetails.subscriptionOfferDetails?.find { it.offerToken == token }
            } else {
                productDetails.subscriptionOfferDetails?.firstOrNull()
            }
        } else null

        // Build billing flow params
        val flowParamsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .apply { selectedOffer?.let { setOfferToken(it.offerToken) } }
                    .build()
            ))

        // SPEC-070-A A.28 — forward host-supplied UUID as obfuscated account ID.
        // Cross-account-leak defence: when the host doesn't pass an explicit
        // token, derive one from the currently-identified user. Without this
        // fallback every purchase made through the convenience API
        // (`AppDNA.billing.purchase(productId)` with no options) would be
        // untagged, and a later user-switch on the device would see it in
        // `queryPurchasesAsync` — the very leak this defence exists to close.
        val resolvedToken = options?.appAccountToken ?: AppAccountTokenResolver.tokenForCurrentUser()
        if (resolvedToken != null) {
            flowParamsBuilder.setObfuscatedAccountId(resolvedToken.toString())
        } else {
            Log.warning("NativeBillingManager.purchase: no appAccountToken — host should call AppDNA.identify(userId:) BEFORE purchase to avoid cross-account entitlement leaks.")
        }

        val flowParams = flowParamsBuilder.build()

        // Launch and await result
        return suspendCancellableCoroutine { continuation ->
            purchaseContinuation = continuation
            continuation.invokeOnCancellation {
                purchaseContinuation = null
            }
            val launchResult = client.launchBillingFlow(activity, flowParams)
            if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
                purchaseContinuation = null
                continuation.resume(
                    PurchaseResult.Failed("Failed to launch billing flow: ${launchResult.debugMessage}")
                ) {}
            }
        }
    }

    /**
     * Internal helper — query a single product against a specific product type.
     * Returns null if the product isn't found for that type. Used by
     * [purchase] to discover whether a productId is SUBS or INAPP.
     */
    private suspend fun queryProductDetails(
        client: BillingClient,
        productId: String,
        @BillingClient.ProductType productType: String,
    ): ResolvedProduct? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(productType)
                    .build()
            ))
            .build()

        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.debug("queryProductDetails($productType) returned ${result.billingResult.responseCode} for $productId")
            return null
        }
        val details = result.productDetailsList?.firstOrNull() ?: return null
        return ResolvedProduct(details, productType)
    }

    private data class ResolvedProduct(
        val productDetails: ProductDetails,
        @BillingClient.ProductType val productType: String,
    )

    // -- Restore --

    /**
     * Restore all active purchases from Google Play.
     * Sends purchase tokens to the server for verification and updates the entitlement cache.
     *
     * SPEC-070-A G.12 — queries BOTH `ProductType.SUBS` AND `ProductType.INAPP`
     * and merges results so one-time products (lifetime unlocks, consumable
     * unlocks) are restorable, not just subscriptions. Mirrors iOS
     * `Billing/NativeBillingManager.swift:184-203` which iterates
     * `Transaction.currentEntitlements` covering both product types.
     *
     * SPEC-070-A G.7 — event names align with iOS:
     *   - per-product `purchase_restored` (matches iOS line 198)
     *   - aggregate `purchase_restored` event with `restored_count` only when
     *     more than 0 (matches iOS PaywallManager.swift:340 + restore_failed
     *     PaywallManager.swift:352 → `purchase_restore_failed`)
     *   - removed Android-only `restore_started` / `restore_completed` /
     *     `restore_failed` strings that iOS never emitted.
     *
     * SPEC-070-A B.2 — fires AppDNABillingDelegate.onRestoreCompleted with
     * the restored product IDs.
     *
     * @return List of restored Entitlements.
     */
    /**
     * SPEC-401 Fix 1D — silent entitlement-cache refresh.
     *
     * Same query path as [restorePurchases] (SUBS + INAPP via Play Billing
     * `queryPurchasesAsync`) but skips the user-visible parts: no
     * `purchase_restored` analytics events, no `fireOnRestoreCompleted`
     * delegate callbacks. Designed for [AppDNA.identify] and host-driven
     * post-auth refresh hooks where firing user-visible restore events
     * would be wrong.
     *
     * Errors are swallowed and logged at warning level — callers must be
     * able to chain without try/catch (identify is fire-and-forget). On
     * failure the cache stays at whatever it was before; no state mutation.
     */
    suspend fun refreshEntitlementCache() {
        try {
            val client = connectionManager.awaitConnectedClient() ?: return
            val (subsResult, inappResult) = coroutineScope {
                val subsDeferred = async {
                    val params = QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                    client.queryPurchasesAsync(params)
                }
                val inappDeferred = async {
                    val params = QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                    client.queryPurchasesAsync(params)
                }
                subsDeferred.await() to inappDeferred.await()
            }
            val mergedPurchases = (subsResult.purchasesList + inappResult.purchasesList)
                .distinctBy { it.purchaseToken }
                .let {
                    // Cross-account-leak defence — see `EntitlementOwnerFilter`.
                    filterOwnedPurchases(
                        it,
                        AppAccountTokenResolver.tokenForCurrentUser(),
                        "refreshEntitlementCache",
                    )
                }
            // Empty entitlements is a valid state (user has no past
            // purchases) — short-circuit before hitting the verifier so
            // we don't make a network call for nothing.
            if (mergedPurchases.isEmpty()) return
            val transactions = mergedPurchases.map { purchase ->
                mapOf(
                    "token" to purchase.purchaseToken,
                    "productId" to (purchase.products.firstOrNull() ?: "unknown")
                )
            }
            val entitlements = receiptVerifier.restore(transactions)
            entitlementCache.replaceAll(entitlements)
        } catch (e: Exception) {
            Log.warning("refreshEntitlementCache: silent refresh failed — ${e.message}")
        }
    }

    suspend fun restorePurchases(): List<Entitlement> {
        Log.info("Restoring purchases (SUBS + INAPP)")

        val client = connectionManager.awaitConnectedClient()
            ?: return emptyList()

        // SPEC-070-A G.12 — query SUBS and INAPP in parallel.
        val (subsResult, inappResult) = coroutineScope {
            val subsDeferred = async {
                val params = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
                client.queryPurchasesAsync(params)
            }
            val inappDeferred = async {
                val params = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
                client.queryPurchasesAsync(params)
            }
            subsDeferred.await() to inappDeferred.await()
        }

        if (subsResult.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.warning("Failed to query SUBS purchases: ${subsResult.billingResult.debugMessage}")
        }
        if (inappResult.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.warning("Failed to query INAPP purchases: ${inappResult.billingResult.debugMessage}")
        }

        val mergedPurchases = (subsResult.purchasesList + inappResult.purchasesList)
            .distinctBy { it.purchaseToken }
            .let {
                // Cross-account-leak defence — see `EntitlementOwnerFilter`.
                // Untagged historical purchases are surfaced under the
                // migration-tolerant policy; server-side `receiptVerifier`
                // is the primary authority and claims ownership for the
                // currently-identified user.
                filterOwnedPurchases(
                    it,
                    AppAccountTokenResolver.tokenForCurrentUser(),
                    "restorePurchases",
                )
            }

        val transactions = mergedPurchases.map { purchase ->
            mapOf(
                "token" to purchase.purchaseToken,
                "productId" to (purchase.products.firstOrNull() ?: "unknown")
            )
        }

        if (transactions.isEmpty()) {
            Log.info("No purchases to restore")
            entitlementCache.replaceAll(emptyList())
            // SPEC-070-A B.2 — fire onRestoreCompleted even with empty list,
            // mirroring iOS StoreKit2Bridge.swift:86 / RevenueCatBridge.swift:75.
            fireOnRestoreCompleted(emptyList())
            return emptyList()
        }

        return try {
            val entitlements = receiptVerifier.restore(transactions)
            entitlementCache.replaceAll(entitlements)

            // SPEC-070-A G.7 — per-product `purchase_restored` (matches iOS SDK behavior)
            for (entitlement in entitlements) {
                AppDNA.track("purchase_restored", mapOf(
                    "product_id" to entitlement.productId
                ))
            }

            // SPEC-070-A G.7 — aggregate event with restored_count, matching
            // iOS `Paywalls/PaywallManager.swift:340` shape.
            // SPEC-402 C1 — emit as Int (not String) to match iOS. Looker
            // number filters silently misbehave on String-typed numerics.
            AppDNA.track("purchase_restored", mapOf(
                "restored_count" to entitlements.size
            ))

            // SPEC-070-A B.2 — typed delegate fan-out.
            fireOnRestoreCompleted(entitlements.map { it.productId })

            entitlements
        } catch (e: Exception) {
            Log.error("Restore failed: ${e.message}")
            // SPEC-070-A G.7 — `purchase_restore_failed` matches iOS PaywallManager.swift:352
            AppDNA.track("purchase_restore_failed", mapOf(
                "error" to (e.message ?: "unknown")
            ))
            // SPEC-070-A B.2 — fire onRestoreCompleted with empty list on failure
            // (iOS adapters do the same in catch branches — see AdaptyBridge.swift:114).
            fireOnRestoreCompleted(emptyList())
            emptyList()
        }
    }

    /**
     * SPEC-070-A B.2 — main-thread dispatch of onRestoreCompleted to the host's
     * AppDNABillingDelegate. Best-effort; delegate failures are logged but do
     * not propagate.
     */
    private fun fireOnRestoreCompleted(productIds: List<String>) {
        try {
            val delegate = AppDNA.billing.billingListener ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    delegate.onRestoreCompleted(productIds)
                } catch (e: Throwable) {
                    Log.warning("AppDNABillingDelegate.onRestoreCompleted threw: ${e.message}")
                }
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    // -- Product Queries --

    /**
     * Get product information for a list of subscription product IDs.
     *
     * @param productIds The product IDs to query.
     * @return List of ProductInfo with localized pricing.
     */
    suspend fun getProducts(productIds: List<String>): List<ProductInfo> {
        return priceResolver.getSubscriptionProducts(productIds)
    }

    // -- Internal Helpers --

    /**
     * Handle a successful purchase from the PurchasesUpdatedListener.
     *
     * SPEC-070-A B.2 — fires AppDNABillingDelegate.onPurchaseCompleted /
     * onPurchaseFailed in addition to the existing analytics + suspend resume.
     *
     * SPEC-070-A G.6 — `purchase_completed` properties extended with `price`,
     * `currency`, `experiment_id` to match iOS `Billing/NativeBillingManager.swift`
     * shape so analytics / Growth Memory / experiments aggregations line up.
     */
    private suspend fun handleSuccessfulPurchase(purchase: Purchase) {
        val productId = purchase.products.firstOrNull() ?: return

        // Handle pending purchases
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            Log.info("Purchase is pending for product: $productId")
            AppDNA.track("purchase_pending", mapOf(
                "product_id" to productId,
                "paywall_id" to (currentPaywallId ?: "")
            ))
            resumePurchase(PurchaseResult.Pending)
            return
        }

        try {
            // Verify with server
            val entitlement = receiptVerifier.verify(
                purchaseToken = purchase.purchaseToken,
                productId = productId,
                platform = "android",
                paywallId = currentPaywallId,
                experimentId = currentExperimentId
            )

            // Acknowledge purchase (CRITICAL: must happen within 3 days or purchase is refunded)
            if (!purchase.isAcknowledged) {
                val ackParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                connectionManager.getClient()?.acknowledgePurchase(ackParams) { ackResult ->
                    if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        Log.warning("Failed to acknowledge purchase: ${ackResult.debugMessage}")
                    }
                }
            }

            // Update cache
            entitlementCache.update(entitlement)

            // SPEC-070-A G.6 — resolve price/currency from PriceResolver cache
            // when available. Resolver is best-effort; failures fall through
            // with empty strings so the rest of the analytics envelope still
            // ships.
            val priceInfo = try {
                priceResolver.cachedPriceInfo(productId)
            } catch (_: Throwable) { null }

            val props = mutableMapOf<String, Any>(
                "product_id" to productId,
                "paywall_id" to (currentPaywallId ?: ""),
                // SPEC-070-A finalization parity audit R6 — emit Bool not
                // stringified Bool. iOS NativeBillingManager.swift:160 emits
                // raw Bool; Android was sending "true"/"false" string. ETL
                // dashboards filtering WHERE is_trial = true silently
                // dropped Android rows.
                "is_trial" to entitlement.isTrial,
            )
            if (priceInfo != null) {
                props["price"] = priceInfo.priceMicros / 1_000_000.0
                props["currency"] = priceInfo.currencyCode
            }
            currentExperimentId?.let { props["experiment_id"] = it }

            AppDNA.track("purchase_completed", props)

            // SPEC-070-A B.2 — typed billing delegate fan-out. Build a
            // TransactionInfo mirror (productId is the most stable handle —
            // Google Play orderId requires a separate API).
            val txInfo = ai.appdna.sdk.TransactionInfo(
                transactionId = purchase.orderId ?: productId,
                productId = productId,
                purchaseDate = purchase.purchaseTime.toString(),
                environment = "production",
            )
            try {
                val delegate = AppDNA.billing.billingListener
                if (delegate != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            delegate.onPurchaseCompleted(productId, txInfo)
                        } catch (e: Throwable) {
                            Log.warning("AppDNABillingDelegate.onPurchaseCompleted threw: ${e.message}")
                        }
                    }
                }
            } catch (_: Throwable) { /* best-effort */ }

            // SPEC-070-A B.5 — fire onPaywallPurchaseCompleted on the paywall
            // delegate when this purchase originated from a paywall context
            // (currentPaywallId set by PaywallManager.present →
            // PaywallActivity onPlanSelected). Mirrors iOS PaywallManager.swift
            // post-purchase delegate dispatch.
            try {
                val pwId = currentPaywallId
                val pwDelegate = AppDNA.paywall.listener
                if (pwId != null && pwDelegate != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            pwDelegate.onPaywallPurchaseCompleted(pwId, productId, txInfo)
                        } catch (e: Throwable) {
                            Log.warning("AppDNAPaywallDelegate.onPaywallPurchaseCompleted threw: ${e.message}")
                        }
                    }
                }
            } catch (_: Throwable) { /* best-effort */ }

            resumePurchase(PurchaseResult.Purchased(entitlement))
        } catch (e: Exception) {
            Log.error("Purchase verification failed: ${e.message}")
            AppDNA.track("purchase_failed", mapOf(
                "product_id" to productId,
                "error" to (e.message ?: "verification_error")
            ))

            // SPEC-070-A B.2 — onPurchaseFailed delegate fan-out.
            try {
                val delegate = AppDNA.billing.billingListener
                if (delegate != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            delegate.onPurchaseFailed(productId, e)
                        } catch (err: Throwable) {
                            Log.warning("AppDNABillingDelegate.onPurchaseFailed threw: ${err.message}")
                        }
                    }
                }
            } catch (_: Throwable) { /* best-effort */ }

            resumePurchase(PurchaseResult.Failed(e.message ?: "Verification failed"))
        }
    }

    /**
     * Resume the suspended purchase coroutine with the given result.
     *
     * SPEC-402 C3 — also clears [pendingProductId]. Every terminal purchase
     * outcome (success / cancel / fail / unknown) flows through here, so
     * clearing on resume guarantees the field doesn't leak across purchases.
     */
    private fun resumePurchase(result: PurchaseResult) {
        pendingProductId = null
        purchaseContinuation?.let { continuation ->
            purchaseContinuation = null
            continuation.resume(result) {}
        }
    }

    /**
     * Shut down the billing system and release all resources.
     */
    fun destroy() {
        Log.info("NativeBillingManager: destroying")
        unregisterForegroundObserver()
        scope.cancel()
        connectionManager.destroy()
        entitlementCache.stopObserving()
        currentPaywallId = null
        currentExperimentId = null
    }
}
