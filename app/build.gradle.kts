plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.minipots.ar"
    compileSdk = 35
    defaultConfig { applicationId = "com.minipots.ar"; minSdk = 24; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("com.google.ar:core:1.46.0") }
