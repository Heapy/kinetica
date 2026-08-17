package seo.site

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.button
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val FEEDBACK_ISLAND: String = "article-feedback"

val IslandJson: Json = Json { ignoreUnknownKeys = true }

@Serializable
data class FeedbackProps(
    val slug: String,
    val helpful: Int,
)

/**
 * Container for one island. The server renders the component inside it, so the markup is already
 * in the initial HTML; the client finds the container by attribute and mounts a live app into it.
 */
fun ComponentScope.island(
    id: String,
    propsJson: String,
    content: @UiComponent ComponentScope.() -> Unit,
) {
    host(
        "div",
        props = mapOf(
            "class" to "island",
            "data-kinetica-island" to id,
            "data-kinetica-props" to propsJson,
        ),
        content = content,
    )
}

/**
 * Runs twice: once on the JVM (producing indexable HTML with no event handlers attached, since
 * `event:` props are dropped by the HTML serialiser) and once in the browser, where the same
 * component becomes interactive.
 */
@UiComponent
fun ComponentScope.ArticleFeedback(props: FeedbackProps) {
    var helpful by state { props.helpful }
    var voted by state { false }

    host("section", props = mapOf("class" to "feedback", "aria-label" to "Article feedback")) {
        host("h2") {
            text("Was this article helpful?", semantics = null)
        }
        host("p", props = mapOf("class" to "feedback-count")) {
            text("$helpful readers found it helpful.", semantics = null)
        }
        host("div", props = mapOf("class" to "feedback-actions")) {
            button(
                enabled = !voted,
                onClick = event {
                    helpful += 1
                    voted = true
                },
                semantics = Semantics(role = Role.Button, testTag = "vote-helpful", focusable = true),
            ) {
                text("Yes, helpful", semantics = null)
            }
            button(
                enabled = !voted,
                onClick = event { voted = true },
                semantics = Semantics(role = Role.Button, testTag = "vote-not-helpful", focusable = true),
            ) {
                text("Not really", semantics = null)
            }
        }
        host("p", props = mapOf("class" to "feedback-status")) {
            text(
                if (voted) "Thanks — your vote stays in this tab." else "Voting needs JavaScript; the count above does not.",
                semantics = null,
            )
        }
    }
}
