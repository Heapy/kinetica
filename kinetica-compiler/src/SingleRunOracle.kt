package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.name.CallableId
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-compilation channel carrying the FIR checker's contract verdicts to the IR frame
 * pass. FIR resolves `callsInPlace(block, EXACTLY_ONCE / AT_MOST_ONCE)` contracts and
 * records the single-run lambda parameters here; the IR walker then descends into exactly
 * the lambdas FIR approved, so the two phases cannot disagree about a contract host.
 *
 * Absence of an entry is NOT a multi-run verdict. Callees without a usable contract —
 * `AT_LEAST_ONCE`/`UNKNOWN` occurrence kinds, no contract at all, null-`callableId`
 * symbols, or the FIR extension not being registered — fall through to the shared
 * [KineticaFramePolicy] name lists on BOTH sides, so the scope functions never regress
 * if contract resolution fails.
 *
 * Constructed once per compilation in `registerKineticaCompilerExtensions` and handed to
 * both extensions — never a global object, because one JVM (a compile daemon, the test
 * suite) runs many compilations. The map is concurrent because FIR checkers may run on
 * multiple threads. Keys include the regular-value-parameter count so overloads sharing
 * a [CallableId] cannot collide on a common parameter name like `block`.
 */
internal class SingleRunOracle {
    private data class Key(val callableId: CallableId, val regularParameterCount: Int)

    private val singleRunParameters = ConcurrentHashMap<Key, MutableSet<String>>()

    fun recordSingleRun(callableId: CallableId, regularParameterCount: Int, parameterName: String) {
        singleRunParameters
            .computeIfAbsent(Key(callableId, regularParameterCount)) { ConcurrentHashMap.newKeySet() }
            .add(parameterName)
    }

    fun isSingleRun(callableId: CallableId?, regularParameterCount: Int, parameterName: String): Boolean {
        if (callableId == null) return false
        val parameters = singleRunParameters[Key(callableId, regularParameterCount)] ?: return false
        return parameterName in parameters
    }
}
