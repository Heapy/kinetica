package io.heapy.kinetica.application

import kotlin.test.*

class ApplicationTest {
    @Test fun commandsResolveTheActiveWindowAndRevalidateBeforeDispatch() {
        val windows = mutableSetOf("one", "two")
        val actions = mutableListOf<String?>()
        val commands = ApplicationCommands(listOf(ApplicationCommand("close", "Close", windowRequired = true,
            state = { CommandState(enabled = it.windowId in windows) }, action = { actions += it.windowId })))
        assertTrue(commands.execute("close", CommandContext("two")))
        windows.remove("two")
        assertFalse(commands.execute("close", CommandContext("two")))
        assertFalse(commands.execute("close", CommandContext(null)))
        assertEquals(listOf<String?>("two"), actions.toList())
    }

    @Test fun duplicateCommandsAndConflictingShortcutsFailBeforeReplacingActions() {
        val commands = ApplicationCommands(listOf(ApplicationCommand("find", "Find", listOf(KeyShortcut("f"))) {}))
        assertFailsWith<IllegalArgumentException> { commands.register(ApplicationCommand("find", "Other") {}) }
        assertFailsWith<IllegalArgumentException> { commands.register(ApplicationCommand("other", "Other", listOf(KeyShortcut("F"))) {}) }
        assertEquals("Find", commands.command("find").title)
        commands.register(ApplicationCommand("dismiss", "Dismiss", listOf(KeyShortcut("Escape", emptySet()))) {})
        assertEquals("Escape", commands.command("dismiss").shortcuts.single().key)
    }

    @Test fun closingOneWindowDoesNotReleaseOthersAndQuitWaitsForOutstandingCloses() {
        val lifetime = ApplicationLifetime()
        var first: (() -> Unit)? = null
        var second: (() -> Unit)? = null
        var releases = 0
        lifetime.register("one") { releases++; first = it }
        lifetime.register("two") { releases++; second = it }
        lifetime.close("one"); lifetime.close("one")
        assertEquals(1, releases)
        var terminated = 0
        lifetime.stop(); lifetime.whenIdle { terminated++ }
        assertEquals(2, releases)
        assertEquals(0, terminated)
        second!!(); assertEquals(0, terminated)
        first!!(); first!!(); assertEquals(1, terminated)
        assertTrue(lifetime.isIdle)
        assertFailsWith<IllegalStateException> { lifetime.register("late") { it() } }
    }

    @Test fun failuresDoNotPreventReleaseOfOtherWindowsOrLeaveShutdownWaiting() {
        val lifetime = ApplicationLifetime()
        var released = false
        lifetime.register("bad") { error("release failed") }
        lifetime.register("good") { released = true; it() }
        assertFailsWith<IllegalStateException> { lifetime.stop() }
        assertTrue(released)
        assertTrue(lifetime.isIdle)
    }

    @Test fun oldCompletionCannotReleaseANewWindowWithTheSameId() {
        val lifetime = ApplicationLifetime()
        lateinit var old: () -> Unit
        lifetime.register("one") { old = it }
        lifetime.close("one")
        assertFailsWith<IllegalArgumentException> { lifetime.register("one") { it() } }
        old()
        lifetime.register("one") {}
        old()
        assertFalse(lifetime.isIdle)
    }

    @Test fun savedGeometryIsClampedWhenDisplaysChange() {
        val screen = WindowBounds(-1000.0, 30.0, 1000.0, 700.0)
        val actual = WindowBounds(3000.0, -1000.0, 5000.0, 2.0).constrainedTo(screen, WindowSize(500.0, 200.0))
        assertEquals(WindowBounds(-1000.0, 30.0, 1000.0, 200.0), actual)
        assertFailsWith<IllegalArgumentException> { WindowBounds(Double.NaN, 0.0, 100.0, 100.0) }
    }
}
