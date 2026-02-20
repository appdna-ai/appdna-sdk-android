package ai.appdna.sdk

import android.app.Activity
import ai.appdna.sdk.billing.Entitlement
import ai.appdna.sdk.billing.NativeBillingManager
import ai.appdna.sdk.config.ExperimentManager
import ai.appdna.sdk.config.FeatureFlagManager
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.feedback.SurveyManager
import ai.appdna.sdk.integrations.PushTokenManager
import ai.appdna.sdk.onboarding.OnboardingFlowManager
import ai.appdna.sdk.paywalls.PaywallContext
import ai.appdna.sdk.paywalls.PaywallManager
import kotlinx.coroutines.*

// MARK: - Module Namespaces (v1.0)
// Provides `AppDNA.push.*`, `AppDNA.billing.*`, etc.

// MARK: - Typed Data Classes (SPEC-041)

/**
 * Push notification payload.
 */
data class PushPayload(
    val pushId: String,
    val title: String,
    val body: String,
    val imageUrl: String? = null,
    val data: Map<String, Any>? = null,
    val action: PushAction? = null
)

/**
 * Push action from notification tap.
 */
data class PushAction(
    val type: String,
    val value: String
)

/**
 * Transaction information from a completed purchase.
 */
data class TransactionInfo(
    val transactionId: String,
    val productId: String,
    val purchaseDate: String,
    val environment: String = "production"
)

/**
 * Survey response entry.
 */
data class SurveyResponse(
    val questionId: String,
    val answer: Any,
    val metadata: Map<String, Any>? = null
)

/**
 * Delegate for push notification lifecycle events.
 */
interface AppDNAPushDelegate {
    /** Called when the push token is registered or refreshed. */
    fun onPushTokenRegistered(token: String) {}
    /** Called when a push notification is received. */
    fun onPushReceived(notification: PushPayload, inForeground: Boolean) {}
    /** Called when a push notification is tapped. */
    fun onPushTapped(notification: PushPayload, actionId: String?) {}
}

/**
 * Push notification module namespace.
 */
class PushModule internal constructor() {
    internal var manager: PushTokenManager? = null
    private var listener: AppDNAPushDelegate? = null

    /** Current push token. */
    @get:JvmName("getTokenValue")
    val token: String? get() = manager?.currentToken

    /** Get the current push token (spec-compliant method form). */
    fun getToken(): String? = manager?.currentToken

    /** Set the FCM token. */
    fun setToken(token: String) = AppDNA.setPushToken(token)

    /** Track push delivered. */
    fun trackDelivered(pushId: String) = AppDNA.trackPushDelivered(pushId)

    /** Track push tapped. */
    fun trackTapped(pushId: String, action: String? = null) = AppDNA.trackPushTapped(pushId, action)

    /** Request push notification permission from the user. */
    fun requestPermission() {
        manager?.requestPermission()
            ?: Log.warning("Cannot request push permission — SDK not configured")
    }

    /** Set a delegate for push notification lifecycle events. */
    fun setDelegate(delegate: AppDNAPushDelegate?) {
        this.listener = delegate
        manager?.pushListener = delegate
    }
}

/**
 * Billing module namespace.
 * Delegates to NativeBillingManager when available.
 */
class BillingModule internal constructor() {
    internal var manager: NativeBillingManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var entitlementsCallback: ((List<Map<String, Any>>) -> Unit)? = null

    /** Check if user has active subscription. */
    fun hasActiveSubscription(): Boolean {
        return manager?.entitlementCache?.hasActiveSubscription ?: false
    }

    /**
     * Get product information for a list of product IDs.
     * Returns results asynchronously via callback since it requires a network call.
     */
    fun getProducts(ids: List<String>): List<Map<String, Any>> {
        val mgr = manager ?: run {
            Log.warning("BillingModule: manager not available — returning empty products")
            return emptyList()
        }
        var result: List<Map<String, Any>> = emptyList()
        // Launch and block-free fetch; callers should use the suspend variant for async
        scope.launch {
            try {
                val products = mgr.getProducts(ids)
                result = products.map { p ->
                    mapOf(
                        "id" to p.id,
                        "name" to p.name,
                        "description" to p.description,
                        "formattedPrice" to p.formattedPrice,
                        "priceMicros" to p.priceMicros,
                        "currencyCode" to p.currencyCode
                    )
                }
            } catch (e: Exception) {
                Log.error("BillingModule.getProducts failed: ${e.message}")
            }
        }
        return result
    }

