package io.heapy.kinetica.testing

import io.heapy.kinetica.BoundaryRetry
import io.heapy.kinetica.CacheScope
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.JournalKind
import io.heapy.kinetica.ResourceKey
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.button
import io.heapy.kinetica.each
import io.heapy.kinetica.errorBoundary
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.invalidate
import io.heapy.kinetica.launchEffect
import io.heapy.kinetica.loadingBoundary
import io.heapy.kinetica.resource
import io.heapy.kinetica.state
import io.heapy.kinetica.store
import io.heapy.kinetica.text
import io.heapy.kinetica.textInput
import io.heapy.kinetica.testing.KineticaTest
import io.heapy.kinetica.testing.hasTestTag
import io.heapy.kinetica.testing.htmlSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorBoundarySoundnessTest {
    /** KSND-068 (sources: SVL-100, SOL-103, PRE-147, RCT-420). */
    @Test
    fun initialRenderThrowShowsFallbackAndKeepsOutsideSiblings() {
        val probe = BoundaryProbe()
        probe.fail.value = true
        val root = KineticaTest.render {
            InitialThrowBoundaryApp(probe)
        }

        try {
            val html = root.htmlSnapshot()
            assertInOrder(html, "before", "err:boom", "after")
        } finally {
            root.dispose()
        }
    }

    /** KSND-069 (sources: SVL-101, INF-129, RCT-418). */
    @Test
    fun updateThrowReplacesContentWithFallbackAndCommits() {
        val probe = BoundaryProbe()
        val root = KineticaTest.render {
            UpdateThrowBoundaryApp(probe)
        }

        try {
            root.click(hasTestTag("content-bump"))
            root.click(hasTestTag("content-bump"))
            assertTrue("content:2" in root.htmlSnapshot())

            val committedBefore = root.journal().count { entry -> entry.kind == JournalKind.RenderCommitted }
            root.click(hasTestTag("fail"))

            val html = root.htmlSnapshot()
            assertTrue("err:boom" in html)
            assertFalse("content:" in html)
            assertEquals(committedBefore + 1, root.journal().count { entry -> entry.kind == JournalKind.RenderCommitted })
        } finally {
            root.dispose()
        }
    }

    /** KSND-070 (sources: SOL-104, SVL-102, PRE-155). */
    @Test
    fun retryAfterConditionIsFixedRestoresContent() {
        val probe = BoundaryProbe()
        probe.fail.value = true
        val root = KineticaTest.render {
            RetryBoundaryApp(probe)
        }

        try {
            assertTrue("err:boom" in root.htmlSnapshot())
            probe.fail.value = false

            root.click(hasTestTag("retry"))

            val html = root.htmlSnapshot()
            assertTrue("content:0" in html)
            assertFalse("err:boom" in html)
            assertEquals(1, probe.contentInits)
        } finally {
            root.dispose()
        }
    }

    /** KSND-071 (sources: SVL-107). */
    @Test
    fun retryWhileStillFailingReturnsToSingleStableFallback() {
        val probe = BoundaryProbe()
        probe.fail.value = true
        val root = KineticaTest.render {
            RetryBoundaryApp(probe)
        }

        try {
            assertEquals(listOf("captured:boom"), probe.log)

            root.click(hasTestTag("retry"))
            assertEquals(listOf("captured:boom", "captured:boom"), probe.log)
            assertEquals(1, root.htmlSnapshot().countOccurrences("err:boom"))

            root.click(hasTestTag("retry"))
            assertEquals(listOf("captured:boom", "captured:boom", "captured:boom"), probe.log)
            assertEquals(1, root.htmlSnapshot().countOccurrences("err:boom"))

            probe.fail.value = false
            root.click(hasTestTag("retry"))
            assertTrue("content:0" in root.htmlSnapshot())
        } finally {
            root.dispose()
        }
    }

    /** KSND-072 (sources: SVL-102, SOL-101, RCT-423). */
    @Test
    fun nestedBoundaryInnerCatchesAndOuterContentStaysMounted() {
        val probe = BoundaryProbe()
        val root = KineticaTest.render {
            NestedBoundaryApp(probe)
        }

        try {
            root.click(hasTestTag("fail"))

            val html = root.htmlSnapshot()
            assertTrue("outer-ok" in html)
            assertTrue("inner-err:boom" in html)
            assertFalse("outer-err:" in html)
            assertEquals(0, probe.fallbackInits)
        } finally {
            root.dispose()
        }
    }

    /** KSND-073 (sources: SOL-105, SVL-106, PRE-150). */
    @Test
    fun throwingFallbackEscalatesToOuterBoundary() {
        val probe = BoundaryProbe()
        val root = KineticaTest.render {
            FallbackThrowsBoundaryApp(probe)
        }

        try {
            root.click(hasTestTag("fail"))

            val html = root.htmlSnapshot()
            assertTrue("outer:fallback-boom" in html)
            assertFalse("inner-broken" in html)
            assertEquals(listOf("inner-captured", "outer-captured"), probe.log)
        } finally {
            root.dispose()
        }
    }

    /** KSND-074 (sources: RCT-418, memory:boundary-slot-collision). */
    @Test
    fun fallbackAndContentSlotsRemainIsolatedAcrossErrorRetryCycles() {
        val probe = BoundaryProbe()
        val root = KineticaTest.render {
            SlotIsolationBoundaryApp(probe)
        }

        try {
            root.click(hasTestTag("content-bump"))
            root.click(hasTestTag("content-bump"))
            root.input(hasTestTag("content-input"), "alpha")
            assertTrue("content:2" in root.htmlSnapshot())

            root.click(hasTestTag("fail"))
            assertTrue("fallback:100" in root.htmlSnapshot())
            root.input(hasTestTag("fallback-input"), "beta")
            root.click(hasTestTag("fallback-bump"))
            assertTrue("fallback:103" in root.htmlSnapshot())

            root.click(hasTestTag("fix"))
            root.click(hasTestTag("retry"))
            val recovered = root.htmlSnapshot()
            // Boundary branch frames deactivate like other retained frame families (KSND-043):
            // transient slots/events are disposed, but ordinary branch state survives mode switches.
            assertTrue("content:2" in recovered)
            assertFalse("fallback:" in recovered)
            root.input(hasTestTag("content-input"), "gamma")

            root.click(hasTestTag("fail"))
            val failedAgain = root.htmlSnapshot()
            assertTrue("fallback:103" in failedAgain)
            assertFalse("content:" in failedAgain)
            assertEquals(1, probe.contentInits)
            assertEquals(1, probe.fallbackInits)
            assertEquals(listOf("content:alpha", "fallback:beta", "content:gamma"), probe.log)
        } finally {
            root.dispose()
        }
    }

    /** KSND-075 (sources: PRE-157, INF-129, SVL-105). */
    @Test
    fun perRowBoundariesKeepSiblingStateAndFailureFollowsKey() {
        val probe = RowBoundaryProbe()
        val root = KineticaTest.render {
            PerRowBoundaryApp(probe)
        }

        try {
            root.click(hasTestTag("bump-a"))
            root.click(hasTestTag("bump-c"))
            root.click(hasTestTag("fail-b"))

            val failed = root.htmlSnapshot()
            assertTrue("a:1" in failed)
            assertTrue("b-fallback:b" in failed)
            assertTrue("c:1" in failed)
            assertEquals(mapOf("a" to 1, "b" to 1, "c" to 1), probe.inits)

            root.click(hasTestTag("reorder"))

            val reordered = root.htmlSnapshot()
            assertInOrder(reordered, "c:1", "b-fallback:b", "a:1")
            assertEquals(mapOf("a" to 1, "b" to 1, "c" to 1), probe.inits)
        } finally {
            root.dispose()
        }
    }

    /** KSND-076 (sources: RCT-421, SOL-009, INF-128). */
    @Test
    fun eventHandlerThrowSurfacesButRuntimeRemainsUsable() {
        val root = KineticaTest.render {
            EventHandlerThrowApp()
        }

        try {
            val failure = assertFailsWith<IllegalStateException> {
                root.click(hasTestTag("bad-event"))
            }
            assertEquals("handler-boom", failure.message)
            assertFalse("event-err:" in root.htmlSnapshot())

            val committedBefore = root.journal().count { entry -> entry.kind == JournalKind.RenderCommitted }
            root.click(hasTestTag("healthy-event"))

            val html = root.htmlSnapshot()
            assertTrue("healthy:1" in html)
            assertFalse("event-err:" in html)
            assertEquals(committedBefore + 1, root.journal().count { entry -> entry.kind == JournalKind.RenderCommitted })
        } finally {
            root.dispose()
        }
    }

    /** KSND-077 (sources: SVL-103, RCT-422, PRE-113). */
    @Test
    fun launchEffectThrowUnderBoundaryDoesNotCorruptLaterUpdates() = runTest {
        val probe = EffectBoundaryProbe()
        val root = KineticaTest.render {
            EffectThrowBoundaryApp(probe)
        }

        try {
            assertTrue("effect-body" in root.htmlSnapshot())

            settle(root)

            assertTrue("fx-err:fx" in root.htmlSnapshot())
            probe.unrelated.value = 1
            settle(root)

            val html = root.htmlSnapshot()
            assertTrue("fx-err:fx" in html)
            assertTrue("outside:1" in html)
        } finally {
            root.dispose()
        }
    }

    /** KSND-078 (sources: SVL-109, SOL-100, INF-130). */
    @Test
    fun unhandledRenderErrorThrowsToCallerAndFreshRenderWorks() {
        val failing = BoundaryProbe()
        failing.fail.value = true

        val failure = assertFailsWith<IllegalStateException> {
            KineticaTest.render {
                UnboundedThrowingApp(failing)
            }
        }
        assertEquals("boom", failure.message)

        val recovered = BoundaryProbe()
        val root = KineticaTest.render {
            UnboundedThrowingApp(recovered)
        }

        try {
            assertEquals("ok", root.htmlSnapshot())
        } finally {
            root.dispose()
        }
    }

    /** KSND-079 (sources: SOL-106, SOL-112, extends BoundaryRetryEventTest). */
    @Test
    fun errorBoundaryAndLoadingBoundaryRecoverFailingResourceOnRetry() = runTest {
        val probe = ResourceBoundaryProbe("errorBoundaryAndLoadingBoundaryRecoverFailingResourceOnRetry")
        val root = KineticaTest.render {
            ResourceBoundaryApp(probe)
        }

        try {
            assertEquals("loading", root.htmlSnapshot())

            settle(root)

            assertTrue("res-err:resource-boom" in root.htmlSnapshot())
            probe.fail.value = false
            root.click(hasTestTag("retry"))
            assertEquals("loading", root.htmlSnapshot())

            settle(root)

            assertEquals("value:data", root.htmlSnapshot())
        } finally {
            root.dispose()
        }
    }
}

