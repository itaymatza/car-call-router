import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    application
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

ktlint {
    baseline.set(file("ktlint-baseline.xml"))
    outputToConsole.set(true)
    ignoreFailures.set(false)
    filter {
        exclude { element -> element.file.path.contains("/app/src/") }
    }
}

dependencies {
    implementation(project(":core"))
}

sourceSets {
    main {
        kotlin {
            srcDir("src/main/kotlin")
            srcDir(rootProject.file("app/src/main/java"))
            include("ServiceTests.kt")
            include("stubs/**/*.kt")
            include("org/carcallrouter/companion/RouterSettings.kt")
            include("org/carcallrouter/companion/SessionBridge.kt")
            include("org/carcallrouter/companion/telecom/AddressedTelecomRouter.kt")
            include("org/carcallrouter/companion/telecom/RouterInCallService.kt")
        }
    }
}

application {
    mainClass.set("ServiceTestsKt")
}

tasks.named<JavaExec>("run") {
    outputs.upToDateWhen { false }
}
