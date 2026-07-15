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

    /** Current cached entitlements. Round-36 — @Volatile: written from the Firestore listener thread + purchase/restore, read from getters on other threads. */
    @Volatile var entitlements: List<Entitlement> = emptyList()
        private set

    /** Whether the user has any active subscription. */
    val hasActiveSubscription: Boolean
        get() = entitlements.any { it.status in ACTIVE_STATUSES }

    /** Listeners notified when entitlements change. */
    private val changeListeners = CopyOnWriteArrayList<(List<Entitlement>) -> Unit>()

    /** Firestore real-time listener registration. */
    private var firestoreListener: ListenerRegistration? = null

    /** Active product-id set at the last change fan-out — diff-guards spurious callbacks (iOS parity). */
    @Volatile private var lastNotifiedProductIds: Set<String> = emptySet()
    /** Guards the diff-guard compare-and-set; setEntitlements runs from the Firestore listener thread AND purchase/restore callbacks. */
    private val notifyLock = Any()

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
     * SPEC-070-A A.23 — Listens to a single document at
     * `orgs/{orgId}/apps/{appId}/users/{userId}/entitlements/current` and parses
     * its `subscriptions[]` array, mirroring iOS `EntitlementCache.swift:30-39,63-64`.
     *
     * Backend writes the doc at this path — see
     * `src/modules/monetization/services/EntitlementSyncService.ts:95`.
     *
     * Previously this code treated the same path as a *collection* and called
     * `db.collection(path).addSnapshotListener` — which never matched the
     * backend's document layout, so subscription state never streamed in.
     *
     * @param orgId The organization ID.
     * @param appId The app ID.
     * @param userId The user ID.
     */
    fun startObserving(orgId: String, appId: String, userId: String) {
        stopObserving()

        val basePath = "orgs/$orgId/apps/$appId/users/$userId"
        val docPath = "$basePath/entitlements/current"
        Log.debug("EntitlementCache: observing Firestore at $docPath")

        val db = AppDNA.firestoreDB ?: run {
            Log.warning("EntitlementCache: Firestore not available — skipping real-time sync")
            return
        }
        firestoreListener = db
            .document(docPath)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.error("EntitlementCache Firestore error: ${error.message}")
                    return@addSnapshotListener
                }

                // SPEC-070-A H.25: empty-snapshot debounce. A null/missing
                // snapshot can happen during a reconnect or when the doc has
                // not yet been provisioned for this user — both are
                // **transient** states that must NOT clobber a non-empty
                // cache. We only treat the snapshot as authoritative when:
                //   - the doc EXISTS and
                //   - it explicitly carries a `subscriptions` field (even
                //     an empty array means "the server says no entitlements").
                //
                // Previously, an empty snapshot during reconnect would clear
                // the cache and stamp out an active subscription locally,
                // briefly toggling paywalls back on for the user.
                val data = snapshot?.data
                if (snapshot == null || !snapshot.exists() || data == null) {
                    Log.debug("EntitlementCache: empty/missing snapshot — preserving cached entitlements")
                    return@addSnapshotListener
                }
                if (!data.containsKey("subscriptions")) {
                    Log.debug("EntitlementCache: snapshot has no `subscriptions` field — preserving cached entitlements")
                    return@addSnapshotListener
                }

                val newEntitlements = parseSubscriptions(data)
                Log.debug("EntitlementCache: received ${newEntitlements.size} entitlements from Firestore")
                setEntitlements(newEntitlements)
            }
    }

    /**
     * Parse the `subscriptions[]` array from a Firestore document map into
     * [Entitlement] instances. Mirrors iOS `parseFirestoreData`
     * (EntitlementCache.swift:63-81).
     *
     * Visible to tests via `internal` (delegates to the static helper in
     * the companion object so unit tests can invoke without a Context).
     */
    internal fun parseSubscriptions(data: Map<String, Any?>): List<Entitlement> =
        parseSubscriptionsImpl(data)

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
            // Round-36 — seed the notify diff-guard from the disk-loaded set so the FIRST Firestore
            // snapshot that merely CONFIRMS the persisted entitlements doesn't fire a spurious
            // onEntitlementsChanged. loadFromDisk bypasses setEntitlements, so without this the guard
            // stayed empty and `empty != {persisted}` fired one no-op callback on every cold start.
            // Runs at init (single-threaded, before any listener); @Volatile makes it visible.
            lastNotifiedProductIds = loaded.map { it.productId }.toSet()
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
     *
     * SPEC-070-A B.7 — fan out to:
     *   1. all callbacks registered via [addChangeListener] (host onEntitlementsChanged
     *      callback via `BillingModule.onEntitlementsChanged(callback)`)
     *   2. the typed `AppDNABillingDelegate.onEntitlementsChanged(...)` registered
     *      on the BillingModule (`AppDNA.billing.setDelegate(...)`).
     * Mirrors iOS `EntitlementCache.swift` `NotificationCenter .entitlementsChanged`
     * fan-out.
     */
    private fun setEntitlements(newEntitlements: List<Entitlement>) {
        entitlements = newEntitlements
        saveToDisk(newEntitlements)
        // Round-35/36 — diff-guard the change fan-out (listeners + billing delegate) on the active
        // product-id set, matching iOS BillingModule.refreshEntitlementCache (posts only when
        // newIds != lastKnownEntitlementIds). Android previously notified UNCONDITIONALLY on every
        // update()/replaceAll(), so re-purchasing an already-held entitlement or a restore that
        // returned the already-cached set fired a spurious onEntitlementsChanged that iOS never did.
        // The cache (entitlements + disk) still updates every call so a same-id expiry change persists;
        // only the notification is gated. Round-36 — the compare-and-set runs under `notifyLock`
        // because setEntitlements is reachable from the Firestore listener thread AND purchase/restore
        // callbacks concurrently; notify OUTSIDE the lock (like iOS releases its lock before posting).
        val newIds = newEntitlements.map { it.productId }.toSet()
        val changed = synchronized(notifyLock) {
            if (newIds == lastNotifiedProductIds) {
                false
            } else {
                lastNotifiedProductIds = newIds
                true
            }
        }
        if (!changed) return
        notifyListeners()
        notifyBillingDelegate()
    }

    /**
     * Test seam: fire the change listeners without a real purchase.
     *
     * `internal`, so it is invisible to hosts. The alternative was to leave the pre-init-listener
     * regression untestable, and an untestable fix is a fix that comes back.
     */
    internal fun notifyChangeListenersForTesting(values: List<Entitlement>) {
        for (listener in changeListeners) listener(values)
    }

    private fun notifyListeners() {
        for (listener in changeListeners) {
            try {
                listener(entitlements)
            } catch (e: Throwable) {
                Log.warning("EntitlementCache: listener threw: ${e.message}")
            }
        }
    }

    /**
     * SPEC-070-A B.7 — fire the typed delegate's onEntitlementsChanged. Read
     * fresh on every call so a delegate registered after configure() still
     * receives updates. Posted to main thread so host code never runs on
     * the Firestore listener thread.
     */
    private fun notifyBillingDelegate() {
        val delegate = try {
            AppDNA.billing.billingListener
        } catch (_: Throwable) {
            return
        } ?: return
        val current = entitlements
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    delegate.onEntitlementsChanged(current)
                } catch (e: Throwable) {
                    Log.warning("AppDNABillingDelegate.onEntitlementsChanged threw: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.warning("EntitlementCache: delegate fan-out failed: ${e.message}")
        }
    }

    companion object {
        private const val PREFS_NAME = "ai.appdna.sdk.billing"
        private const val KEY_ENTITLEMENTS = "entitlements_cache"

        /** Statuses considered "active" for entitlement checks. */
        private val ACTIVE_STATUSES = setOf("active", "trialing", "grace_period")

        /**
         * Static parser for Firestore subscription documents. Pure — no I/O,
         * safe to call from unit tests without a `Context`.
         */
        @Suppress("UNCHECKED_CAST")
        internal fun parseSubscriptionsImpl(data: Map<String, Any?>): List<Entitlement> {
            val raw = data["subscriptions"] as? List<Map<String, Any?>> ?: return emptyList()
            return raw.mapNotNull { sub ->
                try {
                    val productId = sub["product_id"] as? String ?: return@mapNotNull null
                    val store = sub["store"] as? String ?: return@mapNotNull null
                    val status = sub["status"] as? String ?: return@mapNotNull null
                    Entitlement(
                        productId = productId,
                        store = store,
                        status = status,
                        expiresAt = sub["expires_at"] as? String,
                        isTrial = (sub["is_trial"] as? Boolean) ?: false,
                        offerType = sub["offer_type"] as? String,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
