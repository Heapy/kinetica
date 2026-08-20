package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals

class DerivedCellRecomputeLostUpdateTest {
    @Test
    fun writeInReadToSubscribeWindowIsNotLost() {
        val source = store(1)

        var injectedWrite = false
        val derived = DerivedCell(EqualityPolicy.structural()) {
            val observed = source.value          // read the dependency (TOC)
            if (!injectedWrite) {
                injectedWrite = true
                source.value = 5                 // write lands in the read-to-subscribe window
            }
            observed * 2
        }

        derived.observe {}

        assertEquals(5, source.value, "test hook must have advanced the source to 5")

        assertEquals(
            10,
            derived.value,
            "a source write landing between compute()'s read and the subscribe must not be " +
                "lost: derived should reflect the new source value (5 * 2), but it stayed stale " +
                "at the value computed from the pre-write read (1 * 2)",
        )
    }
}
