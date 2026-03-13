plugins {
    id("com.android.library") version "8.2.0"
    id("org.jetbrains.kotlin.android") version "1.9.22"
    id("maven-publish")
    id("signing")
}

android {
    namespace = "ai.appdna.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
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
    implementation("com.google.firebase:firebase-firestore-ktx:24.10.0")

    // Firebase Cloud Messaging for push notifications
    implementation("com.google.firebase:firebase-messaging-ktx:23.4.0")

    // OkHttp for event ingestion
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

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

    // RevenueCat (optional — conditionally used)
    compileOnly("com.revenuecat.purchases:purchases:7.0.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
