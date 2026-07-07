package io.heapy.kinetica.testing

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.button
import io.heapy.kinetica.column
import io.heapy.kinetica.event
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import io.heapy.kinetica.textInput
import io.heapy.kinetica.testing.KineticaTest
import io.heapy.kinetica.testing.assertHtmlSnapshot
import io.heapy.kinetica.testing.assertTreeSnapshot
import io.heapy.kinetica.testing.hasLabel
import io.heapy.kinetica.testing.hasTestTag
import io.heapy.kinetica.testing.hasText
import io.heapy.kinetica.testing.toTreeSnapshot
import io.heapy.kinetica.testing.treeSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KineticaSnapshotTest {
    @Test
    fun treeSnapshotsAreDeterministicJson() {
        assertEquals(
            """
            {
                "type": "text",
                "value": "Hello",
                "strikethrough": false,
                "semantics": {
                    "role": "Text",
                    "label": null,
                    "stateDescription": null,
                    "focusable": false,
                    "traversalIndex": null,
                    "testTag": null,
                    "leaving": false
                }
            }
            """.trimIndent(),
            TextNode("Hello").toTreeSnapshot(),
        )
    }

    @Test
    fun htmlSnapshotsCanBeAssertedFromHeadlessRoots() {
        val root = KineticaTest.render {
            SaveButtonApp()
        }

        root.assertTreeSnapshot(root.treeSnapshot())
        root.assertHtmlSnapshot(
            """
            <button enabled="true">Save</button>
            """,
        )
    }

    @Test
    fun integrationTestsCanDriveFormInputAndSubmit() {
        val root = KineticaTest.render {
            CommitMessageFormApp()
        }

        root.input(hasTestTag("draft"), "Hello")
        root.node(hasTestTag("draft")).submit()

        assertEquals("Committed: Hello", (root.node(hasText("Committed: Hello")).node as TextNode).value)
        root.assertHtmlSnapshot(
            """
            <column><textInput value="" placeholder="Message"></textInput><button enabled="true">Commit</button>Committed: Hello</column>
            """,
        )
    }

    @Test
    fun suspendSnapshotsAndLabelMatchersUseTheSameAssertions() = runTest {
        val root = KineticaTest.renderSuspend {
            text(
                "Profile",
                semantics = Semantics(role = Role.Text, label = "Screen title"),
            )
        }

        assertEquals("Profile", (root.node(hasLabel("Screen title")).node as TextNode).value)
        root.assertTreeSnapshot(root.treeSnapshot())
        root.assertHtmlSnapshot("Profile")
        root.dispose()
    }

    @Test
    fun hasLabelFindsPlainTextDefaultSemantics() {
        val root = KineticaTest.render {
            text("Save")
        }

        assertEquals("Save", (root.node(hasLabel("Save")).node as TextNode).value)
        root.dispose()
    }

    @Test
    fun hasLabelDoesNotDeriveExplicitTextSemantics() {
        val root = KineticaTest.render {
            text("Save", semantics = Semantics(role = Role.Text))
        }

        assertFailsWith<IllegalStateException> {
            root.node(hasLabel("Save"))
        }
        root.dispose()
    }

    @Test
    fun snapshotMismatchesIncludeExpectedAndActualText() {
        val root = KineticaTest.render {
            text("Actual")
        }

        val failure = assertFailsWith<AssertionError> {
            root.assertHtmlSnapshot("Expected")
        }

        assertTrue("HTML snapshot mismatch." in failure.message.orEmpty())
        assertTrue("Expected" in failure.message.orEmpty())
        assertTrue("Actual" in failure.message.orEmpty())
    }
}

@UiComponent
private fun ComponentScope.SaveButtonApp() {
    button {
        text("Save")
    }
}

@UiComponent
private fun ComponentScope.CommitMessageFormApp() {
    var draft by state { "" }
    var committed by state { "none" }
    val commit = event {
        committed = draft.ifBlank { "empty" }
        draft = ""
    }

    column {
        textInput(
            value = draft,
            onInput = event<String> { draft = it },
            onSubmit = commit,
            placeholder = "Message",
            semantics = Semantics(role = Role.TextInput, testTag = "draft", focusable = true),
        )
        button(
            onClick = commit,
            semantics = Semantics(role = Role.Button, testTag = "commit", focusable = true),
        ) {
            text("Commit")
        }
        text("Committed: $committed")
    }
}
