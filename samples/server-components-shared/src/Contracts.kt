package app.servercomponents.shared

import kotlinx.serialization.Serializable

public const val ADD_TO_CART_ACTION_ID: String = "cart.add"
public const val ADD_TO_CART_COMPONENT_ID: String = "app.client.AddToCartButton"
public const val DEMO_CAPABILITY_TOKEN: String = "demo-capability"
public const val DEMO_CSRF_TOKEN: String = "demo-csrf"

/** Inclusive bounds for one add-to-cart request. */
public val CART_QUANTITY_RANGE: IntRange = 1..999

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

/**
 * Returns a client-safe validation error, or `null` for an acceptable input.
 * Checks product ID length after trimming and quantity per request; does not cap accumulated cartCount.
 */
public fun AddToCartInput.validationError(): String? {
    val trimmed = productId.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_PRODUCT_ID_LENGTH) {
        return "productId must be 1..$MAX_PRODUCT_ID_LENGTH characters."
    }
    if (quantity !in CART_QUANTITY_RANGE) {
        return "quantity must be in $CART_QUANTITY_RANGE."
    }
    return null
}

private const val MAX_PRODUCT_ID_LENGTH: Int = 128
