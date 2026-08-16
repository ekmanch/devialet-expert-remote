plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.christian.devialetremote"
    // Bumped 34 -> 37 to clear the "newer compileSdk available" warning.
    // This only changes which APIs are visible at compile time, not runtime
    // behavior, so it's safe on its own. targetSdk is intentionally left at
    // 34 below - jumping targetSdk to 35+ turns on enforced edge-to-edge and
    // other behavior changes that would need real device testing first.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.christian.devialetremote"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Replaces the deprecated android.kotlinOptions { jvmTarget = "17" } block.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    // Only used for BottomSheetDialog (source picker) - native drag-to-dismiss
    // and edge-to-edge handling instead of the hand-rolled Dialog+scrim it replaced.
    implementation("com.google.android.material:material:1.14.0")
}
