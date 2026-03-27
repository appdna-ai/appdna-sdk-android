package ai.appdna.sdk

import android.app.Activity
import android.content.Context
import ai.appdna.sdk.config.ExperimentManager
import ai.appdna.sdk.config.FeatureFlagManager
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.deeplinks.DeferredDeepLink
import ai.appdna.sdk.deeplinks.DeferredDeepLinkManager
import ai.appdna.sdk.feedback.SurveyConfig
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventSchema
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.feedback.SurveyManager
import ai.appdna.sdk.integrations.PushTokenManager
import ai.appdna.sdk.integrations.RevenueCatBridge
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate
import ai.appdna.sdk.onboarding.OnboardingFlowManager
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate
import ai.appdna.sdk.paywalls.PaywallContext
import ai.appdna.sdk.paywalls.PaywallManager
import ai.appdna.sdk.storage.LocalStorage
import ai.appdna.sdk.webentitlements.WebEntitlement
import ai.appdna.sdk.webentitlements.WebEntitlementManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import org.json.JSONObject
import androidx.compose.runtime.Composable

/**
 * Main entry point for the AppDNA Android SDK.
 * All public methods are thread-safe.
 */
object AppDNA {

    /** SDK version string. */
    const val sdkVersion = "1.0.8"

    // Module namespaces (v1.0)
    /** Push notification module. */
    @JvmStatic val push = PushModule()
    /** Billing module. */
    @JvmStatic val billing = BillingModule()
    /** Onboarding module. */
    @JvmStatic val onboarding = ai.appdna.sdk.OnboardingModule()
    /** Paywall module. */
    @JvmStatic val paywall = ai.appdna.sdk.PaywallModule()
    /** Remote config module. */
    @JvmStatic val remoteConfig = ai.appdna.sdk.RemoteConfigModule()
    /** Feature flags module. */
    @JvmStatic val features = ai.appdna.sdk.FeaturesModule()
    /** In-app messages module. */
    @JvmStatic val inAppMessages = InAppMessagesModule()
    /** Surveys module. */
    @JvmStatic val surveys = ai.appdna.sdk.SurveysModule()
    /** Deep links module. */
    @JvmStatic val deepLinks = DeepLinksModule()
    /** Experiments module. */
    @JvmStatic val experiments = ai.appdna.sdk.ExperimentsModule()

    // MARK: - Custom View Registry (SPEC-089d AC-026)

    /**
     * Registry of developer-provided Composable factories keyed by `view_key`.
     * Used by the `custom_view` content block to render developer escape-hatch views.
     */
    @JvmStatic
    val registeredCustomViews: MutableMap<String, @Composable (Map<String, Any>) -> Unit> = mutableMapOf()

    /**
     * Register a custom Composable factory for use in onboarding content blocks.
     * @param key The `view_key` value from the block config.
     * @param factory A composable factory that receives the block's custom config map.
     */
    @JvmStatic
    fun registerCustomView(key: String, factory: @Composable (Map<String, Any>) -> Unit) {
        registeredCustomViews[key] = factory
    }

    /** Current config bundle version reported in events. */
    @JvmStatic var currentBundleVersion: Int = 0
        internal set

    /**
     * Firestore instance used by the SDK.
     * Uses a secondary Firebase app ("appdna") if google-services-appdna.json is found in assets,
     * otherwise falls back to the default Firebase app's Firestore instance.
     */
    internal var firestoreDB: FirebaseFirestore? = null
        private set

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
    private var paywallManager: PaywallManager? = null
    private var onboardingFlowManager: OnboardingFlowManager? = null
    private var surveyManager: SurveyManager? = null
    private var webEntitlementManager: WebEntitlementManager? = null
    private var deferredDeepLinkManager: DeferredDeepLinkManager? = null
    // SPEC-067: Scale Layer 1 components
    private var eventDatabase: ai.appdna.sdk.storage.EventDatabase? = null
    private var connectivityMonitor: ai.appdna.sdk.network.ConnectivityMonitor? = null
    private var appContext: Context? = null
    private var bootstrapOrgId: String? = null
    private var bootstrapAppId: String? = null

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

