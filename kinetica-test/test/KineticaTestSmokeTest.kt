package io.heapy.kinetica.testing

import io.heapy.kinetica.launchEffect
import io.heapy.kinetica.ClientRef
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.JournalKind
import io.heapy.kinetica.ResourceKey
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.button
import io.heapy.kinetica.checkbox
import io.heapy.kinetica.column
import io.heapy.kinetica.event
import io.heapy.kinetica.loadingBoundary
import io.heapy.kinetica.resource
import io.heapy.kinetica.state
import io.heapy.kinetica.store
import io.heapy.kinetica.text
import io.heapy.kinetica.textInput
import io.heapy.kinetica.testing.KineticaTest
import io.heapy.kinetica.testing.hasRole
import io.heapy.kinetica.testing.hasTestTag
import io.heapy.kinetica.testing.hasText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KineticaTestSmokeTest {
    @Test
    fun headlessRootCanRenderSuspendContentAndDispatchEvents() = runTest {
        val root = KineticaTest.renderSuspend {
            delay(10)
            StatusButtonApp()
        }

        assertEquals("ready", root.node(hasText("ready")).node.let { (it as io.heapy.kinetica.TextNode).value })

        root.click(hasTestTag("status"))

        assertEquals("clicked", root.node(hasText("clicked")).node.let { (it as io.heapy.kinetica.TextNode).value })
        assertEquals("cell write", root.journal().last { it.kind == JournalKind.RenderCommitted }.attributes["cause"])
        root.dispose()
    }

    @Test
    fun suspendRootSupportsInputSubmitAndComposedMatchers() = runTest {
        val root = KineticaTest.renderSuspend {
            CommitFormApp()
        }

        root.input(hasRole(Role.TextInput) and hasTestTag("draft"), "Hello")
        root.node(hasTestTag("draft")).submit()

        assertEquals("Committed: Hello", root.node(hasText("Committed: Hello")).node.let { (it as io.heapy.kinetica.TextNode).value })

        root.input(hasTestTag("draft"), "")
        root.click(hasTestTag("commit"))

        assertEquals("Committed: empty", root.node(hasText("Committed: empty")).node.let { (it as io.heapy.kinetica.TextNode).value })
        assertFailsWith<IllegalStateException> {
            root.node(hasTestTag("missing"))
        }
        root.dispose()
    }

    @Test
    fun headlessRootCanQueryAndDispatchBySemantics() {
        val root = KineticaTest.render {
            CounterApp()
        }

        root.click(hasTestTag("increment"))

        assertEquals("Count: 1", root.node(hasText("Count: 1")).node.let { (it as io.heapy.kinetica.TextNode).value })
    }

    @Test
    fun headlessRootTraversesClientRefsAndReportsMissingRenderedState() {
        val root = KineticaTest.render {
            emit(ClientRef("app.ClientIsland"))
            text("After island")
        }

        assertEquals("After island", root.node(hasText("After island")).node.let { (it as io.heapy.kinetica.TextNode).value })
        assertFailsWith<IllegalStateException> {
            root.node(hasTestTag("missing"))
        }

        root.dispose()
        assertFailsWith<IllegalStateException> {
            root.tree()
        }
    }

    @Test
    fun headlessRootDispatchRendersOnlyOncePerHandledEvent() {
        renderCount = 0
        val root = KineticaTest.render {
            RenderCountingApp()
        }

        assertEquals(1, renderCount)

        root.click(hasTestTag("increment"))

        assertEquals(2, renderCount)
        assertEquals("Count: 1", root.node(hasText("Count: 1")).node.let { (it as io.heapy.kinetica.TextNode).value })
    }

    @Test
    fun headlessNodeClickDispatchesToggleAndIgnoresTextNodes() {
        val root = KineticaTest.render {
            AcceptCheckboxApp()
        }

        root.node(hasText("Accepted: false")).click()
        assertEquals("Accepted: false", root.node(hasText("Accepted: false")).node.let { (it as io.heapy.kinetica.TextNode).value })

        root.node(hasRole(Role.Checkbox) and hasTestTag("accept")).click()

        assertEquals("Accepted: true", root.node(hasText("Accepted: true")).node.let { (it as io.heapy.kinetica.TextNode).value })
    }

    @Test
    fun headlessRootAdvancesVirtualTimeInJournal() {
        val root = KineticaTest.render {
            text("Clock")
        }

        root.advanceTimeBy(250)

        val virtualTimeEntry = root.journal().single { entry -> entry.kind == JournalKind.VirtualTime }
        assertEquals("250", virtualTimeEntry.attributes["millis"])
        assertEquals("250", virtualTimeEntry.attributes["now"])
        assertEquals("virtual time", root.journal().last { it.kind == JournalKind.RenderCommitted }.attributes["cause"])
    }

    @Test
    fun suspendRootAwaitIdleAndVirtualTimeMirrorHeadlessRootBehavior() = runTest {
        val root = KineticaTest.renderSuspend {
            LoadingToReadyApp()
        }

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                root.awaitIdle()
            }
        }

        assertEquals("ready", root.node(hasText("ready")).node.let { (it as io.heapy.kinetica.TextNode).value })

        root.advanceTimeBy(125)

        val virtualTimeEntry = root.journal().single { entry ->
            entry.kind == JournalKind.VirtualTime && entry.attributes["millis"] == "125"
        }
        assertEquals("125", virtualTimeEntry.attributes["now"])
        assertEquals("virtual time", root.journal().last { it.kind == JournalKind.RenderCommitted }.attributes["cause"])
        root.dispose()
    }

    @Test
    fun headlessRootDisposeCancelsEffects() = runTest {
        effectStarted = CompletableDeferred()
        effectDisposed = CompletableDeferred()
        val root = KineticaTest.render {
            DisposeSignalsApp()
        }

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                effectStarted.await()
            }
        }

        root.dispose()

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                effectDisposed.await()
            }
        }
    }

    @Test
    fun headlessRootDisposeStopsSharedStoreInvalidationsFromRevivingRoot() = runTest {
        sharedStore = store("before")
        val root = KineticaTest.render {
            SharedStoreApp()
        }

        root.dispose()
        sharedStore.value = "after"

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                root.awaitIdle()
            }
        }

        assertFailsWith<IllegalStateException> {
            root.tree()
        }
    }

    @Test
    fun suspendRootDisposeStopsSharedStoreInvalidationsFromRevivingRoot() = runTest {
        sharedStore = store("before")
        val root = KineticaTest.renderSuspend {
            SharedStoreApp()
        }

        root.dispose()
        sharedStore.value = "after"

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                root.awaitIdle()
            }
        }

        assertFailsWith<IllegalStateException> {
            root.tree()
        }
    }

    @Test
    fun headlessRootAwaitIdleCommitsFiniteEffectInvalidations() = runTest {
        val root = KineticaTest.render {
            LoadingToReadyApp()
        }

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                root.awaitIdle()
            }
        }

        assertEquals("ready", root.node(hasText("ready")).node.let { (it as io.heapy.kinetica.TextNode).value })
        assertEquals("cell write", root.journal().last { it.kind == JournalKind.RenderCommitted }.attributes["cause"])
    }

    @Test
    fun headlessRootAwaitIdleDoesNotBlockOnLongLivedDisposeEffects() = runTest {
        effectStarted = CompletableDeferred()
        val root = KineticaTest.render {
            LongLivedEffectApp()
        }

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                root.awaitIdle()
            }
        }

        assertTrue(effectStarted.isCompleted)
        root.dispose()
    }

    @Test
    fun headlessRootAwaitIdleCommitsResourceResumes() = runTest {
        val root = KineticaTest.render {
            AwaitIdleResourceApp()
        }

        assertEquals("Loading", root.node(hasText("Loading")).node.let { (it as io.heapy.kinetica.TextNode).value })

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                root.awaitIdle()
            }
        }

        assertEquals("Loaded", root.node(hasText("Loaded")).node.let { (it as io.heapy.kinetica.TextNode).value })
    }
}

