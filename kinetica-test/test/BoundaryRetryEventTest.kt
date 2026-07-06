// Own package as a workaround: the Kinetica IR plugin numbers its file-level kineticaFrame$N
// table fields per file, so two files with @UiComponent functions in the same package emit
// colliding top-level field signatures and fail Kotlin/JS klib linking.
package io.heapy.kinetica.testing.boundaryretry

import io.heapy.kinetica.CacheScope
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.ResourceKey
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.action
import io.heapy.kinetica.button
import io.heapy.kinetica.errorBoundary
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.loadingBoundary
import io.heapy.kinetica.resource
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import io.heapy.kinetica.textInput
import io.heapy.kinetica.watch
import io.heapy.kinetica.testing.KineticaTest
import io.heapy.kinetica.testing.hasTestTag
import io.heapy.kinetica.testing.htmlSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The resource → action → invalidate loop with boundaries, driven the way the docs live demo
 * drives it. Regression: the fallback's unit `event { retry }` used to collide with the
 * content's typed `event<String>` slot at the same cursor position, leaving the retry button
 * dead (silent no-op on JS, ClassCastException on JVM).
 */
class BoundaryRetryEventTest {
    @Test
    fun errorBoundaryRetryRecoversAfterActionFailure() = runTest {
        stackKey = StackKey(hashCode())
        languageStack = mutableListOf("Kotlin")

        val root = KineticaTest.render {
            BoundaryRetryApp()
        }

        suspend fun settle() = withContext(Dispatchers.Default) {
            withTimeout(5_000) { root.awaitIdle() }
        }

        settle()
        assertTrue("STACK:Kotlin" in root.htmlSnapshot())

        // the success path: submit, action runs, invalidation refetches
        root.input(hasTestTag("input"), "rust")
        root.click(hasTestTag("add"))
        settle()
        assertTrue("STACK:Kotlin,rust" in root.htmlSnapshot())

        // the failure path: the action throws, the nearest errorBoundary captures it
        root.input(hasTestTag("input"), "java")
        root.click(hasTestTag("add"))
        settle()
        assertTrue("ERROR: NPE from backend" in root.htmlSnapshot())

        // retry clears the error, restores the content, and hands the failed input back
        root.click(hasTestTag("retry"))
        settle()
        val recovered = root.htmlSnapshot()
        assertTrue("STACK:Kotlin,rust" in recovered, "expected recovered stack, got:\n$recovered")
        assertFalse("ERROR" in recovered, "expected the error to be cleared, got:\n$recovered")
        assertTrue("java" in recovered, "expected the failed draft restored into the input, got:\n$recovered")

        // the loop keeps working after recovery
        root.input(hasTestTag("input"), "go")
        root.click(hasTestTag("add"))
        settle()
        assertTrue("STACK:Kotlin,rust,go" in root.htmlSnapshot())

        root.dispose()
    }
}

private data class StackKey(val id: Int) : ResourceKey
private data class Submission(val tick: Int, val language: String)

private var stackKey = StackKey(0)
private var languageStack = mutableListOf("Kotlin")

@UiComponent
private fun ComponentScope.BoundaryRetryApp() {
    val key = stackKey
    val stackResource = resource(key, scope = CacheScope.Component) { _ -> languageStack.toList() }
    val add = action(invalidates = { _: String -> listOf<ResourceKey>(key) }) { language: String ->
        if (language.equals("java", ignoreCase = true)) {
            throw IllegalStateException("NPE from backend")
        }
        languageStack += language
    }
    var draft by state { "" }
    var submission by state { Submission(0, "") }

    host("div") {
        errorBoundary(
            fallback = { error, _, retry ->
                text("ERROR: ${error.message}")
                button(
                    onClick = event {
                        draft = submission.language
                        submission = Submission(0, "")
                        stackResource.invalidate()
                        retry.retry()
                    },
                    semantics = Semantics(role = Role.Button, testTag = "retry"),
                ) {
                    text("Try again")
                }
            },
        ) {
            watch(source = { submission }) { current ->
                if (current.tick > 0) {
                    add(current.language)
                }
            }
            loadingBoundary(fallback = { text("Loading") }) {
                val languages = stackResource.read()
                text("STACK:" + languages.joinToString(","))
                textInput(
                    value = draft,
                    onInput = event<String> { draft = it },
                    semantics = Semantics(role = Role.TextInput, testTag = "input", focusable = true),
                )
                button(
                    onClick = event {
                        val language = draft.trim()
                        if (language.isNotEmpty()) {
                            draft = ""
                            submission = Submission(submission.tick + 1, language)
                        }
                    },
                    semantics = Semantics(role = Role.Button, testTag = "add"),
                ) {
                    text("Add")
                }
            }
        }
    }
}
