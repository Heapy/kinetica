package io.heapy.kinetica

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

public interface Cell<out T> : ReadOnlyProperty<Any?, T> {
    public val value: T

    override public fun getValue(thisRef: Any?, property: KProperty<*>): T = value
}

public interface MutableCell<T> : Cell<T>, ReadWriteProperty<Any?, T> {
    override public var value: T

    // Required to resolve the Cell/ReadWriteProperty getValue diamond.
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

/**
 * Reverse-dependency contract for propagation waves. [versionSnapshot] must not self-heal during
 * marking; [versionHealed] settles the cell during recompute. Nodes use object identity for wave
 * deduplication.
 */
internal interface ReactiveNode {
    val versionSnapshot: Long

    val versionHealed: Long

    fun addDependent(dependent: DerivedCell<*>)

    fun removeDependent(dependent: DerivedCell<*>)

    fun snapshotDependents(): List<DerivedCell<*>>

    fun hasExternalObservers(): Boolean

    fun collectExternalObserversInto(into: MutableList<() -> Unit>)
}

/**
 * One holder per observe() call. Holder identity, not listener identity, is the registration key,
 * so registering the same lambda instance twice yields two independent disposables.
 */
private class ListenerRegistration(val listener: () -> Unit)

private class CommittedWrite<T>(
    val next: T,
    val preVersion: Long,
)

/**
 * Global write stamp that lets clean derived cells skip recursive version walks. Advance it only
 * after publishing a write's version and value; advancing first could stamp an old value as clean
 * at the new clock forever.
 */
internal object ReactiveClock {
    private val counter = AtomicLong(0L)

    val current: Long
        get() = counter.load()

    fun advance() {
        counter.addAndFetch(1L)
    }
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

internal fun schedulePropagation(seed: ReactiveNode, seedPreVersion: Long) =
    PropagationWave().run(seed, seedPreVersion)

/**
 * One stack-local wave per source write: mark every reverse dependency, settle observed cells in
 * dependency order, then notify only changed cells. Notification after settlement prevents
 * listeners from observing glitches.
 */
private class PropagationWave {
    private val visited = HashSet<ReactiveNode>()
    private val observerCells = ArrayList<ReactiveNode>()
    private val preVersion = HashMap<ReactiveNode, Long>()

