package docs.site

import app.servercomponents.shared.ADD_TO_CART_ACTION_ID
import app.servercomponents.shared.ADD_TO_CART_COMPONENT_ID
import app.servercomponents.shared.AddToCartInput
import app.servercomponents.shared.AddToCartResult
import app.servercomponents.shared.DEMO_CAPABILITY_TOKEN
import app.servercomponents.shared.DEMO_CSRF_TOKEN
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.heapy.kinetica.ClientComponentManifest
import io.heapy.kinetica.ClientComponentRegistration
import io.heapy.kinetica.ClientRef
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.KineticaServerActionDispatcher
import io.heapy.kinetica.KineticaServerTransport
import io.heapy.kinetica.ServerActionRegistration
import io.heapy.kinetica.ServerRenderDeferredSubtree
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.host
import io.heapy.kinetica.hydrationPlan
import io.heapy.kinetica.serverActionStub
import io.heapy.kinetica.text
import io.heapy.kinetica.toSafeHtml
import io.heapy.kinetica.toServerRenderStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

fun main(args: Array<String>) {
    val port = args.firstOrNull { it.startsWith("--port=") }?.substringAfter("=")?.toIntOrNull()
        ?: System.getenv("PORT")?.toIntOrNull()
        ?: 8080
    val bundlesDir = System.getenv("KINETICA_BUNDLES_DIR")?.let(Path::of)
        ?: Path.of("build/tasks").toAbsolutePath()
    val server = DocsServer(port, bundlesDir)
    server.start()
    println("Kinetica docs: http://0.0.0.0:${server.boundPort}/ (bundles: $bundlesDir)")
    Thread.currentThread().join()
}

