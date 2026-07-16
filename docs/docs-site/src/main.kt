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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.exists

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
    private val assetUrls: DocsAssetUrls by lazy {
        DocsAssetUrls(
            siteCssHref = versionedUrl("/assets/site.css", siteCssAsset()),
            docsClientScriptSrc = versionedUrl(
                "/docs-client/docs-client.mjs",
                bundleAsset("_docs-client_bundle", "_docs-client_linkJs", "docs-client.mjs"),
            ),
            serverComponentsClientScriptSrc = versionedUrl(
                "/sc-client/server-components-client.mjs",
                bundleAsset(
                    "_server-components-client_bundle",
                    "_server-components-client_linkJs",
                    "server-components-client.mjs",
                ),
            ),
        )
    }
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
                    exchange.respondStaticAsset(siteCssAsset())

                exchange.requestMethod == "GET" && path == "/healthz" ->
                    exchange.respondText(contentType = "text/plain", body = "ok")

                exchange.requestMethod == "GET" && path.startsWith("/docs-client/") ->
                    respondBundleAsset(
                        exchange,
                        preferredRoot = "_docs-client_bundle",
                        fallbackRoot = "_docs-client_linkJs",
                        relative = path.removePrefix("/docs-client/"),
                    )

                exchange.requestMethod == "GET" && path.startsWith("/sc-client/") ->
                    respondBundleAsset(
                        exchange,
                        preferredRoot = "_server-components-client_bundle",
                        fallbackRoot = "_server-components-client_linkJs",
                        relative = path.removePrefix("/sc-client/"),
                    )

                exchange.requestMethod == "GET" && path.startsWith("/bench/") ->
                    respondBenchAsset(exchange, path.removePrefix("/bench/"))

                exchange.requestMethod == "GET" && (path == "/game-of-life" || path.startsWith("/game-of-life/")) ->
                    respondGameOfLifeAsset(
                        exchange,
                        path.removePrefix("/game-of-life").removePrefix("/"),
                    )

                exchange.requestMethod == "GET" && path == "/examples/server-components" ->
                    exchange.respondText(contentType = "text/html", body = renderDemoDocument())

                exchange.requestMethod == "GET" && path == "/favicon.ico" -> {
                    exchange.responseHeaders.set("Cache-Control", "no-store")
                    exchange.sendResponseHeaders(204, -1)
                    exchange.responseBody.close()
                }

                exchange.requestMethod == "GET" && path == "/demo/api/stack" ->
                    respondDemoStack(exchange)

                exchange.requestMethod == "POST" && path == "/demo/api/stack" ->
                    respondDemoStackSubmission(exchange)

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
        exchange.respondText(contentType = "text/html", body = renderDocPage(page, source, assetUrls))
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
            append("\n<script type=\"module\" src=\"")
            append(assetUrls.serverComponentsClientScriptSrc.escapeHtml())
            append("\"></script>")
        }
        return documentShell(
            title = "Server components demo · Kinetica",
            body = body,
            assetUrls = assetUrls,
            liveExamples = false,
        )
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

    // --- the data-fetching demo on /docs/resources: one language stack per visitor session ---

    private val demoSessions = object : LinkedHashMap<String, DemoStackSession>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DemoStackSession>): Boolean =
            size > MaxDemoSessions
    }

    private fun respondDemoStack(exchange: HttpExchange) {
        val session = demoSession(exchange)
        exchange.respondText(contentType = "application/json", body = session.toJson())
    }

    private fun respondDemoStackSubmission(exchange: HttpExchange) {
        val session = demoSession(exchange)
        val body = exchange.requestBody.readAllBytes().decodeToString()
        val language = runCatching {
            Json.parseToJsonElement(body).jsonObject["language"]?.jsonPrimitive?.content
        }.getOrNull()?.trim()
        when {
            language.isNullOrEmpty() -> exchange.respondText(
                status = 400,
                contentType = "text/plain",
                body = """Send {"language": "<name>"} with a non-blank name.""",
            )

            language.length > MaxDemoLanguageLength -> exchange.respondText(
                status = 400,
                contentType = "text/plain",
                body = "Language names are capped at $MaxDemoLanguageLength characters.",
            )

            language.equals("java", ignoreCase = true) -> exchange.respondText(
                status = 500,
                contentType = "text/plain",
                body = JavaFailureBody,
            )

            language.equals("javascript", ignoreCase = true) || language.equals("js", ignoreCase = true) ->
                exchange.respondText(
                    status = 500,
                    contentType = "text/plain",
                    body = JavaScriptFailureBody,
                )

            else -> {
                val accepted = synchronized(demoSessions) {
                    when {
                        session.languages.any { it.equals(language, ignoreCase = true) } -> true
                        session.languages.size >= MaxDemoStackSize -> false
                        else -> {
                            session.languages += language
                            true
                        }
                    }
                }
                if (accepted) {
                    exchange.respondText(contentType = "application/json", body = session.toJson())
                } else {
                    exchange.respondText(
                        status = 400,
                        contentType = "text/plain",
                        body = "That stack already has $MaxDemoStackSize languages — time to ship something.",
                    )
                }
            }
        }
    }

    private fun demoSession(exchange: HttpExchange): DemoStackSession {
        val cookieId = exchange.requestHeaders["Cookie"]
            ?.flatMap { header -> header.split(";") }
            ?.map(String::trim)
            ?.firstOrNull { cookie -> cookie.startsWith("$DemoSessionCookie=") }
            ?.substringAfter("=")
            ?.takeIf(String::isNotBlank)
        synchronized(demoSessions) {
            val existing = cookieId?.let(demoSessions::get)
            if (existing != null) {
                return existing
            }
        }

        val id = UUID.randomUUID().toString()
        val session = DemoStackSession()
        synchronized(demoSessions) { demoSessions[id] = session }
        exchange.responseHeaders.add(
            "Set-Cookie",
            "$DemoSessionCookie=$id; Path=/demo; HttpOnly; SameSite=Lax; Max-Age=86400",
        )
        return session
    }

    private fun DemoStackSession.toJson(): String {
        val snapshot = synchronized(demoSessions) { languages.toList() }
        return JsonObject(mapOf("languages" to JsonArray(snapshot.map(::JsonPrimitive)))).toString()
    }

    // --- static assets ---

    private fun respondBundleAsset(
        exchange: HttpExchange,
        preferredRoot: String,
        fallbackRoot: String,
        relative: String,
    ) {
        val asset = bundleAsset(preferredRoot, fallbackRoot, relative)
        if (asset == null) {
            exchange.respondText(
                status = 404,
                contentType = "text/plain",
                body = "Missing bundle asset $relative under $bundlesDir. Build with node scripts/bundle-docs.mjs, or set KINETICA_BUNDLES_DIR.",
            )
            return
        }
        exchange.respondStaticAsset(asset)
    }

    // Static benchmark demo pages + comparison report + raw results JSON, staged by
    // scripts/bundle-bench-static.mjs into bundlesDir/_bench_dist. Directory paths (trailing
    // slash, or the bare "/bench/") resolve to that directory's index.html.
    private fun respondBenchAsset(exchange: HttpExchange, relativeRaw: String) {
        val relative = if (relativeRaw.isEmpty() || relativeRaw.endsWith("/")) {
            "${relativeRaw}index.html"
        } else {
            relativeRaw
        }
        val asset = bundleCandidate("_bench_dist", relative)
        if (asset == null) {
            exchange.respondText(
                status = 404,
                contentType = "text/plain",
                body = "Missing bench asset $relative under $bundlesDir. Build with node scripts/bundle-bench-static.mjs, or set KINETICA_BUNDLES_DIR.",
            )
            return
        }
        exchange.respondStaticAsset(asset)
    }

    // The behavior-identical Game of Life apps, their trace report, and raw samples are
    // staged by scripts/build-game-of-life.mjs into bundlesDir/_game-of-life_dist.
    private fun respondGameOfLifeAsset(exchange: HttpExchange, relativeRaw: String) {
        val relative = when {
            relativeRaw.isEmpty() -> "benchmark.html"
            relativeRaw.endsWith("/") -> "${relativeRaw}index.html"
            else -> relativeRaw
        }
        val asset = bundleCandidate("_game-of-life_dist", relative)
        if (asset == null) {
            exchange.respondText(
                status = 404,
                contentType = "text/plain",
                body = "Missing Game of Life asset $relative under $bundlesDir. Build with node scripts/build-game-of-life.mjs, or set KINETICA_BUNDLES_DIR.",
            )
            return
        }
        exchange.respondStaticAsset(asset)
    }

    private fun siteCssAsset(): StaticAsset {
        val generated = staticAssetFromPath(
            bundlesDir.resolve("_docs-site_assets").resolve("site.css").normalize(),
            contentType = "text/css",
        )
        if (generated != null) {
            return generated
        }

        val bytes = DocsServer::class.java.getResourceAsStream("/site.css")?.readAllBytes()
            ?: "/* missing site.css */".encodeToByteArray()
        return StaticAsset(
            contentType = "text/css",
            bytes = bytes,
            brotliBytes = null,
        )
    }

    private fun bundleAsset(preferredRoot: String, fallbackRoot: String, relative: String): StaticAsset? =
        bundleCandidate(preferredRoot, relative) ?: bundleCandidate(fallbackRoot, relative)

    private fun bundleCandidate(root: String, relative: String): StaticAsset? {
        val rootPath = bundlesDir.resolve(root).normalize()
        val candidate = rootPath.resolve(relative).normalize()
        return candidate
            .takeIf { it.startsWith(rootPath) }
            ?.let { staticAssetFromPath(it, contentType = contentTypeFor(relative)) }
    }
}

