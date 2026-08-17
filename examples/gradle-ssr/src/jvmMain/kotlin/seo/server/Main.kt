package seo.server

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.CachingOptions
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import seo.site.Articles
import seo.site.articleBySlug
import java.security.MessageDigest

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        siteModule(origin = System.getenv("SITE_ORIGIN") ?: "http://localhost:$port")
    }.start(wait = true)
}

fun Application.siteModule(origin: String) {
    install(Compression) {
        gzip()
        deflate()
    }
    install(CachingHeaders) {
        options { _, content ->
            when (content.contentType?.withoutParameters()) {
                ContentType.Text.CSS, ContentType.Application.JavaScript ->
                    CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 60 * 60))
                else -> null
            }
        }
    }
    install(StatusPages) {
        // Unknown URLs render the same layout as everything else — but with a 404 status and
        // `noindex`, which is what keeps them out of search results.
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondPage(notFoundHtml(call.request.uri, origin), status)
        }
    }

    routing {
        get("/") {
            call.respondPage(indexHtml(origin))
        }
        get("/articles/{slug}") {
            val article = call.parameters["slug"]?.let(::articleBySlug)
            if (article == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respondPage(articleHtml(article, origin))
            }
        }
        get("/robots.txt") {
            call.respondText(
                """
                User-agent: *
                Allow: /
                Sitemap: $origin/sitemap.xml
                """.trimIndent() + "\n",
                ContentType.Text.Plain,
            )
        }
        get("/sitemap.xml") {
            call.respondText(sitemapXml(origin), ContentType.Application.Xml)
        }
        staticResources("/static", "static")
    }
}

private fun sitemapXml(origin: String): String =
    buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n")
        append("  <url><loc>$origin/</loc><changefreq>daily</changefreq></url>\n")
        Articles.forEach { article ->
            append("  <url><loc>$origin/articles/${article.slug}</loc>")
            append("<lastmod>${article.published}</lastmod></url>\n")
        }
        append("</urlset>\n")
    }

/**
 * HTML is cheap to regenerate but expensive to re-download: a strong ETag lets a revalidating
 * crawler spend one 304 instead of a full page, which is a real crawl-budget saving on large sites.
 */
private suspend fun ApplicationCall.respondPage(
    html: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    val etag = "\"" + sha256Hex(html).take(16) + "\""
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, "public, max-age=0, must-revalidate")
    if (status == HttpStatusCode.OK && request.header(HttpHeaders.IfNoneMatch) == etag) {
        respond(HttpStatusCode.NotModified)
        return
    }
    respondText(html, ContentType.Text.Html.withCharset(Charsets.UTF_8), status)
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1) }
