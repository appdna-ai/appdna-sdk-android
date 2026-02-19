package ai.appdna.sdk.billing

import ai.appdna.sdk.Log
import ai.appdna.sdk.network.ApiClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles server-side receipt verification and purchase restoration.
 *
 * Communicates with the AppDNA backend billing endpoints:
 * - POST /api/v1/billing/verify — Verify a single purchase receipt
 * - POST /api/v1/billing/restore — Restore and verify multiple purchases
 * - GET  /api/v1/billing/entitlements — Fetch current active entitlements
 */
internal class ReceiptVerifier(
    private val apiClient: ApiClient
) {

    /**
     * Verify a purchase with the backend and return the resulting entitlement.
     *
     * @param purchaseToken The Google Play purchase token.
     * @param productId The product ID purchased.
     * @param platform The platform identifier ("android").
     * @param paywallId Optional paywall ID for attribution.
     * @param experimentId Optional experiment ID for attribution.
     * @return The verified Entitlement.
     * @throws VerificationException if the server rejects or cannot verify the receipt.
     */
    suspend fun verify(
        purchaseToken: String,
        productId: String,
        platform: String = "android",
        paywallId: String? = null,
        experimentId: String? = null
    ): Entitlement {
        val body = JSONObject().apply {
            put("platform", platform)
            put("transaction", JSONObject().apply {
                put("token", purchaseToken)
                put("productId", productId)
            })
            if (paywallId != null) put("paywall_id", paywallId)
            if (experimentId != null) put("experiment_id", experimentId)
        }

        Log.debug("Verifying receipt for product: $productId")

        val response = apiClient.post("/api/v1/billing/verify", body.toString())
            ?: throw VerificationException("No response from server during verification")

        return try {
            val json = JSONObject(response)
            val data = json.getJSONObject("data")
            parseEntitlement(data)
        } catch (e: Exception) {
            Log.error("Failed to parse verification response: ${e.message}")
            throw VerificationException("Failed to parse verification response: ${e.message}", e)
        }
    }

    /**
     * Restore purchases by sending all known purchase tokens to the backend.
     *
     * @param transactions List of maps with "token" and "productId" keys.
     * @return List of active Entitlements restored.
     */
    suspend fun restore(
        transactions: List<Map<String, String>>
    ): List<Entitlement> {
        if (transactions.isEmpty()) {
            Log.debug("No transactions to restore")
            return emptyList()
        }

        val transactionsArray = JSONArray().apply {
            transactions.forEach { tx ->
                put(JSONObject().apply {
                    put("token", tx["token"])
                    put("productId", tx["productId"])
                })
            }
        }

        val body = JSONObject().apply {
            put("platform", "android")
            put("transactions", transactionsArray)
        }

        Log.debug("Restoring ${transactions.size} purchases")

        val response = apiClient.post("/api/v1/billing/restore", body.toString())
            ?: throw VerificationException("No response from server during restore")

        return try {
            val json = JSONObject(response)
            val dataArray = json.getJSONArray("data")
            val entitlements = mutableListOf<Entitlement>()
            for (i in 0 until dataArray.length()) {
                entitlements.add(parseEntitlement(dataArray.getJSONObject(i)))
            }
            Log.info("Restored ${entitlements.size} entitlements")
            entitlements
        } catch (e: Exception) {
            Log.error("Failed to parse restore response: ${e.message}")
            throw VerificationException("Failed to parse restore response: ${e.message}", e)
        }
    }

    /**
     * Fetch current active entitlements from the server.
     *
     * @return List of currently active Entitlements, or empty if none.
     */
    suspend fun fetchEntitlements(): List<Entitlement> {
        Log.debug("Fetching entitlements from server")

        val response = apiClient.get("/api/v1/billing/entitlements")
            ?: return emptyList()

        return try {
            val data = response.getJSONObject("data")
            val subscriptions = data.getJSONArray("subscriptions")
            val entitlements = mutableListOf<Entitlement>()
            for (i in 0 until subscriptions.length()) {
                entitlements.add(parseEntitlement(subscriptions.getJSONObject(i)))
            }
            entitlements
        } catch (e: Exception) {
            Log.error("Failed to parse entitlements response: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse a JSON object into an Entitlement data class.
     */
    private fun parseEntitlement(json: JSONObject): Entitlement {
        return Entitlement(
            productId = json.optString("product_id", ""),
            store = json.optString("store", "google_play"),
            status = json.optString("status", "unknown"),
            expiresAt = json.optString("expires_at", null),
            isTrial = json.optBoolean("is_trial", false),
            offerType = json.optString("offer_type", null)
        )
    }
}

/**
 * Exception thrown when receipt verification fails.
 */
class VerificationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
