package app.native.counter

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.appkit.renderAppKitApp
import io.heapy.kinetica.button
import io.heapy.kinetica.column
import io.heapy.kinetica.event
import io.heapy.kinetica.row
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import io.heapy.kinetica.textInput
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskTitled
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSBackingStoreBuffered
import platform.Foundation.NSMakeRect

// The counter block is identical to samples/browser-counter — the value tree is toolkit-agnostic.
// The name row exercises the KNT-0046 textInput wiring (onInput payload dispatch + focus/typing
// survival across retained-diff renders).
@UiComponent
fun ComponentScope.CounterApp() {
    var count by state { 0 }
    var name by state { "" }

    column(semantics = Semantics(testTag = "counter-app")) {
        text("Kinetica Counter")
        textInput(
            value = name,
            onInput = event<String> { name = it },
            placeholder = "Your name",
            semantics = Semantics(role = Role.TextInput, testTag = "name-input", focusable = true),
        )
        text(if (name.isEmpty()) "Hello, stranger" else "Hello, $name")
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

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val app = NSApplication.sharedApplication()
    app.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)

    val window = NSWindow(
        contentRect = NSMakeRect(0.0, 0.0, 360.0, 200.0),
        styleMask = NSWindowStyleMaskTitled or
            NSWindowStyleMaskClosable or
            NSWindowStyleMaskMiniaturizable or
            NSWindowStyleMaskResizable,
        backing = NSBackingStoreBuffered,
        defer = false,
    ).apply {
        title = "Kinetica Counter"
    }

    val kineticaApp = renderAppKitApp(
        contentView = window.contentView!!,
        content = { CounterApp() },
    )

    window.center()
    window.makeKeyAndOrderFront(app)
    app.run()
}
