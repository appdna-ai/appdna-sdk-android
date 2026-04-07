package ai.appdna.sdk.config

import ai.appdna.sdk.Log
import ai.appdna.sdk.onboarding.OnboardingConfigParser
import ai.appdna.sdk.onboarding.OnboardingFlowConfig
import ai.appdna.sdk.paywalls.PaywallConfig
import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.storage.LocalStorage
import ai.appdna.sdk.AppDNA
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages remote config from Firestore with local caching.
 */
internal class RemoteConfigManager(
    private val firestorePath: String?,
    private val storage: LocalStorage,
    private val configTTL: Long
) {
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

        // Fetch flags — unwrap "flags" wrapper from Firestore doc
        db.document("$basePath/flags").get().addOnSuccessListener { snapshot ->
            snapshot.data?.let { data ->
                @Suppress("UNCHECKED_CAST")
                val unwrapped = (data["flags"] as? Map<String, Any>) ?: data
                flags = unwrapped
                cacheData("flags", JSONObject(unwrapped).toString())
                notifyChangeListeners()
            }
        }.addOnFailureListener { e ->
            Log.error("Failed to fetch flags: ${e.message}")
        }

        // Fetch experiments
        db.document("$basePath/experiments").get().addOnSuccessListener { snapshot ->
            snapshot.data?.let { data ->
                parseExperiments(data)
                cacheData("experiments", JSONObject(data).toString())
            }
        }.addOnFailureListener { e ->
            Log.error("Failed to fetch experiments: ${e.message}")
        }

        // Fetch surveys (v0.3)
        db.document("$basePath/surveys").get().addOnSuccessListener { snapshot ->
            snapshot.data?.let { data ->
                parseSurveys(data)
                cacheData("surveys", JSONObject(data).toString())
            }
        }.addOnFailureListener { e ->
            Log.error("Failed to fetch surveys: ${e.message}")
        }

        // Fetch paywalls
        db.document("$basePath/paywalls").get().addOnSuccessListener { snapshot ->
            snapshot.data?.let { data ->
                parsePaywalls(data)
                cacheData("paywalls", JSONObject(data).toString())
            }
        }.addOnFailureListener { e ->
            Log.error("Failed to fetch paywalls: ${e.message}")
        }

        // Fetch onboarding (v0.2)
        db.document("$basePath/onboarding").get().addOnSuccessListener { snapshot ->
            snapshot.data?.let { data ->
                parseOnboarding(data)
                cacheData("onboarding", JSONObject(data).toString())
            }
        }.addOnFailureListener { e ->
            Log.error("Failed to fetch onboarding: ${e.message}")
        }

        // SPEC-089c: Fetch screen_index for server-driven UI
        db.document("$basePath/screen_index").get().addOnSuccessListener { snapshot ->
            snapshot.data?.let { data ->
                parseScreenIndex(data)
            }
        }.addOnFailureListener { e ->
            Log.debug("No screen_index config: ${e.message}")
        }

        Log.info("Fetching remote configs from Firestore")
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
                            ExperimentVariant(
                                id = vm["id"] as? String ?: return@mapNotNull null,
                                weight = (vm["weight"] as? Number)?.toDouble() ?: return@mapNotNull null,
                                config = vm["config"] as? Map<String, Any> ?: emptyMap()
                            )
                        } else null
                    } ?: emptyList()

                    parsed[key] = ExperimentConfig(
                        id = map["id"] as? String ?: key,
                        name = map["name"] as? String ?: "",
                        status = map["status"] as? String ?: "paused",
                        salt = map["salt"] as? String ?: "",
                        platforms = (map["platforms"] as? List<*>)?.filterIsInstance<String>() ?: listOf("android"),
                        variants = variants
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
 * Experiment config model.
 */
data class ExperimentConfig(
    val id: String,
    val name: String,
    val status: String,
    val salt: String,
    val platforms: List<String>,
    val variants: List<ExperimentVariant>
)

data class ExperimentVariant(
    val id: String,
    val weight: Double,
    val config: Map<String, Any> = emptyMap()
)
