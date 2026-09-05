package io.heapy.kinetica.buildplugin

import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

@TaskAction
fun installCompilerToMavenLocal(
    @Input compilerJar: CompilationArtifact,
    group: String,
    artifact: String,
    version: String,
    @Output stampDir: Path,
) {
    val target = group.split('.')
        .fold(Path.of(System.getProperty("user.home"), ".m2", "repository")) { dir, part -> dir.resolve(part) }
        .resolve(artifact)
        .resolve(version)
    val pom = target.resolve("$artifact-$version.pom")
    check(pom.exists()) {
        "$pom is missing. Run `./kotlin publish mavenLocal -m $artifact` once to create the POM and " +
            "Gradle module metadata; this task only refreshes the JAR."
    }

    val bytes = compilerJar.artifact.readBytes()
    val stamp = sha256(bytes)
    val installed = target.resolve("$artifact-$version.jar")

    // This task is instantiated per consumer module and those instances run concurrently, all
    // writing the same coordinate. Stage into a unique sibling and ATOMIC_MOVE, so a reader
    // resolving the artifact never observes a partially written JAR.
    if (!installed.exists() || sha256(installed.readBytes()) != stamp) {
        val staged = target.resolve("$artifact-$version.jar.${UUID.randomUUID()}.tmp")
        try {
            staged.writeBytes(bytes)
            Files.move(staged, installed, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staged.deleteIfExists()
        }
        println("Installed ${compilerJar.artifact} into $target (sha256=$stamp)")
    }

    // Comment-only on purpose: a Kotlin file without declarations produces no class file, so the
    // stamp never reaches a published artifact, while its changing content still makes the
    // consumer's compile task re-run against the JAR that was just installed.
    stampDir.createDirectories()
    stampDir.resolve("kinetica-compiler-stamp.kt")
        .writeText("// kinetica-compiler sha256=$stamp\n")
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
