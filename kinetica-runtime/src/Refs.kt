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

public fun <T : Any> ComponentScope.hostRef(): Ref<T> {
    val key = nextSlotKey(null, SlotKind.HostRef)
    registerSlot(SlotMetadata(key, slotId = null, persistent = false, transient = true))
    return checkedSlot(key, Ref::class) { Ref<T>() }
}

public fun <T : Any> ComponentScope.imperativeHandle(factory: () -> T): Ref<T> {
    val key = nextSlotKey(null, SlotKind.Handle)
    registerSlot(SlotMetadata(key, slotId = null, persistent = false, transient = true))
    val ref = checkedSlot(key, Ref::class) { Ref<T>() }
    ref.set(factory())
    return ref
}
