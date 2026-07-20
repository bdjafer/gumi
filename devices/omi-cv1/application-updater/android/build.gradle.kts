import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.gumi.devices.omicv1.updater.android"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "dev.gumi.omicv1.flashlab"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-canary-lab"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // API 36 is the currently qualified phone boundary for this local lab application.
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(project(":edge:sdk"))
    implementation(project(":devices:omi-cv1:edge-driver"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.nordic.mcumgr.ble)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

val canaryApplication = rootProject.layout.projectDirectory.file(
    "local/firmware/omi-cv1/canary-0001/omi.signed.bin",
)
val stockRecoveryApplication = rootProject.layout.projectDirectory.file(
    "local/firmware/omi-cv1/stock-v3.0.12/omi.signed.bin",
)
val generatedFlashLabAssets = layout.buildDirectory.dir("generated/flash-lab-assets")
val verifyFlashLabArtifacts = tasks.register<Exec>("verifyFlashLabArtifacts") {
    group = "verification"
    description = "Verifies the exact local canary and stock application-image-0 inputs."
    inputs.file("scripts/verify-artifacts.sh")
    inputs.files(canaryApplication, stockRecoveryApplication)
    outputs.upToDateWhen { false }
    commandLine(
        "sh",
        layout.projectDirectory.file("scripts/verify-artifacts.sh").asFile.absolutePath,
        canaryApplication.asFile.absolutePath,
        stockRecoveryApplication.asFile.absolutePath,
    )
}

val prepareFlashLabArtifacts = tasks.register<Sync>("prepareFlashLabArtifacts") {
    group = "build setup"
    description = "Verifies and packages only the two qualified Omi application-image-0 artifacts."
    inputs.files(canaryApplication, stockRecoveryApplication)
    outputs.dir(generatedFlashLabAssets)
    dependsOn(verifyFlashLabArtifacts)

    from(canaryApplication) {
        into("firmware")
        rename { "canary-0001-application-image-0.bin" }
    }
    from(stockRecoveryApplication) {
        into("firmware")
        rename { "stock-v3.0.12-application-image-0.bin" }
    }
    into(generatedFlashLabAssets)
}

android.sourceSets.named("main") {
    assets.directories.add(generatedFlashLabAssets.get().asFile.absolutePath)
}

tasks.named("preBuild").configure {
    dependsOn(prepareFlashLabArtifacts)
}

val debugFlashLabApk = layout.buildDirectory.file("outputs/apk/debug/android-debug.apk")
val verifyFlashLabDebugApk = tasks.register<Exec>("verifyFlashLabDebugApk") {
    group = "verification"
    description = "Audits the packaged flash-lab APK permissions and exact firmware asset boundary."
    dependsOn("assembleDebug")
    inputs.file("scripts/verify-apk.test.sh")
    inputs.file(debugFlashLabApk)
    outputs.upToDateWhen { false }
    commandLine(
        "sh",
        layout.projectDirectory.file("scripts/verify-apk.test.sh").asFile.absolutePath,
        debugFlashLabApk.get().asFile.absolutePath,
    )
}

val verifyFlashLabPhonePreparation = tasks.register<Exec>("verifyFlashLabPhonePreparation") {
    group = "verification"
    description = "Proves the physical-phone handoff remains APK-only and stops before authorization."
    inputs.files(
        "scripts/prepare-physical-phone.sh",
        "scripts/prepare-physical-phone.test.sh",
    )
    outputs.upToDateWhen { false }
    commandLine("sh", layout.projectDirectory.file("scripts/prepare-physical-phone.test.sh"))
}

tasks.named("check").configure {
    dependsOn(verifyFlashLabDebugApk, verifyFlashLabPhonePreparation)
}
