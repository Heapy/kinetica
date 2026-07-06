package io.heapy.kinetica

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/*
 * Frame-era port of the RuntimeSmokeTest core: state/derived/cells, events, effects,
 * watch, context, and duplicate-key handling. Every slot-consuming body lives in a private
 * top-level @UiComponent component, parameterized through top-level vars and scope-free
 * store() cells; tests drive them with `runtime.render(scope) { App() }` and dispatch
 * event ids read from node props.
 */

// --- stateDerivedAndEventsProduceSerializableNodeValues ---

@UiComponent(skippable = false)
private fun ComponentScope.SmokeCounterApp() {
    var count by state { 0 }
    val label by derived { "Count: $count" }

    column(semantics = Semantics(testTag = "root")) {
        text(label)
        button(onClick = event { count += 1 }, semantics = Semantics(role = Role.Button, testTag = "inc")) {
            text("Increment")
        }
    }
}

// --- derivedStoreFragmentInvalidatesRuntimeUntilNoLongerObserved ---

private data class SmokeProfile(val name: String, val age: Int)

private val smokeProfile = store(SmokeProfile("Ada", 36))
private var smokeShowName = true

@UiComponent(skippable = false)
private fun ComponentScope.SmokeProfileName() {
    if (smokeShowName) {
        val name by derived { smokeProfile.value.name }
        text("Name: $name")
    } else {
        text("Hidden")
    }
}

// --- runtimeDefaultOverloadsAndManualInvalidationAreExplicit ---

private var smokePayload: Any? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokePayloadButton() {
    button(onClick = { smokePayload = "payload" }) {
        text("Click")
    }
}

// --- equalityPoliciesAndDerivedCellsCoverReferentialAndEqualValueEdges ---

private val smokePositiveCount = store(1)

@UiComponent(skippable = false)
private fun ComponentScope.SmokePositiveProbe() {
    val positive by derived { smokePositiveCount.value > 0 }
    text("Positive: $positive")
}

// --- componentScopeDefaultHelpersCoverFragmentSkipsSlotsAndEmptyExitGroups ---

