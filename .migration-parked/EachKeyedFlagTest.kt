package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NodeFlags.CHILDREN_KEYED certification: `each` proves "every child is exactly one HostNode
 * keyed by its unique row key" and `host` stamps the flag — renderers may then run keyed
 * reconciliation without re-scanning children. Anything the proof doesn't cover must stay
 * unflagged (a wrong flag would mis-reconcile), so the poisoning cases matter most.
 */
class EachKeyedFlagTest {
    private data class Item(val id: Int, val label: String)

    private val items = listOf(Item(1, "one"), Item(2, "two"), Item(3, "three"))

    private fun tbodyFlags(memoize: Boolean = true, body: ComponentScope.() -> Unit): Int {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val tree = runtime.render(scope) { body() }.tree
        fun find(node: Node): HostNode? = when (node) {
            is HostNode -> if (node.tag == "tbody") node else node.children.firstNotNullOfOrNull(::find)
            is FragmentNode -> node.children.firstNotNullOfOrNull(::find)
            else -> null
        }
        return find(tree)?.flags ?: error("no tbody in $tree")
    }

    @Test
    fun keyedEachRowsCertifyTheParentHost() {
        val flags = tbodyFlags {
            host("tbody") {
                each(items, key = { it.id }) { item ->
                    host("tr", key = item.id) { text(item.label, semantics = null) }
                }
            }
        }
        assertEquals(NodeFlags.CHILDREN_KEYED, flags)
    }

    @Test
    fun nonMemoizedEachAlsoCertifies() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val tree = runtime.render(scope) {
            host("tbody") {
                each(items, key = { it.id }, memoize = false) { item ->
                    host("tr", key = item.id) { text(item.label, semantics = null) }
                }
            }
        }.tree
        val tbody = (tree as HostNode)
        assertEquals(NodeFlags.CHILDREN_KEYED, tbody.flags)
    }

    @Test
    fun memoizedSecondRenderKeepsTheFlag() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        fun render(): HostNode = runtime.render(scope) {
            host("tbody") {
                each(items, key = { it.id }) { item ->
                    host("tr", key = item.id) { text(item.label, semantics = null) }
                }
            }
        }.tree as HostNode
        render()
        // second render reuses cached rows; certification must come from the row cache
        assertEquals(NodeFlags.CHILDREN_KEYED, render().flags)
    }

    @Test
    fun emissionBeforeEachPoisonsTheFlag() {
        val flags = tbodyFlags {
            host("tbody") {
                host("tr", key = "header")
                each(items, key = { it.id }) { item ->
                    host("tr", key = item.id) { text(item.label, semantics = null) }
                }
            }
        }
        assertEquals(0, flags)
    }

    @Test
    fun emissionAfterEachPoisonsTheFlag() {
        val flags = tbodyFlags {
            host("tbody") {
                each(items, key = { it.id }) { item ->
                    host("tr", key = item.id) { text(item.label, semantics = null) }
                }
                host("tr", key = "footer")
            }
        }
        assertEquals(0, flags)
    }

    @Test
    fun rowEmittingTwoNodesPoisonsTheFlag() {
        val flags = tbodyFlags {
            host("tbody") {
                each(items, key = { it.id }) { item ->
                    host("tr", key = item.id)
                    host("tr", key = "${item.id}-detail")
                }
            }
        }
        assertEquals(0, flags)
    }

    @Test
    fun rowKeyMismatchPoisonsTheFlag() {
        // host keys not derived from the row key could collide across rows — not certifiable
        val flags = tbodyFlags {
            host("tbody") {
                each(items, key = { it.id }) { item ->
                    host("tr", key = "static") { text(item.label, semantics = null) }
                }
            }
        }
        assertEquals(0, flags)
    }

    @Test
    fun unkeyedRowHostPoisonsTheFlag() {
        val flags = tbodyFlags {
            host("tbody") {
                each(items, key = { it.id }) { item ->
                    host("tr") { text(item.label, semantics = null) }
                }
            }
        }
        assertEquals(0, flags)
    }

    @Test
    fun emptyEachDoesNotCertify() {
        val flags = tbodyFlags {
            host("tbody") {
                each(emptyList<Item>(), key = { it.id }) { item ->
                    host("tr", key = item.id)
                }
            }
        }
        assertEquals(0, flags)
    }

    @Test
    fun plainSingleTextChildCertifiesHostColumnAndRow() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun renderFlags(body: ComponentScope.() -> Unit): Int =
            (runtime.render(scope, body).tree as HostNode).flags

        assertEquals(NodeFlags.CHILDREN_SINGLE_TEXT, renderFlags { host("p") { text("plain", semantics = null) } })
        assertEquals(NodeFlags.CHILDREN_SINGLE_TEXT, renderFlags { column { text("plain", semantics = null) } })
        assertEquals(NodeFlags.CHILDREN_SINGLE_TEXT, renderFlags { row { text("plain", semantics = null) } })
    }

    @Test
    fun singleTextFlagRejectsWrappedOrNonSingleTextChildren() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun renderFlags(body: ComponentScope.() -> Unit): Int =
            (runtime.render(scope, body).tree as HostNode).flags

        assertEquals(NodeFlags.CHILDREN_SINGLE_TEXT, renderFlags { host("p") { text("default semantics") } })
        assertEquals(0, renderFlags { host("p") { text("image", semantics = Semantics(role = Role.Image, label = "Image")) } })
        assertEquals(0, renderFlags { host("p") { text("deleted", strikethrough = true, semantics = null) } })
        assertEquals(0, renderFlags { host("p") { text("one", semantics = null); text("two", semantics = null) } })
    }
}