private class DemoStackSession {
    val languages: MutableList<String> = mutableListOf("Kotlin")
}

private const val DemoSessionCookie = "kinetica-demo-session"
private const val MaxDemoSessions = 1000
private const val MaxDemoStackSize = 12
private const val MaxDemoLanguageLength = 40

private const val JavaFailureBody =
    "java.lang.NullPointerException: Cannot invoke \"Language.modernFeatures()\" because \"java\" is null"

private const val JavaScriptFailureBody =
    "TypeError: undefined is not a function (evaluating 'javascript.typeSystem()')"

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

private data class StaticAsset(
    val contentType: String,
    val bytes: ByteArray,
    val brotliBytes: ByteArray?,
) {
    val hash: String = bytes.sha256Hex()
    val etag: String = "W/\"$hash\""
}

private fun staticAssetFromPath(path: Path, contentType: String): StaticAsset? {
    if (!path.exists() || Files.isDirectory(path)) {
        return null
    }

    val brotliPath = path.resolveSibling("${path.fileName}.br")
    return StaticAsset(
        contentType = contentType,
        bytes = Files.readAllBytes(path),
        brotliBytes = brotliPath
            .takeIf { it.exists() && !Files.isDirectory(it) }
            ?.let(Files::readAllBytes),
    )
}

