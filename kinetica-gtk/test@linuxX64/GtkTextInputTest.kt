package io.heapy.kinetica.gtk

import gtk4.*
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.render.MountedNode
import io.heapy.kinetica.render.Reconciler
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlin.test.*

class GtkTextInputTest {
    @Test
    fun inputTypeMountAndPatchRetainValueAndBindingWhileUpdatingVisibilityAndPurpose() {
        assertNotEquals(0, gtk_init_check(), "GTK tests need a display; run with xvfb-run -a")
        val runtime = KineticaRuntime()
        var inputEvents = 0
        val dispatcher = GtkEventDispatcher(runtime) { inputEvents++ }
        val reconciler = Reconciler(GtkHostAdapter(dispatcher))
        val container = gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!
        g_object_ref_sink(container)
        var node = HostNode("textInput", mapOf("value" to "secret", "type" to "password", "event:onInput" to "input"))
        var mounted = reconciler.mount(node, container)
        try {
            val view = (mounted as MountedNode.Host<GtkWidgetPtr>).view
            val entry = view.reinterpret<GtkEntry>()
            val editable = view.reinterpret<GtkEditable>()
            assertEquals(0, gtk_entry_get_visibility(entry))
            assertEquals(GtkInputPurpose.GTK_INPUT_PURPOSE_PASSWORD, gtk_entry_get_input_purpose(entry))
            assertEquals("secret", gtk_editable_get_text(editable)?.toKString())

            val variants = listOf(
                null to GtkInputPurpose.GTK_INPUT_PURPOSE_FREE_FORM,
                "password" to GtkInputPurpose.GTK_INPUT_PURPOSE_PASSWORD,
                "email" to GtkInputPurpose.GTK_INPUT_PURPOSE_EMAIL,
                "tel" to GtkInputPurpose.GTK_INPUT_PURPOSE_PHONE,
                "url" to GtkInputPurpose.GTK_INPUT_PURPOSE_URL,
                "search" to GtkInputPurpose.GTK_INPUT_PURPOSE_FREE_FORM,
                "password" to GtkInputPurpose.GTK_INPUT_PURPOSE_PASSWORD,
                null to GtkInputPurpose.GTK_INPUT_PURPOSE_FREE_FORM,
            )
            for ((type, purpose) in variants) {
                val props = if (type == null) node.props - "type" else node.props + ("type" to type)
                node = node.copy(props = props)
                val next = reconciler.patch(mounted, node, container)
                assertSame(mounted, next)
                mounted = next
                assertEquals(if (type == "password") 0 else 1, gtk_entry_get_visibility(entry))
                assertEquals(purpose, gtk_entry_get_input_purpose(entry))
                assertEquals("secret", gtk_editable_get_text(editable)?.toKString())
            }
            assertEquals(0, inputEvents, "Changing the type must not emit input events")

            gtk_editable_set_text(editable, "unaccepted edit")
            assertTrue(inputEvents > 0, "Type changes must leave the input signal bound")
            val beforeSync = inputEvents
            mounted = reconciler.patch(mounted, node, container)
            assertEquals("secret", gtk_editable_get_text(editable)?.toKString())
            assertEquals(beforeSync, inputEvents, "Controlled-value resync must suppress input signals")
        } finally {
            reconciler.unmount(mounted, container)
            dispatcher.reset()
            runtime.dispose()
            g_object_unref(container)
        }
    }
}
