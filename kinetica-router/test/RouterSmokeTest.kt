package io.heapy.kinetica.router

import io.heapy.kinetica.BackStack
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.LazyListState
import io.heapy.kinetica.MutableCell
import io.heapy.kinetica.Route
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.lazyItems
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RouterSmokeTest {
    @Test
    fun navigationOptionsValidateAndStringRouteCodecRoundTrips() {
        assertFailsWith<IllegalArgumentException> {
            NavTransition.slide(durationMillis = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NavOptions(retainPreviousEntries = -1)
        }

        val codec = routeCodec<TestRoute>(
            encode = { route ->
                when (route) {
                    TestRoute.Home -> "home"
                    TestRoute.Search -> "search"
                    is TestRoute.Details -> "details:${route.id}:${route.tab}"
                }
            },
            decode = { value ->
                when {
                    value == "home" -> TestRoute.Home
                    value == "search" -> TestRoute.Search
                    value.startsWith("details:") -> {
                        val parts = value.split(":")
                        TestRoute.Details(parts[1], parts[2])
                    }
                    else -> error("Unknown route $value")
                }
            },
        )
        val stack = BackStack<TestRoute>(TestRoute.Home)

        assertEquals("search", codec.encode(TestRoute.Search))
        assertEquals(TestRoute.Details("42", "reviews"), codec.decode("details:42:reviews"))
        assertFailsWith<IllegalArgumentException> {
            stack.replaceFromEncoded(codec, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            stack.replaceAll()
        }

        stack.push(TestRoute.Search)
        InMemoryRouteHistory().let { emptyHistory ->
            stack.restoreFromHistory(emptyHistory, codec)
        }
        assertEquals(listOf(TestRoute.Home, TestRoute.Search), stack.value)
    }

    @Test
    fun jsonRouteCodecRoundTripsDeepLinksAndBackStack() {
        val codec = jsonRouteCodec(TestRoute.serializer())
        val directCodec = JsonRouteCodec(TestRoute.serializer())
        val details = TestRoute.Details(id = "42", tab = "reviews")

        val encoded = codec.encode(details)
        assertEquals(details, codec.decode(encoded))
        assertEquals(details, directCodec.decode(directCodec.encode(details)))
        assertEquals(details, codec.parseDeepLink(encoded).route)
        assertEquals(encoded, codec.deepLink(details).encoded)

        val stack = BackStack<TestRoute>(TestRoute.Home)
        stack.push(details)
        val encodedStack = stack.encodedRoutes(codec)

        stack.replaceAll(TestRoute.Home)
        stack.replaceFromEncoded(codec, encodedStack)

        assertEquals(listOf(TestRoute.Home, details), stack.value)
    }

    @Test
    fun hostBackDispatcherPopsStackAndRouteHistoryRestoresEncodedRoutes() {
        val codec = jsonRouteCodec(TestRoute.serializer())
        val stack = BackStack<TestRoute>(TestRoute.Home)
        val dispatcher = InMemoryHostBackDispatcher()
        val backBinding = stack.bindHostBack(dispatcher)

        stack.push(TestRoute.Details("42", "reviews"))
        stack.push(TestRoute.Search)

        assertEquals(true, dispatcher.dispatchBack())
        assertEquals(listOf(TestRoute.Home, TestRoute.Details("42", "reviews")), stack.value)
        assertEquals(true, dispatcher.dispatchBack())
        assertEquals(listOf(TestRoute.Home), stack.value)
        assertEquals(false, dispatcher.dispatchBack())

        backBinding.dispose()
        stack.push(TestRoute.Search)
        assertEquals(false, dispatcher.dispatchBack())
        assertEquals(listOf(TestRoute.Home, TestRoute.Search), stack.value)

        val history = InMemoryRouteHistory()
        stack.writeToHistory(history, codec, mode = RouteHistoryMode.Replace)
        assertEquals(stack.encodedRoutes(codec), history.current)
        stack.push(TestRoute.Details("99", "summary"))
        stack.writeToHistory(history, codec)
        assertEquals(2, history.snapshots.size)

        stack.replaceAll(TestRoute.Home)
        stack.restoreFromHistory(history, codec)
        assertEquals(listOf(TestRoute.Home, TestRoute.Search, TestRoute.Details("99", "summary")), stack.value)
    }

    @Test
    fun hostBackDispatcherSnapshotsHandlersDuringDispatch() {
        val dispatcher = InMemoryHostBackDispatcher()
        var fallbackCalls = 0
        var lateCalls = 0

        dispatcher.registerBackHandler(
            HostBackRegistration {
                fallbackCalls += 1
                true
            },
        )
        dispatcher.registerBackHandler(
            HostBackRegistration {
                dispatcher.registerBackHandler(
                    HostBackRegistration {
                        lateCalls += 1
                        true
                    },
                )
                false
            },
        )

        assertEquals(true, dispatcher.dispatchBack())
        assertEquals(1, fallbackCalls)
        assertEquals(0, lateCalls)

        assertEquals(true, dispatcher.dispatchBack())
        assertEquals(1, lateCalls)
    }

    @Test
    fun routeHistoryPushAppendsEntriesAndReplaceUpdatesCurrentSnapshot() {
        val history = InMemoryRouteHistory(initial = listOf("home"))

        history.push(listOf("home", "details"))
        history.push(listOf("home", "search"))
        history.replace(listOf("home", "search", "filters"))

        assertEquals(listOf("home", "search", "filters"), history.current)
        assertEquals(
            listOf(
                listOf("home"),
                listOf("home", "details"),
                listOf("home", "search", "filters"),
            ),
            history.snapshots,
        )
    }

    @Test
    fun navHostRendersCurrentBackStackRoute() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val stack = BackStack<TestRoute>(TestRoute.Home)

        fun renderText(): String {
            val node = runtime.render(scope) {
                NavHost(stack) { route ->
                    when (route) {
                        TestRoute.Home -> text("Home")
                        is TestRoute.Details -> text("Details ${route.id}")
                        TestRoute.Search -> text("Search")
                    }
                }
            }.tree
            return (node as HostNode).currentText()
        }

        assertEquals("Home", renderText())
        stack.push(TestRoute.Details("42", "reviews"))
        assertEquals("Details 42", renderText())
    }

    @Test
    fun navHostReportsBackReplaceUnchangedDirectionsAndEscapedDuplicateEntryKeys() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val stack = BackStack<TestRoute>(TestRoute.Home)

        fun renderRoot(): HostNode =
            runtime.render(scope) {
                NavHost(
                    backStack = stack,
                    options = NavOptions(
                        retainPreviousEntries = 2,
                        transition = NavTransition.slide(durationMillis = 240),
                    ),
                    key = "shell/main",
                    routeKey = { route ->
                        when (route) {
                            TestRoute.Home -> "root/home"
                            TestRoute.Search -> "search@global"
                            is TestRoute.Details -> "details:${route.id}"
                        }
                    },
                ) { route ->
                    when (route) {
                        TestRoute.Home -> text("Home")
                        is TestRoute.Details -> text("Details ${route.id}:${route.tab}")
                        TestRoute.Search -> text("Search")
                    }
                }
            }.tree as HostNode

        assertEquals("Initial", renderRoot().props["direction"])

        stack.push(TestRoute.Details("42", "reviews"))
        stack.push(TestRoute.Details("42", "summary"))
        val duplicateKeys = renderRoot()
        assertEquals("Forward", duplicateKeys.props["direction"])
        assertEquals("Slide", duplicateKeys.props["transition"])
        assertEquals("240", duplicateKeys.props["transitionDurationMillis"])
        assertEquals(
            listOf("route:root%2Fhome@0", "route:details%3A42@0", "route:details%3A42@1"),
            duplicateKeys.navEntries().map { entry -> entry.props.getValue("entryKey") },
        )

        stack.pop()
        assertEquals("Back", renderRoot().props["direction"])
        assertEquals("Unchanged", renderRoot().props["direction"])

        stack.replaceAll(TestRoute.Search)
        val replaced = renderRoot()
        assertEquals("Replace", replaced.props["direction"])
        assertEquals("route:search%40global@0", replaced.navEntries().single().props["entryKey"])

        stack.replaceAll(TestRoute.Details("42%beta", "reviews"), TestRoute.Search)
        val longerReplacement = renderRoot()
        assertEquals("Replace", longerReplacement.props["direction"])
        assertEquals(
            listOf("route:details%3A42%25beta@0", "route:search%40global@0"),
            longerReplacement.navEntries().map { entry -> entry.props.getValue("entryKey") },
        )
    }

    @Test
    fun navLazyEachUsesRememberedScrollStateInsideNavEntry() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val stack = BackStack<TestRoute>(TestRoute.Home)
        lateinit var defaultScroll: MutableCell<LazyListState>

        val node = runtime.render(scope) {
            NavHost(stack) {
                defaultScroll = rememberNavScrollState(key = "default-scroll")
                navLazyEach(
                    items = lazyItems(listOf("Home", "Details", "Search"), estimatedSize = 3),
                    key = { item -> item },
                    initialScroll = LazyListState(firstVisibleIndex = 1, visibleCount = 1),
                ) { item ->
                    text(item)
                }
            }
        }.tree as HostNode

        assertEquals(listOf("Details"), node.navEntryAllTexts())
        assertEquals(0, defaultScroll.value.firstVisibleIndex)
        assertEquals(LazyListState(), defaultScroll.value)
    }

    @Test
    fun navLazyEachUsesDefaultScrollAndRetentionArguments() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val stack = BackStack<TestRoute>(TestRoute.Home)

        val node = runtime.render(scope) {
            NavHost(stack) {
                navLazyEach(
                    items = lazyItems(listOf("Home", "Details"), estimatedSize = 2),
                    key = { item -> item },
                ) { item ->
                    text(item)
                }
            }
        }.tree as HostNode

        assertEquals(listOf("Home", "Details"), node.navEntryAllTexts())
    }

    @Test
    fun navHostRetainsPreviousEntryAndExposesTransitionMetadata() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val stack = BackStack<TestRoute>(TestRoute.Home)

        fun renderRoot(): HostNode =
            runtime.render(scope) {
                NavHost(
                    backStack = stack,
                    options = NavOptions(transition = NavTransition.fade(durationMillis = 120)),
                ) { route ->
                    when (route) {
                        TestRoute.Home -> text("Home")
                        is TestRoute.Details -> text("Details ${route.id}")
                        TestRoute.Search -> text("Search")
                    }
                }
            }.tree as HostNode

        val initial = renderRoot()
        assertEquals("Initial", initial.props["direction"])
        assertEquals(listOf("Home"), initial.navEntryTexts())
        assertEquals("true", initial.navEntries().single().props["current"])

        stack.push(TestRoute.Details("42", "reviews"))
        val pushed = renderRoot()
        assertEquals("Forward", pushed.props["direction"])
        assertEquals("Fade", pushed.props["transition"])
        assertEquals("120", pushed.props["transitionDurationMillis"])
        assertEquals(listOf("Home", "Details 42"), pushed.navEntryTexts())
        assertEquals("false", pushed.navEntries().first().props["current"])
        assertEquals("true", pushed.navEntries().first().props["retained"])
        assertEquals("true", pushed.navEntries().last().props["current"])
    }

    @Test
    fun navHostDropsNonPersistentRouteStateOutsideRetentionWindowButKeepsScrollState() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val stack = BackStack<TestRoute>(TestRoute.Home)
        scrollRouteCounter = null
        scrollRouteScroll = null

        fun renderRoot(): HostNode =
            runtime.render(scope) {
                NavHost(stack) { route -> ScrollRestoreRoute(route) }
            }.tree as HostNode

        renderRoot()
        scrollRouteCounter!!.value = 7
        val scroll = scrollRouteScroll!!
        scroll.value = scroll.value.scrollTo(firstVisibleIndex = 4)
        assertEquals("Home 7:4", renderRoot().currentText())

        stack.push(TestRoute.Details("42", "reviews"))
        renderRoot()
        stack.push(TestRoute.Search)
        val search = renderRoot()
        assertEquals(listOf("Details 42", "Search"), search.navEntryTexts())

        stack.pop()
        val details = renderRoot()
        assertEquals(listOf("Home 0:4", "Details 42"), details.navEntryTexts())
    }

    @Test
    fun navHostDisposesDroppedEntryStateWhenNestedInKeyScope() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val stack = BackStack<TestRoute>(TestRoute.Home)
        disposalRouteCounter = null

        fun renderRoot(): HostNode =
            runtime.render(scope) {
                ShellNavHost(stack) { route -> DisposalRoute(route) }
            }.tree as HostNode

        renderRoot()
        disposalRouteCounter!!.value = 7
        assertEquals("Home 7", renderRoot().currentText())

        stack.push(TestRoute.Details("42", "reviews"))
        renderRoot()
        stack.push(TestRoute.Search)
        assertEquals(listOf("Details 42", "Search"), renderRoot().navEntryTexts())

        stack.pop()
        assertEquals(listOf("Home 0", "Details 42"), renderRoot().navEntryTexts())
    }
}

