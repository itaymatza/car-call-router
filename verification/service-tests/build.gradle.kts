plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        kotlin {
            srcDir(projectDir)
            srcDir(rootProject.file("app/src/main/java"))
            include("ServiceTests.kt")
            include("stubs/**/*.kt")
            include("org/carcallrouter/companion/RouterSettings.kt")
            include("org/carcallrouter/companion/SessionBridge.kt")
            include("org/carcallrouter/companion/core/EndpointIdentity.kt")
            include("org/carcallrouter/companion/core/RoutingPolicy.kt")
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
