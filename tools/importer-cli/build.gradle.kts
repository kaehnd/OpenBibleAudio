// JVM CLI around :core:importer (AD-5, spec 0001 §7).
//   ./gradlew :tools:importer-cli:run --args="generate bible_audio/bsb_gilbert --profile bsb-gilbert"
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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

application {
    mainClass = "com.openbibleaudio.tools.importer.MainKt"
    applicationName = "importer-cli"
    // Clikt's terminal detection loads JNA; avoid the JDK's restricted-method warning.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    // Resolve relative paths (e.g. bible_audio/bsb_gilbert) from the repository root.
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":core:importer"))
    implementation(project(":core:podcast")) // plan from-feed / validate --feed (spec 0003 §4)
    implementation(libs.kxml2)
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":core:importer")))
    testImplementation(testFixtures(project(":core:podcast")))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
