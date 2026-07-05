package io.heapy.kinetica

public fun interface EqualityPolicy<in T> {
    public fun equivalent(previous: T, next: T): Boolean

    public companion object {
        public fun <T> structural(): EqualityPolicy<T> = EqualityPolicy { previous, next -> previous == next }

        public fun <T> referential(): EqualityPolicy<T> = EqualityPolicy { previous, next -> previous === next }

        public fun <T> neverEqual(): EqualityPolicy<T> = EqualityPolicy { _, _ -> false }
    }
}

