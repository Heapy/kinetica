package app.todo

import io.heapy.kinetica.ClientRef
import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.Node
import io.heapy.kinetica.TemplateNode
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.materialize
import io.heapy.kinetica.testing.KineticaTest
import io.heapy.kinetica.testing.assertHtmlSnapshot
import io.heapy.kinetica.testing.hasTestTag
import io.heapy.kinetica.testing.hasText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TodoAppTest {
    @Test
    fun todoAppAddsFiltersTogglesAndRemovesItems() {
        val root = KineticaTest.render {
            TodoApp()
        }

        root.click(hasTestTag("add"))
        assertEquals("0 left", (root.node(hasText("0 left")).node as TextNode).value)

        root.input(hasTestTag("new-todo"), "Ship tests")
        root.click(hasTestTag("add"))
        root.input(hasTestTag("new-todo"), "Write docs")
        root.click(hasTestTag("add"))
        assertEquals("Ship tests", (root.node(hasText("Ship tests")).node as TextNode).value)
        assertEquals("Write docs", (root.node(hasText("Write docs")).node as TextNode).value)
        assertEquals("2 left", (root.node(hasText("2 left")).node as TextNode).value)

        root.click(hasTestTag("filter-active"))
        assertEquals("Ship tests", (root.node(hasText("Ship tests")).node as TextNode).value)
        assertEquals("Write docs", (root.node(hasText("Write docs")).node as TextNode).value)
        root.click(hasTestTag("toggle-todo-1"))
        assertEquals("1 left", (root.node(hasText("1 left")).node as TextNode).value)
        root.click(hasTestTag("filter-active"))
        assertEquals("Write docs", (root.node(hasText("Write docs")).node as TextNode).value)
        assertFalse("Ship tests" in root.tree().textValues())
        root.click(hasTestTag("filter-done"))
        assertEquals("Ship tests", (root.node(hasText("Ship tests")).node as TextNode).value)

        root.click(hasTestTag("remove-todo-1"))
        root.click(hasTestTag("filter-all"))
        assertEquals("Write docs", (root.node(hasText("Write docs")).node as TextNode).value)
        assertEquals("1 left", (root.node(hasText("1 left")).node as TextNode).value)
        root.click(hasTestTag("remove-todo-2"))
        assertEquals("0 left", (root.node(hasText("0 left")).node as TextNode).value)
        root.assertHtmlSnapshot(
            """
            <column><row direction="Ltr"><textInput value="" placeholder="What needs doing?"></textInput><button enabled="true">Add</button></row><row direction="Ltr">0 left<button enabled="false">All</button><button enabled="true">Active</button><button enabled="true">Done</button></row></column>
            """,
        )
    }

    @Test
    fun addingAfterRemovingDoesNotReuseIds() {
        val root = KineticaTest.render {
            TodoApp()
        }

        fun add(title: String) {
            root.input(hasTestTag("new-todo"), title)
            root.click(hasTestTag("add"))
        }

        add("A")
        add("B")
        add("C")
        root.click(hasTestTag("remove-todo-1"))

        // Adding after a removal must mint a fresh id: a size-derived id would collide with an
        // existing todo, producing a duplicate each() key that crashes render under the debug runtime.
        add("D")

        assertEquals("B", (root.node(hasText("B")).node as TextNode).value)
        assertEquals("C", (root.node(hasText("C")).node as TextNode).value)
        assertEquals("D", (root.node(hasText("D")).node as TextNode).value)
        assertFalse("A" in root.tree().textValues())
    }
}

private fun Node.textValues(): List<String> = when (this) {
    is HostNode -> children.flatMap { it.textValues() }
    is FragmentNode -> children.flatMap { it.textValues() }
    is TextNode -> listOf(value)
    is ClientRef -> emptyList()
    is TemplateNode -> materialize().textValues()
}
