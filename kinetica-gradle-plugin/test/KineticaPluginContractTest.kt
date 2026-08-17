package io.heapy.kinetica.gradle

import io.heapy.kinetica.compiler.KineticaCompilerContract
import org.gradle.api.Plugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Gradle plugin duplicates the compiler contract as string constants (it must not depend on
 * `kinetica-compiler` at compile time) and its coordinates are baked in by hand. Both are only
 * safe because this test fails when they drift.
 */
class KineticaPluginContractTest {
    @Test
    fun bakedVersionMatchesThePublishedOne() {
        val published = valueOf(repositoryRoot().resolve("publish.module-template.yaml"), "version")
        assertEquals(published, KineticaCoordinates.version, "KineticaCoordinates.version is stale")
        assertEquals(
            published,
            KineticaCompilerContract.pluginVersion,
            "KineticaCompilerContract.pluginVersion is stale",
        )
    }

    @Test
    fun coordinatesPointAtArtifactsThisRepositoryPublishes() {
        val root = repositoryRoot()
        assertEquals(
            valueOf(root.resolve("publish.module-template.yaml"), "group"),
            KineticaCoordinates.group,
        )
        for (artifact in listOf(
            KineticaCoordinates.compilerArtifact,
            KineticaCoordinates.runtimeArtifact,
            KineticaCoordinates.browserArtifact,
        )) {
            // Module directory name is the artifactId the toolchain publishes under.
            assertTrue(
                root.resolve("$artifact/module.yaml").isFile,
                "$artifact is not a module of this project",
            )
        }
    }

    /** The two build systems must ask for the same compiler plugin build. */
    @Test
    fun compilerCoordinateMatchesTheToolchainTemplate() {
        val template = repositoryRoot().resolve("common.module-template.yaml")
        val expected = "${KineticaCoordinates.group}:${KineticaCoordinates.compilerArtifact}:" +
            KineticaCoordinates.version
        assertTrue(
            template.readText().contains("dependency: $expected"),
            "common.module-template.yaml does not wire $expected",
        )
        assertEquals(
            valueOf(template, "version"),
            KineticaCoordinates.kotlinVersion,
            "KineticaCoordinates.kotlinVersion no longer matches the Kotlin version modules build with",
        )
    }

    @Test
    fun optionNamesMatchTheCompilerContract() {
        assertEquals(KineticaCompilerContract.pluginId, KineticaCoordinates.compilerPluginId)
        assertEquals(KineticaCompilerContract.optionModuleId, KineticaCoordinates.optionModuleId)
        assertEquals(KineticaCompilerContract.optionServerSourceSet, KineticaCoordinates.optionServerSourceSet)
        assertEquals(KineticaCompilerContract.optionClientSourceSet, KineticaCoordinates.optionClientSourceSet)
        assertEquals(KineticaCompilerContract.optionTransforms, KineticaCoordinates.optionTransforms)
        assertEquals(KineticaCompilerContract.optionSourcePipeline, KineticaCoordinates.optionSourcePipeline)
        assertEquals(KineticaCompilerContract.optionChecks, KineticaCoordinates.optionChecks)
    }

    /** Gradle resolves `id("io.heapy.kinetica")` through this descriptor and nothing else. */
    @Test
    fun pluginDescriptorNamesALoadablePluginClass() {
        val resource = javaClass.classLoader.getResource(DESCRIPTOR_PATH)
        assertNotNull(resource, "$DESCRIPTOR_PATH is not packaged onto the classpath")

        val implementationClass = resource.openStream().use { stream ->
            Properties().apply { load(stream) }.getProperty("implementation-class")
        }
        assertNotNull(implementationClass, "descriptor has no implementation-class")

        val pluginClass = Class.forName(implementationClass)
        assertTrue(
            Plugin::class.java.isAssignableFrom(pluginClass),
            "$implementationClass is not a Gradle Plugin",
        )
        assertTrue(
            KotlinCompilerPluginSupportPlugin::class.java.isAssignableFrom(pluginClass),
            "$implementationClass would not register with the Kotlin Gradle plugin",
        )
    }

    /**
     * Gradle derives the marker coordinates from the plugin id, so the descriptor's file name and
     * the marker the release script writes have to agree — nothing else checks this pairing.
     */
    @Test
    fun markerScriptPublishesTheIdTheDescriptorDeclares() {
        val root = repositoryRoot()
        assertTrue(
            root.resolve("kinetica-gradle-plugin/resources/$DESCRIPTOR_PATH").isFile,
            "plugin id changed without renaming the descriptor",
        )

        val marker = root.resolve("scripts/gradle-plugin-marker.sh").readText()
        assertTrue(
            marker.contains("artifact=\"$PLUGIN_ID.gradle.plugin\""),
            "the marker script does not publish the marker for $PLUGIN_ID",
        )
        assertTrue(
            marker.contains("<artifactId>kinetica-gradle-plugin</artifactId>"),
            "the marker does not depend on this module",
        )
    }

    private fun valueOf(file: File, key: String): String {
        assertTrue(file.isFile, "missing $file")
        val pattern = Regex("""$key:\s*(\S+)""")
        return file.readLines()
            .firstNotNullOfOrNull { line -> pattern.matchEntire(line.trim())?.groupValues?.get(1) }
            ?: error("no `$key:` line in $file")
    }

    private fun repositoryRoot(): File {
        var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
        while (candidate != null) {
            if (candidate.resolve("publish.module-template.yaml").isFile) return candidate
            candidate = candidate.parentFile
        }
        error("no repository root above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PLUGIN_ID = "io.heapy.kinetica"
        const val DESCRIPTOR_PATH = "META-INF/gradle-plugins/$PLUGIN_ID.properties"
    }
}
