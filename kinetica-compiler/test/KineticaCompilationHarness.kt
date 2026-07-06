package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

internal class KineticaCompilationHarness {
    fun compile(
        sources: Map<String, String>,
        moduleName: String = "test",
        transforms: String = "on",
        checks: String = "off",
    ): CompiledKineticaModule {
        val result = compileInternal(sources, moduleName, transforms, checks)
        if (!result.success || result.messages.any { it.severity.isError }) {
            fail(
                buildString {
                    appendLine("Compilation failed.")
                    result.messages.forEach { message ->
                        appendLine("${message.severity}: ${message.message}")
                    }
                },
            )
        }
        return CompiledKineticaModule(
            outputDir = result.outputDir,
            messages = result.messages,
            classLoader = URLClassLoader(
                arrayOf(result.outputDir.toURI().toURL()),
                Thread.currentThread().contextClassLoader,
            ),
        )
    }

    /** Compiles sources expected to violate the Kinetica FIR rules; returns all messages. */
    fun compileExpectingErrors(
        sources: Map<String, String>,
        moduleName: String = "test",
        checks: String = "error",
    ): List<RecordedCompilerMessage> {
        val result = compileInternal(sources, moduleName, transforms = "on", checks = checks)
        assertTrue(
            !result.success || result.messages.any { it.severity.isError },
            "Expected compilation errors, but compilation succeeded. Messages:\n" +
                result.messages.joinToString("\n") { "${it.severity}: ${it.message}" },
        )
        return result.messages
    }

    private class InternalCompilationResult(
        val success: Boolean,
        val outputDir: File,
        val messages: List<RecordedCompilerMessage>,
    )

    /**
     * Runs the real CLI entry (K2JVMCompiler) with the plugin passed via -Xplugin — the
     * exact loading path production builds use, including FIR extension registration,
     * which the legacy in-process KotlinCoreEnvironment entry point never bridged.
     */
    private fun compileInternal(
        sources: Map<String, String>,
        moduleName: String,
        transforms: String,
        checks: String,
    ): InternalCompilationResult {
        val root = createTempDirectory(prefix = "kinetica-compile-")
        val sourceRoot = root.resolve("src").createDirectories()
        val outputDir = root.resolve("out").createDirectories()
        sources.forEach { (relativePath, text) ->
            val file = sourceRoot.resolve(relativePath)
            file.parent?.createDirectories()
            file.writeText(text.trimIndent())
        }

        val collector = RecordingMessageCollector()
        val arguments = K2JVMCompilerArguments().apply {
            freeArgs = listOf(sourceRoot.toString())
            destination = outputDir.toString()
            // The classpath is passed explicitly; without these the CLI probes a
            // non-existent kotlin-home and logs STRONG_WARNINGs per compilation.
            noStdlib = true
            noReflect = true
            classpath = compilerClasspath().joinToString(File.pathSeparator) { it.absolutePath }
            this.moduleName = moduleName
            pluginClasspaths = arrayOf(pluginJar.absolutePath)
            pluginOptions = arrayOf(
                "plugin:${KineticaCompilerContract.pluginId}:${KineticaCompilerContract.optionModuleId}=$moduleName",
                "plugin:${KineticaCompilerContract.pluginId}:${KineticaCompilerContract.optionTransforms}=$transforms",
                "plugin:${KineticaCompilerContract.pluginId}:${KineticaCompilerContract.optionChecks}=$checks",
            )
        }
        val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)
        return InternalCompilationResult(
            success = exitCode == ExitCode.OK,
            outputDir = outputDir.toFile(),
            messages = collector.messages,
        )
    }

    private fun compilerClasspath(): List<File> =
        System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
            .map(::File)
            .filter { it.exists() }
            .distinctBy { it.absoluteFile.normalize() }
            .toList()

    private companion object {
        /**
         * The plugin jar for -Xplugin, built once per test JVM from the classpath entry
         * holding the plugin classes, with the META-INF/services registrations included
         * (they may live in a separate resources classpath entry).
         */
        private val pluginJar: File by lazy {
            val location = File(KineticaCompilerRegistrar::class.java.protectionDomain.codeSource.location.toURI())
            val serviceNames = listOf(
                "org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar",
                "org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor",
            )
            if (location.isFile) {
                return@lazy location
            }
            val jarFile = File.createTempFile("kinetica-compiler-plugin", ".jar")
            jarFile.deleteOnExit()
            JarOutputStream(jarFile.outputStream()).use { jar ->
                location.walkTopDown().filter { it.isFile }.forEach { file ->
                    jar.putNextEntry(JarEntry(file.relativeTo(location).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(jar) }
                    jar.closeEntry()
                }
                val classLoader = KineticaCompilerRegistrar::class.java.classLoader
                serviceNames.forEach { serviceName ->
                    if (!location.resolve("META-INF/services/$serviceName").isFile) {
                        val resource = classLoader.getResource("META-INF/services/$serviceName")
                            ?: error("Missing service registration for $serviceName on the test classpath")
                        jar.putNextEntry(JarEntry("META-INF/services/$serviceName"))
                        resource.openStream().use { it.copyTo(jar) }
                        jar.closeEntry()
                    }
                }
            }
            jarFile
        }
    }
}

internal class CompiledKineticaModule(
    val outputDir: File,
    val messages: List<RecordedCompilerMessage>,
    private val classLoader: URLClassLoader,
) : AutoCloseable {
    fun assertTransformFired(needle: String) {
        assertTrue(
            messages.any { message -> needle in message.message },
            "Expected compiler transform message containing '$needle'. Messages:\n${messages.joinToString("\n")}",
        )
    }

    fun assertTransformDidNotFire(needle: String) {
        assertFalse(
            messages.any { message -> needle in message.message },
            "Unexpected compiler transform message containing '$needle'. Messages:\n${messages.joinToString("\n")}",
        )
    }

    fun loadClass(fqName: String): Class<*> =
        classLoader.loadClass(fqName)

    override fun close() {
        classLoader.close()
    }
}

internal data class RecordedCompilerMessage(
    val severity: CompilerMessageSeverity,
    val message: String,
    val location: CompilerMessageSourceLocation?,
)

private class RecordingMessageCollector : MessageCollector {
    val messages: MutableList<RecordedCompilerMessage> = mutableListOf()

    override fun clear() {
        messages.clear()
    }

    override fun hasErrors(): Boolean =
        messages.any { message -> message.severity.isError }

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        messages += RecordedCompilerMessage(severity, message, location)
    }
}

private fun restoreSystemProperty(key: String, previous: String?) {
    if (previous == null) {
        System.clearProperty(key)
    } else {
        System.setProperty(key, previous)
    }
}
