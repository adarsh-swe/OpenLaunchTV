plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hodor.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hodor.launcher"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party dependencies — stdlib + Android SDK only. Keeps the APK
// tiny and cold-start instant, which is the whole point of this launcher.
dependencies {
}
