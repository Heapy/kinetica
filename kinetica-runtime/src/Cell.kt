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
 * Internal reactive-graph contract implemented ONLY by [MutableCellImpl] and [DerivedCell]. It
 * exposes the reverse-dependency edges and the two version flavors the propagation wave needs:
 * [versionSnapshot] (a RAW read that must NOT self-heal, used to capture pre-wave versions during
 * MARK) and [versionHealed] (the existing self-healing [ObservableCell.version] getter, used to
 * settle a cell during RECOMPUTE). Kept separate from [ObservableCell] so a foreign observable
 * (e.g. the test-only CountingCell) that only implements [ObservableCell] keeps compiling and is
 * reached through the foreign fallback if ever used as a dependency.
 *
 * Cells do NOT override equals/hashCode, so [ReactiveNode] instances use object identity in the
 * wave's HashSet/HashMap — required for correct dedup.
 */
internal interface ReactiveNode {
    /** RAW version counter; MUST NOT trigger a self-heal/recompute. */
    val versionSnapshot: Long

    /** The self-healing version (== [ObservableCell.version]). */
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
    val previous: T,
    val next: T,
    val preVersion: Long,
)

/**
 * Global monotonic write clock, advanced after every committed source write (and on derived
 * definition refreshes). A [DerivedCell] that validated its transitive dependencies while the
 * clock read C is provably still clean as long as the clock still reads C — no write happened
 * anywhere — so unobserved reads can skip the recursive dependency-version walk entirely.
 *
 * Without this stamp, every `.value`/`.version` read of a depth-k derived cell re-validates its
 * whole chain (each dependency's version check recursing into ITS dependencies): O(k) per read
 * and O(n²) for one write+read over an n-deep chain — bench-jvm's `derived_chain_lazy_1k`
 * measured 3.5 ms per write+read before this stamp.
 *
 * Ordering contract: the clock must advance strictly AFTER the write publishes its version and
 * value. A reader that captured the pre-advance clock and missed the new value will stamp the
 * OLD clock, which the advance immediately invalidates — the next read re-validates. Advancing
 * before the publish would let a reader stamp the NEW clock against the OLD value and never
 * re-validate. (A stamped-clean read can also skip at most one FOREIGN observable's change —
 * acceptable: the only foreign ObservableCell is test-only, see [DerivedCell.foreignDependencyListener].)
 */
internal object ReactiveClock {
    private val counter = atomic(0L)

    val current: Long
        get() = counter.value

