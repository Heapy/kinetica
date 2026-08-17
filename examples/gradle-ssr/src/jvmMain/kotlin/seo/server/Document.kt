package seo.server

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.escapeHtmlAttribute
import io.heapy.kinetica.escapeHtmlText
import io.heapy.kinetica.toSafeHtml
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import seo.site.Article
import seo.site.Articles
import seo.site.ArticlePage
import seo.site.IndexPage
import seo.site.IslandJson
import seo.site.NotFoundPage
import seo.site.SiteLayout

/** Everything a crawler reads before it reads the body. */
data class PageSeo(
    val title: String,
    val description: String,
    val path: String,
    val ogType: String = "website",
    val indexable: Boolean = true,
    val jsonLd: JsonObject? = null,
)

/**
 * Renders a page the way every request gets it: the component tree is evaluated on the JVM into a
 * `Node` value, serialised to HTML, and wrapped in a document head. No user-agent checks anywhere —
 * crawlers and browsers receive byte-identical HTML.
 */
fun renderPage(
    seo: PageSeo,
    origin: String,
    content: @UiComponent ComponentScope.() -> Unit,
): String {
    val tree = KineticaRuntime(debug = false).render {
        SiteLayout(currentPath = seo.path, content = content)
    }.tree
    return documentShell(seo, origin, tree.toSafeHtml())
}

fun indexHtml(origin: String): String =
    renderPage(
        seo = PageSeo(
            title = "Kinetica SSR — SEO-first Kotlin on the server",
            description = "A Gradle example: Kotlin components rendered to HTML on the JVM, hydrated as " +
                "browser islands, with the metadata search engines expect.",
            path = "/",
            jsonLd = itemListJsonLd(origin),
        ),
        origin = origin,
    ) {
        IndexPage()
    }

fun articleHtml(article: Article, origin: String): String =
    renderPage(
        seo = PageSeo(
            title = "${article.title} — Kinetica SSR",
            description = article.description,
            path = "/articles/${article.slug}",
            ogType = "article",
            jsonLd = articleJsonLd(article, origin),
        ),
        origin = origin,
    ) {
        ArticlePage(article)
    }

fun notFoundHtml(path: String, origin: String): String =
    renderPage(
        seo = PageSeo(
            title = "Page not found — Kinetica SSR",
            description = "No page is published at this address.",
            path = path,
            // A 404 body must never invite indexing, even though it renders like any other page.
            indexable = false,
        ),
        origin = origin,
    ) {
        NotFoundPage(path)
    }

private fun articleJsonLd(article: Article, origin: String): JsonObject =
    buildJsonObject {
        put("@context", "https://schema.org")
        put("@type", "BlogPosting")
        put("headline", article.title)
        put("description", article.description)
        put("datePublished", article.published)
        put("url", "$origin/articles/${article.slug}")
        put("keywords", article.tags.joinToString(", "))
        putJsonObject("author") {
            put("@type", "Organization")
            put("name", "Kinetica")
        }
        putJsonObject("mainEntityOfPage") {
            put("@type", "WebPage")
            put("@id", "$origin/articles/${article.slug}")
        }
    }

private fun itemListJsonLd(origin: String): JsonObject =
    buildJsonObject {
        put("@context", "https://schema.org")
        put("@type", "ItemList")
        put("itemListElement", buildJsonArray {
            Articles.forEachIndexed { index, article ->
                add(
                    buildJsonObject {
                        put("@type", "ListItem")
                        put("position", index + 1)
                        put("name", article.title)
                        put("url", "$origin/articles/${article.slug}")
                    },
                )
            }
        })
    }

private fun documentShell(
    seo: PageSeo,
    origin: String,
    bodyHtml: String,
): String {
    val canonical = origin + seo.path
    return buildString {
        append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
        append("<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<title>").append(escapeHtmlText(seo.title)).append("</title>\n")
        appendMeta("description", seo.description)
        appendMeta("robots", if (seo.indexable) "index, follow" else "noindex, follow")
        append("<link rel=\"canonical\" href=\"").append(escapeHtmlAttribute(canonical)).append("\">\n")
        appendProperty("og:type", seo.ogType)
        appendProperty("og:title", seo.title)
        appendProperty("og:description", seo.description)
        appendProperty("og:url", canonical)
        appendProperty("og:site_name", "Kinetica SSR")
        appendMeta("twitter:card", "summary_large_image")
        append("<link rel=\"stylesheet\" href=\"/static/site.css\">\n")
        // defer, not blocking: the document is already complete without the bundle.
        append("<script src=\"/static/client.js\" defer></script>\n")
        seo.jsonLd?.let { json ->
            append("<script type=\"application/ld+json\">")
            append(IslandJson.encodeToString(JsonObject.serializer(), json).replace("</", "<\\/"))
            append("</script>\n")
        }
        append("</head>\n<body>\n")
        append(bodyHtml)
        append("\n</body>\n</html>\n")
    }
}

private fun StringBuilder.appendMeta(name: String, content: String) {
    append("<meta name=\"").append(name).append("\" content=\"")
        .append(escapeHtmlAttribute(content)).append("\">\n")
}

private fun StringBuilder.appendProperty(property: String, content: String) {
    append("<meta property=\"").append(property).append("\" content=\"")
        .append(escapeHtmlAttribute(content)).append("\">\n")
}
