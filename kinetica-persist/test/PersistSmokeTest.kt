package io.heapy.kinetica.persist

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.SlotId
import io.heapy.kinetica.state
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistSmokeTest {
    @Test
    fun jsonBackendRestoresSlotIdAddressedPersistentState() = runTest {
        val slotId = SlotId("todo", "TodoApp", 2, "draft")
        val backend = JsonSlotPersistenceBackend()

        val firstRuntime = KineticaRuntime()
        val firstScope = ComponentScope(firstRuntime)
        firstRuntime.render(firstScope) {
            var draft by state(slotId = slotId, persistent = true) { "" }
            draft = "buy milk"
        }
        firstScope.saveSlot(slotId, backend, String.serializer())

        val restoredRuntime = KineticaRuntime()
        val restoredScope = ComponentScope(restoredRuntime)
        restoredScope.restoreSlot(slotId, backend, String.serializer())
        restoredRuntime.render(restoredScope) {
            val draft by persistentState(
                slotId = slotId,
                restoredValue = readSlot(slotId),
            ) { "" }
            assertEquals("buy milk", draft)
        }
    }

    @Test
    fun stringStoreBackendsPersistJsonByStableSlotKeysAndNamespaces() = runTest {
        val slotId = SlotId("todo", "TodoApp", 3, "settings")
        val reservedSlotId = SlotId("todo%team", "Todo/App", 4, "settings#primary")
        val colonSlotId = SlotId("todo:team", "Todo:App", 5, "settings:primary")
        val store = InMemoryStringSlotPersistenceStore()
        val defaultStringBackend = StringSlotPersistenceBackend(store)
        val dataStore = DataStoreSlotPersistenceBackend(store)
        val localStorage = LocalStorageSlotPersistenceBackend(store)
        val nsUserDefaults = NSUserDefaultsSlotPersistenceBackend(store)
        val settings = PersistedSettings(theme = "dark", density = 2)

        assertEquals("kinetica:todo%23TodoApp%233%23settings", defaultStringBackend.storageKey(slotId))
        dataStore.write(slotId, PersistedSettings.serializer(), settings)

        assertEquals(settings, dataStore.read(slotId, PersistedSettings.serializer()))
        assertEquals(null, localStorage.read(slotId, PersistedSettings.serializer()))
        assertEquals(null, nsUserDefaults.read(slotId, PersistedSettings.serializer()))

        val stored = store.snapshot()
        val key = stored.keys.single()
        assertTrue(key.startsWith("kinetica.datastore:"))
        assertFalse("#" in key)
        assertTrue("TodoApp" in key)

        dataStore.write(reservedSlotId, PersistedSettings.serializer(), settings.copy(theme = "contrast"))
        val encodedKey = store.snapshot().keys.single { storedKey -> storedKey != key }
        assertEquals(
            "kinetica.datastore:todo%25team%23Todo%2FApp%234%23settings%23primary",
            encodedKey,
        )
        assertEquals(settings.copy(theme = "contrast"), dataStore.read(reservedSlotId, PersistedSettings.serializer()))

        dataStore.write(colonSlotId, PersistedSettings.serializer(), settings.copy(theme = "colon"))
        assertEquals(
            "kinetica.datastore:todo%3Ateam%23Todo%3AApp%235%23settings%3Aprimary",
            store.snapshot().keys.single { storedKey -> storedKey.contains("%3A") },
        )

        dataStore.remove(slotId)
        dataStore.remove(reservedSlotId)
        dataStore.remove(colonSlotId)
        assertEquals(null, dataStore.read(slotId, PersistedSettings.serializer()))
        assertEquals(null, dataStore.read(reservedSlotId, PersistedSettings.serializer()))
        assertEquals(null, dataStore.read(colonSlotId, PersistedSettings.serializer()))
        assertEquals(emptyMap(), store.snapshot())
    }

    @Test
    fun batchSaveAndRestoreSlotsUseSerializerBindings() = runTest {
        val draftSlot = SlotId("todo", "TodoApp", 1, "draft")
        val countSlot = SlotId("todo", "TodoApp", 2, "count")
        val backend = LocalStorageSlotPersistenceBackend(InMemoryStringSlotPersistenceStore())
        val draftBinding = persistentSlot(draftSlot, String.serializer())
        val referentialBinding = persistentSlot(draftSlot, String.serializer(), io.heapy.kinetica.EqualityPolicy.referential())
        val bindings = listOf(
            draftBinding,
            persistentSlot(countSlot, Int.serializer()),
        )
        assertTrue(draftBinding.policy.equivalent("draft", "draft"))
        assertFalse(referentialBinding.policy.equivalent(charArrayOf('d').concatToString(), charArrayOf('d').concatToString()))

        val firstRuntime = KineticaRuntime()
        val firstScope = ComponentScope(firstRuntime)
        firstRuntime.render(firstScope) {
            var draft by state(slotId = draftSlot, persistent = true) { "" }
            var count by state(slotId = countSlot, persistent = true) { 0 }
            draft = "batch"
            count = 42
        }
        firstScope.saveSlots(backend, bindings)

        val restoredRuntime = KineticaRuntime()
        val restoredScope = ComponentScope(restoredRuntime)
        restoredScope.restoreSlots(backend, bindings)
        restoredRuntime.render(restoredScope) {
            val draft by persistentState(
                slotId = draftSlot,
                restoredValue = readSlot(draftSlot),
            ) { "" }
            val count by persistentState(
                slotId = countSlot,
                restoredValue = readSlot(countSlot),
            ) { 0 }
            assertEquals("batch", draft)
            assertEquals(42, count)
        }
    }

    @Test
    fun inMemoryBackendAndMissingSlotPathsAreExplicit() = runTest {
        val slotId = SlotId("todo", "TodoApp", 4, "scratch")
        val missingSlot = SlotId("todo", "TodoApp", 5, "missing")
        val backend = InMemorySlotPersistenceBackend()

        assertEquals(null, backend.read(slotId, String.serializer()))
        backend.write(slotId, String.serializer(), "memo")
        assertEquals("memo", backend.read(slotId, String.serializer()))
        backend.remove(slotId)
        assertEquals(null, backend.read(slotId, String.serializer()))

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        scope.restoreSlot(slotId, backend, String.serializer())
        runtime.render(scope) {
            val restored by persistentState(
                slotId = slotId,
                restoredValue = readSlot(slotId),
            ) { "initial" }
            assertEquals("initial", restored)
        }

        scope.saveSlot(missingSlot, backend, String.serializer())
        assertEquals(null, backend.read(missingSlot, String.serializer()))

        backend.write(slotId, String.serializer(), "stale")
        scope.writeSlot<String?>(slotId, null, persistent = true)
        scope.saveSlot(slotId, backend, String.serializer())
        assertEquals(null, backend.read(slotId, String.serializer()))

        assertFailsWith<IllegalArgumentException> {
            StringSlotPersistenceBackend(InMemoryStringSlotPersistenceStore(), namespace = " ")
        }

        assertEquals("encoded", EncodedSlotValue("encoded").value)
    }
}

@Serializable
private data class PersistedSettings(
    val theme: String,
    val density: Int,
)
