plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

tasks.register<Exec>("verifyArchitecture") {
    group = "verification"
    description = "Rejects source imports that cross Gumi's device/edge/cloud boundaries."
    inputs.files(
        fileTree("edge/sdk/src") { include("**/*.kt") },
        fileTree("edge/runtime/src") { include("**/*.kt") },
        fileTree("edge/platforms/android/src") { include("**/*.kt") },
        fileTree("edge/shell/android/src") { include("**/*.kt") },
        fileTree("devices/omi-cv1/edge-driver/src") { include("**/*.kt") },
    )
    inputs.file("gradle/verify-architecture.sh")
    commandLine("sh", "gradle/verify-architecture.sh")
}
