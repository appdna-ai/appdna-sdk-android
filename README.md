# AppDNA SDK for Android

The official Android SDK for [AppDNA](https://appdna.ai) — the growth console for subscription apps.

## Installation

Add JitPack repository to your settings.gradle:

```groovy
dependencyResolutionManagement {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

Add dependency to your app's build.gradle:

```groovy
dependencies {
    implementation 'com.github.appdna-ai:appdna-sdk-android:v1.0.0'
}
```

## Quick Start

```kotlin
import ai.appdna.sdk.AppDNA

// In Application.onCreate()
AppDNA.configure(this, "YOUR_API_KEY")
```

## Documentation

Full documentation at [docs.appdna.ai](https://docs.appdna.ai/sdks/android/installation)

## License

MIT — see [LICENSE](LICENSE) for details.
