plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // kotlin.test maps to JUnit 4 here (the Gradle test default).
    testImplementation(kotlin("test"))
}

// Pure-Kotlin module, but the JVM-target consistency check still inspects the
// (empty) Java compile: pin it to the same 17 the Kotlin code targets, so the
// Android app module can consume :core regardless of the build JDK.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
