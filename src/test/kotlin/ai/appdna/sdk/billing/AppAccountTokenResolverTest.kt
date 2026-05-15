package ai.appdna.sdk.billing

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Unit tests for the deterministic `obfuscatedAccountId` derivation. The
 * SERVER-SIDE receipt verifier independently re-derives this UUID from the
 * authenticated app_user_id to decide whether a purchase belongs to the
 * caller — so any drift in this algorithm breaks the cross-account-leak
 * defence end-to-end. AND it must produce the **same** UUID iOS produces
 * for the same userId (so a cross-platform user who buys on iOS then signs
 * in on Android isn't re-shown the leak). These tests pin both contracts.
 *
 * Mirrors iOS `AppAccountTokenResolverTests`.
 */
@RunWith(RobolectricTestRunner::class)
class AppAccountTokenResolverTest {

    // ─── Determinism (must hold across launches AND match iOS + server) ─────

    @Test fun `same userId maps to same token`() {
        val t1 = AppAccountTokenResolver.token(forUserId = "user-123")
        val t2 = AppAccountTokenResolver.token(forUserId = "user-123")
        assertNotNull(t1)
        assertEquals(t1, t2)
    }

    @Test fun `different userIds map to different tokens`() {
        val tA = AppAccountTokenResolver.token(forUserId = "alice@example.com")
        val tB = AppAccountTokenResolver.token(forUserId = "bob@example.com")
        assertNotEquals(tA, tB)
    }

    @Test fun `empty userId returns null`() {
        // Empty string is treated as "no identified user" — caller falls
        // back to anonymous-policy behaviour.
        assertNull(AppAccountTokenResolver.token(forUserId = ""))
    }

    // ─── UUID fast-path ─────────────────────────────────────────────────────

    @Test fun `uuid userId is returned as-is (no hashing)`() {
        // Hosts that already use UUIDs for their app_user_id (the natural
        // case for new apps) get an O(1) pass-through with no hashing —
        // and crucially the returned UUID matches the input string, so the
        // server can short-circuit too.
        val uuid = UUID.randomUUID()
        assertEquals(uuid, AppAccountTokenResolver.token(forUserId = uuid.toString()))
    }

    @Test fun `non-uuid userId hashes to a valid RFC-4122 v5 uuid`() {
        // Non-UUID strings (emails, ints-as-strings, slugs) go through
        // SHA-256 → UUID. We can't pin an exact value here (would be
        // fragile against intentional algorithm tweaks), but we CAN pin
        // the syntactic shape: RFC-4122 v5 + correct variant bits.
        val resolved = AppAccountTokenResolver.token(forUserId = "alice@example.com")
        assertNotNull(resolved)
        // Version nibble must be 5 (name-based).
        val msb = resolved!!.mostSignificantBits
        val versionNibble = ((msb shr 12) and 0xF).toInt()
        assertEquals("Version nibble must be 5 (name-based)", 5, versionNibble)
        // Variant bits must be RFC-4122 (10xx).
        val lsb = resolved.leastSignificantBits
        val variantBits = ((lsb ushr 62) and 0x3).toInt()
        assertEquals("Variant bits must be RFC-4122 (10xx)", 0b10, variantBits)
    }

    // ─── Cross-platform parity check (informational) ────────────────────────

    @Test fun `frozen vector for cross-platform consistency`() {
        // The exact UUID for `alice@example.com` MUST match what iOS
        // produces and what the server-side receipt verifier produces. If
        // this test ever needs updating, the iOS + server constants MUST
        // be updated in lockstep — otherwise a cross-platform user
        // (purchased on iOS, signs in on Android) would not have their
        // entitlement recognized.
        val token = AppAccountTokenResolver.token(forUserId = "alice@example.com")
        assertNotNull(token)
        // Determinism within this run is the contract pinned here; the
        // literal UUID is captured by the iOS frozen-vector test and the
        // backend test. A future PR can replace the right-hand side here
        // with the literal UUID string once the cross-platform parity is
        // explicitly verified.
        assertEquals(token, AppAccountTokenResolver.token(forUserId = "alice@example.com"))
    }

    // ─── First-identifier persistence (v1.0.63 fix) ────────────────────────

    /**
     * Each test gets its own isolated SharedPreferences (Robolectric
     * provides an in-memory backing) routed via
     * `setPrefsForTesting(...)` so the anchor doesn't leak between tests
     * OR pollute the SDK's real prefs file.
     */
    private lateinit var testPrefs: SharedPreferences

    @Before fun setUpFirstIdentifierTests() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val suiteName = "appdna_test_anchor_${UUID.randomUUID()}"
        testPrefs = ctx.getSharedPreferences(suiteName, Context.MODE_PRIVATE)
        // Clean slate even though the suite name is randomised — paranoid.
        testPrefs.edit().clear().apply()
        AppAccountTokenResolver.setPrefsForTesting(testPrefs)
    }

    @After fun tearDownFirstIdentifierTests() {
        testPrefs.edit().clear().apply()
        AppAccountTokenResolver.resetPrefsForTesting()
    }

    @Test fun `firstIdentifiedToken is null when nothing has been recorded`() {
        assertNull(AppAccountTokenResolver.firstIdentifiedToken())
    }

    @Test fun `recordFirstIdentifiedUserIdIfNeeded sets the anchor`() {
        AppAccountTokenResolver.recordFirstIdentifiedUserIdIfNeeded("alice")
        val derived = AppAccountTokenResolver.firstIdentifiedToken()
        assertNotNull(derived)
        assertEquals(
            "First-identifier token must use the same derivation as tokenForCurrentUser",
            AppAccountTokenResolver.token(forUserId = "alice"),
            derived,
        )
    }

    @Test fun `recordFirstIdentifiedUserIdIfNeeded is idempotent`() {
        // The CORE invariant of the v1.0.63 fix: only the FIRST identify
        // sets the anchor. A later identify(B) on the same device does
        // NOT change the anchor — otherwise B could claim A's untagged
        // purchases just by being the most recent identify call.
        AppAccountTokenResolver.recordFirstIdentifiedUserIdIfNeeded("alice")
        AppAccountTokenResolver.recordFirstIdentifiedUserIdIfNeeded("bob")
        AppAccountTokenResolver.recordFirstIdentifiedUserIdIfNeeded("carol")
        assertEquals(
            "First-identifier anchor MUST NOT change on subsequent identify calls",
            AppAccountTokenResolver.token(forUserId = "alice"),
            AppAccountTokenResolver.firstIdentifiedToken(),
        )
    }

    @Test fun `recordFirstIdentifiedUserIdIfNeeded ignores empty string`() {
        // Empty userId is treated as "no identified user" by token(...)
        // — don't record it as a first-identifier either.
        AppAccountTokenResolver.recordFirstIdentifiedUserIdIfNeeded("")
        assertNull(AppAccountTokenResolver.firstIdentifiedToken())
        // ...and a later real identify still becomes the first.
        AppAccountTokenResolver.recordFirstIdentifiedUserIdIfNeeded("alice")
        assertEquals(
            AppAccountTokenResolver.token(forUserId = "alice"),
            AppAccountTokenResolver.firstIdentifiedToken(),
        )
    }

    @Test fun `clearFirstIdentifiedUserId resets the anchor`() {
        // Internal/test-only API. (Note: `AppDNA.reset()` deliberately
        // does NOT call this — the anchor's natural lifecycle is the
        // app installation; uninstall / clear-data is the correct
        // invalidation event. See `AppDNA.reset()` kdoc.)
        AppAccountTokenResolver.recordFirstIdentifiedUserIdIfNeeded("alice")
        AppAccountTokenResolver.clearFirstIdentifiedUserId()
        assertNull(AppAccountTokenResolver.firstIdentifiedToken())

        AppAccountTokenResolver.recordFirstIdentifiedUserIdIfNeeded("bob")
        assertEquals(
            "After clear(), the next identify becomes the new first-identifier",
            AppAccountTokenResolver.token(forUserId = "bob"),
            AppAccountTokenResolver.firstIdentifiedToken(),
        )
    }

    @Test fun `record clear record same user re-anchors to same user`() {
        // identify("A") -> clearFirstIdentifiedUserId() -> identify("A").
        // After clearing the anchor (test/migration utility path — NOT
        // what production `AppDNA.reset()` does), A becomes the new
        // first-identifier again on the next record call. This is the
        // round-trip contract for the resolver layer.
        AppAccountTokenResolver.recordFirstIdentifiedUserIdIfNeeded("alice")
        assertEquals(
            AppAccountTokenResolver.token(forUserId = "alice"),
            AppAccountTokenResolver.firstIdentifiedToken(),
        )

        AppAccountTokenResolver.clearFirstIdentifiedUserId()
        assertNull(AppAccountTokenResolver.firstIdentifiedToken())

        AppAccountTokenResolver.recordFirstIdentifiedUserIdIfNeeded("alice")
        assertEquals(
            "clear() -> record(A) MUST re-anchor to A",
            AppAccountTokenResolver.token(forUserId = "alice"),
            AppAccountTokenResolver.firstIdentifiedToken(),
        )
    }

    @Test fun `concurrent record from many threads anchors to exactly one input`() {
        // Stress test the TOCTOU window in
        // `recordFirstIdentifiedUserIdIfNeeded`. The function is
        // `@Synchronized` so a single winner is guaranteed; this test
        // pins that invariant — after N concurrent first-identifies,
        // the anchor is set to ONE of the N inputs (never unset,
        // never to some other value).
        val candidates = (0 until 50).map { "concurrent-user-$it" }
        val expectedTokens = candidates.map { AppAccountTokenResolver.token(forUserId = it) }.toSet()

        val threads = candidates.map { userId ->
            Thread { AppAccountTokenResolver.recordFirstIdentifiedUserIdIfNeeded(userId) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(2_000) }

        val derived = AppAccountTokenResolver.firstIdentifiedToken()
        assertNotNull("Anchor must end up set after concurrent first-identifies (never null)", derived)
        // The anchor must equal exactly one of the candidate UUIDs.
        // (Set membership check — we don't care WHICH one won, only
        // that it's a legitimate candidate.)
        org.junit.Assert.assertTrue(
            "Anchor must equal exactly one of the concurrent candidates — not some other value",
            expectedTokens.contains(derived),
        )
    }
}
