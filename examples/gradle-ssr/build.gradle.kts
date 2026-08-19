@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    // Everything Kinetica needs: the mandatory K2 compiler plugin on every compilation of every
    // target, plus kinetica-runtime in commonMain and kinetica-browser in jsMain at the same
    // version. Before 0.4.0 this file resolved the plugin jar itself and pushed -Xplugin into
    // each KotlinCompilationTask by hand.
    alias(libs.plugins.kinetica)
}

repositories {
    // Maven Central only: this example consumes the released io.heapy.kinetica artifacts, never
    // the ones a local `./kotlin publish mavenLocal` leaves in ~/.m2.
    mavenCentral()
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
            // Island props and JSON-LD are built by this example's own code, so it declares the
            // serialization library it uses directly rather than leaning on Kinetica's.
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
    }
}

// One `./gradlew jvmRun` builds the browser island too: the webpack output is packed into the
// server's resources, where Ktor serves it from /static.
tasks.named<ProcessResources>("jvmProcessResources") {
    from(tasks.named("jsBrowserDistribution")) {
        into("static")
    }
}
