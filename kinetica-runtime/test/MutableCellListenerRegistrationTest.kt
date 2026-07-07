package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals

class MutableCellListenerRegistrationTest {
    @Test
    fun disposingOneDuplicateListenerRegistrationLeavesTheOtherActive() {
        val cell = store(0)
        @Suppress("UNCHECKED_CAST")
        val observable = cell as ObservableCell<Int>
        var fired = 0
        val listener: () -> Unit = { fired += 1 }

        val first = observable.observe(listener)
        val second = observable.observe(listener)

        first.dispose()
        cell.value = 1

        assertEquals(1, fired)
        second.dispose()
    }
}
