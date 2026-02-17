package ai.appdna.sdk

import android.content.Context
import ai.appdna.sdk.config.ExperimentManager
import ai.appdna.sdk.config.FeatureFlagManager
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventSchema
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.integrations.PushTokenManager
import ai.appdna.sdk.integrations.RevenueCatBridge
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.LocalStorage
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Main entry point for the AppDNA Android SDK.
 * All public methods are thread-safe.
 */
object AppDNA {

    /** SDK version string. */
    const val sdkVersion = "0.2.0"

    // Internal managers
    private var apiKey: String? = null
    private var environment: Environment = Environment.PRODUCTION
    private var options: AppDNAOptions = AppDNAOptions()

    private var apiClient: ApiClient? = null
    private var identityManager: IdentityManager? = null
    private var eventTracker: EventTracker? = null
    private var eventQueue: EventQueue? = null
    private var remoteConfigManager: RemoteConfigManager? = null
    private var featureFlagManager: FeatureFlagManager? = null
    private var experimentManager: ExperimentManager? = null
    private var pushTokenManager: PushTokenManager? = null
    private var revenueCatBridge: RevenueCatBridge? = null

    private var isConfigured = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val readyCallbacks = mutableListOf<() -> Unit>()

    // MARK: - Public API: Initialization

    /**
     * Configure the SDK. Call once at Application.onCreate().
     */
    fun configure(
        context: Context,
        apiKey: String,
        environment: Environment = Environment.PRODUCTION,
        options: AppDNAOptions = AppDNAOptions()
    ) {
        synchronized(this) {
            if (isConfigured) {
                Log.warning("AppDNA.configure() called multiple times — ignoring")
                return
            }

            this.apiKey = apiKey
            this.environment = environment
            this.options = options
            Log.level = options.logLevel

            Log.info("Configuring AppDNA SDK v$sdkVersion (${environment.name})")

            val appContext = context.applicationContext
            val appVersion = try {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0.0.0"
            } catch (_: Exception) { "0.0.0" }

            // 1. Initialize storage
            val storage = LocalStorage(appContext)

            // 2. Initialize identity
            val identityMgr = IdentityManager(storage)
            this.identityManager = identityMgr

            // 3. Initialize networking
            val client = ApiClient(apiKey, environment)
            this.apiClient = client

            // 4. Initialize event system
            val tracker = EventTracker(identityMgr, appVersion)
            this.eventTracker = tracker

            val eq = EventQueue(
                apiClient = client,
                storage = storage,
                batchSize = options.batchSize,
                flushInterval = options.flushInterval
            )
            this.eventQueue = eq
            tracker.setEventQueue(eq)

            // 5. Initialize push token manager
            this.pushTokenManager = PushTokenManager(storage, tracker)

            // 6. Initialize config managers (Firestore)
            val remoteCfg = RemoteConfigManager(
                firestorePath = null, // Set after bootstrap
                storage = storage,
                configTTL = options.configTTL
            )
            this.remoteConfigManager = remoteCfg

            this.featureFlagManager = FeatureFlagManager(remoteCfg)

            this.experimentManager = ExperimentManager(
                remoteConfigManager = remoteCfg,
                identityManager = identityMgr,
                eventTracker = tracker
            )

            // 7. Bootstrap (fetch orgId/appId, then Firestore configs)
            scope.launch {
                performBootstrap(client, remoteCfg, tracker)
            }
        }
    }

    // MARK: - Public API: Identity

    /**
     * Link the anonymous device to a known user.
     */
    fun identify(userId: String, traits: Map<String, Any>? = null) {
        identityManager?.identify(userId, traits)
    }

    /**
     * Clear user identity (keeps anonymous ID).
     */
    fun reset() {
        identityManager?.reset()
        experimentManager?.resetExposures()
        Log.info("Identity reset")
    }

    // MARK: - Public API: Events

    /**
     * Track a custom event.
     */
    fun track(event: String, properties: Map<String, Any>? = null) {
        eventTracker?.track(event, properties)
    }

