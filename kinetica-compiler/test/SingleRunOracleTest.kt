package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleRunOracleTest {
    @Test
    fun verdictsAreKeyedByCallableIdParameterCountAndName() {
        // Overloads share a CallableId and commonly name their lambda `block`; the
        // regular-parameter count in the key keeps a contract on one overload from
        // leaking a single-run verdict onto another — which would let the IR walker
        // number slots inside a multi-run lambda (silent aliasing).
        val oracle = SingleRunOracle()
        val section = CallableId(FqName("app"), Name.identifier("section"))

        oracle.recordSingleRun(section, regularParameterCount = 1, parameterName = "block")

        assertTrue(oracle.isSingleRun(section, regularParameterCount = 1, parameterName = "block"))
        assertFalse(
            oracle.isSingleRun(section, regularParameterCount = 2, parameterName = "block"),
            "a two-parameter overload must not inherit the one-parameter overload's contract verdict",
        )
        assertFalse(
            oracle.isSingleRun(section, regularParameterCount = 1, parameterName = "other"),
            "a parameter without a recorded contract stays multi-run",
        )
        assertFalse(
            oracle.isSingleRun(
                CallableId(FqName("app"), Name.identifier("other")),
                regularParameterCount = 1,
                parameterName = "block",
            ),
        )
        assertFalse(
            oracle.isSingleRun(null, regularParameterCount = 1, parameterName = "block"),
            "local functions (no CallableId) never get oracle verdicts",
        )
    }
}
