package io.heapy.kinetica.render

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ListReconcileTest {
    @Test
    fun emptyAndSortedInputs() {
        assertContentEquals(intArrayOf(), longestIncreasingSubsequenceIndices(intArrayOf()))
        assertContentEquals(intArrayOf(0, 1, 2), longestIncreasingSubsequenceIndices(intArrayOf(1, 2, 3)))
    }

    @Test
    fun benchmarkSwapKeepsEverythingButTheSwappedPair() {
        // rows 1 and 5 swapped: old positions of the new order
        val positions = intArrayOf(0, 5, 2, 3, 4, 1, 6)
        assertContentEquals(intArrayOf(0, 2, 3, 4, 6), longestIncreasingSubsequenceIndices(positions))
    }

    @Test
    fun reversalKeepsSingleElement() {
        assertEquals(1, longestIncreasingSubsequenceIndices(intArrayOf(4, 3, 2, 1, 0)).size)
    }

    @Test
    fun freshMountsAreSkipped() {
        // -1 marks children with no previous position (new mounts)
        assertContentEquals(intArrayOf(1, 3, 4), longestIncreasingSubsequenceIndices(intArrayOf(-1, 0, -1, 1, 2)))
        assertContentEquals(intArrayOf(), longestIncreasingSubsequenceIndices(intArrayOf(-1, -1)))
    }
}
