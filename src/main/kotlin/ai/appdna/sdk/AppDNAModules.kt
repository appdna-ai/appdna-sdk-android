package ai.appdna.sdk

import android.app.Activity
import android.os.Build
import ai.appdna.sdk.billing.Entitlement
import ai.appdna.sdk.billing.NativeBillingManager
import ai.appdna.sdk.billing.ProductInfo
import ai.appdna.sdk.billing.PurchaseOptions as BillingPurchaseOptions
import ai.appdna.sdk.billing.PurchaseResult
import ai.appdna.sdk.config.ExperimentManager
import ai.appdna.sdk.config.FeatureFlagManager
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.feedback.SurveyManager
import ai.appdna.sdk.integrations.PushTokenManager
import ai.appdna.sdk.onboarding.OnboardingFlowManager
import ai.appdna.sdk.paywalls.PaywallContext
import ai.appdna.sdk.paywalls.PaywallManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.*
import kotlinx.coroutines.future.future
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume

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
    // SPEC-070-A H.17: dropped duplicate `listener` field. The single source
    // of truth is `PushTokenManager.pushListener`, which Phase B.1 wires from
    // [setDelegate]. Reading the delegate now goes through [delegate].

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

    /**
     * SPEC-070-A A.30 + H.4 — typed suspend permission request.
     *
     * On Android 13+ (`Build.VERSION_CODES.TIRAMISU` / API 33), requesting
     * `POST_NOTIFICATIONS` is a runtime permission and MUST be triggered from
     * an Activity via the Activity Result API. We use
     * `registerForActivityResult(RequestPermission)` and suspend until the
     * user answers.
     *
     * Below API 33, push permission is implicit (granted at install) and we
     * return `true` immediately. We still emit the same analytics through
     * [PushTokenManager.setPushPermission].
     *
     * @param activity Required on API 33+. May be `null` on older API levels;
     *                 the permission is implicit there.
     * @return `true` if the user granted the permission (or no permission is
     *         required on this OS version), `false` if denied.
     */
    suspend fun requestPermission(activity: Activity? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Permission is implicit pre-13; report granted.
            manager?.setPushPermission(true)
            return true
        }
        val componentActivity = activity as? ComponentActivity
        if (componentActivity == null) {
            Log.warning("PushModule.requestPermission: requires a ComponentActivity on Android 13+")
            return false
        }
        return suspendCancellableCoroutine { cont ->
            val launcher = componentActivity.activityResultRegistry.register(
                "ai.appdna.sdk.push.permission.${UUID.randomUUID()}",
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                manager?.setPushPermission(granted)
                if (cont.isActive) cont.resume(granted)
            }
            cont.invokeOnCancellation {
                runCatching { launcher.unregister() }
            }
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Java-friendly overload — returns a [CompletableFuture]. Mirrors the
     * suspend [requestPermission] above for non-Kotlin consumers.
     */
    @JvmOverloads
    fun requestPermissionFuture(activity: Activity? = null): CompletableFuture<Boolean> =
        moduleScope.future { requestPermission(activity) }

    /** Set a delegate for push notification lifecycle events. */
    fun setDelegate(delegate: AppDNAPushDelegate?) {
        manager?.pushListener = delegate
    }

    /**
     * SPEC-070-A H.8: read accessor used by [AppDNAMessagingService] to fire
     * `onPushReceived` / `onPushTapped`. Returns the live delegate registered
     * via [setDelegate], or null when no host has registered one.
     */
    internal fun delegate(): AppDNAPushDelegate? = manager?.pushListener

    companion object {
        // Shared scope for Java-future overloads. Kept in a companion to avoid
        // per-instance lifecycle leaks; the SDK never re-creates PushModule.
        private val moduleScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }
}

/**
 * Billing module namespace.
 * Delegates to NativeBillingManager when available.
 *
 * SPEC-070-A A.29 / A.30 — public APIs are typed suspend functions returning
 * concrete data classes (`TransactionInfo`, `ProductInfo`, `Entitlement`),
 * mirroring iOS `AppDNA.Billing` (AppDNA+Modules.swift). Java consumers get
 * `*Future` overloads via [kotlinx.coroutines.future.future].
 */
class BillingModule internal constructor() {
    internal var manager: NativeBillingManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Check if user has active subscription. */
    fun hasActiveSubscription(): Boolean {
        return manager?.entitlementCache?.hasActiveSubscription ?: false
    }

