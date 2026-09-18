plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.itaymatza.carcallrouter"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.itaymatza.carcallrouter"
        minSdk = 34
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.1-device-test"
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
}