class DocsServer(
    port: Int,
    private val bundlesDir: Path,
) {
    private val transport = KineticaServerTransport()
    private var cartCount = 0
    private val dispatcher = KineticaServerActionDispatcher(
        stubs = listOf(
            serverActionStub(
                registration = AddToCartRegistration,
                inputSerializer = AddToCartInput.serializer(),
                outputSerializer = AddToCartResult.serializer(),
            ) { input ->
                cartCount += input.quantity
                AddToCartResult(
                    message = "Added ${input.quantity} of ${input.productId}",
                    cartCount = cartCount,
                )
            },
        ),
        verifyCapabilityToken = { token -> token.value == DEMO_CAPABILITY_TOKEN },
        verifyCsrfToken = { token -> token?.value == DEMO_CSRF_TOKEN },
    )
    private val http = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)

    fun start() {
        http.createContext("/") { exchange -> route(exchange) }
        http.executor = null
        http.start()
    }

    fun stop(): Unit = http.stop(0)

    val boundPort: Int get() = http.address.port

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        try {
            when {
                exchange.requestMethod == "GET" && (path == "/" || path == "/index.html") ->
                    respondDocPage(exchange, "index")

                exchange.requestMethod == "GET" && path.startsWith("/docs/") ->
                    respondDocPage(exchange, path.removePrefix("/docs/").trimEnd('/'))

                exchange.requestMethod == "GET" && path == "/assets/site.css" ->
                    exchange.respondText(contentType = "text/css", body = siteCss())

                exchange.requestMethod == "GET" && path == "/healthz" ->
                    exchange.respondText(contentType = "text/plain", body = "ok")

                exchange.requestMethod == "GET" && path.startsWith("/docs-client/") ->
                    respondBundleAsset(exchange, "_docs-client_linkJs", path.removePrefix("/docs-client/"))

                exchange.requestMethod == "GET" && path.startsWith("/sc-client/") ->
                    respondBundleAsset(exchange, "_server-components-client_linkJs", path.removePrefix("/sc-client/"))

                exchange.requestMethod == "GET" && path == "/examples/server-components" ->
                    exchange.respondText(contentType = "text/html", body = renderDemoDocument())

                exchange.requestMethod == "GET" && path == "/favicon.ico" -> {
                    exchange.responseHeaders.set("Cache-Control", "no-store")
                    exchange.sendResponseHeaders(204, -1)
                    exchange.responseBody.close()
                }

                exchange.requestMethod == "GET" && path == "/stream" ->
                    exchange.respondText(contentType = "application/x-ndjson", body = renderStreamBody())

                exchange.requestMethod == "POST" && path == "/actions/$ADD_TO_CART_ACTION_ID" ->
                    exchange.respondText(
                        contentType = "application/json",
                        body = dispatchAction(exchange.requestBody.readAllBytes().decodeToString()),
                    )

                else -> exchange.respondText(status = 404, contentType = "text/plain", body = "Not found: $path")
            }
        } catch (error: Throwable) {
            exchange.respondText(status = 500, contentType = "text/plain", body = error.stackTraceToString())
        }
    }

    private fun respondDocPage(exchange: HttpExchange, slug: String) {
        val page = pageBySlug(slug)
        val source = page?.let { loadPageSource(it.slug) }
        if (page == null || source == null) {
            exchange.respondText(status = 404, contentType = "text/plain", body = "No such page: $slug")
            return
        }
        exchange.respondText(contentType = "text/html", body = renderDocPage(page, source))
    }

    // --- the server-components demo, hosted inside the docs site ---

    private fun ComponentScope.DemoPage() {
        host("main", props = mapOf("class" to "page-shell")) {
            host("header", props = mapOf("class" to "hero")) {
                text("Server-rendered product page")
                host("p") {
                    text("The product details below are rendered on the JVM. The add-to-cart control is a client island.")
                }
                host("p") {
                    host("a", props = mapOf("href" to "/docs/server-components")) {
                        text("Back to the server-components docs")
                    }
                }
            }
            host("section", props = mapOf("class" to "product", "aria-label" to "Product details")) {
                host("h1") { text("Kinetica runtime license") }
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
                props = mapOf("id" to "recommendations", "class" to "recommendations", "aria-live" to "polite"),
            ) {
                text("Waiting for streamed recommendations...")
            }
            host("section", props = mapOf("class" to "runtime-log")) {
                host("p", props = mapOf("id" to "hydration-status")) { text("Hydration plan pending") }
                host("p", props = mapOf("id" to "stream-status")) { text("Server stream pending") }
            }
        }
    }

    private fun renderDemoTree() = KineticaRuntime().render { DemoPage() }.tree

    private fun renderDemoDocument(): String {
        val tree = renderDemoTree()
        val hydrationPlan = transport.encodeHydrationPlan(tree.hydrationPlan())
        val body = buildString {
            append(tree.toSafeHtml())
            append("\n<script id=\"kinetica-hydration-plan\" type=\"application/json\">")
            append(hydrationPlan.replace("</", "<\\/"))
            append("</script>")
            append("\n<script type=\"module\" src=\"/sc-client/server-components-client.mjs\"></script>")
        }
        return documentShell(title = "Server components demo · Kinetica", body = body, liveExamples = false)
    }

    private fun renderStreamBody(): String = runBlocking {
        renderDemoTree()
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
            .joinToString(separator = "\n", postfix = "\n") { chunk -> transport.encodeChunk(chunk) }
    }

    private fun dispatchAction(body: String): String = runBlocking {
        val request = transport.decodeActionRequest(body)
        transport.encodeActionResponse(dispatcher.dispatch(request))
    }

    // --- static assets ---

    private fun siteCss(): String =
        DocsServer::class.java.getResourceAsStream("/site.css")?.readAllBytes()?.decodeToString()
            ?: "/* missing site.css */"

    /**
     * Each client bundle is an ES-module graph served under its own URL prefix, so relative
     * imports resolve within their own graph — never into another bundle's same-named files.
     */
    private fun respondBundleAsset(exchange: HttpExchange, bundleRoot: String, relative: String) {
        val rootPath = bundlesDir.resolve(bundleRoot).normalize()
        val candidate = rootPath.resolve(relative).normalize()
        if (!candidate.startsWith(rootPath) || !candidate.exists() || Files.isDirectory(candidate)) {
            exchange.respondText(
                status = 404,
                contentType = "text/plain",
                body = "Missing bundle asset $relative under $rootPath. Build with ./kotlin build, or set KINETICA_BUNDLES_DIR.",
            )
            return
        }
        exchange.respondText(contentType = contentTypeFor(candidate), body = candidate.readText())
    }
}

private val AddToCartRegistration = ServerActionRegistration(
    actionId = ADD_TO_CART_ACTION_ID,
    functionFqName = "docs.site.addToCart",
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
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { output -> output.write(bytes) }
}

private fun contentTypeFor(path: Path): String =
    when (path.fileName.toString().substringAfterLast('.', missingDelimiterValue = "")) {
        "mjs", "js" -> "application/javascript"
        "json", "map" -> "application/json"
        "css" -> "text/css"
        else -> "text/plain"
    }
