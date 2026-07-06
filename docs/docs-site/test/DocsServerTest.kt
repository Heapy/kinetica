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
        // Hardened: the 404 body is generic and must not leak the requested path or the bundles
        // dir (those are logged server-side only).
        assertTrue("Bundle asset not found." in missingAsset.body())
        assertTrue("missing.mjs" !in missingAsset.body())

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
        // Hardened: a malformed action body is a client error (400) with a generic message, not a
        // 500 that could bubble internal detail through the catch-all.
        assertEquals(400, malformedAction.statusCode())
        val malformedFailure = transport.decodeActionResponse(malformedAction.body())
        assertEquals(
            "Malformed server action request.",
            assertIs<ServerActionResponse.Failure>(malformedFailure).message,
        )

        val favicon = getResponse("$baseUrl/favicon.ico")
        assertEquals(204, favicon.statusCode())
        assertEquals("no-store", favicon.headers().firstValue("Cache-Control").orElse(""))
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

        // duplicates are ignored, case-insensitively
        val duplicate = postDemoStack(baseUrl, cookie, """{"language": "rust"}""")
        assertEquals(200, duplicate.statusCode())
        assertEquals("""{"languages":["Kotlin","Rust"]}""", duplicate.body())

        val java = postDemoStack(baseUrl, cookie, """{"language": "Java"}""")
        assertEquals(500, java.statusCode())
        assertTrue("java.lang.NullPointerException" in java.body())

        val javascript = postDemoStack(baseUrl, cookie, """{"language": "JavaScript"}""")
        assertEquals(500, javascript.statusCode())
        assertTrue("undefined is not a function" in javascript.body())

        // failed submissions never touch the stack
        val afterFailures = getDemoStack(baseUrl, cookie)
        assertEquals("""{"languages":["Kotlin","Rust"]}""", afterFailures.body())

        val blank = postDemoStack(baseUrl, cookie, """{"language": "  "}""")
        assertEquals(400, blank.statusCode())
        val malformed = postDemoStack(baseUrl, cookie, "not json")
        assertEquals(400, malformed.statusCode())
        val oversized = postDemoStack(baseUrl, cookie, """{"language": "${"x".repeat(41)}"}""")
        assertEquals(400, oversized.statusCode())

        // another visitor (no cookie) gets an isolated session
        val stranger = getResponse("$baseUrl/demo/api/stack")
        assertEquals("""{"languages":["Kotlin"]}""", stranger.body())

        // an unknown cookie is replaced with a fresh session
        val expired = getDemoStack(baseUrl, "kinetica-demo-session=no-such-session")
        assertEquals(200, expired.statusCode())
        assertTrue(expired.headers().firstValue("Set-Cookie").isPresent)
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
