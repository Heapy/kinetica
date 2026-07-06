package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import kotlin.io.path.createTempDirectory

@OptIn(CompilerConfiguration.Internals::class, K1Deprecation::class)
internal fun <T> withKotlinCoreEnvironment(
    configuration: CompilerConfiguration = CompilerConfiguration(),
    block: (KotlinCoreEnvironment) -> T,
): T {
    val disposable = Disposer.newDisposable()
    val ideaHome = createTempDirectory(prefix = "kinetica-compiler-test")
    val previousIdeaHome = System.setProperty("idea.home.path", ideaHome.toString())
    val previousIdeaConfig = System.setProperty("idea.config.path", ideaHome.resolve("config").toString())
    val previousIdeaSystem = System.setProperty("idea.system.path", ideaHome.resolve("system").toString())
    return try {
        val environment = KotlinCoreEnvironment.createForTests(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
        block(environment)
    } finally {
        restoreSystemProperty("idea.system.path", previousIdeaSystem)
        restoreSystemProperty("idea.config.path", previousIdeaConfig)
        restoreSystemProperty("idea.home.path", previousIdeaHome)
        ApplicationManager.getApplication()?.runWriteAction {
            Disposer.dispose(disposable)
        } ?: Disposer.dispose(disposable)
    }
}

internal class RecordingMessageCollector : MessageCollector {
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

internal data class RecordedCompilerMessage(
    val severity: CompilerMessageSeverity,
    val message: String,
    val location: CompilerMessageSourceLocation?,
)

internal fun restoreSystemProperty(key: String, previous: String?) {
    if (previous == null) {
        System.clearProperty(key)
    } else {
        System.setProperty(key, previous)
    }
}
