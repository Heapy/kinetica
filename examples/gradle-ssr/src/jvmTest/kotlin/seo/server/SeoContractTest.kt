package seo.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import seo.site.Articles

/**
 * These assertions are the SEO contract: they fail the build if a refactor ever moves content out
 * of the first HTML response, drops metadata, or starts answering 200 for missing pages.
 */
class SeoContractTest {
    private val origin = "https://example.test"

    @Test
    fun articleHtmlCarriesBodyTextAndMetadata() {
        val article = Articles.first()
        val html = articleHtml(article, origin)

        assertContains(html, "<title>${article.title} — Kinetica SSR</title>")
        assertContains(html, "<meta name=\"description\" content=\"${article.description}\">")
        assertContains(html, "<link rel=\"canonical\" href=\"$origin/articles/${article.slug}\">")
        assertContains(html, "\"@type\":\"BlogPosting\"")
        article.body.forEach { paragraph ->
            assertContains(html, paragraph.substringBefore(','))
        }
    }

    @Test
    fun islandMarkupIsServerRenderedAndCarriesNoHandlers() {
        val article = Articles.first()
        val html = articleHtml(article, origin)

        assertContains(html, "data-kinetica-island=\"article-feedback\"")
        assertContains(html, "${article.helpful} readers found it helpful.")
        assertContains(html, "Yes, helpful")
        // Event wiring is client-only: the HTML serialiser drops `event:` props.
        assertEquals(false, html.contains("event:onClick"))
    }

    @Test
    fun notFoundPageIsNotIndexable() {
        val html = notFoundHtml("/missing", origin)

        assertContains(html, "<meta name=\"robots\" content=\"noindex, follow\">")
    }

    @Test
    fun serverAnswersCrawlerRoutes() = testApplication {
        application { siteModule(origin) }

        val index = client.get("/")
        assertEquals(HttpStatusCode.OK, index.status)
        assertContains(index.bodyAsText(), Articles.first().title)

        assertEquals(HttpStatusCode.NotFound, client.get("/articles/does-not-exist").status)

        val robots = client.get("/robots.txt")
        assertContains(robots.bodyAsText(), "Sitemap: $origin/sitemap.xml")

        val sitemap = client.get("/sitemap.xml")
        Articles.forEach { article ->
            assertContains(sitemap.bodyAsText(), "$origin/articles/${article.slug}")
        }
    }
}
