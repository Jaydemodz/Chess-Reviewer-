plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.chessreviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.chessreviewer"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
    }

    buildFeatures {
        // The Shizuku user service talks to the app over AIDL.
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Shizuku: runs the screenshot code in a shell-privileged process on a phone without root.
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
}
