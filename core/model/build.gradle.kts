// Pure Kotlin/JVM module: domain model shared by the app and the importer CLI.
// Must not depend on Android APIs (see specifications/0000-vision-and-architecture.md, AD-12).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// JVM 17 across all modules (AD-1).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // Pack manifest schema (AD-4, spec 0001 §4) is exposed to consumers.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