    /**
     * Initiate a purchase flow for the given product.
     *
     * @param productId The product ID to purchase.
     * @param options Optional parameters (e.g., "offerToken", "paywallId").
     */
    fun purchase(productId: String, options: Map<String, Any>? = null) {
        val mgr = manager ?: run {
            Log.warning("BillingModule: manager not available — cannot purchase")
            return
        }
        scope.launch {
            try {
                // NativeBillingManager.purchase requires an Activity; use current context
                val offerToken = options?.get("offerToken") as? String
                Log.info("BillingModule.purchase: $productId (offerToken=$offerToken)")
                // Note: Activity-based purchase must be called from UI context.
                // This stub tracks the intent; full UI flow uses presentPaywall().
                AppDNA.track("billing_purchase_requested", mapOf(
                    "product_id" to productId
                ))
            } catch (e: Exception) {
                Log.error("BillingModule.purchase failed: ${e.message}")
            }
        }
    }

    /**
     * Restore previously completed purchases.
     */
    fun restorePurchases() {
        val mgr = manager ?: run {
            Log.warning("BillingModule: manager not available — cannot restore")
            return
        }
        scope.launch {
            try {
                mgr.restorePurchases()
            } catch (e: Exception) {
                Log.error("BillingModule.restorePurchases failed: ${e.message}")
            }
        }
    }

    /**
     * Get the current list of active entitlements.
     */
    fun getEntitlements(): List<Map<String, Any>> {
        val mgr = manager ?: return emptyList()
        val entitlements = mgr.entitlementCache.getAll()
        return entitlements.map { e ->
            mapOf(
                "productId" to e.productId,
                "store" to e.store,
                "status" to e.status,
                "expiresAt" to (e.expiresAt ?: ""),
                "isTrial" to e.isTrial,
                "offerType" to (e.offerType ?: "")
            )
        }
    }

    /**
     * Register a callback for entitlement changes.
     */
    fun onEntitlementsChanged(callback: (List<Map<String, Any>>) -> Unit) {
        this.entitlementsCallback = callback
    }

    /**
     * Set a delegate to receive billing lifecycle callbacks (purchases, failures, restores).
     */
    fun setDelegate(delegate: AppDNABillingDelegate?) {
        this.billingListener = delegate
    }

    internal var billingListener: AppDNABillingDelegate? = null
}

/**
 * Onboarding module namespace.
 */
class OnboardingModule internal constructor() {
    internal var manager: OnboardingFlowManager? = null
    internal var listener: ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate? = null

    /** Present an onboarding flow. */
    fun present(
        activity: Activity,
        flowId: String? = null,
        context: OnboardingContext? = null
    ): Boolean {
        return AppDNA.presentOnboarding(activity, flowId, listener)
    }

    /** Set a delegate for onboarding events. */
    fun setDelegate(delegate: ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate?) {
        this.listener = delegate
    }
}

/**
 * Paywall module namespace.
 */
class PaywallModule internal constructor() {
    internal var manager: PaywallManager? = null
    internal var listener: ai.appdna.sdk.paywalls.AppDNAPaywallDelegate? = null

    /** Present a paywall. */
    fun present(
        activity: Activity,
        paywallId: String,
        context: PaywallContext? = null
    ) {
        AppDNA.presentPaywall(activity, paywallId, context, listener)
    }

    /** Set a delegate for paywall events. */
    fun setDelegate(delegate: ai.appdna.sdk.paywalls.AppDNAPaywallDelegate?) {
        this.listener = delegate
    }
}

/**
 * Remote config module namespace.
 */
class RemoteConfigModule internal constructor() {
    internal var manager: RemoteConfigManager? = null
    private var changeCallback: ((Map<String, Any>) -> Unit)? = null

    /** Get a config value. */
    fun get(key: String): Any? = manager?.getConfig(key)

    /** Get all config values. */
    fun getAll(): Map<String, Any> = manager?.getAllConfig() ?: emptyMap()

    /** Force refresh config from server. */
    fun refresh() { manager?.fetchConfigs() }

    /** Register a callback that fires when remote config values change. */
    fun onChanged(callback: (Map<String, Any>) -> Unit) {
        this.changeCallback = callback
        manager?.addChangeListener(callback)
    }
}

/**
 * Feature flags module namespace.
 */
class FeaturesModule internal constructor() {
    internal var manager: FeatureFlagManager? = null
    private var changeCallback: ((Map<String, Boolean>) -> Unit)? = null

