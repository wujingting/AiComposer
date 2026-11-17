// Shared library: analysis, coaching rules, AI aesthetics, languages, the per-frame pipeline and shared UI pieces.
// Every app (app-sony, app-phone, ...) depends on this module, so a fix here reaches all of them.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.aicomposer.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Unit tests run on the computer (no phone); Android APIs return default values instead of throwing
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    api(composeBom)
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material3:material3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    // ML Kit (models bundled in the app, work offline)
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    // Text recognition (Japanese + Latin), model bundled in the app
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    // Subject segmentation: provided by Google Play services, model downloaded on first use
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    // ONNX Runtime: runs the CLIP aesthetic model
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    testImplementation("junit:junit:4.13.2")
}
