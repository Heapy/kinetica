package io.heapy.kinetica.router

import io.heapy.kinetica.BackStack
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Disposable
import io.heapy.kinetica.KineticaJson
import io.heapy.kinetica.LazyItems
import io.heapy.kinetica.LazyListState
import io.heapy.kinetica.MutableCell
import io.heapy.kinetica.RetainPolicy
import io.heapy.kinetica.Route
import io.heapy.kinetica.RouteCodec
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.SlotId
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.context
import io.heapy.kinetica.disposeKeyScope
import io.heapy.kinetica.host
import io.heapy.kinetica.keyed
import io.heapy.kinetica.layoutEffect
import io.heapy.kinetica.lazyEach
import io.heapy.kinetica.provide
import io.heapy.kinetica.read
import io.heapy.kinetica.state
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
public enum class NavDirection {
    Initial,
    Unchanged,
    Forward,
    Back,
    Replace,
}

@Serializable
public enum class NavTransitionKind {
    None,
    Fade,
    Slide,
}

@Serializable
public data class NavTransition(
    val kind: NavTransitionKind = NavTransitionKind.None,
    val durationMillis: Long = 0,
) {
    init {
        require(durationMillis >= 0) { "durationMillis must be non-negative." }
    }

    public companion object {
        public val None: NavTransition = NavTransition()

        public fun fade(durationMillis: Long): NavTransition =
            NavTransition(NavTransitionKind.Fade, durationMillis)

        public fun slide(durationMillis: Long): NavTransition =
            NavTransition(NavTransitionKind.Slide, durationMillis)
    }
}

@Serializable
public data class NavOptions(
    val retainPreviousEntries: Int = 1,
    val restoreScroll: Boolean = true,
    val transition: NavTransition = NavTransition.None,
) {
    init {
        require(retainPreviousEntries >= 0) { "retainPreviousEntries must be non-negative." }
    }
}

@Serializable
public data class NavEntry(
    val key: String,
    val routeKey: String,
    val stackIndex: Int,
    val isCurrent: Boolean,
    val isRetained: Boolean,
    val direction: NavDirection,
    val transition: NavTransition,
    val restoreScroll: Boolean,
)

private val NavEntryContext = context<NavEntry?>(null, name = "NavEntry")

/** Scope key of the enclosing nav entry; addresses persistence-scoped state per entry. */
private val NavEntryScopeKeyContext = context<String?>(null, name = "NavEntryScopeKey")

@UiComponent
public fun <R : Route> ComponentScope.NavHost(
    backStack: BackStack<R>,
    options: NavOptions = NavOptions(),
    key: Any = "default",
    routeKey: (R) -> Any = { it },
    content: @UiComponent ComponentScope.(R) -> Unit,
) {
    val routes = backStack.value
    require(routes.isNotEmpty()) { "BackStack cannot be empty." }

    val hostKey = key.toNavKeySegment()
    val routeKeys = routes.map { route -> routeKey(route).toNavKeySegment() }
    val entryKeys = routeKeys.toEntryKeys(prefix = "route")
    // The host-keyed frame keeps two NavHost instances with different keys from ever
    // aliasing state, matching the old "navHost:<hostKey>:…" string-keyed slots.
    keyed("navHost:$hostKey") {
        val previousStackKeys = state { mutableListOf<String>() }
        val retainedScopeKeys = state { mutableSetOf<String>() }
        val direction = detectNavDirection(previousStackKeys.value, routeKeys)
        val firstRenderedIndex = (routes.lastIndex - options.retainPreviousEntries).coerceAtLeast(0)
        val renderedIndices = firstRenderedIndex..routes.lastIndex
        val activeScopeKeys = renderedIndices.map { index -> navEntryScopeKey(hostKey, entryKeys[index]) }.toSet()
        val previouslyRetainedScopeKeys = retainedScopeKeys.value.toSet()

        previouslyRetainedScopeKeys
            .filterNot(activeScopeKeys::contains)
            .forEach { scopeKey -> disposeKeyScope(scopeKey, keepPersistentSlots = options.restoreScroll) }

        layoutEffect {
            retainedScopeKeys.value.clear()
            retainedScopeKeys.value.addAll(activeScopeKeys)
            previousStackKeys.value.clear()
            previousStackKeys.value.addAll(routeKeys)
        }

        host(
            tag = "navHost",
            props = mapOf(
                "retainPreviousEntries" to options.retainPreviousEntries.toString(),
                "restoreScroll" to options.restoreScroll.toString(),
                "direction" to direction.name,
                "transition" to options.transition.kind.name,
                "transitionDurationMillis" to options.transition.durationMillis.toString(),
            ),
            semantics = Semantics(testTag = "nav-host"),
            key = hostKey,
        ) {
            // A plain for loop (not forEach) keeps the keyed() calls on the compiler's
            // ordinal-assignment path; iterations are disambiguated by the scope key.
            for (index in renderedIndices) {
                val entryKey = entryKeys[index]
                val scopeKey = navEntryScopeKey(hostKey, entryKey)
                val isCurrent = index == routes.lastIndex
                val entry = NavEntry(
                    key = entryKey,
                    routeKey = routeKeys[index],
                    stackIndex = index,
                    isCurrent = isCurrent,
                    isRetained = !isCurrent,
                    direction = direction,
                    transition = options.transition,
                    restoreScroll = options.restoreScroll,
                )
                val route = routes[index]
                keyed(scopeKey) {
                    provide(NavEntryContext, entry) {
                        provide(NavEntryScopeKeyContext, scopeKey) {
                            host(
                                tag = "navEntry",
                                props = entry.props(),
                                semantics = Semantics(testTag = "nav-entry:$entryKey"),
                                key = entryKey,
                            ) {
                                content(route)
                            }
                        }
                    }
                }
            }
        }
    }
}

