package app.servercomponents.shared

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContractsTest {
    @Test
    fun validatesQuantityBoundaries() {
        for (quantity in listOf(Int.MIN_VALUE, -1, 0, 1000, Int.MAX_VALUE)) {
            assertEquals("quantity must be in 1..999.", AddToCartInput("sku", quantity).validationError())
        }
        for (quantity in listOf(1, 999)) {
            assertNull(AddToCartInput("sku", quantity).validationError())
        }
    }

    @Test
    fun validatesTrimmedProductIdLength() {
        for (id in listOf("", " \t\n", "x".repeat(129))) {
            assertEquals("productId must be 1..128 characters.", AddToCartInput(id, 1).validationError())
        }
        for (id in listOf("x", "x".repeat(128), "  sku  ", "  " + "x".repeat(128) + "  ")) {
            assertNull(AddToCartInput(id, 1).validationError())
        }
    }

    @Test
    fun actionContractsRoundTripAsJson() {
        val input = AddToCartInput(productId = "runtime-license", quantity = 2)
        val result = AddToCartResult(message = "Added", cartCount = 2)

        assertEquals(input, Json.decodeFromString(Json.encodeToString(input)))
        assertEquals(result, Json.decodeFromString(Json.encodeToString(result)))
        assertEquals("cart.add", ADD_TO_CART_ACTION_ID)
        assertEquals("app.client.AddToCartButton", ADD_TO_CART_COMPONENT_ID)
        assertEquals("demo-capability", DEMO_CAPABILITY_TOKEN)
        assertEquals("demo-csrf", DEMO_CSRF_TOKEN)
    }
}
