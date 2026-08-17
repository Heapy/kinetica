rootProject.name = "kinetica-gradle-ssr"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Dependency repositories are declared in build.gradle.kts rather than here: the Kotlin/JS plugin
// registers its own Node.js distribution repository on the project, and a settings-only setup
// (`repositoriesMode`) either rejects it or shadows Maven Central.
