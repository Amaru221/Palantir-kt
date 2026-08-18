plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") // Sin indicar 'version' aquí
}

android {
    namespace = "com.amaru.palantir"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.amaru.palantir"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Dependencias base de AndroidX y Compose
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Wear OS UI (Jetpack Compose)
    implementation("androidx.wear.compose:compose-material:1.3.0")
    implementation("androidx.wear.compose:compose-foundation:1.3.0")
    implementation("androidx.wear.compose:compose-ui-tooling:1.3.0")

    // SDK Oficial de Google AI / Gemini para Kotlin
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
}