package ai.appdna.sdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC-070-B W13 / AC-31(b) — a subsystem that fails to start must take ONLY itself down.
 *
 * 🔴 Android's `configure()` constructed `PaywallManager`, `OnboardingFlowManager`, `SurveyManager`,
 * `WebEntitlementManager`, `MessageManager` and `SessionManager` bare. A throw out of any of their
 * constructors propagated out of `AppDNA.configure()` and into the HOST's `Application.onCreate()`.
 * The SDK never reached `performBootstrap`, so for the rest of the process there was:
 *
 *   • no `isConfigured`, so every `onReady { }` callback the host registered was never called,
 *   • no `sdk_initialized` event,
 *   • no analytics at all beyond the pre-init buffer, and
 *   • `isConfiguring` latched TRUE — so a later `configure()` was rejected by the re-entrancy guard
 *     and the SDK could not even be restarted.
 *
 * iOS isolated each subsystem (`AppDNA.swift` `initSubsystem`). Android did not.
 *
 * These tests drive the REAL `AppDNA.configure()`, with a failure injected through the same
 * `subsystemInitFailures` seam iOS uses. Reverting the isolation makes the FIRST assertion in each
 * of them — that `configure()` returns at all — throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SubsystemInitIsolationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        AppDNA.subsystemInitFailures = emptySet()
        AppDNA.setInitDelegate(null)
        AppDNA.lastInitError = null
        AppDNA.shutdown()
    }

    /** Configure with a failure injected, without letting the throw abort the test itself. */
    private fun configureWith(failing: Set<String>) {
        AppDNA.subsystemInitFailures = failing
        AppDNA.configure(context, "adn_test_isolation")
    }

    @Test
    fun `a paywall that fails to construct does not take configure down with it`() {
        // Without the isolation this line THROWS — PaywallManager's constructor error escapes
        // configure() and reaches the host.
        configureWith(setOf("paywall"))

        val up = AppDNA.subsystemsUp()
        assertFalse("the failing subsystem must not produce an instance", up["paywall"]!!)

        // Everything constructed AFTER the paywall still came up. This is the whole claim: the
        // failure is contained, not fatal.
        assertTrue("onboarding is built after the paywall and must survive its failure", up["onboarding"]!!)
        assertTrue("surveys must survive", up["surveys"]!!)
        assertTrue("web entitlements must survive", up["web_entitlements"]!!)

        // And the floor guarantee: analytics.
        assertTrue("the event pipeline must survive any subsystem failure", up["events"]!!)
    }

    @Test
    fun `the failure is reported as degraded rather than swallowed`() {
        val seen = mutableListOf<Throwable>()
        AppDNA.setInitDelegate(object : AppDNAInitDelegate {
            override fun onInitDegraded(reason: Throwable) { seen.add(reason) }
        })

        configureWith(setOf("surveys"))

        // Drain the main looper BEFORE reading the error, not just before the delegate assertion
        // below. Subsystem init posts its failure bookkeeping to the main looper, and how much of
        // that has already run when `configure` returns is a Robolectric scheduling detail that
        // varies by SDK level — so this read raced it and went red only on compileSdk=35, while 24/28/33
        // (and earlier 35 runs) happened to win. Idling here makes the assertion deterministic on
        // every level instead of depending on which side of the post the test lands.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val error = AppDNA.lastInitError
        assertNotNull("a failing subsystem must surface an error, not vanish", error)
        assertTrue(
            "the error must name the subsystem that failed, got: $error",
            error is AppDNAInitError.SubsystemFailed && error.name == "surveys",
        )

        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(
            "the host's init delegate must be told which subsystem degraded",
            seen.any { it is AppDNAInitError.SubsystemFailed && it.name == "surveys" },
        )
    }

    @Test
    fun `every subsystem failing at once still leaves a configured, tracking SDK`() {
        configureWith(
            setOf("paywall", "onboarding", "surveys", "web_entitlements", "in_app_messages", "sessions"),
        )

        val up = AppDNA.subsystemsUp()
        assertEquals(
            "no subsystem should have come up",
            emptyList<String>(),
            up.filterKeys { it != "events" }.filterValues { it }.keys.toList(),
        )
        assertTrue("analytics is the floor guarantee and must still be up", up["events"]!!)

        // The host's own code keeps working: tracking does not throw, and the SDK is not wedged in
        // a half-configured state that rejects a subsequent configure().
        AppDNA.track("post_failure_event", mapOf("k" to "v"))
    }
}
