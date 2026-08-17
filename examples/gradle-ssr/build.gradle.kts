@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    // Maven Central only: this example consumes the released io.heapy.kinetica artifacts, never
    // the ones a local `./kotlin publish mavenLocal` leaves in ~/.m2.
    mavenCentral()
}

// The Kinetica compiler plugin ships as a plain jar (no Gradle subplugin yet), so it is resolved
// through its own configuration and handed to every Kotlin compilation as -Xplugin=<jar>.
val kineticaCompiler = configurations.resolvable("kineticaCompiler") {
    isTransitive = false
}

dependencies {
    add(kineticaCompiler.name, libs.kinetica.compiler)
}

val kineticaPluginArgument: Provider<String> =
    kineticaCompiler.flatMap { configuration ->
        configuration.elements.map { jars -> "-Xplugin=${jars.single().asFile.absolutePath}" }
    }

kotlin {
    jvmToolchain(21)

    jvm {
        mainRun {
            mainClass.set("seo.server.MainKt")
        }
    }

    js {
        browser {
            commonWebpackConfig {
                outputFileName = "client.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kinetica.runtime)
            // Kinetica exposes kotlinx.serialization at runtime only; island props and JSON-LD
            // are built here, so the compile-time dependency is declared explicitly.
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.compression)
            implementation(libs.ktor.server.caching.headers)
            implementation(libs.ktor.server.status.pages)
            runtimeOnly(libs.slf4j.simple)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.server.test.host)
        }
        jsMain.dependencies {
            implementation(libs.kinetica.browser)
        }
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions.freeCompilerArgs.add(kineticaPluginArgument)
}

// One `./gradlew jvmRun` builds the browser island too: the webpack output is packed into the
// server's resources, where Ktor serves it from /static.
tasks.named<ProcessResources>("jvmProcessResources") {
    from(tasks.named("jsBrowserDistribution")) {
        into("static")
    }
}
