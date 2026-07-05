package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration

public object KineticaDiagnosticReporter {
    public fun report(
        configuration: CompilerConfiguration,
        diagnostics: List<CompilerDiagnostic>,
    ) {
        val collector = configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY) ?: return
        diagnostics.forEach { diagnostic ->
            collector.report(
                diagnostic.severity.toCompilerSeverity(),
                diagnostic.toCompilerMessage(),
                diagnostic.location.toCompilerLocation(),
            )
        }
    }

    private fun CompilerDiagnostic.toCompilerMessage(): String =
        "[$code] $message"

    private fun DiagnosticSeverity.toCompilerSeverity(): CompilerMessageSeverity =
        when (this) {
            DiagnosticSeverity.Error -> CompilerMessageSeverity.ERROR
            DiagnosticSeverity.Warning -> CompilerMessageSeverity.WARNING
        }

    private fun String?.toCompilerLocation(): CompilerMessageSourceLocation? {
        val rawLocation = this?.takeIf { it.isNotBlank() } ?: return null
        val parsed = rawLocation.parseLocation()
            ?: return CompilerMessageLocation.create(rawLocation)

        return CompilerMessageLocation.create(
            parsed.path,
            parsed.line,
            parsed.column,
            "",
        )
    }

    private fun String.parseLocation(): ParsedLocation? {
        val columnSeparator = lastIndexOf(':')
        if (columnSeparator < 0) {
            return null
        }
        val lineSeparator = lastIndexOf(':', startIndex = columnSeparator - 1)
        if (lineSeparator < 0) {
            return null
        }
        val path = substring(0, lineSeparator)
        val line = substring(lineSeparator + 1, columnSeparator).toIntOrNull()
        val column = substring(columnSeparator + 1).toIntOrNull()

        return if (path.isNotBlank() && line != null && column != null && line > 0 && column > 0) {
            ParsedLocation(path, line, column)
        } else {
            null
        }
    }

    private data class ParsedLocation(
        val path: String,
        val line: Int,
        val column: Int,
    )
}
