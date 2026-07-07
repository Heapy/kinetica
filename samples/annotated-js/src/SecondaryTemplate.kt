package app.annotatedjs

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.host
import io.heapy.kinetica.propsOf
import io.heapy.kinetica.text

@UiComponent(skippable = false)
fun ComponentScope.SecondaryTemplateProbe(label: String) {
    host("strong", props = propsOf("class", "secondary-template", "data-template", "secondary")) {
        text(label, semantics = null)
    }
}
