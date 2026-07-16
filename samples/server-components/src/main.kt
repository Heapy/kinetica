package app.servercomponents

import app.servercomponents.shared.ADD_TO_CART_ACTION_ID
import app.servercomponents.shared.ADD_TO_CART_COMPONENT_ID
import app.servercomponents.shared.AddToCartInput
import app.servercomponents.shared.AddToCartResult
import app.servercomponents.shared.DEMO_CAPABILITY_TOKEN
import app.servercomponents.shared.DEMO_CSRF_TOKEN
import app.servercomponents.shared.validationError
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.heapy.kinetica.CapabilityToken
import io.heapy.kinetica.ClientComponentManifest
import io.heapy.kinetica.ClientComponentRegistration
import io.heapy.kinetica.ClientRef
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.CsrfToken
import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.KineticaJson
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.KineticaSecurityHeaders
import io.heapy.kinetica.KineticaServerActionDispatcher
import io.heapy.kinetica.KineticaServerTransport
import io.heapy.kinetica.ServerActionRegistration
import io.heapy.kinetica.ServerActionRequest
import io.heapy.kinetica.ServerActionRejection
import io.heapy.kinetica.ServerRenderDeferredSubtree
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.dispatchHttp
import io.heapy.kinetica.host
import io.heapy.kinetica.hydrationPlan
import io.heapy.kinetica.readBoundedRequestBody
import io.heapy.kinetica.serverActionStub
import io.heapy.kinetica.text
import io.heapy.kinetica.toSafeHtml
import io.heapy.kinetica.toServerRenderStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.readText

internal fun ComponentScope.ProductPage() {
    host("main", props = mapOf("class" to "page-shell")) {
        host("header", props = mapOf("class" to "hero")) {
            text("Server-rendered product page")
            host("p") {
                text("The product details below are rendered on the JVM. The add-to-cart control is a client island.")
            }
        }
        host("section", props = mapOf("class" to "product", "aria-label" to "Product details")) {
            host("h1") {
                text("Kinetica runtime license")
            }
            host("p") {
                text("SSR HTML, hydration metadata, a typed server action, and a streamed server patch on one page.")
            }
        }
        emit(
            ClientRef(
                componentId = ADD_TO_CART_COMPONENT_ID,
                props = JsonObject(
                    mapOf(
                        "productId" to JsonPrimitive("runtime-license"),
                        "quantity" to JsonPrimitive(1),
                    ),
                ),
            ),
        )
        host(
            "section",
            props = mapOf(
                "id" to "recommendations",
                "class" to "recommendations",
                "aria-live" to "polite",
            ),
        ) {
            text("Waiting for streamed recommendations...")
        }
        host("section", props = mapOf("class" to "runtime-log")) {
            host("p", props = mapOf("id" to "hydration-status")) {
                text("Hydration plan pending")
            }
            host("p", props = mapOf("id" to "stream-status")) {
                text("Server stream pending")
            }
        }
    }
}

fun main(args: Array<String>) {
    launchServerComponentsDemo(args)
}

