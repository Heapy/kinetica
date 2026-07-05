package io.heapy.kinetica

import io.heapy.kinetica.forms.FieldState
import io.heapy.kinetica.forms.field
import io.heapy.kinetica.forms.formState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R10 — Forms `FieldState` exposes validation state (`error`, `isValid`, `isTouched`,
 * `isValidating`, `hasValidated`) as plain `var`s instead of Cells.
 *
 * A real component renders validation UI by reading these properties. Because they are
 * plain vars, reading them inside a render records no read-tracking dependency, so the
 * render never re-runs when `validate()` mutates them — the validation UI goes stale.
 *
 * This test asserts the DESIRED behavior: a render that reads `field.isValid` /
 * `field.error` must be invalidated when `validate()` changes those values. On today's
 * buggy code no dependency is recorded, so `validate()` invalidates nothing and this
 * assertion fails.
 */
class FieldStateValidationIsReactiveTest {
    @Test
    fun validateInvalidatesRenderThatReadsFieldValidationState() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var titleField: FieldState<String>

        // A component that renders validation UI derived from field.isValid / field.error.
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

        // Initial render establishes read-tracking subscriptions for whatever the
        // render read (which, correctly, should include the validation state).
        val first = render()
        assertFalse(
            first.invalidated,
            "baseline: the initial render should not already be pending re-render",
        )
        assertFalse(
            runtime.hasPendingInvalidation,
            "baseline: nothing has written a rendered cell yet",
        )

        // The field value is "" -> the validator returns "Too short", so validate()
        // changes error (null -> "Too short") and hasValidated (false -> true).
        titleField.validate()

        // DESIRED behavior: because the render read field.isValid / field.error, the
        // mutation performed by validate() must invalidate the runtime so the stale
        // validation UI re-renders. Fails on buggy code because those reads recorded no
        // reactive dependency.
        assertTrue(
            runtime.hasPendingInvalidation,
            "validate() changed field.error/isValid, but the render that read them was " +
                "not invalidated — validation state is exposed as plain vars, not Cells, " +
                "so the UI would show stale validation results",
        )
    }
}
