// Pure Kotlin/JVM importer: scans a file tree and builds an Audio Bible Pack manifest
// (AD-5, spec 0001 §6). Must not depend on Android APIs (AD-12).
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
    implementation(libs.kotlinx.coroutines.core)

    // InMemoryFileTree and synthetic MP3 builders, shared with :tools:importer-cli tests.
    testFixturesApi(project(":core:model"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.test {
    // Opt-in real-corpus test (spec 0001 §8): ./gradlew :core:importer:test -Poba.corpus.bsb=<dir>
    // The OBA_CORPUS_BSB environment variable works too (inherited by the test JVM).
    providers.gradleProperty("oba.corpus.bsb").orNull?.let { systemProperty("oba.corpus.bsb", it) }
}
