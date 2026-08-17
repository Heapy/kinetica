package fixture

import io.heapy.kinetica.TextNode
import io.heapy.kinetica.testing.KineticaTest
import io.heapy.kinetica.testing.hasTestTag
import io.heapy.kinetica.testing.hasText
import kotlin.test.Test
import kotlin.test.assertEquals

class CounterTest {
    /**
     * Without the compiler plugin `state` throws MissingKineticaPluginException, so a green run
     * here is proof the Gradle plugin wired it into this compilation.
     */
    @Test
    fun stateAndEventsWorkInAGradleBuild() {
        val root = KineticaTest.render { Counter() }

        assertEquals("count: 0", (root.node(hasText("count: 0")).node as TextNode).value)
        root.click(hasTestTag("increment"))
        assertEquals("count: 1", (root.node(hasText("count: 1")).node as TextNode).value)
    }
}