private data class TestResourceKey(val id: String) : ResourceKey

private var renderCount = 0
private var effectStarted = CompletableDeferred<Unit>()
private var effectDisposed = CompletableDeferred<Unit>()
private var sharedStore = store("before")

@UiComponent
private fun ComponentScope.StatusButtonApp() {
    var status by state { "ready" }
    button(
        onClick = event { status = "clicked" },
        semantics = Semantics(role = Role.Button, testTag = "status"),
    ) {
        text(status)
    }
}

@UiComponent
private fun ComponentScope.CommitFormApp() {
    var draft by state { "" }
    var committed by state { "none" }
    val commit = event {
        committed = draft.ifBlank { "empty" }
        draft = ""
    }

    column {
        textInput(
            value = draft,
            onInput = event<String> { draft = it },
            onSubmit = commit,
            semantics = Semantics(role = Role.TextInput, testTag = "draft", focusable = true),
        )
        button(
            onClick = commit,
            semantics = Semantics(role = Role.Button, testTag = "commit", focusable = true),
        ) {
            text("Commit")
        }
        text("Committed: $committed")
    }
}

@UiComponent
private fun ComponentScope.CounterApp() {
    var count by state { 0 }
    column {
        text("Count: $count")
        button(
            onClick = event { count += 1 },
            semantics = Semantics(role = Role.Button, testTag = "increment"),
        ) {
            text("Increment")
        }
    }
}

@UiComponent
private fun ComponentScope.RenderCountingApp() {
    renderCount += 1
    var count by state { 0 }
    button(
        onClick = event { count += 1 },
        semantics = Semantics(role = Role.Button, testTag = "increment"),
    ) {
        text("Count: $count")
    }
}

@UiComponent
private fun ComponentScope.AcceptCheckboxApp() {
    var checked by state { false }
    column {
        checkbox(
            checked = checked,
            onToggle = event { checked = !checked },
            semantics = Semantics(role = Role.Checkbox, testTag = "accept"),
        )
        text("Accepted: $checked")
    }
}

@UiComponent
private fun ComponentScope.LoadingToReadyApp() {
    var status by state { "loading" }
    launchEffect {
        status = "ready"
    }
    text(status)
}

@UiComponent
private fun ComponentScope.DisposeSignalsApp() {
    launchEffect {
        effectStarted.complete(Unit)
        awaitDispose { effectDisposed.complete(Unit) }
    }
    text("Running")
}

@UiComponent
private fun ComponentScope.LongLivedEffectApp() {
    launchEffect {
        effectStarted.complete(Unit)
        awaitDispose()
    }
    text("Running")
}

@UiComponent
private fun ComponentScope.SharedStoreApp() {
    text("Shared: ${sharedStore.value}")
}

@UiComponent
private fun ComponentScope.AwaitIdleResourceApp() {
    loadingBoundary(
        fallback = { text("Loading") },
    ) {
        val value = resource(TestResourceKey("await-idle-resource")) { "Loaded" }.read()
        text(value)
    }
}
