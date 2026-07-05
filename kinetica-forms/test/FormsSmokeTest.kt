package io.heapy.kinetica.forms

import io.heapy.kinetica.ClientRef
import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Node
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.text
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormsSmokeTest {
    @Test
    fun textInputEchoesFieldStateAndSuspendValidationControlsSubmission() = runTest {
        val runtime = KineticaRuntime()
        val scope = io.heapy.kinetica.ComponentScope(runtime)
        lateinit var form: FormState
        lateinit var title: FieldState<String>
        var submittedTitle: String? = null

        fun render(): Node = runtime.render(scope) {
            form = formState()
            title = field(
                form = form,
                name = "title",
                initial = { "" },
                validator = { value ->
                    "Too short".takeIf { value.length < 3 }
                },
            )
            textInput(
                field = title,
                placeholder = "Title",
                semantics = Semantics(role = Role.TextInput, focusable = true, testTag = "title"),
            )
            text("dirty=${form.isDirty}")
        }.tree

        val firstInput = render().findHostByTag("textInput")
        assertEquals("", firstInput.props.getValue("value"))
        assertFalse(form.isDirty)
        assertFalse(form.isValid)
        assertFalse(form.isValidating)
        assertEquals(setOf("title"), form.fields().keys)
        assertEquals(emptyMap(), form.errors())

        runtime.dispatch(firstInput.props.getValue("event:onInput"), "hi")
        val shortInput = render().findHostByTag("textInput")
        assertEquals("hi", shortInput.props.getValue("value"))
        assertTrue(form.isDirty)
        assertTrue(title.isTouched)
        assertFalse(form.validate())
        assertEquals(mapOf("title" to "Too short"), form.errors())

        runtime.dispatch(shortInput.props.getValue("event:onInput"), "done")
        render()
        assertTrue(
            form.submit {
                submittedTitle = title.value.value
            },
        )
        assertEquals("done", submittedTitle)
        assertEquals(1, form.submitCount)
        assertTrue(form.isValid)

        form.reset()
        assertEquals("", title.value.value)
        assertFalse(title.isTouched)
        assertFalse(form.isDirty)
    }

    @Test
    fun fieldStateDelegatesAndPropertyReferenceTextInputEchoesSynchronously() {
        val runtime = KineticaRuntime()
        val scope = io.heapy.kinetica.ComponentScope(runtime)
        lateinit var draftField: FieldState<String>
        lateinit var form: FormState
        val model = DraftModel()

        fun renderField(): Node = runtime.render(scope) {
            form = formState(key = "delegate-form")
            draftField = field(
                form = form,
                name = "draft",
                initial = { "" },
            )
            text("draft=${draftField.value.value}")
        }.tree

        renderField()
        var draft by draftField
        assertEquals("", draft)
        draft = "delegated"
        renderField()
        assertEquals("delegated", draftField.value.value)
        assertTrue(form.isDirty)

        fun renderInput(): HostNode =
            runtime.render(scope) {
                textInput(
                    value = model::draft,
                    placeholder = "Draft",
                    semantics = Semantics(role = Role.TextInput, focusable = true, testTag = "draft"),
                )
            }.tree.findHostByTag("textInput")

        val input = renderInput()
        assertEquals("", input.props.getValue("value"))

        runtime.dispatch(input.props.getValue("event:onInput"), "echo")
        val echoed = renderInput()
        assertEquals("echo", model.draft)
        assertEquals("echo", echoed.props.getValue("value"))
    }

    @Test
    fun formSubmissionTracksInvalidAttemptsAndValidationLifecycle() = runTest {
        val runtime = KineticaRuntime()
        val scope = io.heapy.kinetica.ComponentScope(runtime)
        lateinit var form: FormState
        lateinit var title: FieldState<String>
        var observedValidating = false
        var observedSubmitting = false

        runtime.render(scope) {
            form = formState(key = "strict-form")
            title = field(
                form = form,
                name = "title",
                initial = { "" },
                validator = { value ->
                    observedValidating = observedValidating || title.isValidating || form.isValidating
                    validators(required("Required"), minLength(4)).validate(value)
                },
            )
            textInput(field = title)
        }

        assertFalse(
            form.submit {
                error("Invalid forms must not run submit blocks")
            },
        )
        assertEquals(1, form.submitCount)
        assertEquals(mapOf("title" to "Required"), form.errors())
        assertTrue(observedValidating)
        assertFalse(form.isSubmitting)

        title.set("abc")
        assertFalse(form.validate())
        assertEquals(mapOf("title" to "Must be at least 4 characters"), form.errors())

        title.set("abcd")
        assertTrue(
            form.submit {
                observedSubmitting = isSubmitting
            },
        )
        assertTrue(observedSubmitting)
        assertEquals(2, form.submitCount)
        assertTrue(form.isValid)
        assertFalse(form.isSubmitting)

        title.set("dirty")
        assertTrue(title.isDirty)
        form.reset()
        assertEquals("", title.value.value)
        assertFalse(title.isTouched)
        assertFalse(title.isDirty)
        assertEquals(emptyMap(), form.errors())

        assertFailsWith<IllegalArgumentException> {
            minLength(-1)
        }
        assertEquals("Required", required().validate(""))
    }

    @Test
    fun textInputAndValidatorsUseDocumentedDefaults() = runTest {
        val runtime = KineticaRuntime()
        val scope = io.heapy.kinetica.ComponentScope(runtime)
        val model = DraftModel()

        fun renderInput(): HostNode =
            runtime.render(scope) {
                textInput(value = model::draft)
            }.tree.findHostByTag("textInput")

        val input = renderInput()
        assertEquals("", input.props.getValue("value"))
        assertEquals(null, input.props["placeholder"])
        assertEquals(Semantics(role = Role.TextInput, focusable = true), input.semantics)

        runtime.dispatch(input.props.getValue("event:onInput"), "defaulted")
        assertEquals("defaulted", model.draft)
        assertEquals("defaulted", renderInput().props.getValue("value"))

        assertEquals("Required", validators(required()).validate(""))
        assertEquals(null, validators(required(), minLength(3)).validate("abcd"))
    }
}

private class DraftModel {
    var draft: String = ""
}

private fun Node.findHostByTag(tag: String): HostNode = when (this) {
    is HostNode -> if (this.tag == tag) this else children.firstNotNullOf { it.findHostByTagOrNull(tag) }
    is FragmentNode -> children.firstNotNullOf { it.findHostByTagOrNull(tag) }
    is TextNode -> error("No host node with tag $tag")
    is ClientRef -> error("No host node with tag $tag")
}

private fun Node.findHostByTagOrNull(tag: String): HostNode? = when (this) {
    is HostNode -> if (this.tag == tag) this else children.firstNotNullOfOrNull { it.findHostByTagOrNull(tag) }
    is FragmentNode -> children.firstNotNullOfOrNull { it.findHostByTagOrNull(tag) }
    is TextNode -> null
    is ClientRef -> null
}
