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

    /** Active purchase continuation — only one purchase flow at a time. */
    private var purchaseContinuation: CancellableContinuation<PurchaseResult>? = null

    /** Attribution context for the current purchase. */
    var currentPaywallId: String? = null
    var currentExperimentId: String? = null

    // SPEC-070-A A.20 — process-lifecycle observer that polls
    // `BillingClient.queryPurchasesAsync` on every foreground entry, diffs
    // against the snapshot persisted in [LocalStorage], and emits
    // `subscription_renewed` / `subscription_cancelled` /
    // `subscription_renewal_failed` events. Mirrors iOS
    // `Billing.SubscriptionStatusObserver` (which is StoreKit-driven).
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
                AppDNA.track("purchase_canceled", mapOf(
                    "paywall_id" to (currentPaywallId ?: "")
                ))
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
            }
            else -> {
                Log.warning("Purchase update: code=${billingResult.responseCode}, msg=${billingResult.debugMessage}")
                resumePurchase(PurchaseResult.Unknown)
            }
        }
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
     *    otherwise it's a normal cancellation → `subscription_cancelled`.
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
        val current = mutableMapOf<String, SubSnapshot>()
        for (purchase in result.purchasesList) {
            val productId = purchase.products.firstOrNull() ?: continue
            current[productId] = SubSnapshot(
                productId = productId,
                purchaseTime = purchase.purchaseTime,
                isAcknowledged = purchase.isAcknowledged,
                isAutoRenewing = purchase.isAutoRenewing,
            )
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
                val event = if (prev.isAutoRenewing) "subscription_renewal_failed" else "subscription_cancelled"
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
                val pid = obj.optString("productId", "").ifEmpty { continue }
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
        AppDNA.track("purchase_started", mapOf(
            "product_id" to productId,
            "paywall_id" to (currentPaywallId ?: "")
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
        options?.appAccountToken?.let { token ->
            flowParamsBuilder.setObfuscatedAccountId(token.toString())
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
     * @return List of restored Entitlements.
     */
    suspend fun restorePurchases(): List<Entitlement> {
        Log.info("Restoring purchases")
        AppDNA.track("restore_started", emptyMap())

        val client = connectionManager.awaitConnectedClient()
            ?: return emptyList()

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        val result = client.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.warning("Failed to query purchases: ${result.billingResult.debugMessage}")
            return emptyList()
        }

        val transactions = result.purchasesList.map { purchase ->
            mapOf(
                "token" to purchase.purchaseToken,
                "productId" to (purchase.products.firstOrNull() ?: "unknown")
            )
        }

        if (transactions.isEmpty()) {
            Log.info("No purchases to restore")
            entitlementCache.replaceAll(emptyList())
            return emptyList()
        }

        return try {
            val entitlements = receiptVerifier.restore(transactions)
            entitlementCache.replaceAll(entitlements)

            // Track per-entitlement purchase_restored event (matches iOS SDK behavior)
            for (entitlement in entitlements) {
                AppDNA.track("purchase_restored", mapOf(
                    "product_id" to entitlement.productId
                ))
            }

            AppDNA.track("restore_completed", mapOf(
                "count" to entitlements.size.toString()
            ))

            entitlements
        } catch (e: Exception) {
            Log.error("Restore failed: ${e.message}")
            AppDNA.track("restore_failed", mapOf(
                "error" to (e.message ?: "unknown")
            ))
            emptyList()
        }
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

            // Track success
            AppDNA.track("purchase_completed", mapOf(
                "product_id" to productId,
                "paywall_id" to (currentPaywallId ?: ""),
                "is_trial" to entitlement.isTrial.toString()
            ))

            resumePurchase(PurchaseResult.Purchased(entitlement))
        } catch (e: Exception) {
            Log.error("Purchase verification failed: ${e.message}")
            AppDNA.track("purchase_failed", mapOf(
                "product_id" to productId,
                "error" to (e.message ?: "verification_error")
            ))
            resumePurchase(PurchaseResult.Failed(e.message ?: "Verification failed"))
        }
    }

    /**
     * Resume the suspended purchase coroutine with the given result.
     */
    private fun resumePurchase(result: PurchaseResult) {
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
