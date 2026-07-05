package io.heapy.kinetica

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable

public class JournalBuffer<T>(
    public val capacity: Int,
) {
    private val entries = ArrayDeque<T>()
    private val lock = SynchronizedObject()

    init {
        require(capacity > 0) { "Journal buffer capacity must be positive." }
    }

    public fun append(entry: T) {
        synchronized(lock) {
            if (entries.size == capacity) {
                entries.removeFirst()
            }
            entries.addLast(entry)
        }
    }

    public fun entries(): List<T> = synchronized(lock) {
        entries.toList()
    }

    public fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }
}

@Serializable
public data class JournalEntry(
    val sequence: Long,
    val kind: JournalKind,
    val message: String,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
public enum class JournalKind {
    RenderStarted,
    RenderCommitted,
    CellWrite,
    Event,
    EffectStarted,
    EffectCancelled,
    WatchRestart,
    WatchLoop,
    ResourceLoad,
    ResourceInvalidated,
    BoundaryError,
    DeferredSubtree,
    Skipped,
    Leaving,
    VirtualTime,
    SlotSnapshot,
}

@Serializable
public data class RuntimeWarning(
    val sequence: Long,
    val code: String,
    val message: String,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
public data class SlotSnapshotEntry(
    val key: String,
    val slotId: SlotId? = null,
    val persistent: Boolean,
    val transient: Boolean,
    val value: String,
)

@Serializable
public data class SlotSnapshot(
    val sequence: Long,
    val slots: List<SlotSnapshotEntry> = emptyList(),
)

@Serializable
public data class RenderSnapshot(
    val sequence: Long,
    val cause: String,
    val tree: Node,
    val slots: SlotSnapshot,
)

@Serializable
public data class ExecutionJournal(
    val entries: List<JournalEntry>,
    val renderSnapshots: List<RenderSnapshot> = emptyList(),
)

@Serializable
public data class ReplayState(
    val sequence: Long,
    val entries: List<JournalEntry>,
    val render: RenderSnapshot?,
)

public class JournalReplay(
    private val journal: ExecutionJournal,
) {
    public fun stateAt(sequence: Long): ReplayState =
        ReplayState(
            sequence = sequence,
            entries = journal.entries.filter { it.sequence <= sequence },
            render = journal.renderSnapshots.lastOrNull { it.sequence <= sequence },
        )

    public fun latest(): ReplayState {
        val latestEntry = journal.entries.lastOrNull()?.sequence ?: 0L
        val latestSnapshot = journal.renderSnapshots.lastOrNull()?.sequence ?: 0L
        return stateAt(maxOf(latestEntry, latestSnapshot))
    }

    public fun states(): List<ReplayState> =
        journal.renderSnapshots.map { stateAt(it.sequence) }
}

public fun replayJournal(journal: ExecutionJournal): JournalReplay =
    JournalReplay(journal)
