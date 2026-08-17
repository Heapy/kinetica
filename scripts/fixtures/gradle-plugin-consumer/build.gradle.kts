@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

// What this fixture asserts: everything a Kinetica consumer needs is in the two plugin lines
// below. No -Xplugin wiring, no kinetica-runtime/kinetica-browser declarations — if the Gradle
// plugin stops doing either job, this project fails to compile.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.heapy.kinetica")
}

repositories {
    // Scoped to our own group on purpose: an unrestricted mavenLocal() also serves the partial
    // copies of third-party libraries that other tools leave in ~/.m2 (jar + pom, no Gradle
    // module metadata), and Gradle then resolves a JVM artifact into the JS compilation.
    mavenLocal {
        content {
            includeGroup("io.heapy.kinetica")
        }
    }
    mavenCentral()
}

val kineticaVersion = providers.gradleProperty("kineticaVersion").get()

kinetica {
    moduleId = "fixture"
    // JVM-only. The JS compilation must not receive it — the plugin is what keeps them apart.
    sourcePipeline = "psi"
}

kotlin {
    jvmToolchain(21)

    jvm()
    js {
        nodejs()
    }

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.heapy.kinetica:kinetica-test:$kineticaVersion")
        }
    }
}
