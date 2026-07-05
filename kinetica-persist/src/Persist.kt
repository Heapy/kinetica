package io.heapy.kinetica.persist

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.EqualityPolicy
import io.heapy.kinetica.SlotId
import io.heapy.kinetica.state
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public interface SlotPersistenceBackend {
    public suspend fun <T> read(slotId: SlotId, serializer: KSerializer<T>): T?
    public suspend fun <T> write(slotId: SlotId, serializer: KSerializer<T>, value: T)
    public suspend fun remove(slotId: SlotId)
}

public class InMemorySlotPersistenceBackend : SlotPersistenceBackend {
    private val values = mutableMapOf<SlotId, Any?>()

    override suspend fun <T> read(slotId: SlotId, serializer: KSerializer<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return values[slotId] as T?
    }

    override suspend fun <T> write(slotId: SlotId, serializer: KSerializer<T>, value: T) {
        values[slotId] = value
    }

    override suspend fun remove(slotId: SlotId) {
        values.remove(slotId)
    }
}

public interface StringSlotPersistenceStore {
    public suspend fun read(key: String): String?
    public suspend fun write(key: String, value: String)
    public suspend fun remove(key: String)
}

public class InMemoryStringSlotPersistenceStore(
    initialValues: Map<String, String> = emptyMap(),
) : StringSlotPersistenceStore {
    private val values = initialValues.toMutableMap()

    public fun snapshot(): Map<String, String> = values.toMap()

    override suspend fun read(key: String): String? =
        values[key]

    override suspend fun write(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

public val PersistJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

public fun <T> ComponentScope.persistentState(
    slotId: SlotId,
    restoredValue: T?,
    policy: EqualityPolicy<T> = EqualityPolicy.structural(),
    initial: () -> T,
) = state(
    slotId = slotId,
    policy = policy,
    persistent = true,
    initial = { restoredValue ?: initial() },
)

public suspend fun <T> ComponentScope.restoreSlot(
    slotId: SlotId,
    backend: SlotPersistenceBackend,
    serializer: KSerializer<T>,
    policy: EqualityPolicy<T> = EqualityPolicy.structural(),
) {
    val value = backend.read(slotId, serializer) ?: return
    writeSlot(slotId, value, policy = policy, persistent = true)
}

public suspend fun <T> ComponentScope.saveSlot(
    slotId: SlotId,
    backend: SlotPersistenceBackend,
    serializer: KSerializer<T>,
) {
    if (!containsSlot(slotId)) {
        return
    }
    val value = readSlot<T>(slotId)
    if (value == null) {
        backend.remove(slotId)
        return
    }
    backend.write(slotId, serializer, value)
}

public data class EncodedSlotValue(public val value: String)

public open class StringSlotPersistenceBackend(
    private val store: StringSlotPersistenceStore,
    private val json: Json = PersistJson,
    private val namespace: String = "kinetica",
) : SlotPersistenceBackend {
    init {
        require(namespace.isNotBlank()) { "namespace must not be blank." }
    }

    override suspend fun <T> read(slotId: SlotId, serializer: KSerializer<T>): T? {
        val encoded = store.read(storageKey(slotId)) ?: return null
        return json.decodeFromString(serializer, encoded)
    }

    override suspend fun <T> write(slotId: SlotId, serializer: KSerializer<T>, value: T) {
        store.write(storageKey(slotId), json.encodeToString(serializer, value))
    }

    override suspend fun remove(slotId: SlotId) {
        store.remove(storageKey(slotId))
    }

    public fun storageKey(slotId: SlotId): String =
        "$namespace:${slotId.stableKey().encodeSlotKeySegment()}"
}

public class JsonSlotPersistenceBackend(
    json: Json = PersistJson,
) : StringSlotPersistenceBackend(
    store = InMemoryStringSlotPersistenceStore(),
    json = json,
    namespace = "kinetica",
)

public class DataStoreSlotPersistenceBackend(
    store: StringSlotPersistenceStore,
    json: Json = PersistJson,
    namespace: String = "kinetica.datastore",
) : StringSlotPersistenceBackend(store, json, namespace)

public class LocalStorageSlotPersistenceBackend(
    store: StringSlotPersistenceStore,
    json: Json = PersistJson,
    namespace: String = "kinetica.localStorage",
) : StringSlotPersistenceBackend(store, json, namespace)

public class NSUserDefaultsSlotPersistenceBackend(
    store: StringSlotPersistenceStore,
    json: Json = PersistJson,
    namespace: String = "kinetica.nsUserDefaults",
) : StringSlotPersistenceBackend(store, json, namespace)

public data class PersistentSlotBinding<T>(
    val slotId: SlotId,
    val serializer: KSerializer<T>,
    val policy: EqualityPolicy<T> = EqualityPolicy.structural(),
)

public fun <T> persistentSlot(
    slotId: SlotId,
    serializer: KSerializer<T>,
    policy: EqualityPolicy<T> = EqualityPolicy.structural(),
): PersistentSlotBinding<T> =
    PersistentSlotBinding(slotId, serializer, policy)

public suspend fun ComponentScope.restoreSlots(
    backend: SlotPersistenceBackend,
    bindings: Iterable<PersistentSlotBinding<*>>,
) {
    bindings.forEach { binding ->
        restoreAnySlot(binding, backend)
    }
}

public suspend fun ComponentScope.saveSlots(
    backend: SlotPersistenceBackend,
    bindings: Iterable<PersistentSlotBinding<*>>,
) {
    bindings.forEach { binding ->
        saveAnySlot(binding, backend)
    }
}

private suspend fun <T> ComponentScope.restoreAnySlot(
    binding: PersistentSlotBinding<T>,
    backend: SlotPersistenceBackend,
) {
    restoreSlot(
        slotId = binding.slotId,
        backend = backend,
        serializer = binding.serializer,
        policy = binding.policy,
    )
}

private suspend fun <T> ComponentScope.saveAnySlot(
    binding: PersistentSlotBinding<T>,
    backend: SlotPersistenceBackend,
) {
    saveSlot(
        slotId = binding.slotId,
        backend = backend,
        serializer = binding.serializer,
    )
}

private fun String.encodeSlotKeySegment(): String =
    buildString(length) {
        this@encodeSlotKeySegment.forEach { char ->
            when (char) {
                '%' -> append("%25")
                ':' -> append("%3A")
                '/' -> append("%2F")
                '#' -> append("%23")
                else -> append(char)
            }
        }
    }
