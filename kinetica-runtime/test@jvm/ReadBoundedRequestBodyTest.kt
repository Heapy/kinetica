package io.heapy.kinetica

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReadBoundedRequestBodyTest {
    @Test
    fun acceptsEmptyAndWithinLimitBodies() {
        for (body in listOf("", "abc", "abcd")) {
            assertEquals(body, readBoundedRequestBody(body.byteInputStream(), limit = 4))
        }
    }

    @Test
    fun rejectsOverflowWithoutDrainingRemainder() {
        val input = "abcde-remainder".byteInputStream()

        assertNull(readBoundedRequestBody(input, limit = 4))
        assertEquals("-remainder", input.readAllBytes().decodeToString())
    }

    @Test
    fun handlesShortReadsAndCountsUtf8Bytes() {
        val body = "ёж"
        val input = object : ByteArrayInputStream(body.encodeToByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 1))
        }

        assertEquals(body, readBoundedRequestBody(input, limit = 4))
        assertNull(readBoundedRequestBody(body.byteInputStream(), limit = 3))
    }

    @Test
    fun rejectsInvalidLimitsIncludingLookaheadOverflow() {
        for (limit in listOf(-1, 0, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                readBoundedRequestBody("".byteInputStream(), limit)
            }
        }
    }
}
