# AppDNA SDK for Android

The official Android SDK for [AppDNA](https://appdna.ai) — the growth console for subscription apps.

> ⚠️ **Proprietary software.** A Commercial Agreement with AppDNA AI, Inc. is required to use this SDK. See [LICENSE](./LICENSE) and [NOTICE.md](./NOTICE.md).
>
> **Migrating from MIT-licensed v1.0.30 or earlier?** See [DEPRECATION_NOTICE.md](./DEPRECATION_NOTICE.md). MIT versions stop receiving server support after **15 May 2026**.

## What it does

AppDNA gives you a single drop-in SDK for the growth surfaces every subscription app needs:

- **Analytics & events** — track user behavior with batched, offline-resilient delivery.
- **Experiments & feature flags** — server-driven A/B tests with deterministic variant assignment.
- **Paywalls** — render console-designed paywall layouts with Google Play Billing and RevenueCat / Adapty bridges.
- **Onboarding flows** — multi-step onboarding with form inputs, async hooks, conditional branching, and rich media.
- **Surveys & feedback** — NPS, CSAT, free text, multi-choice with scheduling and frequency caps.
- **In-app messages** — modal, banner, fullscreen messages with audience targeting.
- **Push notifications** — rich content, action buttons, deep links, and delivery analytics via FCM.
- **Web entitlements & deep links** — server-validated entitlements and deferred deep linking.

## Requirements

- Android API 24+ (Android 7.0 Nougat)
- Kotlin 1.9+
- Compose-friendly host (recommended; not required)

## Installation

Add JitPack to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.appdna-ai:appdna-sdk-android:v1.0.31")
}
```

## Quick start

In your `Application.onCreate()`:

```kotlin
import ai.appdna.sdk.AppDNA

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDNA.configure(this, "YOUR_API_KEY")
    }
}
```

Track an event:

```kotlin
AppDNA.track("subscription_viewed", mapOf("plan_id" to "premium_monthly"))
```

Identify a user (after sign-in):

```kotlin
AppDNA.identify("user-123", mapOf("plan" to "premium"))
```

Present a paywall:

```kotlin
AppDNA.paywalls.present(activity, "default") { result ->
    when (result) {
        is PaywallResult.Purchased -> Log.d("AppDNA", "Purchased")
        is PaywallResult.Dismissed -> Log.d("AppDNA", "Dismissed")
        is PaywallResult.Failed -> Log.e("AppDNA", "Failed", result.error)
    }
}
```

## Documentation

Full integration guide, configuration reference, and API docs at **[docs.appdna.ai/sdks/android](https://docs.appdna.ai/sdks/android/installation)**.

## Support

- Technical questions: [support@appdna.ai](mailto:support@appdna.ai)
- Sales / commercial: [sales@appdna.ai](mailto:sales@appdna.ai)
- Licensing: [legal@appdna.ai](mailto:legal@appdna.ai)

## License

⚠️ **The AppDNA SDK is proprietary software, not open source.** This repository is publicly visible for marketing, evaluation, and reference purposes only.

**You may NOT** download, install, run, modify, or use the SDK without a Commercial Agreement with AppDNA AI, Inc. See [LICENSE](./LICENSE) and [NOTICE.md](./NOTICE.md) for the full terms.

**You MAY** view the source on GitHub and read the documentation at <https://docs.appdna.ai> for evaluation purposes.

To use the SDK in your application, sign up at <https://appdna.ai> (self-serve) or contact <sales@appdna.ai> (enterprise).

**Existing customers**: your Terms of Service or Statement of Work governs your use of the SDK.

**Versions before v1.0.31** were distributed under the MIT License — see [DEPRECATION_NOTICE.md](./DEPRECATION_NOTICE.md) for the migration timeline (deadline: **15 May 2026**).

---

© 2026 AppDNA AI, Inc. All rights reserved. "AppDNA" and the AppDNA logo are trademarks of AppDNA AI, Inc.
