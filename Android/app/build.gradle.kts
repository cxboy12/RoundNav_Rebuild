import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localSettings = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun javaString(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.example.roundnav"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.roundnav"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-rebuild"
        listOf("VIETMAP_TILEMAP_KEY", "VIETMAP_ROUTE_URL", "VIETMAP_ROADS_URL").forEach { name ->
            buildConfigField("String", name, javaString(localSettings.getProperty(name, "").trim()))
        }
        manifestPlaceholders["roundnavCleartext"] = "false"
        buildConfigField("boolean", "VIETMAP_USB_TILE_PROXY", "false")
    }

    buildTypes.getByName("debug") {
        manifestPlaceholders["roundnavCleartext"] = "true"
        val routeEndpoint = localSettings.getProperty("VIETMAP_ROUTE_URL").orEmpty().trim()
        buildConfigField("String", "VIETMAP_ROUTE_URL", javaString(routeEndpoint.ifEmpty { "http://127.0.0.1:8765/route" }))
        buildConfigField("boolean", "VIETMAP_USB_TILE_PROXY",
            (providers.gradleProperty("roundnavVietmapUsbProxy").orNull == "true").toString())
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.github.vietmap-company:maps-sdk-android:4.1.0")
    implementation("com.github.vietmap-company:vietmap-services-geojson-android:1.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
}
