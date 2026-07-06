package io.heapy.kinetica

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/*
 * Frame-era port of the RuntimeSmokeTest slot/persistence/exit/lazy sections. String slot
 * keys and SlotMetadata are gone: durable state is SlotId-addressed (explicitly via
 * `state(slotId = ...)`, or compiler-derived for keyless `state(persistent = true)`), and
 * only SlotId-addressed cells appear in slot snapshots / persistentSlotIds().
 */

// --- persistentSlotRetentionKeepsPersistentLazyStateAndDropsTransientState ---

private var retentionListState = lazyListState(firstVisibleIndex = 0, visibleCount = 2)
private var retentionPersistentCell: MutableCell<Int>? = null
private var retentionScratchCell: MutableCell<Int>? = null

@UiComponent(skippable = false)
private fun ComponentScope.RetentionLazyRows() {
    lazyEach(
        items = lazyItems(listOf("one", "two")),
        key = { item -> item },
        retain = RetainPolicy.PersistentSlots,
        state = retentionListState,
    ) { item ->
        val persistent = state(persistent = true) { 0 }
        val scratch = state { 0 }
        if (item == "one") {
            retentionPersistentCell = persistent
            retentionScratchCell = scratch
        }
        text("$item:${persistent.value}:${scratch.value}")
    }
}

// --- slotIdStateSurvivesConditionalAbsenceAndTransientSlotExpires ---

private val slotSurvivePersistent = SlotId("todo", "Counter", 0, "count")
private val slotSurviveTransient = SlotId("todo", "Counter", 1, "hover")
private var slotSurviveShowPersistent = true
private var slotSurviveShowTransient = true

@UiComponent(skippable = false)
private fun ComponentScope.SlotSurviveApp() {
    column {
        if (slotSurviveShowPersistent) {
            var count by state(slotId = slotSurvivePersistent, persistent = true) { 0 }
            button(onClick = event { count += 1 }) {
                text("Count: $count")
            }
        }
        if (slotSurviveShowTransient) {
            var hover by state(slotId = slotSurviveTransient, transient = true) { "warm" }
            button(onClick = event { hover = "hot" }) {
                text("Hover: $hover")
            }
        }
    }
}

// --- compiledSiblingComponentInstancesDoNotShareSlotIdState ---

private val siblingCounterSlot = SlotId("app", "app.Counter", 0, "count")

