package ai.appdna.sdk

import ai.appdna.sdk.core.SessionDataStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 🔴 USER A'S ONBOARDING ANSWERS SURVIVED THE SIGN-OUT AND RENDERED INTO USER B'S PAYWALL.
 *
 * `SessionDataStore` is a PERSISTED process-global — SharedPreferences here, UserDefaults on iOS —
 * holding three buckets: onboarding responses, computed data, session data. `AppDNA.reset()` cleared
 * identity, exposures, message session and survey session, and NEVER TOUCHED IT. Neither did
 * `shutdown()`. `clearAll()` existed on both platforms and had ZERO callers.
 *
 * So on a shared device — a family tablet, a hot-desk phone, a demo unit, a resold handset — user B
 * could read user A's onboarding answers and structured location straight back out:
 *
 *     AppDNA.getOnboardingResponses()   // A's email, name, goals
 *     AppDNA.session.get("…")           // A's session data
 *     AppDNA.getLocationData(fieldId)   // A's structured location
 *
 * And worse than the read: `TemplateEngine.buildContext()` feeds all three buckets into the `{{…}}`
 * namespace, so A's answers RENDERED INTO B's paywall, onboarding and in-app-message copy. "Welcome
 * back, {{onboarding.first_name}}" — with the wrong name. It survived app restarts, because the store
 * is on disk.
 *
 * The file's own comment said the data was "not sensitive". It is an email, a name and a location.
 *
 * These tests assert the LEAK IS CLOSED, not that a method was called: they write the data, reset,
 * and then read through the same public API a host (or the template engine) would use. A test that
 * asserted `verify(store).clearAll()` would pass against a `clearAll()` that does nothing.
 */
@RunWith(RobolectricTestRunner::class)
class ResetClearsSessionDataTest {

    private lateinit var store: SessionDataStore

    @Before
    fun setUp() {
        SessionDataStore.initialize(ApplicationProvider.getApplicationContext())
        store = SessionDataStore.instance!!
        store.clearAll()
    }

    /**
     * `AppDNA` is a PROCESS-GLOBAL singleton and these tests drive the real `reset()` and `shutdown()`,
     * so they mutate state every other test in the module shares. Leaving it dirty is not a theoretical
     * risk: without this, `SubsystemInitIsolationTest` — which passes alone and passed before this test
     * existed — failed with a Firebase error, because it inherited a torn-down SDK.
     *
     * A test that only passes when it runs first is a test that will fail for somebody else, on a day
     * unrelated to whatever they changed. Mirrors SubsystemInitIsolationTest's own tearDown.
     */
    @After
    fun tearDown() {
        store.clearAll()
        AppDNA.subsystemInitFailures = emptySet()
        AppDNA.setInitDelegate(null)
        AppDNA.lastInitError = null
        AppDNA.shutdown()
    }

    /** User A fills in an onboarding flow: an email, a name, a location. Real PII. */
    private fun signInAsUserAAndAnswerOnboarding() {
        // Responses are keyed by STEP id, each step holding its own field map — the shape the flow
        // manager persists when onboarding completes.
        store.setOnboardingResponses(
            mapOf(
                "step_email" to mapOf("email" to "alice@example.com"),
                "step_name" to mapOf("first_name" to "Alice"),
                "step_goal" to mapOf("goal" to "lose_weight"),
            ),
        )
        store.mergeComputedData(mapOf("bmi" to 22.4))
        store.setSessionData("last_city", "Warsaw")
    }

    @Test
    fun `reset clears every bucket — user B cannot read user A's onboarding answers`() {
        signInAsUserAAndAnswerOnboarding()

        // Sanity: the data really is there before the sign-out. Without this, a test that only checks
        // "empty after reset" would also pass against a store that never stored anything.
        assertEquals("alice@example.com", store.onboardingResponses["step_email"]?.get("email"))
        assertEquals("Warsaw", store.getSessionData("last_city"))

        AppDNA.reset() // ← the sign-out boundary

        assertTrue(
            "user B can read user A's onboarding answers after A signed out",
            store.onboardingResponses.isEmpty(),
        )
        assertTrue(
            "user B can read user A's computed data after A signed out",
            store.computedData.isEmpty(),
        )
        assertNull(
            "user B can read user A's session data after A signed out",
            store.getSessionData("last_city"),
        )
    }

    /**
     * The bucket that mattered most, called out on its own: this is what the template engine reads.
     * A stale `first_name` here does not merely leak — it PRINTS, in B's paywall copy.
     */
    @Test
    fun `after reset the template namespace is empty — A's name cannot render in B's paywall`() {
        signInAsUserAAndAnswerOnboarding()
        assertEquals("Alice", store.onboardingResponses["step_name"]?.get("first_name"))

        AppDNA.reset()

        assertNull(
            "\"Welcome back, {{onboarding.first_name}}\" would still render Alice's name to user B",
            store.onboardingResponses["step_name"]?.get("first_name"),
        )
    }

    /**
     * `shutdown()` is a LIFECYCLE stop, not a user change. Clearing a user's answers because the app
     * is tearing down would be a different bug — the same person relaunches and their flow is gone.
     * This pins the distinction so a later "tidy-up" cannot quietly collapse the two.
     */
    @Test
    fun `shutdown does NOT clear session data — it is a lifecycle stop, not a sign-out`() {
        signInAsUserAAndAnswerOnboarding()

        AppDNA.shutdown()

        assertEquals(
            "shutdown() erased the user's own onboarding answers — it is not a sign-out",
            "alice@example.com",
            store.onboardingResponses["step_email"]?.get("email"),
        )
    }
}
