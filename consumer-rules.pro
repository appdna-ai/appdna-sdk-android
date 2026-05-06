# AppDNA Android SDK — consumer ProGuard / R8 rules.
#
# SPEC-070-A Phase A.5: shipped to library consumers via
# `consumerProguardFiles("consumer-rules.pro")` in build.gradle.kts.
# Any host that enables R8/minify will pick these up automatically and
# avoid silent JSON-parse breakage on every config DTO.
#
# Strategy: we KEEP entire DTO/config packages (every field, every
# getter, every constructor) because parsing flows through reflection
# (Firestore Map<String, Any> -> data-class fromMap factories +
# kotlinx.serialization where applicable). Stripping any field will
# render an empty paywall / onboarding step in production builds.
#
# We also KEEP top-level public API entry points so host code can
# resolve `AppDNA.*` and `AppDNAModules.*` symbols after R8.

# -- Public API surface ---------------------------------------------------

-keep public class ai.appdna.sdk.AppDNA { *; }
-keep public class ai.appdna.sdk.AppDNA$* { *; }
-keep public class ai.appdna.sdk.AppDNAModules { *; }
-keep public class ai.appdna.sdk.AppDNAModules$* { *; }
-keep public class ai.appdna.sdk.Configuration { *; }
-keep public class ai.appdna.sdk.Identity { *; }

# -- DTO + config packages (reflective parse via fromMap factories) -------

-keep class ai.appdna.sdk.events.** { *; }
-keep class ai.appdna.sdk.paywalls.** { *; }
-keep class ai.appdna.sdk.feedback.** { *; }
-keep class ai.appdna.sdk.messages.** { *; }
-keep class ai.appdna.sdk.onboarding.** { *; }
-keep class ai.appdna.sdk.screens.** { *; }
-keep class ai.appdna.sdk.config.** { *; }
-keep class ai.appdna.sdk.billing.** { *; }
-keep class ai.appdna.sdk.core.** { *; }
# SPEC-070-A final audit pass G F2/F3 — additional packages whose data
# classes/types are reflectively materialised by Firestore + JSON parsers
# or referenced from the codegen layer.
-keep class ai.appdna.sdk.generated.** { *; }
-keep class ai.appdna.sdk.network.** { *; }
-keep class ai.appdna.sdk.storage.** { *; }
-keep class ai.appdna.sdk.deeplinks.** { *; }
-keep class ai.appdna.sdk.webentitlements.** { *; }

# -- Integrations: FCM service must remain because the manifest binds it --

-keep class ai.appdna.sdk.integrations.AppDNAMessagingService { *; }

# -- WorkManager: Worker classes are instantiated by name via reflection --
# SPEC-070-A final audit pass G F1 — without this, R8 renames
# EventUploadWorker / ConfigRefreshWorker classes and
# `OneTimeWorkRequestBuilder<EventUploadWorker>()` resolves to a stale
# name at runtime → silent event-upload + config-refresh failure on
# release builds.
-keep class ai.appdna.sdk.background.** { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(...);
}

# -- Delegates / public listener interfaces (host implements) -------------

-keep public interface ai.appdna.sdk.**Delegate { *; }
-keep public interface ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate { *; }

# -- @Keep annotation fallback (any class explicitly tagged) --------------

-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}