    fun run(seed: ReactiveNode, seedPreVersion: Long) {
        // MARK: graph walk with raw pre-wave versions; never recompute.
        // The seed needs its explicit pre-write version because its counter is already incremented.
        // Reading versionSnapshot here would equal the post version and suppress the
        // source's own observers.
        preVersion[seed] = seedPreVersion
        val work = ArrayDeque<ReactiveNode>().apply { add(seed) }
        while (work.isNotEmpty()) {
            val cell = work.removeFirst()
            if (!visited.add(cell)) continue
            preVersion.getOrPut(cell) { cell.versionSnapshot }
            if (cell.hasExternalObservers()) observerCells.add(cell)
            for (dependent in cell.snapshotDependents()) if (dependent !in visited) work.add(dependent)
        }
        // RECOMPUTE + PRUNE: settle dependencies before collecting changed observers.
        val toNotify = ArrayList<() -> Unit>()
        for (cell in observerCells) {
            val post = cell.versionHealed
            if (post != preVersion.getValue(cell)) cell.collectExternalObserversInto(toNotify)
        }
        // NOTIFY: all observed cells are settled, so listeners read final values.
        notifyAll(toNotify)
    }
}

internal open class MutableCellImpl<T>(
    initial: T,
    private val policy: EqualityPolicy<T>,
) : MutableCell<T>, ObservableCell<T>, ReactiveNode {
    // Per-instance write lock. Each cell serializes only its OWN read-modify-write
    // commits; writes to independent cells never contend, and there is no single
    // process-wide monitor that a slow transform can freeze (nor one that can form
    // an AB-BA cycle with DerivedCell's per-instance lock).
    private val writeLock = platformLock()
    private val listeners = AtomicReference<List<ListenerRegistration>>(emptyList())
    // Reverse-dependency edges: DerivedCells that read this source. Copy-on-write and
    // lock-free (like `listeners`), so a derived attaching/detaching never contends with
    // the write path and cannot form an AB-BA cycle with any node lock.
    private val dependents = AtomicReference<List<DerivedCell<*>>>(emptyList())
    private val current = AtomicReference(initial)
    private val versionCounter = AtomicLong(0L)

    override val version: Long
        get() = versionCounter.load()

    // A source is never dirty: raw and healed versions are identical.
    override val versionSnapshot: Long get() = versionCounter.load()
    override val versionHealed: Long get() = versionCounter.load()

    override fun addDependent(dependent: DerivedCell<*>) {
        while (true) {
            val currentDependents = dependents.load()
            if (dependents.compareAndSet(currentDependents, currentDependents + dependent)) {
                return
            }
        }
    }

    override fun removeDependent(dependent: DerivedCell<*>) {
        while (true) {
            val currentDependents = dependents.load()
            val nextDependents = currentDependents.filterNot { it === dependent }
            if (dependents.compareAndSet(currentDependents, nextDependents)) {
                return
            }
        }
    }

    override fun snapshotDependents(): List<DerivedCell<*>> = dependents.load()

    override fun hasExternalObservers(): Boolean = listeners.load().isNotEmpty()

    override fun collectExternalObserversInto(into: MutableList<() -> Unit>) {
        listeners.load().forEach { registration -> into += registration.listener }
    }

    override var value: T
        get() {
            ReadTracking.record(this)
            return current.load()
        }
        set(value) = setAtomic(value)

    override fun update(transform: (T) -> T) {
        commitAtomic(directNext = null, transform = transform)
    }

    override fun observe(listener: () -> Unit): Disposable {
        val registration = ListenerRegistration(listener)
        while (true) {
            val currentListeners = listeners.load()
            if (listeners.compareAndSet(currentListeners, currentListeners + registration)) {
                break
            }
        }
        return Disposable {
            while (true) {
                val currentListeners = listeners.load()
                val nextListeners = currentListeners.filterNot { candidate -> candidate === registration }
                if (listeners.compareAndSet(currentListeners, nextListeners)) {
                    return@Disposable
                }
            }
        }
    }

    private fun setAtomic(next: T) {
        commitAtomic(directNext = next, transform = null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun commitAtomic(
        directNext: T?,
        transform: ((T) -> T)?,
    ) {
        val committed = synchronizedOn(writeLock) {
            val previous = current.load()
            val next = if (transform == null) directNext as T else transform(previous)
            commitLocked(previous, next)
        }
        if (committed != null) {
            notifyCommittedWrite(committed)
        }
    }

    private fun commitLocked(
        previous: T,
        next: T,
    ): CommittedWrite<T>? {
        if (policy.equivalent(previous, next)) {
            return null
        }
        // Capture the pre-write version BEFORE the increment (inside the same writeLock section)
        // so the wave can tell the source's own observers apart from a no-op. Publish the version
        // BEFORE the value so any lock-free reader that observes the new value is guaranteed to
        // already observe the matching (or newer) version — never a new value with a stale version.
        // Both are volatile, so this program order is preserved.
        val preVersion = versionCounter.load()
        versionCounter.addAndFetch(1L)
        current.store(next)
        ReactiveClock.advance()
        return CommittedWrite(next, preVersion)
    }

    private fun notifyCommittedWrite(committed: CommittedWrite<T>) {
        // Runtime invalidation must precede graph propagation.
        onCommittedWrite(committed.next)
        // Avoid allocating a wave for isolated cells.
        if (dependents.load().isNotEmpty() || listeners.load().isNotEmpty()) {
            schedulePropagation(this, committed.preVersion)
        }
    }

    protected open fun onCommittedWrite(next: T) {
    }
}

internal class DerivedCell<T>(
    private var policy: EqualityPolicy<T>,
    private var compute: () -> T,
) : Cell<T>, ObservableCell<T>, ReactiveNode {
    private val lock = platformLock()

    private val registrations = mutableListOf<ListenerRegistration>()

    /**
     * Reconciled incrementally so a retained shared dependency is never briefly unsubscribed and
     * reactivated during one propagation wave.
     */
    private val subscriptions = linkedMapOf<ObservableCell<*>, Disposable>()

    /** Guarded reverse edges that keep this cell active for downstream observers. */
    private val dependents = linkedSetOf<DerivedCell<*>>()

    /** Foreign observables cannot join the reverse graph, so bridge them with eager notification. */
    private val foreignDependencyListener: () -> Unit = {
        val pre = synchronizedOn(lock) { versionCounter }
        schedulePropagation(this, pre)
    }

    /** Version snapshots provide lazy staleness checks without subscribing unobserved reads. */
    private val dependencyVersions = mutableListOf<Pair<ObservableCell<*>, Long>>()
    private var initialized = false
    private var dirty = true
    private var cached: T? = null
    private var versionCounter = 0L

    /** Clock value at the last validation; guarded by [lock]. */
    private var validatedAtClock = Long.MIN_VALUE

    internal fun updateDefinition(
        policy: EqualityPolicy<T>,
        compute: () -> T,
    ) = synchronizedOn(lock) {
        this.policy = policy
        this.compute = compute
        dirty = true
        // The refreshed definition can change this cell's value (and version) without any
        // source write; advance the clock so downstream stamped-clean cells re-validate.
        ReactiveClock.advance()
    }

    override val version: Long
        get() = synchronizedOn(lock) {
            settleLocked()
            versionCounter
        }

    override val value: T
        get() {
            ReadTracking.record(this)
            val current = synchronizedOn(lock) {
                settleLocked()
                cached
            }
            @Suppress("UNCHECKED_CAST")
            return current as T
        }

    /**
     * Brings the cell up to date: skips everything when the stamp proves no write happened
     * since the last validation, otherwise runs the version walk / recompute and re-stamps.
     * The clock is captured BEFORE validating: a write racing in during the walk advances the
     * clock past the captured value, so the stamp is immediately stale and the next read
     * re-validates (conservative, never skips a real change).
     */
    private fun settleLocked() {
        val clock = ReactiveClock.current
        if (initialized && !dirty && validatedAtClock == clock) {
            return
        }
        if (needsRecomputeLocked()) {
            recomputeLocked()
        }
        validatedAtClock = clock
    }

    /** Downstream dependents keep intermediate cells active even without external observers. */
    private fun isActiveLocked(): Boolean = registrations.isNotEmpty() || dependents.isNotEmpty()

    private fun needsRecomputeLocked(): Boolean =
        !initialized || dirty || !dependenciesUnchangedLocked()

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
        // Unobserved reads recompute on demand without subscribing, avoiding source retention.
        val active = isActiveLocked()
        var changed = false
        // Capture versions at read time, then recheck after subscribing so a write in that
        // interval cannot be lost.
        while (true) {
            // Reconcile only after compute succeeds, preserving old links across exceptions.
            val readVersions = linkedMapOf<ObservableCell<*>, Long>()
            val next = ReadTracking.collect(
                observer = { cell ->
                    if (cell is ObservableCell<*>) {
                        // Keep the first version so intervening writes remain detectable.
                        if (cell !in readVersions) {
                            readVersions[cell] = cell.version
                        }
                    }
                },
                block = compute,
            )
            val valueChanged = !initialized || !policy.equivalent(cached as T, next)
            if (valueChanged) {
                cached = next
                if (initialized) {
                    versionCounter += 1
                }
                changed = true
            }
            initialized = true

            if (active) {
                reconcileSubscriptionsLocked(readVersions.keys)
            }

            // Retry if a dependency changed between compute and subscription.
            val staleDuringWindow = readVersions.any { (cell, readVersion) ->
                cell.version != readVersion
            }
            if (!staleDuringWindow) {
                dependencyVersions.clear()
                readVersions.forEach { (dependency, readVersion) ->
                    dependencyVersions += dependency to readVersion
                }
                dirty = false
                return changed
            }
        }
    }

    private fun reconcileSubscriptionsLocked(newDependencies: Set<ObservableCell<*>>) {
        for (dependency in subscriptions.keys.filter { it !in newDependencies }) {
            subscriptions.remove(dependency)?.dispose()
        }
        for (dependency in newDependencies) {
            if (dependency !in subscriptions) {
                subscriptions[dependency] = linkDependencyLocked(dependency)
            }
        }
    }

    /** Uses reverse edges for reactive nodes and eager observation for foreign cells. */
    private fun linkDependencyLocked(dependency: ObservableCell<*>): Disposable =
        if (dependency is ReactiveNode) {
            dependency.addDependent(this)
            Disposable { dependency.removeDependent(this) }
        } else {
            dependency.observe(foreignDependencyListener)
        }

    private fun clearSubscriptionsLocked() {
        subscriptions.values.forEach { it.dispose() }
        subscriptions.clear()
        dirty = true
    }

    override fun observe(listener: () -> Unit): Disposable {
        val registration = ListenerRegistration(listener)
        synchronizedOn(lock) {
            val wasInactive = !isActiveLocked()
            registrations += registration
            if (wasInactive) {
                // First observation activates dependencies even if value was never read.
                dirty = true
                recomputeLocked()
            }
        }
        return Disposable {
            synchronizedOn(lock) {
                registrations.remove(registration)
                if (!isActiveLocked()) {
                    clearSubscriptionsLocked()
                }
            }
        }
    }

    override val versionSnapshot: Long
        get() = synchronizedOn(lock) { versionCounter }

    override val versionHealed: Long
        get() = version

    override fun addDependent(dependent: DerivedCell<*>) = synchronizedOn(lock) {
        val wasInactive = !isActiveLocked()
        dependents.add(dependent)
        if (wasInactive) {
            // A first downstream edge activates upstream subscriptions like observe().
            dirty = true
            recomputeLocked()
        }
    }

    override fun removeDependent(dependent: DerivedCell<*>) = synchronizedOn(lock) {
        dependents.remove(dependent)
        if (!isActiveLocked()) {
            clearSubscriptionsLocked()
        }
    }

    override fun snapshotDependents(): List<DerivedCell<*>> = synchronizedOn(lock) { dependents.toList() }

    override fun hasExternalObservers(): Boolean = synchronizedOn(lock) { registrations.isNotEmpty() }

    override fun collectExternalObserversInto(into: MutableList<() -> Unit>) = synchronizedOn(lock) {
        registrations.forEach { into += it.listener }
    }
}

public fun <T> store(
    initial: T,
    policy: EqualityPolicy<T> = EqualityPolicy.structural(),
): MutableCell<T> = MutableCellImpl(initial, policy)

/**
 * Scope-free derived cell for graph building outside render (stores, benchmarks,
 * services). Inside components use `derived {}`, which persists the cell in a slot.
 */
public fun <T> derive(
    policy: EqualityPolicy<T> = EqualityPolicy.structural(),
    compute: () -> T,
): Cell<T> = DerivedCell(policy, compute)

public fun <T> peek(block: () -> T): T = ReadTracking.peek(block)
