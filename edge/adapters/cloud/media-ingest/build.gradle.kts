plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "dev.gumi.edge.adapters.cloud.mediaingest"
        compileSdk {
            version = release(37) {
                minorApiLevel = 0
            }
        }
        minSdk = 29
        withHostTestBuilder {}
    }

    jvm()
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            api(project(":edge:runtime"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okhttp)
            implementation(libs.okhttp.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.okhttp.mockwebserver)
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("gumi.repositoryRoot", rootProject.projectDir.absolutePath)
}
