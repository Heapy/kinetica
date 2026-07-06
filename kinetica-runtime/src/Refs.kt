package io.heapy.kinetica

public class Ref<T : Any> internal constructor(initial: T? = null) {
    private var currentValue: T? = initial

    public val current: T?
        get() = currentValue

    internal fun set(value: T?) {
        currentValue = value
    }

    internal fun clear() {
        currentValue = null
    }
}

public fun <T : Any> ComponentScope.hostRef(ordinal: Int = -1): Ref<T> {
    if (ordinal < 0) throw MissingKineticaPluginException("hostRef")
    return frameSlot(ordinal, transient = true) { Ref<T>() }
}

public fun <T : Any> ComponentScope.imperativeHandle(ordinal: Int = -1, factory: () -> T): Ref<T> {
    if (ordinal < 0) throw MissingKineticaPluginException("imperativeHandle")
    val ref = frameSlot(ordinal, transient = true) { Ref<T>() }
    ref.set(factory())
    return ref
}
