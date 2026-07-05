package app.servercomponents.client

import app.servercomponents.shared.ADD_TO_CART_ACTION_ID
import app.servercomponents.shared.ADD_TO_CART_COMPONENT_ID
import app.servercomponents.shared.AddToCartInput
import app.servercomponents.shared.AddToCartResult
import app.servercomponents.shared.DEMO_CAPABILITY_TOKEN
import app.servercomponents.shared.DEMO_CSRF_TOKEN
import io.heapy.kinetica.CapabilityToken
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.CsrfToken
import io.heapy.kinetica.KineticaJson
import io.heapy.kinetica.KineticaServerTransport
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.ServerActionRequest
import io.heapy.kinetica.ServerActionResponse
import io.heapy.kinetica.ServerRenderChunk
import io.heapy.kinetica.browser.BrowserKineticaApp
import io.heapy.kinetica.browser.mountKineticaApp
import io.heapy.kinetica.button
import io.heapy.kinetica.column
import io.heapy.kinetica.event
import io.heapy.kinetica.row
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.w3c.dom.Document
import org.w3c.dom.Element

fun main() {
    val hydrationPlan = KineticaServerTransport().decodeHydrationPlan(
        document.querySelector("#kinetica-hydration-plan")?.textContent.orEmpty(),
    )
    document.querySelector("#hydration-status")?.textContent =
        "Hydration plan loaded: ${hydrationPlan.clientIslands.size} client island"

    hydrateClientRefs()
    loadServerStream()
}

private fun hydrateClientRefs() {
    val refs = document.querySelectorAll("[data-kinetica-client-ref]")
    for (index in 0 until refs.length) {
        val template = refs.item(index) as? Element ?: continue
        val componentId = template.getAttribute("data-kinetica-client-ref") ?: continue
        if (componentId != ADD_TO_CART_COMPONENT_ID) {
            continue
        }

        val props = KineticaJson.decodeFromString<AddToCartInput>(
            template.getAttribute("data-kinetica-props").orEmpty(),
        )
        val root = document.createElement("span")
        root.setAttribute("data-client-island-root", componentId)
        template.parentNode?.replaceChild(root, template)

        lateinit var app: BrowserKineticaApp
        app = mountKineticaApp(root) {
            AddToCartButton(
                initial = props,
                rerender = { app.render() },
            )
        }
    }
}

private fun ComponentScope.AddToCartButton(
    initial: AddToCartInput,
    rerender: () -> Unit,
) {
    var quantity by state(key = "quantity:${initial.productId}") { initial.quantity }
    var pending by state(key = "pending:${initial.productId}") { false }
    var status by state(key = "status:${initial.productId}") { "Client island hydrated" }

    column(semantics = Semantics(testTag = "add-to-cart-island")) {
        text("Client island")
        row {
            button(
                enabled = !pending && quantity > 1,
                onClick = event { quantity -= 1 },
                semantics = Semantics(role = Role.Button, testTag = "quantity-decrease", focusable = true),
            ) {
                text("-")
            }
            text("Quantity: $quantity")
            button(
                enabled = !pending,
                onClick = event { quantity += 1 },
                semantics = Semantics(role = Role.Button, testTag = "quantity-increase", focusable = true),
            ) {
                text("+")
            }
        }
        button(
            enabled = !pending,
            onClick = event {
                pending = true
                status = "Calling server action..."
                postServerAction(
                    input = AddToCartInput(
                        productId = initial.productId,
                        quantity = quantity,
                    ),
                    onSettled = { message ->
                        pending = false
                        status = message
                        rerender()
                    },
                )
            },
            semantics = Semantics(role = Role.Button, testTag = "add-to-cart", focusable = true),
        ) {
            text(if (pending) "Adding..." else "Add to cart")
        }
        text(status, semantics = Semantics(role = Role.Text, testTag = "cart-status"))
    }
}

private fun postServerAction(
    input: AddToCartInput,
    onSettled: (String) -> Unit,
) {
    val request = ServerActionRequest(
        actionId = ADD_TO_CART_ACTION_ID,
        payload = KineticaJson.encodeToJsonElement(AddToCartInput.serializer(), input),
        token = CapabilityToken(DEMO_CAPABILITY_TOKEN),
        csrfToken = CsrfToken(DEMO_CSRF_TOKEN),
    )
    postJson(
        url = "/actions/$ADD_TO_CART_ACTION_ID",
        body = KineticaJson.encodeToString(ServerActionRequest.serializer(), request),
    ).then({ text: String ->
        val response = KineticaServerTransport().decodeActionResponse(text)
        val message = when (response) {
            is ServerActionResponse.Success -> {
                val result = KineticaJson.decodeFromJsonElement(
                    AddToCartResult.serializer(),
                    response.payload,
                )
                "${result.message}. Cart count: ${result.cartCount}"
            }
            is ServerActionResponse.Failure -> "Server action failed: ${response.message}"
        }
        onSettled(message)
        null
    }).catch({ error: dynamic ->
        onSettled("Server action failed: ${error?.message ?: error.toString()}")
        null
    })
}

private fun loadServerStream() {
    val status = document.querySelector("#stream-status")
    fetchText("/stream").then({ text: String ->
        val transport = KineticaServerTransport()
        text.lineSequence()
            .filter { line -> line.isNotBlank() }
            .forEach { line ->
                when (val chunk = transport.decodeChunk(line)) {
                    is ServerRenderChunk.Patch -> applyServerPatch(chunk)
                    is ServerRenderChunk.Tree -> Unit
                    is ServerRenderChunk.BoundaryError -> {
                        status?.textContent = "Server stream error: ${chunk.message}"
                    }
                    is ServerRenderChunk.End -> {
                        status?.textContent = "Server stream applied"
                    }
                }
            }
        null
    }).catch({ error: dynamic ->
        status?.textContent = "Server stream failed: ${error?.message ?: error.toString()}"
        null
    })
}

private fun applyServerPatch(chunk: ServerRenderChunk.Patch) {
    if (chunk.path != listOf(3)) {
        return
    }
    val node = chunk.node ?: return
    val target = document.querySelector("#recommendations") ?: return
    mountKineticaApp(target) {
        emit(node)
    }
}

private fun postJson(
    url: String,
    body: String,
): dynamic {
    val headers = js("{}")
    headers["Content-Type"] = "application/json"
    val init = js("{}")
    init.method = "POST"
    init.headers = headers
    init.body = body
    return js(
        "fetch(url, init).then((response) => {" +
            "if (!response.ok) throw new Error('HTTP ' + response.status);" +
            "return response.text();" +
            "})",
    )
}

private fun fetchText(url: String): dynamic =
    js(
        "fetch(url).then((response) => {" +
            "if (!response.ok) throw new Error('HTTP ' + response.status);" +
            "return response.text();" +
            "})",
    )

private val document: Document
    get() = js("document").unsafeCast<Document>()
