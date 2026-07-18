plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "dev.gumi.edge.runtime"
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
