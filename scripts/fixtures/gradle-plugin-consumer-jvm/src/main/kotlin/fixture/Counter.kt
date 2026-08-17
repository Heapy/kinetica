package fixture

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.button
import io.heapy.kinetica.column
import io.heapy.kinetica.state
import io.heapy.kinetica.text

@UiComponent
fun ComponentScope.Counter(start: Int = 0) {
    var count by state { start }

    column {
        text("count: $count")
        button(
            onClick = { count += 1 },
            semantics = Semantics(role = Role.Button, testTag = "increment", focusable = true),
        ) {
            text("+")
        }
    }
}
