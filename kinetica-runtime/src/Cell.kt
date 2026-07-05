package io.heapy.kinetica

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.update
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

public interface Cell<out T> : ReadOnlyProperty<Any?, T> {
    public val value: T

    override public fun getValue(thisRef: Any?, property: KProperty<*>): T = value
}

public interface MutableCell<T> : Cell<T>, ReadWriteProperty<Any?, T> {
    override public var value: T

    override public fun getValue(thisRef: Any?, property: KProperty<*>): T = value

    public fun update(transform: (T) -> T) {
        value = transform(value)
    }

    override public fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value = value
    }
}

internal interface ObservableCell<out T> : Cell<T> {
    val version: Long

    fun observe(listener: () -> Unit): Disposable
}

internal object ReadTracking {
    private val local = ReadTrackingLocal()

    fun record(cell: Cell<*>) {
        local.current()?.invoke(cell)
    }

    fun <T> collect(observer: (Cell<*>) -> Unit, block: () -> T): T {
        local.push(observer)
        return try {
            block()
        } finally {
            local.pop(observer)
        }
    }

    suspend fun <T> collectSuspend(observer: (Cell<*>) -> Unit, block: suspend () -> T): T =
        // Delegate to the platform read-tracking store so the observer frame can FOLLOW the
        // coroutine across dispatcher/thread hops. A thread-confined push/pop here would leave
        // the observer on the origin thread's stack after a hop (reads on the resume thread
        // untracked, and the pop landing on the wrong thread). Platforms with real threads
        // (JVM/Android) carry the frame via a coroutine ThreadContextElement; single-threaded
        // platforms keep the original push/try-finally/pop.
        local.collectSuspend(observer, block)

    fun <T> peek(block: () -> T): T {
        val previous = local.clear()
        return try {
            block()
        } finally {
            local.restore(previous)
        }
    }
}

internal expect class ReadTrackingLocal() {
    fun current(): ((Cell<*>) -> Unit)?
    fun push(observer: (Cell<*>) -> Unit)
    fun pop(observer: (Cell<*>) -> Unit)
    fun clear(): List<(Cell<*>) -> Unit>
    fun restore(frames: List<(Cell<*>) -> Unit>)

    /**
     * Runs [block] with [observer] installed as the current read-tracking frame, keeping that
     * frame attached to the COROUTINE (not a single thread) so cell reads are tracked even after
     * the coroutine hops dispatchers/threads. Synchronous per-thread isolation used by [collect]
     * is unaffected.
     */
    suspend fun <T> collectSuspend(observer: (Cell<*>) -> Unit, block: suspend () -> T): T
}

/**
 * Invokes every listener exactly once, isolating exceptions so that one throwing
 * listener cannot suppress notification of the others. If any listeners threw, the
 * first error is rethrown after all have been notified, with the remaining errors
 * attached as suppressed exceptions.
 */
private fun notifyAll(listeners: Collection<() -> Unit>) {
    var primary: Throwable? = null
    for (listener in listeners) {
        try {
            listener()
        } catch (error: Throwable) {
            if (primary == null) {
                primary = error
            } else {
                primary.addSuppressed(error)
            }
        }
    }
    if (primary != null) {
        throw primary
    }
}

internal class MutableCellImpl<T>(
    initial: T,
    private val policy: EqualityPolicy<T>,
    private val onWrite: ((old: T, new: T) -> Unit)? = null,
) : MutableCell<T>, ObservableCell<T> {
    // Per-instance write lock. Each cell serializes only its OWN read-modify-write
    // commits; writes to independent cells never contend, and there is no single
    // process-wide monitor that a slow transform can freeze (nor one that can form
    // an AB-BA cycle with DerivedCell's per-instance lock).
    private val writeLock = SynchronizedObject()
    private val listeners = atomic<List<() -> Unit>>(emptyList())
    private val current = atomic(initial)
    private val versionCounter = atomic(0L)

    override val version: Long
        get() = versionCounter.value

    override var value: T
        get() {
            ReadTracking.record(this)
            return current.value
        }
        set(value) = setAtomic(value)

    override fun update(transform: (T) -> T) {
        val changed = synchronized(writeLock) {
            val previous = current.value
            val next = transform(previous)
            if (policy.equivalent(previous, next)) {
                null
            } else {
                // Publish the version BEFORE the value so that any lock-free reader
                // that observes the new value is guaranteed to already observe the
                // matching (or newer) version — never a new value with a stale
                // version. Both are volatile, so this program order is preserved.
                versionCounter.incrementAndGet()
                current.value = next
                previous to next
            }
        }
        changed?.let { (previous, next) -> notifyWrite(previous, next) }
    }

    override fun observe(listener: () -> Unit): Disposable {
        listeners.update { currentListeners -> currentListeners + listener }
        return Disposable {
            listeners.update { currentListeners -> currentListeners.filterNot { it === listener } }
        }
    }

    private fun setAtomic(next: T) {
        val changed = synchronized(writeLock) {
            val previous = current.value
            if (policy.equivalent(previous, next)) {
                null
            } else {
                // See update(): publish version before value so a lock-free reader
                // never sees the new value paired with a stale version.
                versionCounter.incrementAndGet()
                current.value = next
                previous to next
            }
        }
        changed?.let { (previous, value) -> notifyWrite(previous, value) }
    }

    private fun notifyWrite(previous: T, next: T) {
        onWrite?.invoke(previous, next)
        notifyAll(listeners.value)
    }
}

