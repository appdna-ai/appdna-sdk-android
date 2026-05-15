# Changelog

All notable changes to the AppDNA Android SDK are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project uses [Semantic Versioning](https://semver.org/).

## [1.0.35] — 2026-05-15

Follow-up to the 1.0.34 cross-account-entitlement-leak hotfix. The
write-side per-user binding from 1.0.34 ships every purchase tagged
with the current user — but in SDK-driven onboarding paywall flows the
purchase fires BEFORE the host has called `AppDNA.identify(userId)`,
so the resulting Play purchase is **untagged** (no
`obfuscatedAccountId`). 1.0.34's `EntitlementOwnerFilter` granted any
untagged purchase to whoever happened to be identified at read time
(migration-tolerant policy intended for legacy upgrades). Bogdan
reproduced the resulting leak on iOS: user A buys via onboarding, user
B signs in on the same device, B taps Restore, B inherits A's purchase
under the migration policy. Android shipped the same logic and the
same leak.

### What 1.0.35 changes

The migration-tolerant grant is now **scoped to the device's first
identified user**. Decision matrix (see `EntitlementOwnerFilter.kt`):

```
expectedToken null                                                  → GrantAnonymousPolicy   (unchanged)
expectedToken set, purchase == match                                → Grant                  (unchanged)
expectedToken set, purchase mismatch                                → DenyOtherUser          (unchanged)
expectedToken set, untagged, firstIdentifier == expected            → GrantUntaggedMigration (legitimate self-claim)
expectedToken set, untagged, firstIdentifier != expected            → DenyUntaggedOtherUser  (cross-account close — NEW)
expectedToken set, untagged, firstIdentifier null                   → DenyUntaggedOtherUser  (no anchor — NEW)
```

`AppDNA.identify(userId)` records the userId as the first-identifier
on this device the FIRST time it runs (idempotent — later
`identify(B)` does NOT change the anchor). `AppDNA.reset()` clears it.

Effect: the user who legitimately owns the untagged onboarding
purchase (the device's first-identified user) keeps it on Restore; any
other user on the same device is denied. iOS 1.0.63 ships the same
fix.

Files: `EntitlementOwnerFilter.kt` (new `DenyUntaggedOtherUser` case +
3rd `firstIdentifiedToken` parameter), `AppAccountTokenResolver.kt`
(SharedPreferences-backed first-identifier persistence with test
hooks), `AppDNA.kt` (identify records anchor / reset clears it),
`NativeBillingManager.kt` (threads firstIdentifier into the filter
across all 3 active call sites: `reconcileSubscriptionState`,
`refreshEntitlementCache`, `restorePurchases`).

`appdnaFeatureParity` bumps to `1.0.63`. CI enforces lockstep.

## [1.0.34] — 2026-05-15

Cross-platform hotfix mirroring iOS 1.0.62. Closes the cross-account
entitlement leak — same shape as the bug Bogdan reproduced on iOS: User A
purchases on the device, User B signs in to the host app on the same
device, B taps Restore (or just identifies), and `queryPurchasesAsync`
returns A's purchase unfiltered, granting B a fake-premium state. The
`appdnaFeatureParity` marker bumps to `1.0.62`.

### Write side — every purchase now binds to the current app user

`NativeBillingManager.purchase` already passed `BillingFlowParams.setObfuscatedAccountId`
when the host supplied an explicit `options.appAccountToken`, but had no
fallback for callers using the convenience API
(`AppDNA.billing.purchase(productId)` with no options). Now resolves the
token via the new `AppAccountTokenResolver` (deterministic UUID derived from
`AppDNA.identify(userId)` — UUID pass-through if the userId is already a
UUID, otherwise SHA-256(NAMESPACE || userId) → RFC-4122-shaped UUID). Same
algorithm runs on iOS + (TODO) the backend receipt verifier so a cross-
platform user binds to the same token everywhere. If no user is identified,
the purchase still proceeds untagged with a warning (preserves first-launch
flows; hosts should call `AppDNA.identify(userId)` BEFORE letting the user
purchase).

### Read side — every device-level purchase read is filtered

The 3 sites that consume `BillingClient.queryPurchasesAsync`
(`reconcileSubscriptionState`, `refreshEntitlementCache`,
`restorePurchases`) now decode `Purchase.accountIdentifiers.obfuscatedAccountId`
and route through the new `EntitlementOwnerFilter` before any purchase
reaches the entitlement cache or the server-side `receiptVerifier.restore`.
Decision matrix:

```
expectedToken null                    → grantAnonymousPolicy   (preserves pre-identify flows)
expectedToken set, tx == expected     → grant
expectedToken set, tx == nil          → grantUntaggedMigration (server claims ownership)
expectedToken set, tx != expected     → DENY                   ← the headline fix
```

### Server-side defence (primary)

`ReceiptVerifier.verify` and `ReceiptVerifier.restore` now send `app_user_id`
in every request body. The backend MUST decode each transaction's
`obfuscatedAccountId` and compare to the authenticated user — denying on
mismatch and claiming ownership on null (migration-tolerant). The
client-side filter above is belt-and-suspenders for the cached / silent
paths.

### Tests

`src/test/kotlin/ai/appdna/sdk/billing/EntitlementOwnerFilterTest.kt` (5
decision-matrix tests, including the headline cross-account-deny case) and
`AppAccountTokenResolverTest.kt` (6 tests on determinism, UUID fast-path,
and RFC-4122 shape — paired with the iOS frozen-vector test for
cross-platform parity).

## [1.0.33] — 2026-05-13

Android-only hotfix to close iOS parity gaps in onboarding form rendering and
auth-action validation.

- `FormInputSelectBlock` (stacked + grid display styles) now honors per-option
  styling fields from `field_options[]` — `bg_color`, `selected_bg_color`,
  `border_color`, `selected_border_color`, `text_color`, `selected_text_color`
  — and block-level `field_config` fields including `selection_indicator`,
  `radio_position` (left/right), `selected_border_width`, `unselected_border_width`,
  `option_spacing`, `bg_opacity`, and `grid_columns`. Previously every option
  rendered with the same default colors regardless of console config, so
  flows like Nurrai's stacked select showed one uniform tile instead of
  four differently colored answers.
- Grid display now renders `selected_icon` / `unselected_icon` toggle badges
  in the top-end corner and supports configurable `grid_columns` (default 2)
  with empty-slot fillers to keep the final row balanced.
- `AUTH_ACTIONS_REQUIRING_VALIDATION` now includes `resend_verification`,
  `enable_biometric`, `logout`, and `delete_account` so the form-validation
  gate fires before these actions just like on iOS.

## [1.0.32] — 2026-05-06

Android SDK uses its own version sequence (independent of iOS — Android
last released `1.0.31`). The `appdnaFeatureParity=1.0.61` marker in
`gradle.properties` declares which iOS SDK version this Android release
wraps for cross-platform feature parity: SPEC-070-A brought Android to iOS
1.0.60 parity, and SPEC-401 Phase D mirrored the 4 iOS 1.0.61
entitlement-aware paywall fixes (entitlement gate, bridge restore,
auto-dismiss, identify-time cache refresh) onto Android in the same
release tag. Note: an earlier draft of this changelog tagged this release
as `1.0.60`; that was a mis-merge of the iOS version into the Android
sequence and has been corrected.

Cross-platform feature parity catch-up to iOS — closes the gap from previous
Android-only follow-up debt across SPEC-038, SPEC-082, SPEC-083, SPEC-084,
SPEC-085, SPEC-205, SPEC-206, and SPEC-400 in a single release.

### Added

- **Onboarding** — full `form` step type port (13 P0 input types + 8 P1
  extended types), strict-typed action passthrough, async step hooks
  (`onBeforeStepAdvance`, `onBeforeStepRender`), and `StepAdvanceResult.Stay`
  semantics with optional banner message.
- **Paywalls** — strict-typed restore lifecycle (`onPaywallRestoreStarted`/
  `Completed`/`Failed`) and 12-method `OnboardingPaywallBridge` forwarding so
  paywall actions invoked from an onboarding step pass through correctly.
- **Rich media (SPEC-085)** — Lottie + Rive (always bundled), SVG, GIF, and
  inline video via Media3 ExoPlayer; Lucide / Material / SF-Symbols-equivalent
  icon library; blur / glassmorphism modifiers; haptics; confetti and particle
  overlays across messages, surveys, and push notifications.
- **Theming (SPEC-205)** — light / dark theme tokens with deterministic
  resolution and system-appearance follow.
- **Auth + restore (SPEC-206)** — strict-typed auth context and restore flow
  parity with iOS.
- **Push** — strict-typed action buttons, NSE-equivalent rich content
  (image / GIF / video) helper, and per-action delegate routing.
- **Network + push hardening** — 25 SPEC-070-A H-series items: typed retry
  policy, jitter, hard-cap reconnect on `BillingConnectionManager`, push
  delegate integration, etc.
- **Identity + events** — 22 SPEC-070-A G-series items: anonymous-id
  persistence via EncryptedSharedPreferences, structured event envelope,
  retry policy, in-memory caps.
- **Build / packaging (Phase J)** — Maven Central / Sonatype OSSRH publish
  config with sources + javadoc jars, `gradle/libs.versions.toml` version
  catalog, debug/release `buildTypes` with log gating, LeakCanary in debug,
  AOSP-template `.gitignore`, library-friendly `gradle.properties` flags
  (`android.nonTransitiveRClass=true`, `android.enableJetifier=false`,
  `kotlin.code.style=official`), CHANGELOG, and a CI matrix (API 24 / 28 /
  33 / 34) running `assembleDebug` + `lint` + `test`.
- **kotlinx-serialization-json** is now on the classpath so future DTOs can
  opt in to `@Serializable` incrementally; existing DTOs continue to use
  `org.json` unchanged.
- **`BillingClientFactory` injectable seam** so unit tests can substitute a
  fake `BillingClient`, unblocking JVM tests of the connect / retry /
  surface-billing-unavailable paths.

### Changed

- **Compose BoM** bumped to `2024.02.02` (was `2024.01.00`). Stops at the
  2024.02 line because Compose Compiler 1.5.8 + Kotlin 1.9.22 don't yet
  support 2024.09.x. A follow-up SPEC will bundle the Kotlin + compiler
  bump together.
- **kotlinx-coroutines** bumped to `1.8.1` (was `1.7.3`).
- **`Log` (Configuration.kt)** — INFO + DEBUG levels now no-op in release
  builds even when callers leave `LogLevel.DEBUG` configured. ERROR + WARN
  always print. R8 inlines `BuildConfig.DEBUG` so the gate costs nothing at
  runtime in release.

### Tests

- New unit tests in `src/test/kotlin/ai/appdna/sdk/`:
  - `delegates/DelegateWiringTest`
  - `events/PushEventTest`
  - `events/IdentityEventTest`
  - `events/EventQueuePolicyDocTest`
  - `messages/InAppMessagingTest`
  - `billing/RestoreTest`
  - `onboarding/OnboardingActionPassthroughTest`
  - `onboarding/OnboardingPaywallBridgeForwardingTest`
  - `onboarding/StepAdvanceResultStayTest`
