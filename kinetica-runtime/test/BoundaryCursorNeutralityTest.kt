package io.heapy.kinetica

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Boundaries render content and fallback in their own key scopes and restore the positional
 * cursors on every exit, so they consume a constant number of positions no matter how far
 * content got before throwing. Siblings after a boundary keep their slot identity across
 * error/pending/ready transitions, and a fallback's own slots are independent of where the
 * content failed.
 */
class BoundaryCursorNeutralityTest {
    private data class NeutralityResourceKey(val id: Int) : ResourceKey

    @Test
    fun siblingSurvivesErrorBoundaryThrowsAtDifferentDepths() {
        val runtime = KineticaRuntime(debug = true)
        val scope = ComponentScope(runtime)
        var throwAt = -1
        lateinit var sibling: MutableCell<Int>
        lateinit var fallbackCell: MutableCell<Int>
        lateinit var retryRef: BoundaryRetry
        var fallbackShown = false

        fun render() = runtime.render(scope) {
            errorBoundary(
                fallback = { _, _, retry ->
                    fallbackShown = true
                    retryRef = retry
                    fallbackCell = state { 100 }
                    text("fallback:${fallbackCell.value}")
                },
            ) {
                state { 1 }
                if (throwAt == 1) error("boom at depth 1")
                state { 2 }
                state { 3 }
                if (throwAt == 3) error("boom at depth 3")
                text("content")
            }
            sibling = state { 0 }
            text("sibling:${sibling.value}")
        }

        render()
        val original = sibling
        sibling.value = 42
        render()
        assertSame(original, sibling)

        throwAt = 1
        render()
        assertTrue(fallbackShown)
        assertSame(original, sibling, "sibling shifted when content threw at depth 1")
        val firstFallbackCell = fallbackCell
        fallbackCell.value = 7

        // A later render throwing at a DIFFERENT depth must land the fallback on the same
        // slots: the boundary resets to its mark before rendering the fresh-catch fallback.
        retryRef.retry()
        throwAt = 3
        render()
        assertSame(firstFallbackCell, fallbackCell, "fallback identity depends on content throw depth")
        assertEquals(7, fallbackCell.value)
        assertSame(original, sibling, "sibling shifted when content threw at depth 3")

        retryRef.retry()
        throwAt = -1
        render()
        assertSame(original, sibling)
        assertEquals(42, sibling.value)
    }

    @Test
    fun siblingSurvivesLoadingBoundaryPendingMidContent() = runTest {
        val runtime = KineticaRuntime(debug = true)
        val scope = ComponentScope(runtime)
        val release = CompletableDeferred<String>()
        val key = NeutralityResourceKey(hashCode())
        lateinit var sibling: MutableCell<Int>
        var sawFallback = false

        fun render() = runtime.render(scope) {
            loadingBoundary(
                retainPrevious = false,
                fallback = {
                    sawFallback = true
                    state { 9 }
                    text("loading")
                },
            ) {
                state { 1 }
                val value = resource(key, scope = CacheScope.Component) { release.await() }.read()
                text(value)
            }
            sibling = state { 0 }
            text("sibling:${sibling.value}")
        }

        render()
        assertTrue(sawFallback)
        val original = sibling
        sibling.value = 42

        release.complete("ready")
        withContext(Dispatchers.Default) { withTimeout(5_000) { runtime.awaitIdle() } }
        render()
        assertSame(original, sibling, "sibling shifted when the boundary content resolved")
        assertEquals(42, sibling.value)
    }

    @Test
    fun siblingSurvivesSuspendSubtreeResolution() = runTest {
        val runtime = KineticaRuntime(debug = true)
        val scope = ComponentScope(runtime)
        val release = CompletableDeferred<Unit>()
        lateinit var sibling: MutableCell<Int>

        fun render() = runtime.render(scope) {
            suspendSubtree(fallback = {
                state { 9 }
                text("pending")
            }) {
                release.await()
                text("resolved")
            }
            sibling = state { 0 }
            text("sibling:${sibling.value}")
        }

        render()
        val original = sibling
        sibling.value = 42

        release.complete(Unit)
        withContext(Dispatchers.Default) { withTimeout(5_000) { runtime.awaitIdle() } }
        render()
        assertSame(original, sibling, "sibling shifted when the suspend subtree resolved")
        assertEquals(42, sibling.value)
    }

    @Test
    fun siblingSurvivesExitGroupVisibilityToggle() {
        val runtime = KineticaRuntime(debug = true)
        val scope = ComponentScope(runtime)
        var visible = true
        lateinit var sibling: MutableCell<Int>

        fun render() = runtime.render(scope) {
            exitGroup(key = "group", visible = visible) {
                state { 1 }
                state { 2 }
                text("group content")
            }
            sibling = state { 0 }
            text("sibling:${sibling.value}")
        }

        render()
        val original = sibling
        sibling.value = 42
        render()
        assertSame(original, sibling)

        visible = false
        render()
        assertSame(original, sibling, "sibling shifted when the exit group left")
        assertEquals(42, sibling.value)

        visible = true
        render()
        assertSame(original, sibling)
        assertEquals(42, sibling.value)
    }
}
