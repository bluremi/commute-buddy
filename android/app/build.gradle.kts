import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.commutebuddy.app"
    compileSdk = 35

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
    }
    val geminiModelName = localProperties.getProperty("GEMINI_MODEL_NAME") ?: "gemini-flash-latest"

    defaultConfig {
        applicationId = "com.commutebuddy.app"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GEMINI_MODEL_NAME", "\"$geminiModelName\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.json:json:20250107")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Connect IQ Mobile SDK — wired up in Increment 3
    implementation("com.garmin.connectiq:ciq-companion-app-sdk:2.3.0@aar") {
        isTransitive = true
    }
    // Firebase AI Logic SDK (Gemini API with ThinkingConfig support)
    implementation(platform("com.google.firebase:firebase-bom:34.10.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-appcheck-debug")
    implementation("com.google.firebase:firebase-analytics")
    // Wear OS Data Layer API (Android phone side only — no wear module yet)
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