internal fun launchServerComponentsDemo(
    args: Array<String>,
    blockThread: Boolean = true,
    output: (String) -> Unit = ::println,
): ServerComponentsDemoServer? {
    if ("--print" in args) {
        printProtocolDemo()
        return null
    }

    val port = args.firstOrNull { arg -> arg.startsWith("--port=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
        ?: 4180
    val server = ServerComponentsDemoServer(port = port)
    server.start()
    output("Kinetica server-components demo: http://127.0.0.1:${server.boundPort}/")
    output("Build the client first with: ./kotlin build -m server-components-client")
    if (blockThread) {
        Thread.currentThread().join()
    }
    return server
}

internal class ServerComponentsDemoServer(
    private val port: Int,
    private val projectRoot: Path = Path.of("").toAbsolutePath(),
) {
    private val transport = KineticaServerTransport()
    // AtomicInteger so concurrent handler threads (see [executor]) never read-modify-write the
    // cart count under a data race. Shared across all connections — fine for a demo; real apps
    // would key this off the session, not the server.
    private val cartCount = AtomicInteger(0)
    private val dispatcher = KineticaServerActionDispatcher(
        stubs = listOf(
            serverActionStub(
                registration = AddToCartRegistration,
                inputSerializer = AddToCartInput.serializer(),
                outputSerializer = AddToCartResult.serializer(),
            ) { input ->
                input.validationError()?.let { error -> throw ServerActionRejection(error) }
                val updated = cartCount.addAndGet(input.quantity)
                AddToCartResult(
                    message = "Added ${input.quantity} of ${input.productId}",
                    cartCount = updated,
                )
            },
        ),
        verifyCapabilityToken = { token -> token.value == DEMO_CAPABILITY_TOKEN },
        verifyCsrfToken = { token -> token?.value == DEMO_CSRF_TOKEN },
    )
    private val executor = Executors.newFixedThreadPool(SERVER_THREAD_POOL_SIZE)
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

    fun start() {
        http.createContext("/") { exchange -> route(exchange) }
        http.executor = executor
        http.start()
    }

    fun stop() {
        http.stop(0)
        executor.shutdownNow()
    }

    val boundPort: Int
        get() = http.address.port

    private fun route(exchange: HttpExchange) {
        try {
            when {
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/" -> {
                    exchange.respondText(
                        contentType = "text/html",
                        body = renderDocument(),
                    )
                }
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/client.mjs" -> {
                    exchange.respondText(
                        contentType = "application/javascript",
                        body = clientBundle(),
                    )
                }
                exchange.requestMethod == "GET" && staticClientAsset(exchange.requestURI.path) != null -> {
                    val asset = staticClientAsset(exchange.requestURI.path)!!
                    exchange.respondText(
                        contentType = contentTypeFor(asset),
                        body = asset.readText(),
                    )
                }
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/stream" -> {
                    exchange.respondText(
                        contentType = "application/x-ndjson",
                        body = renderStreamBody(),
                    )
                }
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/actions/$ADD_TO_CART_ACTION_ID" -> {
                    val body = readBoundedRequestBody(exchange.requestBody)
                    val response = runBlocking { dispatcher.dispatchHttp(transport, body) }
                    exchange.respondText(
                        status = response.status,
                        contentType = "application/json",
                        body = response.body,
                    )
                }
                else -> exchange.respondText(
                    status = 404,
                    contentType = "text/plain",
                    body = "Not found",
                )
            }
        } catch (error: Throwable) {
            // Log the full detail server-side; return only a generic message to the client so
            // stack traces, internal paths, and library versions never reach a response body.
            error.printStackTrace()
            exchange.respondText(
                status = 500,
                contentType = "text/plain",
                body = "Internal server error.",
            )
        }
    }

    private fun renderDocument(): String {
        val tree = renderProductTree()
        val hydrationPlan = transport.encodeHydrationPlan(tree.hydrationPlan())
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Kinetica Server Components Demo</title>
              <style>
                :root {
                  font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  background: #f5f7fb;
                  color: #171a1f;
                }
                body {
                  margin: 0;
                  min-height: 100vh;
                  display: grid;
                  place-items: start center;
                  padding: 32px 16px;
                }
                .page-shell {
                  width: min(880px, 100%);
                  display: grid;
                  gap: 16px;
                }
                .hero,
                .product,
                .recommendations,
                .runtime-log,
                [data-client-island-root] {
                  display: block;
                  background: #ffffff;
                  border: 1px solid #d8dee8;
                  border-radius: 8px;
                  padding: 20px;
                  box-shadow: 0 18px 60px rgba(31, 39, 51, 0.10);
                }
                h1 {
                  margin: 0 0 8px;
                  font-size: 28px;
                  line-height: 1.2;
                }
                p {
                  margin: 8px 0 0;
                  color: #4b5565;
                }
                button {
                  min-height: 38px;
                  border: 1px solid #adb7c8;
                  border-radius: 6px;
                  background: #ffffff;
                  color: #171a1f;
                  font: inherit;
                  cursor: pointer;
                  padding: 0 12px;
                }
                button:hover:not(:disabled) {
                  background: #eef3fb;
                  border-color: #6d8fca;
                }
                button:disabled {
                  cursor: not-allowed;
                  opacity: 0.45;
                }
              </style>
            </head>
            <body>
              ${tree.toSafeHtml()}
              <script id="kinetica-hydration-plan" type="application/json">${hydrationPlan.escapeScriptJson()}</script>
              <script type="module" src="/client.mjs"></script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderProductTree() =
        KineticaRuntime().render {
            ProductPage()
        }.tree

    private fun renderStreamBody(): String =
        runBlocking {
            renderProductTree()
                .toServerRenderStream(
                    subtrees = listOf(
                        ServerRenderDeferredSubtree(path = listOf(3)) {
                            delay(100)
                            FragmentNode(
                                children = listOf(
                                    TextNode("Deferred recommendations loaded on the server"),
                                    TextNode("Recommended next: Kinetica forms and routing"),
                                ),
                            )
                        },
                    ),
                    manifest = DemoManifest,
                )
                .joinToString(separator = "\n", postfix = "\n") { chunk ->
                    transport.encodeChunk(chunk)
                }
        }

    private fun clientBundle(): String {
        val bundle = clientBundleRoot().resolve("server-components-client.mjs")
        require(bundle.exists()) {
            "Missing Kotlin/JS client bundle at $bundle. Run ./kotlin build -m server-components-client first."
        }
        return bundle.readText()
    }

    private fun staticClientAsset(requestPath: String): Path? {
        val root = clientBundleRoot()
        val relative = requestPath.removePrefix("/")
        val candidate = root.resolve(relative).normalize()
        if (!candidate.startsWith(root) || !candidate.exists() || Files.isDirectory(candidate)) {
            return null
        }
        return candidate
    }

    private fun clientBundleRoot(): Path =
        projectRoot.resolve("build/tasks/_server-components-client_linkJs").normalize()

    internal companion object {
        const val SERVER_THREAD_POOL_SIZE = 8
    }
}

