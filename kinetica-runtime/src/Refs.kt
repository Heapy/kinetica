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
    if (ordinal >= 0) {
        return frameSlot(ordinal, transient = true) { Ref<T>() }
    }
    val key = nextSlotKey(null, SlotKind.HostRef)
    registerSlot(SlotMetadata(key, slotId = null, persistent = false, transient = true))
    return checkedSlot(key, Ref::class) { Ref<T>() }
}

public fun <T : Any> ComponentScope.imperativeHandle(ordinal: Int = -1, factory: () -> T): Ref<T> {
    val ref = if (ordinal >= 0) {
        frameSlot(ordinal, transient = true) { Ref<T>() }
    } else {
        val key = nextSlotKey(null, SlotKind.Handle)
        registerSlot(SlotMetadata(key, slotId = null, persistent = false, transient = true))
        checkedSlot(key, Ref::class) { Ref<T>() }
    }
    ref.set(factory())
    return ref
}
