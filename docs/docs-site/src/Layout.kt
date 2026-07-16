package docs.site

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.host
import io.heapy.kinetica.markdown.MarkdownOptions
import io.heapy.kinetica.markdown.markdown
import io.heapy.kinetica.text
import io.heapy.kinetica.toSafeHtml

data class DocsAssetUrls(
    val siteCssHref: String,
    val docsClientScriptSrc: String,
    val serverComponentsClientScriptSrc: String,
)

/** The whole page — chrome and content alike — is a Kinetica tree rendered to safe HTML. */
fun renderDocPage(page: DocPage, source: String, assetUrls: DocsAssetUrls): String {
    val tree = KineticaRuntime(debug = false).render {
        DocLayout(page, source)
    }.tree
    return documentShell(
        title = "${page.title} · Kinetica",
        body = tree.toSafeHtml(),
        assetUrls = assetUrls,
        liveExamples = true,
    )
}

private fun ComponentScope.DocLayout(page: DocPage, source: String) {
    host("div", props = mapOf("class" to "shell")) {
        host("header", props = mapOf("class" to "topbar")) {
            host("a", props = mapOf("class" to "brand", "href" to "/")) {
                text("Kinetica", semantics = null)
            }
            host("nav", props = mapOf("class" to "topnav")) {
                host("a", props = mapOf("href" to "/docs/game-of-life")) {
                    text("Game of Life", semantics = null)
                }
                host("a", props = mapOf("href" to "/examples/server-components")) {
                    text("Live demo", semantics = null)
                }
                host("a", props = mapOf("href" to "/docs/performance")) {
                    text("Benchmarks", semantics = null)
                }
            }
        }
        host("div", props = mapOf("class" to "columns")) {
            Sidebar(current = page.slug)
            host("main", props = mapOf("class" to "content", "id" to "content")) {
                markdown(
                    source,
                    MarkdownOptions(directive = { name, argument -> renderDirective(name, argument) }),
                )
            }
        }
        host("footer", props = mapOf("class" to "footer")) {
            text("Rendered on the JVM by Kinetica itself — every page on this site is a Node tree. ", semantics = null)
            host("a", props = mapOf("href" to "/docs/markdown")) {
                text("How this site works", semantics = null)
            }
        }
    }
}

private fun ComponentScope.Sidebar(current: String) {
    host("aside", props = mapOf("class" to "sidebar")) {
        DocPages.groupBy { it.section }.forEach { (section, pages) ->
            host("div", props = mapOf("class" to "nav-section")) {
                host("p", props = mapOf("class" to "nav-title")) {
                    text(section, semantics = null)
                }
                host("ul") {
                    pages.forEach { page ->
                        host("li") {
                            val href = if (page.slug == "index") "/" else "/docs/${page.slug}"
                            val cls = if (page.slug == current) "nav-link active" else "nav-link"
                            host("a", props = mapOf("href" to href, "class" to cls)) {
                                text(page.title, semantics = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ComponentScope.renderDirective(name: String, argument: String): Boolean =
    when (name) {
        "example" -> {
            host("div", props = mapOf("class" to "live-example", "data-example" to argument)) {
                host("div", props = mapOf("class" to "live-example-slot")) {
                    text("Loading live example…", semantics = null)
                }
                host("p", props = mapOf("class" to "live-example-caption")) {
                    text("Live — this widget is a Kinetica app mounted in your browser.", semantics = null)
                }
            }
            true
        }
        "demo-link" -> {
            host("p", props = mapOf("class" to "demo-link")) {
                host("a", props = mapOf("href" to argument, "class" to "demo-button")) {
                    text("Open the live server-components demo", semantics = null)
                }
            }
            true
        }
        else -> false
    }

fun documentShell(
    title: String,
    body: String,
    assetUrls: DocsAssetUrls,
    liveExamples: Boolean,
): String = buildString {
    append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
    append("<meta charset=\"utf-8\">\n")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
    append("<title>").append(title.escapeHtml()).append("</title>\n")
    append("<link rel=\"stylesheet\" href=\"").append(assetUrls.siteCssHref.escapeHtml()).append("\">\n")
    append("</head>\n<body>\n")
    append(body)
    if (liveExamples) {
        append("\n<script type=\"module\" src=\"")
            .append(assetUrls.docsClientScriptSrc.escapeHtml())
            .append("\"></script>")
    }
    append("\n</body>\n</html>\n")
}

internal fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
