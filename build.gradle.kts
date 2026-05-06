plugins {
    id("com.android.library") version "8.2.0"
    id("org.jetbrains.kotlin.android") version "1.9.22"
    id("maven-publish")
    id("signing")
    // SPEC-070-0 §3.4 — visual snapshot harness (Android leg).
    // Roborazzi runs Compose snapshot tests on the JVM via Robolectric — no device required.
    // PNG goldens live in src/test/snapshots/ and are committed; reviewed during PR.
    id("io.github.takahirom.roborazzi") version "1.21.0"
}

android {
    namespace = "ai.appdna.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // SPEC-070-A Phase A.5 — ship ProGuard/R8 keep rules to consumers
        // so any host enabling minify doesn't silently strip SDK config DTOs.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

// Read version from gradle.properties
val sdkVersion: String by project

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "ai.appdna"
                artifactId = "sdk-android"
                version = sdkVersion

                pom {
                    name.set("AppDNA Android SDK")
                    description.set("AppDNA SDK for Android — analytics, experiments, paywalls, onboarding, surveys, and more.")
                    url.set("https://github.com/appdna-ai/appdna-sdk-android")

                    licenses {
                        license {
                            name.set("AppDNA SDK Proprietary License")
                            url.set("https://github.com/appdna-ai/appdna-sdk-android/blob/main/LICENSE")
                            distribution.set("repo")
                        }
                    }

                    developers {
                        developer {
                            id.set("appdna-ai")
                            name.set("AppDNA")
                            email.set("sdk@appdna.ai")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/appdna-ai/appdna-sdk-android.git")
                        developerConnection.set("scm:git:ssh://github.com/appdna-ai/appdna-sdk-android.git")
                        url.set("https://github.com/appdna-ai/appdna-sdk-android")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "LocalStaging"
                url = uri(layout.buildDirectory.dir("staging-deploy"))
            }
        }
    }

    signing {
        val signingKey = System.getenv("GPG_SIGNING_KEY")
        val signingPassword = System.getenv("GPG_SIGNING_PASSWORD")
        if (signingKey != null && signingPassword != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
        sign(publishing.publications["release"])
    }
}

dependencies {
    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.01.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Firebase Firestore for remote config
    implementation("com.google.firebase:firebase-firestore:25.1.1")

    // Firebase Cloud Messaging for push notifications
    implementation("com.google.firebase:firebase-messaging:24.1.0")

    // OkHttp for event ingestion
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // SPEC-070-A A.30 — `future { ... }` coroutine builder so suspend public APIs
    // (PushModule.requestPermission, BillingModule.purchase/getProducts/getEntitlements)
    // can expose `CompletableFuture` overloads for Java consumers.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")

    // JSON serialization
    implementation("org.json:json:20231013")

    // Google Play Billing Library
    implementation("com.android.billingclient:billing-ktx:7.0.0")

    // Google Play In-App Review
    implementation("com.google.android.play:review-ktx:2.0.1")

    // Google Play Install Referrer (for deferred deep links)
    implementation("com.android.installreferrer:installreferrer:2.2")

    // SPEC-067: WorkManager for background event upload
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // SPEC-070-A A.18: ProcessLifecycleOwner observer for app-foreground/background
    // hooks (parity with iOS UIApplication.didEnterBackgroundNotification observer
    // in EventQueue.swift).
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.7.0")

    // SPEC-070-A G.5: EncryptedSharedPreferences for sensitive on-device storage
    // (anon_id, user_id, user_traits, push token). iOS parity = Keychain.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // SPEC-070-A A.3: Coil for NetworkImage memory + disk caching.
    // Replaces the BitmapFactory.decodeStream-on-every-recomposition path.
    // Default singleton ImageLoader is configured in core/AppDNAImageLoader.kt
    // with a 25%-of-available-memory cache + 50 MB disk cache at
    // <cacheDir>/appdna_image_cache.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // RevenueCat (optional — conditionally used).
    // SPEC-070-A A.19 — bumped to 8.x to match the public Purchases API
    // (`Purchases.sharedInstance`, `Purchases.configure(PurchasesConfiguration)`,
    // suspend `awaitOfferings()`, `awaitPurchase()`, `awaitRestore()`,
    // `awaitCustomerInfo()`) we use in integrations/RevenueCatBridge.kt.
    compileOnly("com.revenuecat.purchases:purchases:8.4.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // SPEC-070-0 §3.4 — visual snapshot harness (JVM-only; no device required)
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.21.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.21.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.21.0")
}
