package ai.appdna.sdk.webentitlements

import android.content.Context
import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.AppDNA
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Web subscription entitlement model.
 */
data class WebEntitlement(
    val isActive: Boolean,
    val planName: String?,
    val priceId: String?,
    val interval: String?,
    val status: String,
    val currentPeriodEnd: Long?,
    val trialEnd: Long?
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("isActive", isActive)
        put("status", status)
        if (planName != null) { put("planName", planName); put("plan_name", planName) }
        if (priceId != null) { put("priceId", priceId); put("price_id", priceId) }
        if (interval != null) { put("interval", interval) }
        if (currentPeriodEnd != null) { put("currentPeriodEnd", currentPeriodEnd); put("current_period_end", currentPeriodEnd) }
        if (trialEnd != null) { put("trialEnd", trialEnd); put("trial_end", trialEnd) }
    }

    companion object {
        fun fromMap(data: Map<String, Any>): WebEntitlement {
            val status = data["status"] as? String ?: "canceled"
            return WebEntitlement(
                isActive = status in listOf("active", "trialing"),
                planName = data["plan_name"] as? String,
                priceId = data["price_id"] as? String,
                interval = data["interval"] as? String,
                status = status,
                currentPeriodEnd = (data["current_period_end"] as? Number)?.toLong(),
                trialEnd = (data["trial_end"] as? Number)?.toLong()
            )
        }
    }
}

/**
 * Observes web subscription entitlements from Firestore.
 * Path: /orgs/{orgId}/apps/{appId}/users/{userId}/web_entitlements
 */
internal class WebEntitlementManager(
    private val eventTracker: EventTracker?,
    private val context: Context? = null
) {
    private var listener: ListenerRegistration? = null
    var currentEntitlement: WebEntitlement? = null
        private set
    private var previousStatus: String? = null

    /** Listeners for entitlement changes. */
    private val changeListeners = CopyOnWriteArrayList<(WebEntitlement?) -> Unit>()

    init {
        loadCachedEntitlement()
    }

    fun addChangeListener(listener: (WebEntitlement?) -> Unit) {
        changeListeners.add(listener)
    }

    private fun loadCachedEntitlement() {
        val prefs = context?.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE) ?: return
        val json = prefs.getString("web_entitlement_cache", null) ?: return
        try {
            val obj = JSONObject(json)
            val data = obj.keys().asSequence().associateWith { obj.get(it) as Any }
            currentEntitlement = WebEntitlement.fromMap(data)
            previousStatus = currentEntitlement?.status
        } catch (_: Exception) {}
    }

    private fun cacheEntitlement(entitlement: WebEntitlement?) {
        val prefs = context?.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE) ?: return
        if (entitlement != null) {
            prefs.edit().putString("web_entitlement_cache", JSONObject(entitlement.toMap()).toString()).apply()
        } else {
            prefs.edit().remove("web_entitlement_cache").apply()
        }
    }

    fun startObserving(orgId: String, appId: String, userId: String) {
        stopObserving()

        val path = "orgs/$orgId/apps/$appId/users/$userId/web_entitlements/current"
        Log.debug("WebEntitlementManager: observing $path")

        val db = AppDNA.firestoreDB ?: run {
            Log.warning("WebEntitlementManager: Firestore not available — skipping real-time sync")
            listener = null
            return
        }
        listener = db.document(path)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.error("WebEntitlement listener error: ${error.message}")
                    return@addSnapshotListener
                }

                val data = snapshot?.data
                if (data == null) {
                    if (currentEntitlement != null) {
                        currentEntitlement = null
                        cacheEntitlement(null)
                        notifyListeners(null)
                    }
                    return@addSnapshotListener
                }

                val entitlement = WebEntitlement.fromMap(data)
                val prevStatus = previousStatus
                currentEntitlement = entitlement
                previousStatus = entitlement.status
                cacheEntitlement(entitlement)

                notifyListeners(entitlement)

                // Track events
                if (entitlement.isActive && (prevStatus == null || prevStatus in listOf("canceled", "past_due"))) {
                    eventTracker?.track("web_entitlement_activated", mapOf(
                        "plan_name" to (entitlement.planName ?: ""),
                        "status" to entitlement.status
                    ))
                } else if (!entitlement.isActive && prevStatus in listOf("active", "trialing")) {
                    eventTracker?.track("web_entitlement_expired", mapOf(
                        "plan_name" to (entitlement.planName ?: ""),
                        "reason" to entitlement.status
                    ))
                }
            }
    }

    fun stopObserving() {
        listener?.remove()
        listener = null
    }

    private fun notifyListeners(entitlement: WebEntitlement?) {
        for (cb in changeListeners) {
            cb(entitlement)
        }
    }
}
