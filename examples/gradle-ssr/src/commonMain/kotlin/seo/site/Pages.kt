package seo.site

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.host
import io.heapy.kinetica.text
import kotlinx.serialization.encodeToString

/**
 * Page chrome. Semantic tags on purpose: `header`/`nav`/`main`/`article`/`footer` are what a
 * crawler uses to tell content from boilerplate.
 */
@UiComponent
fun ComponentScope.SiteLayout(
    currentPath: String,
    content: @UiComponent ComponentScope.() -> Unit,
) {
    host("div", props = mapOf("class" to "shell")) {
        host("header", props = mapOf("class" to "topbar")) {
            host("a", props = mapOf("href" to "/", "class" to "brand")) {
                text("Kinetica SSR", semantics = null)
            }
            host("nav", props = mapOf("aria-label" to "Main")) {
                Articles.forEach { article ->
                    val href = "/articles/${article.slug}"
                    host(
                        "a",
                        props = mapOf(
                            "href" to href,
                            "class" to if (href == currentPath) "nav-link current" else "nav-link",
                        ),
                        key = article.slug,
                    ) {
                        text(article.navLabel, semantics = null)
                    }
                }
            }
        }
        host("main", props = mapOf("id" to "content"), content = content)
        host("footer", props = mapOf("class" to "footer")) {
            text("Server-rendered by Kinetica on the JVM. ", semantics = null)
            host("a", props = mapOf("href" to "/sitemap.xml")) {
                text("sitemap.xml", semantics = null)
            }
        }
    }
}

@UiComponent
fun ComponentScope.IndexPage() {
    host("h1") {
        text("SEO-first Kotlin, rendered on the server", semantics = null)
    }
    host("p", props = mapOf("class" to "lede")) {
        text(
            "Three notes on getting a Kotlin app indexed properly. Every word below arrives in the " +
                "first HTML response — view source and see for yourself.",
            semantics = null,
        )
    }
    host("ul", props = mapOf("class" to "article-list")) {
        Articles.forEach { article ->
            host("li", key = article.slug) {
                host("article", props = mapOf("class" to "card")) {
                    host("h2") {
                        host("a", props = mapOf("href" to "/articles/${article.slug}")) {
                            text(article.title, semantics = null)
                        }
                    }
                    host("p", props = mapOf("class" to "card-meta")) {
                        host("time", props = mapOf("datetime" to article.published)) {
                            text(article.published, semantics = null)
                        }
                        text(" · ${article.tags.joinToString(", ")}", semantics = null)
                    }
                    host("p") {
                        text(article.description, semantics = null)
                    }
                }
            }
        }
    }
}

@UiComponent
fun ComponentScope.ArticlePage(article: Article) {
    host("article", props = mapOf("class" to "post")) {
        host("h1") {
            text(article.title, semantics = null)
        }
        host("p", props = mapOf("class" to "card-meta")) {
            host("time", props = mapOf("datetime" to article.published)) {
                text(article.published, semantics = null)
            }
            text(" · ${article.tags.joinToString(", ")}", semantics = null)
        }
        article.body.forEach { paragraph ->
            host("p") {
                text(paragraph, semantics = null)
            }
        }
    }
    val props = FeedbackProps(slug = article.slug, helpful = article.helpful)
    island(FEEDBACK_ISLAND, IslandJson.encodeToString(props)) {
        ArticleFeedback(props)
    }
    host("p", props = mapOf("class" to "back")) {
        host("a", props = mapOf("href" to "/")) {
            text("← All articles", semantics = null)
        }
    }
}

@UiComponent
fun ComponentScope.NotFoundPage(path: String) {
    host("h1") {
        text("Page not found", semantics = null)
    }
    host("p") {
        text("Nothing is published at $path. This response carries HTTP 404, so it stays out of the index.", semantics = null)
    }
    host("p") {
        host("a", props = mapOf("href" to "/")) {
            text("← All articles", semantics = null)
        }
    }
}
