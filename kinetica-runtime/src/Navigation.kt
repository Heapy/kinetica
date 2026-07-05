package io.heapy.kinetica

public interface Route

public interface RouteCodec<R : Route> {
    public fun encode(route: R): String
    public fun decode(value: String): R
}

public class BackStack<R : Route>(
    initial: R,
    private val policy: EqualityPolicy<List<R>> = EqualityPolicy.structural(),
) : MutableCell<List<R>> {
    private val cell = MutableCellImpl(listOf(initial), policy)

    override var value: List<R>
        get() = cell.value
        set(value) {
            require(value.isNotEmpty()) { "BackStack cannot be empty." }
            cell.value = value
        }

    override fun update(transform: (List<R>) -> List<R>) {
        cell.update { current ->
            transform(current).also {
                require(it.isNotEmpty()) { "BackStack cannot be empty." }
            }
        }
    }

    public fun push(route: R) {
        update { it + route }
    }

    public fun pop(): Boolean {
        var popped = false
        cell.update { current ->
            if (current.size <= 1) {
                current
            } else {
                popped = true
                current.dropLast(1)
            }
        }
        return popped
    }

    public fun replaceAll(vararg routes: R) {
        val next = routes.toList()
        require(next.isNotEmpty()) { "BackStack cannot be empty." }
        update { next }
    }
}
