package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals

class DerivedCellVersionMatchesValueWhenDirtyTest {
    @Test
    fun versionGetterRecomputesDirtyDerivedCellSoItAgreesWithValue() {
        val source = store(10)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            source.value * 2
        }

        val subscription = derived.observe {}
        assertEquals(20, derived.value)

        subscription.dispose()
        source.value = 11

        val versionWhileDirty = derived.version

        assertEquals(22, derived.value)
        val versionAfterValue = derived.version

        assertEquals(
            versionAfterValue,
            versionWhileDirty,
            "version getter must recompute a dirty DerivedCell so version agrees with value; " +
                "a stale version lets dependenciesUnchanged() skip a needed re-render",
        )
    }
}
