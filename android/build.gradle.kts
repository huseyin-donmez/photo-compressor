// Root build file. The algorithm lives in :core — a pure Kotlin/JVM module with
// no Android dependencies, mirroring ios/ImageResizerCore (docs/ALGORITHM.md).
// :app is the Compose UI shell around it.
//
// AGP 9 enables built-in Kotlin by default → do NOT apply kotlin-android.
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    kotlin("jvm") version "2.4.20" apply false
}
