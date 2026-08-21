package io.heapy.kinetica.compiler

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KineticaFirCheckerTest {
    private val harness = KineticaCompilationHarness()

    @Test
    fun ruleA_slotCallOutsideComponentIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.state

                    fun ComponentScope.helper() {
                        val count = state { 0 }
                        count.value
                    }
                """,
            ),
        ).assertContainsError("'state' can only be called inside a @UiComponent function")
    }

    @Test
    fun ruleA_slotCallInsideComponentCompiles() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Counter() {
                        val count = state { 0 }
                        emit(TextNode(value = "count: " + count.value))
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun ruleA_slotCallInLocalClassInitializerIsReported() {
        // A local class body is reusable even though its declaration is lexically inside
        // the component. The IR walker used to number this initializer with Screen's one
        // static slot; constructing Box twice then rendered values=1,1 instead of 1,2.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Screen() {
                        val scope = this
                        class Box(seed: Int) {
                            val value = scope.state { seed }.value
                        }
                        val first = Box(1)
                        val second = Box(2)
                        text("values=" + first.value + "," + second.value)
                    }
                """,
            ),
        ).assertContainsError("'state' can only be called inside a @UiComponent function")
    }

    @Test
    fun ruleA_slotCallInAnonymousObjectInitializerCompiles() {
        // Unlike a named local class, an anonymous object is constructed at this exact
        // expression site while the component frame is active. Its initializer cannot be
        // invoked independently, so the outer component owns the ordinal legitimately.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Screen() {
                        val scope = this
                        val box = object {
                            val value = scope.state { 1 }.value
                        }
                        text(box.value.toString())
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun ruleA_slotCallInNestedPropertyAccessorIsReported() {
        // A property accessor is a FirFunction but not a FirNamedFunction. Ignoring that
        // boundary let a getter run repeatedly or after render with an ordinal borrowed
        // from the enclosing component frame.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Screen() {
                        val scope = this
                        val box = object {
                            val value: Int
                                get() = scope.state { 1 }.value
                        }
                        text(box.value.toString())
                    }
                """,
            ),
        ).assertContainsError("'state' can only be called inside a @UiComponent function")
    }

    @Test
    fun ruleA_slotCallInNestedConstructorIsReported() {
        // Constructor bodies are reusable FirFunction boundaries. Numbering this call
        // lexically would make two Box instances read the same slot in Screen's frame.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Screen() {
                        val scope = this
                        class Box {
                            val value: Int

                            constructor(seed: Int) {
                                value = scope.state { seed }.value
                            }
                        }
                        text((Box(1).value + Box(2).value).toString())
                    }
                """,
            ),
        ).assertContainsError("'state' can only be called inside a @UiComponent function")
    }

    @Test
    fun ruleA_slotCallInRenderContentLambdaIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    fun runContent(scope: ComponentScope, content: @UiComponent ComponentScope.() -> Unit) {
                        scope.content()
                    }

                    fun main(scope: ComponentScope) {
                        runContent(scope) {
                            val count = state { 0 }
                            count.value
                        }
                    }
                """,
            ),
        ).assertContainsError("'state' can only be called inside a @UiComponent function")
    }

    @Test
    fun ruleB_componentCallFromPlainFunctionIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    fun ComponentScope.plainHelper() {
                        Badge()
                    }
                """,
            ),
        ).assertContainsError("can only be called from a @UiComponent function")
    }

    @Test
    fun ruleB_componentCallFromComponentAndFromAnnotatedLambdaCompiles() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    @UiComponent
                    fun ComponentScope.Panel() {
                        Badge()
                    }

                    fun runContent(scope: ComponentScope, content: @UiComponent ComponentScope.() -> Unit) {
                        scope.content()
                    }

                    fun entry(scope: ComponentScope) {
                        runContent(scope) {
                            Badge()
                        }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun ruleC_regionContentReferenceIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Panel() {
                        val body: ComponentScope.() -> Unit = {}
                        keyed("tab", content = body)
                    }
                """,
            ),
        ).assertContainsError("must be a lambda literal")
    }

    @Test
    fun ruleC_hoistedComponentContentArgumentIsReported() {
        // F8 probe: content hoisted into a local val bypasses IR's literal-only wrapping
        // (wrapAnnotatedContentArgumentsOf), so Badge() is never staged and the first
        // render throws MissingKineticaPluginException. Must be a compile error instead.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    fun main(runtime: KineticaRuntime) {
                        val content: @UiComponent ComponentScope.() -> Unit = { Badge() }
                        listOf(1, 2).forEach { runtime.render(content) }
                    }
                """,
            ),
        ).assertContainsError("must be a lambda literal")
    }

    @Test
    fun ruleC_localFunctionRenderWrapperCompiles() {
        // The findings' F8 mirror image: wrapping the entry-point render in a nested
        // local fun used to compile only by accident (multiRunLambdaHost stopped at the
        // FirNamedFunction). After Task 4, entry points are not ordinal consumers, so
        // this compiles for a deliberate reason — pinned here. The content stays a
        // lambda literal at the render call, so IR wraps it normally.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    fun entry(runtime: KineticaRuntime, scope: ComponentScope) {
                        fun render() = runtime.render(scope) { Badge() }
                        listOf(1, 2).forEach { render() }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun ruleC_literalComponentContentArgumentsCompile() {
        // Literal content lambdas are exactly what IR wraps — entry points and user
        // content-wrapper helpers taking literals must stay unaffected by the F8 rule.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    fun runContent(scope: ComponentScope, content: @UiComponent ComponentScope.() -> Unit) {
                        scope.content()
                    }

                    fun entry(runtime: KineticaRuntime, scope: ComponentScope) {
                        runtime.render(scope) { Badge() }
                        runContent(scope) { Badge() }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun ruleC_forwardedContentValuesRemainAllowed() {
        // Sound forwarding shapes the framework itself relies on (KineticaRuntime.render
        // single-arg overload, HeadlessTestRoot, EachKeyedFlagTest): the value arriving
        // at a @UiComponent-typed parameter was frame-wrapped at its literal site, so a
        // read of a value parameter or a non-local property must stay allowed. Only the
        // locally hoisted lambda (F8 probe) is provably never wrapped.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    // Value-parameter forward, annotated to annotated (KineticaRuntime.render shape).
                    fun forward(runtime: KineticaRuntime, scope: ComponentScope, content: @UiComponent ComponentScope.() -> Unit) {
                        runtime.render(scope, content)
                    }

                    // Member-property forward, unannotated property type, filled from an
                    // annotated value parameter (KineticaTest.render / HeadlessTestRoot shape).
                    class Root(private val content: ComponentScope.() -> Unit) {
                        fun render(runtime: KineticaRuntime, scope: ComponentScope) {
                            runtime.render(scope, content)
                        }
                    }

                    fun mount(runtime: KineticaRuntime, scope: ComponentScope, content: @UiComponent ComponentScope.() -> Unit) {
                        Root(content).render(runtime, scope)
                    }

                    // Local fun whose unannotated parameter forwards into the annotated
                    // render parameter (EachKeyedFlagTest.renderFlags shape).
                    fun entry(runtime: KineticaRuntime, scope: ComponentScope) {
                        fun renderBody(body: ComponentScope.() -> Unit) {
                            runtime.render(scope, body)
                        }
                        renderBody { text("body") }
                        forward(runtime, scope) { Badge() }
                        mount(runtime, scope) { Badge() }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun ruleD_slotCallInLoopIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Rows() {
                        for (index in 0..2) {
                            val row = state { index }
                            row.value
                        }
                    }
                """,
            ),
        ).assertContainsError("must not be called directly inside a loop")
    }

    @Test
    fun ruleD_keyedWrappedLoopBodyCompiles() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Rows() {
                        for (index in 0..2) {
                            keyed(index) {
                                val row = state { index }
                                emit(TextNode(value = "row " + row.value))
                            }
                        }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun ruleD_componentCallInLoopIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    @UiComponent
                    fun ComponentScope.Rows() {
                        for (index in 0..1) {
                            Badge()
                        }
                    }
                """,
            ),
        ).assertContainsError("'Badge' must not be called directly inside a loop")
    }

    @Test
    fun localComponentFunctionCallsInRepeatedContextsAreReported() {
        // F11 pin: the ordinal-consumer verdict for a component call must come from the
        // @UiComponent annotation alone, never gated behind a callableId bail. The exact
        // reviewed shape — a component callee with a NULL callableId — is inexpressible
        // in Kotlin 2.4.10 (FirFunctionSymbol stores a non-null CallableId; locals get
        // the `<local>` package, and only FirLocalPropertySymbol returns null, which is
        // never a call callee), so this probe was green even before the F11 reorder. It
        // pins the nearest expressible shape — a `<local>`-addressed component call in
        // repeated contexts — and becomes the real red probe if a future toolchain nulls
        // local callableIds.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Rows(items: List<String>) {
                        @UiComponent
                        fun ComponentScope.LocalBadge() {
                            emit(TextNode(value = "local"))
                        }
                        items.forEach {
                            LocalBadge()
                        }
                        for (index in 0..1) {
                            LocalBadge()
                        }
                    }
                """,
            ),
        )
        messages.assertContainsError(multiRunMessage("LocalBadge", "forEach"))
        messages.assertContainsError("'LocalBadge' must not be called directly inside a loop")
    }

    @Test
    fun localComponentFunctionDeclarationIsReported() {
        // A local @UiComponent function can never be framed — IR's framing pass collects
        // only file- and class-level functions — so two direct calls silently share ONE
        // state cell numbered into the ENCLOSING component's region (probe-verified:
        // "inner:1" twice) while each call leaks a staged ordinal. The declaration
        // itself is the soundness violation, reported regardless of call shape.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Rows() {
                        @UiComponent
                        fun ComponentScope.Inner() {
                            val id = state { 0 }
                            text("inner:" + id.value)
                        }
                        Inner()
                        Inner()
                    }
                """,
            ),
        ).assertContainsError("@UiComponent function 'Inner' is declared locally")
    }

    @Test
    fun ruleC_contentLiteralsOutsideArgumentPositionCarryNoOrdinalConsumers() {
        // Why rule C may exempt reads of non-local properties: the compiler wraps a
        // content lambda at its LITERAL site, and it only reaches literals in argument
        // position (including inside property initializers, since the framing collection
        // now visits those). A literal that is a property initializer or a return value
        // is never wrapped — but it also cannot carry an ordinal consumer, because FIR
        // drops the @UiComponent annotation from its inferred type and rules A and B
        // reject slot and component calls inside it. Both non-argument positions are
        // pinned here: if a future toolchain preserved the annotation there, this test
        // turns green and the exemption would need a declaration-site rule.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        val id = state { 0 }
                        text("id:" + id.value)
                    }

                    val stored: @UiComponent ComponentScope.() -> Unit = { Badge() }

                    fun returned(): @UiComponent ComponentScope.() -> Unit = { Badge() }
                """,
            ),
        )
        assertEquals(
            2,
            messages.count {
                it.severity.isError &&
                    "@UiComponent function 'Badge' can only be called" in it.message
            },
            "Both non-argument content literals must be rejected. Messages:\n" +
                messages.joinToString("\n") { "${it.severity}: ${it.message}" },
        )
    }

    @Test
    fun componentInObjectLiteralInsideAccessorOrLambdaIsReported() {
        // LOCAL_COMPONENT_FUNCTION must be the exact complement of what the IR framing
        // pass collects: everything reachable from the file WITHOUT crossing a function
        // body. A property getter and a lambda are function bodies, so an @UiComponent
        // declared in an object literal there is unreachable for framing and must be
        // rejected — where the same literal in a plain property initializer, an
        // `init { }` block or an enum-entry body is framed and stays legal
        // (componentsInInitBlockAndDelegateInitializerAreFramed and friends pin that
        // half in KineticaIrFrameCompileTest).
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    class Screen {
                        val fromGetter: Any get() = object {
                            @UiComponent
                            fun ComponentScope.InGetter() {
                                val id = state { 0 }
                                text("getter:" + id.value)
                            }
                        }
                    }

                    val fromLambda: Any = run {
                        object {
                            @UiComponent
                            fun ComponentScope.InLambda() {
                                val id = state { 0 }
                                text("lambda:" + id.value)
                            }
                        }
                    }
                """,
            ),
        )
        messages.assertContainsError("@UiComponent function 'InGetter' is declared locally")
        messages.assertContainsError("@UiComponent function 'InLambda' is declared locally")
    }

    @Test
    fun componentContentWithoutScopeReceiverIsReported() {
        // IR wraps content lambdas by their ComponentScope extension receiver; a literal
        // bound to a receiver-less @UiComponent function type is declined (probe-verified
        // pre-fix: compiles clean at checks=error, throws MissingKineticaPluginException
        // at first render), so the shape must fail here instead.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    fun host(scope: ComponentScope, content: @UiComponent (ComponentScope) -> Unit) {
                        content(scope)
                    }

                    fun bare(content: @UiComponent () -> Unit) {
                        content()
                    }

                    fun entry(scope: ComponentScope) {
                        host(scope) { s -> s.text("x") }
                        bare { }
                    }
                """,
            ),
        )
        messages.assertContainsError(
            "The 'content' parameter's @UiComponent function type has no " +
                "io.heapy.kinetica.ComponentScope receiver",
        )
    }

    @Test
    fun componentContentWithScopeReceiverFormsCompiles() {
        // The two shapes IR provably wraps: the plain ComponentScope receiver and the
        // suspend receiver form (`renderSuspend`'s parameter type) — the new rule must
        // not reject either.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    fun plain(content: @UiComponent ComponentScope.() -> Unit) {
                    }

                    fun suspending(content: @UiComponent (suspend ComponentScope.() -> Unit)) {
                    }

                    fun entry() {
                        plain { text("p") }
                        suspending { text("s") }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun ruleC_functionReferenceContentArgumentIsReported() {
        // isWrappableContentArgument rejects callable references: IR can only wrap
        // lambda LITERALS, and a reference has no literal site anywhere.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.text

                    fun body(scope: ComponentScope) {
                        scope.text("ref")
                    }

                    fun entry(runtime: KineticaRuntime, scope: ComponentScope) {
                        runtime.render(scope, ::body)
                    }
                """,
            ),
        ).assertContainsError("must be a lambda literal")
    }

    @Test
    fun ruleC_callResultContentArgumentIsReported() {
        // isWrappableContentArgument rejects call results: the returned lambda's literal
        // site (if any) is in another function's body where IR wrapped nothing for THIS
        // parameter, so the value is not provably frame-wrapped.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.text

                    fun makeContent(): ComponentScope.() -> Unit = { text("made") }

                    fun entry(runtime: KineticaRuntime, scope: ComponentScope) {
                        runtime.render(scope, makeContent())
                    }
                """,
            ),
        ).assertContainsError("must be a lambda literal")
    }

    @Test
    fun ruleC_hoistedConstructorContentArgumentIsReported() {
        // Constructor calls are FirFunctionCalls, so rule C's local-hoist rejection
        // applies to @UiComponent constructor parameters exactly like function calls —
        // pinned because IR now frame-wraps constructor content literals and rule C is
        // what keeps the unwrappable non-literal shapes out.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent

                    class Holder(val content: @UiComponent ComponentScope.() -> Unit)

                    fun entry() {
                        val hoisted: ComponentScope.() -> Unit = { }
                        Holder(hoisted)
                    }
                """,
            ),
        ).assertContainsError("must be a lambda literal")
    }

    @Test
    fun ruleE_componentWithoutScopeReceiverIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun Standalone() {
                    }
                """,
            ),
        ).assertContainsError("must be an extension of io.heapy.kinetica.ComponentScope")
    }

    @Test
    fun checksOffKeepsSlotCallOutsideComponentAnError() {
        // S1: rule A is a soundness rule — IR never numbers slot calls outside framed
        // components, so this helper throws MissingKineticaPluginException at runtime.
        // `checks` governs style diagnostics only and must not remove it.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.state

                    fun ComponentScope.helper() {
                        val count = state { 0 }
                        count.value
                    }
                """,
            ),
            checks = "off",
        ).assertContainsError("'state' can only be called inside a @UiComponent function")
    }

    @Test
    fun multiRunSlotCallNamesTheActualUnsafeHost() {
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Fan() {
                        repeat(2) { outer ->
                            listOf(outer).map { index ->
                                val value = state { index }
                                value.value
                            }
                        }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("state", "repeat"))
    }

    @Test
    fun multiRunNestedOrdinalCallsReportOnlyTheOutermostConsumer() {
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.button
                    import io.heapy.kinetica.event
                    import io.heapy.kinetica.row

                    @UiComponent
                    fun ComponentScope.Fan() {
                        listOf(1, 2).forEach { item ->
                            row { button(onClick = event { println(item) }) {} }
                        }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("button", "forEach"))
    }

    @Test
    fun multiRunReportSurvivesOuterConsumerRuleAEarlyExit() {
        // F14: the outer `state` exits check() at rule A (entry content is not a
        // component body) and never reaches rule F, so its report says nothing about
        // the multi-run hazard. Suppression must key on the reports that actually
        // happened, not on "the enclosing call is an ordinal consumer" — before the
        // fix, Badge's CALL_IN_MULTI_RUN_LAMBDA vanished here. The rule-B flavor of
        // this early exit is inexpressible: restoring the inner call's containment
        // needs a component-typed lambda between outer and inner, and that same
        // lambda is a numbering boundary that ends the inner call's rule-F walk.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    fun runContent(scope: ComponentScope, content: @UiComponent ComponentScope.() -> Unit) {
                        scope.content()
                    }

                    fun entry(scope: ComponentScope) {
                        runContent(scope) {
                            listOf(1, 2).forEach { item ->
                                state {
                                    Badge()
                                    item
                                }
                            }
                        }
                    }
                """,
            ),
        )

        messages.assertErrorMessages(
            "'state' can only be called inside a @UiComponent function. " +
                "Move the call into a @UiComponent, or annotate the enclosing function.",
            multiRunMessage("Badge", "forEach"),
        )
    }

    @Test
    fun multiRunReportSurvivesOuterConsumerRuleHEarlyExit() {
        // F14, rule-H flavor: the outer Badge call exits check() at rule H (non-simple
        // receiver) without ever reporting CALL_IN_MULTI_RUN_LAMBDA — it must not
        // suppress the nested consumer's rule-F report inside its own argument.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge(label: String) {
                        text(label)
                    }

                    @UiComponent
                    fun ComponentScope.Fan(panes: List<ComponentScope>, items: List<Int>) {
                        panes.first().Badge(
                            label = items.map { state { it }.value.toString() }.joinToString(),
                        )
                    }
                """,
            ),
        )
        messages.assertErrorMessages(
            "@UiComponent call 'Badge' must be invoked on a simple receiver — 'this', a parameter, " +
                "or a plain local val — so the compiler can stage the child frame ordinal. " +
                "Call-result, safe-call, and smart-cast receivers are left unstaged and fail at " +
                "first render. Bind the receiver to a plain local val of the scope type first.",
            multiRunMessage("state", "map"),
        )
    }

    @Test
    fun multiRunRegionAndComponentCallsAreReported() {
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.each
                    import io.heapy.kinetica.errorBoundary

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    @UiComponent
                    fun ComponentScope.Fan() {
                        listOf(1).forEach {
                            errorBoundary(fallback = { _, _, _ -> }) {}
                        }
                        listOf(1).map {
                            Badge()
                        }
                        listOf(listOf(1)).forEach { items ->
                            each(items, key = { it }) {}
                        }
                    }
                """,
            ),
        )

        messages.assertErrorMessages(
            multiRunMessage("errorBoundary", "forEach"),
            multiRunMessage("Badge", "map"),
            multiRunKeyedRegionMessage("each", "forEach"),
        )
    }

    @Test
    fun multiRunKeyedConstructGetsHoistAdviceInsteadOfEachKeyedAdvice() {
        // S2: when the flagged call IS the keyed construct, "Use each(...) or keyed(...)"
        // is the construct the author already wrote — the advice must suggest hoisting
        // the call out of the multi-run lambda or keying the OUTER repetition instead.
        // (S2's other wrong-advice case — a `render` entry point — cannot arise anymore:
        // entry points stopped being ordinal consumers in Task 4/F1, pinned by
        // renderEntryPointInsideRepeatCompilesAndRuns.)
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Fan(rows: List<String>) {
                        rows.forEach { row ->
                            keyed(row) {
                                emit(TextNode(value = row))
                            }
                        }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunKeyedRegionMessage("keyed", "forEach"))
        messages.filter { it.severity.isError }.forEach { message ->
            assertTrue(
                "Use each(items, key = ...)" !in message.message,
                "A keyed construct must not be advised to use each/keyed: ${message.message}",
            )
        }
    }

    @Test
    fun multiRunComponentCallInsideRuntimeRenderIsReported() {
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    fun render(runtime: KineticaRuntime) {
                        runtime.render {
                            listOf(1).forEach { Badge() }
                        }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("Badge", "forEach"))
    }

    @Test
    fun multiRunCallWithComponentTypedLambdaArgumentCompiles() {
        // F1: a content-wrapper helper is not an ordinal consumer. IR wraps its
        // @UiComponent-typed lambda literal into one static FrameTable and numbers
        // nothing on the call itself; region re-entry forking keeps repeated
        // invocations independent at runtime. Single-run hosts (a raw loop, `run`)
        // stay reachable by the IR walker, so the wrapper compiles there too — only
        // multi-run lambda hosts are rejected (see the componentContentWrapper tests).
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    fun ComponentScope.helper(
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        content()
                    }

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    @UiComponent
                    fun ComponentScope.Fan() {
                        helper { Badge() }
                        for (i in 0..1) {
                            helper { Badge() }
                        }
                        run { helper { Badge() } }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun componentContentWrapperInMultiRunLambdaIsReported() {
        // Known gap recorded at Task 15, closed: within a component body the IR walker
        // never descends into a multi-run lambda, so the wrapper's content literal is
        // never frame-wrapped and Badge() crashes at first render — previously with no
        // diagnostic at ANY checks mode.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    fun ComponentScope.helper(
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        content()
                    }

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    @UiComponent
                    fun ComponentScope.Fan() {
                        listOf(1).forEach { helper { Badge() } }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(componentContentInMultiRunMessage("helper", "forEach"))
    }

    @Test
    fun componentContentWrapperInStoredLambdaIsReported() {
        // The stored-lambda door into the same gap: the IR walker gates variable
        // initializers exactly like multi-run call arguments (F6), so content literals
        // inside a stored lambda are never wrapped either.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    fun ComponentScope.helper(
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        content()
                    }

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    @UiComponent
                    fun ComponentScope.Fan() {
                        val row: ComponentScope.() -> Unit = { helper { Badge() } }
                        row()
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(componentContentInMultiRunMessage("helper", "row"))
    }

    @Test
    fun componentContentWrapperInMultiRunLambdaNestedInContentIsReported() {
        // One more content lambda around the Task-15 shape re-opened the gap verbatim:
        // containment classified the site COMPONENT_TYPED_LAMBDA, not COMPONENT_BODY, yet
        // the content is numbered by the GATED walker (buildFreshRegionTableOf), which
        // never descends into forEach. What decides is the numbering ROOT, not the
        // innermost boundary.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    fun ComponentScope.helper(
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        content()
                    }

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    @UiComponent
                    fun ComponentScope.Fan() {
                        helper { listOf(1, 2).forEach { helper { Badge() } } }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(componentContentInMultiRunMessage("helper", "forEach"))
    }

    @Test
    fun componentContentWrapperInMultiRunLambdaInsideLocalFunctionIsReported() {
        // A local function inside a component body is walked by the same gated walker,
        // so the gap survives there too — while containment reports OUTSIDE (the nearest
        // named function is the local one).
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    fun ComponentScope.helper(
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        content()
                    }

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    @UiComponent
                    fun ComponentScope.Fan() {
                        fun ComponentScope.rows() {
                            listOf(1, 2).forEach { helper { Badge() } }
                        }
                        rows()
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(componentContentInMultiRunMessage("helper", "forEach"))
    }

    @Test
    fun componentContentWrapperInMultiRunNestedInEntryContentCompilesAndRenders() {
        // The same doubly-nested shape rooted OUTSIDE a component: the ungated entry pass
        // wraps content bottom-up before the gated walk of the entry lambda ever runs, so
        // this must stay legal — and actually render.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    fun ComponentScope.helper(
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        content()
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Badge(label: String) {
                        text("badge:" + label)
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) {
                            helper { listOf("a", "b").forEach { helper { Badge(it) } } }
                        }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val tree = compiled.invokeRender("app.MainKt", "render").toString()
            assertTrue("badge:a" in tree && "badge:b" in tree, "entry content must render both rows: $tree")
        }
    }

    @Test
    fun nestedComponentContentWrappersInComponentBodyCompileAndRender() {
        // Without a multi-run lambda in between, the gated walker wraps each nested
        // content in turn: nesting alone must not trip the widened rule.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    fun ComponentScope.helper(
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        content()
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Fan() {
                        helper { helper { Badge() } }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Fan() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val tree = compiled.invokeRender("app.MainKt", "render").toString()
            assertTrue("badge" in tree, "nested content wrappers must render: $tree")
        }
    }

    @Test
    fun componentContentWrapperInEntryContentCompiles() {
        // Outside component bodies the entry-point pass wraps content arguments
        // ungated (it descends into loops and multi-run lambdas alike), so the same
        // wrapper-in-forEach shape is sound inside entry content and must not be
        // rejected — the new rule keys on COMPONENT_BODY containment exactly.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    fun ComponentScope.helper(
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        content()
                    }

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope) {
                        runtime.render(scope) {
                            listOf(1, 2).forEach { helper { Badge() } }
                        }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun renderEntryPointInsideRepeatCompilesAndRuns() {
        // F1 probe (review: RuntimeSmokeSlotsTest repeat host): an entry point such as
        // KineticaRuntime.render merely receives @UiComponent content — IR numbers zero
        // ordinals for the call, so a multi-run host around it is sound.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.text

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node {
                        var tree: Node? = null
                        repeat(2) {
                            tree = runtime.render(scope) { text("x") }.tree
                        }
                        return tree!!
                    }
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val tree = compiled.invokeRender("app.MainKt", "render").toString()
            assertTrue("x" in tree, "Expected rendered text in: $tree")
        }
    }

    @Test
    fun renderEntryPointInsideAssertFailsWithCompiles() {
        // F1 probe. Emulates the downstream assertFailsWith { KineticaTest.render { } }
        // idiom with KineticaRuntime.render (kinetica-test is not on this harness
        // classpath); the F1 shape is identical: an entry point receiving @UiComponent
        // content inside a lambda with no single-run verdict.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.text
                    import kotlin.test.assertFailsWith

                    fun probe(runtime: KineticaRuntime, scope: ComponentScope) {
                        assertFailsWith<IllegalStateException> {
                            runtime.render(scope) {
                                text("boom")
                                error("boom")
                            }
                        }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun knownSingleRunAndKineticaDslLambdasRemainAllowed() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.peek
                    import io.heapy.kinetica.row
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Safe() {
                        listOf(1).let { values ->
                            val count = state { values.size }
                            count.value
                        }
                        run {
                            row {
                                val nested = state { 1 }
                                nested.value
                            }
                        }
                        peek {
                            val untracked = state { 2 }
                            untracked.value
                        }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun keyedAndEachRemainAllowedDirectlyInsideLoops() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.each
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Rows() {
                        for (batch in listOf(listOf(1, 2))) {
                            keyed(batch.size) {
                                val group = state { batch.size }
                                group.value
                            }
                            each(batch, key = { it }) { item ->
                                val row = state { item }
                                row.value
                            }
                        }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun checksOffKeepsMultiRunOrdinalRuleAnError() {
        // S1 primary probe: rule F is a soundness rule with no IR counterpart —
        // removing it turned this compile error into a first-render crash blaming a
        // correct build config. `checks=off` must keep it an error.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Fan() {
                        listOf(1, 2).forEach { item ->
                            val value = state { item }
                            value.value
                        }
                    }
                """,
            ),
            checks = "off",
        )

        messages.assertSingleErrorEquals(multiRunMessage("state", "forEach"))
    }

    @Test
    fun checksOffKeepsValStoredLambdaRuleAnError() {
        // S1's "no error at all" case: the Task 7 aliasing probe — the IR walker never
        // numbers a val-stored lambda, so at checks=off this used to compile silently
        // and alias slot 0 across every invocation.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.ValLambdaFan(items: List<Int>) {
                        val row: (Int) -> Unit = { i ->
                            val s = state { i }
                            text("row=" + i + " state=" + s.value)
                        }
                        items.forEach { row(it) }
                    }
                """,
            ),
            checks = "off",
        )

        messages.assertSingleErrorEquals(multiRunMessage("state", "row"))
    }

    @Test
    fun checksWarningKeepsSoundnessRulesAsErrors() {
        // `checks=warning` downgrades style diagnostics only; soundness rules stay
        // fixed-severity errors.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Fan() {
                        listOf(1, 2).forEach { item ->
                            val value = state { item }
                            value.value
                        }
                    }
                """,
            ),
            checks = "warning",
        )

        messages.assertSingleErrorEquals(multiRunMessage("state", "forEach"))
    }

    @Test
    fun checksOffSuppressesStyleDiagnostics() {
        // Rule E is the style bucket: a scope-free @UiComponent declaration is never
        // framed or staged, so on its own it is provably inert at runtime.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun Standalone() {
                    }
                """,
            ),
            checks = "off",
        ).use { compiled ->
            assertTrue(
                compiled.messages.none {
                    "must be an extension of io.heapy.kinetica.ComponentScope" in it.message
                },
                "checks=off must fully suppress the style diagnostic. Messages:\n" +
                    compiled.messages.joinToString("\n") { "${it.severity}: ${it.message}" },
            )
        }
    }

    @Test
    fun checksUnknownValueFallsBackToErrorMode() {
        // KineticaChecksMode.from maps unrecognized values to ERROR: a typo in the
        // option must never silently downgrade or disable the style rule.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun Standalone() {
                    }
                """,
            ),
            checks = "strict",
        ).assertContainsError("must be an extension of io.heapy.kinetica.ComponentScope")
    }

    @Test
    fun checksWarningDowngradesStyleDiagnosticsToWarnings() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun Standalone() {
                    }
                """,
            ),
            checks = "warning",
        ).use { compiled ->
            val styleWarnings = compiled.messages.filter {
                it.severity == CompilerMessageSeverity.WARNING &&
                    "@UiComponent function 'Standalone' must be an extension of " +
                    "io.heapy.kinetica.ComponentScope." in it.message
            }
            assertEquals(
                1,
                styleWarnings.size,
                "Expected exactly one style WARNING. Messages:\n" +
                    compiled.messages.joinToString("\n") { "${it.severity}: ${it.message}" },
            )
        }
    }

    @Test
    fun scopeFreeComponentBodyStaysOutsideForSoundnessRules() {
        // The crash shape rule E used to shadow: IR frames only @UiComponent functions
        // WITH a ComponentScope receiver, so a slot call reached through a scope
        // parameter inside a scope-free @UiComponent is never numbered and crashes at
        // render. COMPONENT_BODY containment mirrors IR's framing predicate, keeping
        // this a soundness error even when the style rule is downgraded or off.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun Standalone(scope: ComponentScope) {
                        val count = scope.state { 0 }
                        count.value
                    }
                """,
            ),
            checks = "off",
        )

        messages.assertSingleErrorEquals(
            "'state' can only be called inside a @UiComponent function. " +
                "Move the call into a @UiComponent, or annotate the enclosing function.",
        )
    }

    @Test
    fun absentOrNullLiteralHandlersRemainAllowedInRepeatedContexts() {
        // Sound exemption only: an absent or literal-null handler provably never reaches
        // registerHostEvent, so the loop-shared static event ordinal stays unused.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.button
                    import io.heapy.kinetica.checkbox
                    import io.heapy.kinetica.textInput

                    @UiComponent
                    fun ComponentScope.Form() {
                        listOf("one").forEach { value ->
                            button {}
                            button(onClick = null) {}
                            textInput(value = value)
                            textInput(value = value, onInput = null, onSubmit = null)
                            checkbox(checked = false)
                            checkbox(checked = false, onToggle = null)
                        }
                        for (value in listOf("two")) {
                            button {}
                            button(onClick = null) {}
                            textInput(value = value)
                            textInput(value = value, onInput = null, onSubmit = null)
                            checkbox(checked = false)
                            checkbox(checked = false, onToggle = null)
                        }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun nullableTypedHandlerInForEachLambdaIsReported() {
        // F2 probe (a): the handler's STATIC type is nullable but the VALUE may be
        // non-null — IR never descends into forEach, the ordinal keeps its -1 default,
        // and registerHostEvent throws MissingKineticaPluginException on first render.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.button

                    @UiComponent
                    fun ComponentScope.Rows(click: (() -> Unit)?) {
                        listOf("a", "b", "c").forEach { label ->
                            button(onClick = click) {}
                        }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("button", "forEach"))
    }

    @Test
    fun nullableTypedHandlerInForLoopIsReported() {
        // F2 probe (b): in a raw loop IR fills ONE static event ordinal for every
        // iteration, so all rows alias the last closure — clicking row "a" runs row "c".
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.button

                    @UiComponent
                    fun ComponentScope.Rows(click: (() -> Unit)?) {
                        for (label in listOf("a", "b", "c")) {
                            button(onClick = click) {}
                        }
                    }
                """,
            ),
        ).assertContainsError("'button' must not be called directly inside a loop")
    }

    @Test
    fun platformTypedHandlerInLoopIsReported() {
        // ThreadLocal.get() returns the Java platform type (() -> Unit)! — static
        // nullability is undefined, so only the argument-shape proxy can classify it.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.button

                    @UiComponent
                    fun ComponentScope.Rows(holder: ThreadLocal<() -> Unit>) {
                        for (index in 0..1) {
                            button(onClick = holder.get()) {}
                        }
                    }
                """,
            ),
        ).assertContainsError("'button' must not be called directly inside a loop")
    }

    @Test
    fun typeParameterTypedHandlerInLoopIsReported() {
        // A type-parameter-typed handler (nullable upper bound) also has no useful
        // static nullability; the value can still be non-null at runtime.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.button

                    @UiComponent
                    fun <T : (() -> Unit)?> ComponentScope.Rows(handler: T) {
                        for (index in 0..1) {
                            button(onClick = handler) {}
                        }
                    }
                """,
            ),
        ).assertContainsError("'button' must not be called directly inside a loop")
    }

    // Drift tests: the FIR checker exists to predict what the IR frame pass numbers.
    // Each probe compiles with checks=error (FIR verdict: allowed), then asserts the IR
    // pass actually numbered the same construct (no bail-out, renders with stable slots).
    // Both phases must consult KineticaFramePolicy; these fail if either side grows a
    // private copy of the region-content or single-run tables and drifts.

    @Test
    fun firAndIrAgreeOnKeyedRegionContent() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Panel() {
                        keyed("tab") {
                            val id = state { nextId++ }
                            text("k=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Panel() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("k=0")
        }
    }

    @Test
    fun firAndIrAgreeOnEachRegionContent() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.each
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Rows() {
                        each(listOf("a", "b"), key = { it }) { item ->
                            val id = state { nextId++ }
                            text(item + "=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Rows() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("a=0", "b=1")
        }
    }

    @Test
    fun firAndIrAgreeOnLazyEachContentAndPlaceholder() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.lazyEach
                    import io.heapy.kinetica.lazyItems
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Rows() {
                        lazyEach(
                            lazyItems(listOf("a", "b")),
                            key = { it },
                            placeholder = {
                                val pending = state { -1 }
                                text("pending=" + pending.value)
                            },
                        ) { item ->
                            val id = state { nextId++ }
                            text(item + "=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Rows() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            // The placeholder lambda only executes for pending resources; its IR verdict
            // is the absence of a bail-out message, asserted for the whole probe below.
            compiled.assertIrNumberedAndRendersStably("a=0", "b=1")
        }
    }

    @Test
    fun firAndIrAgreeOnSingleRunLambdaHosts() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.peek
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Hosts() {
                        listOf(1).let {
                            val a = state { nextId++ }
                            text("let=" + a.value)
                        }
                        run {
                            val b = state { nextId++ }
                            text("run=" + b.value)
                        }
                        with(listOf(1)) {
                            val c = state { nextId++ }
                            text("with=" + c.value)
                        }
                        listOf(1).apply {
                            val d = state { nextId++ }
                            text("apply=" + d.value)
                        }
                        listOf(1).also {
                            val e = state { nextId++ }
                            text("also=" + e.value)
                        }
                        peek {
                            val f = state { nextId++ }
                            text("peek=" + f.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Hosts() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably(
                "let=0", "run=1", "with=2", "apply=3", "also=4", "peek=5",
            )
        }
    }

    @Test
    fun firAndIrAgreeOnBoundaryRegionContent() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.errorBoundary
                    import io.heapy.kinetica.loadingBoundary
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Panel() {
                        errorBoundary(
                            fallback = { _, _, _ ->
                                val id = state { -1 }
                                text("error=" + id.value)
                            },
                        ) {
                            val a = state { nextId++ }
                            text("eb=" + a.value)
                        }
                        loadingBoundary(
                            fallback = {
                                val id = state { -2 }
                                text("loading=" + id.value)
                            },
                        ) {
                            val b = state { nextId++ }
                            text("lb=" + b.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Panel() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            // The fallback lambdas execute only on error/pending; their IR verdict is
            // the absence of a decline message, asserted for the whole probe below.
            compiled.assertIrNumberedAndRendersStably("eb=0", "lb=1")
        }
    }

    @Test
    fun firAndIrAgreeOnExitGroupAndSuspendKeyedContent() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.exitGroup
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.suspendSubtree
                    import io.heapy.kinetica.text
                    import kotlinx.coroutines.awaitCancellation

                    var nextId: Int = 0
                    var runSuspendKeyed: Boolean = false

                    @UiComponent(skippable = false)
                    fun ComponentScope.Panel() {
                        exitGroup(key = "g", visible = true) {
                            val a = state { nextId++ }
                            text("xg=" + a.value)
                        }
                        suspendSubtree(fallback = { text("pending") }) {
                            if (runSuspendKeyed) {
                                suspendKeyed("tab") {
                                    val b = state { nextId++ }
                                    text("sk=" + b.value)
                                }
                            }
                            // Keeps the subtree pending so renders stay deterministic;
                            // suspendKeyed's verdict is the clean checks=error compile
                            // (FIR) plus the absence of a decline message (IR), the
                            // same precedent as the lazyEach placeholder probe.
                            awaitCancellation()
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Panel() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("xg=0", "pending")
        }
    }

    @Test
    fun userAtLeastOnceContractHostStaysMultiRun() {
        // contractSingleRunParameterNames accepts only EXACTLY_ONCE / AT_MOST_ONCE: an
        // AT_LEAST_ONCE block can run twice, so ordinal consumers inside it stay rule F
        // errors — and the oracle records no verdict, so IR never numbers there either.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text
                    import kotlin.contracts.ExperimentalContracts
                    import kotlin.contracts.InvocationKind
                    import kotlin.contracts.contract

                    @OptIn(ExperimentalContracts::class)
                    inline fun <R> twiceIsh(block: () -> R): R {
                        contract { callsInPlace(block, InvocationKind.AT_LEAST_ONCE) }
                        block()
                        return block()
                    }

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Fan() {
                        twiceIsh {
                            val id = state { nextId++ }
                            text("x" + id.value)
                        }
                    }
                """,
            ),
        ).assertSingleErrorEquals(multiRunMessage("state", "twiceIsh"))
    }

    // F12 remainder: top-level io.heapy.kinetica helpers WITHOUT a ComponentScope
    // receiver, classified per function in KineticaFramePolicy. peek is a single-run
    // numbering context (pinned end to end by firAndIrAgreeOnSingleRunLambdaHosts
    // above); derive, invalidate, and serverActionStub lambdas re-run reactively
    // (recompute / per-key invalidation / per-dispatch), so ordinal consumers inside
    // them are rule F errors — and the IR pass never numbers there either (the
    // checks=off halves live in KineticaIrFrameCompileTest).

    @Test
    fun deriveComputeLambdaOrdinalConsumerIsReported() {
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.derive
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Fan() {
                        val c = derive { state { 1 }.value }
                        text("d=" + c.value)
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("state", "derive"))
    }

    @Test
    fun invalidatePredicateOrdinalConsumerIsReported() {
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.invalidate
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Fan() {
                        invalidate { state { 0 }.value > 0 }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("state", "invalidate"))
    }

    @Test
    fun serverActionStubHandlerOrdinalConsumerIsReported() {
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.ServerActionRegistration
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.serverActionStub
                    import io.heapy.kinetica.state
                    import kotlinx.serialization.builtins.serializer

                    @UiComponent
                    fun ComponentScope.Fan() {
                        serverActionStub(
                            registration = ServerActionRegistration(
                                actionId = "bump",
                                functionFqName = "app.bump",
                            ),
                            inputSerializer = Int.serializer(),
                            outputSerializer = Int.serializer(),
                        ) { input -> state { input }.value }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("state", "serverActionStub"))
    }

    @Test
    fun everyTopLevelKineticaLambdaFunctionHasAnExplicitFramePolicyClassification() {
        // The F12 gap was a helper nobody classified: FIR judged lambdas by RECEIVER, IR
        // by PACKAGE, and every new top-level helper inherited the asymmetry. Both
        // phases now read one predicate, so agreement per function reduces to that
        // predicate's verdict — this test enumerates the ACTUAL published surface
        // (top-level io.heapy.kinetica functions taking a function-typed parameter,
        // without a ComponentScope receiver; extensions and members are classified per
        // (callee, parameter) via MULTI_RUN_DSL_PARAMETERS and the region drift tests
        // above) and fails until each function carries an explicit single-run/multi-run
        // decision in KineticaFramePolicy. kinetica-runtime is the sole io.heapy.kinetica
        // contributor on this classpath; helpers in other modules default to multi-run
        // on both phases, which is aligned and safe.
        val location = File(ComponentScope::class.java.protectionDomain.codeSource.location.toURI())
        val facadeNames = if (location.isDirectory) {
            location.resolve("io/heapy/kinetica").listFiles().orEmpty().map { it.name }
        } else {
            JarFile(location).use { jar ->
                jar.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("io/heapy/kinetica/") }
                    .map { it.removePrefix("io/heapy/kinetica/") }
                    .filter { "/" !in it }
                    .toList()
            }
        }.filter { it.endsWith("Kt.class") && "$" !in it }
            .map { "io.heapy.kinetica." + it.removeSuffix(".class") }

        assertTrue(facadeNames.isNotEmpty(), "expected io.heapy.kinetica file facades on the test classpath")

        val scanned = facadeNames
            .map { Class.forName(it) }
            .flatMap { facade ->
                facade.declaredMethods.filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        Modifier.isPublic(method.modifiers) &&
                        !method.isSynthetic &&
                        "$" !in method.name &&
                        method.parameterTypes.any { Function::class.java.isAssignableFrom(it) } &&
                        method.parameterTypes.firstOrNull() != ComponentScope::class.java
                }.map { it.name }
            }
            .toSortedSet()

        // Kotlin-internal helpers compile to public JVM bytecode (inline internal
        // functions keep their unmangled name), but consumer source cannot call them, so
        // they need no classification. Extend this list consciously when adding one.
        val internalOnly = setOf("synchronizedOn")

        // Multi-run ledger, test-side by design: absence from SINGLE_RUN_TOP_LEVEL_FUNCTIONS
        // already classifies a top-level lambda host as multi-run on BOTH compiler phases
        // (the shared predicate is the only production reader), so this list exists purely
        // to force an explicit single-run/multi-run decision for every top-level helper
        // the runtime publishes. Why each entry is multi-run: `derive`'s compute lambda
        // re-runs reactively whenever a dependency cell changes (`DerivedCell`);
        // `invalidate`'s predicate runs once per cached resource key at invalidation time,
        // long after the numbering render pass; `serverActionStub`'s handler runs per
        // server-action dispatch.
        val multiRun = setOf("derive", "invalidate", "serverActionStub")

        // The predicate verdict for a receiver-less top-level name IS ledger membership
        // (`isKineticaDsl(KINETICA_PACKAGE, false, name)` reduces to
        // `name in SINGLE_RUN_TOP_LEVEL_FUNCTIONS`), so re-asserting it per name would be
        // tautological; the enumeration below is the whole check.
        val singleRun = KineticaFramePolicy.SINGLE_RUN_TOP_LEVEL_FUNCTIONS
        assertEquals(
            emptySet(),
            singleRun intersect multiRun,
            "a top-level function cannot be classified both single-run and multi-run",
        )
        assertEquals(
            emptySet(),
            scanned - singleRun - multiRun - internalOnly,
            "unclassified top-level io.heapy.kinetica lambda-taking functions — record each " +
                "in KineticaFramePolicy.SINGLE_RUN_TOP_LEVEL_FUNCTIONS or in this test's " +
                "multi-run ledger before shipping it",
        )
        assertEquals(
            emptySet(),
            (singleRun + multiRun) - scanned,
            "stale ledger entries — these names no longer exist as " +
                "top-level io.heapy.kinetica lambda-taking functions",
        )
    }

    // Contract-based single-run hosts (F13): single-run detection derives from Kotlin
    // contracts (callsInPlace EXACTLY_ONCE / AT_MOST_ONCE) resolved in FIR and carried
    // to the IR pass through the per-compilation SingleRunOracle. Callees without a
    // usable contract fall through to the shared KineticaFramePolicy name lists.

    @Test
    fun runCatchingLambdaHostCompilesAndNumbersOrdinals() {
        // stdlib 2.4.10 declares NO contract on runCatching; it is single-run through the
        // shared name-list fallback (semantically a try block) — on BOTH compiler phases.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Catcher() {
                        runCatching {
                            val id = state { nextId++ }
                            text("caught=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Catcher() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("caught=0")
        }
    }

    @Test
    fun takeIfContractLambdaHostCompilesAndNumbersOrdinals() {
        // takeIf declares callsInPlace(predicate, EXACTLY_ONCE): FIR resolves the contract
        // and the oracle makes IR descend into the predicate lambda.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Gate(flag: Boolean) {
                        flag.takeIf {
                            val id = state { nextId++ }
                            text("gate=" + id.value)
                            it
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Gate(true) }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("gate=0")
        }
    }

    @Test
    fun userExactlyOnceContractHostCompilesAndNumbersOrdinals() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text
                    import kotlin.contracts.ExperimentalContracts
                    import kotlin.contracts.InvocationKind
                    import kotlin.contracts.contract

                    @OptIn(ExperimentalContracts::class)
                    inline fun <R> mySection(block: () -> R): R {
                        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
                        return block()
                    }

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Sectioned() {
                        mySection {
                            val id = state { nextId++ }
                            text("section=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Sectioned() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("section=0")
        }
    }

    @Test
    fun userAtMostOnceContractHostCompilesAndNumbersOrdinals() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text
                    import kotlin.contracts.ExperimentalContracts
                    import kotlin.contracts.InvocationKind
                    import kotlin.contracts.contract

                    @OptIn(ExperimentalContracts::class)
                    inline fun <R> guarded(enabled: Boolean, block: () -> R): R? {
                        contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
                        return if (enabled) block() else null
                    }

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Guarded() {
                        guarded(true) {
                            val id = state { nextId++ }
                            text("guarded=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Guarded() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("guarded=0")
        }
    }

    @Test
    fun noContractHostsRemainMultiRun() {
        // forEach and map declare nothing; repeat declares callsInPlace(action) with an
        // UNKNOWN occurrence range. None yields an oracle entry, so all three fall through
        // to the name lists and stay multi-run errors.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Fan() {
                        listOf(1).forEach { item ->
                            val a = state { item }
                            a.value
                        }
                        listOf(1).map { item ->
                            val b = state { item }
                            b.value
                        }
                        repeat(1) { index ->
                            val c = state { index }
                            c.value
                        }
                    }
                """,
            ),
        )

        messages.assertErrorMessages(
            multiRunMessage("state", "forEach"),
            multiRunMessage("state", "map"),
            multiRunMessage("state", "repeat"),
        )
    }

    @Test
    fun localFunctionHostStaysMultiRunViaNameListFallback() {
        // A local function cannot get a SingleRunOracle entry (no stable CallableId on the
        // IR side), so both phases must agree via the name-list fallback: FIR rejects the
        // host, and IR never numbers inside it. The compile error is that agreement.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Fan() {
                        fun localSection(block: () -> Unit) = block()
                        localSection {
                            val id = state { 0 }
                            id.value
                        }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("state", "localSection"))
    }

    @Test
    fun deferredHandlerLambdasAreMultiRunHosts() {
        // F4: deferred handlers run at dispatch/post-commit/async time, arbitrarily often,
        // when currentFrame is no longer the numbering frame — an ordinal consumer inside
        // one reads another component's cell instead of failing fast.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.ResourceKey
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.action
                    import io.heapy.kinetica.button
                    import io.heapy.kinetica.checkbox
                    import io.heapy.kinetica.derived
                    import io.heapy.kinetica.event
                    import io.heapy.kinetica.hostEvent
                    import io.heapy.kinetica.hostEventBlock
                    import io.heapy.kinetica.launchEffect
                    import io.heapy.kinetica.layoutEffect
                    import io.heapy.kinetica.resource
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.textInput
                    import io.heapy.kinetica.watch

                    data class RowKey(val id: Int) : ResourceKey

                    @UiComponent
                    fun ComponentScope.Handlers() {
                        button(onClick = { state { 0 }.value }) { }
                        textInput(value = "v", onInput = { state { 1 }.value })
                        checkbox(checked = false, onToggle = { state { 2 }.value })
                        event { state { 3 }.value }
                        hostEvent(onEvent = { state { 4 }.value })
                        hostEventBlock { state { 5 }.value }
                        launchEffect { state { 6 }.value }
                        layoutEffect { state { 7 }.value }
                        watch(source = { derived { 8 }.value }) { state { 9 }.value }
                        action<Int, Int> { state { 10 }.value }
                        resource(RowKey(1)) { state { 11 }.value }
                    }
                """,
            ),
        )

        messages.assertErrorMessages(
            multiRunMessage("state", "button"),
            multiRunMessage("state", "textInput"),
            multiRunMessage("state", "checkbox"),
            multiRunMessage("state", "event"),
            multiRunMessage("state", "hostEvent"),
            multiRunMessage("state", "hostEventBlock"),
            multiRunMessage("state", "launchEffect"),
            multiRunMessage("state", "layoutEffect"),
            multiRunMessage("derived", "watch"),
            multiRunMessage("state", "watch"),
            multiRunMessage("state", "action"),
            multiRunMessage("state", "resource"),
        )
    }

    @Test
    fun eachAndLazyEachKeySelectorsAreMultiRunHosts() {
        // F5: key selectors run once per item but are numbered with the ENCLOSING
        // region's counters, so a slot call inside one returns the first item's cell for
        // every item (duplicate-key crash at render). They fail FIR like any multi-run
        // lambda instead of getting unsound ordinals.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.derived
                    import io.heapy.kinetica.each
                    import io.heapy.kinetica.lazyEach
                    import io.heapy.kinetica.lazyItems
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Rows(items: List<String>) {
                        each(items, key = { item -> state { item }.value }) { item ->
                            text(item)
                        }
                        lazyEach(lazyItems(items), key = { item -> derived { item }.value }) { item ->
                            text(item)
                        }
                    }
                """,
            ),
        )

        messages.assertErrorMessages(
            multiRunMessage("state", "each"),
            multiRunMessage("derived", "lazyEach"),
        )
    }

    @Test
    fun valStoredLambdaWithOrdinalConsumersIsReported() {
        // F6 probe: a lambda stored in a local val is not a resolved call argument, so
        // no run-count contract can exist for it — the IR walker never numbers inside
        // it, while every invocation would otherwise alias the enclosing region's slot 0
        // (review: rendered "state=10" for all three rows).
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.ValLambdaFan(items: List<Int>) {
                        val row: (Int) -> Unit = { i ->
                            val s = state { i }
                            text("row=" + i + " state=" + s.value)
                        }
                        items.forEach { row(it) }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("state", "row"))
    }

    @Test
    fun varargLambdaElementWithOrdinalConsumersIsReported() {
        // F6: vararg lambda elements hide behind FirVarargArgumentsExpression; the host
        // walk must unwrap them so the lambda still resolves to its callee. The exact
        // host name pins the unwrap — without it the lambda would report the generic
        // stored-lambda label instead of 'fanOut'.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    fun fanOut(vararg blocks: (Int) -> Unit) {
                        blocks.forEach { it(0) }
                    }

                    @UiComponent
                    fun ComponentScope.Fan() {
                        fanOut({ i -> state { i }.value })
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("state", "fanOut"))
    }

    @Test
    fun valStoredLambdaWithoutOrdinalConsumersCompiles() {
        // Positive side of F6: storing a lambda stays legal while nothing inside it
        // consumes a compiler-assigned ordinal — including emit-only Kinetica DSL such
        // as text, which needs no numbering.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Rows(items: List<Int>) {
                        val row: (Int) -> Unit = { i ->
                            text("row=" + i)
                        }
                        items.forEach { row(it) }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun suspendSubtreeExplicitKeyIsReported() {
        // F9 probe: suspendSubtree with an explicit non-null key is an ordinal the IR
        // pass deliberately declines to assign — the call stays on the legacy path and
        // throws MissingKineticaPluginException at first render. It compiled clean at
        // checks=error before this rule.
        val messages = harness.compileExpectingErrors(
            mapOf("main.kt" to SUSPEND_SUBTREE_EXPLICIT_KEY_SOURCE),
        )

        messages.assertSingleErrorEquals(
            explicitKeyMessage("suspendSubtree", SUSPEND_SUBTREE_KEY_CONSEQUENCE),
        )
    }

    @Test
    fun suspendSubtreeExplicitKeyFailsCompileWhenChecksAreOff() {
        // F9 + S1: rule G is a soundness rule, so `checks=off` no longer removes it —
        // the compile stops at the FIR error before IR runs. The IR located-ERROR
        // decline stays behind it as defense-in-depth for shapes FIR might miss.
        val messages = harness.compileExpectingErrors(
            mapOf("main.kt" to SUSPEND_SUBTREE_EXPLICIT_KEY_SOURCE),
            checks = "off",
        )

        messages.assertSingleErrorEquals(
            explicitKeyMessage("suspendSubtree", SUSPEND_SUBTREE_KEY_CONSEQUENCE),
        )
    }

    @Test
    fun suspendSubtreeNullOrImplicitKeyCompilesAndTransforms() {
        // Positive side of F9: a literal-null key and the implicit-key form stay on the
        // compiler path — the calls are retargeted to suspendSubtreeRegion and render
        // their fallbacks from real region frames. The content never completes
        // (awaitCancellation), so both renders deterministically emit the fallback.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.suspendSubtree
                    import io.heapy.kinetica.text
                    import kotlinx.coroutines.awaitCancellation

                    @UiComponent(skippable = false)
                    fun ComponentScope.Deferred() {
                        val label = state { "deferred" }
                        suspendSubtree(key = null, fallback = { text("fallback-null:" + label.value) }) {
                            awaitCancellation()
                        }
                        suspendSubtree(fallback = { text("fallback-implicit") }) {
                            awaitCancellation()
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Deferred() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("fallback-null:deferred", "fallback-implicit")
        }
    }

    @Test
    fun persistentStateExplicitKeyIsReported() {
        // Version-skew defense, mirroring IR's "persistent state with explicit key"
        // decline. The pinned runtime addresses persistent state by SlotId only (audit:
        // no state overload has a key parameter, zero key call sites repo-wide), so a
        // simulated legacy overload is the only possible positive coverage.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "skew.kt" to KEY_ADDRESSED_STATE_OVERLOAD_SOURCE,
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Draft() {
                        val draft = state(key = "draft", persistent = true) { "" }
                        draft.value
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(
            explicitKeyMessage("state", PERSISTENT_STATE_KEY_CONSEQUENCE),
        )
    }

    @Test
    fun persistentStateExplicitKeyFailsCompileWhenChecksAreOff() {
        // S1: rule G stays active at checks=off, so the persistence-losing key is a FIR
        // error before IR runs (the IR located-ERROR bail-out remains as backstop).
        val messages = harness.compileExpectingErrors(
            mapOf(
                "skew.kt" to KEY_ADDRESSED_STATE_OVERLOAD_SOURCE,
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Draft() {
                        val draft = state(key = "draft", persistent = true) { "" }
                        draft.value
                    }
                """,
            ),
            checks = "off",
        )

        messages.assertSingleErrorEquals(
            explicitKeyMessage("state", PERSISTENT_STATE_KEY_CONSEQUENCE),
        )
    }

    @Test
    fun nullLiteralOrAbsentPersistentKeysStayOnTheCompilerPath() {
        // FIR exempts absent and literal-null keys (the Task 5 sound proxy); IR must
        // agree and still retarget both forms to the compiler SlotId path — a literal
        // null key on the simulated legacy overload previously tripped IR's bail-out.
        harness.compile(
            mapOf(
                "skew.kt" to KEY_ADDRESSED_STATE_OVERLOAD_SOURCE,
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent(skippable = false)
                    fun ComponentScope.Draft() {
                        val keyless = state(persistent = true) { "keyless" }
                        val nullKey = state(key = null, persistent = true) { "null-key" }
                        text(keyless.value + "/" + nullKey.value)
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Draft() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("keyless/null-key")
        }
    }

    @Test
    fun regionContentNotLiteralFailsCompileWhenChecksAreOff() {
        // The third documented IR decline (F9): non-literal region content. Rule C is a
        // soundness rule, so since S1 `checks=off` keeps it active and the compile
        // stops at the FIR error (the IR located-ERROR decline remains as backstop).
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.each
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Rows(items: List<String>) {
                        val body: ComponentScope.(String) -> Unit = { item -> text(item) }
                        each(items, key = { it }, content = body)
                    }
                """,
            ),
            checks = "off",
        )

        messages.assertSingleErrorEquals(
            "The 'content' content argument must be a lambda literal so the compiler " +
                "can assign its slot ordinals: content hoisted into a local variable or passed " +
                "as a function reference is never frame-wrapped and fails at render. " +
                "Pass the lambda literal directly.",
        )
    }

    @Test
    fun ruleH_componentCallResultReceiverIsReported() {
        // F10 primary probe: the receiver is a call result, so IR cannot stage the child
        // frame ordinal (its stageComponentCall requires an IrGetValue receiver) and the
        // callee prologue throws MissingKineticaPluginException at first render. This
        // compiled clean before rule H.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    @UiComponent
                    fun ComponentScope.Fan(panes: List<ComponentScope>) {
                        panes.first().Badge()
                    }
                """,
            ),
        ).assertContainsError("'Badge' must be invoked on a simple receiver")
    }

    @Test
    fun ruleH_nestedArgumentReceiverStealIsReported() {
        // F10's nastier variant: evaluated inside another staged component call's
        // argument list, the unstaged Badge would pop the ENCLOSING component's staged
        // ordinal and render into the wrong fixed child frame. Rule H rejects the
        // receiver shape before the runtime backstop is ever needed.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    @UiComponent
                    fun ComponentScope.Wrapper(label: String) {
                        text(label)
                    }

                    @UiComponent
                    fun ComponentScope.Fan(panes: List<ComponentScope>) {
                        Wrapper(label = run { panes.first().Badge(); "x" })
                    }
                """,
            ),
        ).assertContainsError("'Badge' must be invoked on a simple receiver")
    }

    @Test
    fun ruleH_safeCallAndSmartCastReceiversAreReported() {
        // Probe-verified against IR at checks=off: fir2ir wraps both receiver reads (the
        // checked safe-call subject and the smart-cast IMPLICIT_CAST), so stageComponentCall
        // sees no bare IrGetValue and leaves BOTH calls unstaged — they crash at first
        // render. Rule H must mirror IR exactly, not the source-level "it is a variable".
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    @UiComponent
                    fun ComponentScope.SafeCall(maybe: ComponentScope?) {
                        maybe?.Badge()
                    }

                    @UiComponent
                    fun ComponentScope.SmartCast(maybe: ComponentScope?) {
                        if (maybe != null) {
                            maybe.Badge()
                        }
                    }
                """,
            ),
        )
        val receiverErrors = messages.filter {
            it.severity.isError && "'Badge' must be invoked on a simple receiver" in it.message
        }
        assertEquals(
            2,
            receiverErrors.size,
            "Both the safe-call and the smart-cast receiver must be reported. Messages:\n" +
                messages.joinToString("\n") { "${it.severity}: ${it.message}" },
        )
    }

    @Test
    fun ruleH_simpleReceiversCompile() {
        // The shapes IR provably stages (bare IrGetValue): implicit and explicit `this`,
        // a value parameter, and a plain non-delegated local val.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    @UiComponent
                    fun ComponentScope.Fan(other: ComponentScope) {
                        Badge()
                        this.Badge()
                        other.Badge()
                        val scope = other
                        scope.Badge()
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun ruleH_localVarReceiverCompiles() {
        // isSimpleStagingReceiver accepts a plain local var: IR re-reads it as a bare
        // IrGetValue exactly like a val, so staging works.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    @UiComponent
                    fun ComponentScope.Fan(first: ComponentScope, second: ComponentScope) {
                        var pane = first
                        pane.Badge()
                        pane = second
                        pane.Badge()
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun ruleH_delegatedLocalValReceiverIsReported() {
        // A delegated local read lowers to a getValue CALL, not a variable read, so IR
        // cannot stage it — rule H must reject it like any other non-simple receiver.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    @UiComponent
                    fun ComponentScope.Fan(other: ComponentScope) {
                        val pane by lazy { other }
                        pane.Badge()
                    }
                """,
            ),
        ).assertContainsError("must be invoked on a simple receiver")
    }

    @Test
    fun componentCallNonTrivialReceiverFailsCompileWhenChecksAreOff() {
        // F10 + S1: rule H is a soundness rule, so `checks=off` keeps it active and the
        // compile stops at the FIR error before IR runs (the IR "left unstaged" located
        // ERROR remains behind it as defense-in-depth for shapes FIR might miss).
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    @UiComponent
                    fun ComponentScope.Fan(panes: List<ComponentScope>) {
                        panes.first().Badge()
                    }
                """,
            ),
            checks = "off",
        )

        messages.assertSingleErrorEquals(
            "@UiComponent call 'Badge' must be invoked on a simple receiver — 'this', a parameter, " +
                "or a plain local val — so the compiler can stage the child frame ordinal. " +
                "Call-result, safe-call, and smart-cast receivers are left unstaged and fail at " +
                "first render. Bind the receiver to a plain local val of the scope type first.",
        )
    }

    /**
     * IR verdict of a drift probe: the frame pass numbered the construct (framed message,
     * no decline-to-transform bail-out) and the slots are real (rendering does not throw
     * MissingKineticaPluginException, re-rendering reuses the same cells).
     */
    private fun CompiledKineticaModule.assertIrNumberedAndRendersStably(vararg markers: String) {
        assertTransformFired("framed (slots=")
        assertTransformDidNotFire("left on the legacy path")
        assertTransformDidNotFire("left unwrapped")
        assertTransformDidNotFire("no ordinal parameter")
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val first = invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toString()
        val second = invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toString()
        markers.forEach { marker ->
            assertTrue(marker in first, "Expected '$marker' in rendered output: $first")
        }
        assertEquals(first, second, "slots must be reused, not re-initialized, across renders")
    }

    @Test
    fun annotatedOverrideOfUnannotatedBaseIsReported() {
        // IR frames by the annotation on the declaration itself, while the call site
        // stages by the annotation on the declaration it RESOLVES to. An annotated
        // override of an unannotated base is therefore framed but never staged:
        // compile-clean, MissingKineticaPluginException at first render.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    abstract class Panel {
                        abstract fun ComponentScope.Render()
                    }

                    class Screen : Panel() {
                        @UiComponent
                        override fun ComponentScope.Render() {
                            text("screen")
                        }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(
            overrideMismatchMessage(
                "Render",
                "is annotated @UiComponent while the declaration it overrides in 'app.Panel' is not",
            ),
        )
    }

    @Test
    fun unannotatedOverrideOfAnnotatedBaseIsReported() {
        // The mirror image: the call stages a child ordinal the unannotated override's
        // body never consumes, leaving a stale entry on the ordinal stack.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    abstract class Panel {
                        @UiComponent
                        abstract fun ComponentScope.Render()
                    }

                    class Screen : Panel() {
                        override fun ComponentScope.Render() {
                            text("screen")
                        }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(
            overrideMismatchMessage(
                "Render",
                "overrides the @UiComponent declaration in 'app.Panel' without being annotated itself",
            ),
        )
    }

    @Test
    fun annotatedOverrideOfUnannotatedInterfaceMemberIsReported() {
        // Interface members with a default implementation share the identical root cause:
        // the annotation lives on the resolved symbol, not on the override chain.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    interface Panel {
                        fun ComponentScope.Render() {
                            text("base")
                        }
                    }

                    class Screen : Panel {
                        @UiComponent
                        override fun ComponentScope.Render() {
                            text("screen")
                        }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(
            overrideMismatchMessage(
                "Render",
                "is annotated @UiComponent while the declaration it overrides in 'app.Panel' is not",
            ),
        )
    }

    @Test
    fun overrideChainAnnotatedOnEveryLevelCompilesAndRenders() {
        // The sanctioned shape: annotate every declaration in the chain. Both the frame
        // prologue and the staging site then agree, so the component renders.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    abstract class Panel {
                        @UiComponent(skippable = false)
                        abstract fun ComponentScope.Render()
                    }

                    class Screen : Panel() {
                        @UiComponent(skippable = false)
                        override fun ComponentScope.Render() {
                            text("screen")
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node {
                        val panel: Panel = Screen()
                        return runtime.render(scope) { with(panel) { Render() } }.tree
                    }
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val tree = compiled.invokeRender("app.MainKt", "render").toString()
            assertTrue("screen" in tree, "agreeing override chain must render: $tree")
        }
    }

    @Test
    fun slotCallInComponentDefaultArgumentIsReported() {
        // Neither IR pass walks IrValueParameter.defaultValue, and a default is evaluated
        // in the $default stub BEFORE beginComponentFrame runs — no frame exists to number
        // it into. The ordinal stays -1 and 'state' throws from Card$default.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.MutableCell
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Card(counter: MutableCell<Int> = state { 0 }) {
                        text("card:" + counter.value)
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(defaultArgumentMessage("state"))
    }

    @Test
    fun componentCallInComponentDefaultArgumentIsReported() {
        // Same hole through rule B's door: a component call in a default is never staged,
        // and containment classifies the default expression as the component's own body.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    @UiComponent
                    fun ComponentScope.Card(marker: Unit = Badge()) {
                        text("card")
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(defaultArgumentMessage("Badge"))
    }

    @Test
    fun entryContentInComponentDefaultArgumentStaysLegal() {
        // A default that only opens its own render root consumes no ordinal of the
        // enclosing component: the content lambda is frame-wrapped at its literal site,
        // so the rule must stop at the component-typed lambda boundary.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    @UiComponent
                    fun ComponentScope.Card(
                        runtime: KineticaRuntime,
                        preview: Node = runtime.render(ComponentScope(runtime)) { Badge() }.tree,
                    ) {
                        text("card:" + preview)
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun callableReferenceToComponentIsReported() {
        // IR stages a child frame ordinal only from visitCall, never from
        // IrFunctionReference, and the reference binds to a plain (unannotated) function
        // type — so the later invoke is not a component call and Badge's framed body finds
        // an empty ordinal stack. Nobody checked FirCallableReferenceAccess before.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    @UiComponent
                    fun ComponentScope.Fan() {
                        val reference: ComponentScope.() -> Unit = ComponentScope::Badge
                        this.reference()
                    }
                """,
            ),
        )

        messages.assertContainsError(componentReferenceMessage("Badge"))
    }

    @Test
    fun callableReferenceToComponentOutsideComponentIsReported() {
        // The reference is unsound wherever it is taken: no call site means no staging,
        // whoever invokes the resulting function value later.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    val hoisted: ComponentScope.() -> Unit = ComponentScope::Badge
                """,
            ),
        )

        messages.assertSingleErrorEquals(componentReferenceMessage("Badge"))
    }

    @Test
    fun callableReferenceToPlainFunctionStaysLegal() {
        // Only @UiComponent targets are rejected: plain function references carry no
        // staging obligation.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    fun ComponentScope.plain() {
                        text("plain")
                    }

                    @UiComponent
                    fun ComponentScope.Fan() {
                        val reference: ComponentScope.() -> Unit = ComponentScope::plain
                        this.reference()
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    private fun componentReferenceMessage(name: String): String =
        "@UiComponent function '$name' cannot be used as a callable reference: the compiler " +
            "stages a component's child frame ordinal at the call site, and a reference has no " +
            "call site to stage. Call it directly, or pass a @UiComponent-typed lambda literal " +
            "({ $name() }) instead."

    private fun defaultArgumentMessage(call: String): String =
        "Kinetica call '$call' cannot use a compiler-assigned ordinal in a default argument " +
            "value: the default is evaluated before the component's frame is entered, so the " +
            "compiler never numbers it. Move the call into the component body (for example " +
            "make the parameter nullable and compute the fallback there)."

    private fun overrideMismatchMessage(name: String, detail: String): String =
        "@UiComponent must agree across an override chain: '$name' $detail. " +
            "The compiler frames a component by the annotation on its own declaration and " +
            "stages the child ordinal by the annotation on the declaration the call resolves " +
            "to, so a mismatch throws MissingKineticaPluginException at first render or " +
            "leaves a staged ordinal unconsumed. Annotate every declaration in the chain, or none."

    private fun multiRunMessage(call: String, host: String): String =
        "Kinetica call '$call' cannot use a compiler-assigned ordinal inside the multi-run '$host' lambda. " +
            "Use each(items, key = ...) or keyed(...) for repeated rendering."

    private fun multiRunKeyedRegionMessage(call: String, host: String): String =
        "Kinetica call '$call' cannot use a compiler-assigned ordinal inside the multi-run '$host' lambda. " +
            "The call already keys its own content; hoist it out of the multi-run lambda, " +
            "or key the outer repetition with keyed(...)."

    private fun componentContentInMultiRunMessage(call: String, host: String): String =
        "'$call' receives @UiComponent content inside the multi-run '$host' lambda. " +
            "Within a component body the compiler never descends into '$host', so the " +
            "content lambda is never frame-wrapped: any component call or slot inside " +
            "it fails at first render. " +
            "Hoist the call out of the multi-run lambda, or use " +
            "each(items, key = ...) or keyed(...) for repeated rendering."

    private fun explicitKeyMessage(call: String, consequence: String): String =
        "'$call' with an explicit 'key' argument cannot use compiler-assigned ordinals: " +
            "the call is left on the legacy path and $consequence"

    /** A decline-to-transform IR error: exactly one, matching [needle], with a location. */
    private fun List<RecordedCompilerMessage>.assertSingleErrorEquals(expected: String) {
        assertEquals(listOf(expected), filter { it.severity.isError }.map { it.message })
    }

    private fun List<RecordedCompilerMessage>.assertErrorMessages(vararg expected: String) {
        val errors = filter { it.severity.isError }.map { it.message }
        assertEquals(expected.size, errors.size, "Unexpected errors:\n${errors.joinToString("\n")}")
        assertEquals(expected.toSet(), errors.toSet())
    }

    private fun List<RecordedCompilerMessage>.assertContainsError(needle: String) {
        assertTrue(
            any { it.severity.isError && needle in it.message },
            "Expected an error containing '$needle'. Messages:\n" +
                joinToString("\n") { "${it.severity}: ${it.message}" },
        )
    }
}

// Message tails pinned verbatim (like multiRunMessage) so wording changes are conscious.
private const val SUSPEND_SUBTREE_KEY_CONSEQUENCE =
    "throws MissingKineticaPluginException at first render. Remove the key argument " +
        "(call-site identity is compiler-assigned), or wrap the call in keyed(...) for " +
        "explicit identity."

private const val PERSISTENT_STATE_KEY_CONSEQUENCE =
    "the slot is never registered for persistence. Address it with state(slotId = ...) " +
        "or omit the key so the compiler derives a durable SlotId."

private val SUSPEND_SUBTREE_EXPLICIT_KEY_SOURCE = """
    package app

    import io.heapy.kinetica.ComponentScope
    import io.heapy.kinetica.UiComponent
    import io.heapy.kinetica.suspendSubtree
    import io.heapy.kinetica.text

    @UiComponent
    fun ComponentScope.Profile(id: String) {
        suspendSubtree(key = id, fallback = { text("loading") }) {
            text("profile " + id)
        }
    }
"""

/**
 * Simulates a runtime revision whose persistent state is addressed by string key. The
 * pinned runtime has no such overload (persistent state is SlotId-addressed), so this is
 * the only way to exercise the explicit-key rules that defend against exactly this skew.
 */
private val KEY_ADDRESSED_STATE_OVERLOAD_SOURCE = """
    package io.heapy.kinetica

    public fun <T> ComponentScope.state(
        key: String?,
        persistent: Boolean = false,
        ordinal: Int = -1,
        initial: () -> T,
    ): MutableCell<T> = throw UnsupportedOperationException("simulated legacy overload")
"""