private class BoundaryProbe {
    val fail = store(false)
    var contentInits = 0
    var fallbackInits = 0
    val log = mutableListOf<String>()
}

private class RowBoundaryProbe {
    val rows = store(listOf("a", "b", "c"))
    val failKey = store<String?>(null)
    val inits = mutableMapOf<String, Int>()
}

private class EffectBoundaryProbe {
    val effectFails = store(true)
    val unrelated = store(0)
}

private class ResourceBoundaryProbe(
    val salt: String,
) {
    val fail = store(true)
}

private data class ResourceBoundaryKey(
    val salt: String,
) : ResourceKey

@UiComponent(skippable = false)
private fun ComponentScope.InitialThrowBoundaryApp(probe: BoundaryProbe) {
    host("div") {
        text("before")
        errorBoundary(
            fallback = { error, _, _ -> text("err:${error.message}") },
        ) {
            PlainThrower(probe)
        }
        text("after")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.UpdateThrowBoundaryApp(probe: BoundaryProbe) {
    host("div") {
        FailButton(probe)
        errorBoundary(
            fallback = { error, _, _ -> text("err:${error.message}") },
        ) {
            StatefulContent(probe)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.RetryBoundaryApp(probe: BoundaryProbe) {
    errorBoundary(
        fallback = { error, _, retry ->
            probe.log += "captured:${error.message}"
            RetryFallback(error, retry)
        },
    ) {
        RetryContent(probe)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.NestedBoundaryApp(probe: BoundaryProbe) {
    host("div") {
        FailButton(probe)
        errorBoundary(
            fallback = { error, _, _ ->
                probe.fallbackInits++
                text("outer-err:${error.message}")
            },
        ) {
            text("outer-ok")
            errorBoundary(
                fallback = { error, _, _ -> text("inner-err:${error.message}") },
            ) {
                PlainThrower(probe)
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.FallbackThrowsBoundaryApp(probe: BoundaryProbe) {
    host("div") {
        FailButton(probe)
        errorBoundary(
            fallback = { error, _, _ ->
                probe.log += "outer-captured"
                text("outer:${error.message}")
            },
        ) {
            errorBoundary(
                fallback = { _, _, _ ->
                    probe.log += "inner-captured"
                    text("inner-broken")
                    error("fallback-boom")
                },
            ) {
                PlainThrower(probe)
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SlotIsolationBoundaryApp(probe: BoundaryProbe) {
    host("div") {
        FailButton(probe)
        button(
            onClick = event { probe.fail.value = false },
            semantics = Semantics(role = Role.Button, testTag = "fix"),
        ) {
            text("fix")
        }
        errorBoundary(
            fallback = { error, _, retry -> SlotIsolationFallback(probe, error, retry) },
        ) {
            SlotIsolationContent(probe)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.PerRowBoundaryApp(probe: RowBoundaryProbe) {
    host("div") {
        button(
            onClick = event { probe.failKey.value = "b" },
            semantics = Semantics(role = Role.Button, testTag = "fail-b"),
        ) {
            text("fail b")
        }
        button(
            onClick = event { probe.rows.value = listOf("c", "b", "a") },
            semantics = Semantics(role = Role.Button, testTag = "reorder"),
        ) {
            text("reorder")
        }
        host("rows") {
            each(probe.rows.value, key = { row -> row }) { row ->
                BoundaryRow(probe, row)
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.EventHandlerThrowApp() {
    var count by state { 0 }
    host("div") {
        errorBoundary(
            fallback = { error, _, _ -> text("event-err:${error.message}") },
        ) {
            button(
                onClick = event { error("handler-boom") },
                semantics = Semantics(role = Role.Button, testTag = "bad-event"),
            ) {
                text("bad")
            }
        }
        button(
            onClick = event { count += 1 },
            semantics = Semantics(role = Role.Button, testTag = "healthy-event"),
        ) {
            text("healthy")
        }
        text("healthy:$count")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.EffectThrowBoundaryApp(probe: EffectBoundaryProbe) {
    host("div") {
        errorBoundary(
            fallback = { error, _, _ -> text("fx-err:${error.message}") },
        ) {
            launchEffect {
                if (probe.effectFails.value) {
                    error("fx")
                }
            }
            text("effect-body")
        }
        text("outside:${probe.unrelated.value}")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.UnboundedThrowingApp(probe: BoundaryProbe) {
    if (probe.fail.value) {
        error("boom")
    }
    text("ok")
}

@UiComponent(skippable = false)
private fun ComponentScope.ResourceBoundaryApp(probe: ResourceBoundaryProbe) {
    errorBoundary(
        fallback = { error, _, retry -> ResourceFallback(probe, error, retry) },
    ) {
        loadingBoundary(fallback = { text("loading") }) {
            val value = resource(
                key = ResourceBoundaryKey(probe.salt),
                scope = CacheScope.Component,
            ) {
                if (probe.fail.value) {
                    error("resource-boom")
                }
                "data"
            }.read()
            text("value:$value")
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.PlainThrower(probe: BoundaryProbe) {
    if (probe.fail.value) {
        error("boom")
    }
    text("plain-ok")
}

@UiComponent(skippable = false)
private fun ComponentScope.StatefulContent(probe: BoundaryProbe) {
    var count by state { probe.contentInits++; 0 }
    if (probe.fail.value) {
        error("boom")
    }
    text("content:$count")
    button(
        onClick = event { count += 1 },
        semantics = Semantics(role = Role.Button, testTag = "content-bump"),
    ) {
        text("content bump")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.RetryContent(probe: BoundaryProbe) {
    if (probe.fail.value) {
        error("boom")
    }
    var count by state { probe.contentInits++; 0 }
    text("content:$count")
}

@UiComponent(skippable = false)
private fun ComponentScope.RetryFallback(
    error: Throwable,
    retry: BoundaryRetry,
) {
    text("err:${error.message}")
    button(
        onClick = event { retry.retry() },
        semantics = Semantics(role = Role.Button, testTag = "retry"),
    ) {
        text("retry")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SlotIsolationContent(probe: BoundaryProbe) {
    var count by state { probe.contentInits++; 0 }
    if (probe.fail.value) {
        error("boom")
    }
    text("content:$count")
    textInput(
        value = "",
        onInput = event<String> { value -> probe.log += "content:$value" },
        semantics = Semantics(role = Role.TextInput, testTag = "content-input"),
    )
    button(
        onClick = event { count += 1 },
        semantics = Semantics(role = Role.Button, testTag = "content-bump"),
    ) {
        text("content bump")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SlotIsolationFallback(
    probe: BoundaryProbe,
    error: Throwable,
    retry: BoundaryRetry,
) {
    var count by state { probe.fallbackInits++; 100 }
    text("fallback:$count")
    text("fallback-err:${error.message}")
    textInput(
        value = "",
        onInput = event<String> { value -> probe.log += "fallback:$value" },
        semantics = Semantics(role = Role.TextInput, testTag = "fallback-input"),
    )
    button(
        onClick = event { count += 3 },
        semantics = Semantics(role = Role.Button, testTag = "fallback-bump"),
    ) {
        text("fallback bump")
    }
    button(
        onClick = event { retry.retry() },
        semantics = Semantics(role = Role.Button, testTag = "retry"),
    ) {
        text("retry")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.BoundaryRow(
    probe: RowBoundaryProbe,
    row: String,
) {
    host("row", key = row) {
        errorBoundary(
            fallback = { error, _, _ -> text("$row-fallback:${error.message}") },
        ) {
            BoundaryRowContent(probe, row)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.BoundaryRowContent(
    probe: RowBoundaryProbe,
    row: String,
) {
    var count by state {
        probe.inits[row] = (probe.inits[row] ?: 0) + 1
        0
    }
    if (probe.failKey.value == row) {
        error(row)
    }
    text("$row:$count")
    button(
        onClick = event { count += 1 },
        semantics = Semantics(role = Role.Button, testTag = "bump-$row"),
    ) {
        text("bump $row")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.FailButton(probe: BoundaryProbe) {
    button(
        onClick = event { probe.fail.value = true },
        semantics = Semantics(role = Role.Button, testTag = "fail"),
    ) {
        text("fail")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.ResourceFallback(
    probe: ResourceBoundaryProbe,
    error: Throwable,
    retry: BoundaryRetry,
) {
    text("res-err:${error.message}")
    button(
        onClick = event {
            invalidate(ResourceBoundaryKey(probe.salt))
            retry.retry()
        },
        semantics = Semantics(role = Role.Button, testTag = "retry"),
    ) {
        text("retry")
    }
}

private suspend fun settle(root: TestRoot) {
    withContext(Dispatchers.Default) {
        withTimeout(2_000) {
            root.awaitIdle()
        }
    }
}

private fun String.countOccurrences(value: String): Int {
    var count = 0
    var index = 0
    while (true) {
        val next = indexOf(value, startIndex = index)
        if (next < 0) {
            return count
        }
        count += 1
        index = next + value.length
    }
}

private fun assertInOrder(
    value: String,
    vararg parts: String,
) {
    var index = 0
    parts.forEach { part ->
        val next = value.indexOf(part, startIndex = index)
        assertTrue(next >= 0, "Expected '$part' after index $index in:\n$value")
        index = next + part.length
    }
}