public fun ComponentScope.currentNavEntry(): NavEntry? =
    read(NavEntryContext)

@UiComponent
public fun ComponentScope.rememberNavScrollState(
    key: String = "default",
    initial: LazyListState = LazyListState(),
): MutableCell<LazyListState> {
    val entry = currentNavEntry()
    val entryScopeKey = read(NavEntryScopeKeyContext)
    val persistent = entry?.restoreScroll == true
    val slotKey = "nav-scroll:${key.toNavKeySegment()}"
    // Persistence-addressed identity: survives disposeKeyScope(scopeKey, keepPersistentSlots =
    // true) because the SlotId registry retains the last cell value, which reseeds the slot
    // when the entry renders again.
    val slotId = SlotId(
        moduleId = "kinetica-router",
        functionFqName = "io.heapy.kinetica.router.rememberNavScrollState",
        declarationOrdinal = 0,
        disambiguator = "${entryScopeKey ?: "no-entry"}:$slotKey",
    )
    var cell: MutableCell<LazyListState>? = null
    keyed(slotKey) {
        val restored = if (persistent) readSlot<LazyListState>(slotId) else null
        cell = state(slotId = slotId, persistent = persistent) { restored ?: initial }
    }
    return checkNotNull(cell) { "rememberNavScrollState: keyed content did not run." }
}

@UiComponent
public fun <T> ComponentScope.navLazyEach(
    items: LazyItems<T>,
    key: (T) -> Any,
    scrollKey: String = "default",
    retain: RetainPolicy = RetainPolicy.Keyed,
    initialScroll: LazyListState = LazyListState(),
    content: @UiComponent ComponentScope.(T) -> Unit,
) {
    val scrollState = rememberNavScrollState(scrollKey, initialScroll)
    lazyEach(
        items = items,
        key = key,
        retain = retain,
        state = scrollState.value,
    ) { item ->
        content(item)
    }
}

public class StringRouteCodec<R : Route>(
    private val encodeValue: (R) -> String,
    private val decodeValue: (String) -> R,
) : RouteCodec<R> {
    override fun encode(route: R): String = encodeValue(route)

    override fun decode(value: String): R = decodeValue(value)
}

public fun <R : Route> routeCodec(
    encode: (R) -> String,
    decode: (String) -> R,
): RouteCodec<R> = StringRouteCodec(encode, decode)

public class JsonRouteCodec<R : Route>(
    private val serializer: KSerializer<R>,
    private val json: Json = KineticaJson,
) : RouteCodec<R> {
    override fun encode(route: R): String =
        json.encodeToString(serializer, route)

    override fun decode(value: String): R =
        json.decodeFromString(serializer, value)
}

public fun <R : Route> jsonRouteCodec(
    serializer: KSerializer<R>,
    json: Json = KineticaJson,
): RouteCodec<R> = JsonRouteCodec(serializer, json)

public fun <R : Route> BackStack<R>.encodedRoutes(codec: RouteCodec<R>): List<String> =
    value.map(codec::encode)

