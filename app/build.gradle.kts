plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rotacerta.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rotacerta.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-build1"
        manifestPlaceholders["MAPS_API_KEY"] = providers.gradleProperty("MAPS_API_KEY").orElse("").get()
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("com.google.android.libraries.navigation:navigation:8.0.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}

configurations.configureEach {
    exclude(group = "com.google.android.gms", module = "play-services-maps")
}
