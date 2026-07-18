pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "gumi"

include(
    ":edge:sdk",
    ":edge:runtime",
    ":edge:platforms:android",
    ":devices:omi-cv1:edge-driver",
    ":edge:shell:linux",
    ":edge:shell:android",
)