    /**
     * SPEC-401 Fix 1D — silently refresh cached entitlement state.
     *
     * Calls into [NativeBillingManager.refreshEntitlementCache] which
     * re-reads the user's current entitlements via Play Billing
     * `BillingClient.queryPurchasesAsync` and primes the
     * [EntitlementCache] in place. Designed for two callers:
     *   1. [AppDNA.identify] — auto-refresh after host signs in a user
     *      so the next paywall_trigger entitlement gate (Fix 1A)
     *      reflects that user's subscriptions, not the previous
     *      anonymous user's empty entitlements.
     *   2. Hosts that complete auth out-of-band (SSO callbacks, deep
     *      links, OAuth web flows) and need to flush stale cache
     *      without firing user-visible restore events.
     *
     * Side effects: ZERO. No analytics events, no delegate callbacks,
     * no UI. Errors are swallowed and logged at warning level — the
     * suspend returns normally so callers can chain without try/catch.
     * Mirrors iOS `BillingModule.refreshEntitlementCache()`.
     */
    suspend fun refreshEntitlementCache() {
        val mgr = manager ?: run {
            Log.warning("BillingModule.refreshEntitlementCache: manager not available")
            return
        }
        try {
            mgr.refreshEntitlementCache()
        } catch (e: Exception) {
            Log.warning("BillingModule.refreshEntitlementCache failed: ${e.message}")
        }
    }

    /** Java-friendly overload — returns a [CompletableFuture]. */
    fun refreshEntitlementCacheFuture(): CompletableFuture<Unit> =
        scope.future { refreshEntitlementCache() }

    /**
     * SPEC-070-A A.30 — typed suspend product fetch.
     *
     * Returns `ProductInfo` data classes from [NativeBillingManager.getProducts].
     * Replaces the previous broken `scope.launch; return result` stub which
     * always returned an empty list synchronously.
     */
    suspend fun getProducts(productIds: List<String>): List<ProductInfo> {
        val mgr = manager ?: run {
            Log.warning("BillingModule: manager not available — returning empty products")
            return emptyList()
        }
        return try {
            mgr.getProducts(productIds)
        } catch (e: Exception) {
            Log.error("BillingModule.getProducts failed: ${e.message}")
            emptyList()
        }
    }

    /** Java-friendly overload — returns a [CompletableFuture]. */
    fun getProductsFuture(productIds: List<String>): CompletableFuture<List<ProductInfo>> =
        scope.future { getProducts(productIds) }

    /**
     * SPEC-070-A A.29 / A.30 — initiate a purchase flow.
     *
     * Activity is now the FIRST parameter (required by Google Play Billing
     * `launchBillingFlow`). Mirrors iOS `BillingModule.purchase(productId:options:)`
     * (AppDNA+Modules.swift:72).
     *
     * Returns a [TransactionInfo] on success. Throws on failure (cancelled,
     * pending, billing error).
     *
     * @param activity Activity used to launch the Play billing dialog.
     * @param productId The Google Play product ID to purchase.
     * @param options Optional purchase parameters (offer token, app account token).
     */
    @JvmOverloads
    suspend fun purchase(
        activity: Activity,
        productId: String,
        options: BillingPurchaseOptions? = null,
    ): TransactionInfo {
        val mgr = manager ?: run {
            Log.warning("BillingModule: manager not available — cannot purchase")
            throw IllegalStateException("BillingModule: NativeBillingManager not initialized — call AppDNA.configure() first")
        }
        AppDNA.track("billing_purchase_requested", mapOf("product_id" to productId))

        val result = mgr.purchase(activity, productId, options)
        return when (result) {
            is PurchaseResult.Purchased -> {
                val ent = result.entitlement
                TransactionInfo(
                    transactionId = ent.productId, // Google Play exposes the orderId via the receipt; entitlement productId is the closest stable handle
                    productId = ent.productId,
                    purchaseDate = ent.expiresAt ?: "",
                    environment = "production",
                )
            }
            is PurchaseResult.Cancelled -> throw PurchaseCancelledException(productId)
            is PurchaseResult.Pending -> throw PurchasePendingException(productId)
            is PurchaseResult.Failed -> throw PurchaseFailedException(productId, result.error)
            is PurchaseResult.Unknown -> throw PurchaseFailedException(productId, "unknown billing result")
        }
    }

    /** Java-friendly overload — returns a [CompletableFuture]. */
    @JvmOverloads
    fun purchaseFuture(
        activity: Activity,
        productId: String,
        options: BillingPurchaseOptions? = null,
    ): CompletableFuture<TransactionInfo> =
        scope.future { purchase(activity, productId, options) }

