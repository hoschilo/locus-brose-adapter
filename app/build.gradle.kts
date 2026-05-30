plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "com.ebikelocus.broseadapter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ebikelocus.broseadapter"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Locus Maps Sensor Adapter API 0.10.0
    implementation("com.github.asamm.locus-api:locus-api-android:0.10.0")
}
