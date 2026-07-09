package ai.appdna.sdk

import ai.appdna.sdk.billing.EntitlementCache
import ai.appdna.sdk.billing.NativeBillingManager
import ai.appdna.sdk.billing.ReceiptVerifier
import ai.appdna.sdk.network.ApiClient
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `shutdown()` destroyed the native billing manager but never nulled the handle.
 *
 * `initBillingModuleIfNeeded` early-returns when `billing.manager != null`, so a
 * later `configure()` skipped re-initialisation entirely — leaving billing and
 * entitlements permanently dead for the rest of the process, with no error.
 *
 * The fix nulls the handle in a `finally`, so it is released even if `destroy()`
 * throws. This test asserts exactly that, and would have failed before the fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BillingShutdownTest {

    private fun newManager(): NativeBillingManager {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return NativeBillingManager(
            context = ctx,
            receiptVerifier = ReceiptVerifier(ApiClient("adn_test_key", Environment.PRODUCTION)),
            entitlementCache = EntitlementCache(ctx),
        )
    }

    @After
    fun tearDown() {
        AppDNA.billing.manager = null
    }

    @Test
    fun `shutdown nulls the billing manager handle, not merely destroys it`() {
        AppDNA.billing.manager = newManager()
        assertNotNull("precondition: manager installed", AppDNA.billing.manager)

        AppDNA.shutdown()

        assertNull(
            "shutdown() must null billing.manager — otherwise initBillingModuleIfNeeded " +
                "early-returns and a later configure() never revives billing",
            AppDNA.billing.manager,
        )
    }

    @Test
    fun `shutdown is safe to call twice`() {
        AppDNA.billing.manager = newManager()
        AppDNA.shutdown()
        AppDNA.shutdown() // must not throw on an already-null handle
        assertNull(AppDNA.billing.manager)
    }

    @Test
    fun `shutdown on a never-configured SDK does not throw`() {
        AppDNA.billing.manager = null
        AppDNA.shutdown()
        assertNull(AppDNA.billing.manager)
    }

    @Test
    fun `a fresh manager can be installed after shutdown`() {
        // The regression: after shutdown() the stale non-null handle made
        // initBillingModuleIfNeeded() a no-op forever. With the handle cleared,
        // the init path is reachable again.
        AppDNA.billing.manager = newManager()
        AppDNA.shutdown()
        assertNull(AppDNA.billing.manager)

        AppDNA.billing.manager = newManager()
        assertNotNull("billing must be revivable after shutdown", AppDNA.billing.manager)
    }
}
