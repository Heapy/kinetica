package io.heapy.kinetica

/**
 * Array-backed immutable props for host nodes. Building and comparing small prop sets
 * dominates Node construction on list-heavy screens; for the 1–4 entry case a flat
 * `[name, value, name, value]` array beats a hash map on allocation count, and lookups
 * are a short scan with an identity fast path (prop names are interned literals). Full
 * [Map] semantics are preserved — equality, hash code, iteration order, serialization —
 * so nodes built with [propsOf] compare equal to nodes built with `mapOf`.
 */
public fun propsOf(): Map<String, String> = emptyMap()

public fun propsOf(name: String, value: String): Map<String, String> =
    PropMap(arrayOf(name, value))

public fun propsOf(
    name1: String, value1: String,
    name2: String, value2: String,
): Map<String, String> =
    fromPairs(arrayOf(name1, value1, name2, value2))

public fun propsOf(
    name1: String, value1: String,
    name2: String, value2: String,
    name3: String, value3: String,
): Map<String, String> =
    fromPairs(arrayOf(name1, value1, name2, value2, name3, value3))

public fun propsOf(
    name1: String, value1: String,
    name2: String, value2: String,
    name3: String, value3: String,
    name4: String, value4: String,
): Map<String, String> =
    fromPairs(arrayOf(name1, value1, name2, value2, name3, value3, name4, value4))

public fun propsOf(vararg pairs: Pair<String, String>): Map<String, String> =
    if (pairs.isEmpty()) {
        emptyMap()
    } else {
        fromPairs(
            Array(pairs.size * 2) { index ->
                val pair = pairs[index / 2]
                if (index % 2 == 0) pair.first else pair.second
            },
        )
    }

/** Wraps the used prefix of a DSL-built buffer without a second validation pass. */
internal fun propsFromBuffer(buffer: Array<String?>, count: Int): Map<String, String> {
    if (count == 0) return emptyMap()
    @Suppress("UNCHECKED_CAST")
    val exact = (if (count == buffer.size) buffer else buffer.copyOf(count)) as Array<String>
    return PropMap(exact)
}

/** Duplicate names fall back to a hash map so `mapOf`'s last-wins semantics hold. */
private fun fromPairs(pairs: Array<String>): Map<String, String> {
    var index = 2
    while (index < pairs.size) {
        var earlier = 0
        while (earlier < index) {
            if (pairs[earlier] == pairs[index]) {
                val deduplicated = LinkedHashMap<String, String>(pairs.size / 2)
                var pair = 0
                while (pair < pairs.size) {
                    deduplicated[pairs[pair]] = pairs[pair + 1]
                    pair += 2
                }
                return deduplicated
            }
            earlier += 2
        }
        index += 2
    }
    return PropMap(pairs)
}

internal class PropMap(
    private val pairs: Array<String>,
) : AbstractMap<String, String>() {
    private var entriesView: Set<Map.Entry<String, String>>? = null

    override val size: Int
        get() = pairs.size / 2

    override fun isEmpty(): Boolean = pairs.isEmpty()

    override fun containsKey(key: String): Boolean = indexOfKey(key) >= 0

    override fun get(key: String): String? {
        val index = indexOfKey(key)
        return if (index >= 0) pairs[index + 1] else null
    }

    override val entries: Set<Map.Entry<String, String>>
        get() = entriesView ?: PropEntries(pairs).also { entriesView = it }

    private fun indexOfKey(key: String): Int {
        var index = 0
        while (index < pairs.size) {
            val candidate = pairs[index]
            if (candidate === key || candidate == key) {
                return index
            }
            index += 2
        }
        return -1
    }

    private class PropEntries(
        private val pairs: Array<String>,
    ) : AbstractSet<Map.Entry<String, String>>() {
        override val size: Int
            get() = pairs.size / 2

        override fun contains(element: Map.Entry<String, String>): Boolean {
            var index = 0
            while (index < pairs.size) {
                if (pairs[index] == element.key) {
                    return pairs[index + 1] == element.value
                }
                index += 2
            }
            return false
        }

        override fun iterator(): Iterator<Map.Entry<String, String>> =
            object : Iterator<Map.Entry<String, String>> {
                private var index = 0

                override fun hasNext(): Boolean = index < pairs.size

                override fun next(): Map.Entry<String, String> {
                    if (index >= pairs.size) throw NoSuchElementException()
                    val entry = PropEntry(pairs[index], pairs[index + 1])
                    index += 2
                    return entry
                }
            }
    }

    private class PropEntry(
        override val key: String,
        override val value: String,
    ) : Map.Entry<String, String> {
        override fun equals(other: Any?): Boolean =
            other is Map.Entry<*, *> && other.key == key && other.value == value

        override fun hashCode(): Int = key.hashCode() xor value.hashCode()

        override fun toString(): String = "$key=$value"
    }
}
