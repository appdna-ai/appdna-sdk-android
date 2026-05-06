package ai.appdna.sdk.config

import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.onboarding.OnboardingConfigParser
import ai.appdna.sdk.onboarding.OnboardingFlowConfig
import ai.appdna.sdk.paywalls.PaywallConfig
import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.storage.LocalStorage
import ai.appdna.sdk.AppDNA
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages remote config from Firestore with local caching.
 */
internal class RemoteConfigManager(
    firestorePath: String?,
    private val storage: LocalStorage,
    private val configTTL: Long
) {
    // Mutable so AppDNA.performBootstrap can supply the path returned by
    // /api/v1/sdk/bootstrap. Without this setter the manager forever falls
    // through to "No Firestore path available — serving cached config only"
    // because the path is null at construction.
    @Volatile
    private var firestorePath: String? = firestorePath

    fun setFirestorePath(path: String) {
        this.firestorePath = path
    }

    /**
     * SPEC-070-A G.4: Optional event tracker so [fetchConfigs] can emit
     * `config_fetched` after each successful Firestore refresh. Wired by
     * AppDNA.configure() once both managers are constructed; until then we
     * simply skip the event (no NPE risk).
     */
    @Volatile
    var eventTracker: EventTracker? = null

    /**
     * SPEC-070-A G.4: Tracks how many of the 6 Firestore documents in a
     * fetchConfigs() batch have completed (success OR failure). When the count
     * reaches the expected total, we emit a single `config_fetched` event with
     * the count of docs that succeeded — mirrors the iOS one-shot semantic
     * (Sources/AppDNASDK/Config/RemoteConfigManager.swift `configFetchCompleted`).
     */
    private val fetchCompletionCounter = AtomicInteger(0)
    private val fetchSuccessCounter = AtomicInteger(0)
    private val fetchExpectedTotal = AtomicInteger(0)
    private var flags: Map<String, Any> = emptyMap()
    private var experiments: Map<String, ExperimentConfig> = emptyMap()
    private var surveys: Map<String, Map<String, Any>> = emptyMap()
    private var paywalls: Map<String, PaywallConfig> = emptyMap()
    private var onboardingFlows: Map<String, OnboardingFlowConfig> = emptyMap()
    private var activeOnboardingFlowId: String? = null

    /** Callback when survey configs are updated from Firestore. */
    var surveyUpdateHandler: ((Map<String, Map<String, Any>>) -> Unit)? = null

    init {
        loadCachedConfigs()
    }

    private val changeListeners = CopyOnWriteArrayList<(Map<String, Any>) -> Unit>()

    fun getConfig(key: String): Any? = flags[key]

    fun getAllConfig(): Map<String, Any> = flags

    fun addChangeListener(callback: (Map<String, Any>) -> Unit) {
        changeListeners.add(callback)
    }

    fun getExperimentConfig(id: String): ExperimentConfig? = experiments[id]

    fun getAllExperiments(): Map<String, ExperimentConfig> = experiments

    fun getSurveyConfigs(): Map<String, Map<String, Any>> = surveys

    fun getPaywallConfig(id: String): PaywallConfig? = paywalls[id]

    /**
     * Get an onboarding flow by ID, or the active flow if id is null.
     */
    fun getOnboardingFlow(id: String?): OnboardingFlowConfig? {
        if (id != null) {
            return onboardingFlows[id]
        }
        val activeId = activeOnboardingFlowId ?: return null
        return onboardingFlows[activeId]
    }

    /**
     * SPEC-070-A F.13: read-only view of every parsed onboarding flow keyed by id.
     * Used by [ai.appdna.sdk.onboarding.OnboardingFlowManager.present] to evaluate
     * `audience_rules` across all flows when no explicit flowId is supplied —
     * mirrors iOS `OnboardingFlowManager.swift:121-137`.
     */
    fun getAllOnboardingFlows(): Map<String, OnboardingFlowConfig> = onboardingFlows

    /**
     * SPEC-067: Force an immediate config refresh, bypassing the cache TTL.
     * Use this when you need configs to update immediately (e.g., after a user action).
     */
    fun forceRefresh() {
        Log.info("Force refreshing remote config (bypassing TTL)")
        fetchConfigs()
    }

    fun fetchConfigs() {
        val path = firestorePath ?: run {
            Log.warning("No Firestore path available — serving cached config only")
            return
        }

        val db = AppDNA.firestoreDB ?: run {
            Log.warning("Firestore not available — serving cached config only")
            return
        }
        val basePath = "$path/config"

        // SPEC-070-A G.4: 6 documents — flags, experiments, paywall_index,
        // onboarding_index, survey_index, screen_index. Re-init counters
        // whenever a new fetch begins.
        val expected = 6
        fetchCompletionCounter.set(0)
        fetchSuccessCounter.set(0)
        fetchExpectedTotal.set(expected)

        // Fetch flags — unwrap "flags" wrapper from Firestore doc
        db.document("$basePath/flags").get().addOnSuccessListener { snapshot ->
            snapshot.data?.let { data ->
                @Suppress("UNCHECKED_CAST")
                val unwrapped = (data["flags"] as? Map<String, Any>) ?: data
                flags = unwrapped
                cacheData("flags", JSONObject(unwrapped).toString())
                notifyChangeListeners()
            }
            markFetchComplete(success = true)
        }.addOnFailureListener { e ->
            Log.error("Failed to fetch flags: ${e.message}")
            markFetchComplete(success = false)
        }

        // Fetch experiments
        db.document("$basePath/experiments").get().addOnSuccessListener { snapshot ->
            snapshot.data?.let { data ->
                parseExperiments(data)
                cacheData("experiments", JSONObject(data).toString())
            }
            markFetchComplete(success = true)
        }.addOnFailureListener { e ->
            Log.error("Failed to fetch experiments: ${e.message}")
            markFetchComplete(success = false)
        }

        // Paywalls: prefer per-item docs via index → fallback to mega-doc
        fetchViaIndex(
            db = db, basePath = basePath,
            indexPath = "paywall_index", indexKey = "paywalls",
            itemCollection = "paywall_index/paywalls", megaDocPath = "paywalls",
            parseItem = { id, data -> parseSinglePaywall(id, data) },
            parseMegaDoc = { data -> parsePaywalls(data); cacheData("paywalls", JSONObject(data).toString()) },
            onComplete = { ok -> markFetchComplete(ok) }
        )

        // Onboarding: prefer per-item docs via index → fallback to mega-doc
        fetchViaIndex(
            db = db, basePath = basePath,
            indexPath = "onboarding_index", indexKey = "flows",
            itemCollection = "onboarding_index/flows", megaDocPath = "onboarding",
            parseItem = { id, data -> parseSingleOnboardingFlow(id, data) },
            parseMegaDoc = { data -> parseOnboarding(data); cacheData("onboarding", JSONObject(data).toString()) },
            extraIndexParse = { indexData ->
                (indexData["active_flow_id"] as? String)?.let { activeOnboardingFlowId = it }
            },
            onComplete = { ok -> markFetchComplete(ok) }
        )

        // Surveys: prefer per-item docs via index → fallback to mega-doc
        fetchViaIndex(
            db = db, basePath = basePath,
            indexPath = "survey_index", indexKey = "surveys",
            itemCollection = "survey_index/surveys", megaDocPath = "surveys",
            parseItem = { id, data -> parseSingleSurvey(id, data) },
            parseMegaDoc = { data -> parseSurveys(data); cacheData("surveys", JSONObject(data).toString()) },
            onComplete = { ok -> markFetchComplete(ok) }
        )

        // SPEC-089c: Fetch screen_index for server-driven UI
        db.document("$basePath/screen_index").get().addOnSuccessListener { snapshot ->
            snapshot.data?.let { data ->
                parseScreenIndex(data)
            }
            markFetchComplete(success = true)
        }.addOnFailureListener { e ->
            Log.debug("No screen_index config: ${e.message}")
            markFetchComplete(success = false)
        }

        Log.info("Fetching remote configs from Firestore")
    }

    /**
     * SPEC-070-A G.4: Increment the per-fetch completion counter and emit
     * `config_fetched` exactly once when every doc in the batch has either
     * succeeded or failed. The event payload mirrors iOS shape:
     *   - success_count: int (docs with non-null Firestore snapshot)
     *   - total_count:   int (docs the SDK attempted to fetch)
     */
    private fun markFetchComplete(success: Boolean) {
        if (success) fetchSuccessCounter.incrementAndGet()
        val done = fetchCompletionCounter.incrementAndGet()
        val expected = fetchExpectedTotal.get()
        if (expected > 0 && done >= expected) {
            val successes = fetchSuccessCounter.get()
            try {
                eventTracker?.track("config_fetched", mapOf(
                    "success_count" to successes,
                    "total_count" to expected,
                ))
            } catch (e: Exception) {
                Log.warning { "config_fetched event emit failed: ${e.message}" }
            }
            // Reset so a subsequent forceRefresh() call doesn't double-fire.
            fetchExpectedTotal.set(0)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseExperiments(data: Map<String, Any>) {
        // Unwrap "experiments" wrapper if present
        val experimentsMap = (data["experiments"] as? Map<String, Any>) ?: data
        val parsed = mutableMapOf<String, ExperimentConfig>()
        for ((key, value) in experimentsMap) {
            if (value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, Any>
                try {
                    val variants = (map["variants"] as? List<*>)?.mapNotNull { v ->
                        if (v is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val vm = v as Map<String, Any>
                            // SPEC-070-A F.10: iOS canonical key is `payload`; legacy SDKs wrote
                            // `config`. Accept both — prefer `payload` when present.
                            @Suppress("UNCHECKED_CAST")
                            val variantData =
                                (vm["payload"] as? Map<String, Any>)
                                    ?: (vm["config"] as? Map<String, Any>)
                                    ?: emptyMap()
                            ExperimentVariant(
                                id = vm["id"] as? String ?: return@mapNotNull null,
                                weight = (vm["weight"] as? Number)?.toDouble() ?: return@mapNotNull null,
                                config = variantData
                            )
                        } else null
                    } ?: emptyList()

                    // SPEC-070-A F.9: parse `segments` for audience targeting (iOS parity).
                    val segments = (map["segments"] as? List<*>)?.filterIsInstance<String>()

                    parsed[key] = ExperimentConfig(
                        id = map["id"] as? String ?: key,
                        name = map["name"] as? String ?: "",
                        status = map["status"] as? String ?: "paused",
                        salt = map["salt"] as? String ?: "",
                        platforms = (map["platforms"] as? List<*>)?.filterIsInstance<String>() ?: listOf("android"),
                        variants = variants,
                        segments = segments,
                    )
                } catch (e: Exception) {
                    Log.warning("Failed to parse experiment '$key': ${e.message}")
                }
            }
        }
        experiments = parsed
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSurveys(data: Map<String, Any>) {
        val surveysMap = data["surveys"] as? Map<String, Any> ?: data
        val parsed = mutableMapOf<String, Map<String, Any>>()
        for ((key, value) in surveysMap) {
            if (value is Map<*, *>) {
                parsed[key] = value as Map<String, Any>
            }
        }
        surveys = parsed
        Log.debug("Parsed ${parsed.size} survey configs")
        surveyUpdateHandler?.invoke(parsed)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePaywalls(data: Map<String, Any>) {
        paywalls = PaywallConfigParser.parsePaywalls(data)
        Log.debug("Parsed ${paywalls.size} paywall configs")
    }

    private fun parseOnboarding(data: Map<String, Any>) {
        val (activeId, flows) = OnboardingConfigParser.parseOnboardingRoot(data)
        onboardingFlows = flows
        activeOnboardingFlowId = activeId
        Log.debug("Parsed ${flows.size} onboarding flows, active=$activeId")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseScreenIndex(data: Map<String, Any>) {
        try {
            val index = ai.appdna.sdk.screens.ScreenIndex.fromMap(data)
            ai.appdna.sdk.screens.ScreenManager.shared.updateIndex(index)
            Log.info("Screen index loaded: ${index.screens?.size ?: 0} screens, ${index.flows?.size ?: 0} flows, ${index.slots?.size ?: 0} slots")
        } catch (e: Exception) {
            Log.error("Failed to parse screen_index: ${e.message}")
        }
    }

    // MARK: - Per-item fetch via index (enterprise-grade)

    /**
     * Generic helper: try to load items via a lightweight index document.
     * If the index exists, fan out to individual per-item documents.
     * If no index, fall back to the legacy mega-document.
     */
    @Suppress("UNCHECKED_CAST")
    private fun fetchViaIndex(
        db: com.google.firebase.firestore.FirebaseFirestore,
        basePath: String,
        indexPath: String,
        indexKey: String,
        itemCollection: String,
        megaDocPath: String,
        parseItem: (String, Map<String, Any>) -> Unit,
        parseMegaDoc: (Map<String, Any>) -> Unit,
        extraIndexParse: ((Map<String, Any>) -> Unit)? = null,
        // SPEC-070-A G.4: invoked once when this index/mega-doc fetch path
        // resolves — used by the parent fetchConfigs() to count down toward
        // the single `config_fetched` event.
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        db.document("$basePath/$indexPath").get()
            .addOnSuccessListener { snapshot ->
                val indexData = snapshot.data
                val itemsMap = indexData?.get(indexKey) as? Map<String, Any>
                if (indexData != null && !itemsMap.isNullOrEmpty()) {
                    // Index exists — fetch individual docs
                    Log.debug("[$indexPath] Found index with ${itemsMap.size} items, fetching individually")
                    extraIndexParse?.invoke(indexData)
                    for (itemId in itemsMap.keys) {
                        db.document("$basePath/$itemCollection/$itemId").get()
                            .addOnSuccessListener { itemSnapshot ->
                                itemSnapshot.data?.let { itemData ->
                                    parseItem(itemId, itemData)
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.warning("[$indexPath] Failed to fetch item $itemId: ${e.message}")
                            }
                    }
                    onComplete?.invoke(true)
                } else {
                    // No index — fall back to legacy mega-doc
                    Log.debug("[$indexPath] No index found, falling back to mega-doc $megaDocPath")
                    db.document("$basePath/$megaDocPath").get()
                        .addOnSuccessListener { megaSnapshot ->
                            megaSnapshot.data?.let { data -> parseMegaDoc(data) }
                            onComplete?.invoke(true)
                        }
                        .addOnFailureListener { e ->
                            Log.error("Failed to fetch $megaDocPath config: ${e.message}")
                            onComplete?.invoke(false)
                        }
                }
            }
            .addOnFailureListener { e ->
                // Index fetch failed — try mega-doc as fallback
                Log.debug("[$indexPath] Index fetch failed (${e.message}), trying mega-doc")
                db.document("$basePath/$megaDocPath").get()
                    .addOnSuccessListener { megaSnapshot ->
                        megaSnapshot.data?.let { data -> parseMegaDoc(data) }
                        onComplete?.invoke(true)
                    }
                    .addOnFailureListener { e2 ->
                        Log.error("Failed to fetch $megaDocPath config: ${e2.message}")
                        onComplete?.invoke(false)
                    }
            }
    }

    // Raw data accumulators for disk cache rebuild during per-item fetches.
    // When using index-based fetch, individual items bypass the mega-doc path
    // that normally handles caching. These track raw Firestore data so we can
    // rebuild the cache after each item is fetched.
    private val rawPaywallData = mutableMapOf<String, Map<String, Any>>()
    private val rawOnboardingData = mutableMapOf<String, Map<String, Any>>()

    @Suppress("UNCHECKED_CAST")
    private fun parseSinglePaywall(id: String, data: Map<String, Any>) {
        try {
            val config = PaywallConfigParser.parseSinglePaywall(id, data)
            if (config != null) {
                paywalls = paywalls + (id to config)
            }
        } catch (e: Exception) {
            Log.error("Failed to parse individual paywall '$id': ${e.message}")
        }
        // Rebuild disk cache in mega-doc format for offline restart
        rawPaywallData[id] = data
        try {
            val combined = JSONObject()
            val paywallsObj = JSONObject()
            for ((pid, pdata) in rawPaywallData) { paywallsObj.put(pid, JSONObject(pdata)) }
            combined.put("paywalls", paywallsObj)
            cacheData("paywalls", combined.toString())
        } catch (_: Exception) {}
    }

    private fun parseSingleOnboardingFlow(id: String, data: Map<String, Any>) {
        try {
            val config = OnboardingConfigParser.parseSingleFlow(id, data)
            if (config != null) {
                onboardingFlows = onboardingFlows + (id to config)
            }
        } catch (e: Exception) {
            Log.error("Failed to parse individual onboarding flow '$id': ${e.message}")
        }
        // Rebuild disk cache in mega-doc format for offline restart
        rawOnboardingData[id] = data
        try {
            val combined = JSONObject()
            val flowsObj = JSONObject()
            for ((fid, fdata) in rawOnboardingData) { flowsObj.put(fid, JSONObject(fdata)) }
            combined.put("flows", flowsObj)
            activeOnboardingFlowId?.let { combined.put("active_flow_id", it) }
            cacheData("onboarding", combined.toString())
        } catch (_: Exception) {}
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSingleSurvey(id: String, data: Map<String, Any>) {
        surveys = surveys + (id to data)
        surveyUpdateHandler?.invoke(surveys)
        // Rebuild disk cache in mega-doc format
        try {
            val combined = JSONObject()
            val surveysObj = JSONObject()
            for ((sid, sdata) in surveys) { surveysObj.put(sid, JSONObject(sdata)) }
            combined.put("surveys", surveysObj)
            cacheData("surveys", combined.toString())
        } catch (_: Exception) {}
    }

    private fun loadCachedConfigs() {
        storage.getString("cache_flags")?.let { json ->
            try {
                val obj = JSONObject(json)
                flags = obj.keys().asSequence().associateWith { obj.get(it) }
            } catch (_: Exception) {}
        }
        storage.getString("cache_experiments")?.let { json ->
            try {
                val obj = JSONObject(json)
                val data = obj.keys().asSequence().associateWith { obj.get(it) as Any }
                @Suppress("UNCHECKED_CAST")
                parseExperiments(data)
            } catch (_: Exception) {}
        }
        storage.getString("cache_surveys")?.let { json ->
            try {
                val obj = JSONObject(json)
                val data = obj.keys().asSequence().associateWith { obj.get(it) as Any }
                @Suppress("UNCHECKED_CAST")
                parseSurveys(data)
            } catch (_: Exception) {}
        }
        storage.getString("cache_paywalls")?.let { json ->
            try {
                val obj = JSONObject(json)
                val data = obj.keys().asSequence().associateWith { obj.get(it) as Any }
                @Suppress("UNCHECKED_CAST")
                parsePaywalls(data)
            } catch (_: Exception) {}
        }
        storage.getString("cache_onboarding")?.let { json ->
            try {
                val obj = JSONObject(json)
                val data = obj.keys().asSequence().associateWith { obj.get(it) as Any }
                @Suppress("UNCHECKED_CAST")
                parseOnboarding(data)
            } catch (_: Exception) {}
        }
    }

    /**
     * Load config from the bundled appdna-config.json embedded in the app.
     * Only populates empty caches — remote and cached data take priority.
     */
    @Suppress("UNCHECKED_CAST")
    fun loadBundledConfig(json: Map<String, Any>) {
        if (paywalls.isEmpty()) {
            (json["paywalls"] as? Map<String, Any>)?.let { data ->
                if (data.isNotEmpty()) { parsePaywalls(data); Log.debug("Loaded paywalls from bundled config") }
            }
        }
        if (onboardingFlows.isEmpty()) {
            (json["onboarding"] as? Map<String, Any>)?.let { data ->
                if (data.isNotEmpty()) { parseOnboarding(data); Log.debug("Loaded onboarding from bundled config") }
            }
        }
        if (experiments.isEmpty()) {
            (json["experiments"] as? Map<String, Any>)?.let { data ->
                if (data.isNotEmpty()) { parseExperiments(data); Log.debug("Loaded experiments from bundled config") }
            }
        }
        if (surveys.isEmpty()) {
            (json["surveys"] as? Map<String, Any>)?.let { data ->
                if (data.isNotEmpty()) { parseSurveys(data); Log.debug("Loaded surveys from bundled config") }
            }
        }
        if (flags.isEmpty()) {
            val flagData = (json["remote_config"] as? Map<String, Any>)
                ?: (json["feature_flags"] as? Map<String, Any>)
            if (flagData != null && flagData.isNotEmpty()) {
                flags = flagData
                Log.debug("Loaded remote_config from bundled config")
            }
        }
        (json["screen_index"] as? Map<String, Any>)?.let { data ->
            if (data.isNotEmpty()) { parseScreenIndex(data); Log.debug("Loaded screen_index from bundled config") }
        }
    }

    private fun cacheData(key: String, json: String) {
        storage.setString("cache_$key", json)
    }

    private fun notifyChangeListeners() {
        for (listener in changeListeners) {
            listener(flags)
        }
    }
}

/**
 * Experiment config model. Mirrors iOS `Config/RemoteConfigManager.swift`
 * `ExperimentConfig`. SPEC-070-A F.9 adds `segments` for audience targeting.
 */
data class ExperimentConfig(
    val id: String,
    val name: String,
    val status: String,
    val salt: String,
    val platforms: List<String>,
    val variants: List<ExperimentVariant>,
    /** Optional segment IDs gating which users see this experiment (iOS parity). */
    val segments: List<String>? = null,
)

/**
 * A single experiment variant.
 *
 * SPEC-070-A F.10: Firestore-canonical key for variant data is `payload`
 * (matching iOS `ExperimentVariant.payload`); legacy SDKs wrote `config`.
 * The parser in [RemoteConfigManager.parseExperiments] accepts BOTH keys,
 * preferring `payload` when set. The Kotlin field stays named `config` so
 * all existing callers (e.g. [ExperimentManager.getValue]) keep working.
 */
data class ExperimentVariant(
    val id: String,
    val weight: Double,
    val config: Map<String, Any> = emptyMap()
)
