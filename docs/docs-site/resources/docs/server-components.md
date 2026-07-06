# Server components

<!-- code: kinetica-runtime/src/ServerComponents.kt, docs/docs-site/src/main.kt (server-components demo) -->

Because a rendered UI is a serializable `Node` tree, "server components" is not a separate
framework — it is the same tree, produced on the JVM and delivered four ways: safe HTML,
hydration metadata, streamed patches, and typed server actions.

::: demo-link /examples/server-components

The demo above is hosted by this very server. View-source it: server HTML, a hydration plan, a
client island, and a streamed update on one page.

## 1. Safe HTML

<!-- code: kinetica-runtime/src/Html.kt (toSafeHtml), docs/docs-site/src/Layout.kt (renderDocPage) -->

```kotlin
val tree = KineticaRuntime().render { ProductPage() }.tree
val html = tree.toSafeHtml()
```

`toSafeHtml()` serializes a tree with escaping and URL-scheme sanitization built in. Every page
of this documentation site is produced exactly this way.

## 2. Client islands & hydration

<!-- code: kinetica-runtime/src/Node.kt (ClientRef), kinetica-runtime/src/ServerComponents.kt (hydrationPlan, encodeHydrationPlan), samples/server-components-client/src/main.kt -->

Mark an interactive region with a `ClientRef` — a serializable pointer to a client component
plus its serializable props:

```kotlin
emit(ClientRef(
    componentId = ADD_TO_CART_COMPONENT_ID,
    props = JsonObject(mapOf("productId" to JsonPrimitive("runtime-license"))),
))
```

The server ships the tree's **hydration plan** alongside the HTML:

```kotlin
val transport = KineticaServerTransport()
val plan = transport.encodeHydrationPlan(tree.hydrationPlan())
// -> <script id="kinetica-hydration-plan" type="application/json">…</script>
```

A Kotlin/JS client reads the plan, finds the island placeholders, and mounts real Kinetica
browser apps into them — the rest of the page stays static HTML with zero client JS cost.

## 3. Streaming

<!-- code: kinetica-runtime/src/ServerComponents.kt (toServerRenderStream, ServerRenderChunk, encodeChunk), docs/docs-site/src/main.kt (GET /stream) -->

Slow parts of a page defer and stream in as **patches against the tree** — the diff format is
the same serializable value used everywhere else:

```kotlin
tree.toServerRenderStream(
    subtrees = listOf(
        ServerRenderDeferredSubtree(path = listOf(3)) {
            delay(100)                       // load recommendations…
            FragmentNode(children = listOf(TextNode("Recommended for you: …")))
        },
    ),
    manifest = manifest,
)   // -> List<ServerRenderChunk>: the initial chunk, then a Patch per resolved subtree
```

Each chunk serializes with `transport.encodeChunk(chunk)`; the demo endpoint joins them with
newlines into NDJSON, which the client applies as path-addressed subtree replacements.

## 4. Typed server actions

<!-- code: kinetica-runtime/src/ServerComponents.kt (serverActionStub, KineticaServerActionDispatcher), docs/docs-site/src/main.kt (POST /actions) -->

Client islands call back into the server through declared, schema-validated actions:

```kotlin
val dispatcher = KineticaServerActionDispatcher(
    stubs = listOf(
        serverActionStub(
            registration = ServerActionRegistration(
                actionId = ADD_TO_CART_ACTION_ID,
                functionFqName = "shop.addToCart",
                invalidates = listOf("cart"),
            ),
            inputSerializer = AddToCartInput.serializer(),
            outputSerializer = AddToCartResult.serializer(),
        ) { input -> addToCart(input) },
    ),
    verifyCapabilityToken = { it.value == expectedCapability },
    verifyCsrfToken = { it?.value == expectedCsrf },
)

// POST /actions/{id} ->
dispatcher.dispatch(transport.decodeActionRequest(body))
```

Inputs are validated against the serializer-derived schema before your handler runs; capability
and CSRF tokens are checked by the dispatcher; handler exceptions return a generic failure
message (no stack-trace leaks); `invalidates` keys plug into the
[resource loop](/docs/resources) on the client.

### Hardening for production

The schema validator only checks a payload's *shape* (object/array/string/number/boolean and
required fields) — never its *ranges* or *domain*. A handler receives a typed value, but a
`quantity: Int` field will accept negatives or `Int.MAX_VALUE`. Validate business invariants
inside the handler and return a typed `Failure` for out-of-range input; do not trust the schema
alone.

The dispatcher **fails closed by default**: omit `verifyCapabilityToken` / `verifyCsrfToken` and
every action is rejected. The demo wires them to fixed constants for brevity, but a fixed string
shipped in the client bundle is public — it is not authentication. Real deployments must:

- issue a **per-session, unguessable** CSRF token (a random ≥128-bit value bound to the session,
  checked server-side), not a shared constant;
- treat the capability token as a secret held server-side, never compiled into client code;
- serve over HTTPS, and consider `SameSite=Lax`/`Strict` cookies plus the
  [double-submit cookie](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
  pattern if you store the CSRF token in a cookie.

The reference HTTP servers (`samples/server-components`, `docs/docs-site`) demonstrate the
hardening baseline: bounded request bodies, a fixed-size request thread pool, synchronized shared
state, generic error bodies with stack traces logged server-side only, and `Content-Security-Policy`
/ `X-Content-Type-Options` / `X-Frame-Options` headers on every response.

## The manifest

<!-- code: kinetica-runtime/src/ServerComponents.kt (ClientComponentManifest, ClientComponentRegistration), kinetica-runtime/src/Annotations.kt (ClientComponent, ServerAction) -->

`ClientComponentManifest` declares which client components and actions exist
(`componentId → componentFqName`, props types, action registrations) — the contract both sides
agree on, and what the [compiler plugin](/docs/compiler-plugin) is designed to generate from
`@ClientComponent` / `@ServerAction` annotations.
