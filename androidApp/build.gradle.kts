import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing key lives outside the repository: ~/.pulse-signing/keystore.properties, or the file named by
// PULSE_SIGNING_PROPERTIES (storeFile, storePassword, keyAlias, keyPassword). Without it the release APK is unsigned.
val releaseSigning: Properties? =
    (System.getenv("PULSE_SIGNING_PROPERTIES")?.let(::File) ?: File(System.getProperty("user.home"), ".pulse-signing/keystore.properties"))
        .takeIf { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

// Faceit Data API client-side key: `faceit.apiKey` in local.properties (git-ignored) or PULSE_FACEIT_API_KEY.
// Without it players enter their own key in the app.
val faceitKey: String? = System.getenv("PULSE_FACEIT_API_KEY")
    ?: rootProject.file("local.properties").takeIf { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } }.getProperty("faceit.apiKey") }

// Pulse API base URL (e.g. https://api.example.com/v1/): `pulse.apiBaseUrl` in local.properties or PULSE_API_BASE_URL.
// Empty (default) = local build with on-device accounts.
val apiBaseUrl: String = System.getenv("PULSE_API_BASE_URL")
    ?: rootProject.file("local.properties").takeIf { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } }.getProperty("pulse.apiBaseUrl") }
    ?: ""

android {
    namespace = "com.pulse.android"
    compileSdk = 37

    defaultConfig {
        // Same as iOS for now; must be final before the first Google Play upload.
        applicationId = "com.pulsecrm.mvp"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "FACEIT_API_KEY", "\"${faceitKey.orEmpty()}\"")
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.trim()}\"")
    }

    signingConfigs {
        releaseSigning?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Compose UI tests run on the JVM (Robolectric) and need the merged resources.
    testOptions { unitTests.isIncludeAndroidResources = true }

    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
        warningsAsErrors = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.compose.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.tooling)
}
