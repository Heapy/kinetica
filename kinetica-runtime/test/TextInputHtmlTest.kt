package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@UiComponent
private fun ComponentScope.DefaultHtmlTextInput() {
    textInput(
        value = "\"<hello & goodbye>'",
        placeholder = "\"<type & enter>'",
        key = "<field&1>",
    )
}

@UiComponent
private fun ComponentScope.PasswordHtmlTextInput() {
    textInput(
        value = "secret-password",
        type = TextInputType.Password,
        autocomplete = "current-password",
        onInput = {},
        onSubmit = {},
    )
}

@UiComponent
private fun ComponentScope.EmailHtmlTextInput() {
    textInput(
        value = "person@example.test",
        type = TextInputType.Email,
        autocomplete = "username\" data-extra=\"injected",
    )
}

class TextInputHtmlTest {
    @Test
    fun defaultTextInputRendersNativeVoidInputWithEscapedAttributes() {
        val html = KineticaRuntime().render {
            DefaultHtmlTextInput()
        }.tree.toSafeHtml()

        assertEquals(
            """<input type="text" value="&quot;&lt;hello &amp; goodbye&gt;&#39;" placeholder="&quot;&lt;type &amp; enter&gt;&#39;" data-kinetica-key="&lt;field&amp;1&gt;">""",
            html,
        )
    }

    @Test
    fun passwordInputOmitsValueAndEventBindingsFromHtml() {
        val html = KineticaRuntime().render {
            PasswordHtmlTextInput()
        }.tree.toSafeHtml()

        assertEquals("""<input type="password" autocomplete="current-password">""", html)
        assertFalse("secret-password" in html)
        assertFalse("event:" in html)
    }

    @Test
    fun emailInputRetainsItsValueAndEscapesAutocomplete() {
        val html = KineticaRuntime().render {
            EmailHtmlTextInput()
        }.tree.toSafeHtml()

        assertEquals(
            """<input type="email" value="person@example.test" autocomplete="username&quot; data-extra=&quot;injected">""",
            html,
        )
    }

    @Test
    fun rawPasswordPropsAreCaseInsensitiveAndNeverSerializeValue() {
        val html = HostNode(
            tag = "textInput",
            props = mapOf("TYPE" to "PaSsWoRd", "VALUE" to "secret-password"),
        ).toSafeHtml()

        assertEquals("""<input type="PaSsWoRd">""", html)
    }

    @Test
    fun ordinaryHostValueAttributesRemainIntact() {
        val html = HostNode("option", props = mapOf("value" to "keep-me")).toSafeHtml()

        assertTrue("value=\"keep-me\"" in html)
        assertEquals("""<option value="keep-me"></option>""", html)
    }
}
