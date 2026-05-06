package ai.appdna.sdk.billing

import android.content.Context
import ai.appdna.sdk.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.*
import kotlin.math.min

/**
 * Manages the Google Play Billing connection lifecycle with exponential backoff retry.
 *
 * The BillingClient must be connected before any billing operations can be performed.
 * This manager handles:
 * - Initial connection establishment
 * - Automatic reconnection on disconnection with exponential backoff
 * - Connection state tracking
 * - Graceful shutdown
 */
internal class BillingConnectionManager(
    private val context: Context,
    private val purchasesUpdatedListener: PurchasesUpdatedListener
) {
    private var billingClient: BillingClient? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var retryAttempt = 0
    private val maxRetryDelay = 60_000L // 60 seconds max
    private val baseRetryDelay = 1_000L // 1 second initial
    // Cap the bit-shift exponent. 2^16 * 1s = 65536s > maxRetryDelay anyway, so
    // beyond this we are always pinned to maxRetryDelay; preventing the shift
    // from going into Long-overflow territory.
    private val maxShiftExponent = 16
    // After this many consecutive failures we stop reconnecting on our own.
    // The next caller will re-trigger `startConnection()` via withConnectedClient.
    // Prevents the runaway loop seen on devices/emulators with no Play Services.
    private val maxAutoRetries = 10
    @Volatile
    private var retryScheduled = false

    @Volatile
    var isConnected = false
        private set

    /** Callbacks waiting for connection readiness. */
    private val pendingCallbacks = mutableListOf<(BillingClient) -> Unit>()

    /**
     * Initialize the BillingClient and start the connection.
     */
    fun initialize() {
        if (billingClient != null) {
            Log.warning("BillingConnectionManager already initialized")
            return
        }

        // SPEC-070-A A.25 — `enablePendingPurchases()` (no-arg) is deprecated in
        // billing-ktx 7+. Use the typed `PendingPurchasesParams` builder. We
        // currently only sell subscriptions and one-time products, so opt in to
        // one-time-product pending purchases. (Subscription pending state is
        // always enabled and does not need a flag.)
        billingClient = BillingClient.newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()

        Log.info("BillingClient created, starting connection...")
        startConnection()
    }

    /**
     * Start or restart the billing connection.
     */
    private fun startConnection() {
        val client = billingClient ?: return

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.info("Billing connection established")
                    isConnected = true
                    retryAttempt = 0

                    // Dispatch pending callbacks
                    synchronized(pendingCallbacks) {
                        val callbacks = ArrayList(pendingCallbacks)
                        pendingCallbacks.clear()
                        callbacks.forEach { it(client) }
                    }
                } else {
                    Log.warning("Billing setup failed: ${result.responseCode} - ${result.debugMessage}")
                    isConnected = false
                    scheduleRetry()
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.warning("Billing service disconnected")
                isConnected = false
                scheduleRetry()
            }
        })
    }

    /**
     * Schedule a reconnection attempt with exponential backoff.
     *
     * Uses a capped shift exponent so the delay never overflows Long, and
     * a hard cap on total automatic retries so a permanently broken device
     * (no Play Services) doesn't loop indefinitely.
     */
    private fun scheduleRetry() {
        if (retryAttempt >= maxAutoRetries) {
            Log.warning(
                "Billing reconnection gave up after $retryAttempt attempts. " +
                    "Will retry on next billing operation."
            )
            return
        }
        if (retryScheduled) return
        retryScheduled = true
        val safeExp = min(retryAttempt, maxShiftExponent)
        val delay = min(baseRetryDelay shl safeExp, maxRetryDelay)
        retryAttempt++
        Log.debug("Scheduling billing reconnection in ${delay}ms (attempt $retryAttempt)")

        scope.launch {
            delay(delay)
            retryScheduled = false
            if (!isConnected) {
                startConnection()
            }
        }
    }

    /**
     * Execute an operation when the billing client is connected.
     * If already connected, executes immediately. Otherwise, queues until connected.
     *
     * @param operation The operation to execute with the connected BillingClient.
     */
    fun withConnectedClient(operation: (BillingClient) -> Unit) {
        val client = billingClient
        if (client != null && isConnected) {
            operation(client)
        } else {
            synchronized(pendingCallbacks) {
                pendingCallbacks.add(operation)
            }
            // A user-initiated billing call is a fresh signal that we should
            // try again — reset the auto-retry counter so a previous "gave up"
            // state doesn't strand the new request.
            if (billingClient != null && !isConnected) {
                retryAttempt = 0
                if (!retryScheduled) startConnection()
            }
        }
    }

    /**
     * Execute a suspend operation when the billing client is connected.
     * Suspends the caller until the client is ready.
     *
     * @return The connected BillingClient, or null if not initialized.
     */
    suspend fun awaitConnectedClient(): BillingClient? {
        val client = billingClient ?: return null
        if (isConnected) return client

        return suspendCancellableCoroutine { continuation ->
            synchronized(pendingCallbacks) {
                pendingCallbacks.add { connectedClient ->
                    continuation.resume(connectedClient) {}
                }
            }
            // Ensure connection attempt is in progress (reset retry budget —
            // see withConnectedClient for rationale).
            if (!isConnected) {
                retryAttempt = 0
                if (!retryScheduled) startConnection()
            }
        }
    }

    /**
     * Get the raw BillingClient instance (may not be connected).
     */
    fun getClient(): BillingClient? = billingClient

    /**
     * End the billing connection and release resources.
     */
    fun destroy() {
        scope.cancel()
        synchronized(pendingCallbacks) {
            pendingCallbacks.clear()
        }
        billingClient?.endConnection()
        billingClient = null
        isConnected = false
        retryAttempt = 0
        Log.info("BillingConnectionManager destroyed")
    }
}