            // Configure Firebase for Firestore config access.
            // Priority: secondary app from google-services-appdna.json in assets > default FirebaseApp.
            val secondaryOptions = loadSecondaryFirebaseOptions(context)
            if (secondaryOptions != null) {
                val existingApp = try { FirebaseApp.getInstance("appdna") } catch (_: Exception) { null }
                if (existingApp == null) {
                    FirebaseApp.initializeApp(context, secondaryOptions, "appdna")
                }
                val secondaryApp = try { FirebaseApp.getInstance("appdna") } catch (_: Exception) { null }
                if (secondaryApp != null) {
                    firestoreDB = FirebaseFirestore.getInstance(secondaryApp)
                    Log.info("Using secondary Firebase app 'appdna' for Firestore (google-services-appdna.json)")
                }
            } else if (FirebaseApp.getApps(context).isNotEmpty()) {
                firestoreDB = FirebaseFirestore.getInstance()
                Log.info("Using default Firebase app for Firestore")
            } else {
                Log.error("Firebase not configured. Either add google-services-appdna.json to assets or configure Firebase via google-services.json. Remote config (paywalls, experiments, flags) will not work. See docs: https://docs.appdna.ai/sdks/android/installation")
            }

            this.appContext = context.applicationContext
            val appContext = this.appContext!!
            val appVersion = try {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0.0.0"
            } catch (_: Exception) { "0.0.0" }

            // 1. Initialize storage
            val storage = LocalStorage(appContext)

            // SPEC-088: Initialize cross-module session data store
            ai.appdna.sdk.core.SessionDataStore.initialize(appContext)

            // 2. Initialize identity
            val identityMgr = IdentityManager(storage)
            this.identityManager = identityMgr

            // 3. Initialize networking
            val client = ApiClient(apiKey, environment)
            this.apiClient = client

            // 4. Initialize event system
            val tracker = EventTracker(identityMgr, appVersion)
            this.eventTracker = tracker

            // SPEC-067: Initialize EventDatabase (SQLite) and ConnectivityMonitor
            val eventDb = ai.appdna.sdk.storage.EventDatabase(appContext)
            eventDb.migrateFromSharedPreferences(storage) // One-time migration
            this.eventDatabase = eventDb

            val connMonitor = ai.appdna.sdk.network.ConnectivityMonitor(appContext)
            this.connectivityMonitor = connMonitor

            val eq = EventQueue(
                apiClient = client,
                eventDatabase = eventDb,
                connectivityMonitor = connMonitor,
                batchSize = options.batchSize,
                flushInterval = options.flushInterval
            )
            this.eventQueue = eq
            tracker.setEventQueue(eq)

            // 5. Initialize push token manager (v0.4 SPEC-030: backend registration)
            this.pushTokenManager = PushTokenManager(context, storage, tracker, client)
            push.manager = this.pushTokenManager

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

            // Paywall & onboarding managers
            this.paywallManager = PaywallManager(
                remoteConfigManager = remoteCfg,
                eventTracker = tracker
            )

            this.onboardingFlowManager = OnboardingFlowManager(
                remoteConfigManager = remoteCfg,
                eventTracker = tracker
            )

            // v0.3 managers
            this.surveyManager = SurveyManager(appContext, tracker, client)
            this.webEntitlementManager = WebEntitlementManager(tracker, appContext)

            // Wire survey config updates from RemoteConfigManager to SurveyManager
            remoteCfg.surveyUpdateHandler = { rawSurveys ->
                val parsed = rawSurveys.mapNotNull { (key, data) ->
                    SurveyConfig.fromMap(data)?.let { key to it }
                }.toMap()
                surveyManager?.updateConfigs(parsed)
            }

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

