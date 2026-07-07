package app.annotatedjs

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Node
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.host
import io.heapy.kinetica.materialize
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

// Must itself be a @UiComponent: the frame model stages child ordinals only inside
// component bodies (and component-typed content lambdas), so a plain helper calling
// ItemBadge would throw MissingKineticaPluginException at runtime.
@UiComponent
fun ComponentScope.App(item: Item, tick: Int) {
    host("div") {
        text("tick $tick", semantics = null)
        ItemBadge(item)
    }
}


// --- K2 hoisting: static leaf hosts become shared singletons; const props are interned ---

@UiComponent(skippable = false)
fun ComponentScope.HoistProbe(label: String) {
    host("span", props = propsOf("class", "chip"))
    host("p", props = propsOf("class", "labeled")) {
        text(label, semantics = null)
    }
}

@UiComponent(skippable = false)
fun ComponentScope.PrimaryTemplateProbe(label: String) {
    host("section", props = propsOf("class", "primary-template", "data-template", "primary")) {
        text(label, semantics = null)
    }
}

private fun findHost(node: io.heapy.kinetica.Node, tag: String): io.heapy.kinetica.HostNode? {
    if (node is io.heapy.kinetica.HostNode) {
        if (node.tag == tag) return node
        node.children.forEach { child -> findHost(child, tag)?.let { return it } }
    }
    if (node is io.heapy.kinetica.FragmentNode) {
        node.children.forEach { child -> findHost(child, tag)?.let { return it } }
    }
    if (node is io.heapy.kinetica.TemplateNode) {
        return findHost(node.materialize(), tag)
    }
    return null
}

private fun verifyHoisting() {
    val runtime = KineticaRuntime(debug = false)
    val scope = ComponentScope(runtime)
    var label = "one"
    fun render() = runtime.render(scope) { HoistProbe(label) }.tree
    val first = render()
    label = "two"
    val second = render()

    val chip1 = checkNotNull(findHost(first, "span")) { "chip missing in $first" }
    val chip2 = checkNotNull(findHost(second, "span")) { "chip missing in $second" }
    check(chip1 === chip2) { "static leaf host must be hoisted to a shared instance" }

    val p1 = checkNotNull(findHost(first, "p"))
    val p2 = checkNotNull(findHost(second, "p"))
    check(p1 !== p2) { "dynamic host must be rebuilt per render" }
    check(p1.props === p2.props) { "const props must be interned to a shared instance" }
    check(findHost(second, "p")!!.children.isNotEmpty()) { "dynamic child lost" }
}

@UiComponent(skippable = false)
fun ComponentScope.TemplateCanary(primary: String, secondary: String) {
    host("article", props = propsOf("data-role", "template-canary")) {
        PrimaryTemplateProbe(primary)
        SecondaryTemplateProbe(secondary)
    }
}

private fun verifyTemplatesAcrossFiles() {
    val runtime = KineticaRuntime(debug = false)
    val scope = ComponentScope(runtime)
    val html = runtime.render(scope) {
        TemplateCanary("Primary JS", "Secondary JS")
    }.tree.toSafeHtml()

    check("""<section class="primary-template" data-template="primary">Primary JS</section>""" in html) {
        "primary template render lost or corrupted: $html"
    }
    check("""<strong class="secondary-template" data-template="secondary">Secondary JS</strong>""" in html) {
        "secondary template render lost or corrupted: $html"
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

    verifyHoisting()
    verifyTemplatesAcrossFiles()
    println("annotated-js OK: skip + hoist + template semantics verified ($badgeRenders badge renders)")
}
