package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals

private data class PayloadSelection(val key: String)

@UiComponent
private fun ComponentScope.PayloadHost(onSelect: (PayloadSelection) -> Unit) {
    host("custom", props = mapOf("event:select" to hostEvent<PayloadSelection>(onEvent = onSelect)))
}

class HostEventPayloadTest {
    @Test
    fun compiledPayloadEventKeepsIdentityAndUsesTheLatestHandler() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val received = mutableListOf<String>()
        var prefix = "first"
        fun render(): String {
            val captured = prefix
            val tree = runtime.render(scope) { PayloadHost { received += "$captured:${it.key}" } }.tree.materializeDeep()
            fun id(node: Node): String? = when (node) {
                is HostNode -> node.props["event:select"] ?: node.children.firstNotNullOfOrNull(::id)
                is FragmentNode -> node.children.firstNotNullOfOrNull(::id)
                else -> null
            }
            return requireNotNull(id(tree))
        }
        val first = render()
        runtime.dispatch(first, PayloadSelection("123:start-a"))
        prefix = "second"
        assertEquals(first, render())
        runtime.dispatch(first, PayloadSelection("456:start-b"))
        assertEquals(listOf("first:123:start-a", "second:456:start-b"), received)
        scope.dispose()
        runtime.dispose()
    }
}
