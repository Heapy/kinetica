package io.heapy.kinetica

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropsTest {
    @Test
    fun propsOfMatchesMapOfContract() {
        val props = propsOf("class", "danger", "data-id", "42")
        val reference = mapOf("class" to "danger", "data-id" to "42")

        assertEquals(reference, props)
        assertEquals(props, reference)
        assertEquals(reference.hashCode(), props.hashCode())
        assertEquals(reference.entries, props.entries)
        assertEquals(reference.toString(), props.toString())
    }

    @Test
    fun lookupAndMembership() {
        val props = propsOf("class", "danger", "aria-hidden", "true")

        assertEquals("danger", props["class"])
        assertEquals("true", props["aria-hidden"])
        assertNull(props["missing"])
        assertTrue("class" in props)
        assertFalse("missing" in props)
        assertEquals(2, props.size)
        assertFalse(props.isEmpty())
        assertTrue(propsOf().isEmpty())
    }

    @Test
    fun iterationPreservesInsertionOrder() {
        val props = propsOf("a", "1", "b", "2", "c", "3")
        assertEquals(listOf("a", "b", "c"), props.keys.toList())
        assertEquals(listOf("1", "2", "3"), props.values.toList())
    }

    @Test
    fun duplicateNamesKeepLastWinsLikeMapOf() {
        val props = propsOf("class", "first", "class", "second")
        assertEquals(mapOf("class" to "first", "class" to "second"), props)
        assertEquals("second", props["class"])
        assertEquals(1, props.size)
    }

    @Test
    fun pairVarargOverloadMatchesPositional() {
        assertEquals(
            propsOf("class", "a", "data-id", "7"),
            propsOf("class" to "a", "data-id" to "7"),
        )
        assertEquals(emptyMap(), propsOf(*emptyArray<Pair<String, String>>()))
    }

    @Test
    fun hostNodesBuiltWithPropsOfAndMapOfAreEqualAndSerializeIdentically() {
        val json = Json
        val withPropsOf = HostNode("tr", propsOf("class", "danger", "data-id", "42"), key = "42")
        val withMapOf = HostNode("tr", mapOf("class" to "danger", "data-id" to "42"), key = "42")

        assertEquals(withMapOf, withPropsOf)
        assertEquals(withMapOf.hashCode(), withPropsOf.hashCode())
        assertEquals(
            json.encodeToString(HostNode.serializer(), withMapOf),
            json.encodeToString(HostNode.serializer(), withPropsOf),
        )
    }
}