@UiComponent
private suspend fun ComponentScope.SmokeSuspendKeyedProbe() {
    suspendKeyed("suspend-key") {
        text("Suspend keyed")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeFragmentAndEmptyExit() {
    fragment {
        text("Fragment child")
    }
    exitGroup(key = "empty", visible = true) {
    }
}

// --- inputAndCheckboxDslCoverNullPayloadAndPassiveEdges ---

private var smokeDraft = "initial"

@UiComponent(skippable = false)
private fun ComponentScope.SmokeInputForm() {
    column {
        textInput(
            value = smokeDraft,
            onInput = event<String> { value -> smokeDraft = value },
        )
        checkbox(checked = true)
    }
}

private var smokeObservedDirection: LayoutDirection? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokeDirectionProbe() {
    smokeObservedDirection = currentLayoutDirection()
    provideLayoutDirection(LayoutDirection.Rtl) {
        row {
            text("rtl")
        }
    }
    textInput(value = "passive")
    host("empty")
}

// --- runtimeRenderSuspendCommitsSuspendComponentNodes ---

@UiComponent
private suspend fun ComponentScope.SmokeSuspendCounter() {
    delay(10)
    var count by state { 0 }
    button(onClick = event { count += 1 }) {
        text("Count: $count")
    }
}

// --- eventsKeepStableHostIdsAndReadLatestCommittedState ---

@UiComponent(skippable = false)
private fun ComponentScope.SmokeStableEventCounter() {
    var count by state { 0 }

    column {
        text("Count: $count")
        button(onClick = event { count += 1 }) {
            text("Increment")
        }
    }
}

// --- directHostEventCallbacksKeepStableIdsAcrossRenders ---

@UiComponent(skippable = false)
private fun ComponentScope.SmokeDirectCallbackCounter() {
    var count by state { 0 }

    button(onClick = { count += 1 }) {
        text("Count: $count")
    }
}

// --- hostEventRegistryEvictsHandlersThatStopRendering ---

private var smokeRegistryRows: List<Int> = emptyList()
private var smokeRegistryClicks = 0

@UiComponent(skippable = false)
private fun ComponentScope.SmokeRowButtons() {
    column {
        each(smokeRegistryRows, key = { it }) { row ->
            button(onClick = { smokeRegistryClicks += 1 }) {
                text("Row $row")
            }
        }
    }
}

// --- launchEffect tests ---

private var smokeLaunchStarts = 0
private var smokeLaunchObservedDuringRender = -1
private var smokeLaunchStartedGate = CompletableDeferred<Unit>()

@UiComponent(skippable = false)
private fun ComponentScope.SmokeLaunchOnce() {
    launchEffect {
        smokeLaunchStarts += 1
        smokeLaunchStartedGate.complete(Unit)
    }
    smokeLaunchObservedDuringRender = smokeLaunchStarts
    text("Effect")
}

private var smokeEffectVisible = true
private var smokeEffectStarted = CompletableDeferred<Unit>()
private var smokeEffectDisposed = CompletableDeferred<Unit>()

@UiComponent(skippable = false)
private fun ComponentScope.SmokeCancellableEffect() {
    if (smokeEffectVisible) {
        launchEffect {
            smokeEffectStarted.complete(Unit)
            awaitDispose { smokeEffectDisposed.complete(Unit) }
        }
    }
    text("Visible: $smokeEffectVisible")
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeDefaultCleanupEffect() {
    if (smokeEffectVisible) {
        launchEffect {
            smokeEffectStarted.complete(Unit)
            awaitDispose()
        }
    }
    text("Visible: $smokeEffectVisible")
}

// --- visibleOnlyLazyEachDisposesEffectsAndRefsForHiddenItems ---

private var smokeLazyEffectListState = lazyListState(firstVisibleIndex = 0, visibleCount = 1)
private var smokeLazyEffectRef: Ref<String>? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokeLazyEffectRows() {
    lazyEach(
        items = lazyItems(listOf("one", "two")),
        key = { item -> item },
        retain = RetainPolicy.VisibleOnly,
        state = smokeLazyEffectListState,
    ) { item ->
        if (item == "one") {
            smokeLazyEffectRef = imperativeHandle { "handle" }
            launchEffect {
                smokeEffectStarted.complete(Unit)
                awaitDispose { smokeEffectDisposed.complete(Unit) }
            }
        }
        text(item)
    }
}

// --- watch tests ---
// The barebones Node runner does not await runTest promises, so async tests in one file
// run interleaved. Every mutable collaborator therefore lives in a per-test probe passed
// as a component parameter — never in shared top-level state.

private class WatchProbe {
    val observed = mutableListOf<Int>()
    var countCell: MutableCell<Int>? = null
    val store = store(0)
    val useFirst = store(true)
    val first = store(0)
    val second = store(100)
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeWatchCounter(probe: WatchProbe) {
    val count = state { 0 }
    probe.countCell = count
    watch({ count.value }) { value ->
        probe.observed += value
    }
    text("Count: ${count.value}")
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeWatchOnlyReader(probe: WatchProbe) {
    watch({ probe.store.value }) { value ->
        probe.observed += value
    }
    text("Watching")
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeWatchSwitcher(probe: WatchProbe) {
    watch(
        source = {
            if (probe.useFirst.value) {
                probe.first.value
            } else {
                probe.second.value
            }
        },
    ) { value ->
        probe.observed += value
    }
    text("Watching")
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeWatchSelfRestarting(probe: WatchProbe) {
    val count = state { 0 }
    probe.countCell = count
    watch({ count.value }) { value ->
        probe.observed += value
        count.value = value + 1
    }
    text("Count: ${count.value}")
}

// --- duplicate keys ---

@UiComponent(skippable = false)
private fun ComponentScope.SmokeDupEach() {
    each(listOf("one", "one"), key = { item -> item }) { item ->
        text(item)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeDupLazyEach() {
    lazyEach(
        items = lazyItems(listOf("two", "two")),
        key = { item -> item },
    ) { item ->
        text(item)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeLastWinsEach() {
    each(listOf("first", "fifth", "third"), key = { item -> item.first() }) { item ->
        text(item)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeLastWinsLazyEach() {
    lazyEach(
        items = lazyItems(listOf("alpha", "beta", "atom")),
        key = { item -> item.first() },
        state = lazyListState(visibleCount = 2),
    ) { item ->
        text(item)
    }
}

class RuntimeSmokeCoreTest {
    @Test
    fun stateDerivedAndEventsProduceSerializableNodeValues() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun render(): Node = runtime.render(scope) { SmokeCounterApp() }.tree

        val first = render()
        val button = first.findHostByTag("button")
        runtime.dispatch(button.props.getValue("event:onClick"))
        val second = render()

        assertTrue(diffNodes(first, second).isNotEmpty())
        assertEquals("Count: 1", second.findText().value)
    }

    @Test
    fun storeReadDuringRenderInvalidatesRuntimeUntilNoLongerObserved() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val shared = store(0)
        var showStore = true

        fun render(): RenderResult = runtime.render(scope) {
            if (showStore) {
                text("Store: ${shared.value}")
            } else {
                text("Hidden")
            }
        }

        assertEquals("Store: 0", render().tree.findText().value)

        shared.value = 1
        assertTrue(runtime.hasPendingInvalidation)
        val changed = render()
        assertEquals("Store: 1", changed.tree.findText().value)
        assertEquals("cell write", changed.journal.last { it.kind == JournalKind.RenderStarted }.attributes["cause"])

        showStore = false
        assertEquals("Hidden", render().tree.findText().value)

        shared.value = 2
        assertFalse(runtime.hasPendingInvalidation)
    }

    @Test
    fun derivedStoreFragmentInvalidatesRuntimeUntilNoLongerObserved() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeProfile.value = SmokeProfile("Ada", 36)
        smokeShowName = true

        fun render(): RenderResult = runtime.render(scope) { SmokeProfileName() }

        assertEquals("Name: Ada", render().tree.findText().value)

        smokeProfile.value = SmokeProfile("Ada", 37)
        assertFalse(runtime.hasPendingInvalidation)
        assertEquals("Name: Ada", render().tree.findText().value)

        smokeProfile.value = SmokeProfile("Grace", 36)
        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("Name: Grace", render().tree.findText().value)

        smokeShowName = false
        assertEquals("Hidden", render().tree.findText().value)

        smokeProfile.value = SmokeProfile("Katherine", 36)
        assertFalse(runtime.hasPendingInvalidation)
    }

    @Test
    fun mutableCellsApplyConcurrentUpdatesAtomically() = runTest {
        val counter = store(0)
        val start = CompletableDeferred<Unit>()

        withContext(Dispatchers.Default) {
            coroutineScope {
                val jobs = List(32) {
                    launch {
                        start.await()
                        repeat(250) {
                            counter.update { value -> value + 1 }
                        }
                    }
                }
                start.complete(Unit)
                jobs.joinAll()
            }
        }

        assertEquals(8_000, counter.value)
    }

    @Test
    fun cellDelegatesExposePublicReadWriteAndUpdateContracts() {
        val readOnlyCell = object : Cell<Int> {
            override val value: Int = 3
        }
        val readOnly by readOnlyCell
        assertEquals(3, readOnly)
        assertEquals(42, peek { 42 })

        val customMutableCell = object : MutableCell<Int> {
            override var value: Int = 5
        }
        var delegated by customMutableCell
        assertEquals(5, delegated)
        delegated = 8
        assertEquals(8, customMutableCell.value)
        customMutableCell.update { value -> value + 2 }
        assertEquals(10, delegated)

        val storeCell = store(1)
        var stored by storeCell
        assertEquals(1, stored)
        stored = 2
        assertEquals(2, storeCell.value)
        storeCell.update { value -> value }
        assertEquals(2, storeCell.value)
    }

    @Test
    fun runtimeAndJournalConfigurationValidateExplicitEdges() {
        assertFailsWith<IllegalArgumentException> {
            KineticaRuntime(watchLoopRestartLimit = 0)
        }.also { failure ->
            assertEquals("watchLoopRestartLimit must be positive.", failure.message)
        }
        assertFailsWith<IllegalArgumentException> {
            KineticaRuntime(journalSampleInterval = 0)
        }.also { failure ->
            assertEquals("journalSampleInterval must be positive when provided.", failure.message)
        }
        assertFailsWith<IllegalArgumentException> {
            KineticaRuntime(appResourceTtlMillis = -1)
        }.also { failure ->
            assertEquals("appResourceTtlMillis must be non-negative.", failure.message)
        }
        assertFailsWith<IllegalArgumentException> {
            KineticaRuntime(exitTimeoutMillis = -1)
        }.also { failure ->
            assertEquals("exitTimeoutMillis must be non-negative.", failure.message)
        }
        assertFailsWith<IllegalArgumentException> {
            JournalBuffer<Int>(capacity = 0)
        }.also { failure ->
            assertEquals("Journal buffer capacity must be positive.", failure.message)
        }
        val buffer = JournalBuffer<Int>(capacity = 2)
        buffer.append(1)
        buffer.append(2)
        buffer.clear()
        assertEquals(emptyList(), buffer.entries())

        val runtime = KineticaRuntime()
        runtime.advanceVirtualTimeBy(0)
        assertEquals(0, runtime.virtualTimeMillis)
        assertFalse(runtime.hasPendingInvalidation)
        assertFalse(runtime.journal().any { entry -> entry.kind == JournalKind.VirtualTime })

        assertFailsWith<IllegalArgumentException> {
            runtime.advanceVirtualTimeBy(-1)
        }.also { failure ->
            assertEquals("millis must be non-negative.", failure.message)
        }
    }

    @Test
    fun runtimeDefaultOverloadsAndManualInvalidationAreExplicit() = runTest {
        val runtime = KineticaRuntime()

        val suspendResult = runtime.renderSuspend {
            text("Suspend overload")
        }
        assertEquals("Suspend overload", suspendResult.tree.findText().value)

        val emptyRender = runtime.render {
        }
        assertTrue(assertIs<FragmentNode>(emptyRender.tree).children.isEmpty())
        assertEquals(emptyList(), RenderResult(TextNode("manual"), emptyList(), invalidated = false).warnings)

        smokePayload = null
        val eventTree = runtime.render {
            SmokePayloadButton()
        }.tree as HostNode
        runtime.dispatch("missing-event")
        runtime.dispatch(eventTree.props.getValue("event:onClick"))
        assertEquals("payload", smokePayload)

        runtime.invalidate()
        assertTrue(runtime.hasPendingInvalidation)
        val manualRender = runtime.render {
            text("Manual")
        }
        assertEquals("manual", manualRender.journal.last { it.kind == JournalKind.RenderStarted }.attributes["cause"])
    }

    @Test
    fun equalityPoliciesAndDerivedCellsCoverReferentialAndEqualValueEdges() {
        val sameReference = StringBuilder("kinetica")
        val equalReference = StringBuilder("kinetica")
        val referential = EqualityPolicy.referential<StringBuilder>()

        assertTrue(referential.equivalent(sameReference, sameReference))
        assertFalse(referential.equivalent(sameReference, equalReference))
        assertFalse(EqualityPolicy.neverEqual<Int>().equivalent(1, 1))

        val referentialRuntime = KineticaRuntime()
        val referentialScope = ComponentScope(referentialRuntime)
        val referentialCell = store(sameReference, policy = referential)

        fun renderReferential(): String =
            referentialRuntime.render(referentialScope) {
                text(referentialCell.value.toString())
            }.tree.findText().value

        assertEquals("kinetica", renderReferential())
        referentialCell.value = equalReference
        assertTrue(referentialRuntime.hasPendingInvalidation)
        assertEquals("kinetica", renderReferential())
        referentialCell.value = equalReference
        assertFalse(referentialRuntime.hasPendingInvalidation)

        val neverEqualRuntime = KineticaRuntime()
        val neverEqualScope = ComponentScope(neverEqualRuntime)
        val neverEqualStore = store("same", policy = EqualityPolicy.neverEqual())

        fun renderNeverEqual(): String =
            neverEqualRuntime.render(neverEqualScope) {
                text(neverEqualStore.value)
            }.tree.findText().value

        assertEquals("same", renderNeverEqual())
        neverEqualStore.value = "same"
        assertTrue(neverEqualRuntime.hasPendingInvalidation)
        assertEquals("same", renderNeverEqual())

        // Scope-free derived cells (derive {}) keep the lazy recompute-once contract; the
        // slot-backed derived {} inside components is covered by SmokePositiveProbe below.
        val source = store(1)
        var computes = 0
        val bucket = derive {
            computes += 1
            source.value / 2
        }

        assertEquals(0, bucket.value)
        assertEquals(0, bucket.value)
        assertEquals(1, computes)
        source.value = 2
        assertEquals(1, bucket.value)

        source.value = 3
        assertEquals(1, bucket.value)
        assertEquals(3, computes)

        val derivedRuntime = KineticaRuntime()
        val derivedScope = ComponentScope(derivedRuntime)
        smokePositiveCount.value = 1

        fun renderDerived(): String =
            derivedRuntime.render(derivedScope) { SmokePositiveProbe() }.tree.findText().value

        assertEquals("Positive: true", renderDerived())
        smokePositiveCount.value = 2
        assertFalse(derivedRuntime.hasPendingInvalidation)

        smokePositiveCount.value = -1
        assertTrue(derivedRuntime.hasPendingInvalidation)
        assertEquals("Positive: false", renderDerived())
    }

    @Test
    fun lazyListStateValidationAndVisibleRangesCoverEdges() {
        assertEquals(IntRange.EMPTY, LazyListState().visibleRange(totalSize = 0))
        assertEquals(2 until 5, LazyListState(firstVisibleIndex = 2).visibleRange(totalSize = 5))
        assertEquals(2 until 4, LazyListState(firstVisibleIndex = 2, visibleCount = 2).visibleRange(totalSize = 5))
        assertEquals(IntRange.EMPTY, LazyListState(firstVisibleIndex = 5, visibleCount = 1).visibleRange(totalSize = 5))
        assertEquals(IntRange.EMPTY, LazyListState(firstVisibleIndex = 2, visibleCount = 0).visibleRange(totalSize = 5))

        assertFailsWith<IllegalArgumentException> {
            LazyListState(firstVisibleIndex = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            LazyListState(visibleCount = -1)
        }

        assertEquals(LazyListState(firstVisibleIndex = 3, visibleCount = 4), lazyListState(3, 4))
    }

    @Test
    fun componentScopeRenderNodeCollectsReturnedComponentNodes() {
        val scope = ComponentScope()

        val node = scope.renderNode {
            text("A")
            emit(
                renderNode {
                    text("B")
                },
            )
        }

        val fragment = assertIs<FragmentNode>(node)
        assertEquals(listOf("A", "B"), fragment.children.map { child -> assertIs<TextNode>(child).value })
    }

    @Test
    fun componentScopeDefaultHelpersCoverFragmentSkipsSlotsAndEmptyExitGroups() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        assertEquals(FragmentNode(), scope.renderNode {})

        val defaultSkip = scope.skippableNode("app.DefaultSkip") {
            TextNode("default inputs")
        }
        assertSame(defaultSkip, scope.skippableNode("app.DefaultSkip") { TextNode("ignored") })

        val defaultSuspendSkip = scope.skippableSuspendNode("app.DefaultSuspendSkip") {
            TextNode("default suspend inputs")
        }
        assertSame(
            defaultSuspendSkip,
            scope.skippableSuspendNode("app.DefaultSuspendSkip") { TextNode("ignored") },
        )

        val suspendKeyedNode = runtime.renderSuspend { SmokeSuspendKeyedProbe() }.tree
        assertEquals("Suspend keyed", suspendKeyedNode.findText().value)

        val slotId = SlotId("runtime", "Defaults", 0, "manual")
        scope.writeSlot(slotId, "stored")
        assertEquals("stored", scope.readSlot(slotId))
        scope.disposeKeyScope("missing")
        assertEquals(LazyListState(), lazyListState())

        val tree = runtime.render(scope) { SmokeFragmentAndEmptyExit() }.tree

        assertEquals(listOf("Fragment child"), tree.findTexts().map { text -> text.value })
    }

    @Test
    fun inputAndCheckboxDslCoverNullPayloadAndPassiveEdges() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeDraft = "initial"

        fun render(): HostNode = runtime.render(scope) { SmokeInputForm() }.tree as HostNode

        val first = render()
        val input = first.findHostByTag("textInput")
        assertFalse("placeholder" in input.props)
        assertFalse("event:onSubmit" in input.props)

        runtime.dispatch(input.props.getValue("event:onInput"), null)
        val second = render()

        assertEquals("", second.findHostByTag("textInput").props["value"])
        val passiveCheckbox = second.findHostByTag("checkbox")
        assertEquals("true", passiveCheckbox.props["checked"])
        assertFalse("event:onToggle" in passiveCheckbox.props)

        smokeObservedDirection = null
        val directionTree = KineticaRuntime().render {
            SmokeDirectionProbe()
        }.tree
        assertEquals(LayoutDirection.Ltr, smokeObservedDirection)
        assertEquals("Rtl", directionTree.findHostByTag("row").props["direction"])
        val passiveInput = directionTree.findHostByTag("textInput")
        assertEquals(mapOf("value" to "passive"), passiveInput.props)
        assertEquals(emptyList(), directionTree.findHostByTag("empty").children)
    }

    @Test
    fun runtimeRenderSuspendCommitsSuspendComponentNodes() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        suspend fun render(): RenderResult = runtime.renderSuspend(scope) { SmokeSuspendCounter() }

        val first = render()
        assertEquals("Count: 0", first.tree.findText().value)
        assertEquals("initial", first.journal.last { it.kind == JournalKind.RenderStarted }.attributes["cause"])

        runtime.dispatch(first.tree.findHostByTag("button").props.getValue("event:onClick"))
        val second = render()

        assertEquals("Count: 1", second.tree.findText().value)
        assertEquals("cell write", second.journal.last { it.kind == JournalKind.RenderStarted }.attributes["cause"])
        assertTrue(second.journal.any { it.kind == JournalKind.RenderCommitted })
    }

    @Test
    fun staticNodeCachesHoistedNodeById() {
        val scope = ComponentScope()

        val first = scope.staticNode("app/Header#static#0") { TextNode("Static") }
        val second = scope.staticNode("app/Header#static#0") { TextNode("Ignored") }

        assertSame(first, second)
        assertEquals("Static", assertIs<TextNode>(second).value)
    }

    @Test
    fun skippableNodeReusesEqualInputsAcrossUnrelatedStateWrites() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var renders = 0

        val first = scope.skippableNode("app.Header", inputs = listOf("A")) {
            renders += 1
            TextNode("A-$renders")
        }
        val second = scope.skippableNode("app.Header", inputs = listOf("A")) {
            renders += 1
            TextNode("A-$renders")
        }
        // A write to a cell the factory never read must NOT defeat the skip (writes to
        // cells the factory DOES read are covered by
        // skippableNodeRerendersWhenHoistedStoreChanges).
        val cell = store(0)
        cell.value = 1
        val third = scope.skippableNode("app.Header", inputs = listOf("A")) {
            renders += 1
            TextNode("A-$renders")
        }
        // A changed input still re-runs the factory.
        val fourth = scope.skippableNode("app.Header", inputs = listOf("B")) {
            renders += 1
            TextNode("B-$renders")
        }

        assertSame(first, second)
        assertSame(first, third)
        assertEquals("A-1", assertIs<TextNode>(third).value)
        assertEquals("B-2", assertIs<TextNode>(fourth).value)
        assertTrue(runtime.journal().any { entry ->
            entry.kind == JournalKind.Skipped && entry.attributes["componentId"] == "app.Header"
        })
    }

    @Test
    fun skippableNodeRerendersWhenHoistedStoreChanges() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val shared = store(0)
        var childRenders = 0

        fun render(): RenderResult = runtime.render(scope) {
            emit(
                skippableNode("app.SharedBadge", inputs = emptyList()) {
                    childRenders += 1
                    renderNode {
                        text("Shared: ${shared.value}")
                    }
                },
            )
        }

        assertEquals("Shared: 0", render().tree.findText().value)

        shared.value = 1
        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("Shared: 1", render().tree.findText().value)

        shared.value = 2
        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("Shared: 2", render().tree.findText().value)
        assertEquals(3, childRenders)
    }

    @Test
    fun suspendNodeHelpersReuseCacheAndRecoverAfterFailures() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var renders = 0

        val first = scope.skippableSuspendNode("app.AsyncHeader", inputs = listOf("A")) {
            delay(1)
            renders += 1
            TextNode("A-$renders")
        }
        val second = scope.skippableSuspendNode("app.AsyncHeader", inputs = listOf("A")) {
            renders += 1
            TextNode("A-$renders")
        }
        val third = scope.skippableSuspendNode("app.AsyncHeader", inputs = listOf("B")) {
            renders += 1
            TextNode("B-$renders")
        }

        assertSame(first, second)
        assertEquals("A-1", assertIs<TextNode>(second).value)
        assertEquals("B-2", assertIs<TextNode>(third).value)
        assertTrue(runtime.journal().any { entry ->
            entry.kind == JournalKind.Skipped && entry.attributes["componentId"] == "app.AsyncHeader"
        })

        val shared = store("initial")
        val dependentFirst = scope.skippableSuspendNode("app.DependentAsync", inputs = emptyList()) {
            TextNode(shared.value)
        }
        val dependentSecond = scope.skippableSuspendNode("app.DependentAsync", inputs = emptyList()) {
            TextNode("unused")
        }
        shared.value = "changed"
        val dependentThird = scope.skippableSuspendNode("app.DependentAsync", inputs = emptyList()) {
            TextNode(shared.value)
        }

        assertSame(dependentFirst, dependentSecond)
        assertEquals("changed", assertIs<TextNode>(dependentThird).value)

        val failure = assertFailsWith<IllegalStateException> {
            scope.renderSuspendNode {
                text("before")
                error("boom")
            }
        }
        assertEquals("boom", failure.message)

        val recovered = scope.renderSuspendNode {
            text("after")
        }
        assertEquals("after", assertIs<TextNode>(recovered).value)
    }

    @Test
    fun eventsKeepStableHostIdsAndReadLatestCommittedState() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun render(): HostNode = runtime.render(scope) { SmokeStableEventCounter() }.tree as HostNode

        val first = render()
        val firstEventId = first.findHostByTag("button").props.getValue("event:onClick")

        runtime.dispatch(firstEventId)
        val second = render()
        val secondEventId = second.findHostByTag("button").props.getValue("event:onClick")

        runtime.dispatch(firstEventId)
        val third = render()

        assertEquals(firstEventId, secondEventId)
        assertEquals("Count: 2", third.findText().value)
    }

    @Test
    fun directHostEventCallbacksKeepStableIdsAcrossRenders() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun render(): HostNode = runtime.render(scope) { SmokeDirectCallbackCounter() }.tree as HostNode

        val first = render()
        val firstEventId = first.props.getValue("event:onClick")

        runtime.dispatch(firstEventId)
        val second = render()
        val secondEventId = second.props.getValue("event:onClick")

        runtime.dispatch(secondEventId)
        val third = render()
        val thirdEventId = third.props.getValue("event:onClick")

        assertEquals(firstEventId, secondEventId)
        assertEquals(secondEventId, thirdEventId)
        assertEquals("Count: 2", third.findText().value)
    }

    @Test
    fun hostEventRegistryEvictsHandlersThatStopRendering() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeRegistryClicks = 0

        fun render(rows: List<Int>): HostNode {
            smokeRegistryRows = rows
            return runtime.render(scope) { SmokeRowButtons() }.tree as HostNode
        }

        fun collectClickEventIds(node: Node): List<String> = when (node) {
            is HostNode -> node.props.filterKeys { it == "event:onClick" }.values.toList() +
                node.children.flatMap(::collectClickEventIds)
            is FragmentNode -> node.children.flatMap(::collectClickEventIds)
            else -> emptyList()
        }

        val firstIds = collectClickEventIds(render(listOf(1, 2, 3)))
        assertEquals(3, firstIds.distinct().size)

        // replacing every keyed row must not grow the registry
        val secondIds = collectClickEventIds(render(listOf(4, 5, 6)))
        assertEquals(3, secondIds.distinct().size)

        val tree = render(listOf(7, 8, 9))
        val removedId = collectClickEventIds(tree).last()

        // shrinking the list evicts the dropped rows' handlers
        val remainingId = collectClickEventIds(render(listOf(7))).single()

        // dispatching an evicted id is a graceful no-op
        runtime.dispatch(removedId)
        assertEquals(0, smokeRegistryClicks)

        // disposing the scope releases everything it registered
        scope.dispose()
        runtime.dispatch(remainingId)
        assertEquals(0, smokeRegistryClicks)
    }

    @Test
    fun layoutEffectRunsAfterRenderCommit() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var phase = "before-render"
        var observedPhase = "not-run"

        runtime.render(scope) {
            phase = "render"
            layoutEffect {
                observedPhase = phase
            }
            phase = "render-complete"
            text("Layout")
        }

        assertEquals("render-complete", observedPhase)
    }

    @Test
    fun launchEffectStartsOnceAfterFirstCommit() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeLaunchStarts = 0
        smokeLaunchObservedDuringRender = -1
        smokeLaunchStartedGate = CompletableDeferred()

        fun render() {
            runtime.render(scope) { SmokeLaunchOnce() }
        }

        render()
        assertEquals(0, smokeLaunchObservedDuringRender)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                smokeLaunchStartedGate.await()
            }
        }
        assertEquals(1, smokeLaunchStarts)

        render()
        delay(50)
        assertEquals(1, smokeLaunchStarts)
        assertEquals(1, smokeLaunchObservedDuringRender)
    }

    @Test
    fun launchEffectIsCancelledWhenDeclarationLeavesRender() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeEffectVisible = true
        smokeEffectStarted = CompletableDeferred()
        smokeEffectDisposed = CompletableDeferred()

        fun render(): Node = runtime.render(scope) { SmokeCancellableEffect() }.tree

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                smokeEffectStarted.await()
            }
        }

        smokeEffectVisible = false
        render()

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                smokeEffectDisposed.await()
            }
        }
    }

    @Test
    fun launchEffectAwaitDisposeDefaultCleanupCancelsCleanly() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeEffectVisible = true
        smokeEffectStarted = CompletableDeferred()

        fun render(): Node = runtime.render(scope) { SmokeDefaultCleanupEffect() }.tree

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                smokeEffectStarted.await()
            }
        }

        smokeEffectVisible = false
        assertEquals("Visible: false", render().findText().value)

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
    }

    @Test
    fun visibleOnlyLazyEachDisposesEffectsAndRefsForHiddenItems() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeEffectStarted = CompletableDeferred()
        smokeEffectDisposed = CompletableDeferred()
        smokeLazyEffectListState = lazyListState(firstVisibleIndex = 0, visibleCount = 1)
        smokeLazyEffectRef = null

        fun render() {
            runtime.render(scope) { SmokeLazyEffectRows() }
        }

        render()
        assertEquals("handle", smokeLazyEffectRef?.current)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                smokeEffectStarted.await()
            }
        }

        smokeLazyEffectListState = smokeLazyEffectListState.scrollTo(firstVisibleIndex = 1)
        render()

        assertEquals(null, smokeLazyEffectRef?.current)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                smokeEffectDisposed.await()
            }
        }
    }

    @Test
    fun watchEvaluatesAfterCommitsAndDoesNotDuplicateAcrossRenders() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = WatchProbe()

        fun render() {
            runtime.render(scope) { SmokeWatchCounter(probe) }
        }

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (probe.observed != listOf(0)) {
                    delay(10)
                }
            }
        }

        render()
        delay(50)
        assertEquals(listOf(0), probe.observed)

        probe.countCell!!.value = 1
        delay(50)
        assertEquals(listOf(0), probe.observed)

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (probe.observed != listOf(0, 1)) {
                    delay(10)
                }
            }
        }

        render()
        delay(50)
        assertEquals(listOf(0, 1), probe.observed)
        assertEquals(
            listOf("watch started", "watch restarted"),
            runtime.journal().filter { it.kind == JournalKind.WatchRestart }.map { it.message },
        )
        // Cancel the watch task: the observed list is shared across tests, and on JS a
        // pending task from this test would fire into the next test's cleared list.
        scope.dispose()
    }

    @Test
    fun watchSourceInvalidatesWhenOnlyReadByWatch() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = WatchProbe()

        fun render() {
            runtime.render(scope) { SmokeWatchOnlyReader(probe) }
        }

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (probe.observed != listOf(0)) {
                    delay(10)
                }
            }
        }

        probe.store.value = 1
        assertTrue(runtime.hasPendingInvalidation)
        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (probe.observed != listOf(0, 1)) {
                    delay(10)
                }
            }
        }

        probe.store.value = 2
        assertTrue(runtime.hasPendingInvalidation)
        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (probe.observed != listOf(0, 1, 2)) {
                    delay(10)
                }
            }
        }
        scope.dispose()
    }

    @Test
    fun watchSourceReplacesSubscriptionsWhenDependenciesChange() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = WatchProbe()

        fun render() {
            runtime.render(scope) { SmokeWatchSwitcher(probe) }
        }

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (probe.observed != listOf(0)) {
                    delay(10)
                }
            }
        }

        probe.first.value = 1
        assertTrue(runtime.hasPendingInvalidation)
        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (probe.observed != listOf(0, 1)) {
                    delay(10)
                }
            }
        }

        probe.useFirst.value = false
        assertTrue(runtime.hasPendingInvalidation)
        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (probe.observed != listOf(0, 1, 100)) {
                    delay(10)
                }
            }
        }

        probe.first.value = 2
        assertFalse(runtime.hasPendingInvalidation)
        probe.second.value = 101
        assertTrue(runtime.hasPendingInvalidation)
        scope.dispose()
    }

    @Test
    fun debugWatchLoopStopsSelfRestartingEffectWithTrace() = runTest {
        val runtime = KineticaRuntime(watchLoopRestartLimit = 3)
        val scope = ComponentScope(runtime)
        val probe = WatchProbe()

        fun render() {
            runtime.render(scope) { SmokeWatchSelfRestarting(probe) }
        }

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (probe.observed != listOf(0)) {
                    delay(10)
                }
            }
        }

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (probe.observed != listOf(0, 1)) {
                    delay(10)
                }
            }
        }

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (probe.observed != listOf(0, 1, 2)) {
                    delay(10)
                }
            }
        }

        render()
        delay(50)

        val loop = runtime.journal().single { it.kind == JournalKind.WatchLoop }
        assertEquals("watch loop stopped", loop.message)
        // The effect key is the watch's frame slot ordinal now (state = 0, watch = 1).
        assertEquals("watch:1", loop.attributes["effectKey"])
        assertEquals("4", loop.attributes["restarts"])
        assertEquals("3", loop.attributes["limit"])
        assertEquals("1 -> 2 -> 3", loop.attributes["trace"])
        assertEquals(listOf(0, 1, 2), probe.observed)
        assertEquals(3, probe.countCell!!.value)

        render()
        delay(50)
        assertEquals(listOf(0, 1, 2), probe.observed)
        scope.dispose()
    }

    @Test
    fun contextProvidesValuesAndBackStackIsACell() {
        val Theme = context("light", name = "Theme")
        val stack = BackStack<SmokeTestRoute>(SmokeTestRoute.Home)
        val node = KineticaRuntime().render {
            provide(Theme, "dark") {
                text("${read(Theme)}:${stack.value.last()}")
            }
        }.tree

        assertEquals("dark:Home", node.findText().value)
        stack.push(SmokeTestRoute.Details("42"))
        assertEquals(listOf(SmokeTestRoute.Home, SmokeTestRoute.Details("42")), stack.value)
        assertTrue(stack.pop())
        assertFalse(stack.pop())
    }

    @Test
    fun eachAndLazyEachRejectDuplicateKeysInDebug() {
        val runtime = KineticaRuntime(debug = true)
        val scope = ComponentScope(runtime)

        val eachError = assertFailsWith<IllegalStateException> {
            runtime.render(scope) { SmokeDupEach() }
        }
        assertEquals("Duplicate key: one", eachError.message)

        val lazyError = assertFailsWith<IllegalStateException> {
            runtime.render(scope) { SmokeDupLazyEach() }
        }
        assertEquals("Duplicate key: two", lazyError.message)
        assertEquals(
            listOf("one", "two"),
            runtime.journal().filter { it.message == "duplicate key" }.map { it.attributes["key"] },
        )
    }

    @Test
    fun eachAndLazyEachUseLastDuplicateKeyWithProductionWarnings() {
        val runtime = KineticaRuntime(debug = false)
        val scope = ComponentScope(runtime)

        val eachNode = runtime.render(scope) { SmokeLastWinsEach() }.tree
        assertEquals(listOf("fifth", "third"), eachNode.findTexts().map { it.value })

        val lazyNode = runtime.render(scope) { SmokeLastWinsLazyEach() }.tree
        assertEquals(listOf("beta", "atom"), lazyNode.findTexts().map { it.value })
        assertTrue(runtime.journal().isEmpty())
        assertEquals(
            listOf("f", "a"),
            runtime.warnings().map { it.attributes["key"] },
        )
        assertTrue(runtime.warnings().all { warning ->
            warning.code == "duplicate-key" && warning.message == "duplicate key"
        })
    }

    private sealed interface SmokeTestRoute : Route {
        data object Home : SmokeTestRoute {
            override fun toString(): String = "Home"
        }

        data class Details(val id: String) : SmokeTestRoute
    }
}

