plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "il.co.shoppingoffline"
    compileSdk = 35
    defaultConfig {
        applicationId = "il.co.shoppingoffline"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
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
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    // On-device inference for the Hebrew handwriting recognizer (no network at runtime).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.2")
}
