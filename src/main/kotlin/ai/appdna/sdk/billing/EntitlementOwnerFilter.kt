package ai.appdna.sdk.billing

import java.util.UUID

/**
 * Outcome of a per-purchase ownership check against the currently-identified
 * user. Mirrors iOS `EntitlementOwnershipDecision` byte-for-byte so the
 * cross-platform defence stays in sync.
 */
internal enum class EntitlementOwnershipDecision {
    /** Purchase is bound to the current user — grant. */
    Grant,
    /** Purchase belongs to a different user — DENY (cross-account leak guard). */
    DenyOtherUser,
    /** Untagged historical purchase — grant under the migration-tolerant policy.
     * Caller surfaces this to the server-side receipt-verifier so the backend
     * can claim ownership for the current user. */
    GrantUntaggedMigration,
    /** No identified user — fall back to no ownership filter (pre-`identify`
     * flows like first-launch "Restore" before any user is established). */
    GrantAnonymousPolicy,
}

/**
 * Pure, fully-unit-testable ownership filter used by every site that calls
 * `BillingClient.queryPurchasesAsync`. The decision matrix is the single
 * source of truth for the Android cross-account-entitlement-leak defence
 * and mirrors iOS `EntitlementOwnerFilter`.
 *
 * Decision matrix:
 * ```
 *   expectedToken  | purchase token     | decision
 *   ───────────────┼────────────────────┼──────────────────────────
 *   null           | any                | GrantAnonymousPolicy
 *   set            | == expectedToken   | Grant
 *   set            | null               | GrantUntaggedMigration
 *   set            | != expectedToken   | DenyOtherUser
 * ```
 *
 * The "anonymous" branch preserves pre-identify behaviour (a host that calls
 * restorePurchases before any user has identified gets every purchase the
 * device knows about — same as before this fix). Once the host calls
 * `AppDNA.identify(...)`, the filter is armed and the per-user binding is
 * enforced on every subsequent read.
 */
internal object EntitlementOwnerFilter {

    fun decide(
        purchaseToken: UUID?,
        expectedToken: UUID?,
    ): EntitlementOwnershipDecision {
        if (expectedToken == null) return EntitlementOwnershipDecision.GrantAnonymousPolicy
        if (purchaseToken == expectedToken) return EntitlementOwnershipDecision.Grant
        if (purchaseToken == null) return EntitlementOwnershipDecision.GrantUntaggedMigration
        return EntitlementOwnershipDecision.DenyOtherUser
    }

    /**
     * Parse the obfuscated-account-id string Google Play returns into a
     * UUID. Returns null when the value is missing or malformed — callers
     * treat that as `purchaseToken = null` (untagged historical), which
     * routes through the migration-tolerant branch.
     */
    fun parseObfuscatedAccountId(raw: String?): UUID? {
        if (raw.isNullOrEmpty()) return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }
}
