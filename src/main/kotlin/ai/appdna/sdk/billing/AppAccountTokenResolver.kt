package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Resolves a deterministic [UUID] for use as Google Play Billing's
 * `BillingFlowParams.setObfuscatedAccountId` value (Android's equivalent of
 * Apple's `Transaction.appAccountToken`), keyed off the host-supplied user
 * identity (`AppDNA.identify(userId:)`).
 *
 * Why this exists — the cross-account-entitlement-leak fix:
 *   `BillingClient.queryPurchasesAsync` is **device-scoped** (tied to the
 *   Google account on the device), not app-user-scoped. If user A buys a
 *   subscription, logs out, and user B logs in on the same device, the next
 *   `queryPurchasesAsync` still returns A's purchase — granting B a "fake
 *   premium" state.
 *
 *   `obfuscatedAccountId` is exactly the hook to bind a purchase to an app
 *   user: set on `launchBillingFlow`, read back via
 *   `Purchase.getAccountIdentifiers().getObfuscatedAccountId()` on every
 *   subsequent purchase query, and surfaced to RTDN webhooks so the binding
 *   survives renewals.
 *
 * What this resolver returns — see Sources/AppDNASDK/Billing/AppAccountTokenResolver.swift
 * for the iOS counterpart. The algorithm MUST match byte-for-byte so a user
 * who is identified on both platforms hashes to the same UUID server-side.
 *
 * Determinism contract — the server-side receipt-verifier MUST be able to
 * reproduce the same mapping from `userId` to UUID independently. The
 * algorithm is:
 *   1. If `userId` already parses as a UUID, return it as-is.
 *   2. Otherwise SHA-256(NAMESPACE_UUID_BYTES || userId.utf8), take the first
 *      16 bytes, force RFC-4122 version=5 (name-based, SHA-1) + variant
 *      bits, return as UUID.
 * (Note: RFC-4122 v5 strictly uses SHA-1; we use SHA-256-truncated for
 * stronger collision resistance — the version/variant nibble overrides keep
 * the UUID syntactically valid. The server uses the same algorithm.)
 */
internal object AppAccountTokenResolver {

    /** Fixed namespace UUID — MUST match the iOS / server constant. */
    private val NAMESPACE: UUID = UUID.fromString("C1A85D8E-7B5B-4B5E-9F4F-1E7D5F4C8B2A")

    /**
     * Resolve a token for an arbitrary user-id string.
     * Returns `null` only if [userId] is empty.
     */
    fun token(forUserId: String): UUID? {
        if (forUserId.isEmpty()) return null
        // Fast path: userId is already a UUID.
        runCatching { return UUID.fromString(forUserId) }
        // Slow path: deterministic SHA-256(namespace || userId) → UUID.
        val md = MessageDigest.getInstance("SHA-256")
        val nsBytes = ByteBuffer.allocate(16)
            .putLong(NAMESPACE.mostSignificantBits)
            .putLong(NAMESPACE.leastSignificantBits)
            .array()
        md.update(nsBytes)
        md.update(forUserId.toByteArray(Charsets.UTF_8))
        val digest = md.digest()
        val bytes = digest.copyOfRange(0, 16)
        // Force RFC-4122 version (5 = name-based, SHA-1 — used here as a
        // syntactic marker; the digest is SHA-256-truncated for collision
        // resistance, and the server uses the same algorithm).
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte()
        // Force RFC-4122 variant (10xx).
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val msb = ByteBuffer.wrap(bytes, 0, 8).long
        val lsb = ByteBuffer.wrap(bytes, 8, 8).long
        return UUID(msb, lsb)
    }

    /**
     * Resolve the token for the currently-identified user (or `null` if the
     * host has not yet called `AppDNA.identify(...)`). NOTE: the anonymous
     * device id is NOT used — that would conflate device with user and undo
     * the whole point of per-user entitlement binding.
     */
    fun tokenForCurrentUser(): UUID? {
        val userId = AppDNA.getIdentityRef()?.userId ?: return null
        return token(forUserId = userId)
    }
}
