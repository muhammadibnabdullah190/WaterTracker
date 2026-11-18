plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.watertracker"
    compileSdk = 36
    signingConfigs {
        getByName("debug") {
            val stableKeyPath = System.getenv("WATERTRACKER_KEYSTORE_PATH")
            if (!stableKeyPath.isNullOrBlank()) {
                storeFile = file(stableKeyPath)
                storePassword = System.getenv("WATERTRACKER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WATERTRACKER_KEY_ALIAS")
                keyPassword = System.getenv("WATERTRACKER_KEY_PASSWORD")
            }
        }
    }
    defaultConfig {
        applicationId = "com.example.watertracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.3.6"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.guava:guava:33.3.1-android")
}
