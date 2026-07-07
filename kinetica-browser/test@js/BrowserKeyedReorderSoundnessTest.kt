package io.heapy.kinetica.browser

import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Node
import io.heapy.kinetica.TextNode
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserKeyedReorderSoundnessTest {
    /**
     * KSND-001 (sources: INF-001, INF-031, VUE-089, VUE-094, VUE-095, SOL-045).
     */
    @Test
    fun emptyNonemptyCyclesAreResidueFree() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val populated = listOf("a", "b", "c", "d")
        val steps = listOf(emptyList(), populated, emptyList(), populated, emptyList())
        var current: Node = keyedList(steps.first())
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            emit(current)
        }

        try {
            assertEquals(expectedHtml(steps.first()), app.innerHtml())
            assertEquals(0, firstElement(root).childNodes.length)

            steps.drop(1).forEachIndexed { index, keys ->
                current = keyedList(keys)
                app.render()

                val parent = firstElement(root)
                assertEquals(expectedHtml(keys), app.innerHtml())
                if (keys.isEmpty()) {
                    assertEquals(0, parent.childNodes.length)
                }
                if (index == 2) {
                    assertEquals(4, parent.childNodes.length)
                }
            }
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-002 (sources: INF-002, INF-003, VUE-090, VUE-091, VUE-092, VUE-093, PRE-004, PRE-006, PRE-008, RCT-012, SVL-033).
     */
    @Test
    fun appendPrependAndMiddleInsertionsPreserveSurvivorIdentity() {
        installTestDocument()

        fun runScenario(states: List<List<String>>) {
            val root = testDocument().createElement("div").unsafeCast<Element>()
            var keys = states.first()
            var current: Node = keyedList(keys)
            val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
                emit(current)
            }

            try {
                assertEquals(expectedHtml(keys), app.innerHtml())
                states.drop(1).forEach { nextKeys ->
                    val parent = firstElement(root)
                    val before = keys.mapIndexed { index, key -> key to childElement(parent, index) }.toMap()

                    keys = nextKeys
                    current = keyedList(nextKeys)
                    app.render()

                    assertEquals(expectedHtml(nextKeys), app.innerHtml())
                    nextKeys.forEachIndexed { index, key ->
                        before[key]?.let { element ->
                            assertSame(element, childElement(parent, index))
                        }
                    }
                }
            } finally {
                app.dispose()
            }
        }

        listOf(
            listOf(listOf("a", "b"), listOf("a", "b", "c")),
            listOf(listOf("c"), listOf("a", "b", "c")),
            listOf(listOf("1", "2", "4", "5"), listOf("1", "2", "3", "4", "5")),
            listOf(listOf("2", "3", "4"), listOf("1", "2", "3", "4", "5")),
            listOf(
                listOf("1", "2", "3"),
                listOf("1", "4", "2", "3"),
                listOf("1", "4", "5", "2", "3"),
            ),
        ).forEach { states -> runScenario(states) }
    }

    /**
     * KSND-003 (sources: INF-004, VUE-096, VUE-097, VUE-098, PRE-005, PRE-007, PRE-009, RCT-011).
     */
    @Test
    fun removalsAtHeadTailMiddleAndContiguousRunDetachOnlyRemovedRows() {
        installTestDocument()

        fun runScenario(beforeKeys: List<String>, afterKeys: List<String>) {
            val root = testDocument().createElement("div").unsafeCast<Element>()
            var current: Node = keyedList(beforeKeys)
            val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
                emit(current)
            }

            try {
                val parent = firstElement(root)
                val before = beforeKeys.mapIndexed { index, key -> key to childElement(parent, index) }.toMap()

                current = keyedList(afterKeys)
                app.render()

                assertEquals(expectedHtml(afterKeys), app.innerHtml())
                afterKeys.forEachIndexed { index, key ->
                    assertSame(before.getValue(key), childElement(parent, index))
                }
                (beforeKeys - afterKeys.toSet()).forEach { key ->
                    assertNull(before.getValue(key).parentNode)
                }
            } finally {
                app.dispose()
            }
        }

        runScenario(listOf("a", "b", "c"), listOf("b", "c"))
        runScenario(listOf("a", "b", "c"), listOf("a", "b"))
        runScenario(listOf("1", "2", "3", "4", "5"), listOf("1", "2", "4", "5"))
        runScenario(listOf("a", "b", "x", "y", "z", "c", "d"), listOf("a", "b", "c", "d"))
    }

    /**
     * KSND-004 (sources: INF-005, VUE-106, PRE-016, RCT-009, SOL-041).
     */
    @Test
    fun fullReversePreservesEveryNode() {
        installTestDocument()

        fun runScenario(keys: List<String>) {
            val root = testDocument().createElement("div").unsafeCast<Element>()
            var current: Node = keyedList(keys)
            val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
                emit(current)
            }

            try {
                val parent = firstElement(root)
                val before = keys.mapIndexed { index, key -> key to childElement(parent, index) }.toMap()
                val reversed = keys.reversed()

                current = keyedList(reversed)
                app.render()

                assertEquals(expectedHtml(reversed), app.innerHtml())
                reversed.forEachIndexed { index, key ->
                    assertSame(before.getValue(key), childElement(parent, index))
                }
            } finally {
                app.dispose()
            }
        }

        runScenario(listOf("a", "b", "c", "d"))
        runScenario((0..9).map { index -> index.toString() })
    }

    /**
     * KSND-005 (sources: INF-006, VUE-102, PRE-012, PRE-013, RCT-004, RCT-006, SOL-041).
     */
    @Test
    fun swapsExchangeTheSameElementsAndCanSwapBack() {
        installTestDocument()

        run {
            val root = testDocument().createElement("div").unsafeCast<Element>()
            var current: Node = keyedList(listOf("1", "2", "3", "4"))
            val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
                emit(current)
            }

            try {
                val parent = firstElement(root)
                val one = childElement(parent, index = 0)
                val two = childElement(parent, index = 1)
                val three = childElement(parent, index = 2)
                val four = childElement(parent, index = 3)

                current = keyedList(listOf("4", "2", "3", "1"))
                app.render()

                assertEquals(expectedHtml(listOf("4", "2", "3", "1")), app.innerHtml())
                assertSame(four, childElement(parent, index = 0))
                assertSame(two, childElement(parent, index = 1))
                assertSame(three, childElement(parent, index = 2))
                assertSame(one, childElement(parent, index = 3))

                current = keyedList(listOf("1", "2", "3", "4"))
                app.render()

                assertEquals(expectedHtml(listOf("1", "2", "3", "4")), app.innerHtml())
                assertSame(one, childElement(parent, index = 0))
                assertSame(two, childElement(parent, index = 1))
                assertSame(three, childElement(parent, index = 2))
                assertSame(four, childElement(parent, index = 3))
            } finally {
                app.dispose()
            }
        }

        run {
            val root = testDocument().createElement("div").unsafeCast<Element>()
            var current: Node = keyedList(listOf("a", "b"))
            val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
                emit(current)
            }

            try {
                val parent = firstElement(root)
                val a = childElement(parent, index = 0)
                val b = childElement(parent, index = 1)

                current = keyedList(listOf("b", "a"))
                app.render()

                assertEquals(expectedHtml(listOf("b", "a")), app.innerHtml())
                assertSame(b, childElement(parent, index = 0))
                assertSame(a, childElement(parent, index = 1))
            } finally {
                app.dispose()
            }
        }

        run {
            val root = testDocument().createElement("div").unsafeCast<Element>()
            var current: Node = keyedList(listOf("a", "b", "c", "d"))
            val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
                emit(current)
            }

            try {
                val parent = firstElement(root)
                val a = childElement(parent, index = 0)
                val b = childElement(parent, index = 1)
                val c = childElement(parent, index = 2)
                val d = childElement(parent, index = 3)

                current = keyedList(listOf("a", "c", "b", "d"))
                app.render()

                assertEquals(expectedHtml(listOf("a", "c", "b", "d")), app.innerHtml())
                assertSame(a, childElement(parent, index = 0))
                assertSame(c, childElement(parent, index = 1))
                assertSame(b, childElement(parent, index = 2))
                assertSame(d, childElement(parent, index = 3))

                current = keyedList(listOf("a", "b", "c", "d"))
                app.render()

                assertEquals(expectedHtml(listOf("a", "b", "c", "d")), app.innerHtml())
                assertSame(a, childElement(parent, index = 0))
                assertSame(b, childElement(parent, index = 1))
                assertSame(c, childElement(parent, index = 2))
                assertSame(d, childElement(parent, index = 3))
            } finally {
                app.dispose()
            }
        }
    }

    /**
     * KSND-006 (sources: INF-007, INF-008, VUE-099, VUE-100, VUE-101, PRE-010, PRE-014, SOL-041).
     */
    @Test
    fun singleMovesInEveryDirectionPreserveAllNodes() {
        installTestDocument()

        fun runScenario(beforeKeys: List<String>, afterKeys: List<String>) {
            val root = testDocument().createElement("div").unsafeCast<Element>()
            var current: Node = keyedList(beforeKeys)
            val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
                emit(current)
            }

            try {
                val parent = firstElement(root)
                val before = beforeKeys.mapIndexed { index, key -> key to childElement(parent, index) }.toMap()

                current = keyedList(afterKeys)
                app.render()

                assertEquals(expectedHtml(afterKeys), app.innerHtml())
                afterKeys.forEachIndexed { index, key ->
                    assertSame(before.getValue(key), childElement(parent, index))
                }
            } finally {
                app.dispose()
            }
        }

        runScenario(listOf("1", "2", "3"), listOf("2", "3", "1"))
        runScenario(listOf("0", "1", "2", "3", "4"), listOf("4", "0", "1", "2", "3"))
        runScenario(listOf("1", "2", "3", "4"), listOf("2", "3", "1", "4"))
        runScenario(listOf("1", "2", "3", "4"), listOf("1", "4", "2", "3"))
    }

    /**
     * KSND-007 (sources: INF-009, RCT-010, SVL-025).
     */
    @Test
    fun cyclicRotationLoopsReturnToOriginIntact() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val origin = listOf("1", "2", "3", "4")
        var keys = origin
        var current: Node = keyedList(keys)
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            emit(current)
        }

        try {
            val parent = firstElement(root)
            val originalElements = origin.mapIndexed { index, key -> key to childElement(parent, index) }.toMap()

            repeat(4) {
                keys = keys.drop(1) + keys.first()
                current = keyedList(keys)
                app.render()

                assertEquals(expectedHtml(keys), app.innerHtml())
            }
            origin.forEachIndexed { index, key ->
                assertSame(originalElements.getValue(key), childElement(parent, index))
            }

            repeat(4) {
                keys = listOf(keys.last()) + keys.dropLast(1)
                current = keyedList(keys)
                app.render()

                assertEquals(expectedHtml(keys), app.innerHtml())
            }
            origin.forEachIndexed { index, key ->
                assertSame(originalElements.getValue(key), childElement(parent, index))
            }
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-008 (sources: INF-014, VUE-107, SOL-042, PRE-015).
     */
    @Test
    fun lisDefeatingPermutationsPreserveEveryElement() {
        installTestDocument()

        fun runScenario(beforeKeys: List<String>, afterKeys: List<String>) {
            val root = testDocument().createElement("div").unsafeCast<Element>()
            var current: Node = keyedList(beforeKeys)
            val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
                emit(current)
            }

            try {
                val parent = firstElement(root)
                val before = beforeKeys.mapIndexed { index, key -> key to childElement(parent, index) }.toMap()

                current = keyedList(afterKeys)
                app.render()

                assertEquals(expectedHtml(afterKeys), app.innerHtml())
                afterKeys.forEachIndexed { index, key ->
                    assertSame(before.getValue(key), childElement(parent, index))
                }
            } finally {
                app.dispose()
            }
        }

        runScenario(
            beforeKeys = (0..5).map { index -> index.toString() },
            afterKeys = listOf("4", "3", "2", "1", "5", "0"),
        )
        runScenario(
            beforeKeys = (0..9).map { index -> index.toString() },
            afterKeys = listOf("8", "1", "3", "4", "5", "6", "0", "7", "2", "9"),
        )
        runScenario(
            beforeKeys = (0..9).map { index -> index.toString() },
            afterKeys = listOf("9", "5", "0", "7", "1", "2", "3", "4", "6", "8"),
        )
        runScenario(
            beforeKeys = listOf("milk", "bread", "chips", "cookie", "honey"),
            afterKeys = listOf("chips", "bread", "cookie", "milk", "honey"),
        )
    }

    /**
     * KSND-009 (sources: INF-010, INF-011, VUE-103, VUE-104, VUE-105, PRE-026, PRE-028, PRE-030, RCT-011).
     */
    @Test
    fun combinedInsertDeleteAndMoveUpdatesKeepSurvivorsAndDistinctRows() {
        installTestDocument()

        fun runScenario(states: List<List<String>>) {
            val root = testDocument().createElement("div").unsafeCast<Element>()
            var keys = states.first()
            var current: Node = keyedList(keys)
            val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
                emit(current)
            }

            try {
                assertEquals(expectedHtml(keys), app.innerHtml())
                states.drop(1).forEach { nextKeys ->
                    val parent = firstElement(root)
                    val before = keys.mapIndexed { index, key -> key to childElement(parent, index) }.toMap()

                    keys = nextKeys
                    current = keyedList(nextKeys)
                    app.render()

                    assertEquals(expectedHtml(nextKeys), app.innerHtml())
                    assertEquals(nextKeys.size, parent.childNodes.length)
                    nextKeys.forEachIndexed { index, key ->
                        before[key]?.let { element ->
                            assertSame(element, childElement(parent, index))
                        }
                    }
                    val texts = (0 until parent.childNodes.length).map { index ->
                        childElement(parent, index).textContent ?: ""
                    }
                    assertEquals(nextKeys.size, texts.size)
                    assertEquals(nextKeys.toSet().size, texts.toSet().size)
                }
            } finally {
                app.dispose()
            }
        }

        listOf(
            listOf(listOf("a", "b", "c", "d"), listOf("e", "d", "c", "a")),
            listOf(listOf("a", "b", "c", "d", "e", "f", "g"), listOf("b", "c", "a")),
            listOf(listOf("1", "2", "3", "4", "5"), listOf("4", "1", "2", "3", "6")),
            listOf(listOf("c", "d"), listOf("a", "b", "c", "e")),
            listOf(listOf("1", "4", "5"), listOf("4", "6")),
            listOf(
                listOf("A", "B", "C", "D", "E"),
                listOf("B", "E", "C", "D"),
                listOf("B", "E", "D", "C"),
            ),
            listOf(listOf("0", "1", "2", "3", "4"), listOf("2", "3", "4", "5", "6")),
        ).forEach { states -> runScenario(states) }
    }

    /**
     * KSND-010 (sources: INF-012, PRE-020, VUE-103, SOL-041).
     */
    @Test
    fun singleSurvivorPivotIsReusedAndFullKeyReplacementCreatesNewRows() {
        installTestDocument()

        run {
            val root = testDocument().createElement("div").unsafeCast<Element>()
            var current: Node = keyedList(listOf("1", "2", "3"))
            val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
                emit(current)
            }

            try {
                val parent = firstElement(root)
                val survivor = childElement(parent, index = 1)

                current = keyedList(listOf("4", "2", "5"))
                app.render()

                assertEquals(expectedHtml(listOf("4", "2", "5")), app.innerHtml())
                assertSame(survivor, childElement(parent, index = 1))
            } finally {
                app.dispose()
            }
        }

        run {
            val root = testDocument().createElement("div").unsafeCast<Element>()
            var current: Node = keyedList(listOf("0", "1", "2"))
            val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
                emit(current)
            }

            try {
                val parent = firstElement(root)
                val previous = (0 until parent.childNodes.length).map { index -> childElement(parent, index) }

                current = keyedList(listOf("3", "4", "5"))
                app.render()

                assertEquals(expectedHtml(listOf("3", "4", "5")), app.innerHtml())
                val next = (0 until parent.childNodes.length).map { index -> childElement(parent, index) }
                assertTrue(
                    previous.none { previousElement ->
                        next.any { nextElement -> previousElement === nextElement }
                    },
                    "Full key replacement must create a disjoint DOM element set.",
                )
            } finally {
                app.dispose()
            }
        }
    }

    /**
     * KSND-011 (sources: INF-013, INF-018).
     */
    @Test
    fun largePartialOverlapCalendarShuffleKeepsOverlapIdentity() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val initial = (31..36).map { week -> "wk$week" } +
            (1..31).map { day -> "d$day" } +
            (1..11).map { outside -> "o$outside" }
        val next = (35..40).map { week -> "wk$week" } +
            (29..31).map { outside -> "o$outside" } +
            (1..30).map { day -> "d$day" } +
            (1..9).map { outside -> "o$outside" }
        var current: Node = keyedList(initial)
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            emit(current)
        }

        try {
            val parent = firstElement(root)
            val before = initial.mapIndexed { index, key -> key to childElement(parent, index) }.toMap()

            current = keyedList(next)
            app.render()

            assertEquals(expectedHtml(next), app.innerHtml())
            assertEquals(next.size, parent.childNodes.length)
            next.forEachIndexed { index, key ->
                before[key]?.let { element ->
                    assertSame(element, childElement(parent, index))
                }
            }
            val texts = (0 until parent.childNodes.length).map { index ->
                childElement(parent, index).textContent ?: ""
            }
            assertEquals(next.size, texts.toSet().size)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-012 (sources: INF-017, INF-020, SVL-020, SVL-021, PRE-033, VUE-108, SOL-040).
     */
    @Test
    fun seededRandomPermutationFuzzRestoresCanonicalList() {
        installTestDocument()
        var seed = 42
        fun nextInt(bound: Int): Int {
            seed = seed * 1664525 + 1013904223
            return (seed ushr 1) % bound
        }
        fun randomSubset(keys: List<String>): List<String> {
            val pool = keys.toMutableList()
            val selected = mutableListOf<String>()
            repeat(nextInt(bound = 13)) {
                val index = nextInt(pool.size)
                selected += pool.removeAt(index)
            }
            return selected
        }

        val root = testDocument().createElement("div").unsafeCast<Element>()
        val canonical = ('a'..'m').map { letter -> letter.toString() }
        var currentKeys = emptyList<String>()
        var current: Node = keyedList(currentKeys)
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            emit(current)
        }

        try {
            repeat(100) { iteration ->
                currentKeys = if ((iteration + 1) % 10 == 0) {
                    canonical
                } else {
                    randomSubset(canonical)
                }
                current = keyedList(currentKeys)
                app.render()

                assertEquals(expectedHtml(currentKeys), app.innerHtml())
            }

            currentKeys = canonical
            current = keyedList(currentKeys)
            app.render()

            assertEquals(expectedHtml(canonical), app.innerHtml())
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-013 (sources: INF-015, INF-016).
     */
    @Test
    fun hostileKeyStringsAndNearCollisionKeysUseExactStringMatching() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val weird = "<WEIRD/&\\key>"
        val insane = "INSANE/(/&\\key"
        val crazy = "<CRAZY/&\\key>"
        var current: Node = keyedList(listOf(weird))
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            emit(current)
        }

        try {
            val parent = firstElement(root)
            val weirdElement = childElement(parent, index = 0)
            val hostileNext = listOf(insane, crazy, weird)

            current = keyedList(hostileNext)
            app.render()

            assertEquals(expectedHtml(hostileNext), app.innerHtml())
            assertSame(weirdElement, childElement(parent, index = 2))

            val nearCollisions = listOf("1", "01", "1 ", "1x", "2", "3")
            current = keyedList(nearCollisions)
            app.render()

            assertEquals(expectedHtml(nearCollisions), app.innerHtml())
            assertEquals(nearCollisions.size, parent.childNodes.length)
            val one = childElement(parent, index = 0)
            val zeroOne = childElement(parent, index = 1)
            val oneSpace = childElement(parent, index = 2)
            val reordered = listOf("01", "1 ", "1", "1x", "3", "2")

            current = keyedList(reordered)
            app.render()

            assertEquals(expectedHtml(reordered), app.innerHtml())
            assertSame(zeroOne, childElement(parent, index = 0))
            assertSame(oneSpace, childElement(parent, index = 1))
            assertSame(one, childElement(parent, index = 2))
            assertEquals(reordered.size, parent.childNodes.length)
        } finally {
            app.dispose()
        }
    }
}

private fun keyedList(keys: List<String>): Node =
    HostNode(
        tag = "section",
        children = keys.map { key ->
            HostNode(
                tag = "div",
                key = key,
                children = listOf(TextNode(key, semantics = null)),
            )
        },
    )

private fun expectedHtml(keys: List<String>): String {
    fun escapeText(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    fun escapeAttribute(value: String): String =
        escapeText(value).replace("\"", "&quot;")

    return "<section>${keys.joinToString(separator = "") { key ->
        """<div data-kinetica-key="${escapeAttribute(key)}">${escapeText(key)}</div>"""
    }}</section>"
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")
