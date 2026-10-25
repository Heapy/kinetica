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

Host tag names must come from trusted application code. A tag that fails `isValidHtmlName`,
such as `"div class=x"`, causes `toSafeHtml()` to throw `IllegalArgumentException` instead of
silently substituting a `div`. This is a syntax check, not a list of permitted elements;
names such as `script` still pass it.

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

For an expected business validation failure, a handler can throw `ServerActionRejection`.
Its message is returned verbatim to the client, so it must contain only client-safe text.
Other handler exceptions and output serialization failures still return a generic error.

Supply both token verifiers: an omitted verifier rejects every request. The generated
`kineticaGeneratedServerActionDispatcher(verifyCapabilityToken, verifyCsrfToken)` factory
requires both arguments. It replaces the generated `KineticaGeneratedServerActionDispatcher`
property; update usages to construct the dispatcher with your application's verifiers.

### Validation and authorization

Schema validation and decoding check the payload's structure and Kotlin types, not all business
rules. For example, `quantity: Int` can still be negative. Validate domain constraints inside the
handler before changing state; use `ServerActionRejection` for a client-safe validation message.

The dispatcher checks tokens before looking up the requested action. Its verifier callbacks receive
only their respective tokens, not the action ID or payload. They do not automatically authorize
access to a particular action or resource: the handler must check the caller's permissions using
trusted application context. An action does not become inaccessible merely because it is absent
from the current UI. Calling a `ServerActionStub` directly bypasses the dispatcher's token checks.

The reference demos use fixed capability and CSRF tokens included in the client bundle. Anyone can
read and reuse them; they are demonstration values, not authentication or CSRF protection.
Applications must provide their own authentication, authorization, and CSRF protection. The
capability token is sent by the client in `ServerActionRequest`; it is not a server-only secret.

### HTTP integration

`readBoundedRequestBody` caps JVM request bodies at 64 KiB by default. Pass its result to
`dispatchHttp`: an oversized body produces HTTP 413, a malformed envelope produces 400, and a
dispatched action produces 200 even when its result is `ServerActionResponse.Failure`.
Serve these responses as `application/json` with `Cache-Control: no-store`. Unexpected faults
propagate to the HTTP server; log their details server-side and return a generic error body.

The reference servers apply `KineticaSecurityHeaders` and process requests on virtual threads.
Neither virtual threads nor the byte limit imposes a request-read timeout; configure time and
connection limits in your HTTP deployment as well.

## The manifest

<!-- code: kinetica-runtime/src/ServerComponents.kt (ClientComponentManifest, ClientComponentRegistration), kinetica-runtime/src/Annotations.kt (ClientComponent, ServerAction) -->

`ClientComponentManifest` declares which client components and actions exist
(`componentId → componentFqName`, props types, action registrations) — the contract both sides
agree on, and what the [compiler plugin](/docs/compiler-plugin) is designed to generate from
`@ClientComponent` / `@ServerAction` annotations.
