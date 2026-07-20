plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":edge:sdk"))
    implementation(project(":edge:runtime"))
    implementation(project(":edge:shell:application"))
    implementation(project(":devices:omi-cv1:edge-driver"))
    implementation(project(":devices:omi-cv1:simulator"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "dev.gumi.edge.shell.linux.MainKt"
}

tasks.test {
    useJUnitPlatform()
}
