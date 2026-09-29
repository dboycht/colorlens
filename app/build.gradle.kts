import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dboycht.colorlens"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dboycht.colorlens"
        // API 28 gives us ImageDecoder (EXIF handling + software allocator that
        // Bitmap.getPixel requires) without a compat shim.
        minSdk = 28
        targetSdk = 36

        // Single source of truth for the version: `versionName` below.
        // `versionCode` is only bumped on release.
        versionCode = 3
        // 1.0.1 was tagged on 2026-09-28; by the project's convention the next
        // development round bumps the patch number, so this round is 1.0.2.
        // 1.0.2 is the first release that ships an APK, so it also carries the
        // first versionCode above 1.
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            // The debug build carries the camera white-balance diagnostics, and it
            // gets installed alongside the real one while that is investigated.
            // Two identical icons named 辨色助手 would be a trap, so it says what
            // it is.
            resValue("string", "app_name", "辨色助手·诊断")
        }
        release {
            // Compose debug payloads are large; shrink so the handed-out APK is
            // small enough to sideload quickly.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // NOTE: material-icons-extended is deliberately NOT included. It alone adds
    // ~40 MB to a debug APK, and this app draws its icons as vectors instead.

    // In-app camera: the whole point is "tap shutter, then tap the spot".
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
