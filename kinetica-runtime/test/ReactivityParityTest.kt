package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReactivityParityTest {
    /**
     * KSND-080 (sources: SVL-001, SOL-020, VUE-029).
     */
    @Test
    fun diamondObserversSeeOnlyConsistentPairs() {
        val probe = object {
            var bComputes = 0
            var cComputes = 0
            var dComputes = 0
            val published = mutableListOf<String>()
        }
        val a = store(0)
        val b = DerivedCell(EqualityPolicy.structural()) {
            probe.bComputes += 1
            a.value * 2
        }
        val c = DerivedCell(EqualityPolicy.structural()) {
            probe.cComputes += 1
            a.value + 1
        }
        val d = DerivedCell(EqualityPolicy.structural()) {
            probe.dComputes += 1
            "${b.value}:${c.value}"
        }

        val subscription = d.observe { probe.published += d.value }
        assertEquals("0:1", d.value)
        probe.bComputes = 0
        probe.cComputes = 0
        probe.dComputes = 0
        probe.published.clear()

        a.value = 1
        a.value = 2

        assertEquals(listOf("2:2", "4:3"), probe.published)
        assertEquals(2, probe.bComputes)
        assertEquals(2, probe.cComputes)
        assertEquals(2, probe.dComputes)
        subscription.dispose()
    }

    /**
     * KSND-081 (sources: SVL-006, SOL-021, VUE-027, VUE-033).
     */
    @Test
    fun twoSourceJoinRecomputesAndNotifiesOncePerWave() {
        val probe = object {
            var firstComputes = 0
            var secondComputes = 0
            var joinComputes = 0
            val firstPublished = mutableListOf<Int>()
            val secondPublished = mutableListOf<Int>()
            val joinPublished = mutableListOf<String>()
        }
        val left = store(1)
        val right = store(10)
        val first = DerivedCell(EqualityPolicy.structural()) {
            probe.firstComputes += 1
            left.value + right.value
        }
        val second = DerivedCell(EqualityPolicy.structural()) {
            probe.secondComputes += 1
            left.value * 10 + right.value
        }
        val join = DerivedCell(EqualityPolicy.structural()) {
            probe.joinComputes += 1
            "${first.value}:${second.value}"
        }

        val firstSubscription = first.observe { probe.firstPublished += first.value }
        val secondSubscription = second.observe { probe.secondPublished += second.value }
        val joinSubscription = join.observe { probe.joinPublished += join.value }
        assertEquals(11, first.value)
        assertEquals(20, second.value)
        assertEquals("11:20", join.value)
        probe.firstComputes = 0
        probe.secondComputes = 0
        probe.joinComputes = 0
        probe.firstPublished.clear()
        probe.secondPublished.clear()
        probe.joinPublished.clear()

        left.value = 2

        assertEquals(1, probe.firstComputes)
        assertEquals(1, probe.secondComputes)
        assertEquals(1, probe.joinComputes)
        assertEquals(listOf(12), probe.firstPublished)
        assertEquals(listOf(30), probe.secondPublished)
        assertEquals(listOf("12:30"), probe.joinPublished)

        right.value = 20

        assertEquals(2, probe.firstComputes)
        assertEquals(2, probe.secondComputes)
        assertEquals(2, probe.joinComputes)
        assertEquals(listOf(12, 22), probe.firstPublished)
        assertEquals(listOf(30, 40), probe.secondPublished)
        assertEquals(listOf("12:30", "22:40"), probe.joinPublished)
        firstSubscription.dispose()
        secondSubscription.dispose()
        joinSubscription.dispose()
    }

    /**
     * KSND-082 (sources: VUE-013, SOL-029).
     */
    @Test
    fun deepChainPropagatesOnceInOnePass() {
        val source = store(0)
        val recomputes = MutableList(30) { 0 }
        val chain = mutableListOf<DerivedCell<Int>>()
        var previous: Cell<Int> = source
        for (index in 0 until 30) {
            val dependency = previous
            val derived = DerivedCell(EqualityPolicy.structural()) {
                recomputes[index] = recomputes[index] + 1
                dependency.value + 1
            }
            chain += derived
            previous = derived
        }
        val leaf = chain.last()
        val leafPublished = mutableListOf<Int>()
        val subscriptions = mutableListOf<Disposable>()
        // A JVM probe with only penultimate + terminal observed failed with
        // recomputes [3 x 28, 2 x 2]; unobserved intermediates currently
        // recompute differently.
        for (cell in chain) {
            subscriptions += cell.observe {}
        }
        val leafSubscription = leaf.observe { leafPublished += leaf.value }

        assertEquals(30, leaf.value)
        source.value = 10

        assertEquals(40, leaf.value)
        assertEquals(List(30) { 2 }, recomputes)
        assertEquals(listOf(40), leafPublished)
        leafSubscription.dispose()
        subscriptions.forEach { it.dispose() }
    }

    /**
     * KSND-083 (sources: SOL-004, SOL-024, VUE-006, SVL-009).
     */
    @Test
    fun dynamicDependencySwapDropsStaleEdges() {
        val flag = store(true)
        val left = store(1)
        val right = store(10)
        val probe = object {
            var computes = 0
            val published = mutableListOf<Int>()
        }
        val selected = DerivedCell(EqualityPolicy.structural()) {
            probe.computes += 1
            if (flag.value) left.value else right.value
        }

        val subscription = selected.observe { probe.published += selected.value }
        assertEquals(1, selected.value)
        assertTrue(isDependent(flag, selected))
        assertTrue(isDependent(left, selected))
        assertFalse(isDependent(right, selected))
        probe.computes = 0
        probe.published.clear()

        right.value = 20
        assertEquals(0, probe.computes)
        assertEquals(emptyList(), probe.published)

        left.value = 2
        assertEquals(1, probe.computes)
        assertEquals(listOf(2), probe.published)

        probe.computes = 0
        flag.value = false
        assertEquals(1, probe.computes)
        assertEquals(listOf(2, 20), probe.published)
        assertTrue(isDependent(flag, selected))
        assertFalse(isDependent(left, selected))
        assertTrue(isDependent(right, selected))

        probe.computes = 0
        left.value = 3
        assertEquals(0, probe.computes)
        assertEquals(listOf(2, 20), probe.published)

        right.value = 30
        assertEquals(1, probe.computes)
        assertEquals(listOf(2, 20, 30), probe.published)

        probe.computes = 0
        flag.value = true
        assertEquals(1, probe.computes)
        assertEquals(listOf(2, 20, 30, 3), probe.published)
        assertTrue(isDependent(flag, selected))
        assertTrue(isDependent(left, selected))
        assertFalse(isDependent(right, selected))

        probe.computes = 0
        right.value = 40
        assertEquals(0, probe.computes)
        assertEquals(listOf(2, 20, 30, 3), probe.published)

        left.value = 4
        assertEquals(1, probe.computes)
        assertEquals(listOf(2, 20, 30, 3, 4), probe.published)
        subscription.dispose()
        assertFalse(isDependent(flag, selected))
        assertFalse(isDependent(left, selected))
        assertFalse(isDependent(right, selected))
    }

    /**
     * KSND-084 (sources: SVL-019a, SOL-022, VUE-035).
     */
    @Test
    fun unchangedIntermediatePrunesDownstreamObservers() {
        val source = store(0)
        val probe = object {
            var midComputes = 0
            var leafComputes = 0
            val published = mutableListOf<Int>()
        }
        val mid = DerivedCell(EqualityPolicy.structural()) {
            probe.midComputes += 1
            source.value / 2
        }
        val leaf = DerivedCell(EqualityPolicy.structural()) {
            probe.leafComputes += 1
            mid.value * 10
        }

        val subscription = leaf.observe { probe.published += leaf.value }
        assertEquals(0, leaf.value)
        probe.midComputes = 0
        probe.leafComputes = 0
        probe.published.clear()

        source.value = 1
        assertEquals(1, probe.midComputes)
        assertEquals(0, probe.leafComputes)
        assertEquals(emptyList(), probe.published)
        assertEquals(0, leaf.value)

        source.value = 2
        assertEquals(2, probe.midComputes)
        assertEquals(1, probe.leafComputes)
        assertEquals(listOf(10), probe.published)
        assertEquals(10, leaf.value)
        subscription.dispose()
    }

    /**
     * KSND-085 (sources: SVL-014, SOL-025, VUE-041).
     */
    @Test
    fun disconnectedDerivedReconnectsWithCurrentValue() {
        val gate = store(true)
        val source = store(1)
        val probe = object {
            var derivedComputes = 0
            var observerComputes = 0
            val published = mutableListOf<Int>()
        }
        val derived = DerivedCell(EqualityPolicy.structural()) {
            probe.derivedComputes += 1
            source.value * 10
        }
        val observer = DerivedCell(EqualityPolicy.structural()) {
            probe.observerComputes += 1
            if (gate.value) derived.value else -1
        }

        val subscription = observer.observe { probe.published += observer.value }
        assertEquals(10, observer.value)
        assertTrue(isDependent(source, derived))
        assertTrue(isDependent(derived, observer))
        probe.derivedComputes = 0
        probe.observerComputes = 0
        probe.published.clear()

        gate.value = false
        assertEquals(listOf(-1), probe.published)
        assertFalse(isDependent(source, derived))
        assertFalse(isDependent(derived, observer))
        probe.derivedComputes = 0
        probe.observerComputes = 0
        probe.published.clear()

        source.value = 2
        source.value = 3
        assertEquals(0, probe.derivedComputes)
        assertEquals(0, probe.observerComputes)
        assertEquals(emptyList(), probe.published)

        gate.value = true
        assertEquals(1, probe.observerComputes)
        assertEquals(listOf(30), probe.published)
        assertTrue(isDependent(source, derived))
        assertTrue(isDependent(derived, observer))
        assertEquals(30, observer.value)
        probe.derivedComputes = 0
        probe.observerComputes = 0

        source.value = 4
        assertEquals(1, probe.derivedComputes)
        assertEquals(1, probe.observerComputes)
        assertEquals(listOf(30, 40), probe.published)
        subscription.dispose()
    }

    /**
     * KSND-086 (sources: SVL-115, SVL-003, SVL-008, VUE-021, SOL-003).
     */
    @Test
    fun unobservedReadsDoNotLeakAndDisposalReleasesSources() {
        val source = store(1)
        val probe = object {
            var computes = 0
            val published = mutableListOf<Int>()
        }
        val derived = DerivedCell(EqualityPolicy.structural()) {
            probe.computes += 1
            source.value * 2
        }

        repeat(5) {
            assertEquals(2, derived.value)
        }
        assertEquals(1, probe.computes)
        assertEquals(0, dependentCount(source))

        val subscription = derived.observe { probe.published += derived.value }
        assertEquals(1, dependentCount(source))
        source.value = 2
        assertEquals(listOf(4), probe.published)

        subscription.dispose()
        assertEquals(0, dependentCount(source))
        val computesBeforePostDisposeWrite = probe.computes
        probe.published.clear()

        source.value = 3
        assertEquals(computesBeforePostDisposeWrite, probe.computes)
        assertEquals(emptyList(), probe.published)
        assertEquals(6, derived.value)
        assertEquals(computesBeforePostDisposeWrite + 1, probe.computes)
        assertEquals(0, dependentCount(source))
    }

    /**
     * KSND-087 (sources: SOL-009, VUE-058).
     */
    @Test
    fun throwingRecomputeRecoversAndListenerExceptionsAreIsolated() {
        val source = store(1)
        val probe = object {
            var riskyComputes = 0
            var leafComputes = 0
            val leafPublished = mutableListOf<Int>()
        }
        val risky = DerivedCell(EqualityPolicy.structural()) {
            probe.riskyComputes += 1
            val value = source.value
            if (value == 13) {
                throw IllegalStateException("boom on sentinel")
            }
            value * 2
        }
        val leaf = DerivedCell(EqualityPolicy.structural()) {
            probe.leafComputes += 1
            risky.value + 1
        }

        val leafSubscription = leaf.observe { probe.leafPublished += leaf.value }
        assertEquals(3, leaf.value)
        probe.leafPublished.clear()

        assertFailsWith<IllegalStateException> {
            source.value = 13
        }
        assertEquals(emptyList(), probe.leafPublished)

        source.value = 2
        assertEquals(5, leaf.value)
        assertEquals(listOf(5), probe.leafPublished)
        leafSubscription.dispose()

        val noisy = store(0)
        val noisyObservable = noisy as ObservableCell<*>
        val firstPublished = mutableListOf<Int>()
        val secondPublished = mutableListOf<Int>()
        val firstSubscription = noisyObservable.observe {
            firstPublished += noisy.value
            throw RuntimeException("boom from first listener")
        }
        val secondSubscription = noisyObservable.observe { secondPublished += noisy.value }

        // These assertFailsWith checks certify propagate-to-writer semantics:
        // the first listener's exception surfaces at the write site.
        assertFailsWith<RuntimeException> {
            noisy.value = 1
        }
        assertFailsWith<RuntimeException> {
            noisy.value = 2
        }
        assertEquals(listOf(1, 2), firstPublished)
        assertEquals(listOf(1, 2), secondPublished)
        firstSubscription.dispose()
        secondSubscription.dispose()
    }

    /**
     * KSND-088 (sources: SVL-019, SOL-028).
     */
    @Test
    fun observeBeforeReadActivatesDelivery() {
        val source = store(1)
        val probe = object {
            var computes = 0
            val published = mutableListOf<Int>()
        }
        val derived = DerivedCell(EqualityPolicy.structural()) {
            probe.computes += 1
            source.value * 3
        }

        val subscription = derived.observe { probe.published += derived.value }
        assertEquals(1, probe.computes)
        assertEquals(emptyList(), probe.published)

        source.value = 5

        assertEquals(2, probe.computes)
        assertEquals(listOf(15), probe.published)
        assertEquals(15, derived.value)
        subscription.dispose()
    }

    /**
     * KSND-089 (sources: VUE-007, SOL-002, PRE-111).
     */
    @Test
    fun sameValueWritesDoNotNotifyIncludingNanPolicyAndDerivedSilence() {
        val intCell = store(5)
        var intNotifications = 0
        val intSubscription = observeCell(intCell) { intNotifications += 1 }
        intCell.value = 5
        assertEquals(0, intNotifications)

        val recordCell = store(ParityRecord("Ada", 36))
        var recordNotifications = 0
        val recordSubscription = observeCell(recordCell) { recordNotifications += 1 }
        recordCell.value = ParityRecord("Ada", 36)
        assertEquals(0, recordNotifications)

        val nanCell = store(Double.NaN)
        var nanNotifications = 0
        val nanSubscription = observeCell(nanCell) { nanNotifications += 1 }
        nanCell.value = Double.NaN
        assertEquals(0, nanNotifications)

        val source = store(5)
        val probe = object {
            var midComputes = 0
            var leafComputes = 0
            var leafNotifications = 0
        }
        val mid = DerivedCell(EqualityPolicy.structural()) {
            probe.midComputes += 1
            source.value * 2
        }
        val leaf = DerivedCell(EqualityPolicy.structural()) {
            probe.leafComputes += 1
            mid.value + 1
        }
        val leafSubscription = leaf.observe { probe.leafNotifications += 1 }
        assertEquals(11, leaf.value)
        probe.midComputes = 0
        probe.leafComputes = 0

        source.value = 5

        assertEquals(0, probe.midComputes)
        assertEquals(0, probe.leafComputes)
        assertEquals(0, probe.leafNotifications)
        assertEquals(11, leaf.value)
        intSubscription.dispose()
        recordSubscription.dispose()
        nanSubscription.dispose()
        leafSubscription.dispose()
    }
}

private data class ParityRecord(
    val name: String,
    val age: Int,
)

private fun dependentsOf(cell: Cell<*>): List<DerivedCell<*>> =
    (cell as ReactiveNode).snapshotDependents()

private fun dependentCount(cell: Cell<*>): Int =
    dependentsOf(cell).size

private fun isDependent(
    source: Cell<*>,
    derived: DerivedCell<*>,
): Boolean =
    dependentsOf(source).any { dependent -> dependent === derived }

private fun observeCell(
    cell: Cell<*>,
    listener: () -> Unit,
): Disposable =
    (cell as ObservableCell<*>).observe(listener)
