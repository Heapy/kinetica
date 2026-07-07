package app.servercomponents.shared

import kotlinx.serialization.Serializable

public const val ADD_TO_CART_ACTION_ID: String = "cart.add"
public const val ADD_TO_CART_COMPONENT_ID: String = "app.client.AddToCartButton"
public const val DEMO_CAPABILITY_TOKEN: String = "demo-capability"
public const val DEMO_CSRF_TOKEN: String = "demo-csrf"

/** Inclusive bounds for a single add-to-cart quantity. Enforced at the handler, not by the schema. */
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
 * Validates an [AddToCartInput] against business invariants the schema cannot express.
 * Returns a human-readable error for an invalid input, or `null` when it is acceptable.
 *
 * The dispatcher's serializer-derived schema only checks *shape* (`quantity` is a number);
 * it does not bound the range, so a client could send negatives or `Int.MAX_VALUE` in a single
 * request. This bounds one request's magnitude; it does not make the cumulative demo `cartCount`
 * overflow-proof. A production counter should be per-session and saturating/`Long`.
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
