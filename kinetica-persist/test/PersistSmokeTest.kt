package io.heapy.kinetica.persist

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.SlotId
import io.heapy.kinetica.UiComponent
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
        val backend = JsonSlotPersistenceBackend()

        val firstRuntime = KineticaRuntime()
        val firstScope = ComponentScope(firstRuntime)
        firstRuntime.render(firstScope) {
            DraftEditor()
        }
        firstScope.saveSlot(draftSlotId, backend, String.serializer())

        val restoredRuntime = KineticaRuntime()
        val restoredScope = ComponentScope(restoredRuntime)
        restoredScope.restoreSlot(draftSlotId, backend, String.serializer())
        // restoreSlot before the first render parks the value in the scope; it seeds the
        // cell when DraftViewer's state slot is created during render.
        assertTrue(restoredScope.containsSlot(draftSlotId))
        assertEquals("buy milk", restoredScope.readSlot(draftSlotId))
        observedDraft = null
        restoredRuntime.render(restoredScope) {
            DraftViewer()
        }
        assertEquals("buy milk", observedDraft)
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
        val backend = LocalStorageSlotPersistenceBackend(InMemoryStringSlotPersistenceStore())
        val draftBinding = persistentSlot(batchDraftSlotId, String.serializer())
        val referentialBinding = persistentSlot(batchDraftSlotId, String.serializer(), io.heapy.kinetica.EqualityPolicy.referential())
        val bindings = listOf(
            draftBinding,
            persistentSlot(batchCountSlotId, Int.serializer()),
        )
        assertTrue(draftBinding.policy.equivalent("draft", "draft"))
        assertFalse(referentialBinding.policy.equivalent(charArrayOf('d').concatToString(), charArrayOf('d').concatToString()))

        val firstRuntime = KineticaRuntime()
        val firstScope = ComponentScope(firstRuntime)
        firstRuntime.render(firstScope) {
            BatchEditor()
        }
        firstScope.saveSlots(backend, bindings)

        val restoredRuntime = KineticaRuntime()
        val restoredScope = ComponentScope(restoredRuntime)
        restoredScope.restoreSlots(backend, bindings)
        observedBatchDraft = null
        observedBatchCount = null
        restoredRuntime.render(restoredScope) {
            BatchViewer()
        }
        assertEquals("batch", observedBatchDraft)
        assertEquals(42, observedBatchCount)
    }

    @Test
    fun inMemoryBackendAndMissingSlotPathsAreExplicit() = runTest {
        val missingSlot = SlotId("todo", "TodoApp", 5, "missing")
        val backend = InMemorySlotPersistenceBackend()

        assertEquals(null, backend.read(scratchSlotId, String.serializer()))
        backend.write(scratchSlotId, String.serializer(), "memo")
        assertEquals("memo", backend.read(scratchSlotId, String.serializer()))
        backend.remove(scratchSlotId)
        assertEquals(null, backend.read(scratchSlotId, String.serializer()))

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        scope.restoreSlot(scratchSlotId, backend, String.serializer())
        observedScratch = null
        runtime.render(scope) {
            ScratchViewer()
        }
        assertEquals("initial", observedScratch)

        scope.saveSlot(missingSlot, backend, String.serializer())
        assertEquals(null, backend.read(missingSlot, String.serializer()))

        backend.write(scratchSlotId, String.serializer(), "stale")
        scope.writeSlot<String?>(scratchSlotId, null, persistent = true)
        scope.saveSlot(scratchSlotId, backend, String.serializer())
        assertEquals(null, backend.read(scratchSlotId, String.serializer()))

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

// Slot DSL is only legal lexically inside @UiComponent functions, so the rendered halves
// of the persistence round-trips live here as file-level components parameterized (and
// observed) through file-level vars.

private val draftSlotId = SlotId("todo", "TodoApp", 2, "draft")
private var observedDraft: String? = null

@UiComponent(skippable = false)
private fun ComponentScope.DraftEditor() {
    var draft by state(slotId = draftSlotId, persistent = true) { "" }
    draft = "buy milk"
}

@UiComponent(skippable = false)
private fun ComponentScope.DraftViewer() {
    val draft by persistentState(
        slotId = draftSlotId,
        restoredValue = readSlot(draftSlotId),
    ) { "" }
    observedDraft = draft
}

private val batchDraftSlotId = SlotId("todo", "TodoApp", 1, "draft")
private val batchCountSlotId = SlotId("todo", "TodoApp", 2, "count")
private var observedBatchDraft: String? = null
private var observedBatchCount: Int? = null

@UiComponent(skippable = false)
private fun ComponentScope.BatchEditor() {
    var draft by state(slotId = batchDraftSlotId, persistent = true) { "" }
    var count by state(slotId = batchCountSlotId, persistent = true) { 0 }
    draft = "batch"
    count = 42
}

@UiComponent(skippable = false)
private fun ComponentScope.BatchViewer() {
    val draft by persistentState(
        slotId = batchDraftSlotId,
        restoredValue = readSlot(batchDraftSlotId),
    ) { "" }
    val count by persistentState(
        slotId = batchCountSlotId,
        restoredValue = readSlot(batchCountSlotId),
    ) { 0 }
    observedBatchDraft = draft
    observedBatchCount = count
}

private val scratchSlotId = SlotId("todo", "TodoApp", 4, "scratch")
private var observedScratch: String? = null

@UiComponent(skippable = false)
private fun ComponentScope.ScratchViewer() {
    val restored by persistentState(
        slotId = scratchSlotId,
        restoredValue = readSlot(scratchSlotId),
    ) { "initial" }
    observedScratch = restored
}
