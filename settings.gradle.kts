pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "historical-marker-alerts"
include(":app")

// :tools fetches NRHP records and Wikipedia blurbs and emits the prebuilt
// SQLite asset. It is a plain JVM module, deliberately NOT an Android one, and
// nothing in :app depends on it — it never ships in the APK. It is run by hand
// when the marker data needs regenerating, not as part of a normal build.
include(":tools")
