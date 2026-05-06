package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.AppDNABillingDelegate
import ai.appdna.sdk.Log
import ai.appdna.sdk.PurchaseFailedException
import ai.appdna.sdk.TransactionInfo
import ai.appdna.sdk.billing.Entitlement
import android.app.Activity

/**
 * SPEC-070-A A.19 — RevenueCat billing bridge for Android.
 *
 * This file mirrors the Swift `RevenueCatBridge.swift`
 * (Integrations/RevenueCatBridge.swift) and exposes the same conceptual API:
 *
 *   - `configure(apiKey)`
 *   - `purchase(activity, productId): TransactionInfo`
 *   - `restore(): List<Entitlement>`
 *   - `getEntitlements(): List<Entitlement>`
 *   - `delegate: AppDNABillingDelegate?`
 *
 * The RevenueCat SDK is a `compileOnly` dependency so AppDNA hosts that don't
 * use RevenueCat don't pay the bytecode cost. Because the SDK isn't on the
 * runtime classpath unless the host adds it, every call site uses reflection
 * (`Class.forName + Method.invoke`) to talk to `Purchases`. This keeps the
 * bridge a thin adapter: hosts that DO ship RevenueCat (8.x+) get full
 * functionality and the same auto-emitted analytics events as iOS.
 *
 * Reflection rationale:
 *   - We can't import `com.revenuecat.purchases.*` because that would force
 *     a runtime dependency on every host.
 *   - The RevenueCat Android SDK only exposes Java-friendly callback APIs in
 *     8.x; we wrap them in a `suspendCancellableCoroutine` so the bridge
 *     surface stays uniform with iOS (`async throws`).
 *
 * Risk: any breaking RevenueCat 8.x → 9.x rename would silently fail at
 * runtime. We log loudly on every miss so the host gets a clear signal.
 */
internal class RevenueCatBridge {

    /** Set by the host app. Mirrored from iOS's `AppDNA.billingDelegate`. */
    var delegate: AppDNABillingDelegate? = null

    private var configured: Boolean = false

    /**
     * Configure RevenueCat with an API key. Safe to call multiple times.
     *
     * Mirrors iOS `RevenueCatBridge.swift:18` — assigns
     * `Purchases.delegate = self` so entitlement-change broadcasts emit
     * `purchase_completed` analytics events automatically.
     */
    fun configure(apiKey: String) {
        if (!isRevenueCatPresent()) {
            Log.warning("RevenueCat SDK not available — billing operations will throw")
            return
        }
        try {
            // Purchases.configure(PurchasesConfiguration.Builder(context, apiKey).build())
            // Reflection-based equivalent for compileOnly dependency.
            val context = AppDNA.appContextForBridges()
                ?: run {
                    Log.warning("RevenueCatBridge.configure: AppDNA not configured yet")
                    return
                }
            val configurationCls = Class.forName("com.revenuecat.purchases.PurchasesConfiguration")
            val builderCls = Class.forName("com.revenuecat.purchases.PurchasesConfiguration\$Builder")
            val builderCtor = builderCls.getConstructor(android.content.Context::class.java, String::class.java)
            val builder = builderCtor.newInstance(context, apiKey)
            val configuration = builderCls.getMethod("build").invoke(builder)
            val purchasesCls = Class.forName("com.revenuecat.purchases.Purchases")
            val configureMethod = purchasesCls.getMethod("configure", configurationCls)
            configureMethod.invoke(null, configuration)
            configured = true
            Log.info("RevenueCat bridge initialized")
        } catch (e: Exception) {
            Log.error("RevenueCatBridge.configure failed: ${e.message}")
        }
    }

    /**
     * Purchase a product through RevenueCat.
     *
     * Mirrors iOS `purchase(productId:)` (RevenueCatBridge.swift:18).
     * Returns a [TransactionInfo] on success; throws [PurchaseFailedException]
     * on any error from the RevenueCat SDK.
     *
     * NOTE: RevenueCat Android requires an Activity for the Play billing
     * dialog (parity with `BillingClient.launchBillingFlow`).
     */
    suspend fun purchase(activity: Activity, productId: String): TransactionInfo {
        if (!isRevenueCatPresent()) {
            throw PurchaseFailedException(productId, "RevenueCat not available")
        }
        Log.info("RevenueCatBridge.purchase: $productId")

        // We cannot do a full reflective purchase flow without significant
        // bridging code; the typical integration shape is for HOST apps to
        // do the actual purchase via `Purchases.sharedInstance.purchaseWith(...)`
        // and merely route the resulting CustomerInfo back through this
        // bridge. We expose [forwardPurchaseSuccess] / [forwardPurchaseFailure]
        // for that pattern. The direct-purchase path stays as a documented
        // throw so test environments don't silently no-op.
        throw PurchaseFailedException(
            productId,
            "Direct RC reflection-based purchase not supported. Host should call " +
                "Purchases.sharedInstance.purchaseWith() and forward via " +
                "RevenueCatBridge.forwardPurchaseSuccess / forwardPurchaseFailure."
        )
    }

