package io.heapy.kinetica.application

/** Window dimensions are logical platform points, not backing pixels. */
public data class WindowSize(val width: Double, val height: Double) {
    init { require(width.isFinite() && width > 0); require(height.isFinite() && height > 0) }
}

public data class WindowBounds(val x: Double, val y: Double, val width: Double, val height: Double) {
    init { require(x.isFinite() && y.isFinite()); WindowSize(width, height) }

    public fun constrainedTo(screen: WindowBounds, minimum: WindowSize): WindowBounds {
        val w = width.coerceIn(minOf(minimum.width, screen.width), screen.width)
        val h = height.coerceIn(minOf(minimum.height, screen.height), screen.height)
        return WindowBounds(x.coerceIn(screen.x, screen.x + screen.width - w),
            y.coerceIn(screen.y, screen.y + screen.height - h), w, h)
    }
}

/** Stable IDs identify native windows independently of their current title or tab position. */
public data class ApplicationWindow(
    val id: String,
    val title: String,
    val size: WindowSize = WindowSize(800.0, 600.0),
    val minimumSize: WindowSize = WindowSize(200.0, 120.0),
    val resizable: Boolean = true,
    val minimizable: Boolean = true,
    val tabGroup: String? = null,
    val initialFocus: String? = null,
) {
    init { require(id.isNotBlank()); require(tabGroup == null || tabGroup.isNotBlank()) }
}

/** Owns async shutdown completion. Confined to the application's event thread. */
public class ApplicationLifetime {
    private class Entry(val release: (() -> Unit) -> Unit) { var closing = false }
    private val entries = linkedMapOf<String, Entry>()
    private val idleListeners = mutableListOf<() -> Unit>()
    public val isIdle: Boolean get() = entries.isEmpty()
    public fun owns(id: String): Boolean = id in entries
    public val closingCount: Int get() = entries.values.count { it.closing }
    public var stopping: Boolean = false
        private set

    public fun register(id: String, release: (() -> Unit) -> Unit) {
        check(!stopping) { "Application is stopping" }
        require(id.isNotBlank() && id !in entries) { "Window ID is already owned: $id" }
        entries[id] = Entry(release)
    }

    public fun close(id: String) {
        val entry = entries[id] ?: return
        if (entry.closing) return
        entry.closing = true
        var completed = false
        val done = {
            if (!completed) {
                completed = true
                entries.remove(id)
                if (entries.isEmpty()) {
                    val listeners = idleListeners.toList()
                    idleListeners.clear()
                    listeners.forEach { it() }
                }
            }
        }
        try { entry.release(done) } catch (error: Throwable) { done(); throw error }
    }

    /** Prevents creation before releasing any resources, including reentrant creation attempts. */
    public fun stop() {
        stopping = true
        var failure: Throwable? = null
        for (id in entries.keys.toList()) try { close(id) } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    public fun whenIdle(action: () -> Unit) {
        if (isIdle) action() else idleListeners += action
    }
}
