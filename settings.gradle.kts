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
        // kotatsu-parsers (the manga sources Futon uses) is published from GitHub through JitPack
        maven("https://jitpack.io")
    }
}
rootProject.name = "book"
include(":app")
