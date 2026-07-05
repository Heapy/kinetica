package app.servercomponents.shared

import kotlinx.serialization.Serializable

public const val ADD_TO_CART_ACTION_ID: String = "cart.add"
public const val ADD_TO_CART_COMPONENT_ID: String = "app.client.AddToCartButton"
public const val DEMO_CAPABILITY_TOKEN: String = "demo-capability"
public const val DEMO_CSRF_TOKEN: String = "demo-csrf"

@Serializable
public data class AddToCartInput(
    val productId: String,
    val quantity: Int,
)

@Serializable
public data class AddToCartResult(
    val message: String,
    val cartCount: Int,
)
