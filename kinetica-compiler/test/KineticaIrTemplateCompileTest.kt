package io.heapy.kinetica.compiler

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Node
import io.heapy.kinetica.NodeFlags
import io.heapy.kinetica.TemplateNode
import io.heapy.kinetica.materialize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KineticaIrTemplateCompileTest {
    private val harness = KineticaCompilationHarness()

    @Test
    fun singleTextTemplateTransformFiresAndMaterializesAtRuntime() {
        harness.compile(mapOf("app/TemplateSample.kt" to TEMPLATE_SAMPLE)).use { compiled ->
            compiled.assertTransformFired("emitted 1 template definitions")
            val facade = compiled.loadClass("app.TemplateSampleKt")
            val fieldNames = facade.declaredFields.map { field -> field.name }
            assertTrue(fieldNames.any { name -> name.startsWith("kineticaTemplate\$") })

            val rendered = renderLabel(facade, "Inbox")
            val template = assertIs<TemplateNode>(rendered)
            assertEquals("Inbox", template.values.single())
            assertEquals(
                HostNode(
                    tag = "span",
                    props = mapOf("class" to "badge"),
                    children = listOf(io.heapy.kinetica.TextNode("Inbox", semantics = null)),
                    flags = NodeFlags.CHILDREN_SINGLE_TEXT,
                ),
                template.materialize(),
            )
        }
    }

    @Test
    fun transformsKillSwitchLeavesTemplateCandidateAsHostNode() {
        harness.compile(
            sources = mapOf("app/TemplateSample.kt" to TEMPLATE_SAMPLE),
            transforms = "off",
        ).use { compiled ->
            compiled.assertTransformDidNotFire("emitted 1 template definitions")
            val facade = compiled.loadClass("app.TemplateSampleKt")
            val fieldNames = facade.declaredFields.map { field -> field.name }
            assertFalse(fieldNames.any { name -> name.startsWith("kineticaTemplate\$") })

            val rendered = renderLabel(facade, "Inbox")
            assertIs<HostNode>(rendered)
            assertEquals("Inbox", rendered.children.single().let { (it as io.heapy.kinetica.TextNode).value })
        }
    }

    @Test
    fun propsLessSingleTextHostTemplatesAndMaterializesAtRuntime() {
        harness.compile(mapOf("app/PropsLessTemplate.kt" to PROPS_LESS_TEMPLATE)).use { compiled ->
            compiled.assertTransformFired("emitted 1 template definitions")
            val facade = compiled.loadClass("app.PropsLessTemplateKt")

            val rendered = renderStringMethod(facade, "renderPropsLess", "Inbox")
            val template = assertIs<TemplateNode>(rendered)
            assertEquals("Inbox", template.values.single())
            assertEquals(
                HostNode(
                    tag = "p",
                    props = emptyMap(),
                    children = listOf(io.heapy.kinetica.TextNode("Inbox", semantics = null)),
                    flags = NodeFlags.CHILDREN_SINGLE_TEXT,
                ),
                template.materialize(),
            )
        }
    }

    @Test
    fun explicitZeroArgPropsTemplatesAndMaterializesAtRuntime() {
        harness.compile(mapOf("app/ZeroArgPropsTemplate.kt" to ZERO_ARG_PROPS_TEMPLATE)).use { compiled ->
            compiled.assertTransformFired("emitted 1 template definitions")
            val facade = compiled.loadClass("app.ZeroArgPropsTemplateKt")

            val rendered = renderStringMethod(facade, "renderZeroArgProps", "Inbox")
            val template = assertIs<TemplateNode>(rendered)
            assertEquals("Inbox", template.values.single())
            assertEquals(
                HostNode(
                    tag = "p",
                    props = emptyMap(),
                    children = listOf(io.heapy.kinetica.TextNode("Inbox", semantics = null)),
                    flags = NodeFlags.CHILDREN_SINGLE_TEXT,
                ),
                template.materialize(),
            )
        }
    }

    @Test
    fun explicitNullTextSemanticsTemplatesButDefaultedSemanticsDoesNot() {
        harness.compile(mapOf("app/TextSemanticsTemplate.kt" to TEXT_SEMANTICS_TEMPLATE)).use { compiled ->
            compiled.assertTransformFired("emitted 1 template definitions")
            val facade = compiled.loadClass("app.TextSemanticsTemplateKt")

            val rendered = renderStringMethod(facade, "renderSemantics", "Inbox")
            val children = assertIs<FragmentNode>(rendered).children
            assertEquals(2, children.size)
            assertIs<TemplateNode>(children[0])
            assertIs<HostNode>(children[1])
        }
    }

    @Test
    fun reversedOrderTemplateRecognizesHoistedConstPropsThroughIndex() {
        harness.compile(
            sources = mapOf("app/ReversedOrderTemplate.kt" to REVERSED_ORDER_TEMPLATE),
            irTransformOrder = "hoist-first",
        ).use { compiled ->
            compiled.assertTransformFired("interned 1 const props, hoisted 0 static leaf hosts")
            compiled.assertTransformFired("emitted 1 template definitions")
            val facade = compiled.loadClass("app.ReversedOrderTemplateKt")

            val rendered = renderStringMethod(facade, "renderIndexed", "Inbox")
            val template = assertIs<TemplateNode>(rendered)
            assertEquals(
                HostNode(
                    tag = "p",
                    props = mapOf("class" to "indexed"),
                    children = listOf(io.heapy.kinetica.TextNode("Inbox", semantics = null)),
                    flags = NodeFlags.CHILDREN_SINGLE_TEXT,
                ),
                template.materialize(),
            )
        }
    }

    @Test
    fun sourcePropsFieldIsNotRecognizedAsTemplateInputWithoutConstPropsIndexEntry() {
        harness.compile(mapOf("app/SourcePropsFieldTemplate.kt" to SOURCE_PROPS_FIELD_TEMPLATE)).use { compiled ->
            compiled.assertTransformDidNotFire("emitted 1 template definitions")
            val facade = compiled.loadClass("app.SourcePropsFieldTemplateKt")

            val rendered = renderStringMethod(facade, "renderSourcePropsField", "Inbox")
            assertEquals(
                HostNode(
                    tag = "p",
                    props = mapOf("class" to "source"),
                    children = listOf(io.heapy.kinetica.TextNode("Inbox", semantics = null)),
                    flags = NodeFlags.CHILDREN_SINGLE_TEXT,
                ),
                rendered,
            )
        }
    }

    @Test
    fun templateTransformOnlyRewritesSafeSingleTextHosts() {
        harness.compile(mapOf("app/TemplateShapes.kt" to TEMPLATE_SHAPES)).use { compiled ->
            compiled.assertTransformFired("emitted 2 template definitions")
            val facade = compiled.loadClass("app.TemplateShapesKt")
            assertEquals(
                2,
                facade.declaredFields.count { field -> field.name.startsWith("kineticaTemplate\$") },
            )

            val rendered = renderAllShapes(facade, "Inbox", "strong", "dynamic")
            val children = assertIs<FragmentNode>(rendered).children
            assertEquals(10, children.size)
            assertIs<TemplateNode>(children[0])
            assertIs<TemplateNode>(children[3])
            children.filterIndexed { index, _ -> index != 0 && index != 3 }.forEach { child ->
                assertIs<HostNode>(child)
            }
        }
    }

    @Test
    fun userPropsFunctionIsNotTreatedAsPropsOfTemplateInput() {
        harness.compile(mapOf("app/UserPropsTemplate.kt" to USER_PROPS_TEMPLATE)).use { compiled ->
            val facade = compiled.loadClass("app.UserPropsTemplateKt")
            val rendered = renderUserProps(facade, "Inbox")
            val materialized = if (rendered is TemplateNode) rendered.materialize() else rendered

            assertEquals(
                HostNode(
                    tag = "span",
                    props = mapOf("class" to "computed-x"),
                    children = listOf(io.heapy.kinetica.TextNode("Inbox", semantics = null)),
                    flags = NodeFlags.CHILDREN_SINGLE_TEXT,
                ),
                materialized,
            )
            compiled.assertTransformDidNotFire("emitted 1 template definitions")
        }
    }

    @Test
    fun textUsingContentLambdaReceiverIsNotLiftedIntoTemplateCallsite() {
        harness.compile(mapOf("app/ReceiverTextTemplate.kt" to RECEIVER_TEXT_TEMPLATE)).use { compiled ->
            compiled.assertTransformDidNotFire("emitted 1 template definitions")
            val facade = compiled.loadClass("app.ReceiverTextTemplateKt")
            val rendered = renderReceiverText(facade)

            assertEquals(
                HostNode(
                    tag = "p",
                    props = mapOf("class" to "x"),
                    children = listOf(io.heapy.kinetica.TextNode("scope-text", semantics = null)),
                    flags = NodeFlags.CHILDREN_SINGLE_TEXT,
                ),
                rendered,
            )
        }
    }

    private fun renderLabel(facade: Class<*>, label: String): Node {
        val render = facade.getDeclaredMethod(
            "renderLabel",
            KineticaRuntime::class.java,
            ComponentScope::class.java,
            String::class.java,
        )
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        return render.invoke(null, runtime, scope, label) as Node
    }

    private fun renderAllShapes(
        facade: Class<*>,
        label: String,
        tag: String,
        className: String,
    ): Node {
        val render = facade.getDeclaredMethod(
            "renderAllShapes",
            KineticaRuntime::class.java,
            ComponentScope::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        return render.invoke(null, runtime, scope, label, tag, className) as Node
    }

    private fun renderUserProps(facade: Class<*>, label: String): Node {
        val render = facade.getDeclaredMethod(
            "renderUserProps",
            KineticaRuntime::class.java,
            ComponentScope::class.java,
            String::class.java,
        )
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        return render.invoke(null, runtime, scope, label) as Node
    }

    private fun renderReceiverText(facade: Class<*>): Node {
        val render = facade.getDeclaredMethod(
            "renderReceiverText",
            KineticaRuntime::class.java,
            ComponentScope::class.java,
        )
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        return render.invoke(null, runtime, scope) as Node
    }

    private fun renderStringMethod(facade: Class<*>, methodName: String, value: String): Node {
        val render = facade.getDeclaredMethod(
            methodName,
            KineticaRuntime::class.java,
            ComponentScope::class.java,
            String::class.java,
        )
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        return render.invoke(null, runtime, scope, value) as Node
    }

    private companion object {
        private const val TEMPLATE_SAMPLE = """
            package app

            import io.heapy.kinetica.ComponentScope
            import io.heapy.kinetica.KineticaRuntime
            import io.heapy.kinetica.Node
            import io.heapy.kinetica.UiComponent
            import io.heapy.kinetica.host
            import io.heapy.kinetica.propsOf
            import io.heapy.kinetica.text

            @UiComponent(skippable = false)
            fun ComponentScope.Label(label: String) {
                host("span", props = propsOf("class", "badge")) {
                    text(label, semantics = null)
                }
            }

            fun renderLabel(runtime: KineticaRuntime, scope: ComponentScope, label: String): Node =
                runtime.render(scope) {
                    Label(label)
                }.tree
        """

        private const val PROPS_LESS_TEMPLATE = """
            package app

            import io.heapy.kinetica.ComponentScope
            import io.heapy.kinetica.KineticaRuntime
            import io.heapy.kinetica.Node
            import io.heapy.kinetica.UiComponent
            import io.heapy.kinetica.host
            import io.heapy.kinetica.text

            @UiComponent(skippable = false)
            fun ComponentScope.PropsLess(value: String) {
                host("p") {
                    text(value, semantics = null)
                }
            }

            fun renderPropsLess(runtime: KineticaRuntime, scope: ComponentScope, value: String): Node =
                runtime.render(scope) {
                    PropsLess(value)
                }.tree
        """

        private const val ZERO_ARG_PROPS_TEMPLATE = """
            package app

            import io.heapy.kinetica.ComponentScope
            import io.heapy.kinetica.KineticaRuntime
            import io.heapy.kinetica.Node
            import io.heapy.kinetica.UiComponent
            import io.heapy.kinetica.host
            import io.heapy.kinetica.propsOf
            import io.heapy.kinetica.text

            @UiComponent(skippable = false)
            fun ComponentScope.ZeroArgProps(value: String) {
                host("p", props = propsOf()) {
                    text(value, semantics = null)
                }
            }

            fun renderZeroArgProps(runtime: KineticaRuntime, scope: ComponentScope, value: String): Node =
                runtime.render(scope) {
                    ZeroArgProps(value)
                }.tree
        """

        private const val TEXT_SEMANTICS_TEMPLATE = """
            package app

            import io.heapy.kinetica.ComponentScope
            import io.heapy.kinetica.KineticaRuntime
            import io.heapy.kinetica.Node
            import io.heapy.kinetica.UiComponent
            import io.heapy.kinetica.fragment
            import io.heapy.kinetica.host
            import io.heapy.kinetica.propsOf
            import io.heapy.kinetica.text

            @UiComponent(skippable = false)
            fun ComponentScope.ExplicitNull(value: String) {
                host("p", props = propsOf("class", "x")) {
                    text(value, semantics = null)
                }
            }

            @UiComponent(skippable = false)
            fun ComponentScope.DefaultedSemantics(value: String) {
                host("p", props = propsOf("class", "x")) {
                    text(value)
                }
            }

            fun renderSemantics(runtime: KineticaRuntime, scope: ComponentScope, value: String): Node =
                runtime.render(scope) {
                    fragment {
                        ExplicitNull(value)
                        DefaultedSemantics(value)
                    }
                }.tree
        """

        private const val REVERSED_ORDER_TEMPLATE = """
            package app

            import io.heapy.kinetica.ComponentScope
            import io.heapy.kinetica.KineticaRuntime
            import io.heapy.kinetica.Node
            import io.heapy.kinetica.UiComponent
            import io.heapy.kinetica.host
            import io.heapy.kinetica.propsOf
            import io.heapy.kinetica.text

            @UiComponent(skippable = false)
            fun ComponentScope.Indexed(value: String) {
                host("p", props = propsOf("class", "indexed")) {
                    text(value, semantics = null)
                }
            }

            fun renderIndexed(runtime: KineticaRuntime, scope: ComponentScope, value: String): Node =
                runtime.render(scope) {
                    Indexed(value)
                }.tree
        """

        private const val SOURCE_PROPS_FIELD_TEMPLATE = """
            package app

            import io.heapy.kinetica.ComponentScope
            import io.heapy.kinetica.KineticaRuntime
            import io.heapy.kinetica.Node
            import io.heapy.kinetica.UiComponent
            import io.heapy.kinetica.host
            import io.heapy.kinetica.propsOf
            import io.heapy.kinetica.text

            private val SOURCE_PROPS = propsOf("class", "source")

            @UiComponent(skippable = false)
            fun ComponentScope.SourcePropsField(value: String) {
                host("p", props = SOURCE_PROPS) {
                    text(value, semantics = null)
                }
            }

            fun renderSourcePropsField(runtime: KineticaRuntime, scope: ComponentScope, value: String): Node =
                runtime.render(scope) {
                    SourcePropsField(value)
                }.tree
        """

        private const val USER_PROPS_TEMPLATE = """
            package app

            import io.heapy.kinetica.ComponentScope
            import io.heapy.kinetica.KineticaRuntime
            import io.heapy.kinetica.Node
            import io.heapy.kinetica.UiComponent
            import io.heapy.kinetica.host
            import io.heapy.kinetica.propsOf
            import io.heapy.kinetica.text

            fun myProps(name: String, value: String): Map<String, String> =
                propsOf(name, "computed-" + value)

            @UiComponent(skippable = false)
            fun ComponentScope.UserProps(label: String) {
                host("span", props = myProps("class", "x")) {
                    text(label, semantics = null)
                }
            }

            fun renderUserProps(runtime: KineticaRuntime, scope: ComponentScope, label: String): Node =
                runtime.render(scope) {
                    UserProps(label)
                }.tree
        """

        private const val RECEIVER_TEXT_TEMPLATE = """
            package app

            import io.heapy.kinetica.ComponentScope
            import io.heapy.kinetica.KineticaRuntime
            import io.heapy.kinetica.Node
            import io.heapy.kinetica.UiComponent
            import io.heapy.kinetica.host
            import io.heapy.kinetica.propsOf
            import io.heapy.kinetica.text

            fun ComponentScope.scopeExtension(): String = "scope-text"

            @UiComponent(skippable = false)
            fun ComponentScope.ReceiverText() {
                host("p", props = propsOf("class", "x")) {
                    text(scopeExtension(), semantics = null)
                }
            }

            fun renderReceiverText(runtime: KineticaRuntime, scope: ComponentScope): Node =
                runtime.render(scope) {
                    ReceiverText()
                }.tree
        """

        private const val TEMPLATE_SHAPES = """
            package app

            import io.heapy.kinetica.ComponentScope
            import io.heapy.kinetica.KineticaRuntime
            import io.heapy.kinetica.Node
            import io.heapy.kinetica.Semantics
            import io.heapy.kinetica.UiComponent
            import io.heapy.kinetica.fragment
            import io.heapy.kinetica.host
            import io.heapy.kinetica.propsOf
            import io.heapy.kinetica.text

            @UiComponent(skippable = false)
            fun ComponentScope.Eligible(label: String) {
                host("span", props = propsOf("class", "badge")) {
                    text(label, semantics = null)
                }
            }

            @UiComponent(skippable = false)
            fun ComponentScope.DynamicTag(tag: String, label: String) {
                host(tag, props = propsOf("class", "badge")) {
                    text(label, semantics = null)
                }
            }

            @UiComponent(skippable = false)
            fun ComponentScope.DynamicProp(className: String, label: String) {
                host("span", props = propsOf("class", className)) {
                    text(label, semantics = null)
                }
            }

            @UiComponent(skippable = false)
            fun ComponentScope.OmittedProps(label: String) {
                host("span") {
                    text(label, semantics = null)
                }
            }

            @UiComponent(skippable = false)
            fun ComponentScope.HostSemantics(label: String) {
                host("span", props = propsOf("class", "badge"), semantics = Semantics(testTag = "badge")) {
                    text(label, semantics = null)
                }
            }

            @UiComponent(skippable = false)
            fun ComponentScope.HostKey(label: String) {
                host("span", props = propsOf("class", "badge"), key = "badge") {
                    text(label, semantics = null)
                }
            }

            @UiComponent(skippable = false)
            fun ComponentScope.MultipleStatements(label: String) {
                host("span", props = propsOf("class", "badge")) {
                    text("prefix", semantics = null)
                    text(label, semantics = null)
                }
            }

            @UiComponent(skippable = false)
            fun ComponentScope.NestedHost(label: String) {
                host("span", props = propsOf("class", "badge")) {
                    host("strong", props = propsOf("class", label)) {
                        text(label, semantics = null)
                    }
                }
            }

            @UiComponent(skippable = false)
            fun ComponentScope.StruckText(label: String) {
                host("span", props = propsOf("class", "badge")) {
                    text(label, strikethrough = true, semantics = null)
                }
            }

            @UiComponent(skippable = false)
            fun ComponentScope.DefaultTextSemantics(label: String) {
                host("span", props = propsOf("class", "badge")) {
                    text(label)
                }
            }

            fun renderAllShapes(
                runtime: KineticaRuntime,
                scope: ComponentScope,
                label: String,
                tag: String,
                className: String,
            ): Node =
                runtime.render(scope) {
                    fragment {
                        Eligible(label)
                        DynamicTag(tag, label)
                        DynamicProp(className, label)
                        OmittedProps(label)
                        HostSemantics(label)
                        HostKey(label)
                        MultipleStatements(label)
                        NestedHost(label)
                        StruckText(label)
                        DefaultTextSemantics(label)
                    }
                }.tree
        """
    }
}