    /**
     * SPEC-070-A A.30 — typed suspend restore. Mirrors iOS
     * `AppDNA+Modules.swift:88` `restorePurchases() async throws -> [String]`.
     * Returns the list of restored product IDs so hosts can show a confirmation
     * UI; the underlying [Entitlement] objects are still surfaced via
     * [AppDNABillingDelegate.onRestoreCompleted] for hosts that prefer the
     * delegate flow.
     */
    suspend fun restorePurchases(): List<String> {
        val mgr = manager ?: run {
            Log.warning("BillingModule: manager not available — cannot restore")
            return emptyList()
        }
        return try {
            mgr.restorePurchases().map { it.productId }
        } catch (e: Exception) {
            Log.error("BillingModule.restorePurchases failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Java-compatible `CompletableFuture` adapter for [restorePurchases].
     */
    @JvmName("restorePurchasesAsync")
    fun restorePurchasesAsync(): CompletableFuture<List<String>> =
        scope.future { restorePurchases() }

    /**
     * SPEC-070-A A.30 — typed suspend entitlements fetch.
     *
     * Returns the current cached active entitlements as `Entitlement`
     * data classes. Suspend so future implementations can refresh from
     * the network without a breaking change.
     */
    suspend fun getEntitlements(): List<Entitlement> {
        val mgr = manager ?: return emptyList()
        return mgr.entitlementCache.getAll()
    }

    /** Java-friendly overload — returns a [CompletableFuture]. */
    fun getEntitlementsFuture(): CompletableFuture<List<Entitlement>> =
        scope.future { getEntitlements() }

    /**
     * Register a callback for entitlement changes.
     */
    fun onEntitlementsChanged(callback: (List<Entitlement>) -> Unit) {
        manager?.entitlementCache?.addChangeListener(callback)
    }

    /**
     * Set a delegate to receive billing lifecycle callbacks (purchases, failures, restores).
     */
    fun setDelegate(delegate: AppDNABillingDelegate?) {
        this.billingListener = delegate
    }

    internal var billingListener: AppDNABillingDelegate? = null

    /**
     * SPEC-070-A H.24 — cancel the wrapper's coroutine scope so any pending
     * `purchaseFuture()` / `getProductsFuture()` / restore continuation is
     * surfaced as cancellation rather than leaking. The owning
     * [NativeBillingManager] is destroyed separately by [AppDNA.shutdown].
     */
    internal fun shutdown() {
        scope.cancel()
        billingListener = null
    }
}

/** Thrown by [BillingModule.purchase] when the user cancels. */
class PurchaseCancelledException(val productId: String) : RuntimeException("Purchase cancelled for product: $productId")

/** Thrown by [BillingModule.purchase] when the purchase enters PENDING state (e.g. parental approval). */
class PurchasePendingException(val productId: String) : RuntimeException("Purchase pending for product: $productId")

/** Thrown by [BillingModule.purchase] when Google Play returns an error. */
class PurchaseFailedException(val productId: String, val error: String) : RuntimeException("Purchase failed for $productId: $error")

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

    /**
     * SPEC-401 Fix 1C — host opt-out for SDK auto-dismiss-on-restore-success.
     *
     * When set to `true`, the next successful Restore tap on a presented
     * paywall will fire `onPaywallRestoreCompleted` to the delegate as
     * usual, but the SDK will NOT auto-finish the PaywallActivity. The
     * host owns dismissal in this case (typical pattern: show a
     * "Restored — tap continue when ready" overlay, then call
     * `activity.finish()` from a button tap).
     *
     * One-shot: PaywallManager.handleRestore reads + clears this flag
     * each time it processes a restore. After the next restore (success
     * or failure), the flag resets to `false` so subsequent paywall
     * presentations get the default auto-dismiss behavior.
     *
     * Mirrors iOS `AppDNA.paywall.skipNextAutoDismissOnRestore`.
     */
    @Volatile
    var skipNextAutoDismissOnRestore: Boolean = false

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
    /**
     * Public delegate exposed for hosts that read it directly. The
     * source-of-truth is `MessageManager.delegate` — this field is kept
     * in lockstep via `setDelegate(...)` so callers querying
     * `AppDNA.inAppMessages` see the same instance.
     */
    var delegate: AppDNAInAppMessageDelegate? = null
        private set

    /** SPEC-070-A A.11: Temporarily suppress display. Forwards to MessageManager. */
    fun suppressDisplay(suppress: Boolean) {
        AppDNA.messageManager?.suppressDisplay(suppress)
    }

    /** SPEC-070-A A.11: Set a delegate for in-app message lifecycle events. */
    fun setDelegate(delegate: AppDNAInAppMessageDelegate?) {
        this.delegate = delegate
        AppDNA.messageManager?.delegate = delegate
    }

    /**
     * SPEC-070-C D10 — set the OPTIONAL async `shouldShowMessage` wrapper-veto.
     * Forwards to `MessageManager.asyncShouldShowMessage`, which awaits it (in
     * addition to the sync delegate veto) before presenting. Null clears it.
     */
    fun setAsyncShouldShowMessage(veto: (suspend (String) -> Boolean)?) {
        AppDNA.messageManager?.asyncShouldShowMessage = veto
    }
}

/**
 * Surveys module namespace.
 *
 * SPEC-070-A B.4 — `surveyListener` is `internal` (was private) so
 * `SurveyManager` can fan out lifecycle callbacks to the host's registered
 * delegate. Mirrors the `BillingModule.billingListener` shape.
 */
class SurveysModule internal constructor() {
    internal var manager: SurveyManager? = null
    internal var surveyListener: AppDNASurveyDelegate? = null

    /** Present a specific survey. */
    fun present(surveyId: String) { manager?.present(surveyId) }

    /** Set a delegate for survey lifecycle events. */
    fun setDelegate(delegate: AppDNASurveyDelegate?) {
        this.surveyListener = delegate
    }
}

/**
 * Deep links module namespace.
 */
class DeepLinksModule internal constructor() {
    private var listener: AppDNADeepLinkDelegate? = null

    /**
     * SPEC-070-C D10 — OPTIONAL async `shouldOpen` wrapper-veto. This is a
     * NET-NEW decision point (no native deep-link veto existed). When set (the
     * Flutter plugin), [handleURL] awaits it before dispatching the deep link;
     * a `false` reply skips processing entirely. Null for native hosts →
     * dispatch synchronously exactly as before.
     */
    @Volatile
    var asyncShouldOpen: (suspend (String, Map<String, String>) -> Boolean)? = null

    /** SPEC-070-C D10 — main-thread scope for awaiting the async veto. */
    private val vetoScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

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
            // SPEC-070-C D10 — NET-NEW async `shouldOpen` veto. When set, await
            // it before processing; a `false` reply skips onDeepLinkReceived +
            // the deep_link_handled event. When null, process synchronously.
            val asyncVeto = asyncShouldOpen
            if (asyncVeto != null) {
                vetoScope.launch {
                    val allow = try {
                        asyncVeto(url, params)
                    } catch (_: Throwable) {
                        true
                    }
                    if (!allow) {
                        Log.debug("DeepLinksModule: URL vetoed by host asyncShouldOpen: $url")
                        return@launch
                    }
                    listener?.onDeepLinkReceived(url, params)
                    AppDNA.track("deep_link_handled", mapOf("url" to url))
                }
                return
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

    /**
     * SPEC-070-A I.14 — Get the assigned variant id for an experiment, or
     * `null` if the user is not eligible.  Type matches iOS
     * `getVariant(experimentId:) -> String?`.
     */
    fun getVariant(experimentId: String): String? = manager?.getVariant(experimentId)

    /**
     * SPEC-070-A I.15 — Get all active exposures as named [ai.appdna.sdk.config.ExposureEntry]
     * tuples, matching iOS's `[(experimentId: String, variant: String)]` shape.
     */
    fun getExposures(): List<ai.appdna.sdk.config.ExposureEntry> =
        manager?.getExposures() ?: emptyList()
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
    // SPEC-070-A finalization §3.2 — widen `error` to `Throwable` so hosts
    // can catch the new typed BillingError sealed class (also Throwable)
    // and Kotlin Errors. Mirrors iOS `error: Error` parity.
    fun onPurchaseFailed(productId: String, error: Throwable) {}
    fun onEntitlementsChanged(entitlements: List<Entitlement>) {}
    fun onRestoreCompleted(restoredProducts: List<String>) {}
    /**
     * SPEC-070-A H.14 — fires once when [BillingConnectionManager] gives up
     * after exhausting all reconnect slots (Play Services missing or
     * permanently broken). Hosts should hide paywalls / disable purchase UI
     * when this fires so users don't tap a dead button.
     */
    fun onBillingUnavailable() {}
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

/**
 * SPEC-070-A H.20 — delegate for SDK init lifecycle events.
 *
 * Currently surfaces only [onInitDegraded], fired once when the SDK detects
 * a recoverable startup failure (missing `google-services-appdna.json`,
 * Firebase project misconfiguration, etc.). Hosts can use this to:
 *   - log to their crash reporter so misconfigured production apps don't go
 *     unnoticed,
 *   - hide features that depend on remote config when the SDK is degraded,
 *   - render an in-app banner for QA builds.
 *
 * The error throwable is also available synchronously via
 * [ai.appdna.sdk.AppDNA.lastInitError] for consumers that read it lazily.
 */
interface AppDNAInitDelegate {
    /**
     * Called when the SDK detects a recoverable init failure. May fire on
     * the main thread; do not block.
     */
    fun onInitDegraded(reason: Throwable) {}
}
