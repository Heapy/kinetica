package io.heapy.kinetica.forms

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.MutableCell
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.state
import io.heapy.kinetica.store
import io.heapy.kinetica.textInput
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty

public fun interface FieldValidator<T> {
    public suspend fun validate(value: T): String?
}

public class FormState internal constructor() {
    private val fields: MutableMap<String, FieldState<*>> = mutableMapOf()

    public val isValid: Boolean
        get() = fields.values.all { it.isValid }

    public val isDirty: Boolean
        get() = fields.values.any { it.isDirty }

    public val isValidating: Boolean
        get() = fields.values.any { it.isValidating }

    public var submitCount: Int = 0
        private set

    public var isSubmitting: Boolean = false
        private set

    public fun fields(): Map<String, FieldState<*>> = fields.toMap()

    public fun errors(): Map<String, String> =
        fields.values.mapNotNull { field ->
            field.error?.let { error -> field.name to error }
        }.toMap()

    public suspend fun validate(): Boolean {
        val results = fields.values.map { it.validate() }
        return results.all { it }
    }

    public suspend fun submit(block: suspend FormState.() -> Unit): Boolean {
        submitCount += 1
        if (!validate()) {
            return false
        }
        isSubmitting = true
        return try {
            block()
            true
        } finally {
            isSubmitting = false
        }
    }

    public fun reset() {
        fields.values.forEach { it.reset() }
    }

    /**
     * Per-field identity lives here, not in slots: the field's cells are created once per
     * (form, name) with scope-free [store] cells, so field registration is legal anywhere —
     * including loops — and a field's lifetime is exactly its form's lifetime.
     */
    internal fun <T> field(
        name: String,
        initial: () -> T,
        validator: FieldValidator<T>,
    ): FieldState<T> {
        val existing = fields[name]
        if (existing != null) {
            @Suppress("UNCHECKED_CAST")
            val field = existing as FieldState<T>
            field.updateValidator(validator)
            return field
        }
        val initialValue = initial()
        val created = FieldState(name, store(initialValue), initialValue, validator)
        fields[name] = created
        return created
    }
}

public class FieldState<T> internal constructor(
    public val name: String,
    public val value: MutableCell<T>,
    private val initialValue: T,
    private var validator: FieldValidator<T>,
) {
    // Validation state is backed by reactive cells so that reads performed inside a
    // render record a read-tracking dependency and mutations (set/reset/validate)
    // invalidate the render — otherwise the validation UI would go stale.
    private val errorCell: MutableCell<String?> = store(null)
    private val hasValidatedCell: MutableCell<Boolean> = store(false)
    private val isTouchedCell: MutableCell<Boolean> = store(false)
    private val isValidatingCell: MutableCell<Boolean> = store(false)

    public val error: String?
        get() = errorCell.value

    private val hasValidated: Boolean
        get() = hasValidatedCell.value

    public val isTouched: Boolean
        get() = isTouchedCell.value

    public val isValidating: Boolean
        get() = isValidatingCell.value

    public val isValid: Boolean
        get() = hasValidated && error == null

    public val isDirty: Boolean
        get() = value.value != initialValue

    public fun set(next: T) {
        value.value = next
        isTouchedCell.value = true
        errorCell.value = null
        hasValidatedCell.value = false
    }

    public fun reset() {
        value.value = initialValue
        isTouchedCell.value = false
        errorCell.value = null
        hasValidatedCell.value = false
    }

    public suspend fun validate(): Boolean {
        isTouchedCell.value = true
        isValidatingCell.value = true
        return try {
            val result = validator.validate(value.value)
            errorCell.value = result
            hasValidatedCell.value = true
            result == null
        } finally {
            isValidatingCell.value = false
        }
    }

    internal fun updateValidator(validator: FieldValidator<T>) {
        this.validator = validator
    }
}

public operator fun <T> FieldState<T>.getValue(
    thisRef: Any?,
    property: KProperty<*>,
): T =
    value.value

public operator fun <T> FieldState<T>.setValue(
    thisRef: Any?,
    property: KProperty<*>,
    next: T,
) {
    set(next)
}

/**
 * A hook holding the whole form in one slot at this call site. Distinct forms come from
 * distinct call sites (or a surrounding `keyed {}`), not string keys: per-field identity
 * lives inside [FormState], keyed by field name.
 */
@UiComponent
public fun ComponentScope.formState(): FormState =
    state { FormState() }.value

/**
 * Registers (or retrieves) the [name] field of [form]. Slot-free: field cells live inside
 * the form, so this may be called anywhere the form is in hand, loops included. The
 * [validator] is refreshed every call so it can capture current render inputs;
 * [initial] runs only when the field is first created.
 */
public fun <T> ComponentScope.field(
    form: FormState,
    name: String,
    initial: () -> T,
    validator: suspend (T) -> String? = { null },
): FieldState<T> =
    form.field(name, initial, FieldValidator { value -> validator(value) })

@UiComponent
public fun ComponentScope.textInput(
    field: FieldState<String>,
    placeholder: String? = null,
    semantics: Semantics? = Semantics(role = Role.TextInput, focusable = true),
    key: Any? = field.name,
) {
    textInput(
        value = field.value.value,
        onInput = field::set,
        placeholder = placeholder,
        semantics = semantics,
        key = key,
    )
}

@UiComponent
public fun ComponentScope.textInput(
    value: KMutableProperty0<String>,
    placeholder: String? = null,
    semantics: Semantics? = Semantics(role = Role.TextInput, focusable = true),
    key: Any? = value.name,
) {
    textInput(
        value = value.get(),
        onInput = value::set,
        placeholder = placeholder,
        semantics = semantics,
        key = key,
    )
}

public fun required(message: String = "Required"): FieldValidator<String> =
    FieldValidator { value -> message.takeIf { value.isBlank() } }

public fun minLength(length: Int, message: String = "Must be at least $length characters"): FieldValidator<String> {
    require(length >= 0) { "length must be non-negative." }
    return FieldValidator { value -> message.takeIf { value.length < length } }
}

public fun <T> validators(vararg validators: FieldValidator<T>): FieldValidator<T> =
    FieldValidator { value ->
        validators.firstNotNullOfOrNull { validator -> validator.validate(value) }
    }
