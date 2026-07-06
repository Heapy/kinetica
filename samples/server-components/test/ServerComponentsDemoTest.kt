package app.servercomponents

import app.servercomponents.shared.ADD_TO_CART_ACTION_ID
import app.servercomponents.shared.ADD_TO_CART_COMPONENT_ID
import app.servercomponents.shared.AddToCartInput
import app.servercomponents.shared.AddToCartResult
import app.servercomponents.shared.CART_QUANTITY_RANGE
import app.servercomponents.shared.DEMO_CAPABILITY_TOKEN
import app.servercomponents.shared.DEMO_CSRF_TOKEN
import io.heapy.kinetica.CapabilityToken
import io.heapy.kinetica.ClientRef
import io.heapy.kinetica.CsrfToken
import io.heapy.kinetica.KineticaJson
import io.heapy.kinetica.KineticaServerTransport
import io.heapy.kinetica.ServerActionRequest
import io.heapy.kinetica.ServerActionResponse
import io.heapy.kinetica.ServerRenderChunk
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.collectClientIslands
import io.heapy.kinetica.hydrationPlan
import io.heapy.kinetica.materialize
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerComponentsDemoTest {
    private val transport = KineticaServerTransport()

    @Test
    fun productPageProducesClientIslandAndHydrationPlan() {
        val tree = io.heapy.kinetica.KineticaRuntime().render {
            ProductPage()
        }.tree
        val islands = tree.collectClientIslands()
        val plan = tree.hydrationPlan()

        assertEquals(1, islands.size)
        assertEquals(ADD_TO_CART_COMPONENT_ID, islands.single().componentId)
        assertEquals(listOf(2), islands.single().path)
        assertEquals(JsonPrimitive("runtime-license"), islands.single().props["productId"])
        assertEquals(plan.clientIslands, islands)
        assertIs<ClientRef>(plan.initialTree.findPath(listOf(2)))
    }

    @Test
    fun httpServerServesSsrHtmlClientAssetsAndStreamChunks() = withDemoServer { baseUrl ->
        val html = get("$baseUrl/")
        assertTrue("Server-rendered product page" in html)
        assertTrue("data-kinetica-client-ref=\"$ADD_TO_CART_COMPONENT_ID\"" in html)
        assertTrue("id=\"kinetica-hydration-plan\"" in html)
        assertTrue("src=\"/client.mjs\"" in html)

        val clientEntry = get("$baseUrl/client.mjs")
        assertEquals("export const marker = 'entry';", clientEntry.trim())
        val clientAsset = get("$baseUrl/kotlin_server_components_client/app/servercomponents/client/main.mjs")
        assertEquals("export const marker = 'asset';", clientAsset.trim())

        val chunks = get("$baseUrl/stream")
            .lineSequence()
            .filter { line -> line.isNotBlank() }
            .map(transport::decodeChunk)
            .toList()
        assertIs<ServerRenderChunk.Tree>(chunks[0])
        val patch = assertIs<ServerRenderChunk.Patch>(chunks[1])
        assertEquals(listOf(3), patch.path)
        assertEquals(
            listOf(
                "Deferred recommendations loaded on the server",
                "Recommended next: Kinetica forms and routing",
            ),
            patch.node!!.flattenText(),
        )
        assertIs<ServerRenderChunk.End>(chunks[2])
    }

    @Test
    fun httpServerCoversErrorsContentTypesAndStaticAssetSafety() = withDemoServer { baseUrl ->
        val json = getResponse("$baseUrl/kotlin_server_components_client/app/servercomponents/client/metadata.json")
        assertEquals(200, json.statusCode())
        assertTrue(json.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
        assertEquals("""{"marker":true}""", json.body().trim())

        val sourceMap = getResponse("$baseUrl/kotlin_server_components_client/app/servercomponents/client/main.mjs.map")
        assertEquals(200, sourceMap.statusCode())
        assertTrue(sourceMap.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))

        val textAsset = getResponse("$baseUrl/kotlin_server_components_client/app/servercomponents/client/readme.txt")
        assertEquals(200, textAsset.statusCode())
        assertTrue(textAsset.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"))
        assertEquals("plain asset", textAsset.body().trim())

        val unknown = getResponse("$baseUrl/missing")
        assertEquals(404, unknown.statusCode())
        assertEquals("Not found", unknown.body())

        val directory = getResponse("$baseUrl/kotlin_server_components_client/app/servercomponents/client")
        assertEquals(404, directory.statusCode())

        val escaped = getResponse("$baseUrl/../server-components-client.mjs")
        assertEquals(404, escaped.statusCode())

        val malformedAction = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/actions/$ADD_TO_CART_ACTION_ID"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        // Malformed/missing-field request bodies are a client error (400) with a generic message;
        // the body must not leak serializer internals or field names.
        assertEquals(400, malformedAction.statusCode())
        val failure = transport.decodeActionResponse(malformedAction.body())
        assertEquals(
            "Malformed server action request.",
            assertIs<ServerActionResponse.Failure>(failure).message,
        )
    }

    @Test
    fun httpServerDispatchesTypedActionsAndRejectsInvalidTokens() = withDemoServer { baseUrl ->
        val first = postAction(
            baseUrl = baseUrl,
            request = ServerActionRequest(
                actionId = ADD_TO_CART_ACTION_ID,
                payload = KineticaJson.encodeToJsonElement(AddToCartInput("runtime-license", 2)),
                token = CapabilityToken(DEMO_CAPABILITY_TOKEN),
                csrfToken = CsrfToken(DEMO_CSRF_TOKEN),
            ),
        )
        val firstResult = first.successResult()
        assertEquals("Added 2 of runtime-license", firstResult.message)
        assertEquals(2, firstResult.cartCount)

        val second = postAction(
            baseUrl = baseUrl,
            request = ServerActionRequest(
                actionId = ADD_TO_CART_ACTION_ID,
                payload = KineticaJson.encodeToJsonElement(AddToCartInput("runtime-license", 3)),
                token = CapabilityToken(DEMO_CAPABILITY_TOKEN),
                csrfToken = CsrfToken(DEMO_CSRF_TOKEN),
            ),
        )
        assertEquals(5, second.successResult().cartCount)

        val denied = postAction(
            baseUrl = baseUrl,
            request = ServerActionRequest(
                actionId = ADD_TO_CART_ACTION_ID,
                payload = KineticaJson.encodeToJsonElement(AddToCartInput("runtime-license", 1)),
                token = CapabilityToken("bad-token"),
                csrfToken = CsrfToken(DEMO_CSRF_TOKEN),
            ),
        )
        assertEquals("Invalid capability token.", assertIs<ServerActionResponse.Failure>(denied).message)

        val csrfDenied = postAction(
            baseUrl = baseUrl,
            request = ServerActionRequest(
                actionId = ADD_TO_CART_ACTION_ID,
                payload = KineticaJson.encodeToJsonElement(AddToCartInput("runtime-license", 1)),
                token = CapabilityToken(DEMO_CAPABILITY_TOKEN),
                csrfToken = CsrfToken("bad-csrf"),
            ),
        )
        assertEquals("Invalid CSRF token.", assertIs<ServerActionResponse.Failure>(csrfDenied).message)
    }

    @Test
    fun httpServerRejectsOutOfRangeQuantitiesAndOversizedBodies() = withDemoServer { baseUrl ->
        // The schema accepts any number; the handler must reject out-of-range business values with
        // a typed Failure (ServerActionRejection) rather than mutating state.
        val negative = postAction(
            baseUrl = baseUrl,
            request = ServerActionRequest(
                actionId = ADD_TO_CART_ACTION_ID,
                payload = KineticaJson.encodeToJsonElement(AddToCartInput("runtime-license", -1)),
                token = CapabilityToken(DEMO_CAPABILITY_TOKEN),
                csrfToken = CsrfToken(DEMO_CSRF_TOKEN),
            ),
        )
        assertEquals(
            "quantity must be in $CART_QUANTITY_RANGE.",
            assertIs<ServerActionResponse.Failure>(negative).message,
        )

        val tooLarge = postAction(
            baseUrl = baseUrl,
            request = ServerActionRequest(
                actionId = ADD_TO_CART_ACTION_ID,
                payload = KineticaJson.encodeToJsonElement(AddToCartInput("runtime-license", 10_000)),
                token = CapabilityToken(DEMO_CAPABILITY_TOKEN),
                csrfToken = CsrfToken(DEMO_CSRF_TOKEN),
            ),
        )
        assertEquals(
            "quantity must be in $CART_QUANTITY_RANGE.",
            assertIs<ServerActionResponse.Failure>(tooLarge).message,
        )

        // A valid quantity still succeeds and is unaffected by the prior rejections.
        val ok = postAction(
            baseUrl = baseUrl,
            request = ServerActionRequest(
                actionId = ADD_TO_CART_ACTION_ID,
                payload = KineticaJson.encodeToJsonElement(AddToCartInput("runtime-license", 1)),
                token = CapabilityToken(DEMO_CAPABILITY_TOKEN),
                csrfToken = CsrfToken(DEMO_CSRF_TOKEN),
            ),
        ).successResult()
        assertEquals(1, ok.cartCount)

        // An oversized body is rejected with 413 before it can exhaust heap.
        val oversized = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/actions/$ADD_TO_CART_ACTION_ID"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("a".repeat(ServerComponentsDemoServer.MAX_ACTION_BODY_BYTES + 256)))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(413, oversized.statusCode())
        assertIs<ServerActionResponse.Failure>(transport.decodeActionResponse(oversized.body()))
    }

    @Test
    fun httpServerReportsMissingClientBundleAsServerError() = withDemoServer(createClientBundle = false) { baseUrl ->
        val response = getResponse("$baseUrl/client.mjs")

        assertEquals(500, response.statusCode())
        // The misconfiguration detail is logged server-side, not leaked in the response body.
        assertEquals("Internal server error.", response.body())
    }

    @Test
    fun cliLauncherStartsServerOnEphemeralPortAndReportsBoundUrl() {
        val messages = mutableListOf<String>()
        val server = launchServerComponentsDemo(
            args = arrayOf("--port=0"),
            blockThread = false,
            output = messages::add,
        ) ?: error("Expected server launcher to return a running server.")

        try {
            val baseUrl = "http://127.0.0.1:${server.boundPort}"

            assertTrue(server.boundPort > 0)
            assertEquals(
                "Kinetica server-components demo: $baseUrl/",
                messages.first(),
            )
            assertEquals(
                "Build the client first with: ./kotlin build -m server-components-client",
                messages[1],
            )
            assertTrue("Server-rendered product page" in get("$baseUrl/"))
        } finally {
            server.stop()
        }
    }

    private fun withDemoServer(block: (String) -> Unit) {
        withDemoServer(createClientBundle = true, block = block)
    }

    private fun withDemoServer(
        createClientBundle: Boolean,
        block: (String) -> Unit,
    ) {
        val root = createTempDirectory(prefix = "kinetica-server-components-test")
        val bundleRoot = root.resolve("build/tasks/_server-components-client_linkJs")
        if (createClientBundle) {
            bundleRoot.createDirectories()
            bundleRoot.resolve("server-components-client.mjs").writeText("export const marker = 'entry';")
            val nested = bundleRoot.resolve("kotlin_server_components_client/app/servercomponents/client")
            nested.createDirectories()
            nested.resolve("main.mjs").writeText("export const marker = 'asset';")
            nested.resolve("metadata.json").writeText("""{"marker":true}""")
            nested.resolve("main.mjs.map").writeText("""{"version":3}""")
            nested.resolve("readme.txt").writeText("plain asset")
        }

        val server = ServerComponentsDemoServer(port = 0, projectRoot = root)
        server.start()
        try {
            block("http://127.0.0.1:${server.boundPort}")
        } finally {
            server.stop()
        }
    }

    private fun get(url: String): String {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), response.body())
        return response.body()
    }

    private fun getResponse(url: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun postAction(
        baseUrl: String,
        request: ServerActionRequest,
    ): ServerActionResponse {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/actions/$ADD_TO_CART_ACTION_ID"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(transport.encodeActionRequest(request)))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), response.body())
        return transport.decodeActionResponse(response.body())
    }

    private fun ServerActionResponse.successResult(): AddToCartResult =
        KineticaJson.decodeFromJsonElement(
            AddToCartResult.serializer(),
            assertIs<ServerActionResponse.Success>(this).payload,
        )

    private companion object {
        val http: HttpClient = HttpClient.newHttpClient()
    }
}

private fun io.heapy.kinetica.Node.findPath(path: List<Int>): io.heapy.kinetica.Node {
    var current = this
    path.forEach { index ->
        current = when (current) {
            is io.heapy.kinetica.FragmentNode -> current.children[index]
            is io.heapy.kinetica.HostNode -> current.children[index]
            is io.heapy.kinetica.TemplateNode -> current.materialize().children[index]
            is ClientRef,
            is TextNode,
            -> error("Node has no children at path $path")
        }
    }
    return current
}

private fun io.heapy.kinetica.Node.flattenText(): List<String> =
    when (this) {
        is io.heapy.kinetica.FragmentNode -> children.flatMap { child -> child.flattenText() }
        is io.heapy.kinetica.HostNode -> children.flatMap { child -> child.flattenText() }
        is io.heapy.kinetica.TemplateNode -> materialize().flattenText()
        is TextNode -> listOf(value)
        is ClientRef -> emptyList()
    }
