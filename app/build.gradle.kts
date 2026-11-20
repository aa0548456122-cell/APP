plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "il.co.shoppingoffline"
    compileSdk = 35
    defaultConfig { applicationId = "il.co.shoppingoffline"; minSdk = 23; targetSdk = 35; versionCode = 1; versionName = "1.0" }
}
dependencies { implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0") }
