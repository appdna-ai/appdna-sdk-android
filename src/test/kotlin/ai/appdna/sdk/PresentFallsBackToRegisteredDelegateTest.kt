package ai.appdna.sdk

import ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 🔴 `AppDNA.presentOnboarding` / `presentPaywall` / `presentPaywallByPlacement` all take an OPTIONAL
 * listener and used to pass a null one straight through to the renderer.
 *
 * Every wrapper hits that path. React Native and Flutter have no Kotlin delegate object to hand over —
 * their delegate lives in JS/Dart behind `setDelegate` — so `AppdnaModule.presentOnboarding` calls
 * `AppDNA.presentOnboarding(activity, flowId)` with no listener at all. The result was invisible,
 * because the surfaces RENDER PERFECTLY with a null delegate:
 *
 *   - every onboarding + paywall callback was dead;
 *   - the veto seam never fired, because the renderer holds THAT reference;
 *   - the auth gate checks a DIFFERENT reference (`AppDNA.onboarding.listener`, which IS set), so it
 *     saw a delegate, passed, and asked nobody — an `email_login` / `verify_otp` step ADVANCED PAST
 *     THE CREDENTIAL STEP with no one authenticating the user;
 *   - and `PaywallManager`'s `onPromoCodeSubmit = if (listener != null) {...} else null` dropped promo
 *     codes into the no-delegate fallback that accepts ANY non-blank code.
 *
 * iOS fell back all along (`AppDNA.swift:607/632/660`). `check:delegate-fallback` only ever read the
 * iOS file — a gate that looks solely where the bug is already fixed — which is why this survived.
 *
 * These tests assert the RESOLUTION, not the render: what the SDK hands the renderer when the caller
 * omits the listener. That is the whole bug.
 */
@RunWith(RobolectricTestRunner::class)
class PresentFallsBackToRegisteredDelegateTest {

    private val onboardingDelegate = object : AppDNAOnboardingDelegate {}
    private val paywallDelegate = object : AppDNAPaywallDelegate {}

    @After
    fun tearDown() {
        AppDNA.onboarding.listener = null
        AppDNA.paywall.listener = null
    }

    @Test
    fun `an omitted onboarding listener resolves to the one the host registered`() {
        AppDNA.onboarding.setDelegate(onboardingDelegate)

        assertSame(
            "presentOnboarding(listener = null) must resolve to the registered delegate — otherwise " +
                "every RN/Flutter host runs onboarding with NO delegate and credential steps advance " +
                "unauthenticated",
            onboardingDelegate,
            AppDNA.resolveOnboardingListener(null),
        )
    }

    @Test
    fun `an explicitly passed onboarding listener still wins over the registered one`() {
        AppDNA.onboarding.setDelegate(onboardingDelegate)
        val explicit = object : AppDNAOnboardingDelegate {}

        assertSame(explicit, AppDNA.resolveOnboardingListener(explicit))
    }

    @Test
    fun `an omitted paywall listener resolves to the one the host registered`() {
        AppDNA.paywall.setDelegate(paywallDelegate)

        assertSame(
            "presentPaywall(listener = null) must resolve to the registered delegate — otherwise the " +
                "paywall renders, every callback is dead, and promo codes hit the no-delegate fallback " +
                "that accepts any non-blank code",
            paywallDelegate,
            AppDNA.resolvePaywallListener(null),
        )
    }

    @Test
    fun `an explicitly passed paywall listener still wins over the registered one`() {
        AppDNA.paywall.setDelegate(paywallDelegate)
        val explicit = object : AppDNAPaywallDelegate {}

        assertSame(explicit, AppDNA.resolvePaywallListener(explicit))
    }

    @Test
    fun `with no delegate registered anywhere, resolution is null — not a fabricated one`() {
        assertSame(null, AppDNA.resolveOnboardingListener(null))
        assertSame(null, AppDNA.resolvePaywallListener(null))
    }
}
