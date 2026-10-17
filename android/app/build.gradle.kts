plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sivelswhy.multiblur"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sivelswhy.multiblur"
        // Android 10: scoped storage saves to the gallery without any permission.
        minSdk = 29
        targetSdk = 37
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "1.0.${versionCode}"
        // 64-bit ARM covers every recent phone; each extra ABI adds ~30 MB of ONNX Runtime.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            // Signed with the debug key so the APK installs directly (sideloading).
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true }

    // The ONNX models are shared with the web app.
    sourceSets.getByName("main").assets.srcDir("../../web/public")
    androidResources { noCompress += "onnx" }
    // Compressed native libraries: a much smaller download, unpacked once at install.
    packaging { jniLibs.useLegacyPackaging = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
}
