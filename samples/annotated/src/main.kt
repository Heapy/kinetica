package app.annotated

import io.heapy.kinetica.Preview
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.generated.KineticaGeneratedCompilerPluginId
import io.heapy.kinetica.generated.KineticaGeneratedCompilerPluginVersion
import io.heapy.kinetica.generated.KineticaGeneratedComponentTransforms
import io.heapy.kinetica.generated.KineticaGeneratedPreviews
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

fun main() {
    check(KineticaGeneratedCompilerPluginId == "io.heapy.kinetica.compiler")
    check(KineticaGeneratedCompilerPluginVersion == "0.1.0")
    check(KineticaGeneratedComponentTransforms.any { it.componentFqName == "app.annotated.AnnotatedApp" })
    check(KineticaGeneratedPreviews.any { it.componentFqName == "app.annotated.AnnotatedApp" })

    val root = KineticaTest.render {
        emit(AnnotatedApp())
    }
    println(root.tree())
}