    /**
     * Force flush all queued events immediately.
     */
    fun flush() {
        eventQueue?.flush()
    }

    // MARK: - Public API: Remote Config

    /**
     * Get a remote config value by key.
     */
    fun getRemoteConfig(key: String): Any? {
        return remoteConfigManager?.getConfig(key)
    }

    /**
     * Check if a feature flag is enabled.
     */
    fun isFeatureEnabled(flag: String): Boolean {
        return featureFlagManager?.isEnabled(flag) ?: false
    }

    // MARK: - Public API: Experiments

    /**
     * Get the variant assignment for an experiment.
     * Exposure is auto-tracked on first call per session.
     */
    fun getExperimentVariant(experimentId: String): String? {
        return experimentManager?.getVariant(experimentId)?.id
    }

    /**
     * Check if the user is in a specific variant.
     */
    fun isInVariant(experimentId: String, variantId: String): Boolean {
        return experimentManager?.isInVariant(experimentId, variantId) ?: false
    }

    /**
     * Get a specific config value from the assigned variant's payload.
     */
    fun getExperimentConfig(experimentId: String, key: String): Any? {
        return experimentManager?.getExperimentConfig(experimentId, key)
    }

    // MARK: - Public API: Push Token

    /**
     * Set the FCM push token. Call from FirebaseMessagingService.onNewToken().
     */
    fun setPushToken(token: String) {
        pushTokenManager?.setPushToken(token)
    }

    /**
     * Report push permission status.
     */
    fun setPushPermission(granted: Boolean) {
        pushTokenManager?.setPushPermission(granted)
    }

    // MARK: - Public API: Privacy

    /**
     * Set analytics consent. When false, events are silently dropped.
     */
    fun setConsent(analytics: Boolean) {
        eventTracker?.setConsent(analytics)
        Log.info("Consent updated: analytics=$analytics")
    }

    // MARK: - v0.2 Stubs (deferred to v0.3)

    /**
     * Present a paywall. NOT available on Android v0.2 — logs a warning.
     */
    fun presentPaywall(id: String) {
        Log.warning("presentPaywall() is not available on Android SDK v0.2 — deferred to v0.3")
    }

    /**
     * Present an onboarding flow. NOT available on Android v0.2 — logs a warning.
     */
    fun presentOnboarding(flowId: String? = null) {
        Log.warning("presentOnboarding() is not available on Android SDK v0.2 — deferred to v0.3")
    }

    // MARK: - Public API: Ready callback

    /**
     * Register a callback that fires when the SDK is fully initialized.
     */
    fun onReady(callback: () -> Unit) {
        synchronized(this) {
            if (isConfigured) {
                callback()
            } else {
                readyCallbacks.add(callback)
            }
        }
    }

    // MARK: - Internal bootstrap

    private suspend fun performBootstrap(
        client: ApiClient,
        remoteCfg: RemoteConfigManager,
        tracker: EventTracker
    ) {
        val result = client.get("/api/v1/sdk/bootstrap")
        if (result != null) {
            try {
                val orgId = result.getString("orgId")
                val appId = result.getString("appId")
                val firestorePath = result.getString("firestorePath")

                Log.info("Bootstrap successful: orgId=$orgId, appId=$appId")

                // Fetch Firestore configs
                remoteCfg.fetchConfigs()
            } catch (e: Exception) {
                Log.error("Bootstrap parse error: ${e.message}")
            }
        } else {
            Log.warning("Bootstrap failed — using cached config")
        }

        // Mark ready
        synchronized(this@AppDNA) {
            isConfigured = true
            tracker.track("sdk_initialized")
            Log.info("SDK ready")

            val callbacks = ArrayList(readyCallbacks)
            readyCallbacks.clear()
            for (cb in callbacks) {
                cb()
            }
        }
    }

    /**
     * Shut down the SDK and release resources. Call from Application.onTerminate().
     */
    fun shutdown() {
        eventQueue?.shutdown()
        scope.cancel()
    }
}
