plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.gumi.edge.platforms.android"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":edge:sdk"))
    implementation(project(":edge:runtime"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.nordic.ble.ktx)
    implementation(libs.nordic.mcumgr.ble)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(kotlin("test-junit"))
}
