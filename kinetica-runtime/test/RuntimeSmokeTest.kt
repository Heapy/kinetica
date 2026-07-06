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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuntimeSmokeTest {
    @Test
    fun stateDerivedAndEventsProduceSerializableNodeValues() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun render(): Node = runtime.render(scope) {
            var count by state(key = "count") { 0 }
            val label by derived { "Count: $count" }

            column(semantics = Semantics(testTag = "root")) {
                text(label)
                button(onClick = event { count += 1 }, semantics = Semantics(role = Role.Button, testTag = "inc")) {
                    text("Increment")
                }
            }
        }.tree

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
        data class Profile(val name: String, val age: Int)

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val profile = store(Profile("Ada", 36))
        var showName = true

        fun render(): RenderResult = runtime.render(scope) {
            if (showName) {
                val name by derived { profile.value.name }
                text("Name: $name")
            } else {
                text("Hidden")
            }
        }

        assertEquals("Name: Ada", render().tree.findText().value)

        profile.value = Profile("Ada", 37)
        assertFalse(runtime.hasPendingInvalidation)
        assertEquals("Name: Ada", render().tree.findText().value)

        profile.value = Profile("Grace", 36)
        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("Name: Grace", render().tree.findText().value)

        showName = false
        assertEquals("Hidden", render().tree.findText().value)

        profile.value = Profile("Katherine", 36)
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

        var payload: Any? = null
        val eventTree = runtime.render {
            button(onClick = { payload = "payload" }) {
                text("Click")
            }
        }.tree as HostNode
        runtime.dispatch("missing-event")
        runtime.dispatch(eventTree.props.getValue("event:onClick"))
        assertEquals("payload", payload)

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

        val source = store(1)
        var computes = 0
        val bucket = ComponentScope().derived {
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
        val count = store(1)

        fun renderDerived(): String =
            derivedRuntime.render(derivedScope) {
                val positive by derived { count.value > 0 }
                text("Positive: $positive")
            }.tree.findText().value

        assertEquals("Positive: true", renderDerived())
        count.value = 2
        assertFalse(derivedRuntime.hasPendingInvalidation)

        count.value = -1
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
    fun persistentSlotRetentionKeepsPersistentLazyStateAndDropsTransientState() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var listState = lazyListState(firstVisibleIndex = 0, visibleCount = 2)
        lateinit var onePersistent: MutableCell<Int>
        lateinit var oneTransient: MutableCell<Int>

        fun render(): List<String> =
            runtime.render(scope) {
                lazyEach(
                    items = lazyItems(listOf("one", "two")),
                    key = { item -> item },
                    retain = RetainPolicy.PersistentSlots,
                    state = listState,
                ) { item ->
                    val persistent = state(key = "persistent", persistent = true) { 0 }
                    val transient = state(key = "transient") { 0 }
                    if (item == "one") {
                        onePersistent = persistent
                        oneTransient = transient
                    }
                    text("$item:${persistent.value}:${transient.value}")
                }
            }.tree.findTexts().map { text -> text.value }

        assertEquals(listOf("one:0:0", "two:0:0"), render())
        onePersistent.value = 1
        oneTransient.value = 1
        listState = listState.scrollTo(firstVisibleIndex = 1, visibleCount = 1)
        assertEquals(listOf("two:0:0"), render())
        listState = listState.scrollTo(firstVisibleIndex = 0, visibleCount = 1)

        assertEquals(listOf("one:1:0"), render())
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
        val suspendKeyedNode = scope.renderSuspendNode {
            suspendKeyed("suspend-key") {
                text("Suspend keyed")
            }
        }
        assertEquals("Suspend keyed", suspendKeyedNode.findText().value)

        val slotId = SlotId("runtime", "Defaults", 0, "manual")
        scope.writeSlot(slotId, "stored")
        assertEquals("stored", scope.readSlot(slotId))
        scope.disposeKeyScope("missing")
        assertEquals(LazyListState(), lazyListState())

        val tree = runtime.render(scope) {
            fragment {
                text("Fragment child")
            }
            exitGroup(key = "empty", visible = true) {
            }
        }.tree

        assertEquals(listOf("Fragment child"), tree.findTexts().map { text -> text.value })
    }

    @Test
    fun inputAndCheckboxDslCoverNullPayloadAndPassiveEdges() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var draft = "initial"

        fun render(): HostNode =
            runtime.render(scope) {
                column {
                    textInput(
                        value = draft,
                        onInput = event<String> { value -> draft = value },
                    )
                    checkbox(checked = true)
                }
            }.tree as HostNode

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

        val directionTree = KineticaRuntime().render {
            assertEquals(LayoutDirection.Ltr, currentLayoutDirection())
            provideLayoutDirection(LayoutDirection.Rtl) {
                row {
                    text("rtl")
                }
            }
            textInput(value = "passive")
            host("empty")
        }.tree
        assertEquals("Rtl", directionTree.findHostByTag("row").props["direction"])
        val passiveInput = directionTree.findHostByTag("textInput")
        assertEquals(mapOf("value" to "passive"), passiveInput.props)
        assertEquals(emptyList(), directionTree.findHostByTag("empty").children)
    }

    @Test
    fun runtimeRenderSuspendCommitsSuspendComponentNodes() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        val first = runtime.renderSuspend(scope) {
            delay(10)
            var count by state(key = "count") { 0 }
            button(onClick = event { count += 1 }) {
                text("Count: $count")
            }
        }

        assertEquals("Count: 0", first.tree.findText().value)
        assertEquals("initial", first.journal.last { it.kind == JournalKind.RenderStarted }.attributes["cause"])

        runtime.dispatch(first.tree.findHostByTag("button").props.getValue("event:onClick"))
        val second = runtime.renderSuspend(scope) {
            delay(10)
            var count by state(key = "count") { 0 }
            button(onClick = event { count += 1 }) {
                text("Count: $count")
            }
        }

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
        // A write to a cell the factory never read must NOT defeat the skip (the old
        // global stateWriteVersion guard did; writes to cells the factory DOES read are
        // covered by skippableNodeRerendersWhenHoistedStoreChanges).
        val cell = scope.state(key = "counter") { 0 }
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

        fun render(): HostNode =
            runtime.render(scope) {
                var count by state(key = "count") { 0 }

                column {
                    text("Count: $count")
                    button(onClick = event { count += 1 }) {
                        text("Increment")
                    }
                }
            }.tree as HostNode

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

        fun render(): HostNode =
            runtime.render(scope) {
                var count by state(key = "count") { 0 }

                button(onClick = { count += 1 }) {
                    text("Count: $count")
                }
            }.tree as HostNode

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
        var clicks = 0

        fun render(rows: List<Int>): HostNode =
            runtime.render(scope) {
                column {
                    each(rows, key = { it }) { row ->
                        button(onClick = { clicks += 1 }) {
                            text("Row $row")
                        }
                    }
                }
            }.tree as HostNode

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
        assertEquals(0, clicks)

        // disposing the scope releases everything it registered
        scope.dispose()
        runtime.dispatch(remainingId)
        assertEquals(0, clicks)
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
        val started = CompletableDeferred<Unit>()
        var starts = 0
        var startsObservedDuringRender = -1

        fun render() {
            runtime.render(scope) {
                launchEffect {
                    starts += 1
                    started.complete(Unit)
                }
                startsObservedDuringRender = starts
                text("Effect")
            }
        }

        render()
        assertEquals(0, startsObservedDuringRender)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                started.await()
            }
        }
        assertEquals(1, starts)

        render()
        delay(50)
        assertEquals(1, starts)
        assertEquals(1, startsObservedDuringRender)
    }

    @Test
    fun launchEffectIsCancelledWhenDeclarationLeavesRender() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val started = CompletableDeferred<Unit>()
        val disposed = CompletableDeferred<Unit>()
        var visible = true

        fun render(): Node =
            runtime.render(scope) {
                if (visible) {
                    launchEffect {
                        started.complete(Unit)
                        awaitDispose { disposed.complete(Unit) }
                    }
                }
                text("Visible: $visible")
            }.tree

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                started.await()
            }
        }

        visible = false
        render()

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                disposed.await()
            }
        }
    }

    @Test
    fun launchEffectAwaitDisposeDefaultCleanupCancelsCleanly() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val started = CompletableDeferred<Unit>()
        var visible = true

        fun render(): Node =
            runtime.render(scope) {
                if (visible) {
                    launchEffect {
                        started.complete(Unit)
                        awaitDispose()
                    }
                }
                text("Visible: $visible")
            }.tree

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                started.await()
            }
        }

        visible = false
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
        val started = CompletableDeferred<Unit>()
        val disposed = CompletableDeferred<Unit>()
        var listState = lazyListState(firstVisibleIndex = 0, visibleCount = 1)
        lateinit var retainedRef: Ref<String>

        fun render() {
            runtime.render(scope) {
                lazyEach(
                    items = lazyItems(listOf("one", "two")),
                    key = { item -> item },
                    retain = RetainPolicy.VisibleOnly,
                    state = listState,
                ) { item ->
                    if (item == "one") {
                        retainedRef = imperativeHandle { "handle" }
                        launchEffect {
                            started.complete(Unit)
                            awaitDispose { disposed.complete(Unit) }
                        }
                    }
                    text(item)
                }
            }
        }

        render()
        assertEquals("handle", retainedRef.current)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                started.await()
            }
        }

        listState = listState.scrollTo(firstVisibleIndex = 1)
        render()

        assertEquals(null, retainedRef.current)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                disposed.await()
            }
        }
    }

    @Test
    fun watchEvaluatesAfterCommitsAndDoesNotDuplicateAcrossRenders() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var count: MutableCell<Int>
        val observed = mutableListOf<Int>()

        fun render() {
            runtime.render(scope) {
                count = state(key = "count") { 0 }
                watch({ count.value }) { value ->
                    observed += value
                }
                text("Count: ${count.value}")
            }
        }

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (observed != listOf(0)) {
                    delay(10)
                }
            }
        }

        render()
        delay(50)
        assertEquals(listOf(0), observed)

        count.value = 1
        delay(50)
        assertEquals(listOf(0), observed)

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (observed != listOf(0, 1)) {
                    delay(10)
                }
            }
        }

        render()
        delay(50)
        assertEquals(listOf(0, 1), observed)
        assertEquals(
            listOf("watch started", "watch restarted"),
            runtime.journal().filter { it.kind == JournalKind.WatchRestart }.map { it.message },
        )
    }

    @Test
    fun watchSourceInvalidatesWhenOnlyReadByWatch() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val count = store(0)
        val observed = mutableListOf<Int>()

        fun render() {
            runtime.render(scope) {
                watch({ count.value }) { value ->
                    observed += value
                }
                text("Watching")
            }
        }

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (observed != listOf(0)) {
                    delay(10)
                }
            }
        }

        count.value = 1
        assertTrue(runtime.hasPendingInvalidation)
        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (observed != listOf(0, 1)) {
                    delay(10)
                }
            }
        }

        count.value = 2
        assertTrue(runtime.hasPendingInvalidation)
        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (observed != listOf(0, 1, 2)) {
                    delay(10)
                }
            }
        }
    }

    @Test
    fun watchSourceReplacesSubscriptionsWhenDependenciesChange() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val useFirst = store(true)
        val first = store(0)
        val second = store(100)
        val observed = mutableListOf<Int>()

        fun render() {
            runtime.render(scope) {
                watch(
                    source = {
                        if (useFirst.value) {
                            first.value
                        } else {
                            second.value
                        }
                    },
                ) { value ->
                    observed += value
                }
                text("Watching")
            }
        }

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (observed != listOf(0)) {
                    delay(10)
                }
            }
        }

        first.value = 1
        assertTrue(runtime.hasPendingInvalidation)
        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (observed != listOf(0, 1)) {
                    delay(10)
                }
            }
        }

        useFirst.value = false
        assertTrue(runtime.hasPendingInvalidation)
        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (observed != listOf(0, 1, 100)) {
                    delay(10)
                }
            }
        }

        first.value = 2
        assertFalse(runtime.hasPendingInvalidation)
        second.value = 101
        assertTrue(runtime.hasPendingInvalidation)
    }

    @Test
    fun debugWatchLoopStopsSelfRestartingEffectWithTrace() = runTest {
        val runtime = KineticaRuntime(watchLoopRestartLimit = 3)
        val scope = ComponentScope(runtime)
        lateinit var count: MutableCell<Int>
        val observed = mutableListOf<Int>()

        fun render() {
            runtime.render(scope) {
                count = state(key = "count") { 0 }
                watch({ count.value }) { value ->
                    observed += value
                    count.value = value + 1
                }
                text("Count: ${count.value}")
            }
        }

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (observed != listOf(0)) {
                    delay(10)
                }
            }
        }

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (observed != listOf(0, 1)) {
                    delay(10)
                }
            }
        }

        render()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (observed != listOf(0, 1, 2)) {
                    delay(10)
                }
            }
        }

        render()
        delay(50)

        val loop = runtime.journal().single { it.kind == JournalKind.WatchLoop }
        assertEquals("watch loop stopped", loop.message)
        assertEquals("effect-0:watch", loop.attributes["effectKey"])
        assertEquals("4", loop.attributes["restarts"])
        assertEquals("3", loop.attributes["limit"])
        assertEquals("1 -> 2 -> 3", loop.attributes["trace"])
        assertEquals(listOf(0, 1, 2), observed)
        assertEquals(3, count.value)

        render()
        delay(50)
        assertEquals(listOf(0, 1, 2), observed)
    }

    @Test
    fun contextProvidesValuesAndBackStackIsACell() {
        val Theme = context("light", name = "Theme")
        val stack = BackStack<TestRoute>(TestRoute.Home)
        val node = KineticaRuntime().render {
            provide(Theme, "dark") {
                text("${read(Theme)}:${stack.value.last()}")
            }
        }.tree

        assertEquals("dark:Home", node.findText().value)
        stack.push(TestRoute.Details("42"))
        assertEquals(listOf(TestRoute.Home, TestRoute.Details("42")), stack.value)
        assertTrue(stack.pop())
        assertFalse(stack.pop())
    }

    @Test
    fun eachAndLazyEachRejectDuplicateKeysInDebug() {
        val runtime = KineticaRuntime(debug = true)
        val scope = ComponentScope(runtime)

        val eachError = assertFailsWith<IllegalStateException> {
            runtime.render(scope) {
                each(listOf("one", "one"), key = { item -> item }) { item ->
                    text(item)
                }
            }
        }
        assertEquals("Duplicate key: one", eachError.message)

        val lazyError = assertFailsWith<IllegalStateException> {
            runtime.render(scope) {
                lazyEach(
                    items = lazyItems(listOf("two", "two")),
                    key = { item -> item },
                ) { item ->
                    text(item)
                }
            }
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

        val eachNode = runtime.render(scope) {
            each(listOf("first", "fifth", "third"), key = { item -> item.first() }) { item ->
                text(item)
            }
        }.tree
        assertEquals(listOf("fifth", "third"), eachNode.findTexts().map { it.value })

        val lazyNode = runtime.render(scope) {
            lazyEach(
                items = lazyItems(listOf("alpha", "beta", "atom")),
                key = { item -> item.first() },
                state = lazyListState(visibleCount = 2),
            ) { item ->
                text(item)
            }
        }.tree
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

    @Test
    fun resourcesAreSingleFlightAndActionsInvalidateKeys() = runTest {
        var loads = 0
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var resourceA: Resource<List<String>>
        lateinit var resourceB: Resource<List<String>>
        lateinit var add: Action<String, String>
        lateinit var echo: Action<String, String>

        runtime.render(scope) {
            resourceA = resource(TodosKey) {
                loads += 1
                listOf("one")
            }
            resourceB = resource(TodosKey) {
                loads += 1
                listOf("two")
            }
            add = action(invalidates = { listOf(TodosKey) }) { title: String -> title }
            echo = action { title: String -> "echo:$title" }
        }

        assertEquals(listOf("one"), resourceA.await())
        assertEquals(listOf("one"), resourceB.await())
        assertEquals(1, loads)
        assertEquals("new", add("new"))
        assertEquals("echo:new", echo("new"))
        assertEquals(listOf("one"), resourceA.await())
        assertEquals(2, loads)
    }

    @Test
    fun resourceReadReportsLoadingReadyFailedAndInvalidatedStates() = runTest {
        data class ReadStateKey(val id: Int) : ResourceKey

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val key = ReadStateKey(runtime.hashCode() + scope.hashCode())
        var release = CompletableDeferred<String>()
        lateinit var current: Resource<String>

        fun render() {
            runtime.render(scope) {
                current = resource(key) {
                    release.await()
                }
            }
        }

        render()
        assertIs<ResourceState.Idle>(current.state)
        val firstPending = assertFailsWith<RuntimeException> {
            current.read()
        }
        assertEquals("Resource is pending: $key", firstPending.message)
        assertIs<ResourceState.Loading>(current.state)
        val secondPending = assertFailsWith<RuntimeException> {
            current.read()
        }
        assertEquals("Resource is pending: $key", secondPending.message)

        release.complete("ready")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        val readyState = assertIs<ResourceState.Ready<String>>(current.state)
        assertEquals("ready", readyState.value)
        assertEquals("ready", current.read())
        assertIs<ResourceState.Ready<String>>(current.state)

        current.invalidate()
        assertIs<ResourceState.Idle>(current.state)

        release = CompletableDeferred()
        render()
        val thirdPending = assertFailsWith<RuntimeException> {
            current.read()
        }
        assertEquals("Resource is pending: $key", thirdPending.message)
        release.completeExceptionally(IllegalStateException("unavailable"))
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        val failure = assertFailsWith<IllegalStateException> {
            current.read()
        }
        assertEquals("unavailable", failure.message)
        val failedState = assertIs<ResourceState.Failed>(current.state)
        assertEquals("unavailable", failedState.error.message)
    }

    @Test
    fun resourceCancellationDoesNotCachePermanentFailure() = runTest {
        data class AwaitCancellationKey(val id: Int) : ResourceKey
        data class ReadCancellationKey(val id: Int) : ResourceKey

        val awaitRuntime = KineticaRuntime()
        val awaitScope = ComponentScope(awaitRuntime)
        val awaitKey = AwaitCancellationKey(awaitRuntime.hashCode() + awaitScope.hashCode())
        var awaitLoads = 0
        lateinit var awaitResource: Resource<String>

        awaitRuntime.render(awaitScope) {
            awaitResource = resource(awaitKey) {
                awaitLoads += 1
                if (awaitLoads == 1) {
                    throw CancellationException("transient await")
                }
                "await-ready"
            }
        }

        val awaitCancellation = assertFailsWith<CancellationException> {
            awaitResource.await()
        }
        assertEquals("transient await", awaitCancellation.message)
        assertIs<ResourceState.Idle>(awaitResource.state)
        assertEquals("await-ready", awaitResource.await())
        assertEquals(2, awaitLoads)

        val readRuntime = KineticaRuntime()
        val readScope = ComponentScope(readRuntime)
        val readKey = ReadCancellationKey(readRuntime.hashCode() + readScope.hashCode())
        var readLoads = 0

        fun renderRead(): Node = readRuntime.render(readScope) {
            loadingBoundary(fallback = { text("Loading") }) {
                val value = resource(readKey) {
                    readLoads += 1
                    if (readLoads == 1) {
                        throw CancellationException("transient read")
                    }
                    "read-ready"
                }.read()
                text(value)
            }
        }.tree

        assertEquals("Loading", renderRead().findText().value)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                readRuntime.awaitIdle()
            }
        }

        assertTrue(readRuntime.hasPendingInvalidation)
        assertEquals("Loading", renderRead().findText().value)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                readRuntime.awaitIdle()
            }
        }
        assertEquals("read-ready", renderRead().findText().value)
        assertEquals(2, readLoads)
    }

    @Test
    fun resourcesShareInFlightAwaitsAndIgnoreStaleCompletionAfterInvalidation() = runTest {
        data class InFlightKey(val id: Int) : ResourceKey

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val key = InFlightKey(runtime.hashCode() + scope.hashCode())
        var gate = CompletableDeferred<String>()
        var loads = 0
        lateinit var first: Resource<String>
        lateinit var second: Resource<String>

        fun render() {
            runtime.render(scope) {
                first = resource(key) {
                    loads += 1
                    gate.await()
                }
                second = resource(key) {
                    loads += 1
                    gate.await()
                }
            }
        }

        render()
        var firstValue = ""
        var secondValue = ""
        val firstAwait = launch { firstValue = first.await() }
        delay(1)
        assertEquals(1, loads)

        val secondAwait = launch { secondValue = second.await() }
        delay(1)
        assertEquals(1, loads)

        gate.complete("shared")
        joinAll(firstAwait, secondAwait)
        assertEquals("shared", firstValue)
        assertEquals("shared", secondValue)

        first.invalidate()
        assertIs<ResourceState.Idle>(first.state)
        gate = CompletableDeferred()
        render()
        assertFailsWith<RuntimeException> {
            first.read()
        }
        assertIs<ResourceState.Loading>(first.state)
        first.invalidate()
        assertIs<ResourceState.Idle>(first.state)
        gate.complete("stale-success")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        assertIs<ResourceState.Idle>(first.state)

        gate = CompletableDeferred()
        render()
        assertFailsWith<RuntimeException> {
            first.read()
        }
        first.invalidate()
        gate.completeExceptionally(IllegalStateException("stale-failure"))
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        assertIs<ResourceState.Idle>(first.state)
        assertEquals(3, loads)
    }

    @Test
    fun staleResourceCompletionCannotOverwriteFreshLoadAfterInvalidation() = runTest {
        data class StaleOverwriteKey(val id: Int) : ResourceKey

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val key = StaleOverwriteKey(runtime.hashCode() + scope.hashCode())
        val staleGate = CompletableDeferred<String>()
        var activeGate = staleGate
        var loads = 0
        lateinit var current: Resource<String>

        suspend fun awaitLoads(expected: Int) {
            withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    while (loads < expected) {
                        delay(10)
                    }
                }
            }
        }

        fun render(): Node = runtime.render(scope) {
            loadingBoundary(fallback = { text("Loading") }) {
                val value = resource(key) {
                    loads += 1
                    activeGate.await()
                }.also { current = it }.read()
                text(value)
            }
        }.tree

        assertEquals("Loading", render().findText().value)
        awaitLoads(1)
        assertEquals(1, loads)

        current.invalidate()
        activeGate = CompletableDeferred()
        assertEquals("Loading", render().findText().value)
        awaitLoads(2)
        assertEquals(2, loads)

        activeGate.complete("fresh")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (!runtime.hasPendingInvalidation) {
                    delay(10)
                }
            }
        }
        assertEquals("fresh", render().findText().value)

        staleGate.complete("stale")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        assertEquals("fresh", render().findText().value)
        assertEquals(2, loads)
    }

    @Test
    fun staleResourceFailuresAndCancellationsCannotOverwriteFreshLoads() = runTest {
        data class StaleTerminalKey(val id: Int) : ResourceKey

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val key = StaleTerminalKey(runtime.hashCode() + scope.hashCode())
        var activeGate = CompletableDeferred<String>()
        var loads = 0
        lateinit var current: Resource<String>

        suspend fun awaitLoads(expected: Int) {
            withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    while (loads < expected) {
                        delay(10)
                    }
                }
            }
        }

        suspend fun yieldDefaultDispatcher() {
            withContext(Dispatchers.Default) {
                delay(50)
            }
        }

        fun render(): Node = runtime.render(scope) {
            loadingBoundary(fallback = { text("Loading") }) {
                val value = resource(key) {
                    loads += 1
                    activeGate.await()
                }.also { current = it }.read()
                text(value)
            }
        }.tree

        assertEquals("Loading", render().findText().value)
        awaitLoads(1)

        val staleFailure = activeGate
        current.invalidate()
        activeGate = CompletableDeferred()
        assertEquals("Loading", render().findText().value)
        awaitLoads(2)

        staleFailure.completeExceptionally(IllegalStateException("stale failure"))
        yieldDefaultDispatcher()
        assertIs<ResourceState.Loading>(current.state)

        activeGate.complete("fresh")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        assertEquals("fresh", render().findText().value)

        current.invalidate()
        activeGate = CompletableDeferred()
        assertEquals("fresh", render().findText().value)
        assertIs<ResourceState.Loading>(current.state)
        awaitLoads(3)

        val staleCancellation = activeGate
        current.invalidate()
        activeGate = CompletableDeferred()
        assertEquals("fresh", render().findText().value)
        assertIs<ResourceState.Loading>(current.state)
        awaitLoads(4)

        staleCancellation.completeExceptionally(CancellationException("stale cancellation"))
        yieldDefaultDispatcher()
        assertIs<ResourceState.Loading>(current.state)

        activeGate.complete("fresh after cancellation")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        assertEquals("fresh after cancellation", render().findText().value)
        assertEquals(4, loads)
    }

    @Test
    fun topLevelInvalidateNotifiesLiveResourcesAndReloadsOnNextRender() = runTest {
        data class LiveProductsKey(val id: Int) : ResourceKey

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val key = LiveProductsKey(runtime.hashCode() + scope.hashCode())
        var loads = 0

        fun render(): Node = runtime.render(scope) {
            loadingBoundary(fallback = { text("Loading") }) {
                val value = resource(key) {
                    loads += 1
                    "Products $loads"
                }.read()
                text(value)
            }
        }.tree

        assertEquals("Loading", render().findText().value)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        assertEquals("Products 1", render().findText().value)

        invalidate(key)
        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("Products 1", render().findText().value)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        assertEquals("Products 2", render().findText().value)
    }

    @Test
    fun predicateInvalidateOnlyReloadsMatchingLiveResources() = runTest {
        data class PredicateKey(val id: String, val salt: Int) : ResourceKey

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val firstKey = PredicateKey("first", runtime.hashCode())
        val secondKey = PredicateKey("second", scope.hashCode())
        var firstLoads = 0
        var secondLoads = 0
        lateinit var first: Resource<String>
        lateinit var second: Resource<String>

        runtime.render(scope) {
            first = resource(firstKey) {
                firstLoads += 1
                "first-$firstLoads"
            }
            second = resource(secondKey) {
                secondLoads += 1
                "second-$secondLoads"
            }
        }

        assertEquals("first-1", first.await())
        assertEquals("second-1", second.await())

        invalidate { key -> key is PredicateKey && key.id == "second" }

        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("first-1", first.await())
        assertEquals("second-2", second.await())
        assertEquals(1, firstLoads)
        assertEquals(2, secondLoads)
        assertTrue(
            runtime.journal().any { entry ->
                entry.kind == JournalKind.ResourceInvalidated &&
                    entry.attributes["key"] == secondKey.toString()
            },
        )
    }

    @Test
    fun componentScopedResourcesAreIsolatedByComponentInstance() = runTest {
        val runtime = KineticaRuntime()
        val firstScope = ComponentScope(runtime, instanceId = "first")
        val secondScope = ComponentScope(runtime, instanceId = "second")
        lateinit var first: Resource<String>
        lateinit var second: Resource<String>

        runtime.render(firstScope) {
            first = resource(ComponentScopedKey, scope = CacheScope.Component) {
                "first"
            }
        }
        runtime.render(secondScope) {
            second = resource(ComponentScopedKey, scope = CacheScope.Component) {
                "second"
            }
        }

        assertEquals("first", first.await())
        assertEquals("second", second.await())
    }

    @Test
    fun componentScopedResourceLoadIsCancelledWhenSlotIsDisposed() = runTest {
        data class LongRunningComponentKey(val id: Int) : ResourceKey

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val key = LongRunningComponentKey(runtime.hashCode() + scope.hashCode())
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val never = CompletableDeferred<String>()
        var visible = true

        fun render(): Node = runtime.render(scope) {
            if (visible) {
                loadingBoundary(fallback = { text("Loading") }) {
                    val value = resource(key, scope = CacheScope.Component) {
                        started.complete(Unit)
                        try {
                            never.await()
                        } catch (error: CancellationException) {
                            cancelled.complete(Unit)
                            throw error
                        }
                    }.read()
                    text(value)
                }
            } else {
                text("Closed")
            }
        }.tree

        try {
            assertEquals("Loading", render().findText().value)
            withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    started.await()
                }
            }

            visible = false
            assertEquals("Closed", render().findText().value)

            withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    runtime.awaitIdle()
                    cancelled.await()
                }
            }
        } finally {
            scope.dispose()
            runtime.dispose()
        }
    }

    @Test
    fun resourceCacheEvictionMatchesScopeLifetime() = runTest {
        data class TemporaryScopedKey(val scope: String, val id: Int) : ResourceKey

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val appKey = TemporaryScopedKey("app", runtime.hashCode() + scope.hashCode())
        var appLoads = 0
        var appVisible = true
        lateinit var appResource: Resource<String>

        fun renderApp() {
            runtime.render(scope) {
                if (appVisible) {
                    appResource = resource(appKey, scope = CacheScope.App) {
                        appLoads += 1
                        "app-$appLoads"
                    }
                } else {
                    text("gone")
                }
            }
        }

        renderApp()
        assertEquals("app-1", appResource.await())

        appVisible = false
        renderApp()

        appVisible = true
        renderApp()
        assertEquals("app-1", appResource.await())
        assertEquals(1, appLoads)

        val componentKey = TemporaryScopedKey("component", runtime.hashCode() + scope.hashCode())
        var componentLoads = 0
        var componentVisible = true
        lateinit var componentResource: Resource<String>

        fun renderComponent() {
            runtime.render(scope) {
                if (componentVisible) {
                    componentResource = resource(componentKey, scope = CacheScope.Component) {
                        componentLoads += 1
                        "component-$componentLoads"
                    }
                } else {
                    text("gone")
                }
            }
        }

        renderComponent()
        assertEquals("component-1", componentResource.await())

        componentVisible = false
        renderComponent()

        componentVisible = true
        renderComponent()
        assertEquals("component-2", componentResource.await())
        assertEquals(2, componentLoads)

        val requestKey = TemporaryScopedKey("request", runtime.hashCode() + scope.hashCode())
        var requestLoads = 0
        var requestVisible = true
        lateinit var requestResource: Resource<String>

        fun renderRequest() {
            runtime.render(scope) {
                if (requestVisible) {
                    requestResource = resource(requestKey, scope = CacheScope.Request) {
                        requestLoads += 1
                        "request-$requestLoads"
                    }
                } else {
                    text("gone")
                }
            }
        }

        renderRequest()
        assertEquals("request-1", requestResource.await())

        requestVisible = false
        renderRequest()

        requestVisible = true
        renderRequest()
        assertEquals("request-2", requestResource.await())
        assertEquals(2, requestLoads)
    }

    @Test
    fun appResourcesAreIsolatedByRuntimeInstance() = runTest {
        data class RuntimeAppKey(val id: Int) : ResourceKey

        val key = RuntimeAppKey(Any().hashCode())
        val firstRuntime = KineticaRuntime()
        val firstScope = ComponentScope(firstRuntime)
        val secondRuntime = KineticaRuntime()
        val secondScope = ComponentScope(secondRuntime)
        var firstLoads = 0
        var secondLoads = 0
        lateinit var first: Resource<String>
        lateinit var second: Resource<String>

        firstRuntime.render(firstScope) {
            first = resource(key, scope = CacheScope.App) {
                firstLoads += 1
                "first-$firstLoads"
            }
        }
        secondRuntime.render(secondScope) {
            second = resource(key, scope = CacheScope.App) {
                secondLoads += 1
                "second-$secondLoads"
            }
        }

        assertEquals("first-1", first.await())
        assertEquals("second-1", second.await())
        assertEquals(1, firstLoads)
        assertEquals(1, secondLoads)
    }

    @Test
    fun appResourceCacheExpiresByRuntimeVirtualTimeTtl() = runTest {
        val runtime = KineticaRuntime(appResourceTtlMillis = 100)
        val scope = ComponentScope(runtime)
        lateinit var orders: Resource<Int>
        var loads = 0

        fun render() {
            runtime.render(scope) {
                orders = resource(AppTtlKey, scope = CacheScope.App) {
                    loads += 1
                    loads
                }
            }
        }

        render()
        assertEquals(1, orders.await())
        assertEquals(1, loads)

        render()
        assertEquals(1, orders.await())
        assertEquals(1, loads)

        runtime.advanceVirtualTimeBy(99)
        render()
        assertEquals(1, orders.await())
        assertEquals(1, loads)

        runtime.advanceVirtualTimeBy(1)
        render()
        assertEquals(2, orders.await())
        assertEquals(2, loads)
    }

    @Test
    fun loadingBoundaryFallsBackRetainsPreviousUiAndResumesResourceReads() = runTest {
        var gate = CompletableDeferred<String>()
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var currentResource: Resource<String>

        fun render(): Node = runtime.render(scope) {
            loadingBoundary(fallback = { text("Loading") }) {
                currentResource = resource(AsyncBoundaryKey) {
                    gate.await()
                }
                text("Value: ${currentResource.read()}")
            }
        }.tree

        assertEquals("Loading", render().findText().value)
        gate.complete("one")
        withTimeout(2_000) {
            while (render().findText().value != "Value: one") {
                delay(10)
            }
        }

        gate = CompletableDeferred()
        currentResource.invalidate()
        assertEquals("Value: one", render().findText().value)

        gate.complete("two")
        withTimeout(2_000) {
            while (render().findText().value != "Value: two") {
                delay(10)
            }
        }
    }

    @Test
    fun errorBoundaryRethrowsPendingResourcesToNearestLoadingBoundary() = runTest {
        data class ErrorBoundaryPendingKey(val id: Int) : ResourceKey

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val key = ErrorBoundaryPendingKey(runtime.hashCode() + scope.hashCode())
        val release = CompletableDeferred<String>()

        fun render(): Node = runtime.render(scope) {
            loadingBoundary(fallback = { text("Loading") }) {
                errorBoundary(
                    fallback = { error, _, _ -> text("Error: ${error.message}") },
                ) {
                    val value = resource(key) {
                        release.await()
                    }.read()
                    text("Value: $value")
                }
            }
        }.tree

        assertEquals("Loading", render().findText().value)

        release.complete("ready")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        assertEquals("Value: ready", render().findText().value)
        assertFalse(runtime.journal().any { it.kind == JournalKind.BoundaryError })
    }

    @Test
    fun errorBoundaryRetryClearsCapturedErrorsAndCancellationEscapes() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var fail = true
        lateinit var retry: BoundaryRetry

        fun render(): Node = runtime.render(scope) {
            errorBoundary(
                fallback = { error, _, boundaryRetry ->
                    retry = boundaryRetry
                    text("Error: ${error.message}")
                },
            ) {
                if (fail) {
                    error("boom")
                }
                text("Recovered")
                text("Again")
            }
        }.tree

        assertEquals("Error: boom", render().findText().value)
        fail = false
        retry.retry()
        assertEquals(listOf("Recovered", "Again"), render().findTexts().map { it.value })

        val emptyTree = KineticaRuntime().render {
            errorBoundary(fallback = { _, _, _ -> text("unused") }) {
            }
        }.tree
        assertEquals(FragmentNode(), emptyTree)

        val cancellation = assertFailsWith<CancellationException> {
            KineticaRuntime().render {
                errorBoundary(fallback = { _, _, _ -> text("unused") }) {
                    throw CancellationException("cancelled")
                }
            }
        }
        assertEquals("cancelled", cancellation.message)
    }

    @Test
    fun errorBoundaryDiscardsPartialContentBeforeRenderingFallback() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        val tree = runtime.render(scope) {
            errorBoundary(
                fallback = { error, _, _ -> text("Error: ${error.message}") },
            ) {
                text("Header")
                error("boom")
            }
        }.tree

        assertEquals(listOf("Error: boom"), tree.findTexts().map { it.value })
        assertTrue(runtime.journal().any { it.kind == JournalKind.BoundaryError })
    }

    @Test
    fun suspendSubtreeCommitsFallbackThenReadyNodeWithoutBlockingSiblings() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val release = CompletableDeferred<Unit>()

        fun render(): Node = runtime.render(scope) {
            column {
                text("Before")
                suspendSubtree(
                    key = "async-panel",
                    fallback = { text("Loading async") },
                ) {
                    release.await()
                    text("Async ready")
                }
                text("After")
            }
        }.tree

        assertEquals(listOf("Before", "Loading async", "After"), render().findTexts().map { it.value })

        release.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        assertTrue(runtime.hasPendingInvalidation)
        assertEquals(listOf("Before", "Async ready", "After"), render().findTexts().map { it.value })
        assertTrue(runtime.journal().any { it.kind == JournalKind.DeferredSubtree && it.message == "suspend subtree ready" })
    }

    @Test
    fun suspendSubtreeCancellationDisposesPendingStateWhenDeclarationLeavesRender() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        var visible = true

        fun render(): Node = runtime.render(scope) {
            if (visible) {
                suspendSubtree(
                    fallback = { text("Loading async") },
                ) {
                    entered.complete(Unit)
                    try {
                        never.await()
                    } catch (cancellation: CancellationException) {
                        cancelled.complete(Unit)
                        throw cancellation
                    }
                    text("unreachable")
                }
            } else {
                text("Hidden")
            }
        }.tree

        assertEquals("Loading async", render().findText().value)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                entered.await()
            }
        }

        visible = false
        assertEquals("Hidden", render().findText().value)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                cancelled.await()
            }
        }
    }

    @Test
    fun suspendSubtreeErrorsRenderNearestErrorBoundaryFallback() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val release = CompletableDeferred<Unit>()

        fun render(): Node = runtime.render(scope) {
            errorBoundary(
                fallback = { error, _, _ -> text("Async error: ${error.message}") },
            ) {
                suspendSubtree(
                    key = "async-failure",
                    fallback = { text("Loading async") },
                ) {
                    release.await()
                    error("bad async")
                }
            }
        }.tree

        assertEquals("Loading async", render().findText().value)

        release.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("Async error: bad async", render().findText().value)
        assertTrue(runtime.journal().any { it.kind == JournalKind.DeferredSubtree && it.message == "suspend subtree failed" })
    }

    @Test
    fun resourceReadFailuresRethrowToErrorBoundaryAfterPendingState() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun render(): Node = runtime.render(scope) {
            errorBoundary(
                fallback = { error, _, _ -> text("Error: ${error.message}") },
            ) {
                loadingBoundary(fallback = { text("Loading") }) {
                    val value: String = resource(FailingBoundaryKey) {
                        throw IllegalStateException("boom")
                    }.read()
                    text(value)
                }
            }
        }.tree

        assertEquals("Loading", render().findText().value)
        withTimeout(2_000) {
            while (render().findText().value != "Error: boom") {
                delay(10)
            }
        }
        assertTrue(runtime.journal().any { it.kind == JournalKind.BoundaryError })
    }

    @Test
    fun launchEffectErrorsRenderNearestErrorBoundaryFallback() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun render(): Node = runtime.render(scope) {
            errorBoundary(
                fallback = { error, _, _ -> text("Effect error: ${error.message}") },
            ) {
                launchEffect {
                    throw IllegalStateException("boom")
                }
                text("Body")
            }
        }.tree

        assertEquals("Body", render().findText().value)

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("Effect error: boom", render().findText().value)
        val boundaryError = runtime.journal().last { it.kind == JournalKind.BoundaryError }
        assertEquals("errorBoundary caught error", boundaryError.message)
        assertTrue(boundaryError.attributes.getValue("boundaryId").startsWith("boundary:"))
    }

    @Test
    fun launchEffectErrorsWithoutBoundaryAreRecordedInJournal() = runTest {
        val runtime = KineticaRuntime()

        runtime.render {
            launchEffect {
                throw IllegalStateException("outside boundary")
            }
            text("Body")
        }

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        val boundaryError = runtime.journal().last { entry ->
            entry.kind == JournalKind.BoundaryError && entry.message == "unhandled effect error"
        }
        assertEquals("launchEffect", boundaryError.attributes["effectKey"])
        assertTrue(boundaryError.attributes.getValue("error").contains("outside boundary"))
    }

    @Test
    fun watchErrorsRenderNearestErrorBoundaryFallback() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var count: MutableCell<Int>

        fun render(): Node = runtime.render(scope) {
            errorBoundary(
                fallback = { error, _, _ -> text("Watch error: ${error.message}") },
            ) {
                count = state(key = "count") { 0 }
                watch({ count.value }) { value ->
                    throw IllegalStateException("bad value $value")
                }
                text("Count: ${count.value}")
            }
        }.tree

        assertEquals("Count: 0", render().findText().value)

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("Watch error: bad value 0", render().findText().value)
        assertTrue(runtime.journal().any { it.kind == JournalKind.BoundaryError })
    }

    @Test
    fun serverRenderStreamEmitsDeferredSubtreesAsTheyBecomeReady() = runTest {
        val initial = HostNode(
            tag = "main",
            children = listOf(
                TextNode("Loading slow"),
                TextNode("Loading fast"),
                TextNode("Loading failing"),
            ),
        )

        val chunks = initial.toServerRenderStream(
            subtrees = listOf(
                ServerRenderDeferredSubtree(path = listOf(0), boundaryId = "slow") {
                    delay(50)
                    TextNode("Slow")
                },
                ServerRenderDeferredSubtree(path = listOf(1), boundaryId = "fast") {
                    TextNode("Fast")
                },
                ServerRenderDeferredSubtree(path = listOf(2), boundaryId = "failing") {
                    delay(25)
                    throw IllegalStateException("broken subtree")
                },
            ),
        )

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), chunks.map { it.sequence })
        assertEquals(initial, assertIs<ServerRenderChunk.Tree>(chunks[0]).node)
        assertEquals(listOf(1), assertIs<ServerRenderChunk.Patch>(chunks[1]).path)
        assertEquals("Fast", assertIs<TextNode>(assertIs<ServerRenderChunk.Patch>(chunks[1]).node).value)
        val error = assertIs<ServerRenderChunk.BoundaryError>(chunks[2])
        assertEquals("failing", error.boundaryId)
        assertEquals("broken subtree", error.message)
        assertEquals(listOf(0), assertIs<ServerRenderChunk.Patch>(chunks[3]).path)
        assertEquals("Slow", assertIs<TextNode>(assertIs<ServerRenderChunk.Patch>(chunks[3]).node).value)
        assertIs<ServerRenderChunk.End>(chunks[4])
    }

    @Test
    fun serverRenderStreamRethrowsDeferredSubtreeCancellation() = runTest {
        val cancellation = assertFailsWith<CancellationException> {
            TextNode("Loading").toServerRenderStream(
                subtrees = listOf(
                    ServerRenderDeferredSubtree(path = listOf(0), boundaryId = "cancelled") {
                        throw CancellationException("stop streaming")
                    },
                ),
            )
        }

        assertEquals("stop streaming", cancellation.message)
    }

    @Test
    fun serverRenderDefaultsHydrationDiffsAndDeferredValidationAreExplicit() = runTest {
        val clientRef = ClientRef(
            componentId = "app.AddToCartButton",
            props = JsonObject.of("productId" to JsonPrimitive("sku-1")),
        )
        val mounted = HostNode(
            tag = "main",
            children = listOf(TextNode("Old details")),
        )
        val current = HostNode(
            tag = "main",
            children = listOf(
                TextNode("New details"),
                clientRef,
                TextNode("Recommendations pending"),
            ),
        )

        val hydration = current.hydrationPlan(mountedTree = mounted)
        assertEquals(
            listOf(ClientIsland("app.AddToCartButton", listOf(1), JsonObject.of("productId" to JsonPrimitive("sku-1")))),
            hydration.clientIslands,
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = listOf(0),
                    kind = NodeDiff.Kind.Replaced,
                    before = TextNode("Old details"),
                    after = TextNode("New details"),
                ),
                NodeDiff(path = listOf(1), kind = NodeDiff.Kind.Inserted, after = clientRef),
                NodeDiff(path = listOf(2), kind = NodeDiff.Kind.Inserted, after = TextNode("Recommendations pending")),
            ),
            hydration.patchesFromMountedTree,
        )

        val initial = current.toInitialServerChunk()
        assertEquals(1L, initial.sequence)
        assertEquals(current, initial.node)
        assertEquals(ClientComponentManifest(), initial.manifest)

        val stream = current.toServerRenderStream()
        assertEquals(listOf(1L, 2L), stream.map { chunk -> chunk.sequence })
        assertEquals(current, assertIs<ServerRenderChunk.Tree>(stream.first()).node)
        assertIs<ServerRenderChunk.End>(stream.last())
        assertEquals(stream, current.toServerRenderStream(subtrees = emptyList()))

        val rootSubtree = ServerRenderDeferredSubtree(path = emptyList()) {
            TextNode("Root patch")
        }
        assertEquals("root", rootSubtree.boundaryId)
        val nestedSubtree = ServerRenderDeferredSubtree(path = listOf(1, 2)) {
            TextNode("Nested patch")
        }
        assertEquals("boundary.1.2", nestedSubtree.boundaryId)
        assertFailsWith<IllegalArgumentException> {
            ServerRenderDeferredSubtree(path = listOf(-1)) {
                TextNode("Invalid")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ServerRenderDeferredSubtree(path = listOf(0), boundaryId = " ") {
                TextNode("Invalid")
            }
        }
    }

    @Test
    fun serverTransportRoundTripsNodesHydrationPlansChunksAndActions() {
        val transport = KineticaServerTransport()
        val tree = FragmentNode(
            children = listOf(
                HostNode(
                    tag = "section",
                    children = listOf(
                        TextNode("Product"),
                        ClientRef(
                            componentId = "app.AddToCartButton",
                            props = JsonObject(
                                mapOf("productId" to JsonPrimitive("sku-1")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val manifest = ClientComponentManifest(
            components = listOf(
                ClientComponentRegistration(
                    componentId = "app.AddToCartButton",
                    componentFqName = "app.client.AddToCartButton",
                    serializablePropsType = "app.ProductIdProps",
                ),
            ),
            actions = listOf(
                ServerActionRegistration(
                    actionId = "cart.add",
                    functionFqName = "app.server.addToCart",
                    invalidates = listOf("cart"),
                ),
            ),
        )

        val decodedNode = transport.decodeNode(transport.encodeNode(tree))
        assertEquals(tree, decodedNode)

        val hydration = decodedNode.hydrationPlan()
        assertEquals(
            listOf(ClientIsland("app.AddToCartButton", listOf(0, 1), JsonObject(mapOf("productId" to JsonPrimitive("sku-1"))))),
            hydration.clientIslands,
        )
        assertEquals(hydration, transport.decodeHydrationPlan(transport.encodeHydrationPlan(hydration)))

        val stream = decodedNode.toServerRenderStream(manifest)
        assertIs<ServerRenderChunk.Tree>(transport.decodeChunk(transport.encodeChunk(stream.first())))
        assertIs<ServerRenderChunk.End>(transport.decodeChunk(transport.encodeChunk(stream.last())))
        assertEquals(stream.first(), transport.decodeSignedChunk(transport.encodeSignedChunk(stream.first())))
        val tamperedSignedChunk = transport
            .encodeSignedChunk(stream.first())
            .replace("sku-1", "sku-2")
        assertFailsWith<IllegalArgumentException> {
            transport.decodeSignedChunk(tamperedSignedChunk)
        }

        val request = ServerActionRequest(
            actionId = "cart.add",
            payload = JsonObject(mapOf("productId" to JsonPrimitive("sku-1"))),
            token = CapabilityToken("capability-token"),
            csrfToken = CsrfToken("csrf-token"),
        )
        assertEquals(request, transport.decodeActionRequest(transport.encodeActionRequest(request)))

        val response: ServerActionResponse = ServerActionResponse.Success(
            payload = JsonObject(mapOf("ok" to JsonPrimitive(true))),
            invalidated = listOf("cart"),
        )
        assertEquals(response, transport.decodeActionResponse(transport.encodeActionResponse(response)))
    }

    @Test
    fun serverTransportAndJournalModelsExposeStableDefaults() {
        val transport = KineticaServerTransport()
        val text = TextNode("Stable")

        val manifest = ClientComponentManifest()
        assertEquals(emptyList(), manifest.components)
        assertEquals(emptyList(), manifest.actions)
        assertEquals(null, ClientComponentRegistration("app.Client", "app.Client").serializablePropsType)
        assertEquals(emptyList(), ServerActionRegistration("cart.add", "app.addToCart").invalidates)
        assertEquals(null, ServerActionRegistration("cart.add", "app.addToCart").inputSchema)

        val schema = ServerActionPayloadSchema(kind = JsonValueKind.Object)
        assertFalse(schema.nullable)
        assertEquals(emptyList(), schema.fields)
        assertTrue(schema.allowUnknownFields)
        val field = ServerActionFieldSchema(name = "quantity", kind = JsonValueKind.Number)
        assertTrue(field.required)
        assertFalse(field.nullable)

        assertEquals(ServerRenderChunk.Tree(sequence = 1, node = text), transport.decodeChunk(transport.encodeChunk(ServerRenderChunk.Tree(1, text))))
        assertEquals(ServerRenderChunk.Patch(sequence = 2, path = listOf(0), node = null), transport.decodeChunk(transport.encodeChunk(ServerRenderChunk.Patch(2, listOf(0), null))))
        assertEquals(ServerRenderChunk.BoundaryError(sequence = 3, boundaryId = "root", message = "failed"), transport.decodeChunk(transport.encodeChunk(ServerRenderChunk.BoundaryError(3, "root", "failed"))))
        assertEquals(ServerRenderChunk.End(sequence = 4), transport.decodeChunk(transport.encodeChunk(ServerRenderChunk.End(4))))

        val hydration = HydrationPlan(initialTree = text, clientIslands = emptyList())
        assertEquals(emptyList(), hydration.patchesFromMountedTree)
        assertEquals(hydration, transport.decodeHydrationPlan(transport.encodeHydrationPlan(hydration)))

        val request = ServerActionRequest(
            actionId = "cart.add",
            payload = JsonNull,
            token = CapabilityToken("capability"),
        )
        assertEquals(null, request.csrfToken)
        assertEquals(request, transport.decodeActionRequest(transport.encodeActionRequest(request)))
        assertEquals(
            ServerActionResponse.Success(payload = JsonNull),
            transport.decodeActionResponse(transport.encodeActionResponse(ServerActionResponse.Success(JsonNull))),
        )
        assertEquals(
            ServerActionResponse.Failure(message = "try again"),
            transport.decodeActionResponse(transport.encodeActionResponse(ServerActionResponse.Failure("try again"))),
        )

        val signed = SignedServerRenderChunk(
            chunk = ServerRenderChunk.End(sequence = 5),
            integrity = IntegrityHash(algorithm = "manual", value = "hash"),
        )
        assertEquals("manual", signed.integrity.algorithm)
        assertEquals("hash", signed.integrity.value)
        assertEquals("csrf", CsrfToken("csrf").value)

        val entry = JournalEntry(sequence = 1, kind = JournalKind.Event, message = "clicked")
        assertEquals(emptyMap(), entry.attributes)
        val warning = RuntimeWarning(sequence = 2, code = "KINETICA_WARNING", message = "warn")
        assertEquals(emptyMap(), warning.attributes)
        val slotEntry = SlotSnapshotEntry(key = "slot", persistent = true, transient = false, value = "7")
        assertEquals(null, slotEntry.slotId)
        assertEquals(emptyList(), SlotSnapshot(sequence = 1).slots)

        val renderSnapshot = RenderSnapshot(
            sequence = 1,
            cause = "initial",
            tree = text,
            slots = SlotSnapshot(sequence = 1, slots = listOf(slotEntry)),
        )
        val exported = ExecutionJournal(entries = listOf(entry), renderSnapshots = listOf(renderSnapshot))
        assertEquals(exported, KineticaJson.decodeFromString<ExecutionJournal>(KineticaJson.encodeToString(exported)))
        assertEquals(emptyList(), ExecutionJournal(entries = emptyList()).renderSnapshots)

        val replay = replayJournal(exported)
        assertEquals(1L, replay.latest().sequence)
        assertEquals(listOf(entry), replay.latest().entries)
        assertEquals(renderSnapshot, replay.states().single().render)
    }

    @Test
    fun nodeDiffCoversRootLeafAndRecursiveChildChanges() {
        val inserted = TextNode("Inserted")
        assertEquals(emptyList(), diffNodes(before = null, after = null))
        assertEquals(
            listOf(NodeDiff(path = emptyList(), kind = NodeDiff.Kind.Inserted, after = inserted)),
            diffNodes(before = null, after = inserted),
        )

        val removed = TextNode("Removed")
        assertEquals(
            listOf(NodeDiff(path = emptyList(), kind = NodeDiff.Kind.Removed, before = removed)),
            diffNodes(before = removed, after = null),
        )

        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = TextNode("Text"),
                    after = ClientRef("app.Client"),
                ),
            ),
            diffNodes(before = TextNode("Text"), after = ClientRef("app.Client")),
        )
        assertEquals(emptyList(), diffNodes(TextNode("Same"), TextNode("Same")))
        assertEquals(emptyList(), diffNodes(FragmentNode(), FragmentNode()))
        assertEquals(emptyList(), diffNodes(HostNode(tag = "section"), HostNode(tag = "section")))
        assertEquals(emptyList(), diffNodes(ClientRef("app.Client"), ClientRef("app.Client")))
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = TextNode("Before"),
                    after = TextNode("After"),
                ),
            ),
            diffNodes(TextNode("Before"), TextNode("After")),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = ClientRef("app.Client", props = JsonObject(mapOf("version" to JsonPrimitive(1)))),
                    after = ClientRef("app.Client", props = JsonObject(mapOf("version" to JsonPrimitive(2)))),
                ),
            ),
            diffNodes(
                ClientRef("app.Client", props = JsonObject(mapOf("version" to JsonPrimitive(1)))),
                ClientRef("app.Client", props = JsonObject(mapOf("version" to JsonPrimitive(2)))),
            ),
        )

        val before = HostNode(
            tag = "ul",
            children = listOf(
                TextNode("A"),
                TextNode("B"),
                ClientRef("app.Item"),
            ),
        )
        val after = HostNode(
            tag = "ul",
            children = listOf(
                TextNode("A"),
                TextNode("C"),
                ClientRef("app.Item"),
                TextNode("D"),
            ),
        )
        assertEquals(
            listOf(
                NodeDiff(path = listOf(1), kind = NodeDiff.Kind.Replaced, before = TextNode("B"), after = TextNode("C")),
                NodeDiff(path = listOf(3), kind = NodeDiff.Kind.Inserted, after = TextNode("D")),
            ),
            diffNodes(before, after),
        )
        assertEquals(
            listOf(NodeDiff(path = listOf(0), kind = NodeDiff.Kind.Removed, before = TextNode("A"))),
            diffNodes(FragmentNode(children = listOf(TextNode("A"))), FragmentNode()),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = HostNode(
                        tag = "button",
                        props = mapOf("enabled" to "true"),
                        children = listOf(TextNode("Save")),
                        key = "primary",
                        semantics = Semantics(role = Role.Button, label = "Save"),
                    ),
                    after = HostNode(
                        tag = "button",
                        props = mapOf("enabled" to "false"),
                        children = listOf(TextNode("Save")),
                        key = "primary",
                        semantics = Semantics(role = Role.Button, label = "Save"),
                    ),
                ),
            ),
            diffNodes(
                before = HostNode(
                    tag = "button",
                    props = mapOf("enabled" to "true"),
                    children = listOf(TextNode("Save")),
                    key = "primary",
                    semantics = Semantics(role = Role.Button, label = "Save"),
                ),
                after = HostNode(
                    tag = "button",
                    props = mapOf("enabled" to "false"),
                    children = listOf(TextNode("Save")),
                    key = "primary",
                    semantics = Semantics(role = Role.Button, label = "Save"),
                ),
            ),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = FragmentNode(semantics = Semantics(label = "before")),
                    after = FragmentNode(semantics = Semantics(label = "after")),
                ),
            ),
            diffNodes(
                before = FragmentNode(semantics = Semantics(label = "before")),
                after = FragmentNode(semantics = Semantics(label = "after")),
            ),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = HostNode(tag = "button"),
                    after = HostNode(tag = "a"),
                ),
            ),
            diffNodes(before = HostNode(tag = "button"), after = HostNode(tag = "a")),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = HostNode(tag = "button", key = "primary"),
                    after = HostNode(tag = "button", key = "secondary"),
                ),
            ),
            diffNodes(
                before = HostNode(tag = "button", key = "primary"),
                after = HostNode(tag = "button", key = "secondary"),
            ),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = HostNode(tag = "button", semantics = Semantics(role = Role.Button, label = "Save")),
                    after = HostNode(tag = "button", semantics = Semantics(role = Role.Button, label = "Submit")),
                ),
            ),
            diffNodes(
                before = HostNode(tag = "button", semantics = Semantics(role = Role.Button, label = "Save")),
                after = HostNode(tag = "button", semantics = Semantics(role = Role.Button, label = "Submit")),
            ),
        )

        val explicitDefault = NodeDiff(path = listOf(9), kind = NodeDiff.Kind.Inserted)
        assertEquals(listOf(9), explicitDefault.path)
        assertEquals(NodeDiff.Kind.Inserted, explicitDefault.kind)
        assertEquals(null, explicitDefault.before)
        assertEquals(null, explicitDefault.after)
    }

    @Test
    fun templateNodesMaterializeForHtmlSemanticsAndDiffs() {
        val definition = TemplateDefinition(
            id = "runtime-template",
            skeleton = HostNode(
                tag = "span",
                props = propsOf("class", ""),
                children = listOf(TextNode("", semantics = Semantics(role = Role.Text))),
            ),
            holes = listOf(
                TemplateHole(path = "", kind = TemplateHoleKinds.Prop, propName = "class"),
                TemplateHole(path = "0", kind = TemplateHoleKinds.Text),
            ),
        )
        val hello = templateNode(definition, values = listOf("cold", "Hello"), key = "greeting")
        val world = templateNode(definition, values = listOf("cold", "World"), key = "greeting")

        assertEquals(
            HostNode(
                tag = "span",
                props = propsOf("class", "cold"),
                children = listOf(TextNode("Hello", semantics = Semantics(role = Role.Text))),
                key = "greeting",
            ),
            hello.materialize(),
        )
        assertEquals("""<span class="cold" data-kinetica-key="greeting">Hello</span>""", hello.toSafeHtml())
        assertEquals("Hello", hello.semanticsTree().byRole(Role.Text).single().semantics.label)
        assertEquals(
            listOf(
                NodeDiff(
                    path = listOf(0),
                    kind = NodeDiff.Kind.Replaced,
                    before = TextNode("Hello", semantics = Semantics(role = Role.Text)),
                    after = TextNode("World", semantics = Semantics(role = Role.Text)),
                ),
            ),
            diffNodes(hello, world),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = hello,
                    after = templateNode(definition, values = listOf("warm", "Hello"), key = "greeting"),
                ),
            ),
            diffNodes(hello, templateNode(definition, values = listOf("warm", "Hello"), key = "greeting")),
        )

        val nestedDefinition = TemplateDefinition(
            id = "nested-template",
            skeleton = HostNode(
                tag = "strong",
                props = propsOf("data-static", "yes"),
                children = listOf(TextNode("", semantics = null)),
            ),
            holes = listOf(TemplateHole(path = "0", kind = TemplateHoleKinds.Text)),
        )
        val complexDefinition = TemplateDefinition(
            id = "complex-template",
            skeleton = HostNode(
                tag = "article",
                props = propsOf("class", "fallback", "data-action", "stale"),
                children = listOf(
                    FragmentNode(children = listOf(TextNode("pending", semantics = null))),
                    HostNode(
                        tag = "button",
                        props = propsOf("data-action", "stale"),
                        children = listOf(TextNode("Click", semantics = null)),
                        key = "child",
                    ),
                    TemplateNode(nestedDefinition, values = listOf("Nested")),
                ),
                key = "skeleton-key",
            ),
            holes = listOf(
                TemplateHole(path = "", kind = TemplateHoleKinds.Prop, propName = "class"),
                TemplateHole(path = "", kind = TemplateHoleKinds.EventProp, propName = "event:onClick"),
                TemplateHole(path = "", kind = TemplateHoleKinds.Prop),
                TemplateHole(path = "0.0", kind = TemplateHoleKinds.Text),
                TemplateHole(path = "1", kind = TemplateHoleKinds.Prop, propName = "data-action"),
                TemplateHole(path = "1.0", kind = TemplateHoleKinds.Text),
            ),
        )
        val complex = TemplateNode(
            definition = complexDefinition,
            values = listOf(null, "event-7", "ignored", "Body", null),
        ).materialize()
        assertEquals("article", complex.tag)
        assertEquals("skeleton-key", complex.key)
        assertFalse("class" in complex.props)
        assertEquals("event-7", complex.props["event:onClick"])
        assertEquals("Body", assertIs<TextNode>(assertIs<FragmentNode>(complex.children[0]).children.single()).value)
        val button = assertIs<HostNode>(complex.children[1])
        assertFalse("data-action" in button.props)
        assertEquals("", assertIs<TextNode>(button.children.single()).value)
        val nested = assertIs<HostNode>(complex.children[2])
        assertEquals("strong", nested.tag)
        assertEquals("Nested", assertIs<TextNode>(nested.children.single()).value)
    }

    @Test
    fun safeHtmlEscapesTextAttributesKeysAndClientRefs() {
        val html = HostNode(
            tag = "section",
            props = linkedMapOf(
                "title" to "\"<Cart & checkout>'",
                "event:onClick" to "event-0",
                "frame:translateX" to "frame-0",
                "onclick" to "alert(1)",
                "href" to "javascript:alert(1)",
                "srcdoc" to "<script>alert(1)</script>",
                "formaction" to " data:text/html,<script>alert(1)</script>",
                "cite" to "https://example.test/cart",
            ),
            children = listOf(
                TextNode("<script>alert('x')</script> & total"),
                ClientRef(
                    componentId = "app.AddToCart\"Button",
                    props = JsonObject(mapOf("productId" to JsonPrimitive("<sku&1>"))),
                ),
            ),
            key = "<row&1>",
        ).toSafeHtml()

        assertTrue(html.startsWith("<section "))
        assertTrue("title=\"&quot;&lt;Cart &amp; checkout&gt;&#39;\"" in html)
        assertTrue("data-kinetica-key=\"&lt;row&amp;1&gt;\"" in html)
        assertTrue("&lt;script&gt;alert('x')&lt;/script&gt; &amp; total" in html)
        assertTrue("data-kinetica-client-ref=\"app.AddToCart&quot;Button\"" in html)
        assertTrue("data-kinetica-props=\"" in html)
        assertTrue("&lt;sku&amp;1&gt;" in html)
        assertFalse("event:onClick" in html)
        assertFalse("frame:translateX" in html)
        assertFalse("onclick" in html)
        assertFalse("href=" in html)
        assertFalse("srcdoc=" in html)
        assertFalse("formaction=" in html)
        assertTrue("cite=\"https://example.test/cart\"" in html)
        assertFalse("<script>" in html)

        val unsafeHtml = HostNode(
            tag = "9bad<tag",
            props = linkedMapOf(
                "" to "empty",
                "9bad" to "bad",
                "data bad" to "space",
                "aria-label" to "Kept",
            ),
            children = listOf(TextNode("Safe")),
        ).toSafeHtml()

        assertTrue(unsafeHtml.startsWith("<div data-kinetica-tag=\"9bad&lt;tag\" aria-label=\"Kept\">"))
        assertTrue(unsafeHtml.endsWith(">Safe</div>"))
        assertFalse("=\"empty\"" in unsafeHtml)
        assertFalse("9bad=\"bad\"" in unsafeHtml)
        assertFalse("data bad=\"space\"" in unsafeHtml)

        assertTrue(isPublicHtmlAttribute("aria-label", "Kept"))
        assertFalse(isPublicHtmlAttribute("event:onClick", "event-0"))
        assertFalse(isPublicHtmlAttribute("frame:translateX", "frame-0"))
        assertFalse(isPublicHtmlAttribute("onclick", "alert(1)"))
        assertFalse(isSafeHtmlAttributeValue("srcdoc", "plain text"))
        assertTrue(isSafeHtmlAttributeValue("href", ""))
        assertTrue(isSafeHtmlAttributeValue("href", "#cart"))
        assertTrue(isSafeHtmlAttributeValue("href", "/cart"))
        assertTrue(isSafeHtmlAttributeValue("href", "./cart"))
        assertTrue(isSafeHtmlAttributeValue("href", "../cart"))
        assertTrue(isSafeHtmlAttributeValue("href", "?sku=runtime"))
        assertTrue(isSafeHtmlAttributeValue("href", "cart/runtime-license"))
        assertTrue(isSafeHtmlAttributeValue("href", "cart/runtime-license:http"))
        assertTrue(isSafeHtmlAttributeValue("href", "mailto:team@example.test"))
        assertTrue(isSafeHtmlAttributeValue("href", "TEL:+123"))
        assertFalse(isSafeHtmlAttributeValue("href", " javascript:alert(1)"))
    }

    @Test
    fun serverActionSchemaAcceptsEnumAndMapInputs() {
        // A top-level enum input serializes as a JSON string and must validate.
        val enumSchema = serverActionPayloadSchema(CartStatus.serializer())
        assertEquals(JsonValueKind.String, enumSchema.kind)
        assertEquals(emptyList(), enumSchema.validate(JsonPrimitive("Pending")))

        // A map input serializes as a JSON object with arbitrary keys; the map descriptor's
        // synthetic key/value elements must NOT be derived as required fields.
        val mapSchema = serverActionPayloadSchema(MapSerializer(String.serializer(), Int.serializer()))
        assertEquals(JsonValueKind.Object, mapSchema.kind)
        assertEquals(
            emptyList(),
            mapSchema.validate(
                JsonObject(mapOf("steps" to JsonPrimitive(3), "lives" to JsonPrimitive(5))),
            ),
        )
    }

    @Test
    fun serverActionSchemasValidateKindsFieldsNullsUnknownsAndSerializers() {
        val schema = ServerActionPayloadSchema(
            kind = JsonValueKind.Object,
            fields = listOf(
                ServerActionFieldSchema("title", JsonValueKind.String),
                ServerActionFieldSchema("count", JsonValueKind.Number),
                ServerActionFieldSchema("flag", JsonValueKind.Boolean, required = false),
                ServerActionFieldSchema("maybe", JsonValueKind.Null, required = false, nullable = true),
            ),
            allowUnknownFields = false,
        )

        assertEquals(
            emptyList(),
            schema.validate(
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("Cart"),
                        "count" to JsonPrimitive(2),
                        "flag" to JsonPrimitive(true),
                        "maybe" to JsonNull,
                    ),
                ),
            ),
        )
        assertEquals(listOf("expected object payload"), schema.validate(JsonNull))
        assertEquals(listOf("expected object payload"), schema.validate(JsonPrimitive("not-object")))
        assertEquals(
            listOf(
                "field 'title' expected string",
                "field 'count' expected number",
                "field 'flag' expected boolean",
                "field 'maybe' expected null",
                "unknown field 'extra'",
            ),
            schema.validate(
                JsonObject(
                    mapOf(
                        "title" to JsonNull,
                        "count" to JsonPrimitive("two"),
                        "flag" to JsonPrimitive("true"),
                        "maybe" to JsonPrimitive("not-null"),
                        "extra" to JsonPrimitive(1),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("missing required field 'title'"),
            schema.validate(JsonObject(mapOf("count" to JsonPrimitive(1)))),
        )

        assertEquals(emptyList(), ServerActionPayloadSchema(JsonValueKind.String, nullable = true).validate(JsonNull))
        assertEquals(emptyList(), ServerActionPayloadSchema(JsonValueKind.Null).validate(JsonNull))
        assertEquals(listOf("expected null payload"), ServerActionPayloadSchema(JsonValueKind.Null).validate(JsonPrimitive("x")))
        assertEquals(emptyList(), ServerActionPayloadSchema(JsonValueKind.Array).validate(JsonArray(emptyList())))
        assertEquals(listOf("expected array payload"), ServerActionPayloadSchema(JsonValueKind.Array).validate(JsonObject(emptyMap())))
        assertEquals(emptyList(), ServerActionPayloadSchema(JsonValueKind.Boolean).validate(JsonPrimitive(false)))
        assertEquals(listOf("expected boolean payload"), ServerActionPayloadSchema(JsonValueKind.Boolean).validate(JsonPrimitive("false")))
        assertEquals(emptyList(), ServerActionPayloadSchema(JsonValueKind.Number).validate(JsonPrimitive(1.5)))
        assertEquals(listOf("expected number payload"), ServerActionPayloadSchema(JsonValueKind.Number).validate(JsonPrimitive("1.5")))
        assertEquals(emptyList(), ServerActionPayloadSchema(JsonValueKind.String).validate(JsonPrimitive("value")))
        assertEquals(listOf("expected string payload"), ServerActionPayloadSchema(JsonValueKind.String).validate(JsonPrimitive(3)))

        assertEquals(JsonValueKind.Boolean, serverActionPayloadSchema(Boolean.serializer()).kind)
        assertEquals(JsonValueKind.Number, serverActionPayloadSchema(Byte.serializer()).kind)
        assertEquals(JsonValueKind.Number, serverActionPayloadSchema(Short.serializer()).kind)
        assertEquals(JsonValueKind.Number, serverActionPayloadSchema(Int.serializer()).kind)
        assertEquals(JsonValueKind.Number, serverActionPayloadSchema(Long.serializer()).kind)
        assertEquals(JsonValueKind.Number, serverActionPayloadSchema(Float.serializer()).kind)
        assertEquals(JsonValueKind.Number, serverActionPayloadSchema(Double.serializer()).kind)
        assertEquals(JsonValueKind.String, serverActionPayloadSchema(Char.serializer()).kind)
        assertEquals(JsonValueKind.String, serverActionPayloadSchema(CartStatus.serializer()).kind)
        assertEquals(JsonValueKind.Array, serverActionPayloadSchema(ListSerializer(String.serializer())).kind)
        assertEquals(
            listOf(
                ServerActionFieldSchema("productId", JsonValueKind.String),
                ServerActionFieldSchema("quantity", JsonValueKind.Number, required = false),
                ServerActionFieldSchema("note", JsonValueKind.String, required = false, nullable = true),
            ),
            serverActionPayloadSchema(OptionalCartPatch.serializer()).fields,
        )
        assertEquals(
            listOf(
                ServerActionFieldSchema("productId", JsonValueKind.String),
                ServerActionFieldSchema("status", JsonValueKind.String),
            ),
            serverActionPayloadSchema(CartStatusPatch.serializer()).fields,
        )
    }

    @Test
    fun serverActionDispatcherInvokesTypedStubsAndVerifiesCapability() = runTest {
        var stringHandlerCalls = 0
        var dtoHandlerCalls = 0
        var enumHandlerCalls = 0
        val explicitSchema = ServerActionPayloadSchema(
            kind = JsonValueKind.Object,
            fields = emptyList(),
            allowUnknownFields = true,
        )
        val dispatcher = KineticaServerActionDispatcher(
            stubs = listOf(
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.add",
                        functionFqName = "app.server.addToCart",
                        invalidates = listOf("cart"),
                    ),
                    inputSerializer = String.serializer(),
                    outputSerializer = String.serializer(),
                    handler = { productId ->
                        stringHandlerCalls += 1
                        "added:$productId"
                    },
                ),
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.setQuantity",
                        functionFqName = "app.server.setQuantity",
                        invalidates = listOf("cart"),
                    ),
                    inputSerializer = CartQuantityDraft.serializer(),
                    outputSerializer = String.serializer(),
                    handler = { draft ->
                        dtoHandlerCalls += 1
                        "${draft.productId}:${draft.quantity}"
                    },
                ),
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.setStatus",
                        functionFqName = "app.server.setStatus",
                        invalidates = listOf("cart"),
                    ),
                    inputSerializer = CartStatusPatch.serializer(),
                    outputSerializer = String.serializer(),
                    handler = { patch ->
                        enumHandlerCalls += 1
                        "${patch.productId}:${patch.status.name}"
                    },
                ),
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.explicitSchema",
                        functionFqName = "app.server.explicitSchema",
                        inputSchema = explicitSchema,
                    ),
                    inputSerializer = CartQuantityDraft.serializer(),
                    outputSerializer = String.serializer(),
                    handler = { draft ->
                        "explicit:${draft.productId}:${draft.quantity}"
                    },
                ),
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.crash",
                        functionFqName = "app.server.crash",
                    ),
                    inputSerializer = String.serializer(),
                    outputSerializer = String.serializer(),
                    handler = {
                        throw IllegalStateException("database password=secret")
                    },
                ),
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.cancel",
                        functionFqName = "app.server.cancel",
                    ),
                    inputSerializer = String.serializer(),
                    outputSerializer = String.serializer(),
                    handler = {
                        throw kotlin.coroutines.cancellation.CancellationException("cancelled")
                    },
                ),
            ),
            verifyCapabilityToken = { token -> token.value == "valid" },
            verifyCsrfToken = { token -> token?.value == "csrf" },
        )

        val success = dispatcher.dispatch(
            ServerActionRequest(
                actionId = "cart.add",
                payload = JsonPrimitive("sku-1"),
                token = CapabilityToken("valid"),
                csrfToken = CsrfToken("csrf"),
            ),
        )

        assertEquals(
            ServerActionResponse.Success(
                payload = JsonPrimitive("added:sku-1"),
                invalidated = listOf("cart"),
            ),
            success,
        )
        assertEquals(1, stringHandlerCalls)
        assertEquals(JsonValueKind.String, dispatcher.actions.single { it.actionId == "cart.add" }.inputSchema?.kind)

        assertEquals(
            ServerActionResponse.Failure("Invalid server action payload: expected string payload"),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.add",
                    payload = JsonObject(mapOf("productId" to JsonPrimitive("sku-1"))),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        assertEquals(1, stringHandlerCalls)

        assertEquals(
            ServerActionResponse.Failure("Invalid server action payload: missing required field 'productId'"),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.setQuantity",
                    payload = JsonObject(mapOf("quantity" to JsonPrimitive(2))),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        assertEquals(0, dtoHandlerCalls)

        assertEquals(
            ServerActionResponse.Success(
                payload = JsonPrimitive("sku-1:2"),
                invalidated = listOf("cart"),
            ),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.setQuantity",
                    payload = JsonObject(
                        mapOf(
                            "productId" to JsonPrimitive("sku-1"),
                            "quantity" to JsonPrimitive(2),
                        ),
                    ),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        assertEquals(1, dtoHandlerCalls)
        assertEquals(
            ServerActionResponse.Success(
                payload = JsonPrimitive("sku-1:Done"),
                invalidated = listOf("cart"),
            ),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.setStatus",
                    payload = JsonObject(
                        mapOf(
                            "productId" to JsonPrimitive("sku-1"),
                            "status" to JsonPrimitive("Done"),
                        ),
                    ),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        assertEquals(1, enumHandlerCalls)
        assertEquals(
            ServerActionResponse.Success(
                payload = JsonPrimitive("explicit:sku-2:4"),
                invalidated = emptyList(),
            ),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.explicitSchema",
                    payload = JsonObject(
                        mapOf(
                            "productId" to JsonPrimitive("sku-2"),
                            "quantity" to JsonPrimitive(4),
                        ),
                    ),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        assertEquals(
            explicitSchema,
            dispatcher.actions.single { it.actionId == "cart.explicitSchema" }.inputSchema,
        )
        assertEquals(
            ServerActionResponse.Failure("Server action failed."),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.crash",
                    payload = JsonPrimitive("sku-1"),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        val cancellation = assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.cancel",
                    payload = JsonPrimitive("sku-1"),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            )
        }
        assertEquals("cancelled", cancellation.message)
        assertEquals(
            ServerActionResponse.Failure("Invalid capability token."),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.add",
                    payload = JsonPrimitive("sku-1"),
                    token = CapabilityToken("invalid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        assertEquals(
            ServerActionResponse.Failure("Invalid CSRF token."),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.add",
                    payload = JsonPrimitive("sku-1"),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("wrong"),
                ),
            ),
        )
        assertEquals(
            ServerActionResponse.Failure("Unknown server action."),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.remove",
                    payload = JsonPrimitive("sku-1"),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
    }

    @Test
    fun slotIdStateSurvivesConditionalAbsenceAndTransientSlotExpires() {
        val persistentSlot = SlotId("todo", "Counter", 0, "count")
        val transientSlot = SlotId("todo", "Counter", 1, "hover")
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun render(showPersistent: Boolean, showTransient: Boolean): Node = runtime.render(scope) {
            column {
                if (showPersistent) {
                    var count by state(slotId = persistentSlot, persistent = true) { 0 }
                    button(onClick = event { count += 1 }) {
                        text("Count: $count")
                    }
                }
                if (showTransient) {
                    var hover by state(slotId = transientSlot, transient = true) { "warm" }
                    button(onClick = event { hover = "hot" }) {
                        text("Hover: $hover")
                    }
                }
            }
        }.tree

        val first = render(showPersistent = true, showTransient = true)
        val buttons = first.findHostsByTag("button")
        runtime.dispatch(buttons[0].props.getValue("event:onClick"))
        runtime.dispatch(buttons[1].props.getValue("event:onClick"))

        val changed = render(showPersistent = true, showTransient = true)
        assertEquals(listOf("Count: 1", "Hover: hot"), changed.findTexts().map { it.value })
        assertEquals(listOf(persistentSlot), scope.persistentSlotIds())

        render(showPersistent = false, showTransient = false)
        assertEquals(1, scope.readSlot<Int>(persistentSlot))
        assertFalse(scope.containsSlot(transientSlot))

        val restored = render(showPersistent = true, showTransient = true)
        assertEquals(listOf("Count: 1", "Hover: warm"), restored.findTexts().map { it.value })
    }

    @Test
    fun compiledSiblingComponentInstancesDoNotShareSlotIdState() {
        val countSlot = SlotId("app", "app.Counter", 0, "count")
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun ComponentScope.CompiledCounter(label: String): Node =
            renderNode {
                var count by state(slotId = countSlot) { 0 }
                button(onClick = event { count += 1 }) {
                    text("$label:$count")
                }
            }

        fun render(): Node = runtime.render(scope) {
            column {
                keyed("app/Parent#call#0") {
                    emit(CompiledCounter("A"))
                }
                keyed("app/Parent#call#1") {
                    emit(CompiledCounter("B"))
                }
            }
        }.tree

        val first = render()
        assertEquals(listOf("A:0", "B:0"), first.findTexts().map { it.value })

        runtime.dispatch(first.findHostsByTag("button").first().props.getValue("event:onClick"))

        assertEquals(listOf("A:1", "B:0"), render().findTexts().map { it.value })
    }

    @Test
    fun compiledSiblingComponentInstancesDoNotShareSkippableCache() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var renders = 0

        fun ComponentScope.CompiledBadge(): Node =
            skippableNode(componentId = "app.Badge", inputs = emptyList()) {
                TextNode("Badge:${++renders}")
            }

        fun render(): List<String> = runtime.render(scope) {
            column {
                keyed("app/Parent#call#0") {
                    emit(CompiledBadge())
                }
                keyed("app/Parent#call#1") {
                    emit(CompiledBadge())
                }
            }
        }.tree.findTexts().map { it.value }

        assertEquals(listOf("Badge:1", "Badge:2"), render())
        assertEquals(2, renders)
        assertEquals(listOf("Badge:1", "Badge:2"), render())
        assertEquals(2, renders)
    }

    @Test
    fun manualSlotApisKeyScopedHelpersAndTransientDisposalAreExplicit() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val cellSlot = SlotId("manual", "CellSlot", 0, "count")

        assertFalse(scope.containsSlot(cellSlot))
        assertEquals(null, scope.readSlot<Int>(cellSlot))

        scope.writeSlot(cellSlot, 1, persistent = true)
        assertEquals(1, scope.readSlot<Int>(cellSlot))
        scope.writeSlot(cellSlot, 2, persistent = true)
        assertEquals(2, scope.readSlot<Int>(cellSlot))
        assertEquals(listOf(cellSlot), scope.persistentSlotIds())

        val restoredRuntime = KineticaRuntime()
        val restoredScope = ComponentScope(restoredRuntime)
        restoredScope.writeSlot(cellSlot, 0, persistent = true)

        fun renderRestoredCounter(): Node = restoredRuntime.render(restoredScope) {
            var count by state(slotId = cellSlot, persistent = true) { -1 }
            button(onClick = event { count += 1 }) {
                text("Restored: $count")
            }
        }.tree

        val restoredCounter = renderRestoredCounter()
        assertEquals("Restored: 0", restoredCounter.findText().value)
        restoredRuntime.dispatch(restoredCounter.findHostByTag("button").props.getValue("event:onClick"))
        assertTrue(restoredRuntime.hasPendingInvalidation)
        assertEquals("Restored: 1", renderRestoredCounter().findText().value)

        val keyedSlot = SlotId("manual", "KeyedSlot", 0, "count")
        val keyedRuntime = KineticaRuntime()
        val keyedScope = ComponentScope(keyedRuntime)

        fun renderKeyedCounter(): Node = keyedRuntime.render(keyedScope) {
            keyed("row") {
                var count by state(slotId = keyedSlot, persistent = true) { 0 }
                button(onClick = event { count += 1 }) {
                    text("Keyed: $count")
                }
            }
        }.tree

        val keyedCounter = renderKeyedCounter()
        assertTrue(keyedCounter.findHostByTag("button").props.getValue("event:onClick").contains("event-"))
        keyedRuntime.dispatch(keyedCounter.findHostByTag("button").props.getValue("event:onClick"))
        assertTrue(keyedRuntime.hasPendingInvalidation)
        assertEquals("Keyed: 1", renderKeyedCounter().findText().value)
        assertEquals(1, keyedScope.readSlot<Int>(keyedSlot))

        val restoredKeyedRuntime = KineticaRuntime()
        val restoredKeyedScope = ComponentScope(restoredKeyedRuntime)
        restoredKeyedScope.writeSlot(keyedSlot, 7, persistent = true)
        val restoredKeyed = restoredKeyedRuntime.render(restoredKeyedScope) {
            keyed("row") {
                val count by state(slotId = keyedSlot, persistent = true) { 0 }
                text("Keyed restored: $count")
            }
        }.tree
        assertEquals("Keyed restored: 7", restoredKeyed.findText().value)

        lateinit var handle: Ref<String>
        val rendered = runtime.render(scope) {
            keyed("row") {
                handle = imperativeHandle { "ready" }
                button(onClick = event {}) {
                    text("Click")
                }
            }
        }.tree
        val button = rendered.findHostByTag("button")
        assertTrue(button.props.getValue("event:onClick").contains("event-"))
        assertEquals("ready", handle.current)

        runtime.render(scope) {
            text("gone")
        }

        assertEquals(null, handle.current)
    }

    @Test
    fun imperativeHandleRefreshesCurrentValueAcrossRenders() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var label = "one"
        lateinit var handle: Ref<() -> String>

        fun render(): Node = runtime.render(scope) {
            val captured = label
            handle = imperativeHandle { { captured } }
            text(captured)
        }.tree

        assertEquals("one", render().findText().value)
        val firstHandle = handle
        assertEquals("one", firstHandle.current?.invoke())

        label = "two"
        assertEquals("two", render().findText().value)
        assertSame(firstHandle, handle)
        assertEquals("two", handle.current?.invoke())
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
        var visible = true
        lateinit var ref: Ref<String>

        fun render(): Node = runtime.render(scope) {
            if (visible) {
                ref = hostRef()
                text("visible")
            } else {
                text("gone")
            }
        }.tree

        assertEquals("visible", render().findText().value)
        val firstRef = ref
        assertEquals(null, firstRef.current)
        assertEquals("visible", render().findText().value)
        assertSame(firstRef, ref)

        visible = false
        assertEquals("gone", render().findText().value)
        assertEquals(null, firstRef.current)
    }

    @Test
    fun semanticsTreeFocusManagerAndHeadlessRendererUseNodeAsData() {
        val node = FragmentNode(
            children = listOf(
                HostNode(
                    tag = "toolbar",
                    semantics = Semantics(role = Role.Navigation, testTag = "toolbar"),
                    children = listOf(
                        HostNode(
                            tag = "button",
                            semantics = Semantics(
                                role = Role.Button,
                                label = "Second",
                                focusable = true,
                                traversalIndex = 2,
                                testTag = "second",
                            ),
                            children = listOf(TextNode("Second")),
                        ),
                        HostNode(
                            tag = "button",
                            semantics = Semantics(
                                role = Role.Button,
                                label = "First",
                                focusable = true,
                                traversalIndex = 1,
                                testTag = "first",
                            ),
                            children = listOf(TextNode("First")),
                        ),
                    ),
                ),
            ),
        )

        val semantics = node.semanticsTree()
        assertEquals(listOf("First", "Second"), semantics.focusOrder().map { it.semantics.label })
        assertEquals(listOf(0, 1), semantics.byTestTag("first")?.path)
        assertEquals(listOf("Second", "First"), semantics.byRole(Role.Button).map { it.semantics.label })
        assertEquals(listOf(listOf(0, 1), listOf(0, 1, 0)), semantics.byLabel("First").map { it.path })
        assertEquals("First", (node.nodeAt(listOf(0, 1, 0)) as TextNode).value)
        assertEquals(null, node.nodeAt(listOf(99)))
        assertEquals(null, node.nodeAt(listOf(0, 0, 0, 0)))
        assertEquals(null, TextNode("leaf").nodeAt(listOf(0)))
        assertEquals(null, ClientRef(componentId = "client", props = JsonObject(emptyMap())).nodeAt(listOf(0)))

        val terminalSemantics = FragmentNode(
            children = listOf(
                TextNode("Label", semantics = Semantics(role = Role.Text, label = "Label")),
                TextNode("Derived label", semantics = Semantics(role = Role.Text)),
                ClientRef(
                    componentId = "client",
                    props = JsonObject(emptyMap()),
                    semantics = Semantics(testTag = "island"),
                ),
            ),
        ).semanticsTree()
        assertEquals(listOf("Label", "Derived label"), terminalSemantics.byRole(Role.Text).map { it.text })
        assertEquals(listOf(listOf(1)), terminalSemantics.byLabel("Derived label").map { it.path })
        assertEquals(listOf(2), terminalSemantics.byTestTag("island")?.path)

        val focus = FocusManager(semantics)
        assertEquals("First", focus.moveNext()?.semantics?.label)
        assertEquals("Second", focus.moveNext()?.semantics?.label)
        assertEquals("First", focus.moveNext()?.semantics?.label)
        assertTrue(focus.requestFocusByTestTag("second"))
        assertEquals("Second", focus.focused?.semantics?.label)

        val renderer = HeadlessRenderer()
        val handle = renderer.mount(node) as HeadlessRenderHandle
        assertEquals("headless", renderer.name)
        assertNotNull(handle.semantics.byTestTag("toolbar"))
        renderer.update(handle, TextNode("Replaced"))
        assertEquals("Replaced", (renderer.handle(handle.id)?.node as TextNode).value)
        renderer.dispose(handle)
        assertEquals(null, renderer.handle(handle.id))
    }

    @Test
    fun focusManagerHandlesDirectRequestsUpdatesEmptyTreesAndReverseTraversal() {
        val focus = FocusManager()

        assertEquals(null, focus.focusedPath)
        assertEquals(null, focus.focused)
        assertEquals(null, focus.moveNext())
        assertEquals(null, focus.focusedPath)

        val tree = SemanticsTree(
            listOf(
                SemanticsNode(
                    path = listOf(0),
                    semantics = Semantics(
                        role = Role.Button,
                        label = "Late",
                        focusable = true,
                        traversalIndex = 2,
                        testTag = "late",
                    ),
                    hostTag = "button",
                ),
                SemanticsNode(
                    path = listOf(1),
                    semantics = Semantics(
                        role = Role.Text,
                        label = "Static",
                        focusable = false,
                        testTag = "static",
                    ),
                    text = "Static",
                ),
                SemanticsNode(
                    path = listOf(2),
                    semantics = Semantics(
                        role = Role.Button,
                        label = "Early",
                        focusable = true,
                        traversalIndex = 1,
                        testTag = "early",
                    ),
                    hostTag = "button",
                ),
                SemanticsNode(
                    path = listOf(3),
                    semantics = Semantics(
                        role = Role.Button,
                        label = "No index",
                        focusable = true,
                        testTag = "no-index",
                    ),
                    hostTag = "button",
                ),
            ),
        )

        focus.update(tree)
        assertTrue(focus.requestFocus(listOf(0)))
        assertEquals(listOf(0), focus.focusedPath)
        assertEquals("Late", focus.focused?.semantics?.label)
        focus.update(tree)
        assertEquals(listOf(0), focus.focusedPath)

        assertFalse(focus.requestFocus(listOf(1)))
        assertFalse(focus.requestFocus(listOf(99)))
        assertFalse(focus.requestFocusByTestTag("static"))
        assertFalse(focus.requestFocusByTestTag("missing"))

        assertTrue(focus.requestFocusByTestTag("early"))
        assertEquals(listOf(3), focus.movePrevious()?.path)
        assertEquals(listOf(0), focus.movePrevious()?.path)

        val reverseFromNoFocus = FocusManager(tree)
        assertEquals(listOf(3), reverseFromNoFocus.movePrevious()?.path)
        reverseFromNoFocus.update(
            SemanticsTree(
                listOf(
                    SemanticsNode(
                        path = listOf(3),
                        semantics = Semantics(
                            role = Role.Button,
                            label = "No index",
                            focusable = false,
                            testTag = "no-index",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(null, reverseFromNoFocus.focusedPath)
        assertEquals(null, reverseFromNoFocus.focused)
        assertEquals(null, reverseFromNoFocus.moveNext())
    }

    @Test
    fun frameValuesBindToHostPropsAndCommitToStateExplicitly() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var offset: FrameValue
        lateinit var committed: MutableCell<Float>

        fun render(): RenderResult = runtime.render(scope) {
            committed = state(key = "committed") { 0f }
            offset = frameValue(committed.value)
            host("box", frameProps = mapOf("translateX" to offset)) {
                text("Committed: ${committed.value}")
            }
        }

        val first = render()
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
        var visible = true
        var clicks = 0

        fun render(): Node = runtime.render(scope) {
            exitGroup(key = "panel", visible = visible) {
                column(semantics = Semantics(testTag = "panel")) {
                    onExit {}
                    button(
                        onClick = event { clicks += 1 },
                        semantics = Semantics(role = Role.Button, testTag = "close", focusable = true),
                    ) {
                        text("Close")
                    }
                }
            }
        }.tree

        val shown = render()
        val shownButton = shown.findHostByTag("button")
        runtime.dispatch(shownButton.props.getValue("event:onClick"))
        assertEquals(1, clicks)
        assertFalse(scope.completeExit("missing"))
        assertFalse(scope.completeExit("panel"))

        visible = false
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
    fun asLeavingCoversNullSemanticsFragmentsAndClientRefs() {
        val node = FragmentNode(
            semantics = null,
            children = listOf(
                HostNode(
                    tag = "button",
                    props = mapOf("event:onClick" to "event-1", "id" to "close"),
                    semantics = null,
                    children = listOf(
                        TextNode("Close", semantics = null),
                        ClientRef("app.Client"),
                    ),
                ),
            ),
        )

        val leaving = assertIs<FragmentNode>(node.asLeaving())
        val host = assertIs<HostNode>(leaving.children.single())
        val text = assertIs<TextNode>(host.children[0])
        val client = assertIs<ClientRef>(host.children[1])

        assertEquals(null, leaving.semantics)
        assertEquals(mapOf("id" to "close"), host.props)
        assertEquals(true, host.semantics?.leaving)
        assertEquals(true, text.semantics?.leaving)
        assertEquals(true, client.semantics?.leaving)
    }

    @Test
    fun exitGroupWithoutOnExitDisposesImmediately() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var visible = true

        fun render(): Node = runtime.render(scope) {
            onExit {}
            exitGroup(key = "panel", visible = visible) {
                text("Panel", semantics = Semantics(testTag = "panel"))
            }
        }.tree

        assertIs<TextNode>(render())

        visible = false
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
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var visible = true

        fun render(): Node = runtime.render(scope) {
            exitGroup(key = "panel", visible = visible) {
                onExit {
                    first.await()
                    complete()
                    complete()
                }
                onExit {
                    second.await()
                    complete()
                }
                text("Panel", semantics = Semantics(testTag = "panel"))
            }
        }.tree

        assertIs<TextNode>(render())

        visible = false
        assertIs<TextNode>(render())
        assertTrue(scope.isLeaving("panel"))

        first.complete(Unit)
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

        second.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (scope.isLeaving("panel")) {
                    delay(10)
                }
            }
        }
        assertEquals(FragmentNode(), render())
    }

    @Test
    fun exitGroupTimeoutCompletesUnfinishedExitCallback() = runTest {
        val runtime = KineticaRuntime(exitTimeoutMillis = 25)
        val scope = ComponentScope(runtime)
        val never = CompletableDeferred<Unit>()
        var visible = true

        fun render(): Node = runtime.render(scope) {
            exitGroup(key = "panel", visible = visible) {
                onExit {
                    never.await()
                    complete()
                }
                text("Panel", semantics = Semantics(testTag = "panel"))
            }
        }.tree

        assertIs<TextNode>(render())

        visible = false
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
    }

    @Test
    fun lazyEachUsesViewportAndRetainsKeyedItemState() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val items = lazyItems(listOf("one", "two"), estimatedSize = 2)
        var listState = lazyListState(firstVisibleIndex = 0, visibleCount = 1)

        fun render(): Node = runtime.render(scope) {
            column {
                lazyEach(
                    items = items,
                    key = { it },
                    state = listState,
                ) { item ->
                    var count by state { 0 }
                    button(onClick = event { count += 1 }) {
                        text("$item:$count")
                    }
                }
            }
        }.tree

        val first = render()
        assertEquals(listOf("one:0"), first.findTexts().map { it.value })
        runtime.dispatch(first.findHostByTag("button").props.getValue("event:onClick"))
        assertEquals(listOf("one:1"), render().findTexts().map { it.value })

        listState = listState.scrollTo(firstVisibleIndex = 1)
        val second = render()
        assertEquals(listOf("two:0"), second.findTexts().map { it.value })
        runtime.dispatch(second.findHostByTag("button").props.getValue("event:onClick"))
        assertEquals(listOf("two:1"), render().findTexts().map { it.value })

        listState = listState.scrollTo(firstVisibleIndex = 0)
        assertEquals(listOf("one:1"), render().findTexts().map { it.value })
    }

    @Test
    fun lazyEachRendersPendingItemPlaceholderWithoutBlockingViewport() = runTest {
        data class PendingLazyKey(val id: Int) : ResourceKey

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val release = CompletableDeferred<Unit>()
        val pendingKey = PendingLazyKey(runtime.hashCode() + scope.hashCode())

        fun render(): Node = runtime.render(scope) {
            column {
                lazyEach(
                    items = lazyItems(listOf("one", "two"), estimatedSize = 2),
                    key = { item -> item },
                    state = lazyListState(visibleCount = 2),
                    placeholder = { item -> text("loading-$item") },
                ) { item ->
                    if (item == "one") {
                        val value = resource(pendingKey) {
                            release.await()
                            "loaded-one"
                        }.read()
                        text(value)
                    } else {
                        text(item)
                    }
                }
            }
        }.tree

        assertEquals(listOf("loading-one", "two"), render().findTexts().map { it.value })
        assertTrue(
            runtime.journal().any { entry ->
                entry.kind == JournalKind.ResourceLoad &&
                    entry.message == "lazyEach item pending" &&
                    entry.attributes["key"] == "one"
            },
        )

        release.complete(Unit)
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
        val items = lazyItems(listOf("one", "two"), estimatedSize = 2)
        var listState = lazyListState(firstVisibleIndex = 0, visibleCount = 1)

        fun render(): Node = runtime.render(scope) {
            column {
                lazyEach(
                    items = items,
                    key = { it },
                    retain = RetainPolicy.VisibleOnly,
                    state = listState,
                ) { item ->
                    var count by state { 0 }
                    button(onClick = event { count += 1 }) {
                        text("$item:$count")
                    }
                }
            }
        }.tree

        val first = render()
        runtime.dispatch(first.findHostByTag("button").props.getValue("event:onClick"))
        assertEquals(listOf("one:1"), render().findTexts().map { it.value })

        listState = listState.scrollTo(firstVisibleIndex = 1)
        assertEquals(listOf("two:0"), render().findTexts().map { it.value })

        listState = listState.scrollTo(firstVisibleIndex = 0)
        assertEquals(listOf("one:0"), render().findTexts().map { it.value })
    }

    @Test
    fun journalIsBoundedExportableAndReplayableWithSlotSnapshots() {
        val countSlot = SlotId("todo", "Counter", 0, "count")
        val runtime = KineticaRuntime(journalCapacity = 8)
        val scope = ComponentScope(runtime)

        fun render(): RenderResult = runtime.render(scope) {
            var count by state(slotId = countSlot, persistent = true) { 0 }
            button(onClick = event { count += 1 }) {
                text("Count: $count")
            }
        }

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

        val latestSlot = replay.latest().render?.slots?.slots?.single { it.slotId == countSlot }
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

    private sealed interface TestRoute : Route {
        data object Home : TestRoute {
            override fun toString(): String = "Home"
        }

        data class Details(val id: String) : TestRoute
    }
}

private data object TodosKey : ResourceKey

private data object AsyncBoundaryKey : ResourceKey

private data object FailingBoundaryKey : ResourceKey

private data object ComponentScopedKey : ResourceKey

private data object AppTtlKey : ResourceKey

@Serializable
private data class CartQuantityDraft(
    val productId: String,
    val quantity: Int,
)

@Serializable
private data class OptionalCartPatch(
    val productId: String,
    val quantity: Int = 1,
    val note: String? = null,
)

@Serializable
private enum class CartStatus {
    Pending,
    Done,
}

@Serializable
private data class CartStatusPatch(
    val productId: String,
    val status: CartStatus,
)

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
