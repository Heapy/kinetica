package docs.site

import app.servercomponents.shared.ADD_TO_CART_ACTION_ID
import app.servercomponents.shared.AddToCartInput
import app.servercomponents.shared.AddToCartResult
import app.servercomponents.shared.DEMO_CAPABILITY_TOKEN
import app.servercomponents.shared.DEMO_CSRF_TOKEN
import io.heapy.kinetica.CapabilityToken
import io.heapy.kinetica.CsrfToken
import io.heapy.kinetica.KineticaJson
import io.heapy.kinetica.KineticaServerTransport
import io.heapy.kinetica.ServerActionRequest
import io.heapy.kinetica.ServerActionResponse
import io.heapy.kinetica.ServerRenderChunk
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.net.URI
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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

class DocsServerTest {
    private val http = HttpClient.newHttpClient()
    private val transport = KineticaServerTransport()

    @Test
    fun docsServerServesPagesAssetsStreamAndActions() = withDocsServer { baseUrl ->
        val home = get("$baseUrl/")
        assertTrue("Kinetica" in home)
        assertTrue("/docs-client/docs-client.mjs?hash=" in home)
        assertTrue("/assets/site.css?hash=" in home)

        val statePage = get("$baseUrl/docs/state")
        assertTrue("<h1" in statePage)
        assertTrue("data-example=\"counter\"" in statePage)

        val motionPage = get("$baseUrl/docs/motion")
        assertTrue("data-example=\"motion-toggle\"" in motionPage)

        val missing = getResponse("$baseUrl/docs/no-such-page")
        assertEquals(404, missing.statusCode())
        assertTrue("No such page" in missing.body())

        val cssPath = requireNotNull(
            Regex("""href="([^"]*/assets/site\.css\?hash=[0-9a-f]+)"""").find(home),
        ).groupValues[1]
        val css = getBytes(baseUrl, cssPath, headers = mapOf("Accept-Encoding" to "br"))
        assertEquals(200, css.statusCode())
        assertEquals("br", css.headers().firstValue("Content-Encoding").orElse(""))
        assertEquals("br-css", css.body().decodeToString())
        assertTrue(css.headers().firstValue("Cache-Control").orElse("").contains("immutable"))

        val etag = css.headers().firstValue("ETag").orElseThrow()
        val cached = getBytes(baseUrl, cssPath, headers = mapOf("If-None-Match" to etag))
        assertEquals(304, cached.statusCode())
        assertSecurityHeaders(cached)
        val wildcardCached = getBytes(baseUrl, cssPath, headers = mapOf("If-None-Match" to "*"))
        assertEquals(304, wildcardCached.statusCode())

        val unhashedCss = getBytes("$baseUrl/assets/site.css")
        assertEquals(200, unhashedCss.statusCode())
        assertEquals("body { color: black; }", unhashedCss.body().decodeToString())
        assertEquals("no-cache", unhashedCss.headers().firstValue("Cache-Control").orElse(""))

        val clientPath = requireNotNull(
            Regex("""src="([^"]*/docs-client/docs-client\.mjs\?hash=[0-9a-f]+)"""").find(home),
        ).groupValues[1]
        val client = get(resolve(baseUrl, clientPath).toString())
        assertTrue("docs client marker" in client)
        assertTrue("fallback marker" in get("$baseUrl/docs-client/fallback.mjs"))
        assertTrue(getResponse("$baseUrl/docs-client/metadata.json").headers().firstValue("Content-Type").orElse("")
            .startsWith("application/json"))
        assertTrue(getResponse("$baseUrl/docs-client/main.mjs.map").headers().firstValue("Content-Type").orElse("")
            .startsWith("application/json"))
        assertTrue(getResponse("$baseUrl/docs-client/readme.txt").headers().firstValue("Content-Type").orElse("")
            .startsWith("text/plain"))
        val missingAsset = getResponse("$baseUrl/docs-client/missing.mjs")
        assertEquals(404, missingAsset.statusCode())
        assertEquals("Asset not found.", missingAsset.body())

        val gameOfLifePage = get("$baseUrl/docs/game-of-life")
        assertTrue("Game of Life, four ways" in gameOfLifePage)
        assertTrue("/game-of-life/compose-html/" in gameOfLifePage)
        assertTrue("benchmark report" in get("$baseUrl/game-of-life/"))
        assertTrue("react marker" in get("$baseUrl/game-of-life/react/main.js"))
        assertTrue(getResponse("$baseUrl/game-of-life/results.json").headers()
            .firstValue("Content-Type").orElse("").startsWith("application/json"))
        val missingGame = getResponse("$baseUrl/game-of-life/no-such-app/")
        assertEquals(404, missingGame.statusCode())
        assertEquals("Asset not found.", missingGame.body())

        val demo = get("$baseUrl/examples/server-components")
        assertTrue("id=\"kinetica-hydration-plan\"" in demo)
        assertTrue("/sc-client/server-components-client.mjs?hash=" in demo)
        assertTrue("server components marker" in get("$baseUrl/sc-client/server-components-client.mjs"))

        val chunks = get("$baseUrl/stream")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map(transport::decodeChunk)
            .toList()
        assertIs<ServerRenderChunk.Tree>(chunks[0])
        assertIs<ServerRenderChunk.Patch>(chunks[1])
        assertIs<ServerRenderChunk.End>(chunks[2])

        val action = postAction(
            baseUrl = baseUrl,
            request = ServerActionRequest(
                actionId = ADD_TO_CART_ACTION_ID,
                payload = KineticaJson.encodeToJsonElement(AddToCartInput("runtime-license", 2)),
                token = CapabilityToken(DEMO_CAPABILITY_TOKEN),
                csrfToken = CsrfToken(DEMO_CSRF_TOKEN),
            ),
        )
        val result = KineticaJson.decodeFromJsonElement<AddToCartResult>(
            assertIs<ServerActionResponse.Success>(action).payload,
        )
        assertEquals("Added 2 of runtime-license", result.message)
        assertEquals(2, result.cartCount)

        val invalid = postAction(
            baseUrl = baseUrl,
            request = ServerActionRequest(
                actionId = ADD_TO_CART_ACTION_ID,
                payload = KineticaJson.encodeToJsonElement(AddToCartInput("runtime-license", 1)),
                token = CapabilityToken("bad"),
                csrfToken = CsrfToken(DEMO_CSRF_TOKEN),
            ),
        )
        assertEquals("Invalid capability token.", assertIs<ServerActionResponse.Failure>(invalid).message)

        val malformedAction = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/actions/$ADD_TO_CART_ACTION_ID"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(400, malformedAction.statusCode())
        assertSecurityHeaders(malformedAction)
        assertEquals(
            ServerActionResponse.Failure("Malformed server action request."),
            transport.decodeActionResponse(malformedAction.body()),
        )
        assertTrue(malformedAction.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
        assertEquals("no-store", malformedAction.headers().firstValue("Cache-Control").orElse(""))

        val favicon = getResponse("$baseUrl/favicon.ico")
        assertEquals(204, favicon.statusCode())
        assertSecurityHeaders(favicon)
        assertEquals("no-store", favicon.headers().firstValue("Cache-Control").orElse(""))
    }

    @Test
    fun incompleteActionBodiesDoNotBlockOtherRequests() = withDocsServer { baseUrl ->
        val uri = URI.create(baseUrl)
        val sockets = mutableListOf<Socket>()
        try {
            val readers = (1..16).map {
                val socket = Socket(uri.host, uri.port)
                sockets.add(socket)
                socket.soTimeout = 5_000
                val output = socket.getOutputStream()
                output.write(
                    (
                        "POST /actions/$ADD_TO_CART_ACTION_ID HTTP/1.1\r\n" +
                            "Host: localhost\r\nContent-Length: 2\r\nExpect: 100-continue\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
                )
                output.flush()
                val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                // Ensure every request is accepted before checking the independent GET.
                assertEquals("HTTP/1.1 100 Continue", input.readLine())
                while (!input.readLine().isNullOrEmpty()) { }
                input
            }

            val response = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/healthz"))
                    .timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode())

            sockets.zip(readers).forEach { (socket, input) ->
                val output = socket.getOutputStream()
                output.write("{}".toByteArray(Charsets.US_ASCII))
                output.flush()
                assertEquals("HTTP/1.1 400 Bad Request", input.readLine())
            }
        } finally {
            sockets.forEach { it.close() }
        }
    }

    @Test
    fun concurrentCartActionsPreserveEveryIncrement() = withDocsServer { baseUrl ->
        val body = transport.encodeActionRequest(ServerActionRequest(
            actionId = ADD_TO_CART_ACTION_ID,
            payload = KineticaJson.encodeToJsonElement(AddToCartInput("sku", 1)),
            token = CapabilityToken(DEMO_CAPABILITY_TOKEN),
            csrfToken = CsrfToken(DEMO_CSRF_TOKEN),
        ))
        val pending = (1..32).map {
            http.sendAsync(
                HttpRequest.newBuilder(URI.create("$baseUrl/actions/$ADD_TO_CART_ACTION_ID"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        CompletableFuture.allOf(*pending.toTypedArray()).get(10, TimeUnit.SECONDS)
        val counts = pending.map { future ->
            val response = future.join()
            assertEquals(200, response.statusCode())
            val success = assertIs<ServerActionResponse.Success>(transport.decodeActionResponse(response.body()))
            KineticaJson.decodeFromJsonElement<AddToCartResult>(success.payload).cartCount
        }
        assertEquals((1..32).toList(), counts.sorted())
    }

    @Test
    fun pagesAssetsAndNotFoundResponsesIncludeSecurityHeaders() = withDocsServer { baseUrl ->
        for ((path, status) in listOf("/" to 200, "/assets/site.css" to 200, "/missing" to 404)) {
            val response = getResponse("$baseUrl$path")
            assertEquals(status, response.statusCode(), path)
            assertSecurityHeaders(response)
        }
    }

    @Test
    fun missingAssetsReturnGenericNotFoundResponses() = withDocsServer { baseUrl ->
        for (path in listOf(
            "/docs-client/missing.mjs",
            "/sc-client/missing.mjs",
            "/bench/missing.json",
            "/game-of-life/no-such-app/",
        )) {
            val response = getResponse("$baseUrl$path")
            assertEquals(404, response.statusCode(), path)
            assertEquals("Asset not found.", response.body(), path)
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"))
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""))
        }
    }

    @Test
    fun demoStackKeepsPerSessionStateAndFailsOnJavaAndJavaScript() = withDocsServer { baseUrl ->
        val fresh = getResponse("$baseUrl/demo/api/stack")
        assertEquals(200, fresh.statusCode())
        assertEquals("""{"languages":["Kotlin"]}""", fresh.body())
        val cookie = fresh.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(";")
        assertTrue(cookie.startsWith("kinetica-demo-session="))

        val added = postDemoStack(baseUrl, cookie, """{"language": "Rust"}""")
        assertEquals(200, added.statusCode())
        assertEquals("""{"languages":["Kotlin","Rust"]}""", added.body())

        val duplicate = postDemoStack(baseUrl, cookie, """{"language": "rust"}""")
        assertEquals(200, duplicate.statusCode())
        assertEquals("""{"languages":["Kotlin","Rust"]}""", duplicate.body())

        val java = postDemoStack(baseUrl, cookie, """{"language": "Java"}""")
        assertEquals(500, java.statusCode())
        assertSecurityHeaders(java)
        assertTrue("java.lang.NullPointerException" in java.body())

        val javascript = postDemoStack(baseUrl, cookie, """{"language": "JavaScript"}""")
        assertEquals(500, javascript.statusCode())
        assertTrue("undefined is not a function" in javascript.body())

        val afterFailures = getDemoStack(baseUrl, cookie)
        assertEquals("""{"languages":["Kotlin","Rust"]}""", afterFailures.body())

        val blank = postDemoStack(baseUrl, cookie, """{"language": "  "}""")
        assertEquals(400, blank.statusCode())
        val malformed = postDemoStack(baseUrl, cookie, "not json")
        assertEquals(400, malformed.statusCode())
        val oversized = postDemoStack(baseUrl, cookie, """{"language": "${"x".repeat(41)}"}""")
        assertEquals(400, oversized.statusCode())

        val stranger = getResponse("$baseUrl/demo/api/stack")
        assertEquals("""{"languages":["Kotlin"]}""", stranger.body())

        val expired = getDemoStack(baseUrl, "kinetica-demo-session=no-such-session")
        assertEquals(200, expired.statusCode())
        assertTrue(expired.headers().firstValue("Set-Cookie").isPresent)
    }

    @Test
    fun demoStackEnforcesBodyLimitInBytes() = withDocsServer { baseUrl ->
        val fresh = getResponse("$baseUrl/demo/api/stack")
        val cookie = fresh.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(";")
        val json = """{"language":"Раст"}"""
        val atLimit = json + " ".repeat(64 * 1024 - json.encodeToByteArray().size)

        for (body in listOf(atLimit.dropLast(1), atLimit)) {
            val accepted = postDemoStack(baseUrl, cookie, body)
            assertEquals(200, accepted.statusCode())
            assertEquals("""{"languages":["Kotlin","Раст"]}""", accepted.body())
        }

        val rejected = postDemoStack(baseUrl, cookie, atLimit.replace("Раст", "Руби") + " ")
        assertEquals(413, rejected.statusCode())
        assertSecurityHeaders(rejected)
        assertEquals("Request body too large.", rejected.body())
        assertEquals("""{"languages":["Kotlin","Раст"]}""", getDemoStack(baseUrl, cookie).body())
    }

    @Test
    fun demoStackRejectsOversizedChunkedBody() = withDocsServer { baseUrl ->
        val fresh = getResponse("$baseUrl/demo/api/stack")
        val cookie = fresh.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(";")
        val json = """{"language":"Rust"}"""
        val body = json.padEnd(64 * 1024 + 1).encodeToByteArray()
        val response = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/demo/api/stack"))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Cookie", cookie)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream { body.inputStream() })
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(413, response.statusCode())
        assertEquals("""{"languages":["Kotlin"]}""", getDemoStack(baseUrl, cookie).body())
    }

    @Test
    fun invalidCartInputsDoNotChangeState() = withDocsServer { baseUrl ->
        fun send(input: AddToCartInput): ServerActionResponse = postAction(
            baseUrl,
            ServerActionRequest(
                actionId = ADD_TO_CART_ACTION_ID,
                payload = KineticaJson.encodeToJsonElement(input),
                token = CapabilityToken(DEMO_CAPABILITY_TOKEN),
                csrfToken = CsrfToken(DEMO_CSRF_TOKEN),
            ),
        )

        for (quantity in listOf(Int.MIN_VALUE, -1, 0, 1000, Int.MAX_VALUE)) {
            assertEquals(
                ServerActionResponse.Failure("quantity must be in 1..999."),
                send(AddToCartInput("sku", quantity)),
            )
        }
        for (id in listOf("", "   ", "x".repeat(129))) {
            assertEquals(
                ServerActionResponse.Failure("productId must be 1..128 characters."),
                send(AddToCartInput(id, 1)),
            )
        }
        for ((quantity, expectedCount) in listOf(1 to 1, 999 to 1000)) {
            val success = assertIs<ServerActionResponse.Success>(send(AddToCartInput("x".repeat(128), quantity)))
            val result = KineticaJson.decodeFromJsonElement<AddToCartResult>(success.payload)
            assertEquals(expectedCount, result.cartCount)
        }
    }

    @Test
    fun actionsEnforceBodyLimitBeforeInvokingHandler() {
        for (chunked in listOf(false, true)) {
            withDocsServer { baseUrl ->
                val request = ServerActionRequest(
                    actionId = ADD_TO_CART_ACTION_ID,
                    payload = KineticaJson.encodeToJsonElement(AddToCartInput("лицензия", 1)),
                    token = CapabilityToken(DEMO_CAPABILITY_TOKEN),
                    csrfToken = CsrfToken(DEMO_CSRF_TOKEN),
                )
                val json = transport.encodeActionRequest(request)
                val atLimit = json + " ".repeat(64 * 1024 - json.encodeToByteArray().size)

                fun send(body: String): HttpResponse<String> {
                    val bytes = body.encodeToByteArray()
                    val publisher = if (chunked) {
                        HttpRequest.BodyPublishers.ofInputStream { bytes.inputStream() }
                    } else {
                        HttpRequest.BodyPublishers.ofByteArray(bytes)
                    }
                    return http.send(
                        HttpRequest.newBuilder(URI.create("$baseUrl/actions/$ADD_TO_CART_ACTION_ID"))
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("Content-Type", "application/json")
                            .POST(publisher)
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                }

                val rejected = send(atLimit + " ")
                assertEquals(413, rejected.statusCode(), "chunked=$chunked")
                assertTrue(rejected.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
                assertEquals(
                    ServerActionResponse.Failure("Request body too large."),
                    transport.decodeActionResponse(rejected.body()),
                )

                for ((index, body) in listOf(atLimit, atLimit.dropLast(1)).withIndex()) {
                    val accepted = send(body)
                    assertEquals(200, accepted.statusCode(), "chunked=$chunked")
                    val success = assertIs<ServerActionResponse.Success>(transport.decodeActionResponse(accepted.body()))
                    val result = KineticaJson.decodeFromJsonElement<AddToCartResult>(success.payload)
                    assertEquals(index + 1, result.cartCount, "oversized requests must not modify the cart")
                }
            }
        }
    }

    @Test
    fun docsServerFallsBackToPackagedCssWhenGeneratedAssetIsMissing() = withDocsServer(
        createGeneratedCss = false,
    ) { baseUrl ->
        val css = getBytes("$baseUrl/assets/site.css")

        assertEquals(200, css.statusCode())
        assertTrue(css.body().decodeToString().contains("body"))
        assertEquals("no-cache", css.headers().firstValue("Cache-Control").orElse(""))
    }

    @Test
    fun documentShellEscapesTitleAndAssetUrls() {
        val html = documentShell(
            title = "A < B & C",
            body = "<main>ok</main>",
            assetUrls = DocsAssetUrls(
                siteCssHref = "/site.css?x=<",
                docsClientScriptSrc = "/docs-client.mjs?x=&",
                serverComponentsClientScriptSrc = "/sc-client.mjs",
            ),
            liveExamples = true,
        )

        assertTrue("<title>A &lt; B &amp; C</title>" in html)
        assertTrue("href=\"/site.css?x=&lt;\"" in html)
        assertTrue("src=\"/docs-client.mjs?x=&amp;\"" in html)
    }

    private fun assertSecurityHeaders(response: HttpResponse<*>) {
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""))
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(""))
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(""))
        assertEquals(
            "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self'; connect-src 'self'; frame-ancestors 'none'",
            response.headers().firstValue("Content-Security-Policy").orElse(""),
        )
    }

    private fun withDocsServer(
        createGeneratedCss: Boolean = true,
        block: (String) -> Unit,
    ) {
        val bundlesDir = createTempDirectory(prefix = "kinetica-docs-test")
        if (createGeneratedCss) {
            bundlesDir.resolve("_docs-site_assets").createDirectories()
            bundlesDir.resolve("_docs-site_assets/site.css").writeText("body { color: black; }")
            bundlesDir.resolve("_docs-site_assets/site.css.br").writeText("br-css")
        }

        bundlesDir.resolve("_docs-client_bundle").createDirectories()
        bundlesDir.resolve("_docs-client_bundle/docs-client.mjs").writeText("export const marker = 'docs client marker';")
        bundlesDir.resolve("_docs-client_bundle/metadata.json").writeText("""{"ok":true}""")
        bundlesDir.resolve("_docs-client_bundle/main.mjs.map").writeText("""{"version":3}""")
        bundlesDir.resolve("_docs-client_bundle/readme.txt").writeText("plain")
        bundlesDir.resolve("_docs-client_linkJs").createDirectories()
        bundlesDir.resolve("_docs-client_linkJs/fallback.mjs").writeText("export const marker = 'fallback marker';")

        bundlesDir.resolve("_server-components-client_bundle").createDirectories()
        bundlesDir.resolve("_server-components-client_bundle/server-components-client.mjs")
            .writeText("export const marker = 'server components marker';")

        bundlesDir.resolve("_game-of-life_dist/react").createDirectories()
        bundlesDir.resolve("_game-of-life_dist/benchmark.html").writeText("benchmark report")
        bundlesDir.resolve("_game-of-life_dist/react/index.html").writeText("react game")
        bundlesDir.resolve("_game-of-life_dist/react/main.js").writeText("react marker")
        bundlesDir.resolve("_game-of-life_dist/results.json").writeText("""{"ok":true}""")

        val server = DocsServer(port = 0, bundlesDir = bundlesDir)
        server.start()
        try {
            block("http://127.0.0.1:${server.boundPort}")
        } finally {
            server.stop()
        }
    }

    private fun get(url: String): String {
        val response = getResponse(url)
        assertEquals(200, response.statusCode(), response.body())
        return response.body()
    }

    private fun getResponse(url: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun getBytes(
        baseUrl: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(resolve(baseUrl, path)).GET()
        for ((name, value) in headers) {
            builder.header(name, value)
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun getBytes(url: String): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
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

    private fun getDemoStack(baseUrl: String, cookie: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/demo/api/stack"))
                .header("Cookie", cookie)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun postDemoStack(
        baseUrl: String,
        cookie: String,
        body: String,
    ): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/demo/api/stack"))
                .header("Cookie", cookie)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun resolve(baseUrl: String, path: String): URI =
        URI.create(baseUrl).resolve(path)
}
