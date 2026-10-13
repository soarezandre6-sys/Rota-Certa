plugins {
    id("com.android.application")
}

val mapsApiKeyProvider = providers.gradleProperty("MAPS_API_KEY").orElse("")
val mapsApiKey = mapsApiKeyProvider.get()
val googleNavConfigured = mapsApiKey.isNotBlank() && mapsApiKey != "CI_PLACEHOLDER"

android {
    namespace = "com.rotacerta.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rotacerta.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-build2"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("boolean", "GOOGLE_NAV_CONFIGURED", googleNavConfigured.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("com.google.android.libraries.navigation:navigation:8.0.0")
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
