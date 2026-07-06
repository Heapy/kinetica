package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.KotlinSourceRoot
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinToJVMBytecodeCompiler
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

internal class KineticaCompilationHarness {
    @OptIn(CompilerConfiguration.Internals::class, K1Deprecation::class)
    fun compile(
        sources: Map<String, String>,
        moduleName: String = "test",
        transforms: String = "on",
    ): CompiledKineticaModule {
        val root = createTempDirectory(prefix = "kinetica-compile-")
        val sourceRoot = root.resolve("src").createDirectories()
        val outputDir = root.resolve("out").createDirectories()
        sources.forEach { (relativePath, text) ->
            val file = sourceRoot.resolve(relativePath)
            file.parent?.createDirectories()
            file.writeText(text.trimIndent())
        }

        val disposable = Disposer.newDisposable()
        val ideaHome = root.resolve("idea").createDirectories()
        val previousIdeaHome = System.setProperty("idea.home.path", ideaHome.toString())
        val previousIdeaConfig = System.setProperty("idea.config.path", ideaHome.resolve("config").toString())
        val previousIdeaSystem = System.setProperty("idea.system.path", ideaHome.resolve("system").toString())
        val collector = RecordingMessageCollector()
        try {
            val configuration = CompilerConfiguration.create(messageCollector = collector).apply {
                put(CommonConfigurationKeys.MODULE_NAME, moduleName)
                put(JVMConfigurationKeys.OUTPUT_DIRECTORY, outputDir.toFile())
                put(KineticaConfigurationKeys.moduleId, moduleName)
                put(KineticaConfigurationKeys.transforms, transforms)
                add(CLIConfigurationKeys.CONTENT_ROOTS, KotlinSourceRoot(sourceRoot.toString(), isCommon = false, hmppModuleName = null))
                compilerClasspath().forEach { file ->
                    add(CLIConfigurationKeys.CONTENT_ROOTS, JvmClasspathRoot(file))
                }
                add(CompilerPluginRegistrar.COMPILER_PLUGIN_REGISTRARS, KineticaCompilerRegistrar())
            }
            val environment = KotlinCoreEnvironment.createForProduction(
                disposable,
                configuration,
                EnvironmentConfigFiles.JVM_CONFIG_FILES,
            )
            val success = KotlinToJVMBytecodeCompiler.compileBunchOfSources(environment)
            if (!success || collector.hasErrors()) {
                fail(
                    buildString {
                        appendLine("Compilation failed.")
                        collector.messages.forEach { message ->
                            appendLine("${message.severity}: ${message.message}")
                        }
                    },
                )
            }
        } finally {
            restoreSystemProperty("idea.system.path", previousIdeaSystem)
            restoreSystemProperty("idea.config.path", previousIdeaConfig)
            restoreSystemProperty("idea.home.path", previousIdeaHome)
            ApplicationManager.getApplication()?.runWriteAction {
                Disposer.dispose(disposable)
            } ?: Disposer.dispose(disposable)
        }

        return CompiledKineticaModule(
            outputDir = outputDir.toFile(),
            messages = collector.messages,
            classLoader = URLClassLoader(
                arrayOf(outputDir.toUri().toURL()),
                Thread.currentThread().contextClassLoader,
            ),
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
