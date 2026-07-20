plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "dev.gumi.devices.omicv1.simulator"
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
            api(project(":edge:sdk"))
            implementation(project(":devices:omi-cv1:edge-driver"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest {
            resources.srcDir(project.file("../protocols/gatt/v3.0.12"))
            resources.srcDir(project.file("../protocols/human-io/v1"))
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
