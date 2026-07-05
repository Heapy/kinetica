package app.todo

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.button
import io.heapy.kinetica.checkbox
import io.heapy.kinetica.column
import io.heapy.kinetica.derived
import io.heapy.kinetica.each
import io.heapy.kinetica.event
import io.heapy.kinetica.row
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import io.heapy.kinetica.textInput
import io.heapy.kinetica.testing.KineticaTest

data class Todo(val id: String, val title: String, val done: Boolean)

enum class Filter { All, Active, Done }

fun ComponentScope.TodoApp() {
    var todos by state(key = "todos") { emptyList<Todo>() }
    var filter by state(key = "filter") { Filter.All }
    var draft by state(key = "draft", persistent = true) { "" }
    var nextId by state(key = "nextId") { 1 }

    val visible by derived {
        when (filter) {
            Filter.All -> todos
            Filter.Active -> todos.filterNot { it.done }
            Filter.Done -> todos.filter { it.done }
        }
    }
    val remaining by derived { todos.count { !it.done } }

    val add = event {
        val title = draft.trim()
        if (title.isNotEmpty()) {
            todos = todos + Todo(id = "todo-$nextId", title = title, done = false)
            nextId += 1
            draft = ""
        }
    }
    val toggle = event<String> { id ->
        todos = todos.map { if (it.id == id) it.copy(done = !it.done) else it }
    }
    val remove = event<String> { id ->
        todos = todos.filterNot { it.id == id }
    }

    column(semantics = Semantics(testTag = "todo-app")) {
        row {
            textInput(
                value = draft,
                onInput = event<String> { draft = it },
                onSubmit = add,
                placeholder = "What needs doing?",
                semantics = Semantics(role = Role.TextInput, testTag = "new-todo", focusable = true),
            )
            button(onClick = add, semantics = Semantics(role = Role.Button, testTag = "add", focusable = true)) {
                text("Add")
            }
        }

        each(visible, key = { it.id }) { todo ->
            row(semantics = Semantics(testTag = "todo-${todo.id}")) {
                checkbox(
                    checked = todo.done,
                    onToggle = event { toggle(todo.id) },
                    semantics = Semantics(role = Role.Checkbox, testTag = "toggle-${todo.id}", focusable = true),
                )
                text(todo.title, strikethrough = todo.done)
                button(
                    onClick = event { remove(todo.id) },
                    semantics = Semantics(role = Role.Button, testTag = "remove-${todo.id}", focusable = true),
                ) {
                    text("Remove")
                }
            }
        }

        row {
            text("$remaining left")
            each(Filter.entries, key = { it }) { entry ->
                button(
                    enabled = entry != filter,
                    onClick = event { filter = entry },
                    semantics = Semantics(
                        role = Role.Button,
                        testTag = "filter-${entry.name.lowercase()}",
                        focusable = true,
                    ),
                ) {
                    text(entry.name)
                }
            }
        }
    }
}

fun main() {
    val root = KineticaTest.render {
        TodoApp()
    }
    println(root.tree())
}
