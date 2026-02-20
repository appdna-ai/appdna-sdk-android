package ai.appdna.sdk.billing

import ai.appdna.sdk.Log
import com.android.billingclient.api.*

/**
 * Resolves product pricing information from Google Play Billing.
 *
 * Queries ProductDetails from Google Play and extracts localized pricing,
 * offer tokens, and trial/introductory pricing phases for subscriptions.
 */
internal class PriceResolver(
    private val connectionManager: BillingConnectionManager
) {

    /**
     * Fetch product details for a list of subscription product IDs.
     *
     * @param productIds The Google Play product IDs to query.
     * @return List of resolved ProductInfo, one per found product.
     */
    suspend fun getSubscriptionProducts(productIds: List<String>): List<ProductInfo> {
        if (productIds.isEmpty()) return emptyList()

        val client = connectionManager.awaitConnectedClient() ?: run {
            Log.warning("PriceResolver: BillingClient not available")
            return emptyList()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productIds.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            })
            .build()

        val result = client.queryProductDetails(params)

        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.warning("PriceResolver: query failed with code ${result.billingResult.responseCode}")
            return emptyList()
        }

        return result.productDetailsList?.map { details ->
            mapProductDetails(details)
        } ?: emptyList()
    }

    /**
     * Fetch product details for in-app (one-time) products.
     *
     * @param productIds The Google Play product IDs to query.
     * @return List of resolved ProductInfo.
     */
    suspend fun getInAppProducts(productIds: List<String>): List<ProductInfo> {
        if (productIds.isEmpty()) return emptyList()

        val client = connectionManager.awaitConnectedClient() ?: run {
            Log.warning("PriceResolver: BillingClient not available")
            return emptyList()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productIds.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            })
            .build()

        val result = client.queryProductDetails(params)

        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.warning("PriceResolver: INAPP query failed with code ${result.billingResult.responseCode}")
            return emptyList()
        }

        return result.productDetailsList?.map { details ->
            ProductInfo(
                id = details.productId,
                name = details.name,
                description = details.description,
                formattedPrice = details.oneTimePurchaseOfferDetails?.formattedPrice ?: "",
                priceMicros = details.oneTimePurchaseOfferDetails?.priceAmountMicros ?: 0,
                currencyCode = details.oneTimePurchaseOfferDetails?.priceCurrencyCode ?: "",
                offerToken = null
            )
        } ?: emptyList()
    }

    /**
     * Get all available offers for a subscription product, including
     * base plans, free trials, and introductory offers.
     *
     * @param productId The subscription product ID.
     * @return List of OfferDetails for the product.
     */
    suspend fun getOffers(productId: String): List<OfferDetails> {
        val client = connectionManager.awaitConnectedClient() ?: return emptyList()

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            ))
            .build()

        val result = client.queryProductDetails(params)
        val details = result.productDetailsList?.firstOrNull() ?: return emptyList()

        return details.subscriptionOfferDetails?.map { offer ->
            val phases = offer.pricingPhases.pricingPhaseList
            val basePricingPhase = phases.lastOrNull()
            val trialPhase = phases.find { it.priceAmountMicros == 0L }
            val introPhase = phases.find {
                it.priceAmountMicros > 0L && it != basePricingPhase && phases.size > 1
            }

            OfferDetails(
                offerToken = offer.offerToken,
                offerId = offer.offerId,
                basePlanId = offer.basePlanId,
                formattedPrice = basePricingPhase?.formattedPrice ?: "",
                priceMicros = basePricingPhase?.priceAmountMicros ?: 0,
                currencyCode = basePricingPhase?.priceCurrencyCode ?: "",
                billingPeriod = basePricingPhase?.billingPeriod ?: "",
                hasTrial = trialPhase != null,
                trialPeriod = trialPhase?.billingPeriod,
                hasIntroOffer = introPhase != null,
                introFormattedPrice = introPhase?.formattedPrice,
                introPriceMicros = introPhase?.priceAmountMicros,
                introPeriod = introPhase?.billingPeriod,
                introCycles = introPhase?.billingCycleCount ?: 0
            )
        } ?: emptyList()
    }

    /**
     * Map a ProductDetails to our simplified ProductInfo model.
     * Uses the base offer (last pricing phase of the first offer).
     */
    private fun mapProductDetails(details: ProductDetails): ProductInfo {
        val offer = details.subscriptionOfferDetails?.firstOrNull()
        val basePricingPhase = offer?.pricingPhases?.pricingPhaseList?.lastOrNull()

        return ProductInfo(
            id = details.productId,
            name = details.name,
            description = details.description,
            formattedPrice = basePricingPhase?.formattedPrice ?: "",
            priceMicros = basePricingPhase?.priceAmountMicros ?: 0,
            currencyCode = basePricingPhase?.priceCurrencyCode ?: "",
            offerToken = offer?.offerToken
        )
    }
}

/**
 * Detailed offer information for a subscription product.
 * Includes base pricing, trial, and introductory offer phases.
 */
data class OfferDetails(
    /** Token required to initiate purchase for this offer. */
    val offerToken: String,
    /** Google Play offer ID (null for base plans). */
    val offerId: String?,
    /** The base plan ID this offer belongs to. */
    val basePlanId: String,
    /** Formatted base price string (e.g., "$9.99"). */
    val formattedPrice: String,
    /** Base price in micros (e.g., 9990000 for $9.99). */
    val priceMicros: Long,
    /** ISO 4217 currency code (e.g., "USD"). */
    val currencyCode: String,
    /** ISO 8601 billing period (e.g., "P1M" for monthly, "P1Y" for yearly). */
    val billingPeriod: String,
    /** Whether this offer includes a free trial phase. */
    val hasTrial: Boolean,
    /** Trial period duration (ISO 8601), if applicable. */
    val trialPeriod: String?,
    /** Whether this offer includes an introductory price phase. */
    val hasIntroOffer: Boolean,
    /** Formatted introductory price string, if applicable. */
    val introFormattedPrice: String?,
    /** Introductory price in micros, if applicable. */
    val introPriceMicros: Long?,
    /** Introductory period duration (ISO 8601), if applicable. */
    val introPeriod: String?,
    /** Number of introductory billing cycles. */
    val introCycles: Int
)
