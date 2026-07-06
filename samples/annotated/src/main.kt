package app.annotated

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Preview
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.generated.KineticaGeneratedCompilerPluginId
import io.heapy.kinetica.generated.KineticaGeneratedCompilerPluginVersion
import io.heapy.kinetica.generated.KineticaGeneratedComponentTransforms
import io.heapy.kinetica.generated.KineticaGeneratedPreviews
import io.heapy.kinetica.host
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
    println("annotated OK: skip semantics verified on JVM ($badgeRenders badge renders)")
}