// Route components live at the top level: slot DSL is only legal lexically inside
// @UiComponent functions, so tests parameterize them through file-level vars.

private var scrollRouteCounter: MutableCell<Int>? = null
private var scrollRouteScroll: MutableCell<LazyListState>? = null

@UiComponent(skippable = false)
private fun ComponentScope.ScrollRestoreRoute(route: TestRoute) {
    when (route) {
        TestRoute.Home -> {
            val counter = state { 0 }
            val scroll = rememberNavScrollState(initial = LazyListState())
            scrollRouteCounter = counter
            scrollRouteScroll = scroll
            text("Home ${counter.value}:${scroll.value.firstVisibleIndex}")
        }

        is TestRoute.Details -> text("Details ${route.id}")
        TestRoute.Search -> text("Search")
    }
}

private var disposalRouteCounter: MutableCell<Int>? = null

@UiComponent(skippable = false)
private fun ComponentScope.DisposalRoute(route: TestRoute) {
    when (route) {
        TestRoute.Home -> {
            val counter = state { 0 }
            disposalRouteCounter = counter
            text("Home ${counter.value}")
        }

        is TestRoute.Details -> text("Details ${route.id}")
        TestRoute.Search -> text("Search")
    }
}

/**
 * Nests the NavHost inside an enclosing keyed scope. The content lambda is passed through
 * as a parameter: it must be region-wrapped at its literal site (the test method), because
 * literals passed to component calls inside component bodies are not wrapped.
 */
@UiComponent(skippable = false)
private fun ComponentScope.ShellNavHost(
    stack: BackStack<TestRoute>,
    content: @UiComponent ComponentScope.(TestRoute) -> Unit,
) {
    keyed("shell") {
        NavHost(stack, content = content)
    }
}

@Serializable
private sealed interface TestRoute : Route {
    @Serializable
    data object Home : TestRoute

    @Serializable
    data class Details(val id: String, val tab: String) : TestRoute

    @Serializable
    data object Search : TestRoute
}

private fun HostNode.navEntries(): List<HostNode> =
    children.map { it as HostNode }

private fun HostNode.navEntryTexts(): List<String> =
    navEntries().map { entry -> (entry.children.single() as TextNode).value }

private fun HostNode.navEntryAllTexts(): List<String> =
    navEntries().flatMap { entry -> entry.children.map { child -> (child as TextNode).value } }

private fun HostNode.currentText(): String =
    (navEntries().single { it.props["current"] == "true" }.children.single() as TextNode).value
