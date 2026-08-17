// Single-target Kotlin/JVM — the shape an SSR server has. Its extension is a
// KotlinSingleTargetExtension with no targets container and no commonMain source set, so the
// plugin has to take a different path than in the multiplatform fixture: `main` gets the runtime,
// and `sourcePipeline = "psi"` must not produce the "project has no JVM target" warning.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.heapy.kinetica")
}

repositories {
    mavenLocal {
        content {
            includeGroup("io.heapy.kinetica")
        }
    }
    mavenCentral()
}

val kineticaVersion = providers.gradleProperty("kineticaVersion").get()

kinetica {
    moduleId = "fixture-jvm"
    sourcePipeline = "psi"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.heapy.kinetica:kinetica-test:$kineticaVersion")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
