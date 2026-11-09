// Pure Kotlin/JVM podcast logic: RSS parsing, episode→day matching and cycle selection
// (spec 0003 §7). Shared by :app and :tools:importer-cli. Must not depend on Android (AD-12).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

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
    api(project(":core:model"))
    // Written against org.xmlpull.v1. Android provides that API; JVM consumers (CLI, tests) add kxml2.
    compileOnly(libs.kxml2)

    // A synthetic Bible-Recap-shaped feed, shared with :tools:importer-cli tests.
    testFixturesApi(project(":core:model"))

    testImplementation(libs.kxml2)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.test {
    // Opt-in real-feed test (spec 0003 §10.1): ./gradlew :core:podcast:test -Poba.feed.tbr=<feed.xml>
    providers.gradleProperty("oba.feed.tbr").orNull?.let { systemProperty("oba.feed.tbr", it) }
    providers.gradleProperty("oba.plan.tbr").orNull?.let { systemProperty("oba.plan.tbr", it) }
}
