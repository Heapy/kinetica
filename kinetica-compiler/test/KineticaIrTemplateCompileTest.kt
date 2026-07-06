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
    fun templateTransformOnlyRewritesSafeSingleTextHosts() {
        harness.compile(mapOf("app/TemplateShapes.kt" to TEMPLATE_SHAPES)).use { compiled ->
            compiled.assertTransformFired("emitted 1 template definitions")
            val facade = compiled.loadClass("app.TemplateShapesKt")
            assertEquals(
                1,
                facade.declaredFields.count { field -> field.name.startsWith("kineticaTemplate\$") },
            )

            val rendered = renderAllShapes(facade, "Inbox", "strong", "dynamic")
            val children = assertIs<FragmentNode>(rendered).children
            assertEquals(10, children.size)
            assertIs<TemplateNode>(children[0])
            children.drop(1).forEach { child ->
                assertIs<HostNode>(child)
            }
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
