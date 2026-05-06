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
            val data = json.optJSONObject("data")
                ?: throw VerificationException("Missing 'data' in verification response")
            // SPEC-070-A audit Round 2 finding 1: backend returns
            // `{ data: { entitled, subscription: <entity> } }` — must unwrap
            // `subscription` before parsing, otherwise every verify response
            // returns an Entitlement with productId="" and expiresAt=null.
            // iOS reference: Billing/ReceiptVerifier.swift:7-23.
            val subscription = data.optJSONObject("subscription") ?: data
            parseEntitlement(subscription)
        } catch (e: VerificationException) {
            throw e
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
            val dataArray = json.optJSONArray("data")
                ?: throw VerificationException("Missing 'data' in restore response")
            val entitlements = mutableListOf<Entitlement>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue
                entitlements.add(parseEntitlement(item))
            }
            Log.info("Restored ${entitlements.size} entitlements")
            entitlements
        } catch (e: VerificationException) {
            throw e
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
            val data = response.optJSONObject("data") ?: return emptyList()
            val subscriptions = data.optJSONArray("subscriptions") ?: return emptyList()
            val entitlements = mutableListOf<Entitlement>()
            for (i in 0 until subscriptions.length()) {
                val item = subscriptions.optJSONObject(i) ?: continue
                entitlements.add(parseEntitlement(item))
            }
            entitlements
        } catch (e: Exception) {
            Log.error("Failed to parse entitlements response: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse a JSON object into an Entitlement data class.
     *
     * SPEC-070-A A.13: backend `ReceiptValidationService.ts:96-119`
     * (`VerificationResult` shape, see `SubscriptionService.ts:6-38`)
     * returns **camelCase** fields. Snake_case keys are kept as fallbacks
     * so older shipped clients still parse responses from any future
     * mixed-shape rollout, and so disk-cached entitlements written under
     * the previous schema rehydrate correctly.
     */
    private fun parseEntitlement(json: JSONObject): Entitlement {
        // productId — camelCase primary, product_id fallback
        val productId = json.optStringOrNull("productId")
            ?: json.optStringOrNull("product_id")
            ?: ""

        // store — same key on both sides, but legacy server sometimes
        // omitted it for Google Play; default preserved.
        val store = json.optStringOrNull("store") ?: "google_play"

        // status — same key on both sides.
        val status = json.optStringOrNull("status") ?: "unknown"

        // expiresAt — backend returns ISO-formatted `currentPeriodEnd`
        // (Date serialised by JSON.stringify -> "2026-05-06T12:00:00Z").
        // Older payload sometimes called it `expires_at`. Accept both.
        val expiresAt = json.optStringOrNull("currentPeriodEnd")
            ?: json.optStringOrNull("expires_at")

        // isTrial — backend uses `isTrialPeriod`. Snake_case kept as fallback.
        val isTrial = when {
            json.has("isTrialPeriod") -> json.optBoolean("isTrialPeriod", false)
            json.has("is_trial") -> json.optBoolean("is_trial", false)
            else -> status == "trialing"
        }

        // offerType — nested under `offerApplied.offer_type` per backend
        // `SubscriptionService.ts:27-30`. Top-level `offer_type` kept as
        // legacy fallback.
        val offerType = json.optJSONObject("offerApplied")?.optStringOrNull("offer_type")
            ?: json.optStringOrNull("offer_type")

        return Entitlement(
            productId = productId,
            store = store,
            status = status,
            expiresAt = expiresAt,
            isTrial = isTrial,
            offerType = offerType
        )
    }
}

/**
 * `JSONObject.optString` returns the literal string "null" when a JSON
 * `null` value is present — and an empty string when the key is missing.
 * This helper turns both into a real Kotlin `null` so absent / explicit-null
 * fields don't sneak through as bogus strings.
 */
private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return if (v.isEmpty()) null else v
}

/**
 * Exception thrown when receipt verification fails.
 */
class VerificationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
