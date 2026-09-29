import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Upload-key signing for Play (Play re-signs with its own key after upload).
// Credentials live in android/keystore.properties + the keystore itself —
// BACK THEM UP (losing the upload key means losing the ability to ship
// updates). Missing file → release builds stay unsigned, as before.
val uploadKeystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.imageresizer.app"
    // Compose BOM 2026.09 (ui 1.12) requires compileSdk 37.
    compileSdk = 37

    defaultConfig {
        // Play identity — must be globally unique across all developers.
        // (com.imageresizer.app was already claimed on Play; renamed before
        // first upload. Only applicationId changes — the Kotlin source
        // packages/namespace stay com.imageresizer.app.*.)
        applicationId = "com.appkitstudios.photocompressor"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // AdMob: Google's PUBLIC TEST IDs for debug builds — tapping your own
        // live ads is invalid traffic, so real IDs only ship in `release`
        // (overridden below; account verified 2026-09).
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
        buildConfigField(
            "String",
            "REWARDED_AD_UNIT_ID",
            "\"ca-app-pub-3940256099942544/5224354917\"",
        )
        // Anchored adaptive banner (free users only — hidden for premium).
        buildConfigField(
            "String",
            "BANNER_AD_UNIT_ID",
            "\"ca-app-pub-3940256099942544/6300978111\"",
        )
        // Full-screen ad on the finished-batch "Done" transition (free only).
        buildConfigField(
            "String",
            "INTERSTITIAL_AD_UNIT_ID",
            "\"ca-app-pub-3940256099942544/1033173712\"",
        )
        // Play Billing product id for the one-off lifetime unlock (no subscription).
        buildConfigField("String", "PRODUCT_LIFETIME", "\"lifetime\"")
    }

    signingConfigs {
        if (uploadKeystore.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(uploadKeystore.getProperty("storeFile"))
                storePassword = uploadKeystore.getProperty("storePassword")
                keyAlias = uploadKeystore.getProperty("keyAlias")
                keyPassword = uploadKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // REAL AdMob IDs (debug keeps Google's test IDs above).
            manifestPlaceholders["admobAppId"] = "ca-app-pub-8419665123353776~8832780276"
            buildConfigField(
                "String",
                "REWARDED_AD_UNIT_ID",
                "\"ca-app-pub-8419665123353776/6757106266\"",
            )
            buildConfigField(
                "String",
                "BANNER_AD_UNIT_ID",
                "\"ca-app-pub-8419665123353776/4402580678\"",
            )
            buildConfigField(
                "String",
                "INTERSTITIAL_AD_UNIT_ID",
                "\"ca-app-pub-8419665123353776/3923145080\"",
            )

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // EXIF read/write for the codec (AndroidX is first-party, not a third-party
    // image library): reads source tags, writes the preserved whitelist on output.
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    // Monetization only (no analytics/crash SDKs, no upload — docs/ALGORITHM.md):
    // AdMob rewarded ads for the free tier + UMP consent + Play Billing lifetime.
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("com.android.billingclient:billing-ktx:9.1.0")

    // App unit tests (JVM): format/alpha sniffing against the shared fixtures.
    testImplementation("junit:junit:4.13.2")
}
