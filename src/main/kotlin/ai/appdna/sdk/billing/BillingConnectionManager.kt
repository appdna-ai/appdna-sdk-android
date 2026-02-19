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

        billingClient = BillingClient.newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
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
     */
    private fun scheduleRetry() {
        val delay = min(baseRetryDelay * (1L shl retryAttempt), maxRetryDelay)
        retryAttempt++
        Log.debug("Scheduling billing reconnection in ${delay}ms (attempt $retryAttempt)")

        scope.launch {
            delay(delay)
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
            // Ensure we're attempting to connect
            if (billingClient != null && !isConnected) {
                startConnection()
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
            // Ensure connection attempt is in progress
            if (!isConnected) {
                startConnection()
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
