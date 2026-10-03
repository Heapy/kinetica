@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.appkit

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.application.*
import io.heapy.kinetica.render.HostWidgetFactory
import kotlinx.cinterop.useContents
import kotlinx.cinterop.ObjCAction
import platform.AppKit.*
import platform.Foundation.*
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

public enum class EditingCommand { CUT, COPY, PASTE, SELECT_ALL, UNDO, REDO }

/** Event-thread callbacks. [release] must eventually call its completion, once resources are closed. */
public class WindowCallbacks(
    public val canClose: () -> Boolean = { true },
    public val visibilityChanged: (Boolean) -> Unit = {},
    public val boundsChanged: (WindowBounds) -> Unit = {},
    public val closed: () -> Unit = {},
    public val release: (() -> Unit) -> Unit = { it() },
)

/** Owns native application/window delegates, menus, tab groups and renderer lifetimes.
 * Domain resources (PTYs, network clients) are supplied through [WindowCallbacks.release]. */
public class AppKitApplication(
    public val name: String,
    public val commands: ApplicationCommands = ApplicationCommands(),
    public val nativeApplication: NSApplication = NSApplication.sharedApplication(),
    public val quitAfterLastWindow: Boolean = true,
    private val requestNativeTermination: () -> Unit = { nativeApplication.terminate(null) },
    private val replyToTermination: (Boolean) -> Unit = { nativeApplication.replyToApplicationShouldTerminate(it) },
) : NSObject(), NSApplicationDelegateProtocol, NSMenuItemValidationProtocol {
    private val owned = linkedMapOf<String, AppKitWindow>()
    private val lifetime = ApplicationLifetime()
    private val menuCommands = mutableMapOf<NSMenuItem, String>()
    private val editing = mutableMapOf<String, EditingCommand>()
    private var previousDelegate: NSApplicationDelegateProtocol? = null
    private var previousMenu: NSMenu? = null
    private var keyMonitor: Any? = null
    private var installed = false
    private var disposed = false
    private var waitingForTermination = false
    public var menu: NSMenu = NSMenu()
        private set
    public val windows: List<AppKitWindow> get() = owned.values.toList()
    public val activeWindow: AppKitWindow? get() {
        // Never route a window command to an arbitrary background document/settings window.
        val native = nativeApplication.keyWindow ?: return null
        return owned.values.firstOrNull { it.nativeWindow == native }
    }
    public val context: CommandContext get() = CommandContext(activeWindow?.id)
    public val closingWindowCount: Int get() = lifetime.closingCount

    /** Standard editing actions stay on AppKit's responder chain, including native text fields. */
    public fun editingCommand(id: String, title: String, shortcut: KeyShortcut, edit: EditingCommand) {
        commands.register(ApplicationCommand(id, title, listOf(shortcut), windowRequired = true) {
            nativeApplication.sendAction(NSSelectorFromString(edit.selector()), to = null, from = null)
        })
        editing[id] = edit
    }

    public fun setMenus(menus: List<ApplicationMenu>) {
        check(!disposed)
        val bindings = mutableMapOf<NSMenuItem, String>()
        fun makeMenu(model: ApplicationMenu): NSMenu = NSMenu(model.title).also { target ->
            for (item in model.items) when (item) {
                MenuItem.Separator -> target.addItem(NSMenuItem.separatorItem())
                is MenuItem.Submenu -> target.addItem(NSMenuItem().also {
                    it.title = item.menu.title; it.submenu = makeMenu(item.menu)
                })
                is MenuItem.Command -> {
                    val command = commands.command(item.id)
                    for ((index, shortcut) in command.shortcuts.ifEmpty { listOf(null) }.withIndex()) {
                        val edit = editing[item.id]
                        val native = NSMenuItem(command.title,
                            NSSelectorFromString(edit?.selector() ?: "invokeCommand:"), shortcut?.appKitKey().orEmpty())
                        native.keyEquivalentModifierMask = shortcut?.appKitModifiers() ?: 0uL
                        native.target = if (edit == null) this else null
                        native.hidden = item.hidden || index > 0
                        native.allowsKeyEquivalentWhenHidden = native.hidden
                        bindings[native] = item.id
                        target.addItem(native)
                    }
                }
            }
        }
        val next = NSMenu()
        menus.forEach { model -> next.addItem(NSMenuItem().also { it.title = model.title; it.submenu = makeMenu(model) }) }
        menuCommands.clear(); menuCommands.putAll(bindings); menu = next
        if (installed) nativeApplication.mainMenu = menu
    }

    public fun install() {
        check(!disposed)
        if (installed) return
        installed = true
        previousDelegate = nativeApplication.delegate; previousMenu = nativeApplication.mainMenu
        nativeApplication.delegate = this; nativeApplication.mainMenu = menu
        nativeApplication.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        NSWindow.setAllowsAutomaticWindowTabbing(false)
        keyMonitor = NSEvent.addLocalMonitorForEventsMatchingMask(NSEventMaskKeyDown) { event ->
            if (event != null && handleLocalShortcut(event)) null else event
        }
    }

    public fun run() {
        install()
        nativeApplication.activateIgnoringOtherApps(true)
        try { nativeApplication.run() } finally { dispose() }
    }

    public fun window(id: String): AppKitWindow? = owned[id]

    internal fun createWindow(
        specification: ApplicationWindow,
        tabOf: String? = null,
        restoredBounds: WindowBounds? = null,
        callbacks: WindowCallbacks = WindowCallbacks(),
        runtime: KineticaRuntime = KineticaRuntime(debug = false),
        hostWidgets: Map<String, HostWidgetFactory<NSView>> = emptyMap(),
        content: @UiComponent ComponentScope.() -> Unit,
    ): AppKitWindow {
        check(!disposed && !lifetime.stopping) { "Application is stopping" }
        require(!lifetime.owns(specification.id)) { "Window ID is already owned: ${specification.id}" }
        val parent = tabOf?.let { requireNotNull(owned[it]) { "Unknown parent tab: $it" } }
        require(parent == null || specification.tabGroup != null && parent.specification.tabGroup == specification.tabGroup) {
            "Tabs must have the same non-null group"
        }
        val window = AppKitWindow(this, specification, callbacks)
        lifetime.register(specification.id) { done -> callbacks.release(done) }
        owned[specification.id] = window
        try {
            window.mount(runtime, hostWidgets, content)
            if (restoredBounds == null || !window.restoreBounds(restoredBounds)) window.nativeWindow.center()
            parent?.nativeWindow?.addTabbedWindow(window.nativeWindow, NSWindowAbove)
            window.show()
        } catch (error: Throwable) {
            window.closeImmediately()
            throw error
        }
        return window
    }

    public fun execute(id: String): Boolean = !disposed && commands.execute(id, context)

    @ObjCAction public fun invokeCommand(sender: NSMenuItem) { menuCommands[sender]?.let(::execute) }

    override fun validateMenuItem(menuItem: NSMenuItem): Boolean {
        val id = menuCommands[menuItem] ?: return false
        if (disposed) return false
        val state = commands.state(id, context)
        menuItem.title = state.title ?: commands.command(id).title
        menuItem.state = if (state.checked) NSControlStateValueOn else NSControlStateValueOff
        return state.enabled
    }

    private fun handleLocalShortcut(event: NSEvent): Boolean {
        if (disposed || event.window != activeWindow?.nativeWindow) return false
        if ((nativeApplication.keyWindow?.firstResponder as? NSTextInputClientProtocol)?.hasMarkedText() == true) return false
        val mask = event.modifierFlags and (NSEventModifierFlagCommand or NSEventModifierFlagShift or NSEventModifierFlagOption or NSEventModifierFlagControl)
        // Command equivalents are handled by the native menu. Local shortcuts may act only
        // in an owned active window and only while their command is enabled (e.g. find Escape).
        if (mask and NSEventModifierFlagCommand != 0uL) return false
        val characters = event.charactersIgnoringModifiers ?: return false
        val command = commands.all.firstOrNull { command -> command.shortcuts.any { shortcut ->
            KeyModifier.PRIMARY !in shortcut.modifiers && shortcut.appKitModifiers() == mask &&
                shortcut.appKitKey() == characters
        } && commands.state(command.id, context).enabled } ?: return false
        return execute(command.id)
    }

    public fun selectTab(index: Int) {
        require(index >= 0)
        val active = activeWindow ?: return
        val group = active.nativeWindow.tabGroup?.windows?.filterIsInstance<NSWindow>() ?: listOf(active.nativeWindow)
        group.getOrNull(index)?.makeKeyAndOrderFront(null)
        refreshVisibility()
    }

    public fun nextTab(backwards: Boolean = false) {
        val active = activeWindow ?: return
        if (backwards) active.nativeWindow.selectPreviousTab(null) else active.nativeWindow.selectNextTab(null)
        refreshVisibility()
    }

    public fun requestQuit() { if (!disposed) requestNativeTermination() }
    public fun hide() { if (!disposed) nativeApplication.hide(null) }

    override fun applicationShouldTerminateAfterLastWindowClosed(sender: NSApplication): Boolean = quitAfterLastWindow

    override fun applicationShouldTerminate(sender: NSApplication): NSApplicationTerminateReply {
        if (waitingForTermination) return NSTerminateLater
        if (owned.values.any { !it.callbacks.canClose() }) return NSTerminateCancel
        dispose()
        if (lifetime.isIdle) return NSTerminateNow
        waitingForTermination = true
        lifetime.whenIdle {
            // Even synchronous completion must reply after the delegate's return.
            dispatch_async(dispatch_get_main_queue()) {
                if (waitingForTermination) { waitingForTermination = false; replyToTermination(true) }
            }
        }
        return NSTerminateLater
    }

    override fun applicationDidHide(notification: NSNotification) { refreshVisibility() }
    override fun applicationDidUnhide(notification: NSNotification) { refreshVisibility() }
    override fun applicationWillTerminate(notification: NSNotification) { dispose() }

    internal fun refreshVisibility() { owned.values.toList().forEach { it.updateVisibility() } }

    internal fun windowClosed(window: AppKitWindow) {
        if (owned[window.id] !== window) return
        owned.remove(window.id)
        try { window.callbacks.closed() } finally { lifetime.close(window.id); refreshVisibility() }
    }

    public fun dispose() {
        if (disposed) return
        disposed = true
        keyMonitor?.let { NSEvent.removeMonitor(it) }; keyMonitor = null
        var failure: Throwable? = null
        fun releasing(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        for (window in owned.values.toList()) releasing { window.closeImmediately() }
        releasing { lifetime.stop() }
        if (installed) {
            if (nativeApplication.delegate === this) nativeApplication.delegate = previousDelegate
            if (nativeApplication.mainMenu == menu) nativeApplication.mainMenu = previousMenu
            previousDelegate = null; previousMenu = null; installed = false
        }
        failure?.let { throw it }
    }
}

/** Kotlin entry point for compiler-wrapped component content.
 * Every window/tab owns its Kinetica renderer and its resource-release callback. */
public fun AppKitApplication.openWindow(
    specification: ApplicationWindow,
    tabOf: String? = null,
    restoredBounds: WindowBounds? = null,
    callbacks: WindowCallbacks = WindowCallbacks(),
    runtime: KineticaRuntime = KineticaRuntime(debug = false),
    hostWidgets: Map<String, HostWidgetFactory<NSView>> = emptyMap(),
    content: @UiComponent ComponentScope.() -> Unit,
): AppKitWindow = createWindow(specification, tabOf, restoredBounds, callbacks, runtime, hostWidgets, content)

/** Stable managed window handle. Native access is available for platform-specific integrations. */
public class AppKitWindow internal constructor(
    private val owner: AppKitApplication,
    public val specification: ApplicationWindow,
    internal val callbacks: WindowCallbacks,
) : NSObject(), NSWindowDelegateProtocol {
    public val id: String get() = specification.id
    public val nativeWindow: NSWindow = NSWindow(NSMakeRect(0.0, 0.0, specification.size.width, specification.size.height),
        NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or
            (if (specification.minimizable) NSWindowStyleMaskMiniaturizable else 0uL) or
            (if (specification.resizable) NSWindowStyleMaskResizable else 0uL), NSBackingStoreBuffered, false)
    public var renderer: AppKitKineticaApp? = null
        private set
    private var closed = false
    private var visible = false
    private var minimizing = false
    private var shown = false
    public var title: String
        get() = nativeWindow.title
        set(value) { nativeWindow.title = value }
    public val bounds: WindowBounds get() = nativeWindow.frame.useContents { WindowBounds(origin.x, origin.y, size.width, size.height) }
    public val isVisible: Boolean get() = visible

    init {
        nativeWindow.title = specification.title
        nativeWindow.minSize = NSMakeSize(specification.minimumSize.width, specification.minimumSize.height)
        nativeWindow.setReleasedWhenClosed(false)
        specification.tabGroup?.let { nativeWindow.tabbingIdentifier = it }
        nativeWindow.delegate = this
    }

    internal fun mount(runtime: KineticaRuntime, widgets: Map<String, HostWidgetFactory<NSView>>,
        content: @UiComponent ComponentScope.() -> Unit) {
        renderer = AppKitKineticaApp(nativeWindow.contentView!!, runtime, widgets, content)
        renderer!!.renderUntilSettled()
    }

    public fun show() {
        check(!closed)
        nativeWindow.makeKeyAndOrderFront(null)
        if (!shown) {
            shown = true
            specification.initialFocus?.let { focus(it) }
        }
        owner.refreshVisibility()
    }

    public fun hide() { if (!closed) { nativeWindow.orderOut(null); owner.refreshVisibility() } }
    public fun focus(testTag: String): Boolean = !closed && renderer?.focus(testTag) == true
    public fun close() { if (!closed) nativeWindow.performClose(null) }

    public fun restoreBounds(bounds: WindowBounds): Boolean {
        val screen = NSScreen.screens.filterIsInstance<NSScreen>().maxByOrNull { screen ->
            NSIntersectionRect(bounds.native(), screen.visibleFrame).useContents { size.width * size.height }
        } ?: return false
        val available = screen.visibleFrame.useContents { WindowBounds(origin.x, origin.y, size.width, size.height) }
        nativeWindow.setFrame(bounds.constrainedTo(available, specification.minimumSize).native(), display = false)
        return true
    }

    override fun windowShouldClose(sender: NSWindow): Boolean = callbacks.canClose()
    override fun windowWillClose(notification: NSNotification) { finishClose() }
    override fun windowDidBecomeKey(notification: NSNotification) { owner.refreshVisibility() }
    override fun windowDidResignKey(notification: NSNotification) { owner.refreshVisibility() }
    override fun windowWillMiniaturize(notification: NSNotification) { minimizing = true; owner.refreshVisibility() }
    override fun windowDidMiniaturize(notification: NSNotification) { minimizing = true; owner.refreshVisibility() }
    override fun windowDidDeminiaturize(notification: NSNotification) {
        minimizing = false; owner.refreshVisibility()
        // AppKit can finish updating visibility after this delegate notification.
        dispatch_async(dispatch_get_main_queue()) { if (!closed) owner.refreshVisibility() }
    }
    override fun windowDidChangeOcclusionState(notification: NSNotification) { owner.refreshVisibility() }
    override fun windowDidMove(notification: NSNotification) { if (!closed && nativeWindow.visible) callbacks.boundsChanged(bounds) }
    override fun windowDidResize(notification: NSNotification) { if (!closed && nativeWindow.visible) callbacks.boundsChanged(bounds) }

    internal fun updateVisibility() {
        val selected = nativeWindow.tabGroup?.selectedWindow
        val next = !closed && !minimizing && nativeWindow.visible && !nativeWindow.miniaturized && !owner.nativeApplication.hidden &&
            (selected == null || selected == nativeWindow)
        if (visible != next) { visible = next; callbacks.visibilityChanged(next) }
    }

    internal fun closeImmediately() {
        if (!closed) { nativeWindow.close(); finishClose() }
    }

    private fun finishClose() {
        if (closed) return
        closed = true
        nativeWindow.delegate = null
        updateVisibility()
        try { renderer?.dispose() } finally { renderer = null; owner.windowClosed(this) }
    }
}

private fun WindowBounds.native() = NSMakeRect(x, y, width, height)

private fun EditingCommand.selector(): String = when (this) {
    EditingCommand.CUT -> "cut:"
    EditingCommand.COPY -> "copy:"
    EditingCommand.PASTE -> "paste:"
    EditingCommand.SELECT_ALL -> "selectAll:"
    EditingCommand.UNDO -> "undo:"
    EditingCommand.REDO -> "redo:"
}

internal fun KeyShortcut.appKitKey(): String {
    val character = when (key) { "Enter" -> "\r"; "Escape" -> "\u001b"; "Tab" -> "\t"; "Backspace" -> "\u007f"; else -> key.lowercase() }
    // AppKit's charactersIgnoringModifiers includes Shift, including shifted punctuation.
    return if (KeyModifier.SHIFT in modifiers) when (character) {
        "[" -> "{"; "]" -> "}"; "=" -> "+"; "-" -> "_"; "/" -> "?"; else -> character.uppercase()
    } else character
}

internal fun KeyShortcut.appKitModifiers(): ULong =
    (if (KeyModifier.PRIMARY in modifiers) NSEventModifierFlagCommand else 0uL) or
        (if (KeyModifier.SHIFT in modifiers) NSEventModifierFlagShift else 0uL) or
        (if (KeyModifier.ALT in modifiers) NSEventModifierFlagOption else 0uL) or
        (if (KeyModifier.CONTROL in modifiers) NSEventModifierFlagControl else 0uL)
