package io.heapy.kinetica

import io.heapy.kinetica.forms.FieldState
import io.heapy.kinetica.forms.field
import io.heapy.kinetica.forms.formState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldStateValidationIsReactiveTest {
    @Test
    fun validateInvalidatesRenderThatReadsFieldValidationState() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var titleField: FieldState<String>

        fun render(): RenderResult =
            runtime.render(scope) {
                val form = formState()
                titleField = field(
                    form = form,
                    name = "title",
                    initial = { "" },
                    validator = { value -> "Too short".takeIf { value.length < 3 } },
                )
                text("valid=${titleField.isValid} error=${titleField.error}")
            }

        val first = render()
        assertFalse(
            first.invalidated,
            "baseline: the initial render should not already be pending re-render",
        )
        assertFalse(
            runtime.hasPendingInvalidation,
            "baseline: nothing has written a rendered cell yet",
        )

        titleField.validate()

        assertTrue(
            runtime.hasPendingInvalidation,
            "validate() changed field.error/isValid, but the render that read them was " +
                "not invalidated — validation state is exposed as plain vars, not Cells, " +
                "so the UI would show stale validation results",
        )
    }
}
