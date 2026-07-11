package ai.appdna.sdk

import ai.appdna.sdk.billing.Entitlement
import ai.appdna.sdk.billing.EntitlementCache
import ai.appdna.sdk.billing.NativeBillingManager
import ai.appdna.sdk.billing.ReceiptVerifier
import ai.appdna.sdk.network.ApiClient
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A host that subscribes to entitlement changes before billing has finished initialising must not
 * lose the subscription.
 *
 * 🔴 `onEntitlementsChanged` was `manager?.entitlementCache?.addChangeListener(cb)` — a null-safe
 * DROP. `billing.manager` is only assigned inside `performBootstrap`, which is launched from
 * `configure()` and completes after it returns, so the ordinary sequence
 *
 *     await AppDNA.configure(...)
 *     AppDNABilling.onEntitlementsChanged(cb)
 *
 * hit a null manager and the callback vanished. The React Native facade latches "observer started"
 * after its first call and never retries, so such a host received no entitlement change for the whole
 * app session — no unlock after a purchase, no revocation after a refund. iOS never had this: its
 * handlers live in a static dictionary that does not care about init order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EntitlementListenerBeforeInitTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun newManager(): NativeBillingManager = NativeBillingManager(
        context = ctx,
        receiptVerifier = ReceiptVerifier(ApiClient("adn_test_key", Environment.PRODUCTION)),
        entitlementCache = EntitlementCache(ctx),
    )

    @After
    fun tearDown() {
        AppDNA.billing.manager = null
    }

    @Test
    fun `a listener registered before billing exists is attached once billing arrives`() {
        AppDNA.billing.manager = null

        var seen: List<Entitlement>? = null
        val listener: (List<Entitlement>) -> Unit = { seen = it }

        // Subscribe FIRST — this is the drop that used to happen silently.
        AppDNA.billing.onEntitlementsChanged(listener)

        // Bootstrap finishes and billing appears.
        val manager = newManager()
        AppDNA.billing.manager = manager

        manager.entitlementCache.notifyChangeListenersForTesting(emptyList())

        assertEquals(
            "the pre-init listener was dropped — the host would never learn about a purchase",
            emptyList<Entitlement>(),
            seen,
        )
    }

    @Test
    fun `unsubscribing before billing arrives really unsubscribes`() {
        AppDNA.billing.manager = null

        var calls = 0
        val listener: (List<Entitlement>) -> Unit = { calls++ }

        AppDNA.billing.onEntitlementsChanged(listener)
        AppDNA.billing.removeEntitlementsChangedListener(listener)

        val manager = newManager()
        AppDNA.billing.manager = manager
        manager.entitlementCache.notifyChangeListenersForTesting(emptyList())

        assertEquals("a listener removed before init was re-attached by the flush", 0, calls)
    }
}
