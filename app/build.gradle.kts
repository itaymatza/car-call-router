plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val configuredApplicationId = providers.gradleProperty("APP_APPLICATION_ID")
    .orElse("org.carcallrouter.companion")
val configuredVersionCode = providers.gradleProperty("APP_VERSION_CODE")
    .map { it.toInt() }
    .getOrElse(1)
val configuredVersionName = providers.gradleProperty("APP_VERSION_NAME")
    .orElse("0.1.0")

android {
    namespace = "org.carcallrouter.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = configuredApplicationId.get()
        minSdk = 34
        targetSdk = 35
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