internal class DerivedCell<T>(
    private val policy: EqualityPolicy<T>,
    private val compute: () -> T,
) : Cell<T>, ObservableCell<T> {
    private val lock = SynchronizedObject()

    /**
     * Each observe() call produces a fresh [Registration] holder, so two identity-equal
     * listener instances yield two independent entries. Disposing one removes only its own
     * holder, leaving any other registration — even of the same lambda — live.
     */
    private class Registration(val listener: () -> Unit)

    private val registrations = mutableListOf<Registration>()

    /**
     * Live subscription to each dependency, keyed by the dependency cell. Present IFF this
     * cell is currently observed AND still depends on that cell. Reconciled INCREMENTALLY on
     * each recompute (see [reconcileSubscriptionsLocked]): a dependency that is still read keeps
     * its existing subscription rather than being torn down and re-added. Re-adding would, for a
     * shared upstream cell whose only observer is this subscription, momentarily drop that cell
     * to zero observers and RE-ACTIVATE it — forcing a redundant recompute of the shared cell.
     * Keeping subscriptions stable is what bounds a diamond's shared legs to one recompute per
     * propagation wave.
     */
    private val subscriptions = linkedMapOf<ObservableCell<*>, Disposable>()

    /**
     * The single listener this cell installs on every dependency (one identity per DerivedCell).
     * When a dependency changes it recomputes THIS cell — but ONLY if the cell is not already
     * consistent with its current dependency versions ([needsRecomputeLocked]). In a diamond
     * (C = A + B, both from S) writing S fires A's and B's callbacks independently; the first to
     * fire recomputes C, whose compute() reads and self-heals the OTHER leg. When that other
     * leg's callback then fires, this cell has already incorporated the leg's new version, so
     * needsRecomputeLocked is false and the callback is a no-op. That is the topological
     * batching: each derived recomputes at most once — and notifies at most once — per wave.
     */
    private val dependencyListener: () -> Unit = {
        val listenersToNotify = synchronized(lock) {
            if (needsRecomputeLocked()) {
                if (recomputeLocked()) {
                    registrations.map { it.listener }
                } else {
                    emptyList()
                }
            } else {
                // A prior recompute in this same propagation wave (triggered by another leg of
                // a diamond reading and self-healing us) already incorporated this dependency's
                // new version. Nothing to recompute, nothing to notify.
                emptyList()
            }
        }
        notifyAll(listenersToNotify)
    }

    /**
     * Snapshot of each dependency and the version it had at the last successful
     * recompute. While UNobserved the cell holds no source subscriptions, so it
     * cannot be told when a dependency changes; instead the next read compares these
     * recorded versions against the live ones and recomputes only if one advanced.
     * This keeps unobserved reads cached (recompute-once) yet correct, without the
     * leak of subscribing a bare read to its sources.
     */
    private val dependencyVersions = mutableListOf<Pair<ObservableCell<*>, Long>>()
    private var initialized = false
    private var dirty = true
    private var cached: T? = null
    private var versionCounter = 0L

    override val version: Long
        get() = synchronized(lock) {
            if (needsRecomputeLocked()) {
                recomputeLocked()
            }
            versionCounter
        }

    override val value: T
        get() {
            ReadTracking.record(this)
            val current = synchronized(lock) {
                if (needsRecomputeLocked()) {
                    recomputeLocked()
                }
                cached
            }
            @Suppress("UNCHECKED_CAST")
            return current as T
        }

    private fun needsRecomputeLocked(): Boolean =
        !initialized || dirty || !dependenciesUnchangedLocked()

    /**
     * True if every recorded dependency still reports the version it had at the last
     * recompute. Used for lazy staleness detection on the unobserved path (an
     * observed cell is invalidated eagerly via its source subscriptions instead).
     */
    private fun dependenciesUnchangedLocked(): Boolean {
        for ((cell, recordedVersion) in dependencyVersions) {
            if (cell.version != recordedVersion) {
                return false
            }
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun recomputeLocked(): Boolean {
        // A DerivedCell subscribes to its sources IFF it currently has at least one
        // observer. An UNobserved read (bare .value, peek{}, version) recomputes on
        // demand but must NOT register listeners on its sources — otherwise the source
        // retains this cell forever and eagerly recomputes it on every write with no one
        // observing (a leak). Only while observed do we (re)attach source subscriptions.
        val observed = registrations.isNotEmpty()
        var changed = false
        // TOCTOU guard: compute() reads a dependency, but this cell only subscribes to
        // that dependency AFTERWARDS. A write landing between the read and the subscribe
        // would neither reach this cell (not yet subscribed) NOR be caught by the lazy
        // version check (dependencyVersions would be captured after the write, already
        // matching). We therefore capture each dependency's version AT READ TIME and,
        // after subscribing, re-check whether any dependency advanced during that window;
        // if so we recompute with the fresh source values and loop until stable.
        while (true) {
            // Run compute() FIRST to collect the new dependency set (recording each
            // dependency's version at the moment it was read). Only after it succeeds do
            // we reconcile subscriptions. If compute() throws, we return early WITHOUT
            // touching the existing subscriptions, so the reactive link survives a
            // transient error and future dependency changes are still delivered.
            val readVersions = linkedMapOf<ObservableCell<*>, Long>()
            val next = ReadTracking.collect(
                observer = { cell ->
                    if (cell is ObservableCell<*>) {
                        // Keep the version at the FIRST read of each dependency, so a
                        // write between that read and the subscribe reads as an advance.
                        if (cell !in readVersions) {
                            readVersions[cell] = cell.version
                        }
                    }
                },
                block = compute,
            )
            // compute() succeeded — commit the value.
            val valueChanged = !initialized || !policy.equivalent(cached as T, next)
            if (valueChanged) {
                cached = next
                if (initialized) {
                    versionCounter += 1
                }
                changed = true
            }
            initialized = true

            if (observed) {
                // compute() succeeded — reconcile subscriptions to the NEW dependency set.
                // Incremental (not tear-down-all): dependencies still read keep their existing
                // subscription, so a shared upstream cell is never disposed/re-added (which would
                // re-activate it and recompute it a second time within the same wave). Only
                // dropped dependencies are disposed and only newly-read ones are subscribed.
                reconcileSubscriptionsLocked(readVersions.keys)
            }

            // Re-check the TOCTOU window: did any dependency change between the read
            // captured during compute() and now (after subscribing)? If so a write
            // landed in the window and must not be lost — recompute with fresh values.
            val staleDuringWindow = readVersions.any { (cell, readVersion) ->
                cell.version != readVersion
            }
            if (!staleDuringWindow) {
                // Record the (now stable) dependency versions so an unobserved cell can
                // detect staleness lazily on its next read (see dependenciesUnchangedLocked).
                // Captured after any (re)subscription so observed and unobserved paths agree.
                dependencyVersions.clear()
                readVersions.forEach { (dependency, readVersion) ->
                    dependencyVersions += dependency to readVersion
                }
                // The cache now matches the recorded dependency versions. Observed cells
                // are re-invalidated eagerly by their subscriptions; unobserved cells
                // re-check dependency versions on the next read — so the cell is clean.
                dirty = false
                return changed
            }
            // A dependency advanced during the read-to-subscribe window: loop and
            // recompute so the cell reflects the write instead of caching a stale value.
        }
    }

    /**
     * Bring the live [subscriptions] into agreement with [newDependencies] with minimal churn:
     * dispose only subscriptions to dependencies no longer read, subscribe only dependencies not
     * already subscribed, and leave every persisting subscription exactly as it was. Called only
     * on the observed path, AFTER a successful compute().
     */
    private fun reconcileSubscriptionsLocked(newDependencies: Set<ObservableCell<*>>) {
        val stale = subscriptions.keys.filter { it !in newDependencies }
        for (dependency in stale) {
            subscriptions.remove(dependency)?.dispose()
        }
        for (dependency in newDependencies) {
            if (dependency !in subscriptions) {
                subscriptions[dependency] = dependency.observe(dependencyListener)
            }
        }
    }

    private fun clearSubscriptionsLocked() {
        subscriptions.values.forEach { it.dispose() }
        subscriptions.clear()
        dirty = true
    }

    override fun observe(listener: () -> Unit): Disposable {
        val registration = Registration(listener)
        synchronized(lock) {
            val wasInactive = registrations.isEmpty()
            registrations += registration
            if (wasInactive) {
                // First observer ACTIVATES the cell: compute + subscribe to the current
                // dependency set so that later source writes reach this observer even
                // when .value was never read. Re-observing after a full teardown (which
                // cleared all subscriptions) re-activates the same way.
                dirty = true
                recomputeLocked()
            }
        }
        return Disposable {
            synchronized(lock) {
                registrations.remove(registration)
                if (registrations.isEmpty()) {
                    clearSubscriptionsLocked()
                }
            }
        }
    }
}

public fun <T> store(
    initial: T,
    policy: EqualityPolicy<T> = EqualityPolicy.structural(),
): MutableCell<T> = MutableCellImpl(initial, policy)

public fun <T> peek(block: () -> T): T = ReadTracking.peek(block)
