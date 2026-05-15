package ai.appdna.sdk.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * Pure unit tests for the cross-account-leak ownership filter — the single
 * source of truth for the decision matrix that defends every site reading
 * `BillingClient.queryPurchasesAsync`. Bogdan's reproducer (User A buys →
 * User B signs in → B sees A's subscription) lives or dies on this decision
 * table being correct; if any case here flips wrong, the whole defence
 * silently breaks.
 *
 * Decision matrix re-stated for clarity:
 *   expectedToken null                    → GrantAnonymousPolicy   (pre-identify pass-through)
 *   expectedToken set, tx token == match  → Grant                  (current user's purchase)
 *   expectedToken set, tx token null      → GrantUntaggedMigration (historical, server claims)
 *   expectedToken set, tx token mismatch  → DenyOtherUser          ← THE FIX
 *
 * Mirrors iOS `EntitlementOwnerFilterTests` byte-for-byte.
 */
class EntitlementOwnerFilterTest {

    private val tokenA = UUID.randomUUID()
    private val tokenB = UUID.randomUUID()

    // ─── The four cases of the decision matrix ──────────────────────────────

    @Test fun `no expected token returns anonymous-policy grant`() {
        // No identified user (host hasn't called identify yet) — any
        // transaction token, including null, is accepted under the legacy
        // pass-through. Preserves first-launch / pre-identify flows.
        assertEquals(
            EntitlementOwnershipDecision.GrantAnonymousPolicy,
            EntitlementOwnerFilter.decide(purchaseToken = tokenA, expectedToken = null),
        )
        assertEquals(
            EntitlementOwnershipDecision.GrantAnonymousPolicy,
            EntitlementOwnerFilter.decide(purchaseToken = null, expectedToken = null),
        )
    }

    @Test fun `tagged and matching token grants`() {
        // Identified user, transaction tagged with the same user's token → grant.
        assertEquals(
            EntitlementOwnershipDecision.Grant,
            EntitlementOwnerFilter.decide(purchaseToken = tokenA, expectedToken = tokenA),
        )
    }

    @Test fun `untagged historical token is migration-tolerant`() {
        // Identified user, transaction has no obfuscatedAccountId (purchased
        // before the SDK started tagging) → grant under the migration-
        // tolerant policy. The server is expected to claim ownership for
        // the current user so a later user-switch doesn't silently re-grant.
        assertEquals(
            EntitlementOwnershipDecision.GrantUntaggedMigration,
            EntitlementOwnerFilter.decide(purchaseToken = null, expectedToken = tokenA),
        )
    }

    @Test fun `tagged for a different user is denied (the headline fix)`() {
        // Identified user A, purchase tagged for user B → DENY.
        // This is the specific case that produced Bogdan's reproducer:
        // user B signs in, taps Restore, queryPurchasesAsync returns A's
        // purchase. With this filter the purchase is dropped.
        assertEquals(
            EntitlementOwnershipDecision.DenyOtherUser,
            EntitlementOwnerFilter.decide(purchaseToken = tokenB, expectedToken = tokenA),
        )
        // Symmetric — user B is identified, A's purchase is denied.
        assertEquals(
            EntitlementOwnershipDecision.DenyOtherUser,
            EntitlementOwnerFilter.decide(purchaseToken = tokenA, expectedToken = tokenB),
        )
    }

    // ─── parseObfuscatedAccountId ───────────────────────────────────────────

    @Test fun `parses a valid uuid string`() {
        val uuid = UUID.randomUUID()
        assertEquals(uuid, EntitlementOwnerFilter.parseObfuscatedAccountId(uuid.toString()))
    }

    @Test fun `returns null for empty or null input (treated as untagged)`() {
        assertNull(EntitlementOwnerFilter.parseObfuscatedAccountId(null))
        assertNull(EntitlementOwnerFilter.parseObfuscatedAccountId(""))
    }

    @Test fun `returns null for malformed input (treated as untagged)`() {
        // Malformed obfuscatedAccountId on a purchase routes through the
        // migration-tolerant branch rather than throwing — safer than
        // failing the whole restore for one bad row.
        assertNull(EntitlementOwnerFilter.parseObfuscatedAccountId("not-a-uuid"))
        assertNull(EntitlementOwnerFilter.parseObfuscatedAccountId("12345"))
    }
}