private fun printProtocolDemo() {
    val transport = KineticaServerTransport()
    val tree = KineticaRuntime().render { ProductPage() }.tree
    println("HTML")
    println(tree.toSafeHtml())
    println()
    println("Hydration plan")
    println(transport.encodeHydrationPlan(tree.hydrationPlan()))
    println()
    println("Render stream")
    runBlocking {
        tree.toServerRenderStream(manifest = DemoManifest)
            .forEach { chunk -> println(transport.encodeChunk(chunk)) }
    }
}

private val AddToCartRegistration = ServerActionRegistration(
    actionId = ADD_TO_CART_ACTION_ID,
    functionFqName = "app.servercomponents.addToCart",
    invalidates = listOf("cart"),
)

private val DemoManifest = ClientComponentManifest(
    components = listOf(
        ClientComponentRegistration(
            componentId = ADD_TO_CART_COMPONENT_ID,
            componentFqName = "app.servercomponents.client.AddToCartButton",
            serializablePropsType = AddToCartInput::class.qualifiedName,
        ),
    ),
    actions = listOf(AddToCartRegistration),
)

private fun HttpExchange.respondText(
    status: Int = 200,
    contentType: String,
    body: String,
) {
    val bytes = body.encodeToByteArray()
    responseHeaders.set("Content-Type", "$contentType; charset=utf-8")
    responseHeaders.set("Cache-Control", "no-store")
    applySecurityHeaders()
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { output -> output.write(bytes) }
}

private fun HttpExchange.applySecurityHeaders() {
    // One hardened baseline shared with the runtime (and the docs server) so the header set and CSP
    // can't drift per server; see KineticaSecurityHeaders for the directives and the style-src note.
    KineticaSecurityHeaders.forEach { (name, value) -> responseHeaders.set(name, value) }
}

private fun String.escapeScriptJson(): String =
    replace("</", "<\\/")

private fun contentTypeFor(path: Path): String =
    when (path.fileName.toString().substringAfterLast('.', missingDelimiterValue = "")) {
        "mjs", "js" -> "application/javascript"
        "json", "map" -> "application/json"
        else -> "text/plain"
    }
