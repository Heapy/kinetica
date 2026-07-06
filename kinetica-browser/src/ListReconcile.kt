package io.heapy.kinetica.browser

/**
 * Indices (into [values]) of one longest strictly-increasing subsequence.
 * Negative entries mean "no previous position" (freshly mounted nodes) and are skipped —
 * they can never be part of the stable sequence.
 *
 * Used by keyed child reconciliation: children whose old positions form the LIS stay in
 * place; everything else is moved. O(n log n).
 */
internal fun longestIncreasingSubsequenceIndices(values: IntArray): IntArray {
    val scratch = LongestIncreasingSubsequenceScratch()
    val size = longestIncreasingSubsequenceIndices(values, values.size, scratch)
    return scratch.result.copyOf(size)
}

internal class LongestIncreasingSubsequenceScratch {
    var tails: IntArray = IntArray(0)
    var previous: IntArray = IntArray(0)
    var result: IntArray = IntArray(0)
}

internal fun longestIncreasingSubsequenceIndices(
    values: IntArray,
    size: Int,
    scratch: LongestIncreasingSubsequenceScratch,
): Int {
    if (size == 0) return 0
    if (scratch.tails.size < size) {
        scratch.tails = IntArray(size)
    }
    if (scratch.previous.size < size) {
        scratch.previous = IntArray(size)
    }
    if (scratch.result.size < size) {
        scratch.result = IntArray(size)
    }
    val tails = scratch.tails
    val previous = scratch.previous
    previous.fill(-1, 0, size)
    var length = 0
    for (index in 0 until size) {
        val value = values[index]
        if (value < 0) continue
        var low = 0
        var high = length
        while (low < high) {
            val mid = (low + high) / 2
            if (values[tails[mid]] < value) low = mid + 1 else high = mid
        }
        if (low > 0) {
            previous[index] = tails[low - 1]
        }
        tails[low] = index
        if (low == length) length++
    }
    if (length == 0) return 0
    val result = scratch.result
    var cursor = tails[length - 1]
    for (position in length - 1 downTo 0) {
        result[position] = cursor
        cursor = previous[cursor]
    }
    return length
}
