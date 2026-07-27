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
        versionCode = 13
        versionName = "0.13.0-ota-handoff-repair"
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
    implementation(project(":edge:platforms:android"))
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

val recoveryOnlyApplication = rootProject.layout.projectDirectory.file(
    "local/firmware/omi-cv1/recovery-only-0001/omi.signed.bin",
)
val stockRecoveryApplication = rootProject.layout.projectDirectory.file(
    "local/firmware/omi-cv1/stock-v3.0.12/omi.signed.bin",
)
val stockRecoveryArchive = rootProject.layout.projectDirectory.file(
    "local/firmware/omi-cv1/stock-v3.0.12/Omi_CV1_OTA_v3.0.12.zip",
)
val capturePortSelftestApplication = rootProject.layout.projectDirectory.file(
    "local/firmware/omi-cv1/capture-port-selftest-0001/omi.signed.bin",
)
val recordingRootProvisionerApplication = rootProject.layout.projectDirectory.file(
    "local/firmware/omi-cv1/recording-root-provisioner-0001/omi.signed.bin",
)
val legacyStorageReclaimerApplication = rootProject.layout.projectDirectory.file(
    "local/firmware/omi-cv1/legacy-storage-reclaimer-0002/omi.signed.bin",
)
val functionalRecording0006Application = rootProject.layout.projectDirectory.file(
    "local/firmware/omi-cv1/functional-recording-0006/omi.signed.bin",
)
val functionalRecording0007Application = rootProject.layout.projectDirectory.file(
    "local/firmware/omi-cv1/functional-recording-0007/omi.signed.bin",
)
val generatedFlashLabAssets = layout.buildDirectory.dir("generated/flash-lab-assets")
val verifyFlashLabArtifacts = tasks.register<Exec>("verifyFlashLabArtifacts") {
    group = "verification"
    description =
        "Verifies the seven exact image-0 inputs and official stock-normalization image-1 input."
    inputs.file("scripts/verify-artifacts.sh")
    inputs.files(
        recoveryOnlyApplication,
        capturePortSelftestApplication,
        recordingRootProvisionerApplication,
        legacyStorageReclaimerApplication,
        functionalRecording0006Application,
        functionalRecording0007Application,
        stockRecoveryApplication,
        stockRecoveryArchive,
    )
    outputs.upToDateWhen { false }
    commandLine(
        "sh",
        layout.projectDirectory.file("scripts/verify-artifacts.sh").asFile.absolutePath,
        recoveryOnlyApplication.asFile.absolutePath,
        capturePortSelftestApplication.asFile.absolutePath,
        recordingRootProvisionerApplication.asFile.absolutePath,
        legacyStorageReclaimerApplication.asFile.absolutePath,
        functionalRecording0006Application.asFile.absolutePath,
        functionalRecording0007Application.asFile.absolutePath,
        stockRecoveryApplication.asFile.absolutePath,
        stockRecoveryArchive.asFile.absolutePath,
    )
}

val prepareFlashLabArtifacts = tasks.register<Sync>("prepareFlashLabArtifacts") {
    group = "build setup"
    description =
        "Verifies and packages seven closed image-0 artifacts plus one stock-normalization image-1."
    inputs.files(
        recoveryOnlyApplication,
        capturePortSelftestApplication,
        recordingRootProvisionerApplication,
        legacyStorageReclaimerApplication,
        functionalRecording0006Application,
        functionalRecording0007Application,
        stockRecoveryApplication,
        stockRecoveryArchive,
    )
    outputs.dir(generatedFlashLabAssets)
    dependsOn(verifyFlashLabArtifacts)

    from(recoveryOnlyApplication) {
        into("firmware")
        rename { "recovery-only-0001-application-image-0.bin" }
    }
    from(stockRecoveryApplication) {
        into("firmware")
        rename { "stock-v3.0.12-application-image-0.bin" }
    }
    from(zipTree(stockRecoveryArchive)) {
        include("ipc_radio.bin")
        into("firmware")
        rename { "stock-v3.0.12-network-image-1.bin" }
    }
    from(capturePortSelftestApplication) {
        into("firmware")
        rename { "capture-port-selftest-0001-application-image-0.bin" }
    }
    from(recordingRootProvisionerApplication) {
        into("firmware")
        rename { "recording-root-provisioner-0001-application-image-0.bin" }
    }
    from(legacyStorageReclaimerApplication) {
        into("firmware")
        rename { "legacy-storage-reclaimer-0002-application-image-0.bin" }
    }
    from(functionalRecording0006Application) {
        into("firmware")
        rename { "functional-recording-0006-application-image-0.bin" }
    }
    from(functionalRecording0007Application) {
        into("firmware")
        rename { "functional-recording-0007-application-image-0.bin" }
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
val resolvedAndroidSdk = androidComponents.sdkComponents.sdkDirectory
val verifyFlashLabDebugApk = tasks.register<Exec>("verifyFlashLabDebugApk") {
    group = "verification"
    description = "Audits the packaged flash-lab APK permissions and exact firmware asset boundary."
    dependsOn("assembleDebug")
    inputs.file("scripts/verify-apk.test.sh")
    inputs.file(debugFlashLabApk)
    inputs.dir(resolvedAndroidSdk.map { it.dir("build-tools/36.0.0") })
    outputs.upToDateWhen { false }
    environment("ANDROID_SDK_ROOT", resolvedAndroidSdk.get().asFile.absolutePath)
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