        // Start web entitlement observer for this user (v0.3)
        val orgId = bootstrapOrgId
        val appId = bootstrapAppId
        if (orgId != null && appId != null) {
            webEntitlementManager?.startObserving(orgId, appId, userId)
        }
    }

    /**
     * Get current user traits (for audience evaluation).
     */
    fun getUserTraits(): Map<String, Any> {
        return identityManager?.currentIdentity?.traits ?: emptyMap()
    }

    /**
     * Clear user identity (keeps anonymous ID).
     */
    fun reset() {
        identityManager?.reset()
        experimentManager?.resetExposures()
        surveyManager?.resetSession()
        webEntitlementManager?.stopObserving()
        Log.info("Identity reset")
    }

    // MARK: - Public API: Events

    /**
     * Track a custom event.
     */
    fun track(event: String, properties: Map<String, Any>? = null) {
        eventTracker?.track(event, properties)
        // Evaluate surveys on every tracked event (v0.3)
        surveyManager?.onEvent(event, properties)
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
     * SPEC-067: Force an immediate config refresh, bypassing the cache TTL.
     */
    @JvmStatic
    fun forceRefreshConfig() {
        remoteConfigManager?.forceRefresh()
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

    // MARK: - Public API: Paywalls

    /**
     * Present a paywall by ID from the given Activity.
     * Fetches the paywall config from remote config and presents the paywall UI.
     *
     * @param activity The Activity to present from.
     * @param id The paywall ID (matching Firestore config).
     * @param context Optional paywall context (placement, experiment, variant).
     * @param listener Optional listener for paywall lifecycle events.
     */
    fun presentPaywall(
        activity: Activity,
        id: String,
        context: PaywallContext? = null,
        listener: AppDNAPaywallDelegate? = null
    ) {
        paywallManager?.present(
            activity = activity,
            id = id,
            context = context,
            listener = listener
        ) ?: Log.warning("Cannot present paywall — SDK not configured")
    }

    // MARK: - Public API: Onboarding (v0.2)

    /**
     * Present an onboarding flow by ID. Returns false if config is unavailable.
     * If flowId is null, the active flow from remote config is used.
     *
     * @param activity The Activity to present from.
     * @param flowId Optional flow ID. If null, the active flow is used.
     * @param listener Optional listener for onboarding lifecycle events.
     * @return true if the flow was presented, false if config was not found.
     */
    fun presentOnboarding(
        activity: Activity,
        flowId: String? = null,
        listener: AppDNAOnboardingDelegate? = null
    ): Boolean {
        return onboardingFlowManager?.present(
            activity = activity,
            flowId = flowId,
            listener = listener
        ) ?: run {
            Log.warning("Cannot present onboarding — SDK not configured")
            false
        }
    }

    // MARK: - Public API: Server-Driven Screens (SPEC-089c)

    /** Show a server-driven screen by ID. */
    fun showScreen(screenId: String, callback: ((ai.appdna.sdk.screens.ScreenResult) -> Unit)? = null) {
        ai.appdna.sdk.screens.ScreenManager.shared.showScreen(screenId, callback)
    }

    /** Show a server-driven multi-screen flow by ID. */
    fun showFlow(flowId: String, callback: ((ai.appdna.sdk.screens.FlowResult) -> Unit)? = null) {
        ai.appdna.sdk.screens.ScreenManager.shared.showFlow(flowId, callback)
    }

    /** Dismiss the currently presented server-driven screen or flow. */
    fun dismissScreen() {
        ai.appdna.sdk.screens.ScreenManager.shared.dismissScreen()
    }

    /** Enable navigation interception for server-driven screens. */
    fun enableNavigationInterception(forScreens: List<String>? = null) {
        ai.appdna.sdk.screens.ScreenManager.shared.enableNavigationInterception(forScreens)
    }

    /** Disable navigation interception. */
    fun disableNavigationInterception() {
        ai.appdna.sdk.screens.ScreenManager.shared.disableNavigationInterception()
    }

    /** Check if analytics consent is granted. */
    fun isConsentGranted(): Boolean {
        return eventTracker?.isConsentGranted ?: true
    }

    /** Shorthand to show a paywall by ID (used by screen action routing). */
    fun showPaywall(id: String) {
        // Route through existing paywall presentation
        Log.info("showPaywall($id) triggered from SDUI screen")
    }

    /** Shorthand to show a survey by ID (used by screen action routing). */
    fun showSurvey(id: String) {
        surveyManager?.present(surveyId = id)
    }

    // MARK: - Public API: Push Token + Push Tracking (v0.4 / SPEC-030)

    /**
     * Set the FCM push token. Call from FirebaseMessagingService.onNewToken().
     * This registers the token with the backend for direct push delivery.
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

    /**
     * Track that a push notification was delivered.
     * Call from FirebaseMessagingService.onMessageReceived().
     */
    fun trackPushDelivered(pushId: String) {
        pushTokenManager?.trackDelivered(pushId)
    }

    /**
     * Track that a push notification was tapped.
     * Call from the tap intent handler.
     */
    fun trackPushTapped(pushId: String, action: String? = null) {
        pushTokenManager?.trackTapped(pushId, action)
    }

    /**
     * Called when FCM token refreshes. Re-registers with backend.
     */
    fun onNewPushToken(token: String) {
        pushTokenManager?.onNewToken(token)
    }

    // MARK: - Public API: Session Data (SPEC-088)

    /**
     * Store a key-value pair in the cross-module session data store.
     * Available to all modules via `{{session.key}}` template variables.
     */
    @JvmStatic
    fun setSessionData(key: String, value: Any) {
        ai.appdna.sdk.core.SessionDataStore.instance?.setSessionData(key, value)
    }

    /**
     * Retrieve a session data value by key.
     */
    @JvmStatic
    fun getSessionData(key: String): Any? {
        return ai.appdna.sdk.core.SessionDataStore.instance?.getSessionData(key)
    }

    /**
     * Clear all app-defined session data.
     */
    @JvmStatic
    fun clearSessionData() {
        ai.appdna.sdk.core.SessionDataStore.instance?.clearSessionData()
    }

    // MARK: - Public API: Privacy

    /**
     * Set analytics consent. When false, events are silently dropped.
     */
    fun setConsent(analytics: Boolean) {
        eventTracker?.setConsent(analytics)
        Log.info("Consent updated: analytics=$analytics")
    }

    // MARK: - Public API: Log Level

    /**
     * Set the SDK log level at runtime.
     * Valid values: "none", "error", "warning", "info", "debug".
     */
    @JvmStatic
    fun setLogLevel(level: String) {
        val logLevel = when (level.lowercase()) {
            "none" -> LogLevel.NONE
            "error" -> LogLevel.ERROR
            "warning" -> LogLevel.WARNING
            "info" -> LogLevel.INFO
            "debug" -> LogLevel.DEBUG
            else -> {
                Log.warning("Unknown log level '$level' — defaulting to INFO")
                LogLevel.INFO
            }
        }
        Log.level = logLevel
        Log.info("Log level set to ${logLevel.name}")
    }

    // MARK: - Public API: Web Entitlements (v0.3)

    /**
     * Get the current web subscription entitlement.
     */
    val webEntitlement: WebEntitlement?
        get() = webEntitlementManager?.currentEntitlement

    /**
     * Register a listener for web entitlement changes.
     */
    fun onWebEntitlementChanged(listener: (WebEntitlement?) -> Unit) {
        webEntitlementManager?.addChangeListener(listener)
    }

    // MARK: - Public API: Deferred Deep Links (v0.3)

    /**
     * Check for a deferred deep link on first launch.
     * Call after configure() and onReady.
     */
    fun checkDeferredDeepLink(callback: (DeferredDeepLink?) -> Unit) {
        deferredDeepLinkManager?.checkDeferredDeepLink(callback) ?: callback(null)
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

    // MARK: - Internal accessors (used by onboarding hooks, SPEC-088)

    internal fun getCurrentUserId(): String? {
        return identityManager?.currentIdentity?.userId
            ?: identityManager?.currentIdentity?.anonId
    }

    internal fun getCurrentAppId(): String? {
        return bootstrapAppId
    }

    internal fun getRemoteConfigFlag(key: String): String? {
        return remoteConfigManager?.getConfig(key) as? String
    }

    /** Internal reference to identity for TemplateEngine (SPEC-088). */
    internal fun getIdentityRef(): DeviceIdentity? {
        return identityManager?.currentIdentity
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

                synchronized(this@AppDNA) {
                    bootstrapOrgId = orgId
                    bootstrapAppId = appId

                    // v0.3: Create deferred deep link manager
                    appContext?.let { ctx ->
                        deferredDeepLinkManager = DeferredDeepLinkManager(ctx, orgId, appId, tracker)
                    }

                    // Start web entitlement observer if user is already identified
                    identityManager?.currentIdentity?.userId?.let { userId ->
                        webEntitlementManager?.startObserving(orgId, appId, userId)
                    }
                }

                // Fetch Firestore configs
                remoteCfg.fetchConfigs()
            } catch (e: Exception) {
                Log.error("Bootstrap parse error: ${e.message}")
            }
        } else {
            Log.warning("Bootstrap failed — using cached config")
        }

        // Wire module namespaces (v1.0)
        synchronized(this@AppDNA) {
            onboarding.manager = onboardingFlowManager
            paywall.manager = paywallManager
            remoteConfig.manager = remoteConfigManager
            features.manager = featureFlagManager
            surveys.manager = surveyManager
            experiments.manager = experimentManager
        }

        // Load bundled config (v1.0 offline-first)
        loadConfigBundle()

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

    // MARK: - Config Bundle (v1.0 offline-first)

    /**
     * Load config from bundle embedded in app assets.
     * Priority: remote (already fetched) > cached > bundled.
     */
    private fun loadConfigBundle() {
        try {
            val ctx = appContext ?: return
            val inputStream = ctx.assets.open("appdna-config.json")
            val json = inputStream.bufferedReader().use { it.readText() }
            val config = JSONObject(json)
            val bundleVersion = config.optInt("bundle_version", 0)
            currentBundleVersion = bundleVersion
            Log.info("Loaded bundled config (version $bundleVersion)")
        } catch (_: Exception) {
            Log.debug("No bundled config found at appdna-config.json — using remote/cached only")
        }
    }

    /**
     * Load FirebaseOptions from google-services-appdna.json in app assets.
     * Returns null if the file is not found or cannot be parsed.
     */
    private fun loadSecondaryFirebaseOptions(context: Context): FirebaseOptions? {
        return try {
            val inputStream = context.assets.open("google-services-appdna.json")
            val json = inputStream.bufferedReader().use { it.readText() }
            val config = JSONObject(json)

            // google-services.json format has project_info and client arrays
            val projectInfo = config.getJSONObject("project_info")
            val projectId = projectInfo.getString("project_id")
            val storageBucket = projectInfo.optString("storage_bucket", "$projectId.appspot.com")

            // Find the first client entry
            val clients = config.getJSONArray("client")
            if (clients.length() == 0) return null
            val client = clients.getJSONObject(0)

            val clientInfo = client.getJSONObject("client_info")
            val mobileSdkAppId = clientInfo.getString("mobilesdk_app_id")

            // API key from api_key array
            val apiKeys = client.getJSONArray("api_key")
            val apiKey = if (apiKeys.length() > 0) {
                apiKeys.getJSONObject(0).getString("current_key")
            } else null

            val builder = FirebaseOptions.Builder()
                .setProjectId(projectId)
                .setApplicationId(mobileSdkAppId)
                .setStorageBucket(storageBucket)

            if (apiKey != null) {
                builder.setApiKey(apiKey)
            }

            builder.build()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Shut down the SDK and release resources. Call from Application.onTerminate().
     */
    fun shutdown() {
        synchronized(this) {
            eventQueue?.shutdown()
            // SPEC-067: Schedule background upload for remaining events
            val ctx = appContext
            val key = apiKey
            val db = eventDatabase
            if (ctx != null && key != null && db != null) {
                ai.appdna.sdk.background.EventUploadWorker.scheduleIfNeeded(
                    ctx, key, environment.baseUrl, db
                )
            }
            connectivityMonitor?.shutdown()
            webEntitlementManager?.stopObserving()
            scope.cancel()
            isConfigured = false
            Log.info("SDK shut down")
        }
    }
}
