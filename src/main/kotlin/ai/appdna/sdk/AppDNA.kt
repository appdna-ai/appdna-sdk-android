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
    const val sdkVersion = "1.0.60"

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
    // SPEC-070-A A.8/A.9: SessionManager + MessageManager wired in configure().
    // Snapshot of the active message catalog kept alongside the manager so the
    // configProvider lambda has a stable read path when triggers evaluate.
    internal var messageManager: ai.appdna.sdk.messages.MessageManager? = null
    internal var sessionManager: ai.appdna.sdk.core.SessionManager? = null
    @Volatile
    private var activeMessages: Map<String, ai.appdna.sdk.messages.MessageConfig> = emptyMap()
    // SPEC-203: journey-triggered pending-messages listener.
    private var pendingMessageListener: ai.appdna.sdk.messages.PendingMessageListener? = null
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

    // SPEC-070-A G.18: pre-init event buffer. Calls to track() that arrive before
    // configure() finishes wiring the EventTracker land here. Drained on first
    // setEventQueue call (after configure completes constructing the EventQueue).
    // Cap = 200; on overflow we log + drop the OLDEST so the most recent action
    // (e.g. a deep-link tap that triggered the configure call) survives.
    private const val PRE_INIT_BUFFER_CAP = 200
    private data class PreInitEvent(val name: String, val properties: Map<String, Any>?)
    private val preInitBuffer = java.util.concurrent.LinkedBlockingQueue<PreInitEvent>(PRE_INIT_BUFFER_CAP)

    // SPEC-070-A G.17: most recent screen name observed by NavigationInterceptor
    // / ScreenManager / Compose hosts. Surfaced into every event envelope as
    // `context.screen` for SPEC-086 zero-code attribution. Updated through
    // [notifyScreenAppeared] (already public) so no extra API required.
    @Volatile private var lastScreenName: String? = null

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

            // Validate API key format
            if (!apiKey.startsWith("adn_live_") && !apiKey.startsWith("adn_test_")) {
                Log.error("API key format invalid. Keys must start with 'adn_live_' (production) or 'adn_test_' (sandbox). Got: ${apiKey.take(10)}...")
            }

            // SPEC-070-A A.31a — Firebase asset I/O (loadSecondaryFirebaseOptions
            // reads google-services-appdna.json and parses JSON) is moved off
            // the configure-thread (Main on Application.onCreate()) into the
            // bootstrap coroutine on Dispatchers.IO. Until bootstrap finishes
            // `firestoreDB` stays null — every consumer already null-checks it
            // (`firestoreDB?.collection(...)`), so the lazy init is safe.

            this.appContext = context.applicationContext
            val appContext = this.appContext ?: run {
                Log.error("AppDNA not initialized — call configure() with valid context")
                return
            }
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
            // SPEC-070-A G.10: tag every emitted event with the active SDK
            // environment ("production" / "sandbox") so ingest can route
            // test-key traffic to the sandbox dataset.
            val envTag = environment.name.lowercase()
            val tracker = EventTracker(identityMgr, appVersion, envTag)
            this.eventTracker = tracker

            // SPEC-067: Initialize EventDatabase (SQLite) and ConnectivityMonitor
            val eventDb = ai.appdna.sdk.storage.EventDatabase(appContext)
            eventDb.migrateFromSharedPreferences(storage) // One-time migration
            this.eventDatabase = eventDb

            val connMonitor = ai.appdna.sdk.network.ConnectivityMonitor(appContext)
            this.connectivityMonitor = connMonitor

            // SPEC-070-A A.18: pass the application context + a background scheduler
            // so EventQueue can register a ProcessLifecycleOwner observer and hand
            // off remaining events to WorkManager when the app is backgrounded.
            val bgScheduler = object : ai.appdna.sdk.events.BackgroundUploadScheduler {
                override fun scheduleIfNeeded() {
                    try {
                        ai.appdna.sdk.background.EventUploadWorker.scheduleIfNeeded(
                            appContext, apiKey, environment.baseUrl, eventDb
                        )
                    } catch (e: Throwable) {
                        Log.warning("Background upload schedule failed: ${e.message}")
                    }
                }
            }

            val eq = EventQueue(
                apiClient = client,
                eventDatabase = eventDb,
                connectivityMonitor = connMonitor,
                batchSize = options.batchSize,
                flushInterval = options.flushInterval,
                context = appContext,
                backgroundUploadScheduler = bgScheduler
            )
            this.eventQueue = eq
            tracker.setEventQueue(eq)

            // SPEC-070-A G.18: drain any track() calls that arrived before the
            // EventTracker was wired up. We snapshot+drain so concurrent
            // late-arriving calls won't block on the lock.
            val drained = ArrayList<PreInitEvent>(preInitBuffer.size)
            preInitBuffer.drainTo(drained)
            if (drained.isNotEmpty()) {
                Log.info { "Draining ${drained.size} pre-init buffered event(s) into tracker" }
                for (entry in drained) {
                    tracker.track(entry.name, entry.properties)
                }
            }

            // SPEC-070-A G.17: Wire the screen-name source so every event
            // envelope can pick up `context.screen` for zero-code attribution.
            // The latest screen is tracked via [updateCurrentScreen] which
            // NavigationInterceptor + ScreenManager call as the user navigates
            // (wired in a follow-up item; field stays null safely until then).
            tracker.setScreenProvider { lastScreenName }

            // 5. Initialize push token manager (v0.4 SPEC-030: backend registration)
            this.pushTokenManager = PushTokenManager(context, storage, tracker, client)
            push.manager = this.pushTokenManager

            // 6. Initialize config managers (Firestore)
            val remoteCfg = RemoteConfigManager(
                firestorePath = null, // Set after bootstrap
                storage = storage,
                configTTL = options.configTTL
            )
            // SPEC-070-A G.4: wire EventTracker so RemoteConfigManager.fetchConfigs()
            // can emit a `config_fetched` event after each successful refresh.
            remoteCfg.eventTracker = tracker
            this.remoteConfigManager = remoteCfg

            this.featureFlagManager = FeatureFlagManager(remoteCfg)

            val expManager = ExperimentManager(
                remoteConfigManager = remoteCfg,
                identityManager = identityMgr,
                eventTracker = tracker
            )
            this.experimentManager = expManager

            // SPEC-070-A A.14: every event envelope now carries
            // `context.experiment_exposures` so BigQuery ETL can attribute
            // conversions to the variant the user was bucketed into.
            tracker.setExperimentExposureProvider {
                expManager.getExposures().map { (experimentId, variantId) ->
                    ai.appdna.sdk.events.ExperimentExposure(experimentId, variantId)
                }
            }

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
            // SPEC-203: per-user journey message listener.
            this.pendingMessageListener = ai.appdna.sdk.messages.PendingMessageListener(tracker, appContext)

            // Wire survey config updates from RemoteConfigManager to SurveyManager
            remoteCfg.surveyUpdateHandler = { rawSurveys ->
                val parsed = rawSurveys.mapNotNull { (key, data) ->
                    SurveyConfig.fromMap(data)?.let { key to it }
                }.toMap()
                surveyManager?.updateConfigs(parsed)
            }

            // SPEC-070-A A.8: SessionManager — observes ProcessLifecycle to emit
            // session_start / session_end / app_open / app_close with 30-min idle rotation.
            ai.appdna.sdk.core.SessionDataStore.instance?.let { sds ->
                val sm = ai.appdna.sdk.core.SessionManager(appContext, tracker, sds)
                this.sessionManager = sm
                sm.start()
            }

            // SPEC-070-A A.9: MessageManager — trigger evaluator + frequency tracker
            // + presentation queue + delegate veto. Renderer separate (InAppMessageRenderer)
            // per architectural split.
            ai.appdna.sdk.core.SessionDataStore.instance?.let { _ ->
                this.messageManager = ai.appdna.sdk.messages.MessageManager(
                    context = appContext,
                    configProvider = { activeMessages },
                )
            }

            // SPEC-070-A A.10: NavigationInterceptor — observes Activity resume to
            // fire registered hooks. Compose-only screens use AppDNA.notifyScreenAppeared(...).
            (appContext as? android.app.Application)?.registerActivityLifecycleCallbacks(
                ai.appdna.sdk.screens.NavigationInterceptorActivityCallbacks(
                    ai.appdna.sdk.screens.NavigationInterceptor.shared
                )
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
        // Capture pre-identify ids so the backend alias call + identify event
        // can include the previous values (iOS parity, AppDNA.swift:185-216).
        val previousAnonId = identityManager?.currentIdentity?.anonId
        val previousUserId = identityManager?.currentIdentity?.userId

        identityManager?.identify(userId, traits)
        val deviceId = identityManager?.currentIdentity?.anonId

        // SPEC-070-A G.3: emit local `identify` event so the existing client
        // pipeline (BigQuery alias resolution + experiment exposure ledger)
        // sees the user-id transition immediately.
        val identifyProps = mutableMapOf<String, Any>(
            "user_id" to userId,
            "anon_id" to (previousAnonId ?: ""),
        )
        if (previousUserId != null && previousUserId != userId) {
            identifyProps["previous_user_id"] = previousUserId
        }
        if (traits != null) {
            identifyProps["traits"] = traits
        }
        track("identify", identifyProps)

        // SPEC-070-A G.2: fire-and-forget POST to /api/v1/sdk/identify so the
        // backend can stitch anon → user identities even if the user never
        // emits another event. Retries on 5xx/network are handled inside
        // ApiClient.post() (called by postFireAndForget).
        try {
            val body = JSONObject()
            body.put("anon_id", previousAnonId ?: "")
            body.put("user_id", userId)
            if (deviceId != null) body.put("device_id", deviceId)
            if (traits != null) body.put("traits", JSONObject(traits))
            apiClient?.postFireAndForget("/api/v1/sdk/identify", body.toString())
        } catch (e: Exception) {
            Log.warning { "Failed to post identify alias: ${e.message}" }
        }

        // Start web entitlement observer for this user (v0.3)
        val orgId = bootstrapOrgId
        val appId = bootstrapAppId
        if (orgId != null && appId != null) {
            webEntitlementManager?.startObserving(orgId, appId, userId)
            // SPEC-203: start journey-triggered pending-messages listener.
            pendingMessageListener?.startObserving(orgId, appId, userId)
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
        pendingMessageListener?.stopObserving()
        // SPEC-070-A A.9: clear in-session message frequency counters + queue.
        messageManager?.resetSession()
        Log.info("Identity reset")
    }

    // MARK: - Public API: Events

    /**
     * Track a custom event.
     */
    fun track(event: String, properties: Map<String, Any>? = null) {
        val tracker = eventTracker
        if (tracker == null) {
            // SPEC-070-A G.18: pre-init buffer. Tracks issued before configure()
            // wires the EventTracker land here and are drained on first
            // setEventQueue call. Cap = 200; on overflow we drop the OLDEST so
            // the most recent action survives.
            val entry = PreInitEvent(event, properties)
            if (!preInitBuffer.offer(entry)) {
                // Queue full — pop oldest, push new.
                preInitBuffer.poll()
                if (!preInitBuffer.offer(entry)) {
                    Log.warning { "Pre-init event buffer full; dropping '$event'" }
                } else {
                    Log.warning { "Pre-init event buffer full; dropping oldest" }
                }
            }
            return
        }
        tracker.track(event, properties)
        // Evaluate surveys on every tracked event (v0.3)
        surveyManager?.onEvent(event, properties)
        // SPEC-070-A A.9: evaluate in-app message triggers on every event.
        messageManager?.onEvent(event, properties ?: emptyMap())
    }

    /**
     * SPEC-070-A A.10: notify the SDK that a Compose-only screen has appeared
     * (Activity-based screens fire automatically via the lifecycle callback
     * registered in `configure()`). Hosts should call this from
     * `LaunchedEffect(Unit)` of any composable that represents a navigable
     * destination if they want it to participate in NavigationInterceptor hooks.
     */
    fun notifyScreenAppeared(screenName: String) {
        // SPEC-070-A G.17: snapshot the latest screen so subsequent events
        // include `context.screen` even when the host hasn't wired any
        // NavigationInterceptor hooks (zero-code attribution path).
        lastScreenName = screenName
        ai.appdna.sdk.screens.NavigationInterceptor.shared.notifyScreenAppeared(screenName)
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

    /**
     * SPEC-070-A A.12: Returns the configured environment's base URL so internal
     * Composables (e.g. the AC-046 location autocomplete renderer) can issue
     * sandbox-vs-production-aware backend requests without hard-coding hosts.
     *
     * Returns Production base URL when called before [configure] (defensive fallback).
     */
    internal fun getApiBaseUrl(): String = environment.baseUrl

    /**
     * SPEC-070-A: Internal accessor for the SDK API key, used by ad-hoc HTTP calls
     * inside renderers that don't go through [ApiClient] (e.g. the geocode autocomplete).
     * Null before [configure] runs.
     */
    internal fun getApiKey(): String? = apiKey

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
        // SPEC-070-A A.31a — Firebase asset I/O moved off the configure-thread.
        // Reads google-services-appdna.json from assets + parses JSON; we run
        // it here on Dispatchers.IO before any Firestore consumer needs it.
        // SPEC-070-A A.24 — also adds GCM sender ID so the secondary
        // FirebaseApp can do FCM (otherwise PushTokenManager.registerToken
        // returns nothing because the SDK FirebaseApp has no project number).
        initSecondaryFirebaseAppIfNeeded()

        // SPEC-070-A A.26 — instantiate NativeBillingManager and bind to
        // BillingModule.manager so AppDNA.billing.purchase() / restore() /
        // getProducts() / getEntitlements() all work. Was never set,
        // resulting in silent no-ops every time the host called billing.
        initBillingModuleIfNeeded(tracker, client)

        val result = client.get("/api/v1/sdk/bootstrap")
        if (result != null) {
            try {
                val orgId = result.optString("orgId", "").ifEmpty { throw IllegalArgumentException("Missing orgId") }
                val appId = result.optString("appId", "").ifEmpty { throw IllegalArgumentException("Missing appId") }
                val firestorePath = result.optString("firestorePath", "").ifEmpty { throw IllegalArgumentException("Missing firestorePath") }

                Log.info("Bootstrap successful: orgId=$orgId, appId=$appId")

                synchronized(this@AppDNA) {
                    bootstrapOrgId = orgId
                    bootstrapAppId = appId

                    // Propagate the Firestore tenant scope into RemoteConfigManager.
                    // Without this assignment, fetchConfigs() always falls through
                    // to "No Firestore path available — serving cached config only"
                    // because the manager was constructed with a null path.
                    remoteCfg.setFirestorePath(firestorePath)

                    // v0.3: Create deferred deep link manager
                    appContext?.let { ctx ->
                        deferredDeepLinkManager = DeferredDeepLinkManager(ctx, orgId, appId, tracker)
                    }

                    // Start web entitlement observer if user is already identified
                    identityManager?.currentIdentity?.userId?.let { userId ->
                        webEntitlementManager?.startObserving(orgId, appId, userId)
                        // SPEC-203: pending-messages listener when already identified.
                        pendingMessageListener?.startObserving(orgId, appId, userId)
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
    @Suppress("UNCHECKED_CAST")
    private fun loadConfigBundle() {
        try {
            val ctx = appContext ?: return
            val inputStream = ctx.assets.open("appdna-config.json")
            val json = inputStream.bufferedReader().use { it.readText() }
            val config = JSONObject(json)
            val bundleVersion = config.optInt("bundle_version", 0)
            currentBundleVersion = bundleVersion

            // Feed bundled config to RemoteConfigManager — only fills empty caches
            // Convert JSONObject to Map recursively so parse methods work
            val map = jsonObjectToMap(config)
            remoteConfigManager?.loadBundledConfig(map)
            Log.info("Loaded bundled config (version $bundleVersion)")
        } catch (_: Exception) {
            Log.debug("No bundled config found at appdna-config.json — using remote/cached only")
        }
    }

    /**
     * Load FirebaseOptions from google-services-appdna.json in app assets.
     * Returns null if the file is not found or cannot be parsed.
     */
    /** Recursively convert JSONObject to Map<String, Any> for parse methods. */
    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for (key in obj.keys()) {
            val value = obj.get(key)
            when (value) {
                JSONObject.NULL -> {} // Skip null values
                is JSONObject -> map[key] = jsonObjectToMap(value)
                is org.json.JSONArray -> map[key] = jsonArrayToList(value)
                else -> map[key] = value
            }
        }
        return map
    }

    private fun jsonArrayToList(arr: org.json.JSONArray): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until arr.length()) {
            val value = arr.get(i)
            when (value) {
                JSONObject.NULL -> {} // Skip null values
                is JSONObject -> list.add(jsonObjectToMap(value))
                is org.json.JSONArray -> list.add(jsonArrayToList(value))
                else -> list.add(value)
            }
        }
        return list
    }

    private fun loadSecondaryFirebaseOptions(context: Context): FirebaseOptions? {
        return try {
            val inputStream = context.assets.open("google-services-appdna.json")
            val json = inputStream.bufferedReader().use { it.readText() }
            val config = JSONObject(json)

            // google-services.json format has project_info and client arrays
            val projectInfo = config.optJSONObject("project_info") ?: return null
            val projectId = projectInfo.optString("project_id", "").ifEmpty { return null }
            val storageBucket = projectInfo.optString("storage_bucket", "$projectId.appspot.com")
            // SPEC-070-A A.24 (part 2) — pull `project_number` so we can populate
            // `setGcmSenderId` on the secondary FirebaseApp. Without this, FCM
            // (`FirebaseMessaging.getInstance(secondaryApp).token`) returns an
            // error because the secondary app has no sender ID.
            val gcmSenderId = projectInfo.optString("project_number", "").ifEmpty { null }

            // Find the first client entry
            val clients = config.optJSONArray("client") ?: return null
            if (clients.length() == 0) return null
            val client = clients.optJSONObject(0) ?: return null

            val clientInfo = client.optJSONObject("client_info") ?: return null
            val mobileSdkAppId = clientInfo.optString("mobilesdk_app_id", "").ifEmpty { return null }

            // API key from api_key array
            val apiKeys = client.optJSONArray("api_key")
            val apiKey = if (apiKeys != null && apiKeys.length() > 0) {
                apiKeys.optJSONObject(0)?.optString("current_key", null)
            } else null

            val builder = FirebaseOptions.Builder()
                .setProjectId(projectId)
                .setApplicationId(mobileSdkAppId)
                .setStorageBucket(storageBucket)

            if (apiKey != null) {
                builder.setApiKey(apiKey)
            }
            if (gcmSenderId != null) {
                builder.setGcmSenderId(gcmSenderId)
            }

            builder.build()
        } catch (_: Exception) {
            null
        }
    }

    // SPEC-070-A A.31a — Firebase asset I/O moved off the configure-thread.
    private fun initSecondaryFirebaseAppIfNeeded() {
        val context = appContext ?: return
        if (firestoreDB != null) return  // already initialized
        val secondaryOptions = loadSecondaryFirebaseOptions(context)
        synchronized(this@AppDNA) {
            if (secondaryOptions != null) {
                val existingApp = try { FirebaseApp.getInstance("appdna") } catch (_: Exception) { null }
                if (existingApp == null) {
                    FirebaseApp.initializeApp(context, secondaryOptions, "appdna")
                }
                val secondaryApp = try { FirebaseApp.getInstance("appdna") } catch (_: Exception) { null }
                if (secondaryApp != null) {
                    firestoreDB = FirebaseFirestore.getInstance(secondaryApp)
                    Log.info("Firebase: Using secondary app 'appdna' (google-services-appdna.json)")
                } else {
                    Log.error("Firebase: google-services-appdna.json found but failed to create secondary app. Check the JSON content.")
                }
            } else if (FirebaseApp.getApps(context).isEmpty()) {
                Log.error("Firebase: No configuration found. Download google-services-appdna.json from Console -> Settings -> SDK and add it to your app/src/main/assets/ directory. See: https://docs.appdna.ai/sdks/android/installation#firebase-configuration")
            } else {
                Log.error("Firebase: Your app already has Firebase configured (its own project), but google-services-appdna.json was NOT found. AppDNA needs its own Firebase config. Download it from Console -> Settings -> SDK and add to app/src/main/assets/. Remote config will NOT work without this file.")
            }
        }
    }

    // SPEC-070-A A.26 — instantiate NativeBillingManager and bind to BillingModule.
    private fun initBillingModuleIfNeeded(
        tracker: ai.appdna.sdk.events.EventTracker,
        client: ai.appdna.sdk.network.ApiClient,
    ) {
        if (billing.manager != null) return
        val ctx = appContext ?: return
        val storage = LocalStorage(ctx)
        try {
            val verifier = ai.appdna.sdk.billing.ReceiptVerifier(client)
            val cache = ai.appdna.sdk.billing.EntitlementCache(ctx)
            val mgr = ai.appdna.sdk.billing.NativeBillingManager(
                context = ctx,
                receiptVerifier = verifier,
                entitlementCache = cache,
                storage = storage,
            )
            mgr.initialize()
            synchronized(this@AppDNA) {
                billing.manager = mgr
            }
            // Start observing entitlement changes from Firestore once we have org/app/user.
            val orgId = bootstrapOrgId
            val appId = bootstrapAppId
            val userId = identityManager?.currentIdentity?.userId
            if (orgId != null && appId != null && userId != null) {
                cache.startObserving(orgId, appId, userId)
            }
            Log.info("BillingModule: NativeBillingManager initialized")
        } catch (e: Exception) {
            Log.error("BillingModule init failed: ${e.message}")
        }
    }

    /**
     * Internal accessor for bridges that need the application context but
     * aren't passed it directly (e.g. `RevenueCatBridge.configure`).
     */
    internal fun appContextForBridges(): Context? = appContext

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
            pendingMessageListener?.stopObserving()
            scope.cancel()
            isConfigured = false
            Log.info("SDK shut down")
        }
    }
}