    fun advance() {
        counter.incrementAndGet()
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

/**
 * Drive a single glitch-free propagation for one source write. Runs on the writing thread's
 * stack — no global lock, no thread-local. See [PropagationWave].
 */
internal fun schedulePropagation(seed: ReactiveNode, seedPreVersion: Long) =
    PropagationWave().run(seed, seedPreVersion)

/**
 * One transient, stack-local wave per source write. Two phases decouple recompute from notify so
 * that on any source write every observed derived recomputes AT MOST ONCE, no observer reads a
 * glitch value, and every observer whose transitive value actually changed is notified EXACTLY
 * ONCE.
 *
 *  - MARK: pure reverse-graph walk from the seed, UNPRUNED (never gated on whether an intermediate
 *    "decided to notify"). Captures each reached cell's RAW pre-wave version and remembers which
 *    cells carry external observers. Because collection is purely structural, a second independent
 *    observer of a shared intermediate is always collected — this is the R05 D-notification fix.
 *  - RECOMPUTE + PRUNE + COLLECT: for each observer-bearing cell read [ReactiveNode.versionHealed],
 *    which reuses the existing dependency-ordered self-heal (pulling and healing its deps first).
 *    A cell is scheduled for notification IFF its post version differs from its captured pre
 *    version — pruning cells whose value did not actually change. Version-dedup inside the healer
 *    guarantees each cell recomputes at most once.
 *  - NOTIFY: runs only AFTER every observer cell (and, transitively, its deps) has settled, so any
 *    `.value` read inside a listener returns the final, consistent value.
 */
private class PropagationWave {
    private val visited = HashSet<ReactiveNode>()
    private val observerCells = ArrayList<ReactiveNode>()
    private val preVersion = HashMap<ReactiveNode, Long>()

    fun run(seed: ReactiveNode, seedPreVersion: Long) {
        // ---- MARK (pure graph walk; captures RAW pre-wave versions; NEVER recomputes) ----
        // The seed is given its PRE-WRITE version explicitly: by wave time its counter is already
        // incremented, so versionSnapshot would wrongly equal the post version and suppress the
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
        // ---- RECOMPUTE + PRUNE + COLLECT (dependency-ordered self-heal via versionHealed) ----
        val toNotify = ArrayList<() -> Unit>()
        for (cell in observerCells) {
            val post = cell.versionHealed
            if (post != preVersion.getValue(cell)) cell.collectExternalObserversInto(toNotify)
        }
        // ---- NOTIFY (after everything settled -> observers can only read final values) ----
        notifyAll(toNotify)
    }
}

internal class MutableCellImpl<T>(
    initial: T,
    private val policy: EqualityPolicy<T>,
    private val onWrite: ((old: T, new: T) -> Unit)? = null,
) : MutableCell<T>, ObservableCell<T>, ReactiveNode {
    // Per-instance write lock. Each cell serializes only its OWN read-modify-write
    // commits; writes to independent cells never contend, and there is no single
    // process-wide monitor that a slow transform can freeze (nor one that can form
    // an AB-BA cycle with DerivedCell's per-instance lock).
    private val writeLock = SynchronizedObject()
    private val listeners = atomic<List<ListenerRegistration>>(emptyList())
    // Reverse-dependency edges: DerivedCells that read this source. Copy-on-write and
    // lock-free (like `listeners`), so a derived attaching/detaching never contends with
    // the write path and cannot form an AB-BA cycle with any node lock.
    private val dependents = atomic<List<DerivedCell<*>>>(emptyList())
    private val current = atomic(initial)
    private val versionCounter = atomic(0L)

    override val version: Long
        get() = versionCounter.value

    // A source is never dirty: raw and healed versions are identical.
    override val versionSnapshot: Long get() = versionCounter.value
    override val versionHealed: Long get() = versionCounter.value

    override fun addDependent(dependent: DerivedCell<*>) {
        dependents.update { it + dependent }
    }

    override fun removeDependent(dependent: DerivedCell<*>) {
        dependents.update { list -> list.filterNot { it === dependent } }
    }

    override fun snapshotDependents(): List<DerivedCell<*>> = dependents.value

    override fun hasExternalObservers(): Boolean = listeners.value.isNotEmpty()

    override fun collectExternalObserversInto(into: MutableList<() -> Unit>) {
        listeners.value.forEach { registration -> into += registration.listener }
    }

    override var value: T
        get() {
            ReadTracking.record(this)
            return current.value
        }
        set(value) = setAtomic(value)

    override fun update(transform: (T) -> T) {
        commitAtomic(directNext = null, transform = transform)
    }

    override fun observe(listener: () -> Unit): Disposable {
        val registration = ListenerRegistration(listener)
        listeners.update { currentListeners -> currentListeners + registration }
        return Disposable {
            listeners.update { currentListeners ->
                currentListeners.filterNot { candidate -> candidate === registration }
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
        val committed = synchronized(writeLock) {
            val previous = current.value
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
        val preVersion = versionCounter.value
        versionCounter.incrementAndGet()
        current.value = next
        ReactiveClock.advance()
        return CommittedWrite(previous, next, preVersion)
    }

    private fun notifyCommittedWrite(committed: CommittedWrite<T>) {
        // onWrite still fires on every changed write, before propagation (preserves
        // ComponentScope.state -> runtime.invalidate).
        onWrite?.invoke(committed.previous, committed.next)
        // FAST-PATH: only spawn a wave when someone is actually listening or derives from
        // this cell — keeps writes to isolated cells lean (R09/R12).
        if (dependents.value.isNotEmpty() || listeners.value.isNotEmpty()) {
            schedulePropagation(this, committed.preVersion)
        }
    }
}

internal class DerivedCell<T>(
    private var policy: EqualityPolicy<T>,
    private var compute: () -> T,
) : Cell<T>, ObservableCell<T>, ReactiveNode {
    private val lock = SynchronizedObject()

    /**
     * Each observe() call produces a fresh [ListenerRegistration] holder, so two identity-equal
     * listener instances yield two independent entries. Disposing one removes only its own
     * holder, leaving any other registration — even of the same lambda — live.
     */
    private val registrations = mutableListOf<ListenerRegistration>()

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
     * Reverse-dependency edges: the DerivedCells that read THIS cell. Guarded by [lock]. Together
     * with [registrations] these define whether the cell is "active" (see [isActiveLocked]): an
     * active cell holds live subscriptions to its own dependencies so a source write can BFS-reach
     * its observers. Identity-based ([DerivedCell] does not override equals/hashCode).
     */
    private val dependents = linkedSetOf<DerivedCell<*>>()

    /**
     * The single listener installed on a FOREIGN (non-[ReactiveNode]) dependency — the only
     * observable that cannot participate in the reverse-graph wave. Dead in production/tests (only
     * the test-only CountingCell is foreign and nothing derives from it), but preserves today's
     * eager-push semantics with zero risk: it seeds a fresh wave rooted at THIS cell.
     */
    private val foreignDependencyListener: () -> Unit = {
        val pre = synchronized(lock) { versionCounter }
        schedulePropagation(this, pre)
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

    /**
     * The [ReactiveClock] value at which this cell last proved itself clean (validated or
     * recomputed). While the clock hasn't advanced, no write happened anywhere, so the
     * recursive dependency-version walk in [needsRecomputeLocked] is skipped — the cutoff
     * that keeps deep chains O(depth) per write instead of O(depth²). Guarded by [lock].
     */
    private var validatedAtClock = Long.MIN_VALUE

    internal fun updateDefinition(
        policy: EqualityPolicy<T>,
        compute: () -> T,
    ) = synchronized(lock) {
        this.policy = policy
        this.compute = compute
        dirty = true
        // The refreshed definition can change this cell's value (and version) without any
        // source write; advance the clock so downstream stamped-clean cells re-validate.
        ReactiveClock.advance()
    }

    override val version: Long
        get() = synchronized(lock) {
            settleLocked()
            versionCounter
        }

    override val value: T
        get() {
            ReadTracking.record(this)
            val current = synchronized(lock) {
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

    /**
     * The cell is ACTIVE — and therefore holds live subscriptions to its dependencies — whenever
     * it has at least one external observer OR at least one downstream dependent. Reconciliation
     * and teardown gate on this so a purely-derived-from chain (D observes C observes B) stays
     * wired even though the intermediates have no external observers of their own.
     */
    private fun isActiveLocked(): Boolean = registrations.isNotEmpty() || dependents.isNotEmpty()

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
        val active = isActiveLocked()
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

            if (active) {
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
        for (dependency in subscriptions.keys.filter { it !in newDependencies }) {
            subscriptions.remove(dependency)?.dispose()
        }
        for (dependency in newDependencies) {
            if (dependency !in subscriptions) {
                subscriptions[dependency] = linkDependencyLocked(dependency)
            }
        }
    }

    /**
     * Establish the reverse-graph edge from [dependency] to this cell. For a [ReactiveNode]
     * dependency (the production/test norm) this registers this cell as a dependent, so a source
     * write can structurally BFS-reach this cell during a wave; the returned [Disposable] detaches
     * that edge. A foreign dependency (only ever the test-only CountingCell) has no reverse graph,
     * so we fall back to today's eager-push subscription.
     */
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
        synchronized(lock) {
            val wasInactive = !isActiveLocked()
            registrations += registration
            if (wasInactive) {
                // First observer ACTIVATES the cell: compute + subscribe to the current
                // dependency set so that later source writes reach this observer even
                // when .value was never read. Re-observing after a full teardown (which
                // cleared all subscriptions) re-activates the same way. If the cell is
                // already active via a downstream dependent, it is already wired.
                dirty = true
                recomputeLocked()
            }
        }
        return Disposable {
            synchronized(lock) {
                registrations.remove(registration)
                if (!isActiveLocked()) {
                    clearSubscriptionsLocked()
                }
            }
        }
    }

    // ---- ReactiveNode: reverse-graph participation for the propagation wave ----

    // RAW version — must NOT trigger a recompute/self-heal (used to capture the pre-wave version).
    override val versionSnapshot: Long
        get() = synchronized(lock) { versionCounter }

    // The existing self-healing getter — settles the cell (and, transitively, its deps).
    override val versionHealed: Long
        get() = version

    override fun addDependent(dependent: DerivedCell<*>) = synchronized(lock) {
        val wasInactive = !isActiveLocked()
        dependents.add(dependent)
        if (wasInactive) {
            // Activating via a first downstream dependent mirrors observe(): compute and
            // subscribe upstream so this cell is BFS-reachable on later source writes.
            dirty = true
            recomputeLocked()
        }
    }

    override fun removeDependent(dependent: DerivedCell<*>) = synchronized(lock) {
        dependents.remove(dependent)
        if (!isActiveLocked()) {
            clearSubscriptionsLocked()
        }
    }

    override fun snapshotDependents(): List<DerivedCell<*>> = synchronized(lock) { dependents.toList() }

    override fun hasExternalObservers(): Boolean = synchronized(lock) { registrations.isNotEmpty() }

    override fun collectExternalObserversInto(into: MutableList<() -> Unit>) = synchronized(lock) {
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
