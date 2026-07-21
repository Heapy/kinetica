package app.gtk.counter

import gtk4.G_APPLICATION_DEFAULT_FLAGS
import gtk4.GtkApplication
import gtk4.GtkWindow
import gtk4.g_application_run
import gtk4.g_object_unref
import gtk4.g_signal_connect_data
import gtk4.gtk_application_new
import gtk4.gtk_application_window_new
import gtk4.GtkOrientation
import gtk4.gtk_box_new
import gtk4.gtk_window_present
import gtk4.gtk_window_set_child
import gtk4.gtk_window_set_default_size
import gtk4.gtk_window_set_title
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.button
import io.heapy.kinetica.column
import io.heapy.kinetica.event
import io.heapy.kinetica.gtk.GtkKineticaApp
import io.heapy.kinetica.gtk.renderGtkApp
import io.heapy.kinetica.row
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import io.heapy.kinetica.textInput
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction

// The component is identical to samples/native-counter (and the counter block to
// samples/browser-counter) — the value tree is toolkit-agnostic across DOM, AppKit and GTK.
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

// GTK's activate callback is a capture-free staticCFunction, so the running app is held in a
// top-level var (the idiomatic shape for gtk-c-style bootstraps).
private var kineticaApp: GtkKineticaApp? = null

private fun onActivate(app: CPointer<GtkApplication>) {
    val window = gtk_application_window_new(app)!!
    gtk_window_set_title(window.reinterpret<GtkWindow>(), "Kinetica Counter")
    gtk_window_set_default_size(window.reinterpret<GtkWindow>(), 360, 240)

    val root = gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!
    gtk_window_set_child(window.reinterpret<GtkWindow>(), root)

    kineticaApp = renderGtkApp(container = root) { CounterApp() }

    gtk_window_present(window.reinterpret<GtkWindow>())
}

fun main() {
    val app = gtk_application_new("io.heapy.kinetica.counter", G_APPLICATION_DEFAULT_FLAGS)!!
    g_signal_connect_data(
        app,
        "activate",
        staticCFunction { appPtr: CPointer<GtkApplication>?, _: COpaquePointer? ->
            appPtr?.let(::onActivate)
        }.reinterpret(),
        null,
        null,
        0u,
    )
    g_application_run(app.reinterpret(), 0, null)
    g_object_unref(app)
}
