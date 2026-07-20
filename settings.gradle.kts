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
    ":edge:adapters:cloud:media-ingest",
    ":edge:platforms:android",
    ":devices:omi-cv1:edge-driver",
    ":devices:omi-cv1:application-updater:android",
    ":devices:omi-cv1:simulator",
    ":edge:shell:application",
    ":edge:shell:linux",
    ":edge:shell:android",
)
