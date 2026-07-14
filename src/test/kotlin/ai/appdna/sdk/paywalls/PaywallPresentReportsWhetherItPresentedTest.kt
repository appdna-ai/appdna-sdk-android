package ai.appdna.sdk.paywalls

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 `presentPaywall` returned `Unit`, so "nothing was presented" was UNREPORTABLE.
 *
 * A paywall id that is not in the published config, an SDK that was never configured, a
 * runtime-locked SDK — all three logged a line and returned. Every wrapper then resolved its promise
 * SUCCESSFULLY: `await AppDNA.paywall.present('typo_id')` told the host the paywall was shown, and no
 * paywall ever appeared. `presentOnboarding` and `showScreen` have always returned a Boolean; this is
 * the same contract on the surface that takes the money.
 *
 * These drive the REAL `PaywallManager` against a REAL `RemoteConfigManager` seeded with a REAL parsed
 * paywall config — the same lookup `present()` performs, not a lookalike.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PaywallPresentReportsWhetherItPresentedTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun paywallDoc(id: String, placement: String?): Map<String, Any> = buildMap {
        put("id", id)
        put("name", id)
        put("layout", mapOf("type" to "stack"))
        put("sections", emptyList<Map<String, Any>>())
        if (placement != null) put("placement", placement)
    }

    private fun managerWith(vararg paywalls: Pair<String, Map<String, Any>>): PaywallManager {
        val remoteConfig = RemoteConfigManager(
            firestorePath = null,
            storage = LocalStorage(ctx),
            configTTL = 3600L,
        )
        remoteConfig.loadBundledConfig(mapOf("paywalls" to paywalls.toMap()))
        val tracker = EventTracker(ai.appdna.sdk.IdentityManager(LocalStorage(ctx)), "1.0", "sandbox")
        return PaywallManager(remoteConfigManager = remoteConfig, eventTracker = tracker)
    }

    @Test
    fun `hasPaywall answers the id lookup present() would do`() {
        val manager = managerWith("pw_real" to paywallDoc("pw_real", placement = null))

        assertTrue("a published paywall id must be presentable", manager.hasPaywall("pw_real"))
        // The whole defect in one line: this used to be indistinguishable from the line above.
        assertFalse("a typo'd id must report that nothing will be shown", manager.hasPaywall("pw_reall"))
    }

    @Test
    fun `hasPaywallForPlacement runs the same selector presentByPlacement runs`() {
        val manager = managerWith(
            "pw_upgrade" to paywallDoc("pw_upgrade", placement = "upgrade"),
            "pw_other" to paywallDoc("pw_other", placement = "settings"),
        )

        assertTrue(manager.hasPaywallForPlacement("upgrade"))
        assertTrue(manager.hasPaywallForPlacement("settings"))
        assertFalse("no paywall is authored for this placement", manager.hasPaywallForPlacement("onboarding_end"))
    }

    /**
     * The facade's own arms. `AppDNA` is not configured in this test, so `paywallManager` is null —
     * the exact state a host is in when it calls `present()` before `configure()` resolves, and the
     * one that used to resolve the wrapper's promise with a cheerful success.
     */
    @Test
    fun `an unconfigured SDK reports false rather than silently doing nothing`() {
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        assertFalse(AppDNA.presentPaywall(activity, "pw_real"))
        assertFalse(AppDNA.presentPaywallByPlacement(activity, "upgrade"))
    }

    /**
     * 🔴 THE TEST ABOVE COULD NOT FAIL, BECAUSE IT COULD NOT REACH THE CODE.
     *
     * Every test of this Boolean either called [PaywallManager] directly, or called the facade on an
     * UNCONFIGURED SDK — which returns early at the null-manager guard and never reaches the line that
     * computes the answer (`val known = manager.hasPaywall(id)`). So that line was untested, and
     * mutating it to `val known = true` passed the entire suite: a typo'd paywall id would once again
     * report success to the wrapper while nothing was shown, and no test anywhere would notice.
     *
     * This one drives the FACADE with a CONFIGURED manager — the state a real host is in — so the
     * mutation now has somewhere to be caught.
     */
    @Test
    fun `a CONFIGURED SDK reports true for a real id and false for a typo`() {
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        AppDNA.paywallManager = managerWith("pw_real" to paywallDoc("pw_real", placement = "upgrade"))

        try {
            assertTrue(
                "a published paywall id reported that nothing was shown",
                AppDNA.presentPaywall(activity, "pw_real"),
            )
            assertFalse(
                "a TYPO'D paywall id reported success — the wrapper resolves its promise \"shown\" and " +
                    "the host believes a paywall appeared. Nothing did.",
                AppDNA.presentPaywall(activity, "pw_reall"),
            )

            assertTrue(AppDNA.presentPaywallByPlacement(activity, "upgrade"))
            assertFalse(
                "no paywall is authored for this placement, and the facade said one was shown",
                AppDNA.presentPaywallByPlacement(activity, "onboarding_end"),
            )
        } finally {
            // `AppDNA` is a process-global singleton — leaving a manager behind would leak into every
            // test that runs after this one.
            AppDNA.paywallManager = null
        }
    }
}
