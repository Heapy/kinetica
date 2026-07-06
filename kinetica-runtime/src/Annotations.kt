package io.heapy.kinetica

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
public annotation class UiComponent(
    /** Opt out of compiler-inferred skipping for this component. */
    val skippable: Boolean = true,
)

/**
 * Asserts to the Kinetica compiler that a type is deeply immutable, so components taking it
 * may be skipped when inputs compare equal. Use only when the compiler cannot prove stability
 * itself (e.g. a `List` you guarantee is never mutated in place); a wrong assertion shows
 * stale UI.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Stable

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Preview(val name: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class ServerComponent

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class ClientComponent

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class ServerAction(
    val invalidates: Array<String> = [],
)
