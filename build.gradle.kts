plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

val discoveredGradleBuildFiles = fileTree(rootDir) {
    include(
        "devices/**/build.gradle",
        "devices/**/build.gradle.kts",
        "edge/**/build.gradle",
        "edge/**/build.gradle.kts",
        "cloud/**/build.gradle",
        "cloud/**/build.gradle.kts",
    )
    exclude(
        "**/.gradle/**",
        "**/build/**",
        "**/node_modules/**",
    )
}
val configuredModuleDirectories = subprojects
    .map { it.projectDir.relativeTo(rootDir).invariantSeparatorsPath }
    .toSortedSet()
val verifiableSubprojectTaskPaths = subprojects
    .filter { it.buildFile.isFile }
    .map { "${it.path}:check" }
    .sorted()

tasks.register<Exec>("verifyArchitecture") {
    group = "verification"
    description = "Rejects source imports and project dependencies that cross Gumi boundaries."
    inputs.files(
        fileTree("edge/sdk/src") { include("**/*.kt") },
        fileTree("edge/runtime/src") { include("**/*.kt") },
        fileTree("edge/adapters/cloud/media-ingest/src") { include("**/*.kt") },
        fileTree("edge/shell/application/src") { include("**/*.kt") },
        fileTree("edge/platforms/android/src") { include("**/*.kt") },
        fileTree("edge/shell/android/src") { include("**/*.kt") },
        fileTree("devices/omi-cv1/edge-driver/src") { include("**/*.kt") },
        fileTree("devices/omi-cv1/simulator/src") { include("**/*.kt") },
    )
    inputs.file("gradle/verify-architecture.sh")
    inputs.files(discoveredGradleBuildFiles)
    outputs.upToDateWhen { false }
    commandLine("sh", "gradle/verify-architecture.sh")
}

tasks.register<Exec>("verifyModuleCoverage") {
    group = "verification"
    description = "Rejects unconfigured or multiply configured Gradle module build directories."
    inputs.file("settings.gradle.kts")
    inputs.file("gradle/verify-module-coverage.sh")
    inputs.files(discoveredGradleBuildFiles)
    inputs.property("configuredModuleDirectories", configuredModuleDirectories)
    outputs.upToDateWhen { false }
    workingDir(rootProject.projectDir)
    commandLine(
        listOf("sh", "gradle/verify-module-coverage.sh") + configuredModuleDirectories,
    )
}

tasks.register<Exec>("verifyShellScripts") {
    group = "verification"
    description = "Syntax-checks all shell scripts and runs every self-contained shell test."
    inputs.file("gradle/verify-shell-scripts.sh")
    inputs.file("gumiw")
    inputs.files(
        fileTree(rootDir) {
            include(
                "gradle/**/*.sh",
                "devices/**/*.sh",
                "edge/**/*.sh",
                "cloud/**/*.sh",
            )
            exclude(
                "**/build/**",
                "**/node_modules/**",
            )
        },
    )
    outputs.upToDateWhen { false }
    workingDir(rootProject.projectDir)
    commandLine("sh", "gradle/verify-shell-scripts.sh")
}

tasks.register<Exec>("verifyWorkspace") {
    group = "verification"
    description = "Runs every local module, Android, shell, and cloud application verification gate."
    dependsOn(
        "verifyArchitecture",
        "verifyModuleCoverage",
        "verifyShellScripts",
        *verifiableSubprojectTaskPaths.toTypedArray(),
        ":edge:platforms:android:assembleDebugAndroidTest",
        ":edge:shell:android:assembleDebug",
        ":edge:shell:android:assembleDebugAndroidTest",
    )
    inputs.file("cloud/verify-apps.sh")
    inputs.files(
        fileTree("cloud/apps") {
            exclude(
                "**/.astrale/**",
                "**/.dist/**",
                "**/coverage/**",
                "**/dist/**",
                "**/node_modules/**",
            )
        },
    )
    outputs.upToDateWhen { false }
    workingDir(rootProject.projectDir)
    commandLine("sh", "cloud/verify-apps.sh")
}
