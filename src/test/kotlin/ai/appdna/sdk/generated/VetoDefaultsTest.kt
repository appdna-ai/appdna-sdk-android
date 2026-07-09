package ai.appdna.sdk.generated

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A host that registers a delegate but does not override a veto method gets the
 * interface default. Android shipped `onPromoCodeSubmit(...): Boolean = true`,
 * so such a host **granted every promo code a user typed** — while iOS and
 * Flutter both rejected.
 *
 * The blanket `= true` default is shared with the three *sync* vetoes, where
 * `true` means "allow" and is correct. So the fix is an async-only carve-out in
 * `emit-delegates.ts`, not a wholesale flip. This test pins both halves.
 */
class VetoDefaultsTest {

    /** A host that overrides nothing — the delegate-less case. */
    private val paywall = object : AppDNAPaywallDelegate {}
    private val messages = object : AppDNAInAppMessageDelegate {}
    private val deepLinks = object : AppDNADeepLinkDelegate {}
    private val screens = object : AppDNAScreenDelegate {}

    @Test
    fun `a non-overriding host REJECTS promo codes`() = runTest {
        assertFalse(
            "a delegate-less host must not grant an unvalidated promo code",
            paywall.onPromoCodeSubmit(paywallId = "pw_1", code = "FREEMONEY"),
        )
    }

    @Test
    fun `promo rejection does not depend on the code`() = runTest {
        for (code in listOf("", "  ", "SAVE50", "'; DROP TABLE--")) {
            assertFalse(paywall.onPromoCodeSubmit("pw_1", code))
        }
    }

    @Test
    fun `the three sync vetos still default to ALLOW`() {
        // Fail-open by design: a delegate-less host must not have its in-app
        // messages, deep links, or screen actions silently suppressed.
        assertTrue(messages.shouldShowMessage("msg_1"))
        assertTrue(deepLinks.shouldOpen("https://example.com", emptyMap()))
        assertTrue(screens.onScreenAction("screen_1", emptyMap()))
    }
}