public fun <R : Route> BackStack<R>.replaceFromEncoded(
    codec: RouteCodec<R>,
    encodedRoutes: Iterable<String>,
) {
    val decodedRoutes = encodedRoutes.map(codec::decode)
    require(decodedRoutes.isNotEmpty()) { "BackStack cannot be empty." }
    value = decodedRoutes
}

public data class DeepLink<R : Route>(
    val encoded: String,
    val route: R,
)

public fun <R : Route> RouteCodec<R>.deepLink(route: R): DeepLink<R> =
    DeepLink(encoded = encode(route), route = route)

public fun <R : Route> RouteCodec<R>.parseDeepLink(encoded: String): DeepLink<R> =
    DeepLink(encoded = encoded, route = decode(encoded))

public fun interface HostBackRegistration {
    public fun onBack(): Boolean
}

public interface HostBackDispatcher {
    public fun registerBackHandler(handler: HostBackRegistration): Disposable
    public fun dispatchBack(): Boolean
}

public class InMemoryHostBackDispatcher : HostBackDispatcher {
    private val lock = SynchronizedObject()
    private val handlers = mutableListOf<HostBackRegistration>()

    override fun registerBackHandler(handler: HostBackRegistration): Disposable {
        synchronized(lock) {
            handlers += handler
        }
        return Disposable {
            synchronized(lock) {
                handlers -= handler
            }
        }
    }

    override fun dispatchBack(): Boolean =
        synchronized(lock) { handlers.toList() }
            .asReversed()
            .any { handler -> handler.onBack() }
}

public enum class RouteHistoryMode {
    Push,
    Replace,
}

public interface RouteHistory {
    public val current: List<String>
    public fun push(encodedRoutes: List<String>)
    public fun replace(encodedRoutes: List<String>)
}

public class InMemoryRouteHistory(
    initial: List<String> = emptyList(),
) : RouteHistory {
    private val entries = mutableListOf(initial)
    private var cursor = 0

    override val current: List<String>
        get() = entries[cursor]

    public val snapshots: List<List<String>>
        get() = entries.toList()

    override fun push(encodedRoutes: List<String>) {
        entries += encodedRoutes
        cursor = entries.lastIndex
    }

    override fun replace(encodedRoutes: List<String>) {
        entries[cursor] = encodedRoutes
    }
}

public fun <R : Route> BackStack<R>.bindHostBack(dispatcher: HostBackDispatcher): Disposable =
    dispatcher.registerBackHandler(HostBackRegistration { pop() })

public fun <R : Route> BackStack<R>.writeToHistory(
    history: RouteHistory,
    codec: RouteCodec<R>,
    mode: RouteHistoryMode = RouteHistoryMode.Push,
) {
    val encoded = encodedRoutes(codec)
    when (mode) {
        RouteHistoryMode.Push -> history.push(encoded)
        RouteHistoryMode.Replace -> history.replace(encoded)
    }
}

public fun <R : Route> BackStack<R>.restoreFromHistory(
    history: RouteHistory,
    codec: RouteCodec<R>,
) {
    if (history.current.isNotEmpty()) {
        replaceFromEncoded(codec, history.current)
    }
}

private fun detectNavDirection(
    previous: List<String>,
    current: List<String>,
): NavDirection =
    when {
        previous.isEmpty() -> NavDirection.Initial
        previous == current -> NavDirection.Unchanged
        current.size > previous.size && current.take(previous.size) == previous -> NavDirection.Forward
        current.size < previous.size && previous.take(current.size) == current -> NavDirection.Back
        else -> NavDirection.Replace
    }

private fun List<String>.toEntryKeys(prefix: String): List<String> {
    val counts = mutableMapOf<String, Int>()
    return map { routeKey ->
        val count = counts.getOrElse(routeKey) { 0 }
        counts[routeKey] = count + 1
        "$prefix:$routeKey@$count"
    }
}

private fun navEntryScopeKey(hostKey: String, entryKey: String): String =
    "navHost:$hostKey:entry:$entryKey"

private fun NavEntry.props(): Map<String, String> =
    mapOf(
        "entryKey" to key,
        "routeKey" to routeKey,
        "stackIndex" to stackIndex.toString(),
        "current" to isCurrent.toString(),
        "retained" to isRetained.toString(),
        "direction" to direction.name,
        "transition" to transition.kind.name,
        "transitionDurationMillis" to transition.durationMillis.toString(),
        "restoreScroll" to restoreScroll.toString(),
    )

private fun Any.toNavKeySegment(): String =
    toString()
        .replace("%", "%25")
        .replace("/", "%2F")
        .replace(":", "%3A")
        .replace("@", "%40")
