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
        // NewPipe Extractor (experimental in-app YouTube trailers) is published on JitPack.
        maven("https://jitpack.io")
    }
}
rootProject.name = "EmberTV"
include(":app")
// Device-only profiling is opt-in; ordinary app builds and existing workflows are unchanged.
if (providers.gradleProperty("includePerformanceTests").orNull == "true") include(":performance")
