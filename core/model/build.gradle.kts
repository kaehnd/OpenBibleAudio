// Pure Kotlin/JVM module: domain model shared by the app and the importer CLI.
// Must not depend on Android APIs (see specifications/0000-vision-and-architecture.md, AD-12).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
