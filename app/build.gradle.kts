plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val configuredApplicationId = providers.gradleProperty("APP_APPLICATION_ID")
    .orElse("org.carcallrouter.companion")
val configuredVersionCode = providers.gradleProperty("APP_VERSION_CODE")
    .map { it.toInt() }
    .getOrElse(5)
val configuredVersionName = providers.gradleProperty("APP_VERSION_NAME")
    .orElse("0.3.0-beta.3")
val enableBetaSigning = providers.gradleProperty("ENABLE_BETA_SIGNING")
    .map { it.toBoolean() }
    .getOrElse(false)

android {
    namespace = "org.carcallrouter.companion"
    // API 37 is not yet available from the hosted sdkmanager repository. The service declares and
    // regression-tests its exact forward-compatible virtual signature while building on API 36.
    compileSdk = 36

    defaultConfig {
        applicationId = configuredApplicationId.get()
        minSdk = 34
        targetSdk = 36
        versionCode = configuredVersionCode
        versionName = configuredVersionName.get()
    }

    buildFeatures { buildConfig = true }
    signingConfigs {
        if (enableBetaSigning) {
            create("beta") {
                storeFile = file(providers.environmentVariable("BETA_KEYSTORE_FILE").get())
                storePassword = providers.environmentVariable("BETA_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("BETA_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("BETA_KEY_PASSWORD").get()
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (enableBetaSigning) signingConfig = signingConfigs.getByName("beta")
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
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