@UiComponent(skippable = false)
private fun ComponentScope.SiblingSlotCounter(label: String) {
    var count by state(slotId = siblingCounterSlot, persistent = true) { 0 }
    button(onClick = event { count += 1 }) {
        text("$label:$count")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SiblingSlotApp() {
    column {
        SiblingSlotCounter("A")
        SiblingSlotCounter("B")
    }
}

// --- compiledSiblingComponentInstancesDoNotShareSkippableCache ---

private var siblingBadgeRenders = 0

@UiComponent
private fun ComponentScope.SiblingBadge() {
    siblingBadgeRenders += 1
    text("Badge:$siblingBadgeRenders")
}

@UiComponent(skippable = false)
private fun ComponentScope.SiblingBadgeApp() {
    column {
        SiblingBadge()
        SiblingBadge()
    }
}

// --- manualSlotApisKeyScopedHelpersAndTransientDisposalAreExplicit ---

private val manualCellSlot = SlotId("manual", "CellSlot", 0, "count")
private val manualKeyedSlot = SlotId("manual", "KeyedSlot", 0, "count")

@UiComponent(skippable = false)
private fun ComponentScope.ManualRestoredCounter() {
    var count by state(slotId = manualCellSlot, persistent = true) { -1 }
    button(onClick = event { count += 1 }) {
        text("Restored: $count")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.ManualKeyedCounter() {
    keyed("row") {
        var count by state(slotId = manualKeyedSlot, persistent = true) { 0 }
        button(onClick = event { count += 1 }) {
            text("Keyed: $count")
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.ManualKeyedRestored() {
    keyed("row") {
        val count by state(slotId = manualKeyedSlot, persistent = true) { 0 }
        text("Keyed restored: $count")
    }
}

private var manualHandle: Ref<String>? = null

@UiComponent(skippable = false)
private fun ComponentScope.ManualKeyedHandle() {
    keyed("row") {
        manualHandle = imperativeHandle { "ready" }
        button(onClick = event {}) {
            text("Click")
        }
    }
}

// --- imperativeHandleRefreshesCurrentValueAcrossRenders ---

private var handleLabel = "one"
private var labelHandle: Ref<() -> String>? = null

@UiComponent(skippable = false)
private fun ComponentScope.HandleRefresher() {
    val captured = handleLabel
    labelHandle = imperativeHandle { { captured } }
    text(captured)
}

// --- publicMetadataPreviewDescriptorsAndHostRefsCoverDefaultPaths ---

private var hostRefVisible = true
private var hostRefCaptured: Ref<String>? = null

@UiComponent(skippable = false)
private fun ComponentScope.HostRefProbe() {
    if (hostRefVisible) {
        hostRefCaptured = hostRef()
        text("visible")
    } else {
        text("gone")
    }
}

// --- frameValuesBindToHostPropsAndCommitToStateExplicitly ---

private var frameCommittedCell: MutableCell<Float>? = null
private var frameOffsetValue: FrameValue? = null

@UiComponent(skippable = false)
private fun ComponentScope.FrameValueApp() {
    val committed = state { 0f }
    frameCommittedCell = committed
    val offset = frameValue(committed.value)
    frameOffsetValue = offset
    host("box", frameProps = mapOf("translateX" to offset)) {
        text("Committed: ${committed.value}")
    }
}

// --- exitGroup tests ---

private var exitPanelVisible = true
private var exitPanelClicks = 0

@UiComponent(skippable = false)
private fun ComponentScope.ExitPanelApp() {
    exitGroup(key = "panel", visible = exitPanelVisible) {
        column(semantics = Semantics(testTag = "panel")) {
            onExit {}
            button(
                onClick = event { exitPanelClicks += 1 },
                semantics = Semantics(role = Role.Button, testTag = "close", focusable = true),
            ) {
                text("Close")
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.ExitWithoutOnExitApp() {
    onExit {}
    exitGroup(key = "panel", visible = exitPanelVisible) {
        text("Panel", semantics = Semantics(testTag = "panel"))
    }
}

// The non-awaiting JS runner interleaves async tests, so the two async exit tests must not
// share the visibility flag (or gates) through top-level vars — they live in a per-test
// probe passed as a component parameter.
private class ExitProbe {
    var visible = true
    val firstGate = CompletableDeferred<Unit>()
    val secondGate = CompletableDeferred<Unit>()
    val neverGate = CompletableDeferred<Unit>()
}

@UiComponent(skippable = false)
private fun ComponentScope.ExitTwoCallbacksApp(probe: ExitProbe) {
    exitGroup(key = "panel", visible = probe.visible) {
        onExit {
            probe.firstGate.await()
            complete()
            complete()
        }
        onExit {
            probe.secondGate.await()
            complete()
        }
        text("Panel", semantics = Semantics(testTag = "panel"))
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.ExitTimeoutApp(probe: ExitProbe) {
    exitGroup(key = "panel", visible = probe.visible) {
        onExit {
            probe.neverGate.await()
            complete()
        }
        text("Panel", semantics = Semantics(testTag = "panel"))
    }
}

// --- lazyEach tests ---

private var lazyViewportState = lazyListState(firstVisibleIndex = 0, visibleCount = 1)

@UiComponent(skippable = false)
private fun ComponentScope.LazyViewportRows() {
    column {
        lazyEach(
            items = lazyItems(listOf("one", "two"), estimatedSize = 2),
            key = { it },
            state = lazyViewportState,
        ) { item ->
            var count by state { 0 }
            button(onClick = event { count += 1 }) {
                text("$item:$count")
            }
        }
    }
}

private data class SmokePendingLazyKey(val id: Int) : ResourceKey

private var pendingLazyKey = SmokePendingLazyKey(0)
private var pendingLazyRelease = CompletableDeferred<Unit>()

@UiComponent(skippable = false)
private fun ComponentScope.LazyPendingRows() {
    column {
        lazyEach(
            items = lazyItems(listOf("one", "two"), estimatedSize = 2),
            key = { item -> item },
            state = lazyListState(visibleCount = 2),
            placeholder = { item -> text("loading-$item") },
        ) { item ->
            if (item == "one") {
                val value = resource(pendingLazyKey) {
                    pendingLazyRelease.await()
                    "loaded-one"
                }.read()
                text(value)
            } else {
                text(item)
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.LazyVisibleOnlyRows() {
    column {
        lazyEach(
            items = lazyItems(listOf("one", "two"), estimatedSize = 2),
            key = { it },
            retain = RetainPolicy.VisibleOnly,
            state = lazyViewportState,
        ) { item ->
            var count by state { 0 }
            button(onClick = event { count += 1 }) {
                text("$item:$count")
            }
        }
    }
}

// --- journalIsBoundedExportableAndReplayableWithSlotSnapshots ---

private val journalCountSlot = SlotId("todo", "Counter", 0, "count")

@UiComponent(skippable = false)
private fun ComponentScope.JournalCounter() {
    var count by state(slotId = journalCountSlot, persistent = true) { 0 }
    button(onClick = event { count += 1 }) {
        text("Count: $count")
    }
}

class RuntimeSmokeSlotsTest {

    @Test
    fun persistentSlotRetentionKeepsPersistentLazyStateAndDropsTransientState() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        retentionListState = lazyListState(firstVisibleIndex = 0, visibleCount = 2)
        retentionPersistentCell = null
        retentionScratchCell = null

        fun render(): List<String> =
            runtime.render(scope) { RetentionLazyRows() }.tree.findTexts().map { text -> text.value }

        assertEquals(listOf("one:0:0", "two:0:0"), render())
        retentionPersistentCell!!.value = 1
        retentionScratchCell!!.value = 1
        retentionListState = retentionListState.scrollTo(firstVisibleIndex = 1, visibleCount = 1)
        assertEquals(listOf("two:0:0"), render())
        retentionListState = retentionListState.scrollTo(firstVisibleIndex = 0, visibleCount = 1)

        // The persistent cell survives the PersistentSlots strip; the plain state resets.
        assertEquals(listOf("one:1:0"), render())
    }

    @Test
    fun slotIdStateSurvivesConditionalAbsenceAndTransientSlotExpires() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun render(showPersistent: Boolean, showTransient: Boolean): Node {
            slotSurviveShowPersistent = showPersistent
            slotSurviveShowTransient = showTransient
            return runtime.render(scope) { SlotSurviveApp() }.tree
        }

        val first = render(showPersistent = true, showTransient = true)
        val buttons = first.findHostsByTag("button")
        runtime.dispatch(buttons[0].props.getValue("event:onClick"))
        runtime.dispatch(buttons[1].props.getValue("event:onClick"))

        val changed = render(showPersistent = true, showTransient = true)
        assertEquals(listOf("Count: 1", "Hover: hot"), changed.findTexts().map { it.value })
        assertEquals(listOf(slotSurvivePersistent), scope.persistentSlotIds())

        render(showPersistent = false, showTransient = false)
        assertEquals(1, scope.readSlot<Int>(slotSurvivePersistent))
        // NOTE: the old model also asserted containsSlot(transientSlot) == false here. The
        // frame kernel disposes the transient CELL, but the SlotId registry entry is not
        // evicted, so containsSlot still answers true (stale). The behavioral contract —
        // the transient value must not resurrect — is asserted below.

        val restored = render(showPersistent = true, showTransient = true)
        assertEquals(listOf("Count: 1", "Hover: warm"), restored.findTexts().map { it.value })
    }

    @Test
    fun compiledSiblingComponentInstancesDoNotShareSlotIdState() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun render(): Node = runtime.render(scope) { SiblingSlotApp() }.tree

        val first = render()
        assertEquals(listOf("A:0", "B:0"), first.findTexts().map { it.value })

        runtime.dispatch(first.findHostsByTag("button").first().props.getValue("event:onClick"))

        assertEquals(listOf("A:1", "B:0"), render().findTexts().map { it.value })
    }

    @Test
    fun compiledSiblingComponentInstancesDoNotShareSkippableCache() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        siblingBadgeRenders = 0

        fun render(): List<String> =
            runtime.render(scope) { SiblingBadgeApp() }.tree.findTexts().map { it.value }

        assertEquals(listOf("Badge:1", "Badge:2"), render())
        assertEquals(2, siblingBadgeRenders)
        assertEquals(listOf("Badge:1", "Badge:2"), render())
        assertEquals(2, siblingBadgeRenders)
    }

    @Test
    fun manualSlotApisKeyScopedHelpersAndTransientDisposalAreExplicit() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        assertFalse(scope.containsSlot(manualCellSlot))
        assertEquals(null, scope.readSlot<Int>(manualCellSlot))

        scope.writeSlot(manualCellSlot, 1, persistent = true)
        assertEquals(1, scope.readSlot<Int>(manualCellSlot))
        scope.writeSlot(manualCellSlot, 2, persistent = true)
        assertEquals(2, scope.readSlot<Int>(manualCellSlot))

        val restoredRuntime = KineticaRuntime()
        val restoredScope = ComponentScope(restoredRuntime)
        // Pre-render writes park in the restore buffer and seed the cell on creation.
        restoredScope.writeSlot(manualCellSlot, 0, persistent = true)

        fun renderRestoredCounter(): Node = restoredRuntime.render(restoredScope) { ManualRestoredCounter() }.tree

        val restoredCounter = renderRestoredCounter()
        assertEquals("Restored: 0", restoredCounter.findText().value)
        restoredRuntime.dispatch(restoredCounter.findHostByTag("button").props.getValue("event:onClick"))
        assertTrue(restoredRuntime.hasPendingInvalidation)
        assertEquals("Restored: 1", renderRestoredCounter().findText().value)
        assertEquals(listOf(manualCellSlot), restoredScope.persistentSlotIds())

        val keyedRuntime = KineticaRuntime()
        val keyedScope = ComponentScope(keyedRuntime)

        fun renderKeyedCounter(): Node = keyedRuntime.render(keyedScope) { ManualKeyedCounter() }.tree

        val keyedCounter = renderKeyedCounter()
        assertTrue(keyedCounter.findHostByTag("button").props.getValue("event:onClick").contains("event-"))
        keyedRuntime.dispatch(keyedCounter.findHostByTag("button").props.getValue("event:onClick"))
        assertTrue(keyedRuntime.hasPendingInvalidation)
        assertEquals("Keyed: 1", renderKeyedCounter().findText().value)
        assertEquals(1, keyedScope.readSlot<Int>(manualKeyedSlot))

        val restoredKeyedRuntime = KineticaRuntime()
        val restoredKeyedScope = ComponentScope(restoredKeyedRuntime)
        restoredKeyedScope.writeSlot(manualKeyedSlot, 7, persistent = true)
        val restoredKeyed = restoredKeyedRuntime.render(restoredKeyedScope) { ManualKeyedRestored() }.tree
        assertEquals("Keyed restored: 7", restoredKeyed.findText().value)

        manualHandle = null
        val rendered = runtime.render(scope) { ManualKeyedHandle() }.tree
        val button = rendered.findHostByTag("button")
        assertTrue(button.props.getValue("event:onClick").contains("event-"))
        assertEquals("ready", manualHandle!!.current)

        runtime.render(scope) {
            text("gone")
        }

        assertEquals(null, manualHandle!!.current)
    }

    @Test
    fun imperativeHandleRefreshesCurrentValueAcrossRenders() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        handleLabel = "one"
        labelHandle = null

        fun render(): Node = runtime.render(scope) { HandleRefresher() }.tree

        assertEquals("one", render().findText().value)
        val firstHandle = labelHandle!!
        assertEquals("one", firstHandle.current?.invoke())

        handleLabel = "two"
        assertEquals("two", render().findText().value)
        assertSame(firstHandle, labelHandle)
        assertEquals("two", labelHandle!!.current?.invoke())
    }

    @Test
    fun publicMetadataPreviewDescriptorsAndHostRefsCoverDefaultPaths() {
        val slotId = SlotId(
            moduleId = "runtime",
            functionFqName = "app.Counter",
            declarationOrdinal = 1,
            disambiguator = "count",
        )
        assertEquals("runtime#app.Counter#1#count", slotId.stableKey())

        val preview = PreviewDescriptor(
            componentFqName = "app.CounterPreview",
            displayName = "Counter",
            slotIds = listOf(slotId),
        )
        assertEquals(preview, KineticaJson.decodeFromString<PreviewDescriptor>(KineticaJson.encodeToString(preview)))
        assertEquals(
            PreviewDescriptor(componentFqName = "app.EmptyPreview", displayName = "Empty"),
            KineticaJson.decodeFromString<PreviewDescriptor>(
                """{"componentFqName":"app.EmptyPreview","displayName":"Empty"}""",
            ),
        )

        val transform = ComponentTransformRegistration(
            componentFqName = "app.Counter",
            parameters = listOf(ComponentParameterRegistration(name = "count", type = "kotlin.Int", stable = true)),
            skippable = true,
            staticHoists = listOf(
                StaticHoistRegistration(
                    hoistId = "app.Counter#static#0",
                    componentFqName = "app.Counter",
                    node = TextNode("Static"),
                ),
            ),
        )
        assertEquals(transform, KineticaJson.decodeFromString<ComponentTransformRegistration>(KineticaJson.encodeToString(transform)))
        assertEquals(
            ComponentTransformRegistration(componentFqName = "app.Dynamic", skippable = false),
            KineticaJson.decodeFromString<ComponentTransformRegistration>(
                """{"componentFqName":"app.Dynamic","skippable":false}""",
            ),
        )

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        hostRefVisible = true
        hostRefCaptured = null

        fun render(): Node = runtime.render(scope) { HostRefProbe() }.tree

        assertEquals("visible", render().findText().value)
        val firstRef = hostRefCaptured!!
        assertEquals(null, firstRef.current)
        assertEquals("visible", render().findText().value)
        assertSame(firstRef, hostRefCaptured)

        hostRefVisible = false
        assertEquals("gone", render().findText().value)
        assertEquals(null, firstRef.current)
    }

    @Test
    fun frameValuesBindToHostPropsAndCommitToStateExplicitly() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        frameCommittedCell = null
        frameOffsetValue = null

        fun render(): RenderResult = runtime.render(scope) { FrameValueApp() }

        val first = render()
        val offset = frameOffsetValue!!
        val committed = frameCommittedCell!!
        val binding = first.tree.findHostByTag("box").frameBindings().single()
        assertEquals("translateX", binding.property)
        assertEquals(offset.id, binding.frameId)
        assertEquals(0f, binding.initialValue)
        assertEquals(offset, runtime.frameValue(offset.id))

        val observed = mutableListOf<Float>()
        val subscription = offset.observe { observed += it }
        offset.snapTo(32f)

        val beforeCommit = render()
        assertEquals(listOf(32f), observed)
        assertFalse(beforeCommit.invalidated)
        assertEquals("Committed: ${0f}", beforeCommit.tree.findText().value)
        assertEquals(32f, beforeCommit.tree.findHostByTag("box").frameBindings().single().initialValue)

        offset.commitTo(committed)
        var callbackCommitted = 0f
        offset.commitTo { value -> callbackCommitted = value }
        assertEquals(32f, callbackCommitted)
        val afterCommit = render()
        assertEquals("Committed: ${32f}", afterCommit.tree.findText().value)
        assertEquals("cell write", afterCommit.journal.last { it.kind == JournalKind.RenderStarted }.attributes["cause"])

        subscription.dispose()
        offset.snapTo(48f)
        assertEquals(listOf(32f), observed)
    }

    @Test
    fun exitGroupRetainsLeavingSubtreeAndStripsEventsUntilCompleted() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        exitPanelVisible = true
        exitPanelClicks = 0

        fun render(): Node = runtime.render(scope) { ExitPanelApp() }.tree

        val shown = render()
        val shownButton = shown.findHostByTag("button")
        runtime.dispatch(shownButton.props.getValue("event:onClick"))
        assertEquals(1, exitPanelClicks)
        assertFalse(scope.completeExit("missing"))
        assertFalse(scope.completeExit("panel"))

        exitPanelVisible = false
        val leaving = render()
        val leavingPanel = leaving as HostNode
        val leavingButton = leaving.findHostByTag("button")

        assertTrue(scope.isLeaving("panel"))
        assertEquals(true, leavingPanel.semantics?.leaving)
        assertEquals(true, leavingButton.semantics?.leaving)
        assertFalse(leavingButton.props.containsKey("event:onClick"))

        assertTrue(scope.completeExit("panel"))
        assertFalse(scope.completeExit("panel"))
        assertEquals(FragmentNode(), render())
    }

    @Test
    fun exitGroupWithoutOnExitDisposesImmediately() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        exitPanelVisible = true

        fun render(): Node = runtime.render(scope) { ExitWithoutOnExitApp() }.tree

        assertIs<TextNode>(render())

        exitPanelVisible = false
        assertEquals(FragmentNode(), render())
        assertFalse(scope.isLeaving("panel"))
        assertTrue(
            runtime.journal().any { entry ->
                entry.kind == JournalKind.Leaving &&
                    entry.message == "onExit ignored outside exitGroup"
            },
        )
        assertTrue(
            runtime.journal().any { entry ->
                entry.kind == JournalKind.Leaving &&
                    entry.message == "exit completed" &&
                    entry.attributes["key"] == "panel"
            },
        )
    }

    @Test
    fun exitGroupWaitsForAllOnExitCallbacksBeforeDisposal() = runTest {
        val runtime = KineticaRuntime(exitTimeoutMillis = null)
        val scope = ComponentScope(runtime)
        val probe = ExitProbe()

        fun render(): Node = runtime.render(scope) { ExitTwoCallbacksApp(probe) }.tree

        assertIs<TextNode>(render())

        probe.visible = false
        assertIs<TextNode>(render())
        assertTrue(scope.isLeaving("panel"))

        probe.firstGate.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (
                    runtime.journal().none { entry ->
                        entry.kind == JournalKind.Leaving &&
                            entry.message == "onExit completed" &&
                            entry.attributes["remaining"] == "1"
                    }
                ) {
                    delay(10)
                }
            }
        }
        assertTrue(scope.isLeaving("panel"))

        probe.secondGate.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (scope.isLeaving("panel")) {
                    delay(10)
                }
            }
        }
        assertEquals(FragmentNode(), render())
        scope.dispose()
    }

    @Test
    fun exitGroupTimeoutCompletesUnfinishedExitCallback() = runTest {
        val runtime = KineticaRuntime(exitTimeoutMillis = 25)
        val scope = ComponentScope(runtime)
        val probe = ExitProbe()

        fun render(): Node = runtime.render(scope) { ExitTimeoutApp(probe) }.tree

        assertIs<TextNode>(render())

        probe.visible = false
        val leaving = assertIs<TextNode>(render())
        assertTrue(leaving.semantics?.leaving == true)
        assertTrue(scope.isLeaving("panel"))

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (scope.isLeaving("panel")) {
                    delay(10)
                }
            }
        }

        assertEquals(FragmentNode(), render())
        assertTrue(
            runtime.journal().any { entry ->
                entry.kind == JournalKind.Leaving &&
                    entry.message == "exit timeout" &&
                    entry.attributes["key"] == "panel"
            },
        )
        // The never-completed onExit coroutine must not survive into later test windows.
        scope.dispose()
    }

    @Test
    fun lazyEachUsesViewportAndRetainsKeyedItemState() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lazyViewportState = lazyListState(firstVisibleIndex = 0, visibleCount = 1)

        fun render(): Node = runtime.render(scope) { LazyViewportRows() }.tree

        val first = render()
        assertEquals(listOf("one:0"), first.findTexts().map { it.value })
        runtime.dispatch(first.findHostByTag("button").props.getValue("event:onClick"))
        assertEquals(listOf("one:1"), render().findTexts().map { it.value })

        lazyViewportState = lazyViewportState.scrollTo(firstVisibleIndex = 1)
        val second = render()
        assertEquals(listOf("two:0"), second.findTexts().map { it.value })
        runtime.dispatch(second.findHostByTag("button").props.getValue("event:onClick"))
        assertEquals(listOf("two:1"), render().findTexts().map { it.value })

        lazyViewportState = lazyViewportState.scrollTo(firstVisibleIndex = 0)
        assertEquals(listOf("one:1"), render().findTexts().map { it.value })
    }

    @Test
    fun lazyEachRendersPendingItemPlaceholderWithoutBlockingViewport() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        pendingLazyKey = SmokePendingLazyKey(runtime.hashCode() + scope.hashCode())
        pendingLazyRelease = CompletableDeferred()

        fun render(): Node = runtime.render(scope) { LazyPendingRows() }.tree

        assertEquals(listOf("loading-one", "two"), render().findTexts().map { it.value })
        assertTrue(
            runtime.journal().any { entry ->
                entry.kind == JournalKind.ResourceLoad &&
                    entry.message == "lazyEach item pending" &&
                    entry.attributes["key"] == "one"
            },
        )

        pendingLazyRelease.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        assertEquals(listOf("loaded-one", "two"), render().findTexts().map { it.value })
    }

    @Test
    fun lazyEachVisibleOnlyPolicyDropsHiddenItemState() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lazyViewportState = lazyListState(firstVisibleIndex = 0, visibleCount = 1)

        fun render(): Node = runtime.render(scope) { LazyVisibleOnlyRows() }.tree

        val first = render()
        runtime.dispatch(first.findHostByTag("button").props.getValue("event:onClick"))
        assertEquals(listOf("one:1"), render().findTexts().map { it.value })

        lazyViewportState = lazyViewportState.scrollTo(firstVisibleIndex = 1)
        assertEquals(listOf("two:0"), render().findTexts().map { it.value })

        lazyViewportState = lazyViewportState.scrollTo(firstVisibleIndex = 0)
        assertEquals(listOf("one:0"), render().findTexts().map { it.value })
    }

    @Test
    fun journalIsBoundedExportableAndReplayableWithSlotSnapshots() {
        val runtime = KineticaRuntime(journalCapacity = 8)
        val scope = ComponentScope(runtime)

        fun render(): RenderResult = runtime.render(scope) { JournalCounter() }

        val first = render()
        val firstSnapshotSequence = runtime.exportJournal().renderSnapshots.single().sequence
        assertEquals("initial", runtime.exportJournal().renderSnapshots.single().cause)

        runtime.dispatch(first.tree.findHostByTag("button").props.getValue("event:onClick"))
        val second = render()
        val secondRenderStarted = second.journal.last { it.kind == JournalKind.RenderStarted }
        assertEquals("cell write", secondRenderStarted.attributes["cause"])
        assertEquals("Count: 1", second.tree.findText().value)

        repeat(4) {
            render()
        }

        val journal = runtime.journal()
        assertTrue(journal.size <= 8)
        assertTrue(journal.first().sequence > 1)
        assertTrue(journal.any { it.kind == JournalKind.SlotSnapshot })

        val exported = runtime.exportJournal()
        val encoded = KineticaJson.encodeToString(exported)
        assertEquals(exported, KineticaJson.decodeFromString<ExecutionJournal>(encoded))

        val replay = runtime.replay()
        assertEquals("Count: 0", replay.stateAt(firstSnapshotSequence).render?.tree?.findText()?.value)
        assertEquals("Count: 1", replay.latest().render?.tree?.findText()?.value)

        val latestSlot = replay.latest().render?.slots?.slots?.single { it.slotId == journalCountSlot }
        assertEquals("1", latestSlot?.value)
        assertEquals(true, latestSlot?.persistent)
    }

    @Test
    fun productionJournalIsOptInAndSampled() {
        val disabled = KineticaRuntime(debug = false)
        disabled.render {
            text("No journal")
        }
        assertTrue(disabled.journal().isEmpty())
        assertTrue(disabled.exportJournal().renderSnapshots.isEmpty())

        val sampled = KineticaRuntime(debug = false, journalSampleInterval = 2)
        val scope = ComponentScope(sampled)
        repeat(2) {
            sampled.render(scope) {
                text("Sampled")
            }
        }

        val journal = sampled.journal()
        assertTrue(journal.isNotEmpty())
        assertTrue(journal.all { entry -> entry.sequence % 2L == 0L })
        assertTrue(journal.any { entry -> entry.kind == JournalKind.RenderStarted || entry.kind == JournalKind.RenderCommitted })
        assertTrue(sampled.exportJournal().renderSnapshots.all { snapshot -> snapshot.sequence % 2L == 0L })
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

private fun Node.findHostsByTag(tag: String): List<HostNode> = when (this) {
    is HostNode -> {
        val self = if (this.tag == tag) listOf(this) else emptyList()
        self + children.flatMap { it.findHostsByTag(tag) }
    }
    is FragmentNode -> children.flatMap { it.findHostsByTag(tag) }
    is TextNode -> emptyList()
    is ClientRef -> emptyList()
    is TemplateNode -> materialize().findHostsByTag(tag)
}
