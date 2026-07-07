package io.heapy.kinetica

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReadBoundedRequestBodyTest {
    @Test
    fun acceptsBodiesUpToTheLimitAndRejectsTheFirstByteBeyondIt() {
        // Comfortably within the limit -> decoded verbatim.
        assertEquals("hi", readBoundedRequestBody(ByteArrayInputStream("hi".encodeToByteArray()), limit = 8))

        // Exactly at the limit -> accepted (no off-by-one at the boundary).
        assertEquals("abcd", readBoundedRequestBody(ByteArrayInputStream("abcd".encodeToByteArray()), limit = 4))

        // One byte past the limit -> null, which dispatchHttp maps to 413.
        assertNull(readBoundedRequestBody(ByteArrayInputStream("abcde".encodeToByteArray()), limit = 4))

        // Empty body -> empty string, not null.
        assertEquals("", readBoundedRequestBody(ByteArrayInputStream(ByteArray(0)), limit = 4))

        assertFailsWith<IllegalArgumentException> {
            readBoundedRequestBody(ByteArrayInputStream(ByteArray(0)), limit = 0)
        }
    }
}
