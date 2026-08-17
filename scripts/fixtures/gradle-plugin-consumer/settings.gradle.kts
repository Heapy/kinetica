pluginManagement {
    // mavenLocal first: this fixture always verifies the plugin that was just built, never a
    // released one.
    repositories {
        mavenLocal {
            content {
                includeGroup("io.heapy.kinetica")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }

    val kineticaVersion = providers.gradleProperty("kineticaVersion").orNull
        ?: error("pass -PkineticaVersion=<version>, or run scripts/verify-gradle-plugin.mjs")

    plugins {
        id("org.jetbrains.kotlin.multiplatform") version "2.4.10"
        id("io.heapy.kinetica") version kineticaVersion
    }
}

rootProject.name = "gradle-plugin-consumer"
