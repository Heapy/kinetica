package app.annotatedjs

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.host
import io.heapy.kinetica.propsOf
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import io.heapy.kinetica.toSafeHtml

data class Item(val id: Int, val label: String)

private var badgeRenders = 0

// Receiver-style component: the Kinetica IR transform (K2 backend, JVM+JS) wraps this body in
// skippableNode keyed on `item`, so re-renders with an equal item reuse the cached subtree.
@UiComponent
fun ComponentScope.ItemBadge(item: Item) {
    badgeRenders++
    host("span", props = propsOf("class", "badge", "data-id", item.id.toString())) {
        text(item.label, semantics = null)
    }
}

fun ComponentScope.App(item: Item, tick: Int) {
    host("div") {
        text("tick $tick", semantics = null)
        ItemBadge(item)
    }
}

fun main() {
    val runtime = KineticaRuntime(debug = false)
    val scope = ComponentScope(runtime)
    var item = Item(1, "Inbox")
    var tick = 0

    fun render(): String = runtime.render(scope) { App(item, tick) }.tree.toSafeHtml()

    val first = render()
    check("Inbox" in first) { "unexpected render: $first" }
    check(badgeRenders == 1) { "expected 1 badge render, got $badgeRenders" }

    // unchanged input + changed sibling state: the badge must be SKIPPED
    tick = 1
    val second = render()
    check("tick 1" in second) { "sibling update lost: $second" }
    check("Inbox" in second) { "skipped subtree lost: $second" }
    check(badgeRenders == 1) {
        "expected skip on unchanged input (1 render), got $badgeRenders — IR transform inactive or broken"
    }

    // changed input: the badge must re-render
    item = Item(1, "Archive")
    val third = render()
    check("Archive" in third) { "input change not applied: $third" }
    check(badgeRenders == 2) { "expected re-render on changed input, got $badgeRenders" }

    println("annotated-js OK: skip semantics verified ($badgeRenders badge renders)")
}
