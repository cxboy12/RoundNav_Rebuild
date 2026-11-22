plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.roundnav"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.roundnav"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-rebuild"
        buildConfigField("String", "ROUNDNAV_BASE_URL", "\"http://111.229.191.7\"")
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
}
