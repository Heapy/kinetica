package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TextInputTest {
    @Test
    fun textInputOptionsPreserveInputAndSubmitHandlers() {
        val runtime = KineticaRuntime()
        var value = "initial"
        var submissions = 0

        fun render(): HostNode = runtime.render {
            TextInputOptionsProbe(
                value = value,
                onInput = { value = it },
                onSubmit = { submissions++ },
                type = TextInputType.Password,
                autocomplete = "current-password",
            )
        }.tree as HostNode

        val input = render()
        assertEquals("password", input.props["type"])
        assertEquals("current-password", input.props["autocomplete"])
        assertEquals("Password", input.props["placeholder"])
        assertEquals("password", input.key)
        runtime.dispatch(input.props.getValue("event:onInput"), "updated")
        val updated = render()
        assertEquals("updated", updated.props["value"])
        runtime.dispatch(updated.props.getValue("event:onSubmit"))
        assertEquals(1, submissions)
    }

    @Test
    fun allTextInputTypesUseTheirHtmlValues() {
        val expected = mapOf(
            TextInputType.Text to null,
            TextInputType.Email to "email",
            TextInputType.Password to "password",
            TextInputType.Search to "search",
            TextInputType.Telephone to "tel",
            TextInputType.Url to "url",
        )
        for ((type, htmlType) in expected) {
            val input = KineticaRuntime().render {
                TextInputOptionsProbe(value = "", type = type)
            }.tree as HostNode
            assertEquals(htmlType, input.props["type"])
            assertFalse("autocomplete" in input.props)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.TextInputOptionsProbe(
    value: String,
    type: TextInputType,
    onInput: ((String) -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
    autocomplete: String? = null,
) {
    textInput(
        value = value,
        onInput = onInput,
        onSubmit = onSubmit,
        placeholder = "Password",
        key = "password",
        type = type,
        autocomplete = autocomplete,
    )
}
