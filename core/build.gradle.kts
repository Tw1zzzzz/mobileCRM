plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    // JVM runs the core and its tests on the Mac; the Android target is added with the Android SDK (step 2).
    jvm()

    sourceSets {
        all {
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
        }
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
    }
}

// The Kotlin parity test reads the staff-metrics fixture shared with the server's tests: a change to it must rerun the test.
tasks.named("jvmTest") {
    inputs.file(rootProject.file("server/test/fixtures/staff-metrics.json")).withPropertyName("staffMetricsFixture")
}
