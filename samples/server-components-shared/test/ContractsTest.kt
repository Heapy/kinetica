package app.servercomponents.shared

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ContractsTest {
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
