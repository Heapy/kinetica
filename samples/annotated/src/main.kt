package app.annotated

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Preview
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.generated.KineticaGeneratedCompilerPluginId
import io.heapy.kinetica.generated.KineticaGeneratedCompilerPluginVersion
import io.heapy.kinetica.generated.KineticaGeneratedComponentTransforms
import io.heapy.kinetica.generated.KineticaGeneratedPreviews
import io.heapy.kinetica.host
import io.heapy.kinetica.materialize
import io.heapy.kinetica.propsOf
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import io.heapy.kinetica.testing.KineticaTest

@UiComponent
fun Badge(label: String) {
    text("Static badge")
    text(label)
}

@Preview("Annotated app")
@UiComponent
fun AnnotatedApp() {
    var label by state { "Inbox" }

    Badge(label)
}

// --- receiver-style component: exercised by the IR transform (same path as Kotlin/JS) ---

data class Item(val id: Int, val label: String)

private var badgeRenders = 0

@UiComponent
fun ComponentScope.ItemBadge(item: Item) {
    badgeRenders++
    host("span", props = propsOf("class", "badge", "data-id", item.id.toString())) {
        text(item.label, semantics = null)
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
    val runtime = io.heapy.kinetica.KineticaRuntime(debug = false)
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

fun main() {
    check(KineticaGeneratedCompilerPluginId == "io.heapy.kinetica.compiler")
    check(KineticaGeneratedCompilerPluginVersion == "0.2.0")
    check(KineticaGeneratedComponentTransforms.any { it.componentFqName == "app.annotated.AnnotatedApp" })
    check(KineticaGeneratedPreviews.any { it.componentFqName == "app.annotated.AnnotatedApp" })

    val root = KineticaTest.render {
        emit(AnnotatedApp())
    }
    println(root.tree())

    // IR-transform skip semantics, mirroring samples/annotated-js
    val runtime = io.heapy.kinetica.KineticaRuntime(debug = false)
    val scope = ComponentScope(runtime)
    var item = Item(1, "Inbox")
    var tick = 0
    fun render() = runtime.render(scope) {
        host("div") {
            text("tick $tick", semantics = null)
            ItemBadge(item)
        }
    }.tree

    render()
    check(badgeRenders == 1) { "expected 1 badge render, got $badgeRenders" }
    tick = 1
    render()
    check(badgeRenders == 1) { "expected skip on unchanged input, got $badgeRenders renders" }
    item = Item(1, "Archive")
    render()
    check(badgeRenders == 2) { "expected re-render on changed input, got $badgeRenders renders" }
    verifyHoisting()
    println("annotated OK: skip + hoist semantics verified on JVM ($badgeRenders badge renders)")
}