private fun Node.findText(): TextNode = when (this) {
    is TextNode -> this
    is FragmentNode -> children.firstNotNullOf { it.findTextOrNull() }
    is HostNode -> children.firstNotNullOf { it.findTextOrNull() }
    is ClientRef -> error("No text node")
    is TemplateNode -> materialize().findText()
}

private fun Node.findTextOrNull(): TextNode? = when (this) {
    is TextNode -> this
    is FragmentNode -> children.firstNotNullOfOrNull { it.findTextOrNull() }
    is HostNode -> children.firstNotNullOfOrNull { it.findTextOrNull() }
    is ClientRef -> null
    is TemplateNode -> materialize().findTextOrNull()
}

private fun Node.findHostByTag(tag: String): HostNode = when (this) {
    is HostNode -> if (this.tag == tag) this else children.firstNotNullOf { it.findHostByTagOrNull(tag) }
    is FragmentNode -> children.firstNotNullOf { it.findHostByTagOrNull(tag) }
    is TextNode -> error("No host node with tag $tag")
    is ClientRef -> error("No host node with tag $tag")
    is TemplateNode -> materialize().findHostByTag(tag)
}

private fun Node.findHostByTagOrNull(tag: String): HostNode? = when (this) {
    is HostNode -> if (this.tag == tag) this else children.firstNotNullOfOrNull { it.findHostByTagOrNull(tag) }
    is FragmentNode -> children.firstNotNullOfOrNull { it.findHostByTagOrNull(tag) }
    is TextNode -> null
    is ClientRef -> null
    is TemplateNode -> materialize().findHostByTagOrNull(tag)
}

private fun Node.findTexts(): List<TextNode> = when (this) {
    is TextNode -> listOf(this)
    is FragmentNode -> children.flatMap { it.findTexts() }
    is HostNode -> children.flatMap { it.findTexts() }
    is ClientRef -> emptyList()
    is TemplateNode -> materialize().findTexts()
}
