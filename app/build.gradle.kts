plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.waz.pocketpdf"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.waz.pocketpdf"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
    }

    // A keystore that lives in the repo, so every build — local or CI — signs
    // with the same key. Without this each CI run generates its own throwaway
    // debug key, Android sees a signature mismatch, and installing a new APK
    // fails with "package conflicts with an existing package" until the old app
    // is uninstalled. This is a debug key only; it must never sign a Play release.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Apache PDFBox, ported to Android. Does the actual PDF writing:
    // text, images, page tree surgery. Rendering stays on Android's own
    // PdfRenderer, which is hardware-backed and much faster.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
