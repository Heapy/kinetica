package app.todo

import kotlinx.coroutines.delay
import rivulet.*

// Иллюстративный код по спецификации Rivulet v2 (deep-research-report.md).
// Фреймворк — исследовательская спека, поэтому host-узлы (column, row,
// textInput, checkbox) — гипотетический DSL renderer-модуля.

data class Todo(val id: String, val title: String, val done: Boolean)

enum class Filter { All, Active, Done }

interface TodoStorage {
    suspend fun load(): List<Todo>
    suspend fun save(todos: List<Todo>)
}

data object TodosKey : ResourceKey

val Storage = context<TodoStorage>(default = InMemoryStorage, name = "TodoStorage")

@UiComponent
fun TodoRoot(storage: TodoStorage) {
    provide(Storage, storage) {
        errorBoundary(
            fallback = { error, _, retry ->
                column {
                    text("Не удалось загрузить задачи: ${error.message}")
                    button(onClick = event { retry.retry() }) { text("Повторить") }
                }
            }
        ) {
            loadingBoundary(fallback = { text("Loading…") }) {
                TodoApp()
            }
        }
    }
}

@UiComponent
suspend fun TodoApp() {
    val storage = read(Storage)

    // Начальная загрузка — часть render contract: пока грузится,
    // loadingBoundary показывает fallback. Ни loaded-, ни cancelled-флагов.
    val saved = resource(TodosKey) { storage.load() }.await()

    var todos by state { saved }
    var filter by state { Filter.All }
    var draft by state(persistent = true) { "" }

    val visible by derived {
        when (filter) {
            Filter.All -> todos
            Filter.Active -> todos.filterNot { it.done }
            Filter.Done -> todos.filter { it.done }
        }
    }
    val remaining by derived { todos.count { !it.done } }

    // Debounce-персистентность: каждое изменение todos отменяет
    // недоигранный delay и перезапускает watch с новым снимком.
    watch({ todos }) { snapshot ->
        delay(300)
        storage.save(snapshot)
    }

    val add = event {
        val title = draft.trim()
        if (title.isNotEmpty()) {
            todos = todos + Todo(id = newId(), title = title, done = false)
            draft = ""
        }
    }
    val toggle = event<String> { id ->
        todos = todos.map { if (it.id == id) it.copy(done = !it.done) else it }
    }
    val remove = event<String> { id ->
        todos = todos.filterNot { it.id == id }
    }

    column {
        row {
            textInput(
                value = draft,
                onInput = event<String> { draft = it },
                onSubmit = add,
                placeholder = "What needs doing?",
                semantics = Semantics(testTag = "new-todo")
            )
            button(onClick = add) { text("Add") }
        }

        each(visible, key = { it.id }) { todo ->
            row {
                checkbox(checked = todo.done, onToggle = event { toggle(todo.id) })
                text(todo.title, strikethrough = todo.done)
                button(onClick = event { remove(todo.id) }) { text("✕") }
            }
        }

        row {
            text("$remaining left")
            each(Filter.entries, key = { it }) { f ->
                button(enabled = f != filter, onClick = event { filter = f }) {
                    text(f.name)
                }
            }
        }
    }
}
