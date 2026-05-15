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
    /** Purchase belongs to a different identified user — DENY
     *  (cross-account leak guard, tagged-mismatch path). */
    DenyOtherUser,
    /** Untagged historical purchase AND the current user matches the
     *  first-identifier recorded on this device — grant under the
     *  migration-tolerant "first-identifier-claims-untagged-history"
     *  policy. The caller surfaces this to the server-side receipt-verifier
     *  so the backend can claim ownership and prevent silent re-grant on a
     *  later user switch. */
    GrantUntaggedMigration,
    /** Untagged historical purchase but the current user is NOT the
     *  first-identifier recorded on this device — DENY (cross-account
     *  leak guard, untagged-mismatch path). Closes the v1.0.62 leak in
     *  flows where SDK paywall purchases fire BEFORE the host calls
     *  `AppDNA.identify(...)` (e.g. onboarding paywalls): the resulting
     *  untagged purchase now belongs to the first user who identifies on
     *  the device and no other user can inherit it. */
    DenyUntaggedOtherUser,
    /** No identified user — fall back to no ownership filter (pre-`identify`
     *  flows like first-launch "Restore" before any user is established). */
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
 *   expectedToken  | purchase token     | firstIdentifiedToken             | decision
 *   ───────────────┼────────────────────┼──────────────────────────────────┼───────────────────────
 *   null           | any                | any                              | GrantAnonymousPolicy
 *   set            | == expectedToken   | any                              | Grant
 *   set            | != expectedToken   | any                              | DenyOtherUser
 *   set            | null               | == expectedToken (self-claim)    | GrantUntaggedMigration
 *   set            | null               | != expectedToken (other-claim)   | DenyUntaggedOtherUser
 *   set            | null               | null (no firstIdentifier yet)    | DenyUntaggedOtherUser
 * ```
 *
 * The "anonymous" branch preserves pre-identify behaviour. The
 * first-identifier scope on untagged grants closes the v1.0.62 leak where
 * any later identified user could inherit an untagged purchase made before
 * `identify(...)` was called (typical for SDK paywall onboarding flows).
 */
internal object EntitlementOwnerFilter {

    /**
     * Apply the decision matrix above to a single purchase's token.
     *
     * [firstIdentifiedToken] defaults to `null` to preserve source-compat
     * for any call site that hasn't been updated yet — but doing so falls
     * into the strict `DenyUntaggedOtherUser` branch for untagged purchases
     * (i.e. no migration grant unless the caller actively threads the
     * first-identifier through). All shipped call sites pass it explicitly.
     */
    fun decide(
        purchaseToken: UUID?,
        expectedToken: UUID?,
        firstIdentifiedToken: UUID? = null,
    ): EntitlementOwnershipDecision {
        if (expectedToken == null) return EntitlementOwnershipDecision.GrantAnonymousPolicy
        if (purchaseToken != null) {
            return if (purchaseToken == expectedToken) {
                EntitlementOwnershipDecision.Grant
            } else {
                EntitlementOwnershipDecision.DenyOtherUser
            }
        }
        // Untagged: only the first-identifier on this device may claim
        // historical untagged purchases. Any other identified user is
        // denied (this is the cross-account-leak close for onboarding-
        // paywall flows that purchase before identify()).
        if (firstIdentifiedToken != null && firstIdentifiedToken == expectedToken) {
            return EntitlementOwnershipDecision.GrantUntaggedMigration
        }
        return EntitlementOwnershipDecision.DenyUntaggedOtherUser
    }

    /**
     * Parse the obfuscated-account-id string Google Play returns into a
     * UUID. Returns null when the value is missing or malformed — callers
     * treat that as `purchaseToken = null` (untagged historical), which
     * routes through the first-identifier-scoped grant/deny branch.
     */
    fun parseObfuscatedAccountId(raw: String?): UUID? {
        if (raw.isNullOrEmpty()) return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }
}
