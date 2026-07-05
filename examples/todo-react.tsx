import { useState, useMemo, useCallback, useEffect } from 'react'

type Todo = { id: string; title: string; done: boolean }
type Filter = 'all' | 'active' | 'done'

declare const storage: {
  load(): Promise<Todo[]>
  save(todos: Todo[]): Promise<void>
}

export function TodoApp() {
  const [todos, setTodos] = useState<Todo[]>([])
  const [filter, setFilter] = useState<Filter>('all')
  const [draft, setDraft] = useState('')
  const [loaded, setLoaded] = useState(false)

  // Начальная загрузка: cancelled-флаг против гонки при unmount,
  // loaded-флаг против записи пустого списка поверх сохранённого.
  useEffect(() => {
    let cancelled = false
    storage.load().then(saved => {
      if (!cancelled) {
        setTodos(saved)
        setLoaded(true)
      }
    })
    return () => { cancelled = true }
  }, [])

  // Debounce-персистентность на setTimeout/clearTimeout.
  useEffect(() => {
    if (!loaded) return
    const t = setTimeout(() => storage.save(todos), 300)
    return () => clearTimeout(t)
  }, [todos, loaded])

  const visible = useMemo(
    () => todos.filter(t => filter === 'all' || (filter === 'done') === t.done),
    [todos, filter],
  )
  const remaining = useMemo(() => todos.filter(t => !t.done).length, [todos])

  const addTodo = useCallback(() => {
    const title = draft.trim()
    if (!title) return
    setTodos(ts => [...ts, { id: crypto.randomUUID(), title, done: false }])
    setDraft('')
  }, [draft])

  const toggle = useCallback((id: string) => {
    setTodos(ts => ts.map(t => (t.id === id ? { ...t, done: !t.done } : t)))
  }, [])

  const remove = useCallback((id: string) => {
    setTodos(ts => ts.filter(t => t.id !== id))
  }, [])

  if (!loaded) return <p>Loading…</p>

  return (
    <section>
      <form onSubmit={e => { e.preventDefault(); addTodo() }}>
        <input
          value={draft}
          onChange={e => setDraft(e.target.value)}
          placeholder="What needs doing?"
        />
        <button type="submit">Add</button>
      </form>

      <ul>
        {visible.map(todo => (
          <li key={todo.id}>
            <label>
              <input type="checkbox" checked={todo.done} onChange={() => toggle(todo.id)} />
              <span style={{ textDecoration: todo.done ? 'line-through' : 'none' }}>
                {todo.title}
              </span>
            </label>
            <button onClick={() => remove(todo.id)}>✕</button>
          </li>
        ))}
      </ul>

      <footer>
        <span>{remaining} left</span>
        {(['all', 'active', 'done'] as Filter[]).map(f => (
          <button key={f} disabled={f === filter} onClick={() => setFilter(f)}>
            {f}
          </button>
        ))}
      </footer>
    </section>
  )
}
