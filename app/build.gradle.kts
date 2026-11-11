import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Signing: keystore.properties locally (git-ignored), or environment variables on GitHub Actions.
val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signing(name: String, env: String): String? = (keyProps.getProperty(name) ?: System.getenv(env))?.trim()?.ifEmpty { null }

android {
    namespace = "com.ledgersync.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ledgersync.app"
        minSdk = 26
        targetSdk = 35
        // CI passes these from the git tag (v1.2.0 → 1.2.0) and the run number.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"
        // "owner/repo" used by the in-app update check against GitHub Releases.
        buildConfigField("String", "GITHUB_REPO", "\"${project.findProperty("githubRepo") ?: "Utsavshah222/ledgersync"}\"")
        // Pre-filled on every phone so only the secret key has to be entered.
        buildConfigField(
            "String", "DEFAULT_SCRIPT_URL",
            "\"https://script.google.com/macros/s/AKfycbzVXIBmhyPsPvDZHnAMIUaIuLS7S5mnGqH9AouqSorKAseR4lDKsZPfYjZkkDXs1Kzm/exec\"",
        )
    }

    signingConfigs {
        create("release") {
            val store = signing("storeFile", "KEYSTORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = signing("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signing("keyAlias", "KEY_ALIAS")
                keyPassword = signing("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