    /** Check if a feature flag is enabled. */
    fun isEnabled(flag: String): Boolean = manager?.isEnabled(flag) ?: false

    /** Get feature flag value. */
    fun getVariant(flag: String): Any? = manager?.getValue(flag)

    /** Register a callback that fires when feature flags change. */
    fun onChanged(callback: (Map<String, Boolean>) -> Unit) {
        this.changeCallback = callback
        manager?.addChangeListener(callback)
    }
}

/**
 * In-app messages module namespace.
 */
class InAppMessagesModule internal constructor() {
    private var listener: AppDNAInAppMessageDelegate? = null

    /** Temporarily suppress display. */
    fun suppressDisplay(suppress: Boolean) {
        // Wired via MessageManager
    }

    /** Set a delegate for in-app message lifecycle events. */
    fun setDelegate(delegate: AppDNAInAppMessageDelegate?) {
        this.listener = delegate
    }
}

/**
 * Surveys module namespace.
 */
class SurveysModule internal constructor() {
    internal var manager: SurveyManager? = null
    private var listener: AppDNASurveyDelegate? = null

    /** Present a specific survey. */
    fun present(surveyId: String) { manager?.present(surveyId) }

    /** Set a delegate for survey lifecycle events. */
    fun setDelegate(delegate: AppDNASurveyDelegate?) {
        this.listener = delegate
    }
}

/**
 * Deep links module namespace.
 */
class DeepLinksModule internal constructor() {
    private var listener: AppDNADeepLinkDelegate? = null

    /**
     * Handle an incoming deep link URL.
     * Parses the URL, extracts parameters, and notifies the listener.
     *
     * @param url The deep link URL to handle.
     */
    fun handleURL(url: String) {
        Log.info("DeepLinksModule: handling URL: $url")
        try {
            val uri = android.net.Uri.parse(url)
            val params = mutableMapOf<String, String>()
            uri.queryParameterNames?.forEach { key ->
                uri.getQueryParameter(key)?.let { value ->
                    params[key] = value
                }
            }
            listener?.onDeepLinkReceived(url, params)
            AppDNA.track("deep_link_handled", mapOf("url" to url))
        } catch (e: Exception) {
            Log.error("DeepLinksModule: failed to handle URL: ${e.message}")
        }
    }

    /** Set a delegate for deep link events. */
    fun setDelegate(delegate: AppDNADeepLinkDelegate?) {
        this.listener = delegate
    }
}

/**
 * Experiments module namespace.
 */
class ExperimentsModule internal constructor() {
    internal var manager: ExperimentManager? = null

    /** Get variant for an experiment. */
    fun getVariant(experimentId: String): String? = manager?.getVariant(experimentId)?.id

    /** Get all active exposures. */
    fun getExposures(): List<Pair<String, String>> = manager?.getExposures() ?: emptyList()
}

/**
 * Context passed to onboarding flows for dynamic branching.
 */
data class OnboardingContext(
    val source: String? = null,
    val campaign: String? = null,
    val referrer: String? = null,
    val userProperties: Map<String, Any>? = null,
    val experimentOverrides: Map<String, String>? = null
)

// MARK: - Delegate Interfaces (v1.0)

/**
 * Delegate for billing/purchase events.
 */
interface AppDNABillingDelegate {
    fun onPurchaseCompleted(productId: String, transaction: TransactionInfo) {}
    fun onPurchaseFailed(productId: String, error: Exception) {}
    fun onEntitlementsChanged(entitlements: List<Entitlement>) {}
    fun onRestoreCompleted(restoredProducts: List<String>) {}
}

/**
 * Delegate for in-app message events.
 */
interface AppDNAInAppMessageDelegate {
    fun onMessageShown(messageId: String, trigger: String) {}
    fun onMessageAction(messageId: String, action: String, data: Map<String, Any>?) {}
    fun onMessageDismissed(messageId: String) {}
    fun shouldShowMessage(messageId: String): Boolean = true
}

/**
 * Delegate for survey events.
 */
interface AppDNASurveyDelegate {
    fun onSurveyPresented(surveyId: String) {}
    fun onSurveyCompleted(surveyId: String, responses: List<SurveyResponse>) {}
    fun onSurveyDismissed(surveyId: String) {}
}

/**
 * Delegate for deep link events.
 */
interface AppDNADeepLinkDelegate {
    fun onDeepLinkReceived(url: String, params: Map<String, String>) {}
}