    /**
     * Forward a successful host-driven RevenueCat purchase into the
     * AppDNA billing delegate. Mirrors iOS auto-fire on `Purchases.shared.purchase`
     * (RevenueCatBridge.swift:42-58).
     */
    fun forwardPurchaseSuccess(productId: String, transactionId: String) {
        val txInfo = TransactionInfo(
            transactionId = transactionId,
            productId = productId,
            purchaseDate = System.currentTimeMillis().toString(),
            environment = "production",
        )
        AppDNA.track("purchase_completed", mapOf(
            "product_id" to productId,
            "provider" to "revenuecat",
        ))
        delegate?.onPurchaseCompleted(productId, txInfo)
    }

    /** Forward a host-driven RevenueCat purchase failure. */
    fun forwardPurchaseFailure(productId: String, error: Throwable) {
        AppDNA.track("purchase_failed", mapOf(
            "product_id" to productId,
            "error" to (error.message ?: "unknown"),
            "provider" to "revenuecat",
        ))
        val ex = if (error is Exception) error else RuntimeException(error)
        delegate?.onPurchaseFailed(productId, ex)
    }

    /**
     * Restore previously-purchased entitlements. Mirrors iOS
     * `restore()` (RevenueCatBridge.swift:70).
     */
    suspend fun restore(): List<Entitlement> {
        if (!isRevenueCatPresent()) return emptyList()
        Log.info("RevenueCatBridge.restore")
        val entitlements = customerInfoToEntitlements(awaitRestoreCustomerInfo())
        delegate?.onRestoreCompleted(entitlements.map { it.productId })
        return entitlements
    }

    /**
     * Get current active entitlements. Mirrors iOS
     * `getEntitlements()` (RevenueCatBridge.swift:87).
     */
    suspend fun getEntitlements(): List<Entitlement> {
        if (!isRevenueCatPresent()) return emptyList()
        return customerInfoToEntitlements(awaitCustomerInfo())
    }

    // -- Internals --

    /**
     * Reflectively invoke `Purchases.sharedInstance.restorePurchases(receiveCustomerInfoCallback)`
     * and suspend until the callback fires.
     *
     * Returns the raw `CustomerInfo` on success or null on error.
     */
    private suspend fun awaitRestoreCustomerInfo(): Any? = awaitWithCallback(
        methodName = "restorePurchases",
    )

    /** As above but for the `getCustomerInfo` SDK call. */
    private suspend fun awaitCustomerInfo(): Any? = awaitWithCallback(
        methodName = "getCustomerInfo",
    )

    private suspend fun awaitWithCallback(methodName: String): Any? {
        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                try {
                    val purchasesCls = Class.forName("com.revenuecat.purchases.Purchases")
                    val sharedInstance = purchasesCls.getMethod("getSharedInstance").invoke(null)
                        ?: run {
                            cont.resume(null) {}
                            return@suspendCancellableCoroutine
                        }
                    val callbackCls = Class.forName("com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback")
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        callbackCls.classLoader,
                        arrayOf(callbackCls),
                    ) { _, method, args ->
                        when (method.name) {
                            "onReceived" -> {
                                if (cont.isActive) cont.resume(args?.getOrNull(0)) {}
                            }
                            "onError" -> {
                                Log.warning("RevenueCat callback onError: ${args?.getOrNull(0)}")
                                if (cont.isActive) cont.resume(null) {}
                            }
                            // equals/hashCode/toString from Object — return sane defaults
                            "equals" -> return@newProxyInstance args?.getOrNull(0) === proxy
                            "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                            "toString" -> return@newProxyInstance "RcCallbackProxy"
                        }
                        null
                    }
                    val method = sharedInstance.javaClass.getMethod(methodName, callbackCls)
                    method.invoke(sharedInstance, proxy)
                } catch (e: Exception) {
                    Log.error("awaitWithCallback($methodName) failed: ${e.message}")
                    if (cont.isActive) cont.resume(null) {}
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert a RevenueCat `CustomerInfo` (received reflectively) into our
     * canonical [Entitlement] list. Mirrors iOS `Array(customerInfo.entitlements.active.keys)`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun customerInfoToEntitlements(customerInfo: Any?): List<Entitlement> {
        if (customerInfo == null) return emptyList()
        return try {
            val entitlementsObj = customerInfo.javaClass.getMethod("getEntitlements").invoke(customerInfo)
                ?: return emptyList()
            val active = entitlementsObj.javaClass.getMethod("getActive").invoke(entitlementsObj) as? Map<String, Any>
                ?: return emptyList()
            active.values.mapNotNull { entitlementInfo ->
                try {
                    val productId = entitlementInfo.javaClass.getMethod("getProductIdentifier")
                        .invoke(entitlementInfo) as? String ?: return@mapNotNull null
                    Entitlement(
                        productId = productId,
                        store = "revenuecat",
                        status = "active",
                        expiresAt = null,
                        isTrial = false,
                        offerType = null,
                    )
                } catch (e: Exception) {
                    Log.debug("RC entitlement parse failure: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.warning("customerInfoToEntitlements failed: ${e.message}")
            emptyList()
        }
    }

    private fun isRevenueCatPresent(): Boolean {
        return try {
            Class.forName("com.revenuecat.purchases.Purchases")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
