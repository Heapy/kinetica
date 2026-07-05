package app.browser.todo

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.browser.mountKineticaApp
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

data class BrowserTodo(val id: String, val title: String, val done: Boolean)

enum class BrowserFilter { All, Active, Done }

fun ComponentScope.BrowserTodoApp() {
    var todos by state(key = "todos") {
        listOf(
            BrowserTodo(id = "todo-1", title = "Render in the browser", done = true),
            BrowserTodo(id = "todo-2", title = "Write UI tests", done = false),
        )
    }
    var filter by state(key = "filter") { BrowserFilter.All }
    var draft by state(key = "draft", persistent = true) { "" }
    var nextTodoId by state(key = "nextTodoId") { 3 }

    val visible by derived {
        when (filter) {
            BrowserFilter.All -> todos
            BrowserFilter.Active -> todos.filterNot { it.done }
            BrowserFilter.Done -> todos.filter { it.done }
        }
    }
    val remaining by derived { todos.count { !it.done } }
    val add = event {
        val title = draft.trim()
        if (title.isNotEmpty()) {
            todos = todos + BrowserTodo(
                id = "todo-$nextTodoId",
                title = title,
                done = false,
            )
            nextTodoId += 1
            draft = ""
        }
    }

    column(semantics = Semantics(testTag = "todo-app")) {
        text("Kinetica Todo")
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

        column(semantics = Semantics(role = Role.List, testTag = "todo-list")) {
            each(visible, key = { it.id }) { todo ->
                row(semantics = Semantics(role = Role.ListItem, testTag = todo.id)) {
                    checkbox(
                        checked = todo.done,
                        onToggle = event {
                            todos = todos.map { item ->
                                if (item.id == todo.id) item.copy(done = !item.done) else item
                            }
                        },
                        semantics = Semantics(role = Role.Checkbox, testTag = "toggle-${todo.id}", focusable = true),
                    )
                    text(todo.title, strikethrough = todo.done)
                    button(
                        onClick = event { todos = todos.filterNot { item -> item.id == todo.id } },
                        semantics = Semantics(role = Role.Button, testTag = "remove-${todo.id}", focusable = true),
                    ) {
                        text("Remove")
                    }
                }
            }
        }

        row {
            text("$remaining left")
            each(BrowserFilter.entries, key = { it }) { entry ->
                button(
                    enabled = entry != filter,
                    onClick = event { filter = entry },
                    semantics = Semantics(role = Role.Button, testTag = "filter-${entry.name}", focusable = true),
                ) {
                    text(entry.name)
                }
            }
        }
    }
}

fun main() {
    mountKineticaApp("#app") {
        BrowserTodoApp()
    }
}
