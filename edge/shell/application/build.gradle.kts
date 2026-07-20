plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "dev.gumi.edge.shell.application"
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
            api(project(":edge:runtime"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
