plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val configuredApplicationId = providers.gradleProperty("APP_APPLICATION_ID")
    .orElse("org.carcallrouter.companion")
val configuredVersionCode = providers.gradleProperty("APP_VERSION_CODE")
    .map { it.toInt() }
    .getOrElse(4)
val configuredVersionName = providers.gradleProperty("APP_VERSION_NAME")
    .orElse("0.3.0-beta.2")

android {
    namespace = "org.carcallrouter.companion"
    // API 37 compiler verification is required for onCallEndpointRequested(). Keep the runtime
    // behavior target on stable API 36 until Android 17 behavior changes are qualified separately.
    compileSdk = 37

    defaultConfig {
        applicationId = configuredApplicationId.get()
        minSdk = 34
        targetSdk = 36
        versionCode = configuredVersionCode
        versionName = configuredVersionName.get()
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
