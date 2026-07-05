package app.browser.counter

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.browser.mountKineticaApp
import io.heapy.kinetica.button
import io.heapy.kinetica.column
import io.heapy.kinetica.event
import io.heapy.kinetica.row
import io.heapy.kinetica.state
import io.heapy.kinetica.text

fun ComponentScope.CounterApp() {
    var count by state(key = "count") { 0 }

    column(semantics = Semantics(testTag = "counter-app")) {
        text("Kinetica Counter")
        row {
            button(
                onClick = event { count -= 1 },
                semantics = Semantics(role = Role.Button, testTag = "decrement", focusable = true),
            ) {
                text("-")
            }
            text("Count: $count")
            button(
                onClick = event { count += 1 },
                semantics = Semantics(role = Role.Button, testTag = "increment", focusable = true),
            ) {
                text("+")
            }
        }
        button(
            enabled = count != 0,
            onClick = event { count = 0 },
            semantics = Semantics(role = Role.Button, testTag = "reset", focusable = true),
        ) {
            text("Reset")
        }
    }
}

fun main() {
    mountKineticaApp("#app") {
        CounterApp()
    }
}
