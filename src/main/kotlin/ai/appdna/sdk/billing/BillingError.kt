package ai.appdna.sdk.billing

/**
 * Typed billing errors emitted by the SDK. Mirrors iOS
 * `Billing/NativeBillingManager.swift` `public enum BillingError`. Hosts
 * can `when` over this sealed class in `onPaywallPurchaseFailed` /
 * `onPurchaseFailed` callbacks instead of regex-matching error messages.
 *
 * SPEC-070-A finalization §3.2.
 */
sealed class BillingError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Product id was not found by Play Billing or the local catalog. */
    class ProductNotFound(val productId: String) :
        BillingError("Product not found: $productId")

    /** Server-side receipt verification failed (signature/state/refund). */
    class VerificationFailed(message: String = "Transaction verification failed", cause: Throwable? = null) :
        BillingError(message, cause)

    /** Network error during purchase or verification call. */
    class NetworkError(cause: Throwable) :
        BillingError("Network error: ${cause.message ?: cause.javaClass.simpleName}", cause)

    /** Server returned a non-2xx with a message body the SDK couldn't decode. */
    class ServerError(message: String) :
        BillingError("Server error: $message")

    /**
     * Selected billing provider (RevenueCat / Adapty) wasn't on the
     * classpath at runtime so the call could not be dispatched. iOS uses
     * this when SwiftPM extras are excluded from the host target.
     */
    class ProviderNotAvailable(message: String) :
        BillingError(message)

    /** User cancelled the Play Billing dialog (BillingResponseCode.USER_CANCELED). */
    class UserCancelled(message: String = "User cancelled the purchase") :
        BillingError(message)

    /** Pending purchase (e.g. parent approval, deferred payment). */
    class Pending(val productId: String) :
        BillingError("Purchase pending for product: $productId")
}
