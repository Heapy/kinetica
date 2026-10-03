@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.samples.harmon

import io.heapy.kinetica.appkit.*
import io.heapy.kinetica.application.*

/** Domain policy only: Kinetica owns native windows, tab groups, menu targets and disposal. */
class HarmonApplication(
    private val newSession: () -> MonitorSession,
    val application: AppKitApplication = AppKitApplication("Harmon Preview"),
) {
    val sessions = linkedMapOf<String, MonitorSession>()
    private var sequence = 0

    init {
        fun command(id: String, title: String, shortcut: KeyShortcut, action: () -> Unit) {
            application.commands.register(ApplicationCommand(id, title, listOf(shortcut)) { action() })
        }
        fun monitorCommand(id: String, title: String, shortcut: KeyShortcut,
            state: (MonitorSession) -> CommandState = { CommandState() }, action: (MonitorSession) -> Unit) {
            application.commands.register(ApplicationCommand(id, title, listOf(shortcut), windowRequired = true,
                state = { context -> sessions[context.windowId]?.let(state) ?: CommandState(enabled = false) },
                action = { context -> sessions[context.windowId]?.let(action) }))
        }
        command("app.quit", "Quit Harmon Preview", KeyShortcut("q"), application::requestQuit)
        command("app.hide", "Hide Harmon Preview", KeyShortcut("h"), application::hide)
        command("window.new", "New Window", KeyShortcut("n")) { open() }
        command("window.tab", "New Tab", KeyShortcut("t")) { open(tabbed = true) }
        monitorCommand("window.close", "Close Tab", KeyShortcut("w")) { application.activeWindow?.close() }
        monitorCommand("monitor.freeze", "Freeze Snapshot", KeyShortcut("p"),
            state = { CommandState(checked = it.state.value.frozen, title = if (it.state.value.frozen) "Resume Live" else "Freeze Snapshot") },
            action = { it.setFrozen(!it.state.value.frozen) })
        monitorCommand("monitor.refresh", "Reconnect / Next Sample", KeyShortcut("r"),
            state = { CommandState(enabled = if (it.isLive) !it.state.value.frozen else it.state.value.frozen,
                title = if (it.isLive) "Reconnect" else "Next Sample") },
            action = { if (it.isLive) it.retry() else it.state.value = it.state.value.nextSample() })
        monitorCommand("monitor.find", "Find Process…", KeyShortcut("f")) { application.activeWindow?.focus("search") }
        monitorCommand("tab.next", "Next Tab", KeyShortcut("]", setOf(KeyModifier.PRIMARY, KeyModifier.SHIFT))) { application.nextTab() }
        monitorCommand("tab.previous", "Previous Tab", KeyShortcut("[", setOf(KeyModifier.PRIMARY, KeyModifier.SHIFT))) { application.nextTab(backwards = true) }
        application.editingCommand("edit.undo", "Undo", KeyShortcut("z"), EditingCommand.UNDO)
        application.editingCommand("edit.cut", "Cut", KeyShortcut("x"), EditingCommand.CUT)
        application.editingCommand("edit.copy", "Copy", KeyShortcut("c"), EditingCommand.COPY)
        application.editingCommand("edit.paste", "Paste", KeyShortcut("v"), EditingCommand.PASTE)
        application.editingCommand("edit.selectAll", "Select All", KeyShortcut("a"), EditingCommand.SELECT_ALL)
        application.setMenus(listOf(
            ApplicationMenu("Harmon Preview", listOf(MenuItem.Command("app.hide"), MenuItem.Command("app.quit"))),
            ApplicationMenu("Window", listOf("window.new", "window.tab", "window.close", "tab.next", "tab.previous").map { MenuItem.Command(it) }),
            ApplicationMenu("Edit", listOf("edit.undo", "edit.cut", "edit.copy", "edit.paste", "edit.selectAll", "monitor.find").map { MenuItem.Command(it) }),
            ApplicationMenu("Monitor", listOf(MenuItem.Command("monitor.freeze"), MenuItem.Command("monitor.refresh"))),
        ))
    }

    fun open(tabbed: Boolean = false): AppKitWindow {
        val parent = if (tabbed) application.activeWindow?.id?.takeIf { it in sessions } else null
        val id = "monitor-${++sequence}"
        val session = newSession()
        sessions[id] = session
        return try { application.openWindow(ApplicationWindow(id, "Harmon — Native Preview",
            size = WindowSize(1120.0, 720.0), minimumSize = WindowSize(900.0, 500.0),
            tabGroup = "harmon.monitor", initialFocus = "search"), tabOf = parent,
            callbacks = WindowCallbacks(visibilityChanged = session::setWindowVisible,
                closed = { sessions.remove(id) }, release = session::close),
        ) { HarmonMonitor(session) } } catch (failure: Throwable) {
            // A failure before window ownership is established still owns a fresh source.
            if (sessions.remove(id) != null) session.close {}
            throw failure
        }
    }

    fun start() { application.install(); open() }
    fun run() { start(); application.run() }
    fun dispose() { application.dispose() }
}
