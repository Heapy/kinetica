package seo.client

import io.heapy.kinetica.browser.mountKineticaApp
import kotlinx.browser.document
import org.w3c.dom.Element
import seo.site.ArticleFeedback
import seo.site.FEEDBACK_ISLAND
import seo.site.FeedbackProps
import seo.site.IslandJson

/**
 * Hydration, island-style: the document is already complete, so the client only looks for the
 * containers the server marked, reads their props out of the markup, and mounts a live Kinetica
 * app into each one. Nothing here is on the critical path for a crawler.
 */
fun main() {
    val islands = document.querySelectorAll("[data-kinetica-island]")
    for (index in 0 until islands.length) {
        val element = islands.item(index) as? Element ?: continue
        when (element.getAttribute("data-kinetica-island")) {
            FEEDBACK_ISLAND -> {
                val props = IslandJson.decodeFromString(
                    FeedbackProps.serializer(),
                    element.getAttribute("data-kinetica-props").orEmpty(),
                )
                mountKineticaApp(element) {
                    ArticleFeedback(props)
                }
            }
        }
    }
}