private fun versionedUrl(path: String, asset: StaticAsset?): String =
    asset?.let { "$path?hash=${it.hash}" } ?: path

private fun HttpExchange.respondStaticAsset(asset: StaticAsset) {
    val cacheControl = if (hasHash(asset.hash)) {
        "public, max-age=31536000, immutable"
    } else {
        "no-cache"
    }

    responseHeaders.set("Content-Type", "${asset.contentType}; charset=utf-8")
    responseHeaders.set("Cache-Control", cacheControl)
    responseHeaders.set("ETag", asset.etag)
    responseHeaders.set("Vary", "Accept-Encoding")

    if (requestHeaders.getFirst("If-None-Match")?.matchesEtag(asset.etag) == true) {
        sendResponseHeaders(304, -1)
        responseBody.close()
        return
    }

    val brotliBytes = asset.brotliBytes
    val responseBytes = if (brotliBytes != null && acceptsBrotli()) {
        responseHeaders.set("Content-Encoding", "br")
        brotliBytes
    } else {
        asset.bytes
    }

    sendResponseHeaders(200, responseBytes.size.toLong())
    responseBody.use { output -> output.write(responseBytes) }
}

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

private fun HttpExchange.hasHash(hash: String): Boolean =
    requestURI.rawQuery
        ?.split("&")
        ?.any { part -> part.substringBefore("=") == "hash" && part.substringAfter("=", "") == hash }
        ?: false

private fun HttpExchange.acceptsBrotli(): Boolean =
    requestHeaders["Accept-Encoding"]
        ?.flatMap { it.split(",") }
        ?.any { value -> value.trim().substringBefore(";").equals("br", ignoreCase = true) }
        ?: false

private fun String.matchesEtag(etag: String): Boolean =
    split(",").any { candidate ->
        val normalized = candidate.trim()
        normalized == "*" || normalized == etag
    }

private fun ByteArray.sha256Hex(length: Int = 16): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    val chars = CharArray(digest.size * 2)
    for (index in digest.indices) {
        val value = digest[index].toInt() and 0xff
        chars[index * 2] = HexChars[value ushr 4]
        chars[index * 2 + 1] = HexChars[value and 0x0f]
    }
    return chars.concatToString().take(length)
}

private val HexChars = "0123456789abcdef".toCharArray()

private fun contentTypeFor(path: String): String =
    when (path.substringBefore("?").substringAfterLast('.', missingDelimiterValue = "")) {
        "mjs", "js" -> "application/javascript"
        "json", "map" -> "application/json"
        "css" -> "text/css"
        "html" -> "text/html"
        else -> "text/plain"
    }
