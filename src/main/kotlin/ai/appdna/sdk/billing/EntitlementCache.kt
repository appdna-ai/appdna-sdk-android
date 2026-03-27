package ai.appdna.sdk.billing

import android.content.Context
import ai.appdna.sdk.Log
import ai.appdna.sdk.AppDNA
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Caches entitlements locally using SharedPreferences and optionally
 * observes real-time entitlement updates from Firestore.
 *
 * The cache provides instant access to the last known entitlements on app launch
 * (before the server can be reached), while the Firestore listener ensures
 * entitlements stay in sync when changes occur server-side (e.g., renewal,
 * cancellation, refund).
 *
 * Firestore path: orgs/{orgId}/apps/{appId}/users/{userId}/entitlements
 */
internal class EntitlementCache(
    private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Current cached entitlements. */
    var entitlements: List<Entitlement> = emptyList()
        private set

    /** Whether the user has any active subscription. */
    val hasActiveSubscription: Boolean
        get() = entitlements.any { it.status in ACTIVE_STATUSES }

    /** Listeners notified when entitlements change. */
    private val changeListeners = CopyOnWriteArrayList<(List<Entitlement>) -> Unit>()

    /** Firestore real-time listener registration. */
    private var firestoreListener: ListenerRegistration? = null

    init {
        loadFromDisk()
    }

    // -- Public API --

    /**
     * Update the cache with a single new or updated entitlement.
     * Merges by productId (replaces existing entry for the same product).
     */
    fun update(entitlement: Entitlement) {
        val updated = entitlements
            .filter { it.productId != entitlement.productId }
            .plus(entitlement)
        setEntitlements(updated)
    }

    /**
     * Replace all cached entitlements (e.g., after a full restore or server fetch).
     */
    fun replaceAll(newEntitlements: List<Entitlement>) {
        setEntitlements(newEntitlements)
    }

    /**
     * Remove a specific entitlement by product ID.
     */
    fun remove(productId: String) {
        setEntitlements(entitlements.filter { it.productId != productId })
    }

    /**
     * Clear all cached entitlements.
     */
    fun clear() {
        setEntitlements(emptyList())
    }

    /**
     * Check if a specific product has an active entitlement.
     */
    fun isEntitled(productId: String): Boolean {
        return entitlements.any { it.productId == productId && it.status in ACTIVE_STATUSES }
    }

    /**
     * Get a specific entitlement by product ID.
     */
    fun getEntitlement(productId: String): Entitlement? {
        return entitlements.find { it.productId == productId }
    }

    /**
     * Get all cached entitlements.
     */
    fun getAll(): List<Entitlement> = entitlements

    /**
     * Register a listener for entitlement changes.
     */
    fun addChangeListener(listener: (List<Entitlement>) -> Unit) {
        changeListeners.add(listener)
    }

    /**
     * Remove a previously registered change listener.
     */
    fun removeChangeListener(listener: (List<Entitlement>) -> Unit) {
        changeListeners.remove(listener)
    }

    // -- Firestore Real-Time Sync --

    /**
     * Start observing entitlement changes from Firestore.
     *
     * @param orgId The organization ID.
     * @param appId The app ID.
     * @param userId The user ID.
     */
    fun startObserving(orgId: String, appId: String, userId: String) {
        stopObserving()

        val path = "orgs/$orgId/apps/$appId/users/$userId/entitlements"
        Log.debug("EntitlementCache: observing Firestore at $path")

        val db = AppDNA.firestoreDB ?: run {
            Log.warning("EntitlementCache: Firestore not available — skipping real-time sync")
            return
        }
        firestoreListener = db
            .collection(path)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.error("EntitlementCache Firestore error: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot == null || snapshot.isEmpty) {
                    if (entitlements.isNotEmpty()) {
                        setEntitlements(emptyList())
                    }
                    return@addSnapshotListener
                }

                val newEntitlements = snapshot.documents.mapNotNull { doc ->
                    try {
                        val data = doc.data ?: return@mapNotNull null
                        Entitlement(
                            productId = data["product_id"] as? String ?: doc.id,
                            store = data["store"] as? String ?: "google_play",
                            status = data["status"] as? String ?: "unknown",
                            expiresAt = data["expires_at"] as? String,
                            isTrial = data["is_trial"] as? Boolean ?: false,
                            offerType = data["offer_type"] as? String
                        )
                    } catch (e: Exception) {
                        Log.warning("Failed to parse entitlement document: ${e.message}")
                        null
                    }
                }

                Log.debug("EntitlementCache: received ${newEntitlements.size} entitlements from Firestore")
                setEntitlements(newEntitlements)
            }
    }

    /**
     * Stop observing Firestore entitlement changes.
     */
    fun stopObserving() {
        firestoreListener?.remove()
        firestoreListener = null
    }

    // -- Persistence --

    /**
     * Load cached entitlements from SharedPreferences.
     */
    private fun loadFromDisk() {
        val json = prefs.getString(KEY_ENTITLEMENTS, null) ?: return
        try {
            val array = JSONArray(json)
            val loaded = mutableListOf<Entitlement>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                loaded.add(Entitlement(
                    productId = obj.getString("product_id"),
                    store = obj.optString("store", "google_play"),
                    status = obj.optString("status", "unknown"),
                    expiresAt = obj.optString("expires_at", null),
                    isTrial = obj.optBoolean("is_trial", false),
                    offerType = obj.optString("offer_type", null)
                ))
            }
            entitlements = loaded
            Log.debug("EntitlementCache: loaded ${loaded.size} entitlements from disk")
        } catch (e: Exception) {
            Log.warning("EntitlementCache: failed to load from disk: ${e.message}")
        }
    }

    /**
     * Persist entitlements to SharedPreferences.
     */
    private fun saveToDisk(items: List<Entitlement>) {
        val array = JSONArray().apply {
            items.forEach { ent ->
                put(JSONObject().apply {
                    put("product_id", ent.productId)
                    put("store", ent.store)
                    put("status", ent.status)
                    if (ent.expiresAt != null) put("expires_at", ent.expiresAt)
                    put("is_trial", ent.isTrial)
                    if (ent.offerType != null) put("offer_type", ent.offerType)
                })
            }
        }
        prefs.edit().putString(KEY_ENTITLEMENTS, array.toString()).apply()
    }

    /**
     * Internal setter that updates in-memory cache, persists, and notifies listeners.
     */
    private fun setEntitlements(newEntitlements: List<Entitlement>) {
        entitlements = newEntitlements
        saveToDisk(newEntitlements)
        notifyListeners()
    }

    private fun notifyListeners() {
        for (listener in changeListeners) {
            listener(entitlements)
        }
    }

    companion object {
        private const val PREFS_NAME = "ai.appdna.sdk.billing"
        private const val KEY_ENTITLEMENTS = "entitlements_cache"

        /** Statuses considered "active" for entitlement checks. */
        private val ACTIVE_STATUSES = setOf("active", "trialing", "grace_period")
    }
}
