# Changelog

All notable changes to the AppDNA Android SDK are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project uses [Semantic Versioning](https://semver.org/).

## [1.0.32] — 2026-05-06

Android SDK uses its own version sequence (independent of iOS — Android
last released `1.0.31`). The `appdnaFeatureParity=1.0.60` marker in
`gradle.properties` declares which iOS SDK version this Android release
wraps for cross-platform feature parity (CI enforces equality once
SPEC-070-A is signed off as complete). Note: an earlier draft of this
changelog tagged this release as `1.0.60`; that was a mis-merge of the
iOS version into the Android sequence and has been corrected.

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
