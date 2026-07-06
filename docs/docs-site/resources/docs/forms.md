# Forms

<!-- code: kinetica-forms/src/Forms.kt (FormState, FieldState) -->

`kinetica-forms` models a form as reactive field cells plus suspend-friendly validation — no
special components, just state that composes with the [UI DSL](/docs/ui-dsl).

## Fields and validation

<!-- code: kinetica-forms/src/Forms.kt (formState, field, validators, required, minLength, textInput) -->

```kotlin
val form = formState()

val email = field(form, "email", initial = { "" }) { value ->
    validators(required(), minLength(3)).validate(value)
}
val password = field(form, "password", initial = { "" }) { value ->
    required().validate(value)
}

textInput(email, placeholder = "Email")        // bound input: writes + touches the field
textInput(password, placeholder = "Password")

if (form.errors().isNotEmpty()) {
    each(form.errors().entries.toList(), key = { it.key }) { (name, message) ->
        text("$name: $message")
    }
}
```

A validator is `suspend (T) -> String?` — return an error message or null. Because validation is
`suspend`, a uniqueness check against a server is the same shape as `required()`. Combine
validators with `validators(a, b, …)` — first failure wins.

## Field state is reactive

<!-- code: kinetica-forms/src/Forms.kt (FieldState, getValue, setValue) -->

`FieldState` exposes `error`, `isTouched`, `isValidating`, `isDirty`, `isValid` — all backed by
cells, so rendering them subscribes and validation results update the UI without plumbing.

```kotlin
var emailValue by email        // property delegation onto the field's value cell
```

## Try it

<!-- code: docs/docs-client/src/main.kt (FormSignupExample) -->

::: example form-signup

The form above is `formState()` + two `field`s with validators, a bound `textInput` per field,
and `form.submit {}` behind the button — running in your browser against this page's bundle.

## Submission semantics

<!-- code: kinetica-forms/src/Forms.kt (FormState.submit, validate, isValid, reset) -->

```kotlin
val submitted = form.submit {
    api.login(email.value.value, password.value.value)
}
```

`submit {}` (suspend): bumps `submitCount`, validates **all** fields, runs the block only when
everything passes, and returns whether it ran. `isSubmitting` is true for the duration —
disable the button with it.

One deliberate rule: **a never-validated form is not valid.** `form.isValid` requires every
field to have successfully validated at least once — an untouched required field cannot slip
through an `if (form.isValid) submit()` guard.

`form.reset()` returns every field to its initial value and clears validation state.

## Binding to plain vars

<!-- code: kinetica-forms/src/Forms.kt (textInput KMutableProperty0 overload) -->

For quick cases there is a `textInput` overload bound to any `var` via property reference:

```kotlin
var query by state { "" }
textInput(::query, placeholder = "Search")
```
