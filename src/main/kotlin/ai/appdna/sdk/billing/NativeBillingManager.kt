package ai.appdna.sdk.billing

import android.app.Activity
import android.content.Context
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellableContinuation

/**
 * Entitlement model representing an active subscription or purchase.
 */
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
sealed class BillingResult {
    /** Purchase completed and verified. */
    data class Purchased(val entitlement: Entitlement) : BillingResult()
    /** User cancelled the purchase flow. */
    object Cancelled : BillingResult()
    /** Purchase is pending (e.g., awaiting parental approval or slow payment method). */
    object Pending : BillingResult()
    /** Unknown or unhandled result. */
    object Unknown : BillingResult()
    /** Purchase verification failed. */
    data class Failed(val error: String) : BillingResult()
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
class NativeBillingManager(
    private val context: Context,
    internal val receiptVerifier: ReceiptVerifier,
    internal val entitlementCache: EntitlementCache
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Connection manager handling BillingClient lifecycle. */
    internal val connectionManager = BillingConnectionManager(
        context = context,
        purchasesUpdatedListener = purchaseUpdateListener
    )

    /** Price resolver for querying product details. */
    val priceResolver = PriceResolver(connectionManager)

    /** Active purchase continuation — only one purchase flow at a time. */
    private var purchaseContinuation: CancellableContinuation<BillingResult>? = null

    /** Attribution context for the current purchase. */
    var currentPaywallId: String? = null
    var currentExperimentId: String? = null

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
                resumePurchase(BillingResult.Cancelled)
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.info("Item already owned — triggering restore")
                scope.launch {
                    try {
                        val restored = restorePurchases()
                        val entitlement = restored.firstOrNull()
                        if (entitlement != null) {
                            resumePurchase(BillingResult.Purchased(entitlement))
                        } else {
                            resumePurchase(BillingResult.Unknown)
                        }
                    } catch (e: Exception) {
                        resumePurchase(BillingResult.Failed(e.message ?: "Restore failed"))
                    }
                }
            }
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> {
                Log.warning("Item not owned")
                resumePurchase(BillingResult.Failed("Item not owned"))
            }
            else -> {
                Log.warning("Purchase update: code=${billingResult.responseCode}, msg=${billingResult.debugMessage}")
                resumePurchase(BillingResult.Unknown)
            }
        }
    }

    // -- Initialization --

    /**
     * Initialize the billing system. Must be called before any billing operations.
     * Typically called after AppDNA.configure() and onReady.
     */
    fun initialize() {
        Log.info("NativeBillingManager: initializing")
        connectionManager.initialize()
    }

    // -- Purchase Flow --

    /**
     * Initiate a subscription purchase.
     *
     * @param activity The Activity to launch the billing flow from.
     * @param productId The Google Play product ID to purchase.
     * @param offerToken Optional offer token for a specific offer/trial.
     * @return The result of the purchase operation.
     */
    suspend fun purchase(
        activity: Activity,
        productId: String,
        offerToken: String? = null
    ): BillingResult {
        Log.info("Starting purchase for product: $productId")
        AppDNA.track("purchase_started", mapOf(
            "product_id" to productId,
            "paywall_id" to (currentPaywallId ?: "")
        ))

        val client = connectionManager.awaitConnectedClient()
            ?: return BillingResult.Failed("BillingClient not connected")

        // Query product details
        val queryParams = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            ))
            .build()

        val queryResult = client.queryProductDetails(queryParams)
        if (queryResult.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.warning("Failed to query product details: ${queryResult.billingResult.debugMessage}")
            return BillingResult.Failed("Failed to query product details")
        }

        val productDetails = queryResult.productDetailsList?.firstOrNull()
            ?: return BillingResult.Failed("Product not found: $productId")

        // Resolve offer
        val selectedOffer = if (offerToken != null) {
            productDetails.subscriptionOfferDetails?.find { it.offerToken == offerToken }
        } else {
            productDetails.subscriptionOfferDetails?.firstOrNull()
        }

        // Build billing flow params
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .apply { selectedOffer?.let { setOfferToken(it.offerToken) } }
                    .build()
            ))
            .build()

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
                    BillingResult.Failed("Failed to launch billing flow: ${launchResult.debugMessage}")
                ) {}
            }
        }
    }

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
                "productId" to purchase.products.first()
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
            resumePurchase(BillingResult.Pending)
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

            resumePurchase(BillingResult.Purchased(entitlement))
        } catch (e: Exception) {
            Log.error("Purchase verification failed: ${e.message}")
            AppDNA.track("purchase_failed", mapOf(
                "product_id" to productId,
                "error" to (e.message ?: "verification_error")
            ))
            resumePurchase(BillingResult.Failed(e.message ?: "Verification failed"))
        }
    }

    /**
     * Resume the suspended purchase coroutine with the given result.
     */
    private fun resumePurchase(result: BillingResult) {
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
        scope.cancel()
        connectionManager.destroy()
        entitlementCache.stopObserving()
        currentPaywallId = null
        currentExperimentId = null
    }
}
